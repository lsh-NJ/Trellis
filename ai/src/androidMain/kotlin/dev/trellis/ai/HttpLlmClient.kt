package dev.trellis.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 安卓上的 `/chat/completions` 客户端。
 *
 * 和桌面版 `HttpLlmClient` **协议一致、文案一致**，差别只在传输：
 * 安卓的 `android.jar` 里没有 `java.net.http`（JDK 11+ 的模块），所以走
 * `HttpURLConnection`。
 *
 * ### 为什么把实现整份拷过来，而不是抽一个公共的 JSON 层
 *
 * 桌面版把请求体拼装、SSE 切分、错误文案都写在类内部（`requestBody` /
 * `extractDelta` / `extractReply`）。抽公共层意味着改动桌面端已经在跑的代码；
 * 而这份实现只在安卓上跑，拷一份的代价是 300 行，收益是**桌面端零风险**。
 * 两边的分歧由 `HttpLlmClientProtocolTest` 之外的“同一份 JSON 形状”约束：
 * 字段名、`response_format`、图片数组的格式都逐字对齐。
 *
 * ### 超时与断流
 *
 * 桌面版要自己养一个看门狗线程，因为 `HttpRequest.timeout` 只管到响应头。
 * `HttpURLConnection` 反而更好办：`readTimeout` 天生是「两次读之间的最大间隔」，
 * 正好就是这里要的语义。
 */
class HttpLlmClient(
    private val config: LlmConfig,
    private val timeoutSeconds: Long = 90,
    private val temperature: Double = 0.2,
    private val maxTokens: Int = 2048,
    private val streamIdleSeconds: Long = 60,
    private val completeIdleSeconds: Long = 120,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(messages: List<LlmMessage>): String =
        completeWithReasoning(messages).content

    override suspend fun completeWithReasoning(messages: List<LlmMessage>): LlmReply {
        val body = requestBody(messages, stream = false, jsonMode = true)
        val (status, text) = withContext(Dispatchers.IO) { post(body, stream = false) }
        if (status !in 200..299) {
            throw LlmException("模型接口返回 " + status + "：" + text.take(500))
        }
        return extractReply(text)
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        jsonMode: Boolean,
    ): String {
        val body = requestBody(messages, stream = true, jsonMode = jsonMode)
        val full = StringBuilder()

        withContext(Dispatchers.IO) {
            val connection = open(body, stream = true, readTimeoutSeconds = streamIdleSeconds)
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    val text = runCatching {
                        connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                    }.getOrNull().orEmpty()
                    throw LlmException("模型接口返回 " + status + "：" + text.take(500))
                }
                val input = connection.inputStream
                // 用户点「停止生成」时把阻塞中的读踢醒：协程取消打断不了 InputStream，
                // 只能挂一个「取消时顺手 close」的兄弟协程。
                val closeOnCancel = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        runCatching { input.close() }
                    }
                }
                try {
                    input.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data.isEmpty()) continue
                            if (data == "[DONE]") break
                            val delta = runCatching { extractDelta(data) }.getOrNull() ?: continue
                            // 思考内容单独走 onReasoning；空白分片不送。
                            delta.reasoning?.takeIf { it.isNotBlank() }?.let { onReasoning(it) }
                            val chunk = delta.content
                            if (chunk.isNullOrEmpty()) continue
                            full.append(chunk)
                            onDelta(chunk)
                        }
                    }
                } catch (e: IOException) {
                    // 用户点了停止：这是取消，不是断流，不能报成「网络断了」。
                    ensureActive()
                    if (e is SocketTimeoutException) {
                        throw LlmException(
                            "模型响应超时：" + streamIdleSeconds + " 秒没有新内容，网络或代理可能断了。可以重试这一轮，或先点「停止生成」。",
                        )
                    }
                    throw e
                } finally {
                    closeOnCancel.cancel()
                    runCatching { input.close() }
                }
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        return full.toString()
    }

    /** 非流式：读完整响应体。 */
    private fun post(body: String, stream: Boolean): Pair<Int, String> {
        val connection = open(body, stream = stream, readTimeoutSeconds = completeIdleSeconds)
        return try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                .orEmpty()
            status to text
        } catch (e: SocketTimeoutException) {
            throw LlmException(
                "模型响应超时：" + completeIdleSeconds + " 秒没有新内容，网络或代理可能断了。可以重试这一轮。",
            )
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun open(body: String, stream: Boolean, readTimeoutSeconds: Long): HttpURLConnection {
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = (timeoutSeconds * 1000).toInt().coerceAtMost(Int.MAX_VALUE)
            // 关键：这是「两次读之间的最大间隔」，正好对应桌面版那个看门狗要防的断流。
            readTimeout = (readTimeoutSeconds * 1000).toInt().coerceAtMost(Int.MAX_VALUE)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + config.apiKey)
            setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return connection
    }

    /** 请求体。字段与桌面版逐字对齐，见类注释。 */
    internal fun requestBody(
        messages: List<LlmMessage>,
        stream: Boolean,
        jsonMode: Boolean,
    ): String = buildJsonObject {
        put("model", config.model)
        put("temperature", temperature)
        put("stream", stream)
        if (jsonMode) {
            put("response_format", buildJsonObject { put("type", "json_object") })
        }
        put("max_tokens", maxTokens)
        putJsonArray("messages") {
            for (message in messages) {
                addJsonObject {
                    put("role", message.role.name.lowercase())
                    if (message.images.isEmpty()) {
                        put("content", message.content)
                    } else {
                        putJsonArray("content") {
                            if (message.content.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", message.content)
                                }
                            }
                            for (url in message.images) {
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", url) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }.toString()

    /** 从 `choices[0].message` 取正文与思考（DeepSeek 的 `reasoning_content`）。 */
    private fun extractReply(raw: String): LlmReply {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray
        if (choices.isNullOrEmpty()) {
            throw LlmException("模型返回里没有 choices：" + raw.take(300))
        }
        val message = choices[0].jsonObject["message"] as? JsonObject
            ?: throw LlmException("模型返回里没有 message：" + raw.take(300))
        val contentElement = message["content"]
            ?: throw LlmException("模型返回里没有 content：" + raw.take(300))
        // 视觉模型有时把 content 回成 [{type:"text", text:"..."}]，这里摊平。
        val content = flattenText(contentElement) ?: ""
        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        return LlmReply(content = content, reasoning = reasoning)
    }

    /** SSE 分片：正文 `choices[0].delta.content` 与思考 `delta.reasoning_content`。 */
    private data class SseDelta(val content: String?, val reasoning: String?)

    private fun extractDelta(raw: String): SseDelta? {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null
        return SseDelta(
            content = flattenText(delta["content"]),
            reasoning = flattenText(delta["reasoning_content"]),
        )
    }

    /** content 可能是字符串，也可能是 `[{type:"text", text:"..."}]` 数组；空数组算没有。 */
    private fun flattenText(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonArray -> element.mapNotNull { part ->
            (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
        }.joinToString("").ifEmpty { null }
        else -> element.jsonPrimitive.content
    }
}
