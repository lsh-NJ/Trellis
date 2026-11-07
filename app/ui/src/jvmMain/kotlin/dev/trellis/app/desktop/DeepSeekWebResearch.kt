package dev.trellis.app.desktop

import dev.trellis.ai.LlmConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * DeepSeek 官方 Search / Fetch 的桌面 JVM 实现。
 *
 * 放在 `jvmMain` 而不是共享源集：它用 `java.net.http.HttpClient`，安卓上没有这个包
 * （`SharedSourceCompatibilityTest` 会拦住）。共享层只留接口、数据模型和纯解析函数，
 * 手机端将来接自己的 HTTP 实现即可。
 */
internal class DeepSeekWebResearch(
    private val config: LlmConfig,
    private val timeoutSeconds: Long = 20,
) : ResearchGateway {

    private val endpoint: String = config.endpoint
        .removeSuffix("/v1/chat/completions")
        .removeSuffix("/chat/completions")
        .trimEnd('/')

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun gather(goal: String): ResearchSources? {
        if (config.apiKey.isBlank()) return null
        val queries = listOf(
            goal,
            goal + " 学习路径 阶段",
            goal + " 教程 项目实战",
        )
        val hits = linkedMapOf<String, ResearchHit>()
        queries.forEach { query ->
            search(query, count = 5).forEach { hit ->
                if (hit.url.isNotBlank()) hits.putIfAbsent(hit.url, hit)
            }
        }
        val ordered = hits.values.sortedByDescending { it.authority }.take(8)
        if (ordered.isEmpty()) return null
        val pages = ordered
            .filter { it.summary.length >= 80 && it.url.startsWith("http") }
            .take(2)
            .mapNotNull { hit ->
                fetch(hit.url)?.let { page ->
                    page.copy(
                        url = page.url.ifBlank { hit.url },
                        title = page.title.ifBlank { hit.title },
                    )
                }
            }
        return ResearchSources(hits = ordered, pages = pages)
    }

    internal suspend fun search(query: String, count: Int = 5): List<ResearchHit> {
        val body = """
            {"query":${quote(query)},"count":${count.coerceIn(1, 50)}}
        """.trim()
        val raw = post("/v1/tools/search", body) ?: return emptyList()
        return parseSearchResults(raw)
    }

    internal suspend fun fetch(url: String): ResearchPage? {
        val body = """{"url":${quote(url)}}"""
        val raw = post("/v1/tools/fetch", body) ?: return null
        return parseFetchedPage(raw)
    }

    private suspend fun post(path: String, body: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + config.apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                appendWebResearchLog("web research " + path + " → HTTP " + response.statusCode())
                return@withContext null
            }
            response.body()
        }.onFailure { error ->
            appendWebResearchLog("web research " + path + " 失败：" + (error.message ?: error::class.simpleName))
        }.getOrNull()
    }

    private fun quote(text: String): String =
        Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(text))
}

internal actual fun createWebResearch(
    config: dev.trellis.ai.LlmConfig,
    timeoutSeconds: Long,
): ResearchGateway? = DeepSeekWebResearch(config, timeoutSeconds)
