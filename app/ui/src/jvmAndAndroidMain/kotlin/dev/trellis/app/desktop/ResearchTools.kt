package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.StageDraft
import dev.trellis.feature.learning.PlanDocStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「由面到点」第一步：只有一句目标时，先做**全局规划**。
 *
 * 产出的是「空壳计划 + 阶段表 + 大纲 + 排好的第一周」：
 * - `createShell` 建好 process/plan/生效版本（0 单元）；
 * - `setStages` 把阶段区间写进计划（写失败不挡流程，大纲文档里还有一份）；
 * - 计划文档写目标、验收、各阶段的周数与焦点；
 * - 每个阶段一条里程碑；有 goalId 就挂上，没有就新建一个目标；
 * - 同一回合用 [planOneWeek] 把**第一周**排出来（先写周文档、再落库铺天），不让用户建完计划面对空周；
 * - 返回每天投入的建议值，由 AGENT 用 `ask_choices` 让用户拍板（不自己定死）。
 *
 * 后续周仍留给每周的 `plan_week`（见 [PlanWeekTools]）。
 */
class ResearchTools(
    private val service: () -> LearningPlanService?,
    private val planDocStore: () -> PlanDocStore?,
    private val planDbError: () -> String? = { null },
    private val weeklyCapacity: () -> Int? = { null },
    private val clock: TrellisClock = SystemTrellisClock(),
    private val llm: LlmClient,
    /** 网络调研网关；null 时按「没有网络」走（测试/未配置 key）。 */
    private val research: ResearchGateway? = null,
    /** 计划落库后回调新建的 planId：调用方拿它把计划页切到新计划。 */
    private val onChanged: (String) -> Unit = {},
) {

    val guidance: String = listOf(
        "<how_to_research_plan>",
        "1. 用户只有一句目标、没指定材料时（「我想当 AI 应用工程师」「两周复习完数据结构」），**先调 `research_plan(goal=...)`**：",
        "   宿主会先用 DeepSeek 官方搜索 + 网页抓取查资料，再做全局规划——拆阶段，建**空壳计划**（还没有单元），",
        "   写大纲文档，每个阶段一条里程碑，并给出每天投入建议（这些资料只用于规划，不改用户笔记）。",
        "   有考试/截止日期就先按今天换算成 YYYY-MM-DD 传给 deadline；不要换算成「先排两周」。",
        "2. research_plan 之后**用 ask_choices 让用户拍板每天投入**（如「先按每天 90 分钟排？」60/90/120/自定义），",
        "   用户选了就调 `set_plan_pace` 写进去；不要替用户定死，也不要一次问超过一个问题。",
        "3. 具体单元按周排：research_plan 建完计划会顺手把**第一周**排出来；之后每周开始时用 `plan_week` 从大纲定这一周的内容（宿主铺到天、写周文档）。",
        "4. 用户明确给了材料（bvid、课程名、大纲）就走 `create_learning_plan`，不要用 research_plan。",
        "5. research_plan 失败（超时/输出不可用）退回 create_learning_plan 手写路径，并说清原因，不要假装建好了。",
        "</how_to_research_plan>",
    ).joinToString("\n")

    val all: List<AgentTool> get() = listOf(researchPlan)

    private val researchPlan = object : AgentTool {
        override val name: String = "research_plan"

        override val description: String =
            "只有一句学习目标时先做全局规划：拆阶段、建空壳计划、写大纲文档、每个阶段一条里程碑，并建议每天投入。具体单元留给每周 plan_week。"

        override val parameters: String =
            "goal：学习目标，一句话（必填，例：我想当 AI 应用工程师）；" +
                "deadline：可选截止日期（YYYY-MM-DD）；用户说「还有两周考试」「年底前」时按今天换算；" +
                "dailyMinutes：可选，每天大概投入多少分钟（30–240）；用户没说就省略，宿主按设置里的每天预计投入给建议值。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plan = service() ?: return ToolResult(
                buildString {
                    append("计划库打不开，没写成。")
                    planDbError()?.takeIf { it.isNotBlank() }?.let { append("具体原因：").append(it).append("。") }
                    append("先调 get_diagnostics 看清楚环境，再决定重试、绕开还是让用户做一个真能执行的修复动作。")
                },
                isError = true,
            )
            val goal = arguments["goal"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (goal.isEmpty()) return ToolResult("要写一句目标（goal），例如「我想当 AI 应用工程师」。", isError = true)
            val today = clock.today()
            val deadlineArg = arguments["deadline"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val deadline: String? = when {
                deadlineArg.isEmpty() -> null
                else -> {
                    val parsed = runCatching { LocalDate.parse(deadlineArg) }.getOrNull()
                        ?: return ToolResult("deadline 要写成 YYYY-MM-DD，现在是「$deadlineArg」。", isError = true)
                    if (parsed.isBefore(today)) return ToolResult("deadline（$deadlineArg）已经过去了。", isError = true)
                    parsed.toString()
                }
            }
            val thisWeek = isoWeekOf(today)
            val dailySuggestion = (arguments["dailyMinutes"]?.jsonPrimitive?.intOrNull ?: weeklyCapacity()?.div(7) ?: 60)
                .coerceIn(30, 240)
            val sources = research?.let { gateway ->
                withTimeoutOrNull(RESEARCH_TIMEOUT_MS) {
                    runCatching { gateway.gather(goal) }.getOrNull()
                }
            }
            val raw = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                llm.complete(
                    listOf(
                        LlmMessage(LlmRole.System, shellSystemPrompt(thisWeek, deadline, dailySuggestion, sources != null)),
                        LlmMessage(LlmRole.User, shellUserPrompt(goal, today.toString(), thisWeek, deadline, sources?.render())),
                    ),
                )
            } ?: return ToolResult(
                "调研模型没在 " + (PLAN_TIMEOUT_MS / 1000) + " 秒内回话，这次没写成。可以稍后重试，或改用 create_learning_plan 手写大纲。",
                isError = true,
            )
            val draft = parseShell(raw, thisWeek, deadline?.let { isoWeekOf(LocalDate.parse(it)) }, fallbackTitle = goal.take(16))
                ?: return ToolResult(
                    "调研结果没解析成阶段表（模型可能没说 JSON）。这次没建计划；可以重试，或改用 create_learning_plan 手写。",
                    isError = true,
                )
            val created = plan.createShell(
                env(null, "plan.research"),
                title = draft.title,
                goal = goal,
                deadline = deadline,
            )
            val planId = created.value?.planId
                ?: return ToolResult("建计划失败：" + (created.error?.message ?: "未知原因"), isError = true)

            // 阶段区间先写进计划：每周的单元铺进去之前就要有阶段（有承诺周后阶段区间会被校验），
            // 所以这一步必须早于下面的第一周排期。写失败不影响计划本身（大纲文档里还有一份阶段表）。
            val stageDrafts = stageDraftsFor(draft, thisWeek)
            val stagesWritten = stageDrafts.isNotEmpty() && runCatching {
                plan.setStages(env(planId, "plan.stages"), planId, stageDrafts).ok
            }.getOrElse {
                appendFailedLog("阶段区间写入失败：" + it.message)
                false
            }

            // 目标 + 里程碑：失败不影响计划本身（计划已经建好了）。
            val goalRef = runCatching {
                plan.createGoal(
                    env(planId, "plan.goal"),
                    title = goal.take(80),
                    kind = "goal",
                    intent = goal,
                    success = "按阶段推进并完成每个里程碑",
                    deadline = deadline,
                )
            }.getOrNull()?.value
            goalRef?.let { ref ->
                runCatching { plan.linkPlanToGoal(env(planId, "plan.goal_link"), planId, ref.goalId) }
                draft.stages.forEach { stage ->
                    runCatching {
                        plan.addMilestone(env(planId, "plan.milestone"), ref.goalId, stage.name + "（约 " + stage.weeks + " 周）")
                    }
                }
            }

            planDocStore()?.let { store ->
                runCatching { store.save(planId, buildOutline(goal, draft)) }
                    .onFailure { appendFailedLog("计划文档写入失败：" + it.message) }
            }

            // 同一回合把第一周排出来：复用 plan_week 的共享实现（先写周文档、再落库铺天）。
            // 不让用户「建完计划面对一个空周」；失败不算建计划失败，回执里说清并允许 plan_week 重试。
            val firstWeek = runCatching {
                planOneWeek(
                    plan = plan,
                    planDocStore = planDocStore(),
                    llm = llm,
                    clock = clock,
                    planId = planId,
                    week = thisWeek,
                    dailyMinutes = dailySuggestion,
                    stageFocus = draft.stages.first().focus,
                )
            }.getOrNull()
            onChanged(planId)

            val totalWeeks = draft.stages.sumOf { it.weeks }
            return ToolResult(
                buildString {
                    append("计划「").append(draft.title).append("」已建好（空壳 + 大纲）：").append(draft.stages.size).append(" 个阶段、共约 ")
                    append(totalWeeks).append(" 周")
                    deadline?.let { append("，覆盖到 ").append(it).append(" 的截止日") }
                    append("。")
                    append("计划文档和每个阶段的里程碑都写好了；先按每天 ").append(draft.dailyMinutes).append(" 分钟估的（只是建议）。")
                    if (stagesWritten) append("阶段区间也写进计划了（").append(stageDrafts.size).append(" 个阶段，计划页能看到进度）。")
                    if (firstWeek != null && firstWeek.error == null) {
                        append("第一周（").append(thisWeek).append("）已经排好：").append(firstWeek.placed).append(" 条")
                        if (firstWeek.docWritten) append("，本周文档也写好了")
                        append("。")
                    } else {
                        append("第一周（").append(thisWeek).append("）这次没排成")
                        firstWeek?.error?.let { append("：").append(it) }
                        append("；可以调 plan_week 补排。")
                    }
                    if (sources != null) {
                        append("这次参考了网上搜到的 ").append(sources.hits.size).append(" 条资料（其中 ")
                            .append(sources.pages.size).append(" 篇读了正文），比如「")
                            .append(sources.hits.take(2).joinToString("」「") { it.title.take(24) })
                            .append("」。")
                    }
                    append("下一步：用 ask_choices 让用户拍板每天投入（如 60 / 90 / 120 分钟），选了就 set_plan_pace；")
                    append("之后每周开始时用 plan_week 排这一周的具体内容。")
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

    private fun appendFailedLog(message: String?) {
        // 由调用方（AppRuntime）统一写 app.log；这里只在没有回调时静默。
    }

    private fun parseShell(raw: String, thisWeek: String, deadlineWeek: String?, fallbackTitle: String): ShellDraft? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject }.getOrNull()
            ?: return null
        val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle
        val daily = root["dailyMinutes"]?.jsonPrimitive?.intOrNull?.coerceIn(30, 240) ?: DEFAULT_DAILY_MINUTES
        val stagesElement = root["stages"] as? JsonArray ?: return null
        if (stagesElement.isEmpty() || stagesElement.size > MAX_STAGES) return null
        val stages = stagesElement.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val weeks = obj["weeks"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (weeks !in 1..MAX_STAGE_WEEKS) return@mapNotNull null
            val focus = obj["focus"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            ShellStage(name = name.take(40), weeks = weeks, focus = focus)
        }
        if (stages.size != stagesElement.size) return null
        val totalWeeks = stages.sumOf { it.weeks }
        if (totalWeeks > MAX_TOTAL_WEEKS) return null
        if (deadlineWeek != null) {
            val firstMonday = isoWeekStart(thisWeek) ?: return null
            val lastWeek = isoWeekOf(firstMonday.plusWeeks((totalWeeks - 1).toLong()))
            if (lastWeek > deadlineWeek) return null
        }
        return ShellDraft(title = title, dailyMinutes = daily, stages = stages)
    }

    /** 大纲阶段 → 计划里的阶段区间：从本周一（含）起按各阶段周数依次铺，end 是最后一周的周日。 */
    private fun stageDraftsFor(draft: ShellDraft, thisWeek: String): List<StageDraft> {
        var cursor = isoWeekStart(thisWeek) ?: return emptyList()
        return draft.stages.mapIndexed { index, stage ->
            val start = cursor
            val end = start.plusWeeks(stage.weeks.toLong()).minusDays(1)
            cursor = start.plusWeeks(stage.weeks.toLong())
            StageDraft(
                name = stage.name,
                weekStart = isoWeekOf(start),
                weekEnd = isoWeekOf(end),
                focus = stage.focus,
                sortOrder = index,
            )
        }
    }

    private fun buildOutline(goal: String, draft: ShellDraft): String = buildString {
        appendLine("# 目标与验收")
        appendLine("- 目标：" + goal)
        appendLine("- 验收：每个阶段的里程碑都完成，并按周推进（每周用 plan_week 排这一周的内容）。")
        appendLine()
        appendLine("## 阶段")
        draft.stages.forEachIndexed { index, stage ->
            append("- 阶段 ").append(index + 1).append(" · ").append(stage.name).append("（约 ").append(stage.weeks).append(" 周）")
            if (stage.focus.isNotBlank()) append("：").append(stage.focus)
            appendLine()
        }
    }

    private fun shellSystemPrompt(thisWeek: String, deadline: String?, dailyMinutes: Int, hasSources: Boolean): String =
        buildString {
            appendLine("你是学习规划师。用户只有一句目标，你要先做**全局规划**（不是排具体单元）：把目标拆成阶段，估算每个阶段的周数。")
            appendLine("只输出一个 JSON 对象，不要 markdown、不要解释：")
            appendLine("{\"title\":\"计划标题（≤16 字）\",\"dailyMinutes\":" + dailyMinutes + ",\"stages\":[{\"name\":\"阶段名（≤16 字）\",\"weeks\":2,\"focus\":\"这一阶段聚焦什么（≤30 字）\"}]}")
            appendLine("约束：阶段 1–8 个，每个阶段 1–12 周，总周数 ≤ 40；这个计划从 " + thisWeek + " 这一周开始。")
            if (deadline != null) appendLine("用户给了截止日期 " + deadline + "，总周数要能覆盖到那个日期之前。")
            appendLine("阶段要覆盖从入门到能做出东西的完整路线：通识/理论学习在前，动手实践（写代码、做项目）后置。")
            if (hasSources) {
                appendLine("用户消息里会附一段 <research_sources>（宿主从网上搜到的资料）。")
                appendLine("优先用它里面的事实与结构（比如岗位要求、常见学习顺序、真实项目），引用时写出站点名；")
                appendLine("资料与目标无关就忽略；不要编造链接或资料里没有的课程。")
            }
        }

    private fun shellUserPrompt(
        goal: String,
        today: String,
        thisWeek: String,
        deadline: String?,
        sources: String?,
    ): String = buildString {
        appendLine("目标：" + goal)
        appendLine("今天：" + today + "，本周：" + thisWeek)
        deadline?.let { appendLine("截止日期：" + it) }
        if (!sources.isNullOrBlank()) appendLine(sources)
        appendLine("请按上述 JSON 格式输出阶段表。")
    }

    private data class ShellStage(val name: String, val weeks: Int, val focus: String)

    private data class ShellDraft(val title: String, val dailyMinutes: Int, val stages: List<ShellStage>)

    private companion object {
        const val PLAN_TIMEOUT_MS = 30_000L
        const val DEFAULT_DAILY_MINUTES = 90
        const val MAX_STAGES = 8
        const val MAX_STAGE_WEEKS = 12
        const val MAX_TOTAL_WEEKS = 40
        const val RESEARCH_TIMEOUT_MS = 45_000L
    }
}
