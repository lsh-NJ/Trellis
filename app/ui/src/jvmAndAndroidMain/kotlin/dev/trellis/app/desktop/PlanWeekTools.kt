package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.PlanDocStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 每周的「局部计划」：在计划对应的会话里跑一轮，把大纲落成这一周的具体单元。
 *
 * 输入：计划文档（大纲/上周文档）+ 上周完成情况 + 每天投入。
 * 产出：追加单元（study/practice/review + 分钟）→ 宿主按预算铺到这一周每天 → 写本周文档。
 *
 * 和 [PlanTools]、[ResearchTools] 的分工：create/research 建计划，plan_week 只往已有计划里加这一周的活。
 */
class PlanWeekTools(
    private val service: () -> LearningPlanService?,
    private val planDocStore: () -> PlanDocStore?,
    private val weeklyCapacity: () -> Int? = { null },
    private val clock: TrellisClock = SystemTrellisClock(),
    private val llm: LlmClient,
    private val onChanged: () -> Unit = {},
) {

    val guidance: String = listOf(
        "<how_to_plan_week>",
        "1. 周计划：用户说「排本周」「排下周」「这周学什么」，或每周开始要排计划时，先调 `plan_week`（可带 week=YYYY-Www / dailyMinutes）。",
        "2. 它读计划文档与上周完成情况，产出这一周的具体条目（学习/实践/复习 + 分钟），宿主会按预算铺到每天并写周文档。",
        "3. 先读再排：如果计划文档里没有这一阶段的内容，先用 read_plan_doc 看清楚，必要时用 write_plan_doc 补；不要凭空编。",
        "4. 计划里已经有这一周的条目时不要重复排；要调整就用 move_plan_node / placeUnit 那套重排工具。",
        "5. 排完跟用户说清「这周重点 + 每天大概多久 + 有没有实践」，不要只报数字。",
        "</how_to_plan_week>",
    ).joinToString("\n")

    val all: List<AgentTool> get() = listOf(planWeek)

    private val planWeek = object : AgentTool {
        override val name: String = "plan_week"

        override val description: String =
            "排某一周的具体计划：读大纲与上周完成情况，产出本周条目（学习/实践/复习），按预算铺到每天并写周文档。"

        override val parameters: String =
            "week：可选，ISO 周（如 2026-W45）；省略=本周；" +
                "dailyMinutes：可选，这一周每天大概投入多少分钟（30–240）；省略=设置里的每天预计投入或 90。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult("计划库打不开，没排成。", isError = true)
            val planId = plan.activePlanId() ?: return ToolResult(
                "现在还没有进行中的计划。先用 research_plan 或 create_learning_plan 建一份。",
                isError = true,
            )
            val today = clock.today()
            val thisWeek = isoWeekOf(today)
            val week = arguments["week"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: thisWeek
            if (!WEEK_PATTERN.matches(week)) return ToolResult("week 要写成 2026-W45 这种格式，现在是「$week」。", isError = true)
            if (week < thisWeek) return ToolResult("$week 已经过去了，只能排本周及以后。", isError = true)
            val daily = (arguments["dailyMinutes"]?.jsonPrimitive?.intOrNull ?: weeklyCapacity()?.div(7) ?: 90).coerceIn(30, 240)

            val outline = planDocStore()?.read(planId).orEmpty()
            val lastWeek = previousWeekOf(week)
            val lastWeekState = runCatching { plan.week(planId, lastWeek) }.getOrNull()
            val lastSummary = lastWeekState?.commitment?.units.orEmpty()
                .joinToString("；") { it.title + "（" + it.stateLabel + "）" }

            val raw = withTimeoutOrNull(TIMEOUT_MS) {
                llm.complete(
                    listOf(
                        LlmMessage(LlmRole.System, systemPrompt(week, daily)),
                        LlmMessage(LlmRole.User, userPrompt(today.toString(), outline, lastWeek, lastSummary)),
                    ),
                )
            } ?: return ToolResult("排周计划的模型没在 " + (TIMEOUT_MS / 1000) + " 秒内回话，没排成。", isError = true)

            val items = parseItems(raw)
                ?: return ToolResult("排周结果没解析成条目（模型可能没说 JSON），这次没落库；可以重试或改用 plan_next_week。", isError = true)

            val drafts = items.map { item ->
                UnitDraft(
                    chapter = item.chapter.ifBlank { "本周" },
                    title = item.title,
                    estimateMinutes = item.minutes.coerceIn(10, 45),
                    kind = item.kind,
                )
            }
            val appended = plan.appendUnits(env(planId, "plan.week"), planId, drafts)
            val unitIds = appended.value
                ?: return ToolResult("追加本周条目失败：" + (appended.error?.message ?: "未知原因"), isError = true)

            val start = if (week == thisWeek) maxOf(today, weekStart(week)) else weekStart(week)
            val days = buildList {
                var cursor = start
                while (!cursor.isAfter(weekEnd(week))) { add(cursor); cursor = cursor.plusDays(1) }
            }
            val budget = (daily * 7 * 0.8).toInt()
            val placedByDay = linkedMapOf<String, MutableList<PlannedItem>>()
            var dayIndex = 0
            var used = 0
            items.forEachIndexed { index, item ->
                if (dayIndex < days.lastIndex && used + item.minutes > budget && used > 0) {
                    dayIndex++
                    used = 0
                }
                used += item.minutes
                val day = days[dayIndex].toString()
                val placed = plan.placeUnit(env(planId, "plan.week"), planId, week, unitIds[index], day)
                if (placed.ok) placedByDay.getOrPut(day) { mutableListOf() } += item
            }

            planDocStore()?.let { store ->
                runCatching { store.saveWeek(planId, week, weekDoc(week, items, placedByDay)) }
            }
            onChanged()
            val placedCount = placedByDay.values.sumOf { it.size }
            return ToolResult(
                buildString {
                    append(week).append(" 排好了：").append(placedCount).append(" 条，按每天 ").append(daily).append(" 分钟铺开")
                    append("（学习 ").append(items.count { it.kind == "study" })
                    append(" · 实践 ").append(items.count { it.kind == "practice" })
                    append(" · 复习 ").append(items.count { it.kind == "review" }).append("）。")
                    if (placedCount < items.size) append("有 ").append(items.size - placedCount).append(" 条没铺上（可能那天已有同单元），可以在计划页拖一下。")
                },
            )
        }
    }

    private fun env(planId: String?, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID(),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )

    private fun parseItems(raw: String): List<PlannedItem>? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val array = root["items"] as? JsonArray ?: return null
        if (array.isEmpty() || array.size > 21) return null
        val items = array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val minutes = obj["minutes"]?.jsonPrimitive?.intOrNull ?: 30
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: "study"
            val normalized = when (kind) {
                "practice", "实践" -> "practice"
                "review", "复习" -> "review"
                else -> "study"
            }
            val chapter = obj["chapter"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            PlannedItem(title = title, minutes = minutes, kind = normalized, chapter = chapter)
        }
        return items.takeIf { it.isNotEmpty() }
    }

    private fun systemPrompt(week: String, dailyMinutes: Int): String = buildString {
        appendLine("你是学习规划师，正在给 " + week + " 这一周排具体内容。只输出一个 JSON 对象，不要 markdown、不要解释：")
        appendLine("{\"focus\":\"这周重点（≤20 字）\",\"items\":[{\"title\":\"条目标题（≤20 字）\",\"chapter\":\"章节或主题\",\"kind\":\"study|practice|review\",\"minutes\":30}]}")
        appendLine("约束：条目 3–14 个；每条 10–45 分钟；本周每天投入约 " + dailyMinutes + " 分钟。")
        appendLine("内容规则：看视频/看书算 study；动手写代码、做项目、做题算 practice；复盘/重做错题/回顾算 review。")
        appendLine("前几周通识偏 study，专业技术路线要给 practice（写出能被运行/看到的东西）；复习安排在阶段收尾。")
    }

    private fun userPrompt(today: String, outline: String, lastWeek: String, lastSummary: String): String = buildString {
        appendLine("今天：" + today)
        appendLine("上周（" + lastWeek + "）的条目：" + (lastSummary.ifBlank { "没有记录" }))
        appendLine("计划文档（大纲）：")
        appendLine(outline.ifBlank { "（还没有文档）" })
        appendLine("请只排这一周的具体条目。")
    }

    private fun weekDoc(week: String, items: List<PlannedItem>, placed: Map<String, List<PlannedItem>>): String = buildString {
        appendLine("# " + week + " 本周计划")
        appendLine()
        placed.forEach { (day, list) ->
            appendLine("## " + day + "（" + weekdayCn(LocalDate.parse(day)) + "）")
            list.forEach { item ->
                appendLine("- " + item.title + "（" + kindLabel(item.kind) + " · 约 " + item.minutes + " 分钟）")
            }
            appendLine()
        }
        if (placed.isEmpty()) {
            appendLine("（这一周还没有铺到天的条目。）")
            items.forEach { item -> appendLine("- " + item.title + "（" + kindLabel(item.kind) + " · 约 " + item.minutes + " 分钟）") }
        }
    }

    private fun weekdayCn(day: LocalDate): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[day.dayOfWeek.value - 1]

    private fun kindLabel(kind: String): String = when (kind) {
        "practice" -> "实践"
        "review" -> "复习"
        else -> "学习"
    }

    private fun weekStart(week: String): LocalDate = isoWeekStart(week) ?: LocalDate.parse("2020-01-06")

    private fun weekEnd(week: String): LocalDate = weekStart(week).plusDays(6)

    private fun previousWeekOf(week: String): String {
        val start = isoWeekStart(week) ?: return week
        return isoWeekOf(start.minusWeeks(1))
    }

    private data class PlannedItem(val title: String, val minutes: Int, val kind: String, val chapter: String)

    private companion object {
        const val TIMEOUT_MS = 30_000L
        val WEEK_PATTERN = Regex("^\\d{4}-W\\d{2}$")
    }
}
