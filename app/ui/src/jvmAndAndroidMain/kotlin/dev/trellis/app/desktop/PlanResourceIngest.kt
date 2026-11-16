package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.PlanResourceDraft
import dev.trellis.feature.learning.PlanDocStore
import java.util.UUID

/** 一次资源入库的结果：写了几条、剔了几条、给用户看的摘要。 */
internal data class IngestResult(
    val total: Int = 0,
    val dropped: Int = 0,
    val summary: String = "",
)

private val PROMO_WORDS = listOf(
    "课程介绍", "试听", "训练营", "加群", "领资料", "领取", "优惠", "报名", "咨询", "联系方式",
    "卖课", "试看", "宣传", "招生", "福利", "抽奖", "三连", "点赞", "投币", "关注",
)

private fun isPromoTitle(raw: String): Boolean {
    val lower = raw.lowercase()
    return PROMO_WORDS.any { lower.contains(it) }
}

/**
 * 资源入库：B 站课程/分集、外部链接、用户粘贴的清单，统一进 plan_resource。
 *
 * 分集只作为材料，不是节点；卖课/试听/联系方式标 dropped（保留但永不进周规划）。
 */
/**
 * 把材料清单写进总文档的 `## 资源清单` 段（不建资源池、不产生节点）。
 *
 * 为什么先这样：周规划本来就把整份总文档当画布，模型能看到课程/分集/文章；
 * 一张独立的资源表暂时没人读，先把「材料在哪」写清楚，等有资源面板再进库。
 */
internal suspend fun writeResourceList(
    store: PlanDocStore?,
    planId: String,
    client: BilibiliClient,
    bvids: List<String>,
    urls: List<String>,
    text: String,
): IngestResult {
    if (store == null) return IngestResult()
    var total = 0
    var dropped = 0
    val lines = mutableListOf<String>()
    lines += "## 资源清单"
    for (bvid in bvids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
        val parts = runCatching { client.parts(bvid) }.getOrNull().orEmpty()
        if (parts.isEmpty()) continue
        lines += ""
        lines += "### B 站课程 " + bvid + "（" + parts.size + " 集）"
        total++
        parts.forEach { part ->
            val cleaned = cleanPartTitle(part.title)
            val promo = cleaned == null || isPromoTitle(part.title) || (part.durationMinutes in 1..3)
            if (promo) dropped++
            val title = cleaned ?: ("第 " + part.page + " 集")
            val url = "https://www.bilibili.com/video/" + bvid + "?p=" + part.page
            lines += "- " + title + (part.durationMinutes.takeIf { it > 0 }?.let { "（$it 分钟）" } ?: "") +
                " " + url + if (promo) "（已剔除）" else ""
            total++
        }
    }
    val extra = (urls.flatMap { it.lines() } + text.lines())
        .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (extra.isNotEmpty()) {
        lines += ""
        lines += "### 外部资料"
        extra.forEach { line ->
            val url = Regex("""https?://[^\s)）]+""").find(line)?.value
            lines += if (url != null) "- [" + line.removePrefix(url).trim().trim('-', '—', ':', '：').ifBlank { url } + "](" + url + ")" else "- " + line
            total++
        }
    }
    val existing = store.read(planId).orEmpty()
    val section = lines.joinToString("\n")
    val merged = if (Regex("(?m)^##\\s*资源清单\\s*$").containsMatchIn(existing)) {
        val start = existing.indexOf("## 资源清单")
        val before = existing.substring(0, start).trimEnd()
        before + "\n\n" + section + "\n"
    } else {
        existing.trimEnd() + "\n\n" + section + "\n"
    }
    runCatching { store.save(planId, merged) }
    val summary = buildString {
        append("资源清单已写进总文档 ").append(total).append(" 条")
        if (dropped > 0) append("，其中 ").append(dropped).append(" 条课程介绍/试听已标注剔除")
        append("。分集/链接只作参考，不是节点。")
    }
    return IngestResult(total, dropped, summary)
}

internal suspend fun ingestMaterials(
    service: LearningPlanService,
    planId: String,
    client: BilibiliClient,
    bvids: List<String>,
    urls: List<String>,
    text: String,
): IngestResult {
    val env = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = "plan.resources",
    )
    var total = 0
    var dropped = 0

    for (bvid in bvids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
        val parts = runCatching { client.parts(bvid) }.getOrNull().orEmpty()
        if (parts.isEmpty()) continue
        val courseId = service.upsertResources(
            env, planId,
            listOf(
                PlanResourceDraft(
                    kind = "course",
                    title = "B 站课程 " + bvid,
                    url = "https://www.bilibili.com/video/" + bvid,
                    source = "bilibili",
                    status = "active",
                ),
            ),
        ).value?.firstOrNull() ?: continue
        total++
        val drafts = parts.map { part ->
            val cleaned = cleanPartTitle(part.title)
            val droppedPart = cleaned == null || isPromoTitle(part.title) ||
                (part.durationMinutes in 1..3)
            if (droppedPart) dropped++
            PlanResourceDraft(
                kind = "part",
                title = cleaned ?: ("第 " + part.page + " 集"),
                url = "https://www.bilibili.com/video/" + bvid + "?p=" + part.page,
                locator = "第 " + part.page + " 集",
                minutes = part.durationMinutes.takeIf { it > 0 },
                source = "bilibili",
                status = if (droppedPart) "dropped" else "active",
                parentId = courseId,
            )
        }
        service.upsertResources(env, planId, drafts)
        total += drafts.size
    }

    val linkLines = urls.flatMap { it.lines() } + text.lines()
    val drafts = linkLines.map { it.trim() }.filter { it.isNotEmpty() }.distinct().mapNotNull { line ->
        val url = Regex("""https?://[^\s)）]+""").find(line)?.value
        if (url == null) {
            if (line.length < 2) null else PlanResourceDraft(
                kind = "other", title = line.take(80), source = "user", status = "active",
            )
        } else {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: ""
            val kind = when {
                host.contains("bilibili.com") -> "part"
                host.contains("wikipedia.org") || host.contains("wiki") -> "wiki"
                else -> "article"
            }
            PlanResourceDraft(
                kind = kind,
                title = line.removePrefix(url).trim().trim('-', '—', ':', '：').takeIf { it.isNotBlank() }
                    ?: (host.ifBlank { "外部链接" }),
                url = url,
                source = "user",
                status = "active",
            )
        }
    }
    if (drafts.isNotEmpty()) {
        service.upsertResources(env, planId, drafts)
        total += drafts.size
    }

    val summary = buildString {
        append("资源入库 ").append(total).append(" 条")
        if (dropped > 0) append("，其中 ").append(dropped).append(" 条课程介绍/试听已标记剔除")
        append("。")
    }
    return IngestResult(total, dropped, summary)
}
