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
 * - `research_plan` 处理「只有一句目标」的情况——让模型先把目标拆成单元和两周安排，
 *   宿主负责校验、落库、写计划文档。模型只出内容，落库仍走同一套命令
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
        "   **先调 `research_plan(goal=...)`**，不要先 search_bilibili 或 create_learning_plan：宿主会先把目标拆成单元与两周安排，",
        "   写成计划文档并直接建好计划（本周/下周已排、其余进待安排）。",
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
            "先调研再落计划：给一句学习目标，宿主会让模型拆成单元和前两周安排，" +
                "写成计划文档并直接建好计划（本周/下周已排、其余进待安排）。" +
                "适合「只有目标、没有现成材料」的情况；已有 B 站课程用 create_learning_plan(bvid=...)，" +
                "已有大纲用 create_learning_plan(units/weeks)。会写用户数据。"
        override val parameters: String =
            "goal：用户的学习目标原话（必填，越具体越好，例：「两周内复习完数据结构」）；" +
                "dailyMinutes：每天能投入的分钟数（15–240，默认先问用户；拿不准按 60）。"

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

            val today = clock.today()
            val thisWeek = isoWeekOf(today)
            val nextWeek = isoWeekOf(today.plusWeeks(1))

            val raw = runCatching {
                withTimeoutOrNull(30_000) {
                    llm.complete(
                        listOf(
                            LlmMessage(LlmRole.System, researchSystemPrompt(dailyMinutes, thisWeek, nextWeek)),
                            LlmMessage(LlmRole.User, researchUserPrompt(goal, today.toString(), thisWeek, nextWeek, dailyMinutes)),
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

            val draft = parseDraft(raw, thisWeek, nextWeek, fallbackTitle = goal.take(20))
                ?: return ToolResult(
                    "调研结果没解析成合法计划（模型输出不符合 JSON 约定：" +
                        "总单元 3–40、两三周内每周 1–7 个且标题一致）。可以重试一次；还不行就用 create_learning_plan 手写。",
                    isError = true,
                )

            val weeks = draft.weeks ?: packWeeks(draft.units, dailyMinutes, thisWeek, nextWeek)
            if (weeks.isEmpty()) {
                return ToolResult("调研结果里没有可排进本周/下周的单元，用 create_learning_plan 手写吧。", isError = true)
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
            val weekLines = commitments.joinToString("、") { "${it.week} 排 ${it.unitIndexes.size} 个" }
            return ToolResult(
                buildString {
                    append("调研完了。计划「").append(title).append("」已建好：共 ").append(units.size).append(" 个单元，")
                    append(weekLines)
                    if (poolCount > 0) append("，其余 ").append(poolCount).append(" 个进「待安排」池")
                    append("。每天按 ").append(dailyMinutes).append(" 分钟排。")
                    if (docPath != null) {
                        append("计划文档写在 plans/docs/").append(planId).append(".md（计划页「计划文档」可改条目再同步）。")
                    }
                    append("去「计划」页能看到周卡片和每天安排。")
                },
            )
        }
    }

    /** 调研目标 → 计划草案；解析失败返回 null，由调用方退回到手写路径。 */
    private fun parseDraft(raw: String, thisWeek: String, nextWeek: String, fallbackTitle: String): ResearchDraftPlan? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching {
            Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
        }.getOrNull() ?: return null

        val title = root.textOrNull("title")?.take(30)?.takeIf { it.isNotEmpty() } ?: fallbackTitle
        val rawUnits = root["units"] as? JsonArray ?: return null
        if (rawUnits.size !in 3..40) return null

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
                val allowed = setOf(thisWeek, nextWeek)
                val entries = weeksElement.entries
                    .map { (week, value) -> week.trim() to value }
                    .sortedBy { it.first }
                if (entries.isEmpty() || entries.size > 2) return null
                if (entries.any { it.first !in allowed }) return null
                if (entries.first().first != thisWeek) return null
                val parsed = entries.map { (week, value) ->
                    val names = (value as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { name -> name.isNotEmpty() } }
                        .orEmpty()
                    if (names.size !in 1..7) return null
                    if (names.any { it !in unitTitles }) return null
                    if (names.distinct().size != names.size) return null
                    week to names
                }
                parsed
            }
            else -> return null
        }
        return ResearchDraftPlan(title = title, units = units, weeks = weeks)
    }

    /** 模型没给 weeks（或给的不合规）时，按每天投入预算把单元塞进本周/下周。 */
    private fun packWeeks(
        units: List<ResearchUnitDraft>,
        dailyMinutes: Int,
        thisWeek: String,
        nextWeek: String,
    ): List<Pair<String, List<String>>> {
        val budget = (dailyMinutes * 7 * 0.8).toInt()
        val first = takeWeek(units, start = 0, budgetMinutes = budget)
        val secondStart = first.size
        val second = takeWeek(units, start = secondStart, budgetMinutes = budget)
        return buildList {
            if (first.isNotEmpty()) add(thisWeek to first.map { it.title })
            if (second.isNotEmpty()) add(nextWeek to second.map { it.title })
        }
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
            appendLine("- 验收：学完后能讲清「$title」每个单元的要点，并按计划完成本周、下周的安排。")
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

        private fun researchSystemPrompt(dailyMinutes: Int, thisWeek: String, nextWeek: String): String = """
            你是一位学习路径规划师。用户会给你一个学习目标；请把它拆成可执行的单元，并排出前两周的安排。
            只输出一个 JSON 对象，不要 Markdown 代码块，不要解释，不要多余文字。
            JSON 结构：
            {"title":"计划标题（不超过 20 字）","units":[{"title":"单元标题（不超过 24 字）","minutes":25,"chapter":"可选章节名"}],"weeks":{"$thisWeek":["单元标题"],"$nextWeek":["单元标题"]}}
            规则：
            - 总单元 6–24 个，覆盖从入门到目标的完整关键路径，按依赖顺序排列；
            - 每周 2–6 个单元，按每天 $dailyMinutes 分钟的节奏；只排本周和下一周，其余单元不出现在 weeks 里；
            - 单元 minutes 取 10–45 的整数；
            - weeks 的键必须是 $thisWeek 和 $nextWeek 中至少一个，且标题必须与 units 完全一致；
            - 不要虚构不存在的前置知识，不要输出用户没要求的资源链接。
        """.trimIndent()

        private fun researchUserPrompt(
            goal: String,
            today: String,
            thisWeek: String,
            nextWeek: String,
            dailyMinutes: Int,
        ): String = """
            目标：$goal
            今天：$today（本周 $thisWeek，下一周 $nextWeek），每天预计投入 $dailyMinutes 分钟。
            请给出前两周的计划草案 JSON。
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
