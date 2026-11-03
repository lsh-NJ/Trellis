package dev.trellis.app.desktop

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.domain.plan.WeekView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 笔记 → 计划节点联动：判断一篇笔记是否已经「讲完」某个计划节点。
 *
 * 数据流（设计见 `docs/plan-page.md` §13）：
 *
 * 1. 只取**本周还没完成**的节点当候选（[planLinkCandidates]），未来周与已完成的都不参与；
 * 2. 笔记 AI 整理成功后再跑一次判定（[buildPlanLinkMessages]），正文太短或没有候选就直接跳过；
 * 3. 模型给每个候选 `coverage/confidence/evidence`，这里只信任能通过硬校验的（[validatePlanLinkJudgments]）：
 *    `coverage=full`、`confidence ≥ 0.8`、`evidence` 必须是正文逐字子串、节点没被用户屏蔽；
 * 4. 校验通过的交给 `AppRuntime` 用系统身份完成并写进 `plans/auto-completions.json`，可撤销。
 *
 * 这个文件只有纯逻辑（提示词 / 解析 / 校验），不碰文件、不碰 UI、不调网络。
 */

/** 本周一个还没完成的计划节点，作为联动判定的候选。 */
internal data class PlanLinkCandidate(
    val occurrenceId: String,
    val title: String,
    val chapter: String = "",
    val day: String = "",
    val minutes: Int = 0,
)

/** 模型对「这篇笔记对某个节点的覆盖程度」的判断。 */
internal enum class PlanLinkCoverage(val wire: String) {
    Full("full"),
    Partial("partial"),
    None("none"),
    ;

    companion object {
        fun fromWire(raw: String): PlanLinkCoverage? =
            entries.firstOrNull { it.wire.equals(raw.trim(), ignoreCase = true) }
    }
}

/** 模型返回的一条原始判断（还没过逐字校验）。 */
internal data class PlanLinkJudgment(
    val occurrenceId: String,
    val coverage: PlanLinkCoverage,
    val confidence: Double,
    val evidence: String = "",
    val reason: String = "",
)

/** 通过校验、可以真正执行的一笔自动完成。 */
internal data class PlanLinkCompletion(
    val occurrenceId: String,
    val title: String,
    val day: String,
    val confidence: Double,
    val evidence: String,
    val reason: String = "",
)

/** 正文短于这个长度就不跑判定：几百字的随笔不可能「讲完」一个计划节点。 */
internal const val PLAN_LINK_MIN_BODY_CHARS: Int = 400

/** 自动完成的置信度门槛。 */
internal const val PLAN_LINK_MIN_CONFIDENCE: Double = 0.8

/** 一条笔记最多自动完成这么多个节点，多出来的只当「可能完成」不当真。 */
internal const val PLAN_LINK_MAX_AUTO_COMPLETIONS: Int = 3

/** 送给模型的正文上限（更长的笔记只判前面这部分）。 */
internal const val PLAN_LINK_MAX_BODY_CHARS: Int = 12000

/** 一次判定最多给模型看这么多个候选节点（按与正文的相关度挑）。 */
internal const val PLAN_LINK_MAX_CANDIDATES: Int = 20

/** 证据至少这么长，避免模型给一两个字蒙过逐字校验。 */
private const val PLAN_LINK_MIN_EVIDENCE_CHARS: Int = 8

private const val PLAN_LINK_MAX_EVIDENCE_CHARS: Int = 160

private const val PLAN_LINK_MAX_REASON_CHARS: Int = 60

/** 正文够长、且有本周没完成的节点时才值得多花一次模型请求。 */
internal fun shouldAttemptPlanLink(body: String, candidates: List<PlanLinkCandidate>): Boolean =
    body.trim().length >= PLAN_LINK_MIN_BODY_CHARS && candidates.isNotEmpty()

/**
 * 从本周的 [WeekView] 里挑出「还没完成」的节点。
 *
 * 只用 `status = planned` 的排期：已完成（done）、跳过的（skipped/superseded）、
 * 结算掉的（missed）都不再自动完成。[chapters] 用来补章节名（周视图里没有，调用方
 * 从 `openNodes` 结果里拼一张表即可；没有也不影响判定）。
 */
