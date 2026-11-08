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
import dev.trellis.domain.plan.UnitProgress
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
 * 输入：计划文档（大纲/上周文档）+ 上周完成情况 + 每天投入（**只做参考**：条目按天尽量摊开，
 * 不会因为超出参考分钟数被拒绝或丢进待安排池）。
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
        "2. 它读计划文档与上周完成情况，产出这一周的具体条目（学习/实践/复习 + 分钟），宿主**先写本周文档、再**把条目追加成单元、铺到每天；两步都会在回执里说清（周文档已写 / 已排 N 条）。每天投入只是参考：宿主会把条目按天尽量摊开，不会因为超出参考分钟数而漏排。",
        "3. 先读再排：如果计划文档里没有这一阶段的内容，先用 read_plan_doc 看清楚，必要时用 write_plan_doc 补；不要凭空编。",
        "4. 计划里已经有这一周的条目时，`plan_week` 默认直接返回「已经排过了」；确认要重排就传 replan=true（去重逻辑会跳过重复、只补空位；时间只做参考，不会漏排）。要不要重排由你判断。",
        "5. 排完跟用户说清「这周重点 + 每天大概多久 + 有没有实践」，不要只报数字。",
        "6. 已完成的计划（所有阶段都做完、待安排池也空了）不能再 plan_week，宿主会直接拒绝：那份计划保持完成状态、不再追加内容；要拓展就新开一份计划（create_learning_plan / research_plan，可带 goalId 挂到同一目标）。",
        "</how_to_plan_week>",
    ).joinToString("\n")

    val all: List<AgentTool> get() = listOf(planWeek)

    private val planWeek = object : AgentTool {
        override val name: String = "plan_week"

        override val description: String =
            "排某一周的具体计划：读大纲与上周完成情况，产出本周条目（学习/实践/复习），" +
                "**先把本周文档写进计划文档，再**把条目追加成单元、按每天参考分钟数尽量摊开到每天；" +
                "时间只做参考，不会因为超出参考而少排。这一周已经排过时默认直接拒绝；要重排就传 replan=true。"

        override val parameters: String =
            "week：可选，ISO 周（如 2026-W45）；省略=本周；" +
                "dailyMinutes：可选，这一周每天大概投入多少分钟（30–240）；省略=设置里的每天预计投入或 90；" +
                "replan：可选，true 表示这一周已经排过也继续重排（默认 false：已排过直接返回「已经排过了」；重排会去重、只补空位）。要不要重排由你判断。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult("计划库打不开，没排成。", isError = true)
            val planId = plan.activePlanId() ?: return ToolResult(
                "现在还没有进行中的计划。先用 research_plan 或 create_learning_plan 建一份。",
                isError = true,
            )
            // 已完成的计划对 AGENT 只读：在调模型、落文档、追加单元之前就挡住。
            completedPlanGuard(plan, planId)?.let { return it }
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

            val replan = when (
                arguments["replan"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ) {
                "true", "1", "yes" -> true
                else -> false
            }

            val result = planOneWeek(
                plan = plan,
                planDocStore = planDocStore(),
                llm = llm,
                clock = clock,
                planId = planId,
                week = week,
                dailyMinutes = daily,
                replan = replan,
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

/** 已经按天排好的一条：导师检查时要知道「哪一天要学什么」。 */
private data class ScheduleItem(val item: PlannedItem, val day: LocalDate)

/** 导师判断需要提前补的一条前置知识：放在依赖条目之前的那一天。 */
private data class PrereqTarget(
    val title: String,
    val chapter: String,
    val minutes: Int,
    val kind: String,
    val day: LocalDate,
    val beforeTitle: String,
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
    /**
     * 这一周已经排过时要不要继续。
     *
     * 默认 false：直接退回（数据安全，排两次会重复追加）。
     * true 时继续往下走，后面的去重会跳过已有条目、只补空位。
     */
    replan: Boolean = false,
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
    // 默认直接退回，调整交给计划页拖拽或 PlanAdminTools；模型明确 replan=true 时才继续，
    // 后面的去重/容量逻辑保证只补空位、不重复追加。
    val plannedAlready = runCatching { plan.week(planId, week) }
        .getOrNull()?.days?.flatMap { day -> day.items }.orEmpty()
    if (plannedAlready.isNotEmpty() && !replan) {
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
    // 每天已有安排要算进去，补排时从已排分钟继续装。
    // 时间只做参考（2026-11-08 用户定的：周上限 = 每天投入 × 7 只对 AGENT 起参考作用，
    // 不必须严格按时间来安排任务）：条目全部排进来，某天到参考值就摊到别的天，
    // 不再因为「剩余容量」把条目拒之周外或丢进待安排池。
    val start = if (week == thisWeek) maxOf(today, weekStart) else weekStart
    if (deduped.isEmpty()) {
        return WeekPlanResult(
            week = week,
            items = emptyList(),
            note = "这一周的条目都已经在计划里了，没有重复排；要调整就直接拖节点。",
        )
    }
    val dayOfItem = assignDays(deduped, start, weekEnd, dailyMinutes, existingMinutesByDay)
    val plannedByDay = linkedMapOf<String, MutableList<PlannedItem>>()
    dayOfItem.forEachIndexed { index, day ->
        plannedByDay.getOrPut(day.toString()) { mutableListOf() } += deduped[index]
    }
    val totalMinutes = deduped.sumOf { it.minutes }
    // 「参考量」：这段时间按每天参考分钟数加起来大概多少——只用来在回执里说明，
    // 不是上限（超出也照排，只是告诉用户内容比参考多）。
    val referenceMinutes =
        (java.time.temporal.ChronoUnit.DAYS.between(start, weekEnd) + 1).toInt() * dailyMinutes

    // ---- 导师检查：这周要学的东西，有没有「得先会」的前置知识 ----
    // 用户要的：导师先看一遍这一周的安排——比如周三要学微积分、周四要写 Python，
    // 就在周一/周二先把导数、环境这些前置补上，别到当天才卡住。只补真正必要的，失败就跳过。
    val tutorCheck = tutorPrerequisites(
        llm = llm,
        week = week,
        start = start,
        weekEnd = weekEnd,
        stageFocus = focus,
        outline = outline,
        schedule = deduped.mapIndexed { index, item -> ScheduleItem(item, dayOfItem[index]) },
        existingTitles = (existingTitles + acceptedTitles).toSet(),
    )
    val prerequisites = tutorCheck.items

    // ---- 第一步：先写周文档。写不成，这一周就整周不排（不落库、不铺天）。 ----
    var docWritten = false
    if (planDocStore != null) {
        val saved = runCatching {
            planDocStore.saveWeek(planId, week, weekDoc(week, focus, deduped, plannedByDay, prerequisites))
        }
        if (saved.isFailure) {
            val reason = saved.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: "未知原因"
            return WeekPlanResult(week, error = "周文档没写成（$reason），这一周没有排，计划库也没动；等文档能写了再试。")
        }
        docWritten = true
    }

    // ---- 第二步：追加单元。全部追加（时间只做参考，不再有「排不下留在池里」），失败时周文档已经写了，必须保留并说清。 ----
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

    // ---- 第三步：按先算好的安排逐日 placeUnit（全部条目都有安排）。 ----
    var placedCount = 0
    deduped.indices.forEach { index ->
        val day = dayOfItem[index].toString()
        val placed = plan.placeUnit(commandEnv(planId, "plan.week"), planId, week, unitIds[index], day)
        if (placed.ok) placedCount++
    }
    // ---- 第四步：把导师补的前置知识也追加进计划库，并按算好的「依赖条目之前」铺到天 ----
    var prereqPlaced = 0
    if (prerequisites.isNotEmpty()) {
        val prereqDrafts = prerequisites.map { prereq ->
            UnitDraft(
                chapter = prereq.chapter.ifBlank { "前置知识" },
                title = prereq.title,
                estimateMinutes = prereq.minutes.coerceIn(10, 45),
                kind = prereq.kind,
            )
        }
        val prereqUnits = plan.appendUnits(commandEnv(planId, "plan.prereq"), planId, prereqDrafts).value
        if (prereqUnits != null) {
            prerequisites.forEachIndexed { index, prereq ->
                val placed = plan.placeUnit(
                    commandEnv(planId, "plan.prereq"),
                    planId,
                    week,
                    prereqUnits[index],
                    prereq.day.toString(),
                )
                if (placed.ok) prereqPlaced++
            }
        }
    }
    val note = buildString {
        when {
            prerequisites.isNotEmpty() -> {
                append("导师检查后补了 ").append(prereqPlaced).append(" 条前置知识：")
                append(prerequisites.joinToString("；") { prereq ->
                    prereq.title + "（" + weekdayCn(prereq.day) + " " + prereq.day.toString().takeLast(5) +
                        "，为「" + prereq.beforeTitle + "」打底）"
                })
                append("。")
            }
            tutorCheck.checked -> append("导师看过这周安排：没有必须先补的前置知识。")
            else -> append("导师这次没给出前置检查结果（不影响这周的排布）。")
        }
        if (dropped > 0) append("去掉了 ").append(dropped).append(" 条本周已经有的重复条目。")
        if (totalMinutes > referenceMinutes) {
            append("本周合计约 ").append(totalMinutes).append(" 分钟（参考每天 ").append(dailyMinutes)
                .append(" 分钟）；时间只做参考，超出也照排，觉得多可以整体延期。")
        }
        if (placedCount < deduped.size) {
            append("有 ").append(deduped.size - placedCount)
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
 * 把这一周的条目按「每天参考值」尽量摊到每天（纯函数）。
 *
 * [dailyMinutes] 是**参考值不是上限**（2026-11-08 用户定的：周时间上限 = 每天投入 × 7 只对 AGENT
 * 起参考作用，不必须严格按时间来安排任务）。先按顺序往后填，当天到参考值就换下一天；
 * 整周都到参考值后回环摊到**当前分钟数最少**的那天——不把剩余条目堆在最后一天（现场出现过
 * 周日一天 20 件约 700 分钟），也不因为「装不下」把条目拒之周外。
 * [existingMinutesByDay] 是这一天已经排好的分钟数：补排时从这里继续装，别重复占满同一天。
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
    val usedByDay = days.associateWith { existingMinutesByDay[it] ?: 0 }.toMutableMap()
    val result = MutableList(items.size) { days.first() }
    var cursor = 0
    items.forEachIndexed { index, item ->
        // 从当前游标往后找第一处「还空着 / 装得下这条」的天；空着的天永远收（和旧行为一致）。
        var target = -1
        for (i in cursor..days.lastIndex) {
            val used = usedByDay.getValue(days[i])
            if (used == 0 || used + item.minutes <= dailyMinutes) {
                target = i
                break
            }
        }
        // 整周都已到参考值：不拒绝、也不堆最后一天——挑当前分钟数最少的那天继续放。
        if (target < 0) target = days.indices.minByOrNull { usedByDay.getValue(days[it]) } ?: 0
        usedByDay[days[target]] = usedByDay.getValue(days[target]) + item.minutes
        result[index] = days[target]
        cursor = target
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
    appendLine("参考：本周每天投入约 " + dailyMinutes + " 分钟（只是参考，内容多可以多排、整体延期也行，不必卡死）；条目 3–14 个；每条 10–45 分钟。")
    appendLine("内容规则：看视频/看书算 study；动手写代码、做项目、做题算 practice；复盘/重做错题/回顾算 review。")
    appendLine("交付物整理规则：整理 README/文档/提交历史/仓库结构这类收尾工作算 practice，只在阶段收尾周出现一次，不要每周都排。")
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
    prerequisites: List<PrereqTarget> = emptyList(),
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
    if (prerequisites.isNotEmpty()) {
        appendLine("## 导师补充的前置知识")
        prerequisites.forEach { prereq ->
            appendLine(
                "- " + prereq.day.toString() + "（" + weekdayCn(prereq.day) + "）· " + prereq.title +
                    "（" + kindLabel(prereq.kind) + " · 约 " + prereq.minutes + " 分钟）—— 为「" + prereq.beforeTitle + "」打底",
            )
        }
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

// ---------------------------------------------------------------------------
// 导师检查：本周要学的东西，有没有「得先会」的前置知识
// ---------------------------------------------------------------------------

private const val TUTOR_TIMEOUT_MS = 20_000L

private val TUTOR_SYSTEM_PROMPT: String = """
    你是这份学习计划的导师。下面给出这一周已经排好的安排（每一条都有日期）。
    你的任务是判断：这些条目里有哪些需要「前置知识」才能学懂——例如周三要学「微积分应用」但还没学导数，
    周四要写「Python 爬虫」但还没学 requests / HTML 基础，周五要做项目但环境还没装过。

    只输出一个 JSON 对象，不要 markdown、不要解释：
    {"prerequisites":[{"title":"前置条目标题（≤20 字）","chapter":"所属主题","kind":"study|practice","minutes":30,"day":"YYYY-MM-DD","before":"依赖的条目标题（逐字抄上面的）"}]}

    规则：
    1. 最多 3 条，只列真正必要的；本周不需要前置就给 {"prerequisites":[]}，不要凑数。
       注意：需要动手跑代码/装环境/用某个库或工具、但计划里还没学过相关基础的，都算「必要前置」，
       例如「按 README 从零跑通项目」之前要先有「环境搭建与依赖安装」，
       「写接口测试」之前要先有「测试框架入门」。不要因为条目看起来简单就一律给空数组。
    2. day 必须早于依赖条目那天，最好提前一天；如果依赖条目在周一，就把前置放在同一天最前面（day 写那天）。
    3. 前置条目本身要能在 10–45 分钟内做完（一个概念/一次环境搭建/一个小练习），不要写「学完整个数学」这种大条目。
    4. title 要具体、和依赖条目有明确衔接（如「导数与极限基础」「Python 环境与 requests 入门」）。
""".trimIndent()

/**
 * 让模型以「导师」视角看一遍本周安排，找出必须先补的前置知识，并算出该放哪天。
 *
 * 失败（超时/解析不出）返回 checked=false：导师检查是锦上添花，不能因为它让整周排不成。
 * 放天规则也在这里兜底：模型给的日期必须严格早于依赖条目那天，否则退到前一天；
 * 前一天早于本周开始就放本周第一天（周一的依赖条目只能同日热身）。
 */
private suspend fun tutorPrerequisites(
    llm: LlmClient,
    week: String,
    start: LocalDate,
    weekEnd: LocalDate,
    stageFocus: String,
    outline: String,
    schedule: List<ScheduleItem>,
    existingTitles: Set<String>,
): TutorCheck {
    if (schedule.isEmpty()) return TutorCheck(checked = false)
    val scheduleText = schedule.joinToString("\n") { entry ->
        "- " + entry.day.toString() + "（" + weekdayCn(entry.day) + "）：" + entry.item.title +
            "（" + kindLabel(entry.item.kind) + " · " + entry.item.minutes + " 分钟）"
    }
    val userPrompt = buildString {
        appendLine("这一周（" + week + "）的安排：")
        appendLine(scheduleText)
        if (stageFocus.isNotBlank()) appendLine("本周阶段焦点：" + stageFocus)
        appendLine("计划大纲（供判断前后知识依赖）：")
        appendLine(outline.ifBlank { "（没有文档）" })
    }
    val raw = withTimeoutOrNull(TUTOR_TIMEOUT_MS) {
        llm.complete(
            listOf(
                LlmMessage(LlmRole.System, TUTOR_SYSTEM_PROMPT),
                LlmMessage(LlmRole.User, userPrompt),
            ),
        )
    } ?: return TutorCheck(checked = false)
    val candidates = parseTutorPrereqs(raw) ?: return TutorCheck(checked = false)

    val normalizedExisting = existingTitles.map { normalizeTitle(it) }.toMutableList()
    val accepted = mutableListOf<PrereqTarget>()
    candidates.take(3).forEach { candidate ->
        val titleNorm = normalizeTitle(candidate.title)
        if (titleNorm.isBlank()) return@forEach
        if (normalizedExisting.any { isSameTitle(it, titleNorm) }) return@forEach
        val dependencyDay = schedule.firstOrNull {
            isSameTitle(normalizeTitle(it.item.title), normalizeTitle(candidate.before))
        }?.day
        var day = candidate.day
        if (dependencyDay != null) {
            // 前置必须在依赖条目之前；模型给了当天或更晚就退到前一天，前一天早于本周就同日热身。
            if (!day.isBefore(dependencyDay) || day.isBefore(start)) day = dependencyDay.minusDays(1)
            if (day.isBefore(start)) day = start
        } else if (day.isBefore(start) || day.isAfter(weekEnd)) {
            day = start
        }
        if (day.isAfter(weekEnd)) return@forEach
        accepted += PrereqTarget(
            title = candidate.title,
            chapter = candidate.chapter,
            minutes = candidate.minutes.coerceIn(10, 45),
            kind = candidate.kind,
            day = day,
            beforeTitle = candidate.before.ifBlank { "本周内容" },
        )
        normalizedExisting += titleNorm
    }
    return TutorCheck(checked = true, items = accepted)
}

/** 导师检查结果：[checked]=模型是否给出了可解析的结论（空列表也算检查过）。 */
private data class TutorCheck(val checked: Boolean, val items: List<PrereqTarget> = emptyList())

private data class PrereqCandidate(
    val title: String,
    val chapter: String,
    val minutes: Int,
    val kind: String,
    val day: LocalDate,
    val before: String,
)

private fun parseTutorPrereqs(raw: String): List<PrereqCandidate>? {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val root = runCatching {
        Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
    }.getOrNull() ?: return null
    val array = root["prerequisites"] as? JsonArray ?: return null
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val dayText = obj["day"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val day = runCatching { LocalDate.parse(dayText) }.getOrNull() ?: return@mapNotNull null
        val minutes = obj["minutes"]?.jsonPrimitive?.intOrNull ?: 30
        val kind = when (obj["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()) {
            "practice", "实践" -> "practice"
            "review", "复习" -> "review"
            else -> "study"
        }
        PrereqCandidate(
            title = title,
            chapter = obj["chapter"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            minutes = minutes,
            kind = kind,
            day = day,
            before = obj["before"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
        )
    }
}

// ---------------------------------------------------------------------------
// 「已完成计划对 AGENT 只读」的统一守卫（plan_week / 计划文档写 / 计划管理员写共用）
// ---------------------------------------------------------------------------

/**
 * 已完成计划的写操作拒绝文案。标题为空时退回「未命名」，保证回执里总有一个可指认的名字。
 */
internal fun completedPlanMessage(title: String): String {
    val name = title.trim().ifBlank { "未命名" }
    return "计划「$name」已完成，不再追加；拓展请用 create_learning_plan / research_plan 新开一份" +
        "（可带 goalId 挂到同一目标）。"
}

/**
 * 读计划的阶段与待安排池，用 [isPlanComplete] 判定它是不是「已完成」，是就返回拒绝结果。
 *
 * 约定：**读不到阶段/池子时不拦**（放行）——计划库刚打开、某条查询失败的瞬间不该让写工具全体罢工；
 * 完成态只是「别往旧计划里加东西」的保护，不是安全边界。调用方应在任何写之前调用它。
 */
internal fun completedPlanGuard(svc: LearningPlanService, planId: String): ToolResult? {
    val stages = runCatching { svc.stages(planId) }.getOrNull() ?: return null
    val poolEmpty = runCatching { svc.pool(planId).isEmpty() }.getOrNull() ?: return null
    // 没有阶段的计划按「全部单元做完」判完成；有阶段的计划不需要这条查询。
    val unitProgress = if (stages.isEmpty()) {
        runCatching { svc.unitProgress(planId) }.getOrNull() ?: return null
    } else {
        UnitProgress()
    }
    if (!isPlanComplete(stages, poolEmpty, unitProgress)) return null
    val title = runCatching { svc.planState(planId).title }.getOrNull().orEmpty()
    return ToolResult(completedPlanMessage(title), isError = true)
}
