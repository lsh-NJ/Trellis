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
            // 每天投入只有一个真相源：计划自己的 process.daily 优先（用户在计划设置/choice bar 里选的），
            // 没设过才退回全局 study.json 的每日预计投入。以前直接读 studyPrefs（固定 60），
            // 用户选了 120 分钟也会被排成 60，页面上却写「按每天 120 分钟铺开」。
            val planState = runCatching { plan.planState(planId) }.getOrNull()
            val daily = (arguments["dailyMinutes"]?.jsonPrimitive?.intOrNull
                ?: planState?.dailyMinutes
                ?: weeklyCapacity()?.div(7)
                ?: 90)
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
            // 没有新条目（已经排过 / 全是重复）时别回「排好了：0 条」这种自相矛盾的句子。
            if (items.isEmpty() && result.placed == 0) {
                return ToolResult(result.week + " " + result.note.ifBlank { "这一周没有需要新排的条目。" })
            }
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

    // 一周只排一次：已经铺到天的周再跑 plan_week 就变成「追加」——四周模拟里同一周被连排两次，
    // 单元从 9 涨到 24、待安排池从 2 涨到 15，当天分钟数也从 60 加到 130。
    // 已经排过的周直接退回，调整交给计划页拖拽或 PlanAdminTools。
    val plannedAlready = runCatching { plan.week(planId, week) }
        .getOrNull()?.days?.flatMap { day -> day.items }.orEmpty()
    if (plannedAlready.isNotEmpty()) {
        return WeekPlanResult(
            week = week,
            note = "这一周已经排过了（" + plannedAlready.size + " 条）；要调整就直接在计划页拖节点，或告诉我把哪几条改到哪天。",
        )
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
    // 这一周已经排过什么：周承诺里的单元 + 已铺到天的条目。重排不是追加——
    // 以前每次 plan_week 都 appendUnits，四周里同一条任务被排了 11 次，周一堆到 23 件。
    val weekView = runCatching { plan.week(planId, week) }.getOrNull()
    val existingTitles = buildList {
        weekView?.commitment?.units.orEmpty().forEach { unit -> add(normalizeTitle(unit.title)) }
        weekView?.days.orEmpty().forEach { day -> day.items.forEach { item -> add(normalizeTitle(item.title)) } }
    }.filter { it.isNotBlank() }
    val existingMinutesByDay = weekView?.days.orEmpty().mapNotNull { day ->
        runCatching { LocalDate.parse(day.day) }.getOrNull()?.let { date -> date to day.items.sumOf { it.minutes } }
    }.toMap()
    // 去重不只比「完全一样」：模型每次会换措辞（「阶段1：…」/「…·复习」），
    // 所以归一后互相包含也算重复，否则同一件事会被排成两三条新单元。
    val acceptedTitles = mutableListOf<String>()
    val deduped = parsed.items.filter { item ->
        val norm = normalizeTitle(item.title)
        if (norm.isBlank()) return@filter false
        val duplicate = existingTitles.any { isSameTitle(it, norm) } || acceptedTitles.any { isSameTitle(it, norm) }
        if (!duplicate) acceptedTitles += norm
        !duplicate
    }
    val dropped = parsed.items.size - deduped.size
    // 周文档里的「本周重点」：模型自己给的最贴切，没有就用阶段焦点（research_plan 传进来的）。
    val focus = parsed.focus.ifBlank { stageFocus.trim() }

    // 先纯函数算好每天放哪几条：文档里写的天和实际 placeUnit 的天必须是同一份安排。
    // 每天已有安排要算进预算，否则补排会把新条目继续堆到已经满的那一天。
    val start = if (week == thisWeek) maxOf(today, weekStart) else weekStart
    // 本周剩余容量：从今天到周日，每天 dailyMinutes 减去已排分钟。排不下的条目不硬塞
    // 到某一天（现场出现过周日一天 20 件 700 分钟），留在待安排池等下一周或手拖。
    val remainingCapacity = buildList {
        var cursor = start
        while (!cursor.isAfter(weekEnd)) {
            add(maxOf(0, dailyMinutes - (existingMinutesByDay[cursor] ?: 0)))
            cursor = cursor.plusDays(1)
        }
    }.sum()
    // 排不下的条目不硬塞到某一天（现场出现过周日一天 20 件、700 分钟）：
    // 它们仍然进计划库（留在待安排池），只是这一周不铺到天，等下一周再排或手拖。
    val placeableIndices = buildList {
        var reserved = 0
        deduped.forEachIndexed { index, item ->
            // 容量为 0 时不能再「先铺第一条」：现场出现过回复「剩余容量只够 0 分钟」却仍加了 1 条，
            // 当天从 60 分钟变成 90 分钟。放不下就留在待安排池。
            if (reserved + item.minutes <= remainingCapacity) {
                add(index)
                reserved += item.minutes
            }
        }
    }
    val placeable = placeableIndices.map { deduped[it] }
    val overflow = deduped.size - placeable.size
    if (placeable.isEmpty() && dropped > 0) {
        return WeekPlanResult(
            week = week,
            items = emptyList(),
            note = "这一周的条目已经在计划里了，没有重复排；要调整就直接拖节点。",
        )
    }
    if (placeable.isEmpty()) {
        return WeekPlanResult(
            week = week,
            items = emptyList(),
            note = "这一周没有剩余容量了（每天投入 " + dailyMinutes + " 分钟，到周日只剩约 " +
                remainingCapacity + " 分钟），这些条目不硬塞；等下周再排，或在计划页拖到具体某天。",
        )
    }
    val dayOfItem = assignDays(placeable, start, weekEnd, dailyMinutes, existingMinutesByDay)
    val plannedByDay = linkedMapOf<String, MutableList<PlannedItem>>()
    dayOfItem.forEachIndexed { index, day ->
        plannedByDay.getOrPut(day.toString()) { mutableListOf() } += placeable[index]
    }

    // ---- 第一步：先写周文档。写不成，这一周就整周不排（不落库、不铺天）。 ----
    var docWritten = false
    if (planDocStore != null) {
        val saved = runCatching {
            planDocStore.saveWeek(planId, week, weekDoc(week, focus, placeable, plannedByDay, overflow))
        }
        if (saved.isFailure) {
            val reason = saved.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "未知原因"
            return WeekPlanResult(week, error = "周文档没写成（$reason），这一周没有排，计划库也没动；等文档能写了再试。")
        }
        docWritten = true
    }

    // ---- 第二步：追加单元。全部追加（排不下的进待安排池），失败时周文档已经写了，必须保留并说清。 ----
    val drafts = deduped.map { item ->
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
            items = deduped,
            docWritten = docWritten,
            error = "周文档已经写了并保留着，但追加单元失败：" + (appended.error?.message ?: "未知原因") +
                "；这一周的条目还没进计划库，修好后重试即可（文档不会丢）。",
        )

    // ---- 第三步：按先算好的安排逐日 placeUnit（只铺放得下的那些）。 ----
    var placedCount = 0
    placeableIndices.forEachIndexed { placeIndex, itemIndex ->
        val day = dayOfItem[placeIndex].toString()
        val placed = plan.placeUnit(commandEnv(planId, "plan.week"), planId, week, unitIds[itemIndex], day)
        if (placed.ok) placedCount++
    }
    val note = buildString {
        if (dropped > 0) append("去掉了 ").append(dropped).append(" 条本周已经有的重复条目。")
        if (overflow > 0) {
            append("本周剩余容量只够 ").append(remainingCapacity).append(" 分钟，先铺了 ")
                .append(placedCount).append(" 条；其余 ").append(overflow)
                .append(" 条留在待安排池，等下周再排，或在计划页拖到某天。")
        }
        if (placedCount < placeable.size) {
            append("有 ").append(placeable.size - placedCount)
                .append(" 条没铺上（可能那天已有同单元），可以在计划页拖一下。")
        }
        if (planDocStore == null) {
            append("计划文档存储没接上：这一周只排进了计划库，没写周文档。")
        }
    }
    return WeekPlanResult(
        week = week,
        items = deduped,
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
 * 把这一周的条目按「每天」预算分到每天（纯函数）。
 *
 * **每天**的预算是 [dailyMinutes]，不是一周预算——一周预算 daily×7 当一天用，
 * 120 分/天时一天能装 672 分，整周条目全堆周一（现场测试的 P0-1 就是这个）。
 * [existingMinutesByDay] 是这一天已经排好的分钟数：补排时从这里继续装，别把已经满的天塞爆。
 * 先算好再落文档/落库，保证周文档里写的天和实际 placeUnit 的天一致。
 */
private fun assignDays(
    items: List<PlannedItem>,
    start: LocalDate,
    end: LocalDate,
    dailyMinutes: Int,
    existingMinutesByDay: Map<LocalDate, Int> = emptyMap(),
): List<LocalDate> {
    val days = buildList {
        var cursor = start
        while (!cursor.isAfter(end)) {
            add(cursor)
            cursor = cursor.plusDays(1)
        }
    }
    if (days.isEmpty()) return items.map { start }
    val result = MutableList(items.size) { days.first() }
    var dayIndex = 0
    var used = existingMinutesByDay[days[0]] ?: 0
    items.forEachIndexed { index, item ->
        while (dayIndex < days.lastIndex && used > 0 && used + item.minutes > dailyMinutes) {
            dayIndex += 1
            used = existingMinutesByDay[days[dayIndex]] ?: 0
        }
        used += item.minutes
        result[index] = days[dayIndex]
    }
    return result
}

/**
 * 两个标题是不是同一件事：归一后相同，或都够长（≥6 字）且一个包含另一个。
 * 模型每次重排会换措辞（「阶段1：知识梳理」vs「阶段1知识梳理·第1周」），只比等号挡不住。
 */
private fun isSameTitle(a: String, b: String): Boolean {
    if (a == b) return true
    if (a.length < 6 || b.length < 6) return false
    return a.contains(b) || b.contains(a)
}

/** 标题归一：大小写、空格、标点、以及「的/与/及」都不算差异，用来挡模型换个说法的重复条目。 */
private fun normalizeTitle(raw: String): String =
    raw.lowercase()
        .filter { it.isLetterOrDigit() }
        .replace("的", "")
        .replace("与", "")
        .replace("及", "")

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
    overflow: Int = 0,
): String = buildString {
    appendLine("# " + week + " 本周计划")
    if (focus.isNotBlank()) appendLine("> 本周重点：" + focus)
    if (overflow > 0) appendLine("> 本周还剩 " + overflow + " 条排在待安排池（本周容量放不下，下一周优先补）。")
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
