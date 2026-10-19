package dev.trellis.ai

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
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
 */
class HttpLlmClient(
    private val config: LlmConfig,
    private val timeoutSeconds: Long = 90,
    private val temperature: Double = 0.2,
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build(),
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(messages: List<LlmMessage>): String {
        val body = buildJsonObject {
            put("model", config.model)
            put("temperature", temperature)
            put("stream", false)
            putJsonArray("messages") {
                for (message in messages) {
                    addJsonObject {
                        put("role", message.role.name.lowercase())
                        put("content", message.content)
                    }
                }
            }
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(config.endpoint))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
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
        return message["content"]?.jsonPrimitive?.content
            ?: throw LlmException("模型返回里没有 content：" + raw.take(300))
    }
}

class LlmException(message: String) : Exception(message)
