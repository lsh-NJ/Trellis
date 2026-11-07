package dev.trellis.app.desktop

import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 网络调研：DeepSeek 官方的 Search / Fetch 两个只读接口。
 *
 * 为什么用它：规划一条学习路线不能只靠模型的记忆 —— 它得先**看见**网上现在有什么教程、
 * 招聘方在要什么、哪些路径被反复推荐。DeepSeek 的 key 本来就有这两个接口
 * （`POST /v1/tools/search`、`POST /v1/tools/fetch`，按成功请求计费），
 * 不用再自己解析搜索引擎的 HTML。
 *
 * 这里只负责「拿资料」，不负责写计划：[ResearchGateway] 是给 [ResearchTools] 的最小接口，
 * 测试传假的网关就能把网络隔掉。失败一律降级成「没有资料」——调研可以继续，计划照样要能建。
 */
data class ResearchHit(
    val title: String,
    val url: String,
    val siteName: String,
    val summary: String,
    val date: String = "",
    val authority: Int = -1,
)

data class ResearchPage(
    val url: String,
    val title: String,
    val markdown: String,
)

data class ResearchSources(
    val hits: List<ResearchHit>,
    val pages: List<ResearchPage>,
) {
    /** 拼进提示词的一段资料；没有正文时只给标题/摘要。 */
    fun render(): String = buildString {
        appendLine("<research_sources>")
        appendLine("下面是宿主刚从网上搜到的资料（可能有噪音，只当作参考）：")
        hits.forEachIndexed { index, hit ->
            append("[").append(index + 1).append("] ").append(hit.title)
            append(" — ").append(hit.siteName)
            if (hit.date.isNotBlank()) append(" — ").append(hit.date.take(10))
            appendLine()
            appendLine(hit.summary.take(500))
        }
        if (pages.isNotEmpty()) {
            appendLine()
            appendLine("正文摘录：")
            pages.forEach { page ->
                appendLine("[" + page.url + "] " + page.title)
                appendLine(page.markdown.take(1500))
                appendLine()
            }
        }
        appendLine("</research_sources>")
    }
}

/** 调研网关：给 [ResearchTools] 用；测试里传假的即可。 */
interface ResearchGateway {
    suspend fun gather(goal: String): ResearchSources?
}

/**
 * 平台各自的实现：桌面 JVM 用 `java.net.http` 调 DeepSeek 官方接口，安卓端暂时没有实现（返回 null，
 * 调研降级成「没有资料」）。放在 expect/actual 里是为了让共享的 [AppRuntime] 不直接依赖桌面 JDK。
 */
internal expect fun createWebResearch(
    config: dev.trellis.ai.LlmConfig,
    timeoutSeconds: Long = 20,
): ResearchGateway?

/** 解析 `POST /v1/tools/search` 的响应；解析不出来就返回空（调研降级，不报错）。 */
internal fun parseSearchResults(raw: String): List<ResearchHit> {
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
    val results = root["results"] as? JsonArray ?: return emptyList()
    return results.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) return@mapNotNull null
        val site = obj["site_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .ifBlank { runCatching { URI.create(url).host.orEmpty() }.getOrDefault("") }
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .ifBlank { obj["snippet"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() }
        ResearchHit(
            title = title.ifBlank { site },
            url = url,
            siteName = site,
            summary = summary,
            date = obj["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            authority = obj["authority"]?.jsonPrimitive?.intOrNull ?: -1,
        )
    }
}

/** 解析 `POST /v1/tools/fetch` 的响应：只要正文 Markdown。 */
internal fun parseFetchedPage(raw: String): ResearchPage? {
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    val content = root["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (content.isEmpty()) return null
    val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val url = root["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    return ResearchPage(url = url, title = title, markdown = content)
}

/** 客户端内部日志；由 AppRuntime 在初始化时接上写 app.log 的实现。 */
internal var webResearchLog: ((String) -> Unit)? = null

internal fun appendWebResearchLog(message: String) {
    runCatching { webResearchLog?.invoke(message) }
}