internal fun planLinkCandidates(
    view: WeekView,
    chapters: Map<String, String> = emptyMap(),
): List<PlanLinkCandidate> = view.days
    .flatMap { day ->
        day.items.asSequence()
            .filter { it.status == "planned" }
            .map { item ->
                PlanLinkCandidate(
                    occurrenceId = item.occurrenceId,
                    title = item.title,
                    chapter = chapters[item.occurrenceId].orEmpty(),
                    day = day.day,
                    minutes = item.minutes,
                )
            }
    }
    .distinctBy { it.occurrenceId }

/**
 * 候选太多时按「和正文的相关度」挑前 [PLAN_LINK_MAX_CANDIDATES] 个，避免提示词膨胀。
 *
 * 相关度只是便宜的字面粗筛（节点标题/章节里两字片段在正文里出现几次），
 * 真正判断是否「讲完」还是模型的工作；同分保持原来的先后顺序（周内顺序）。
 */
internal fun selectPlanLinkCandidates(
    body: String,
    candidates: List<PlanLinkCandidate>,
    limit: Int = PLAN_LINK_MAX_CANDIDATES,
): List<PlanLinkCandidate> {
    if (candidates.size <= limit) return candidates
    val scored = candidates.mapIndexed { index, candidate ->
        Triple(candidate, planLinkRelevance(body, candidate), index)
    }
    return scored
        .sortedWith(compareByDescending<Triple<PlanLinkCandidate, Int, Int>> { it.second }.thenBy { it.third })
        .take(limit)
        .sortedBy { it.third }
        .map { it.first }
}

/** 字面粗筛：把节点标题/章节切成两字片段，数一数在正文里出现多少次。 */
private fun planLinkRelevance(body: String, candidate: PlanLinkCandidate): Int {
    val haystack = body.lowercase()
    val needle = (candidate.title + " " + candidate.chapter).lowercase()
    val grams = needle.windowed(2).filter { gram -> gram.any { it.isLetterOrDigit() } }.toSet()
    return grams.sumOf { gram -> countOccurrences(haystack, gram) }
}

private fun countOccurrences(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var index = haystack.indexOf(needle)
    while (index >= 0) {
        count++
        index = haystack.indexOf(needle, index + needle.length)
    }
    return count
}

/** 判定用的提示词：只输出 JSON，只信正文原文。 */
internal fun buildPlanLinkMessages(
    noteTitle: String,
    body: String,
    candidates: List<PlanLinkCandidate>,
): List<LlmMessage> {
    val system = "你是学习计划与笔记的联动助手。用户会给你一篇刚整理完的笔记和「本周还没完成的计划节点」。" +
        "你的任务：逐个判断这篇笔记是否已经把节点的主题**讲得比较完整**——" +
        "出现了该主题的解释、步骤、结论、示例之类，而不是只提一句名字或顺带带过。" +
        "只输出 JSON 对象，不输出任何解释。硬规则：" +
        "① 只有确实认为笔记已经覆盖住这个节点时才给 coverage=full；只有一部分给 partial；沾不上给 none。" +
        "② confidence 用 0~1 的小数，拿不准就给低一点（低于 0.8 不会被自动完成）。" +
        "③ evidence 必须从笔记正文里**逐字**摘一段（不要改写、不要加省略号、不要跨段拼接），" +
        "系统会用它在正文里做精确匹配，匹配不上这条判断就作废。" +
        "④ occurrenceId 只能从给定的节点列表里挑，不能编造。"
    val shown = body.take(PLAN_LINK_MAX_BODY_CHARS)
    val truncated = body.length > PLAN_LINK_MAX_BODY_CHARS
    val user = buildString {
        append("笔记标题：").append(noteTitle.ifBlank { "（无标题）" }).append("\n\n")
        append("笔记正文")
        if (truncated) append("（笔记太长，只给前 ").append(PLAN_LINK_MAX_BODY_CHARS).append(" 字）")
        append("：\n").append(shown).append("\n\n")
        append("本周还没完成的计划节点（").append(candidates.size).append(" 个）：\n")
        candidates.forEach { candidate ->
            append("- occurrenceId=").append(candidate.occurrenceId)
            append(" ｜ 标题：").append(candidate.title)
            if (candidate.chapter.isNotBlank()) append(" ｜ 章节：").append(candidate.chapter)
            if (candidate.day.isNotBlank()) append(" ｜ 计划日：").append(candidate.day)
            if (candidate.minutes > 0) append(" ｜ 约 ").append(candidate.minutes).append(" 分钟")
            append("\n")
        }
        append("\n输出 JSON：{\"completions\":[{\"occurrenceId\":\"…\",\"coverage\":\"full|partial|none\",")
        append("\"confidence\":0.0,\"evidence\":\"正文逐字片段\",\"reason\":\"一句话\"}]}")
        append("\n每个候选节点都输出一条；没有把握的给 none/低 confidence。")
    }
    return listOf(
        LlmMessage(LlmRole.System, system),
        LlmMessage(LlmRole.User, user),
    )
}

