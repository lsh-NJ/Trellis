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
                "deadline：可选截止日期（YYYY-MM-DD，必须是今天之后）；用户说「还有两周考试」「年底前」时按今天换算；用户没提就别传；" +
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
                    // 模型偶尔会自己编一个过期日期；过去就按「没给截止日」处理，别让整轮调研白跑。
                    parsed.takeIf { !it.isBefore(today) }?.toString()
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
            val deadlineWeek = deadline?.let { isoWeekOf(LocalDate.parse(it)) }
            var draft = parseShell(raw, thisWeek, deadlineWeek, fallbackTitle = goal.take(16))
            if (draft == null) {
                // 一次解析失败很常见（模型带了解释/代码块/半截 JSON）。补一句「只要 JSON」再问一次，
                // 比直接让用户重来更省事；第二次还不行才报错。
                val retryRaw = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
                    llm.complete(
                        listOf(
                            LlmMessage(LlmRole.System, shellSystemPrompt(thisWeek, deadline, dailySuggestion, sources != null)),
                            LlmMessage(LlmRole.User, shellUserPrompt(goal, today.toString(), thisWeek, deadline, sources?.render())),
                            LlmMessage(LlmRole.Assistant, raw.take(4000)),
                            LlmMessage(LlmRole.User, "上面的输出不是可解析的 JSON。请只输出一个 JSON 对象（不要 markdown 代码块、不要解释），字段与示例完全一致：title / dailyMinutes / stages[{name, weeks, focus, deliverable, acceptance}]。"),
                        ),
                    )
                }
                draft = retryRaw?.let { parseShell(it, thisWeek, deadlineWeek, fallbackTitle = goal.take(16)) }
            }
            val resolved = draft
                ?: return ToolResult(
                    "调研结果没解析成阶段表（模型可能没说 JSON）。这次没建计划；可以重试，或改用 create_learning_plan 手写。",
                    isError = true,
                )
            val created = plan.createShell(
                env(null, "plan.research"),
                title = resolved.title,
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
                resolved.stages.forEach { stage ->
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
                    stageFocus = resolved.stages.first().focus,
                )
            }.getOrNull()
            onChanged(planId)

            val totalWeeks = resolved.stages.sumOf { it.weeks }
            return ToolResult(
                buildString {
                    append("计划「").append(resolved.title).append("」已建好（空壳 + 大纲）：").append(resolved.stages.size).append(" 个阶段、共约 ")
                    append(totalWeeks).append(" 周")
                    deadline?.let { append("，覆盖到 ").append(it).append(" 的截止日") }
                    append("。")
                    append("计划文档和每个阶段的里程碑都写好了；先按每天 ").append(resolved.dailyMinutes).append(" 分钟估的（只是建议）。")
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

    /**
     * 从模型回复里抠出第一个**配平**的 JSON 对象：跳过 ```json 代码块前后的话、只保留花括号内的内容。
     *
     * 为什么不用 indexOf('{')..lastIndexOf('}')：回复里常有解释、示例片段或多个对象，
     * 直接截取会拼出非法 JSON，表现成「模型没说 JSON」。
     */
    private fun extractJsonObject(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").trimEnd().removeSuffix("```").trim()
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun parseShell(raw: String, thisWeek: String, deadlineWeek: String?, fallbackTitle: String): ShellDraft? {
        val jsonText = extractJsonObject(raw) ?: return null
        val root = runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull()
            ?: return null
        val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackTitle
        val daily = root["dailyMinutes"]?.jsonPrimitive?.intOrNull?.coerceIn(30, 240) ?: DEFAULT_DAILY_MINUTES
        val stagesElement = root["stages"] as? JsonArray ?: return null
        if (stagesElement.isEmpty() || stagesElement.size > MAX_STAGES) return null
        val stages = stagesElement.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            // weeks 有的模型会写成 "4" / "约 4 周"；能抠出数字就收，别因为它整份失败。
            val weeks = obj["weeks"]?.jsonPrimitive?.let { primitive ->
                primitive.intOrNull
                    ?: primitive.contentOrNull?.filter { it.isDigit() }?.toIntOrNull()
            } ?: return@mapIndexedNotNull null
            if (weeks !in 1..MAX_STAGE_WEEKS) return@mapIndexedNotNull null
            val rawName = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val name = rawName.takeIf { it.isNotEmpty() } ?: ("阶段 " + (index + 1))
            val focus = sanitizeStageText(obj["focus"]?.jsonPrimitive?.contentOrNull)
            val deliverable = sanitizeStageText(obj["deliverable"]?.jsonPrimitive?.contentOrNull)
            val acceptance = sanitizeStageText(obj["acceptance"]?.jsonPrimitive?.contentOrNull)
            ShellStage(name = name.take(40).trim(), weeks = weeks, focus = focus, deliverable = deliverable, acceptance = acceptance)
        }
        // 丢了一两条坏阶段还能用；全丢才当解析失败。阶段总数仍要落在 1..MAX_STAGES。
        if (stages.isEmpty()) return null
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
        appendLine("- 验收：下面每个阶段都有自己的「交付物」和「过关标准」，逐条自测；")
        appendLine("  全部阶段过关 + 最后做出一件能给别人演示的东西，才算完成这份计划。")
        appendLine()
        appendLine("## 阶段")
        draft.stages.forEachIndexed { index, stage ->
            append("- 阶段 ").append(index + 1).append(" · ").append(stage.name).append("（约 ").append(stage.weeks).append(" 周）")
            if (stage.focus.isNotBlank()) append("：").append(stage.focus)
            appendLine()
            append("  - 交付物：").appendLine(stage.deliverable.ifBlank { fallbackDeliverable(index) })
            append("  - 过关标准：").appendLine(stage.acceptance.ifBlank { fallbackAcceptance() })
        }
    }

    /** 模型没给可交付物时的兜底：能看见/能运行的东西，不再用「掌握/了解」这类不可验收动词。 */
    private fun fallbackDeliverable(index: Int): String =
        if (index == 0) "一份用自己的话写的基础笔记 + 一次能跑通的最小示例" else "一个能运行或能展示的小作品/文档，说明这一阶段学到的东西"

    /** 兜底过关标准：可观察、可自测，且不引用内部工具名。 */
    private fun fallbackAcceptance(): String =
        "能不看资料复述核心概念，并独立完成本阶段交付物；卡壳时能说出卡在哪一步"

    /** 阶段文案清洗：去掉来源标签（如「（Zpedu）」「(GitHub)」）和宿主内部工具名，避免漏进用户可见文档。 */
    private fun sanitizeStageText(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val withoutSourceTags = SOURCE_TAG_PATTERN.replace(text, "")
        val withoutTools = text.replace("plan_week", "").replace("research_plan", "").replace("sync_plan_week", "")
        return withoutTools.replace(SOURCE_TAG_PATTERN, "").trim().take(60)
    }

    private fun shellSystemPrompt(thisWeek: String, deadline: String?, dailyMinutes: Int, hasSources: Boolean): String =
        buildString {
            appendLine("你是学习规划师。用户只有一句目标，你要先做**全局规划**（不是排具体单元）：把目标拆成阶段，估算每个阶段的周数。")
            appendLine("只输出一个 JSON 对象，不要 markdown、不要解释：")
            appendLine("{\"title\":\"计划标题（≤16 字）\",\"dailyMinutes\":" + dailyMinutes + ",\"stages\":[{\"name\":\"阶段名（≤16 字）\",\"weeks\":2,\"focus\":\"聚焦什么（≤30 字）\",\"deliverable\":\"这一阶段结束时要拿出的东西（≤30 字）\",\"acceptance\":\"怎么算过关（可自测，≤30 字）\"}]}")
            appendLine("约束：阶段 1–8 个，每个阶段 1–12 周，总周数 ≤ 40；这个计划从 " + thisWeek + " 这一周开始。")
            if (deadline != null) appendLine("用户给了截止日期 " + deadline + "，总周数要能覆盖到那个日期之前。")
            appendLine("阶段要覆盖从入门到能做出东西的完整路线：通识/理论学习在前，动手实践（写代码、做项目）后置。")
            appendLine("写法要求（很重要）：")
            appendLine("1. 每个阶段必须有可交付物（能运行/能展示/能给别人看的东西）和可自测的过关标准；")
            appendLine("   不要用「掌握 / 熟悉 / 了解 / 跑通」这类无法验收的词当交付物。")
            appendLine("2. name / focus / deliverable / acceptance 里不要出现来源站点名或括号标注（如「（Zpedu）」「(GitHub)」「（阿里云）」）；")
            appendLine("   来源只在正文里参考，不要写进这四段文案。")
            appendLine("3. 不要写任何内部工具名（如 plan_week / research_plan / sync_plan_week），用户看不到这些。")
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

    private data class ShellStage(
        val name: String,
        val weeks: Int,
        val focus: String = "",
        val deliverable: String = "",
        val acceptance: String = "",
    )

    private data class ShellDraft(val title: String, val dailyMinutes: Int, val stages: List<ShellStage>)

    private companion object {
        /** 括号里带拉丁字母的，基本是来源站点标注：写作「（Zpedu）」或「(GitHub)」。 */
        val SOURCE_TAG_PATTERN = Regex("[（(][^）)]*[A-Za-z][^）)]*[）)]")
        const val PLAN_TIMEOUT_MS = 30_000L
        const val DEFAULT_DAILY_MINUTES = 90
        const val MAX_STAGES = 8
        const val MAX_STAGE_WEEKS = 12
        const val MAX_TOTAL_WEEKS = 40
        const val RESEARCH_TIMEOUT_MS = 45_000L
    }
}
