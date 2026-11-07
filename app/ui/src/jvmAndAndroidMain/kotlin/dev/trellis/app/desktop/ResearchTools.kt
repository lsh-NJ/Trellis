package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.PlanDocStore
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「由面到点」的第一步：只有目标时，先调研出一条可执行的路径。
 *
 * 和 [PlanTools] 的分工：
 * - `create_learning_plan` 处理「已经有材料」的情况（B 站 bvid / 用户给的大纲）——结构是确定的；
 * - `research_plan` 处理「只有一句目标」的情况——让模型先做**全局规划**：把目标拆成阶段/单元，
 *   排出从本周起的完整周表；宿主负责校验、落库、写计划文档。模型只出内容，落库仍走同一套命令
 *   （`createProcess → saveDraft → acceptDraft`），失败路径与手工建计划一致。
 *
 * 调研结果同时写成计划文档（`<home>/plans/docs/<planId>.md`），
 * 用户之后可以在计划页「计划文档」里改条目，再用「同步本周到计划」追加进池。
 */
class ResearchTools(
    private val service: () -> LearningPlanService?,
    private val planDocStore: () -> PlanDocStore?,
    private val planDbError: () -> String? = { null },
    private val weeklyCapacity: () -> Int? = { null },
    private val clock: TrellisClock = SystemTrellisClock(),
    private val llm: LlmClient,
    private val onChanged: () -> Unit = {},
) {

    val guidance: String = listOf(
        "<how_to_research_plan>",
        "1. 用户用**目标式说法**且没指定材料时（「我想学会 Python 数据分析」「两周复习完数据结构」「系统掌握概率论」），",
        "   **先调 `research_plan(goal=...)`**，不要先 search_bilibili 或 create_learning_plan：宿主会先做**全局规划**——",
        "   把目标拆成阶段/单元、排出从本周起的完整周表，写成计划文档并直接建好计划（之后每周局部推进）。",
        "   用户提到考试/截止日期（「还有两周考试」）就先按今天换算成 YYYY-MM-DD，再把 deadline 一起传进去；不要把它换算成「先排两周」，也不要忽略它。",
        "2. 用户**明确指定了材料**（给了 bvid/链接、点名某门课、说「用这个视频速通」）才走 `create_learning_plan(bvid=...)`；",
        "   用户给了大纲/书单就手写 `units + weeks`。",
        "3. research_plan 失败（模型超时/输出不可用）时退回 `create_learning_plan` 手写路径，并把原因说清楚，不要假装建好了。",
        "4. 同一轮不要同时调 research_plan 和 create_learning_plan：那会建出两份计划。研究完直接说结论，等用户去计划页看。",
        "</how_to_research_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(ResearchPlan())

    private inner class ResearchPlan : AgentTool {
        override val name: String = "research_plan"
        override val description: String =
            "先调研再落计划：给一句学习目标，宿主会让模型做**全局规划**——把目标拆成阶段/单元、" +
                "排出从本周起的完整周表，写成计划文档并直接建好计划（之后每周自动铺到天）。" +
                "适合「只有目标、没有现成材料」的情况；已有 B 站课程用 create_learning_plan(bvid=...)，" +
                "已有大纲用 create_learning_plan(units/weeks)。会写用户数据。"
        override val parameters: String =
            "goal：用户的学习目标原话（必填，越具体越好，例：「两周内复习完数据结构」）；" +
                "dailyMinutes：建议值，每天能投入的分钟数（15–240；没说就按设置里的每天预计投入/60，不要单独问用户）；" +
                "deadline：可选截止日期（YYYY-MM-DD）。用户说「XX 天后考试」「月底前学完」时按今天算出日期传进来，周表会覆盖到那天并留出复习。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult(
                buildString {
                    append("计划库打不开，没法调研落库。")
                    planDbError()?.takeIf { it.isNotBlank() }?.let { append("具体原因：").append(it).append("。") }
                    append("先调 get_diagnostics 看清楚环境，再决定重试还是让用户修。")
                },
                isError = true,
            )

            val goal = arguments.textArg("goal")
                ?: return ToolResult("goal 必填：把用户想学的东西写进来（原话也行）。", isError = true)
            if (goal.length < 2) return ToolResult("goal 太短了，写清要学什么。", isError = true)
            val dailyMinutes = arguments.intArg("dailyMinutes")?.coerceIn(15, 240)
                ?: (weeklyCapacity()?.div(7)?.takeIf { it >= 15 } ?: 60)
            val deadlineText = arguments.textArg("deadline")
            val deadline = deadlineText?.let { raw ->
                runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
                    ?: return ToolResult("deadline 要写成 YYYY-MM-DD，现在是「$raw」。", isError = true)
            }

            val today = clock.today()
            if (deadline != null && deadline < today) {
                return ToolResult("deadline（$deadlineText）已经过去了；不要给过去的截止日期。", isError = true)
            }
            val thisWeek = isoWeekOf(today)
            val deadlineWeek = deadline?.let { isoWeekOf(it) }

            val raw = runCatching {
                withTimeoutOrNull(30_000) {
                    llm.complete(
                        listOf(
                            LlmMessage(LlmRole.System, researchSystemPrompt(dailyMinutes, thisWeek, deadlineText)),
                            LlmMessage(LlmRole.User, researchUserPrompt(goal, today.toString(), thisWeek, deadlineText, dailyMinutes)),
                        ),
                    )
                }
            }.getOrNull()
            if (raw.isNullOrBlank()) {
                return ToolResult(
                    "调研这次没跑起来（模型超时或返回空）。可以重试一次；还不行就用 create_learning_plan 手写 units + weeks 建计划。",
                    isError = true,
                )
            }

            val draft = parseDraft(raw, thisWeek, deadlineWeek, fallbackTitle = goal.take(20))
                ?: return ToolResult(
                    "调研结果没解析成合法计划（模型输出不符合 JSON 约定：" +
                        "总单元 3–60、周表从本周起一周接一周、每周 1–14 个、每个单元都要且只能出现在一周里）。" +
                        "可以重试一次；还不行就用 create_learning_plan 手写。",
                    isError = true,
                )

            val weeks = draft.weeks ?: packWeeks(draft.units, dailyMinutes, thisWeek)
            if (weeks.isEmpty()) {
                return ToolResult("调研结果里没有可排的单元，用 create_learning_plan 手写吧。", isError = true)
            }

            val title = draft.title
            val units = draft.units.map { UnitDraft(chapter = it.chapter, title = it.title, estimateMinutes = it.minutes) }
            val indexByTitle = draft.units.mapIndexed { index, unit -> unit.title to index }.toMap()
            val commitments = weeks.map { (week, names) ->
                CommitmentDraft(week = week, unitIndexes = names.map { indexByTitle.getValue(it) })
            }

            val planId = writePlan(plan, title, goal, dailyMinutes, units, commitments)
                ?: return ToolResult("建计划失败（写库没成功）。可以用 create_learning_plan 手写路径重试。", isError = true)

            val docPath = writePlanDoc(planId, title, goal, units, commitments)
            onChanged()

            val placedTitles = commitments.flatMap { draft2 -> draft2.unitIndexes.map { units[it].title } }.toSet()
            val poolCount = units.count { it.title !in placedTitles }
            val weekLines = if (commitments.size <= 3) {
                commitments.joinToString("、") { "${it.week} 排 ${it.unitIndexes.size} 个" }
            } else {
                commitments.take(2).joinToString("、") { "${it.week} 排 ${it.unitIndexes.size} 个" } +
                    " … 最后 " + commitments.last().week
            }
            return ToolResult(
                buildString {
                    append("调研完了。计划「").append(title).append("」已建好：共 ").append(units.size).append(" 个单元，")
                    append("全局周表 ").append(commitments.size).append(" 周（").append(weekLines).append("）")
                    if (poolCount > 0) append("，其余 ").append(poolCount).append(" 个进「待安排」池")
                    if (deadlineText != null) append("，覆盖到 ").append(deadlineText).append(" 的截止日")
                    append("。每天按 ").append(dailyMinutes).append(" 分钟排（只是建议，想改随时说）。")
                    if (docPath != null) {
                        append("计划文档写在 plans/docs/").append(planId).append(".md（计划页「计划文档」可改条目再同步）。")
                    }
                    append("去「计划」页能看到全局周卡片和每天安排。")
                },
            )
        }
    }

    /** 调研目标 → 全局计划草案；解析失败返回 null，由调用方退回到手写路径。 */
    private fun parseDraft(
        raw: String,
        thisWeek: String,
        deadlineWeek: String?,
        fallbackTitle: String,
    ): ResearchDraftPlan? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        }.getOrNull() ?: return null

        val title = root.textOrNull("title")?.take(30)?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val rawUnits = root["units"] as? JsonArray ?: return null
        if (rawUnits.size !in 3..60) return null

        val units = ArrayList<ResearchUnitDraft>(rawUnits.size)
        for (element in rawUnits) {
            val obj = element as? JsonObject ?: return null
            val name = obj.textOrNull("title")?.take(40)?.takeIf { it.isNotEmpty() } ?: return null
            if (units.any { it.title == name }) return null
            val minutes = obj["minutes"]?.jsonPrimitive?.intOrNull?.coerceIn(10, 45) ?: 25
            val chapter = obj.textOrNull("chapter")?.takeIf { it.isNotEmpty() } ?: "未分章"
            units += ResearchUnitDraft(title = name, minutes = minutes, chapter = chapter)
        }
        val unitTitles = units.map { it.title }.toSet()

        val weeksElement = root["weeks"]
        val weeks: List<Pair<String, List<String>>>? = when (weeksElement) {
            null -> null
            is JsonObject -> {
                val entries = weeksElement.entries
                    .map { (week, value) -> week.trim() to value }
                    .sortedBy { it.first }
                if (entries.isEmpty()) return null
                if (entries.first().first != thisWeek) return null
                if (deadlineWeek != null && entries.any { it.first > deadlineWeek }) return null
                val firstMonday = isoWeekStart(thisWeek) ?: return null
                val covered = mutableSetOf<String>()
                entries.forEachIndexed { position, (week, value) ->
                    if (isoWeekStart(week) == null) return null
                    if (week != isoWeekOf(firstMonday.plusWeeks(position.toLong()))) return null
                    val names = (value as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { name -> name.isNotEmpty() } }
                        .orEmpty()
                    if (names.size !in 1..14) return null
                    if (names.any { it !in unitTitles }) return null
                    if (names.distinct().size != names.size) return null
                    if (!covered.addAll(names)) return null
                }
                if (covered != unitTitles) return null
                entries.map { (week, value) ->
                    val names = (value as JsonArray)
                        .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { name -> name.isNotEmpty() } }
                    week to names
                }
            }
            else -> return null
        }
        return ResearchDraftPlan(title = title, units = units, weeks = weeks)
    }

    /** 模型没给周表（或给的不合规）时，按每天投入预算把单元排成全局周表。 */
    private fun packWeeks(
        units: List<ResearchUnitDraft>,
        dailyMinutes: Int,
        thisWeek: String,
    ): List<Pair<String, List<String>>> {
        val firstMonday = isoWeekStart(thisWeek) ?: return emptyList()
        val budget = (dailyMinutes * 7 * 0.8).toInt()
        val weeks = mutableListOf<Pair<String, List<String>>>()
        var start = 0
        var index = 0
        while (start < units.size) {
            val picked = takeWeek(units, start, budget)
            if (picked.isEmpty()) break
            weeks += isoWeekOf(firstMonday.plusWeeks(index.toLong())) to picked.map { it.title }
            start += picked.size
            index++
        }
        return weeks
    }

    private fun takeWeek(
        units: List<ResearchUnitDraft>,
        start: Int,
        budgetMinutes: Int,
    ): List<ResearchUnitDraft> {
        val picked = mutableListOf<ResearchUnitDraft>()
        var used = 0
        var index = start
        while (index < units.size && picked.size < 7) {
            val minutes = units[index].minutes
            if (picked.isNotEmpty() && used + minutes > budgetMinutes) break
            picked += units[index]
            used += minutes
            index++
        }
        return picked
    }

    private fun writePlan(
        plan: LearningPlanService,
        title: String,
        goal: String,
        dailyMinutes: Int,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
    ): String? {
        val created = plan.createProcess(
            env(null, "plan.research"),
            title = title,
            goal = goal,
            weeklyCapacityMinutes = dailyMinutes * 7,
        )
        val createdValue = created.value ?: return null
        val saved = plan.saveDraft(
            env(createdValue.planId, "plan.research.save"),
            planId = createdValue.planId,
            versionId = null,
            units = units,
            commitments = commitments,
        )
        val savedValue = saved.value ?: return null
        val accepted = plan.acceptDraft(
            env(createdValue.planId, "plan.research.accept"),
            versionId = savedValue.versionId,
            firstWeek = commitments.first().week,
        )
        if (accepted.value == null) return null
        return createdValue.planId
    }

    /** 计划文档只是审计/可编辑副本：写失败不影响计划本身，返回 null 让调用方少说一句。 */
    private fun writePlanDoc(
        planId: String,
        title: String,
        goal: String,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
    ): String? {
        val store = planDocStore() ?: return null
        val markdown = buildPlanDocMarkdown(title, goal, units, commitments)
        return runCatching {
            store.save(planId, markdown)
            store.fileFor(planId).path
        }.getOrNull()
    }

    companion object {
        /** 把调研结果渲染成计划文档：目标 + 已排周 + 待安排，格式与 [PlanDocStore] 解析一致。 */
        fun buildPlanDocMarkdown(
            title: String,
            goal: String,
            units: List<UnitDraft>,
            commitments: List<CommitmentDraft>,
        ): String = buildString {
            appendLine("# 目标与验收")
            appendLine("- $goal")
            appendLine("- 验收：学完后能讲清「$title」每个单元的要点，并按全局周表逐周完成。")
            appendLine()
            val placedTitles = mutableSetOf<String>()
            commitments.forEach { commitment ->
                appendLine("## " + commitment.week)
                commitment.unitIndexes.forEach { index ->
                    val unit = units[index]
                    placedTitles += unit.title
                    appendLine("- " + unit.title + "（约 " + unit.estimateMinutes + " 分钟）")
                }
                appendLine()
            }
            val pool = units.filter { it.title !in placedTitles }
            if (pool.isNotEmpty()) {
                appendLine("## 待安排")
                pool.forEach { unit -> appendLine("- " + unit.title + "（约 " + unit.estimateMinutes + " 分钟）") }
            }
        }.trimEnd() + "\n"

        private fun researchSystemPrompt(dailyMinutes: Int, thisWeek: String, deadline: String?): String = """
            你是一位学习路径规划师。用户会给你一个学习目标；请先做**全局**规划：
            把目标拆成阶段/单元，再把**全部单元**分配到从本周开始的连续各周，直到达成目标。
            只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要多余文字。
            JSON 结构：
            {"title":"计划标题（不超过 20 字）","units":[{"title":"单元标题（不超过 24 字）","minutes":25,"chapter":"可选章节名"}],"weeks":{"$thisWeek":["单元标题","…"]}}
            规则：
            - 总单元 3–60 个，覆盖从入门到目标的完整关键路径，按依赖顺序排列；
            - weeks 必须从 $thisWeek 开始、一周接一周连续（中间不跳周）；周数不设上限，但要与单元数量匹配；
            - 每周 1–14 个单元，按每天 $dailyMinutes 分钟的节奏估算（只是建议，可以灵活）；
            - 每个单元必须且只能出现在一周里：units 里的每个标题都要在 weeks 里出现一次；
            - 单元 minutes 取 10–45 的整数；
            - weeks 里写的标题必须与 units 完全一致；不要输出用户没要求的资源链接。""" +
            if (deadline != null) {
                "\n            - 用户给了截止日期 $deadline：最后一周不能晚于截止日所在周，并留出 1–2 周复习/模考（可以是复习类单元）。"
            } else {
                ""
            }

        private fun researchUserPrompt(
            goal: String,
            today: String,
            thisWeek: String,
            deadline: String?,
            dailyMinutes: Int,
        ): String = """
            目标：$goal
            今天：$today（本周 $thisWeek）${if (deadline != null) "；截止日期：$deadline" else ""}，每天预计投入 $dailyMinutes 分钟（建议值）。
            请给出从本周起的**全局**周表草案 JSON（覆盖到目标达成/截止日期）。
        """.trimIndent()
    }
}

private data class ResearchUnitDraft(val title: String, val minutes: Int, val chapter: String)

private data class ResearchDraftPlan(
    val title: String,
    val units: List<ResearchUnitDraft>,
    val weeks: List<Pair<String, List<String>>>?,
)

private fun JsonObject.textOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.textArg(name: String): String? = textOrNull(name)

private fun JsonObject.intArg(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

private fun ResearchTools.env(planId: String?, origin: String): CommandEnvelope = CommandEnvelope(
    commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
    actor = Actor.AGENT,
    planId = planId,
    origin = origin,
)
