package dev.trellis.ai

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * OpenAI 兼容的 `/chat/completions` 客户端。
 *
 * 用 JDK 自带的 [java.net.http.HttpClient]，**不引任何网络库** ——
 * 一个 HTTP POST 不值得为它加一个依赖，尤其是在这个网络环境下
 * （多一个依赖就多一处可能拉不下来的东西）。
 *
 * ### 为什么 temperature 默认这么低
 *
 * 0.2。我们不是在创作文案，是在**生成结构化数据**：
 * 同一个请求应该长出同一份插件。高温只会让它在「格式对了」和「格式又崩了」之间随机跳。
 *
 * ### 两种调用
 *
 * - [complete]：一次性拿完整文本。开了 JSON mode，给插件生成/工具循环用。
 * - [stream]：SSE 流式，边生成边吐增量。给对话界面用，**不开 JSON mode**
 *   （对话是给人看的自由文本，不是给机器解析的 JSON）。
 *
 * 两者都切到 [Dispatchers.IO] 再发请求：`HttpClient.send` 是阻塞的，
 * 直接在主线程上发会把整个界面冻住，流式更是要冻到最后一个字。
 */
class HttpLlmClient(
    private val config: LlmConfig,
    private val timeoutSeconds: Long = 90,
    private val temperature: Double = 0.2,
    /**
     * 输出上限。
     *
     * 不设的话，一个话痨模型可能把 JSON 写到一半就被截断 ——
     * 而截断的 JSON 报错长得像「模型不会写 JSON」，查起来很费劲。
     *
     * 2026-11-08 从 2048 提到 8192：打开思考后 reasoning tokens 也算在
     * completion 里（DeepSeek 文档），2048 会把正文/JSON 直接挤没。
     */
    private val maxTokens: Int = 8192,
    /**
     * 流式响应「多久没有新内容」就判定断流，兜底用。
     *
     * `HttpRequest.timeout` 只管到响应头：SSE 的 body 一旦开始回来、中途网络/代理断掉，
     * 读循环会永远等下一行 —— 界面上就是「运行中」一直转圈，用户只能强退。
     * 有了这个看门狗，断流时会抛 [LlmException]，聊天页显示「本轮出错」并停下来。
     */
    private val streamIdleSeconds: Long = 60,
    /**
     * 非流式请求等待完整 body 的「多久没有新字节」上限。
     *
     * 和流式同病：`HttpRequest.timeout` 只管到响应头，真断流时 `ofString` 也会永远等下去。
     * 模型生成一段长 JSON 可能要几十秒，所以这里比流式宽。
     */
    private val completeIdleSeconds: Long = 120,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 要不要在请求体里带 `thinking={"type":"enabled"}`。
     *
     * 只有 DeepSeek 认识这个字段；别的 OpenAI 兼容服务收到未知字段可能直接 400，
     * 所以默认按地址判断，`llm.json` 里写 `thinking: true/false` 可以覆盖。
     * 实测（2026-11-08）：不带这个字段时 DeepSeek 默认**不开**思考，
     * 返回的 reasoning_content 是空的 —— 界面上自然也就没有思考行。
     */
    private val thinkingEnabled: Boolean =
        config.thinking ?: config.endpoint.contains("deepseek", ignoreCase = true)

    override suspend fun complete(messages: List<LlmMessage>): String =
        completeWithReasoning(messages).content

    override suspend fun completeWithReasoning(messages: List<LlmMessage>): LlmReply {
        val request = requestFor(messages, stream = false, jsonMode = true)
        val (status, body) = withContext(Dispatchers.IO) { readResponse(request, completeIdleSeconds) }
        if (status !in 200..299) {
            // 把响应体带上 —— 认证失败、模型名写错、额度用尽，全在里面。
            // 截断到 500 字，免得一个 HTML 错误页糊满整个界面。
            throw LlmException("模型接口返回 " + status + "：" + body.take(500))
        }
        return extractReply(body)
    }

    override suspend fun stream(
        messages: List<LlmMessage>,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit,
        jsonMode: Boolean,
    ): String {
        // 对话页要的是「边想边说」，不强制 JSON mode；工具循环要 JSON，由调用方开。
        val request = requestFor(messages, stream = true, jsonMode = jsonMode)
        val full = StringBuilder()

        withContext(Dispatchers.IO) {
            // 用 ofInputStream 而不是 ofLines：ofLines 的 send 只等到响应头就返回，
            // 之后读 body 没有任何超时；网络/代理半途断流时，读循环会永远卡在下一行。
            // 这里自己读，并用看门狗按「多久没有新内容」兜底（见 streamIdleSeconds）。
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) {
                val body = runCatching {
                    response.body().use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrDefault("")
                throw LlmException("模型接口返回 " + response.statusCode() + "：" + body.take(500))
            }

            val body = response.body()
            // 用户点「停止生成」时要把阻塞中的读踢醒：正文读在 IO 线程上，
            // 协程取消没法直接打断 InputStream，所以挂一个「取消时顺手 close」的兄弟协程。
            // 少了它，按钮按下去也只是假装停了，界面还得等网络超时。
            val closeOnCancel = launch {
                try {
                    awaitCancellation()
                } finally {
                    runCatching { body.close() }
                }
            }
            val watchdog = ResponseWatchdog(body, streamIdleSeconds)

            try {
                body.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        watchdog.touch()
                        // SSE：空行是分隔符，注释行（以 : 开头）是保活，其余都是 data:
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        if (data == "[DONE]") break
                        val delta = runCatching { extractDelta(data) }.getOrNull() ?: continue
                        // DeepSeek 的思考内容走单独通道（delta.reasoning_content），
                        // 不混进正文；空白分片不送。
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
                if (watchdog.stalled) {
                    throw LlmException(
                        "模型响应超时：" + streamIdleSeconds + " 秒没有新内容，网络或代理可能断了。可以重试这一轮，或先点「停止生成」。",
                    )
                }
                throw e
            } finally {
                closeOnCancel.cancel()
                watchdog.close()
            }
        }

        return full.toString()
    }

    /** 读完整响应体（非流式用），同样带「多久没有新字节」的看门狗。 */
    private fun readResponse(request: HttpRequest, idleSeconds: Long): Pair<Int, String> {
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val body = response.body()
        val watchdog = ResponseWatchdog(body, idleSeconds)
        return try {
            val text = body.bufferedReader(Charsets.UTF_8).use { reader ->
                val sb = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: break
                    watchdog.touch()
                    sb.append(line).append('\n')
                }
                sb.toString()
            }
            response.statusCode() to text
        } catch (e: IOException) {
            if (watchdog.stalled) {
                throw LlmException(
                    "模型响应超时：" + idleSeconds + " 秒没有新内容，网络或代理可能断了。可以重试这一轮。",
                )
            }
            throw e
        } finally {
            watchdog.close()
        }
    }

    /**
     * 响应体看门狗：`HttpRequest.timeout` 只覆盖到响应头，body 中途断掉时
     * 读循环会永远等下一行/下一个字节（界面就一直「运行中」）。
     * 超过 [idleSeconds] 没有新数据就标记 [stalled] 并 close 流，把读阻塞踢醒。
     */
    private class ResponseWatchdog(
        private val body: java.io.InputStream,
        private val idleSeconds: Long,
    ) : AutoCloseable {
        private val lastActivity = AtomicLong(System.nanoTime())
        private val finished = AtomicBoolean(false)

        @Volatile
        var stalled: Boolean = false
            private set

        private val thread = Thread({
            while (!finished.get()) {
                runCatching { Thread.sleep(WATCHDOG_TICK_MS) }
                if (finished.get()) return@Thread
                if (System.nanoTime() - lastActivity.get() > idleSeconds * 1_000_000_000L) {
                    stalled = true
                    runCatching { body.close() }
                    return@Thread
                }
            }
        }, "llm-response-watchdog").apply {
            isDaemon = true
            start()
        }

        fun touch() {
            lastActivity.set(System.nanoTime())
        }

        override fun close() {
            finished.set(true)
            thread.interrupt()
            runCatching { body.close() }
        }
    }

    private companion object {
        /** 看门狗检查间隔：越小越及时，但没必要每毫秒醒一次。 */
        const val WATCHDOG_TICK_MS = 500L
    }

    private fun requestFor(
        messages: List<LlmMessage>,
        stream: Boolean,
        jsonMode: Boolean,
    ): HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create(config.endpoint))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + config.apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(requestBody(messages, stream, jsonMode), Charsets.UTF_8))
        .build()

    /**
     * 请求体。单独拆出来是为了让测试看得见「图片到底按什么格式发出去的」——
     * 真发一次请求测不了，但发出去的字节可以钉住。
     */
    internal fun requestBody(
        messages: List<LlmMessage>,
        stream: Boolean,
        jsonMode: Boolean,
    ): String {
        return buildJsonObject {
            put("model", config.model)
            put("temperature", temperature)
            put("stream", stream)
            if (jsonMode) {
                // 让接口保证输出是合法 JSON。
                // 注意：开了它还必须 (a) prompt 里出现 json 这个词，
                // (b) 给足 max_tokens，否则会被截断成半截 JSON。
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put("max_tokens", maxTokens)
            if (thinkingEnabled) {
                put("thinking", buildJsonObject { put("type", "enabled") })
            }
            putJsonArray("messages") {
                for (message in messages) {
                    addJsonObject {
                        put("role", message.role.name.lowercase())
                        if (message.images.isEmpty()) {
                            // 没有图片时保持老样子（纯字符串），别为视觉把文本链路也改了。
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
    }

    /**
     * 从 `choices[0].message` 里取正文与思考。
     *
     * 正文在 `content`；开了思考的模型（DeepSeek）另给 `reasoning_content`。
     * 有些服务会在 content 里再套一层 JSON（比如强制 JSON 模式），
     * 那也不影响这里 —— 外层解析出来是字符串，交给 [JsonExtraction] 就行。
     */
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
        // DeepSeek 的 JSON 模式偶尔会返回空内容（官方文档承认了这一点），
        // 返回空串而不是抛异常：让重试循环把它当成「没给出 JSON」再问一次。
        val content = flattenText(contentElement) ?: ""
        val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        return LlmReply(content = content, reasoning = reasoning)
    }

    /** SSE 分片：正文 `choices[0].delta.content` 与思考 `delta.reasoning_content`。 */
    private data class SseDelta(val content: String?, val reasoning: String?)

    /** 首包/尾包可能只有其中一种，缺的那个给 null 让调用方跳过。 */
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
    internal fun flattenText(element: JsonElement?): String? = when (element) {
        // JSON null 也是 JsonPrimitive，`.content` 会返回字符串 "null"；
        // 开思考后首批分片的 content 就是 null，必须显式挡掉，否则每个思考分片都会往正文塞一个 "null"。
        null, is JsonNull -> null
        is JsonArray -> element.mapNotNull { part ->
            (part as? JsonObject)?.get("text")?.let(::flattenText)
        }.joinToString("").ifEmpty { null }
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }
}

