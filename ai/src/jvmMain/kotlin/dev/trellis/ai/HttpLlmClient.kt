package dev.trellis.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
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
     * 一个插件最多三张卡片，2048 绰绰有余。
     */
    private val maxTokens: Int = 2048,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(messages: List<LlmMessage>): String {
        val request = requestFor(messages, stream = false, jsonMode = true)
        val response = withContext(Dispatchers.IO) {
            client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        }
        if (response.statusCode() !in 200..299) {
            // 把响应体带上 —— 认证失败、模型名写错、额度用尽，全在里面。
            // 截断到 500 字，免得一个 HTML 错误页糊满整个界面。
            throw LlmException(
                "模型接口返回 " + response.statusCode() + "：" +
                    response.body().take(500),
            )
        }
        return extractContent(response.body())
    }

    override suspend fun stream(messages: List<LlmMessage>, onDelta: suspend (String) -> Unit): String {
        // 对话页要的是「边想边说」，所以这里不强制 JSON mode。
        val request = requestFor(messages, stream = true, jsonMode = false)
        val full = StringBuilder()

        withContext(Dispatchers.IO) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
            if (response.statusCode() !in 200..299) {
                val body = runCatching {
                    response.body().use { lines -> lines.toList().joinToString("\n") }
                }.getOrDefault("")
                throw LlmException("模型接口返回 " + response.statusCode() + "：" + body.take(500))
            }
            val lines = response.body()
            try {
                val iterator = lines.iterator()
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    // SSE：空行是分隔符，注释行（以 : 开头）是保活，其余都是 data:
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val delta = runCatching { extractDelta(data) }.getOrNull() ?: continue
                    if (delta.isEmpty()) continue
                    full.append(delta)
                    onDelta(delta)
                }
            } finally {
                lines.close()
            }
        }

        return full.toString()
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
     * 从 `choices[0].message.content` 里取文本。
     *
     * 有些服务会在 content 里再套一层 JSON（比如强制 JSON 模式），
     * 那也不影响这里 —— 外层解析出来是字符串，交给 [JsonExtraction] 就行。
     */
    private fun extractContent(raw: String): String {
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
        val content = when (contentElement) {
            is JsonArray -> contentElement.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
            }.joinToString("")
            else -> contentElement.jsonPrimitive.content
        }
        // DeepSeek 的 JSON 模式偶尔会返回空内容（官方文档承认了这一点）。
        // 返回空串而不是抛异常：让重试循环把它当成「没给出 JSON」再问一次。
        return content
    }

    /** SSE 分片：`choices[0].delta.content`。首包/尾包可能没有 content，返回 null 让调用方跳过。 */
    private fun extractDelta(raw: String): String? {
        val root = json.parseToJsonElement(raw).jsonObject
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull()?.jsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null
        val content = delta["content"] ?: return null
        return when (content) {
            is JsonArray -> content.mapNotNull { part ->
                (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
            }.joinToString("").ifEmpty { null }
            else -> content.jsonPrimitive.content
        }
    }
}

class LlmException(message: String) : Exception(message)
