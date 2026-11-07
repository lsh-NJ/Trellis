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
 * 产出顺序是固定的：**先写本周文档，再把条目追加成单元、铺到这一周每天**。
 * 先文档后落库的原因：周文档是这一周「应该做什么」的账本，落库失败时它还在，
 * 重试有据可查；反过来先落库再写文档，文档写失败用户会看到「计划页有、文档没有」。
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
        "2. 它读计划文档与上周完成情况，产出这一周的具体条目（学习/实践/复习 + 分钟），宿主**先写本周文档、再**把条目追加成单元、铺到每天；两步都会在回执里说清（周文档已写 / 已排 N 条）。",
        "3. 先读再排：如果计划文档里没有这一阶段的内容，先用 read_plan_doc 看清楚，必要时用 write_plan_doc 补；不要凭空编。",
        "4. 计划里已经有这一周的条目时不要重复排；要调整就用 move_plan_node / placeUnit 那套重排工具。",
        "5. 排完跟用户说清「这周重点 + 每天大概多久 + 有没有实践」，不要只报数字。",
        "</how_to_plan_week>",
    ).joinToString("\n")

    val all: List<AgentTool> get() = listOf(planWeek)

    private val planWeek = object : AgentTool {
        override val name: String = "plan_week"

        override val description: String =
            "排某一周的具体计划：读大纲与上周完成情况，产出本周条目（学习/实践/复习），" +
                "**先把本周文档写进计划文档，再**把条目追加成单元、按预算铺到每天。"

        override val parameters: String =
            "week：可选，ISO 周（如 2026-W45）；省略=本周；" +
                "dailyMinutes：可选，这一周每天大概投入多少分钟（30–240）；省略=设置里的每天预计投入或 90。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult("计划库打不开，没排成。", isError = true)
            val planId = plan.activePlanId() ?: return ToolResult(
                "现在还没有进行中的计划。先用 research_plan 或 create_learning_plan 建一份。",
                isError = true,
            )
            val week = arguments["week"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: isoWeekOf(clock.today())
            val daily = (arguments["dailyMinutes"]?.jsonPrimitive?.intOrNull ?: weeklyCapacity()?.div(7) ?: 90)
                .coerceIn(30, 240)

            val result = planOneWeek(
                plan = plan,
                planDocStore = planDocStore(),
                llm = llm,
                clock = clock,
                planId = planId,
                week = week,
                dailyMinutes = daily,
            )
            if (result.docWritten || result.appended) onChanged()
            result.error?.let { return ToolResult(it, isError = true) }

            val items = result.items
            return ToolResult(
                buildString {
                    append(result.week).append(" 排好了：").append(result.placed).append(" 条，按每天 ").append(daily).append(" 分钟铺开")
                    append("（学习 ").append(items.count { it.kind == "study" })
                    append(" · 实践 ").append(items.count { it.kind == "practice" })
                    append(" · 复习 ").append(items.count { it.kind == "review" }).append("）。")
                    if (result.docWritten) append("本周文档也写好了（计划文档里能看到每天的安排）。")
                    if (result.note.isNotBlank()) append(result.note)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 共享的「排一周」逻辑：plan_week 直接调；research_plan 出完大纲后也调它排第一周，
// 保证「先写周文档、再排天」只有一份实现。
// ---------------------------------------------------------------------------

/** 模型给的一条周计划条目。 */
internal data class PlannedItem(val title: String, val minutes: Int, val kind: String, val chapter: String)

/** 模型给的整周安排：这周重点 + 条目。 */
internal data class ParsedWeek(val focus: String = "", val items: List<PlannedItem> = emptyList())

/**
 * 排一周的结果。
 *
 * [docWritten] = 周文档已经落盘；[appended] = 单元已追加；[placed] = 真正铺到天的条数。
 * [error] 非空表示这次没排成（可能是周文档没写成，也可能是文档写了但追加失败；看文案区分）。
 */
internal data class WeekPlanResult(
    val week: String,
    val items: List<PlannedItem> = emptyList(),
    val docWritten: Boolean = false,
    val appended: Boolean = false,
    val placed: Int = 0,
    val error: String? = null,
    val note: String = "",
)

/**
 * 排一周：校验 → 读大纲/上周 → 模型出条目 → **先写周文档** → appendUnits → 逐日 placeUnit。
 *
 * 周文档先落盘：落库失败时文档保留着，重试/排查有据可查（见 [WeekPlanResult.error]）。
 */
internal suspend fun planOneWeek(
    plan: LearningPlanService,
    planDocStore: PlanDocStore?,
    llm: LlmClient,
    clock: TrellisClock,
    planId: String,
    week: String,
    dailyMinutes: Int,
    /** 本周所属阶段的焦点（research_plan 排第一周时从阶段表带过来；模型自己的 focus 优先）。 */
    stageFocus: String = "",
    today: LocalDate = clock.today(),
): WeekPlanResult {
    val thisWeek = isoWeekOf(today)
    if (!WEEK_PATTERN.matches(week)) {
        return WeekPlanResult(week, error = "week 要写成 2026-W45 这种格式，现在是「$week」。")
    }
    val weekStart = isoWeekStart(week)
        ?: return WeekPlanResult(
            week,
            error = "week「$week」不是一个真实的 ISO 周（周号要在 01–53 之间，比如 2026-W45）。",
        )
    val weekEnd = weekStart.plusDays(6)
    if (week < thisWeek) {
        return WeekPlanResult(week, error = "$week 已经过去了，只能排本周及以后。")
    }

    val outline = planDocStore?.read(planId).orEmpty()
    val lastWeek = previousWeekOf(week)
    val lastWeekState = runCatching { plan.week(planId, lastWeek) }.getOrNull()
    val lastSummary = lastWeekState?.commitment?.units.orEmpty()
        .joinToString("；") { it.title + "（" + it.stateLabel + "）" }

    val raw = withTimeoutOrNull(TIMEOUT_MS) {
        llm.complete(
            listOf(
                LlmMessage(LlmRole.System, weekSystemPrompt(week, dailyMinutes)),
                LlmMessage(LlmRole.User, weekUserPrompt(today.toString(), outline, lastWeek, lastSummary, stageFocus)),
            ),
        )
    } ?: return WeekPlanResult(week, error = "排周计划的模型没在 " + (TIMEOUT_MS / 1000) + " 秒内回话，没排成。")

    val parsed = parseWeekItems(raw)
        ?: return WeekPlanResult(
            week,
            error = "排周结果没解析成条目（模型可能没说 JSON），这次没落库；可以重试或改用 plan_next_week。",
        )
    val items = parsed.items
    // 周文档里的「本周重点」：模型自己给的最贴切，没有就用阶段焦点（research_plan 传进来的）。
    val focus = parsed.focus.ifBlank { stageFocus.trim() }

    // 先纯函数算好每天放哪几条：文档里写的天和实际 placeUnit 的天必须是同一份安排。
    val start = if (week == thisWeek) maxOf(today, weekStart) else weekStart
    val dayOfItem = assignDays(items, start, weekEnd, dailyMinutes)
    val plannedByDay = linkedMapOf<String, MutableList<PlannedItem>>()
    dayOfItem.forEachIndexed { index, day ->
        plannedByDay.getOrPut(day.toString()) { mutableListOf() } += items[index]
    }

    // ---- 第一步：先写周文档。写不成，这一周就整周不排（不落库、不铺天）。 ----
    var docWritten = false
    if (planDocStore != null) {
        val saved = runCatching { planDocStore.saveWeek(planId, week, weekDoc(week, focus, items, plannedByDay)) }
        if (saved.isFailure) {
            val reason = saved.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "未知原因"
            return WeekPlanResult(week, error = "周文档没写成（$reason），这一周没有排，计划库也没动；等文档能写了再试。")
        }
        docWritten = true
    }

    // ---- 第二步：追加单元。失败时周文档已经写了，必须保留并说清。 ----
    val drafts = items.map { item ->
        UnitDraft(
            chapter = item.chapter.ifBlank { "本周" },
            title = item.title,
            estimateMinutes = item.minutes.coerceIn(10, 45),
            kind = item.kind,
        )
    }
    val appended = plan.appendUnits(commandEnv(planId, "plan.week"), planId, drafts)
    val unitIds = appended.value
        ?: return WeekPlanResult(
            week,
            items = items,
            docWritten = docWritten,
            error = "周文档已经写了并保留着，但追加单元失败：" + (appended.error?.message ?: "未知原因") +
                "；这一周的条目还没进计划库，修好后重试即可（文档不会丢）。",
        )

    // ---- 第三步：按先算好的安排逐日 placeUnit。 ----
    var placedCount = 0
    items.forEachIndexed { index, _ ->
        val day = dayOfItem[index].toString()
        val placed = plan.placeUnit(commandEnv(planId, "plan.week"), planId, week, unitIds[index], day)
        if (placed.ok) placedCount++
    }
    val note = buildString {
        if (placedCount < items.size) {
            append("有 ").append(items.size - placedCount).append(" 条没铺上（可能那天已有同单元），可以在计划页拖一下。")
        }
        if (planDocStore == null) {
            append("计划文档存储没接上：这一周只排进了计划库，没写周文档。")
        }
    }
    return WeekPlanResult(
        week = week,
        items = items,
        docWritten = docWritten,
        appended = true,
        placed = placedCount,
        note = note,
    )
}

// ---------------------------------------------------------------------------
// 纯函数与提示词（plan_week / research_plan 共用）
// ---------------------------------------------------------------------------

private val WEEK_PATTERN = Regex("^\\d{4}-W\\d{2}$")
private const val TIMEOUT_MS = 30_000L

private fun commandEnv(planId: String?, origin: String) = CommandEnvelope(
    commandId = "cmd_" + UUID.randomUUID(),
    actor = Actor.AGENT,
    planId = planId,
    origin = origin,
)

private fun parseWeekItems(raw: String): ParsedWeek? {
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
    if (items.isEmpty()) return null
    val focus = root["focus"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    return ParsedWeek(focus = focus.take(40), items = items)
}

/**
 * 把这一周的条目按预算分到每天（纯函数）。
 *
 * 先算好再落文档/落库，保证周文档里写的天和实际 placeUnit 的天一致。
 * 预算算法沿用原有节奏：一周预算 daily×7×0.8，按条目顺序往每天装，
 * 装不下才翻到第二天；第一天不会空着（哪怕第一条就超预算）。
 */
private fun assignDays(
    items: List<PlannedItem>,
    start: LocalDate,
    end: LocalDate,
    dailyMinutes: Int,
): List<LocalDate> {
    val days = buildList {
        var cursor = start
        while (!cursor.isAfter(end)) {
            add(cursor)
            cursor = cursor.plusDays(1)
        }
    }
    val budget = (dailyMinutes * 7 * 0.8).toInt()
    val result = mutableListOf<LocalDate>()
    var dayIndex = 0
    var used = 0
    items.forEach { item ->
        if (dayIndex < days.lastIndex && used + item.minutes > budget && used > 0) {
            dayIndex++
            used = 0
        }
        used += item.minutes
        result += days[dayIndex]
    }
    return result
}

private fun weekSystemPrompt(week: String, dailyMinutes: Int): String = buildString {
    appendLine("你是学习规划师，正在给 " + week + " 这一周排具体内容。只输出一个 JSON 对象，不要 markdown、不要解释：")
    appendLine("{\"focus\":\"这周重点（≤20 字）\",\"items\":[{\"title\":\"条目标题（≤20 字）\",\"chapter\":\"章节或主题\",\"kind\":\"study|practice|review\",\"minutes\":30}]}")
    appendLine("约束：条目 3–14 个；每条 10–45 分钟；本周每天投入约 " + dailyMinutes + " 分钟。")
    appendLine("内容规则：看视频/看书算 study；动手写代码、做项目、做题算 practice；复盘/重做错题/回顾算 review。")
    appendLine("前几周通识偏 study，专业技术路线要给 practice（写出能被运行/看到的东西）；复习安排在阶段收尾。")
}

private fun weekUserPrompt(
    today: String,
    outline: String,
    lastWeek: String,
    lastSummary: String,
    stageFocus: String,
): String = buildString {
    appendLine("今天：" + today)
    appendLine("上周（" + lastWeek + "）的条目：" + (lastSummary.ifBlank { "没有记录" }))
    if (stageFocus.isNotBlank()) appendLine("本周所属阶段的焦点：" + stageFocus)
    appendLine("计划文档（大纲）：")
    appendLine(outline.ifBlank { "（还没有文档）" })
    appendLine("请只排这一周的具体条目。")
}

private fun weekDoc(
    week: String,
    focus: String,
    items: List<PlannedItem>,
    placed: Map<String, List<PlannedItem>>,
): String = buildString {
    appendLine("# " + week + " 本周计划")
    if (focus.isNotBlank()) appendLine("> 本周重点：" + focus)
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

private fun previousWeekOf(week: String): String {
    val start = isoWeekStart(week) ?: return week
    return isoWeekOf(start.minusWeeks(1))
}