/**
 * 解析模型输出里的 `completions`。
 *
 * 容忍 ```json 围栏和前后废话（定位第一个 `{` 到最后一个 `}`）；任何一条坏数据单独丢弃，
 * 解析失败返回空列表——联动是增强功能，不能因为模型乱写让页面报错。
 */
internal fun parsePlanLinkJudgments(raw: String): List<PlanLinkJudgment> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    val root = runCatching {
        Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
    }.getOrNull() ?: return emptyList()
    val array = root.arrayOrEmpty("completions")
    return array.mapNotNull { element ->
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val occurrenceId = obj.stringOrNull("occurrenceId")?.trim().orEmpty()
        if (occurrenceId.isEmpty()) return@mapNotNull null
        val coverage = PlanLinkCoverage.fromWire(obj.stringOrNull("coverage").orEmpty()) ?: return@mapNotNull null
        val confidence = obj.numberOrNull("confidence")
            ?: obj.stringOrNull("confidence")?.trim()?.toDoubleOrNull()
            ?: 0.0
        PlanLinkJudgment(
            occurrenceId = occurrenceId,
            coverage = coverage,
            confidence = confidence.coerceIn(0.0, 1.0),
            evidence = obj.stringOrNull("evidence").orEmpty().trim().take(PLAN_LINK_MAX_EVIDENCE_CHARS),
            reason = obj.stringOrNull("reason").orEmpty().trim().take(PLAN_LINK_MAX_REASON_CHARS),
        )
    }
}

/**
 * 把模型判断变成真正可以执行的自动完成列表。
 *
 * 门槛（全部满足）：
 * - 节点在候选里、且没被用户屏蔽（[blocked]）；
 * - `coverage = full`；
 * - `confidence ≥ [PLAN_LINK_MIN_CONFIDENCE]`；
 * - `evidence` 至少 [PLAN_LINK_MIN_EVIDENCE_CHARS] 个字，并且是正文的**逐字子串**。
 *
 * 通过后按 confidence 从高到低取前 [maxCompletions] 个（同一节点只留一条）。
 */
internal fun validatePlanLinkJudgments(
    body: String,
    candidates: List<PlanLinkCandidate>,
    judgments: List<PlanLinkJudgment>,
    blocked: Set<String> = emptySet(),
    maxCompletions: Int = PLAN_LINK_MAX_AUTO_COMPLETIONS,
): List<PlanLinkCompletion> {
    if (candidates.isEmpty() || judgments.isEmpty()) return emptyList()
    val byId = candidates.associateBy { it.occurrenceId }
    val seen = HashSet<String>()
    val accepted = ArrayList<Pair<PlanLinkCandidate, PlanLinkJudgment>>()
    for (judgment in judgments) {
        if (judgment.coverage != PlanLinkCoverage.Full) continue
        if (judgment.confidence < PLAN_LINK_MIN_CONFIDENCE) continue
        if (judgment.occurrenceId in blocked) continue
        val candidate = byId[judgment.occurrenceId] ?: continue
        if (!seen.add(candidate.occurrenceId)) continue
        val evidence = judgment.evidence.trim()
        if (evidence.length < PLAN_LINK_MIN_EVIDENCE_CHARS) continue
        if (!body.contains(evidence)) continue
        accepted += candidate to judgment
    }
    return accepted
        .sortedWith(compareByDescending<Pair<PlanLinkCandidate, PlanLinkJudgment>> { it.second.confidence }
            .thenBy { it.first.day }
            .thenBy { it.first.title })
        .take(maxCompletions.coerceAtLeast(0))
        .map { (candidate, judgment) ->
            PlanLinkCompletion(
                occurrenceId = candidate.occurrenceId,
                title = candidate.title,
                day = candidate.day,
                confidence = judgment.confidence,
                evidence = judgment.evidence.trim(),
                reason = judgment.reason,
            )
        }
}

// ---------------------------------------------------------------------------
// 小工具（与 `KnowledgeGraphAi` 同款：只读、容错）
// ---------------------------------------------------------------------------

private fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    (this[key] as? JsonArray) ?: JsonArray(emptyList())

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.numberOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
