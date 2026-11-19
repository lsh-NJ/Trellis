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
import dev.trellis.domain.plan.StageTopicDraft
import dev.trellis.domain.plan.StageTopicView
import dev.trellis.domain.plan.StageView
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.PlanDocStage
import dev.trellis.feature.learning.PlanDocStore
import dev.trellis.feature.learning.PlanUnitBand
import dev.trellis.feature.learning.PlanWorkflowStore
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 计划工作流：先定目标，再三段「文档 → 文档」的转换。
 *
 *   用户情况    → 目标摘要（目标 / 动机 / 起点 / 约束 / 验收）
 *   调研结果    → 总计划文档
 *   总计划文档  → 周文档（本周）
 *   周文档      → 计划节点（只有要学的知识点）
 *
 * 「确定目标」这一段不是文档转换，是让模型自己问：用户往往只给一句空泛目标
 *（「我想学编程」「想准备就业」），宿主不预设问题、不限制轮数，只要求问清后把结论写成
 * brief 交给宿主，用户在确认卡上点头之后才开始调研和写文档。
 *
 * 为什么这么切：每一段的输入和输出都是文件（[PlanDocStore]），中间没有「模型自己记着」的
 * 状态 —— 上一段的产物就是下一段的输入，所以换一轮对话、关一次应用都能接着走（状态在
 * job / job_step 两张表里），用户也能直接打开文档看。
 *
 * 工具面刻意很小：管理员（Manager）只看见**一个**工具（plan_workflow）。调研、写文档、
 * 写节点都是这段流程内部的宿主代码 + 三次独立的 LLM 调用，不注册成工具 ——
 * 管理员的上下文里不会多出一整套计划工具；反过来，流程内部也用不到管理员的任何工具。
 *
 * 推进权在宿主：每段跑完，宿主自己摆「开始下一步 / 我要改 / 先不建」三个选项并停下；
 * 用户点了「开始下一步」，或在对话里明确回「继续 / 好 / 开始下一步」这类确定答复，
 * [consumeConsent] 才会放行 advance（判定是宿主按白名单整句匹配，见 [confirmIntent]）。
 * 模型自己写 confirmed=true 已经没用了 —— 那个参数被删掉了。
 */
internal class PlanWorkflow(
    private val service: () -> LearningPlanService?,
    private val workflow: () -> PlanWorkflowStore?,
    private val docStore: () -> PlanDocStore?,
    private val clock: TrellisClock,
    private val llm: LlmClient,
    /**
     * 写文档用的模型客户端（可选）：关掉思考的同一个模型。
     *
     * 总计划文档 / 周文档都是结构化输出，思考过程只会吃掉输出预算 —— 实测
     * （2026-11-17）开思考时模型把 8192 token 里的绝大部分花在推理上，正文只剩
     * 572 字、JSON 被截断，整段流程失败。这里退化成 [llm] 时行为不变（测试注入假模型）。
     */
    private val structuredLlm: () -> LlmClient? = { null },
    private val gateway: () -> ResearchGateway?,
    private val onChanged: () -> Unit,
    private val dbError: () -> String? = { null },
    /**
     * 流程状态变了（段推进 / 等确认 / 结束）：宿主据此把右栏清单从 job_step 同步一遍。
     *
     * 只传 jobId 不传段序号和状态 —— 清单必须由 job 的真状态推导，不能由调用点各写一份。
     */
    private val onChecklist: (jobId: String) -> Unit = {},
    /** 这一段产出的文档：id 是草稿或计划 id，week 非空表示要直接翻到那一周。 */
    private val onDocLink: (id: String, label: String, week: String?) -> Unit = { _, _, _ -> },
    /** 让用户拍板是否进入下一段：宿主负责摆选项（不是模型自己问）。 */
    private val onAskConfirm: (jobId: String, phase: String, question: String, options: List<String>) -> Unit =
        { _, _, _, _ -> },
    /** 用户是否真的点了「开始下一步」；每段只能放行一次。 */
    private val consumeConsent: (jobId: String, phase: String) -> Boolean = { _, _ -> false },
    private val bilibili: BilibiliClient = BilibiliClient(),
    /** 流程内部诊断（写 app.log）：只记失败原因，不记文档正文。 */
    private val onLog: (String) -> Unit = {},
) {

    /** 四段：先定目标，再三段文档转换。显示名、清单、状态、提示词共用这一份，改名只改这里。 */
    internal val phases: List<String> = listOf("goal", "master", "week", "units")

    internal fun phaseLabel(phase: String): String = when (phase) {
        "goal" -> "确定目标"
        "master" -> "调研结果 → 总计划文档"
        "week" -> "总计划文档 → 周文档"
        "units" -> "周文档 → 计划节点"
        else -> phase
    }

    val all: List<AgentTool> = listOf(Tool())

    /** 写文档用的客户端：优先用关思考的那个，没配就退回主客户端（测试注入假模型时走这条）。 */
    private val docLlm: LlmClient by lazy { structuredLlm() ?: llm }

    /**
     * 结构化输出的调用 + 一次重试。
     *
     * [accept] 判断这次输出能不能用；不能用时再要一次，并明确告诉模型「上一次不可用」。
     * 超时 / 异常都算这次失败；两次都不行就返回 null，由调用方给出人话错误。
     */
    private suspend fun structuredCall(
        timeoutMs: Long,
        retryHint: String,
        accept: (String) -> Boolean,
        call: suspend (extra: String) -> String,
    ): String? {
        var extra = ""
        lastDocReject = ""
        repeat(2) { attempt ->
            lastCallFailure = ""
            val raw = withTimeoutOrNull(timeoutMs) {
                runCatching { call(extra) }
                    .onFailure { lastCallFailure = "调用失败：" + (it.message ?: it::class.simpleName) }
                    .getOrNull()
            }
            if (raw == null) {
                if (lastCallFailure.isBlank()) lastCallFailure = "超过 " + (timeoutMs / 1000) + " 秒没返回"
                return null
            }
            if (accept(raw)) return raw
            if (attempt == 0) {
                extra = "\n\n" + retryHint +
                    lastDocReject.takeIf { it.isNotBlank() }?.let { "\n上一次被拒的具体原因：" + it + "；请按这一条改。" }.orEmpty()
            }
        }
        return null
    }

    /** 上一次结构化调用失败的原因（超时 / 异常 / 输出不达标），给用户和日志看。 */
    private var lastCallFailure: String = ""

    /** 上一次总计划文档被拒的具体原因；空白表示没被拒过。 */
    private var lastDocReject: String = ""

    private inner class Tool : AgentTool {
        override val name: String = "plan_workflow"
        override val description: String =
            "建学习计划：交给这条固定流程 —— 先定目标，再调研出总计划文档 → 周文档 → 本周要学的知识点。" +
                "目标太空（「我想学编程」「想准备就业」）时先 start（不带 brief）：流程进入「确定目标」段，" +
                "这一轮你直接问用户、问清后调 action=brief 提交摘要；目标已经说清楚就 start 时带上 brief 跳过提问。" +
                "之后每段的推进要用户拍板：用户点了按钮、或在对话里明确说「开始下一步 / 继续 / 好」，" +
                "宿主会记一次性凭据，这时才调 action=advance（凭据由宿主校验，你自己说没用）；" +
                "已经停在「等待确认」时用户说继续，就直接 advance，不要再交 brief。" +
                "用户要改当前这段用 action=revise + feedback。不要用它之外的写工具另建计划。"
        override val parameters: String = """
            action: start | brief | advance | revise | status | cancel（默认 start）
            goal: start 必填，一句话目标（例：两个月后期末考试，复习操作系统）；brief 时传可以改写成一句更具体的
            brief: 可选，目标摘要（4–6 行：目标 / 动机 / 起点 / 约束 / 验收）。start 时带 = 目标已说清楚、跳过提问；不带 = 先进「确定目标」段由你自己问；action=brief 时必填
            deadline: 可选，这件事要完成的日期 YYYY-MM-DD（「一周内」= 今天+7）。用户只是提到更远的考试/比赛日期时，那不是这个计划的截止，写进 goal 就行；revise / brief 时传可以改截止日期
            dailyMinutes: 可选，30–240（用户说清每天投入才传）
            feedback: revise 用，用户对当前这段的修改意见
            jobId: 可选，不传就用最近一段没结束的计划流程
        """.trimIndent()

        override suspend fun call(arguments: JsonObject): ToolResult = runCatching {
            val wf = workflow() ?: return@runCatching ToolResult("计划库打不开，这次没开始。", isError = true)
            when (val action = arguments.str("action") ?: "start") {
                "start" -> start(wf, arguments)
                "brief" -> brief(wf, arguments)
                "advance" -> advance(wf, arguments)
                "revise" -> revise(wf, arguments)
                "status" -> status(wf, arguments)
                "cancel" -> cancel(wf, arguments)
                else -> ToolResult("action 只能是 start / advance / revise / status / cancel。", isError = true)
            }
        }.getOrElse { ToolResult("计划流程出错：" + (it.message ?: it), isError = true) }
    }

    // ---------------------------------------------------------------- 五个动作

    private suspend fun start(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        wf.latestOpen("plan_flow")?.let { open ->
            if (flowStale(open)) {
                // 计划被删、草稿没了：这段流程已经续不下去，自动取消后允许开新的。
                wf.setJobState(open.id, "cancelled")
                onChecklist(open.id)
            } else {
                val current = open.steps.firstOrNull { it.state == "running" }?.let { phaseLabel(it.step) } ?: "进行中"
                return ToolResult(
                    "已经有一段计划流程在进行中（jobId=" + open.id + "，当前：" + current + "）。" +
                        "先推进或取消它，再开新的；目标还没定完就用 action=brief 交摘要。",
                    isError = true,
                )
            }
        }
        val goal = arguments.str("goal")?.trim().orEmpty()
        if (goal.isEmpty()) return ToolResult("start 需要 goal（一句话目标）。", isError = true)
        val brief = arguments.str("brief")?.trim().orEmpty()
        val payload = buildJsonObject {
            put("goal", goal)
            put("deadline", normalizeDeadline(arguments.str("deadline")))
            arguments.int("dailyMinutes")?.let { put("dailyMinutes", it.coerceIn(30, 240)) }
            if (brief.isNotEmpty()) put("brief", brief)
        }.toString()
        val jobId = wf.create("plan_flow", payload, phases)
        onChecklist(jobId)
        // 目标已经说得够清楚（学什么 / 为什么 / 什么基础 / 每天多久 / 什么时候要完成）：
        // 跳过提问直接写文档；模型没带 brief 就先停在「确定目标」，由它自己问。
        if (brief.isEmpty()) return beginGoal(wf, jobId)
        wf.setStep(jobId, "goal", "done", output = brief)
        val job = wf.load(jobId) ?: return ToolResult("流程状态读不到了，用 action=status 看一下。", isError = true)
        return runPhase(wf, job, "master", feedback = null)
    }

    /**
     * 「确定目标」段：目标太空，流程先停在这里，由模型自己问。
     *
     * 这一段不调模型、不写文档，job 保持 running（不是 awaiting_user）—— 问几轮、问什么、
     * 要不要先 search_web 查一下市场，全由模型决定；宿主只在它调 [brief] 时接住结论、摆确认卡。
     * 现在先按「自由问」试：宿主不预设问题清单，只在提示词里给出该问到的维度。
     */
    private fun beginGoal(wf: PlanWorkflowStore, jobId: String): ToolResult {
        wf.setStep(jobId, "goal", "running")
        onChecklist(jobId)
        return ToolResult(
            buildString {
                appendLine("「确定目标」这一段开始了：现在不写文档，先把用户的情况问清楚。")
                appendLine("这一轮直接开口问（问几轮由你决定，不要一次抛一堆问题）：")
                appendLine("1. 先问「为什么想学 / 想解决什么问题」「现在什么基础、做过什么」。")
                appendLine("2. 再问「每天大概能投入多久」「想达成什么能自己检验的结果」「什么时候要完成」。")
                appendLine("3. 就业/转行类目标：先问有没有意向岗位；答不出来就用 search_web 查市场，")
                appendLine("   给 2–3 个方向 + 各自的要求和差距，让用户挑一个（不要替他决定）。")
                appendLine("4. 用户答不清就问得更具体（举例给他选），不要自己编造他的情况。")
                appendLine("问清后把结论收成 4–6 行（目标 / 动机 / 起点 / 约束 / 验收 / 时限），调 action=brief 提交；")
                appendLine("提交时把 goal 也改写成一句具体的（例：三个月内达到能投前端实习的 JS 基础）。")
                appendLine("现在只问用户，不要调 advance，不要写文档。")
            }.trimEnd(),
        )
    }

    /**
     * 目标问清后提交摘要：写进 payload，摆确认卡等用户点头。
     *
     * 和其余三段一样，推进权在宿主：模型交摘要只是「说它问清了」，真正开始调研要用户点
     * 「开始下一步」（或明确说继续）。用户要改就回到对话继续问，见 [revise]。
     */
    private suspend fun brief(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult(noOpenMessage(wf), isError = true)
        val goalStep = job.steps.firstOrNull()
        if (goalStep == null || goalStep.step != "goal") {
            return ToolResult("这段流程没有「确定目标」段（目标早就定了）。要改计划内容用 action=revise。", isError = true)
        }
        if (goalStep.state == "done") {
            return ToolResult("目标已经在流程里定过了。想改目标用 action=revise（带 feedback / goal）。", isError = true)
        }
        // 摘要交过、正停在确认上：用户说「继续」就该 advance，不要再交一次（会把卡片重新摆一遍）。
        if (job.state == "awaiting_user") {
            return ToolResult(
                "目标摘要已经交过、正停在确认上。用户点了「" + ADVANCE_LABEL + "」或明确说继续，就调 action=advance 开始调研；" +
                    "要改目标用 action=revise（带 feedback）。不要再交 brief。",
                isError = true,
            )
        }
        val text = arguments.str("brief")?.trim().orEmpty()
        if (text.length < 8) {
            return ToolResult("brief 太短：用 4–6 行写清目标 / 动机 / 起点 / 约束 / 验收。", isError = true)
        }
        val pairs = mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement>>(
            "brief" to JsonPrimitive(text),
        )
        arguments.str("goal")?.trim()?.takeIf { it.isNotBlank() }?.let { pairs += "goal" to JsonPrimitive(it) }
        normalizeDeadline(arguments.str("deadline")).takeIf { it.isNotBlank() }
            ?.let { pairs += "deadline" to JsonPrimitive(it) }
        arguments.int("dailyMinutes")?.coerceIn(30, 240)?.let { pairs += "dailyMinutes" to JsonPrimitive(it) }
        wf.updatePayload(job.id, payloadWith(job.payload, *pairs.toTypedArray()))
        wf.setStep(job.id, "goal", "running", output = text)
        wf.setJobState(job.id, "awaiting_user")
        onChecklist(job.id)
        onAskConfirm(
            job.id,
            "goal",
            "目标先按上面这份来定，可以开始做调研和总计划了吗？",
            listOf(ADVANCE_LABEL, "我要改一下", "先不建"),
        )
        return ToolResult(
            buildString {
                appendLine("目标摘要先记进流程了（用户点确认之前不会开始调研）：")
                appendLine(text)
                appendLine()
                append("把上面这些用两三句话复述给用户（让他一眼看出理解对不对），然后等拍板：")
                append("用户点「").append(ADVANCE_LABEL).append("」或明确说「对 / 继续」，再调 action=advance 开始调研。")
            }.trimEnd(),
        )
    }

    /**
     * 用户点了「开始下一步」之后才允许推进。
     *
     * [consumeConsent] 是宿主记的一次性凭据：用户点了选项，或对话里明确说「继续 / 好 /
     * 开始下一步」这类确定答复，宿主才记；判定在 [confirmIntent]（白名单整句匹配，
     * 带「改 / 先 / 等 / 看」等字眼一律不算）。模型自己调 advance（没凭据）会被挡住。
     */
    private suspend fun advance(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult(noOpenMessage(wf), isError = true)
        if (job.state != "awaiting_user") {
            return ToolResult(
                "这段流程现在不在等确认（状态：" + job.state + "）。用 action=status 看进度。",
                isError = true,
            )
        }
        val current = job.steps.firstOrNull { it.state == "running" }
            ?: return ToolResult("流程状态不完整，用 action=status 看一下。", isError = true)
        if (!consumeConsent(job.id, current.step)) {
            return ToolResult(
                "这次没有往下走：用户还没确认（没点「" + ADVANCE_LABEL + "」，也没明确说继续）。" +
                    "先把这一段的产出讲清楚；用户点了那个选项、或明确回「继续 / 好」之后，再调 action=advance。",
                isError = true,
            )
        }
        val index = phases.indexOf(current.step)
        if (index < 0) return ToolResult("不认识的阶段：" + current.step, isError = true)
        wf.setStep(job.id, current.step, "done")
        if (index == phases.lastIndex) {
            wf.setJobState(job.id, "done")
            onChecklist(job.id)
            return ToolResult("三段都完成了：总计划文档、周文档和本周计划节点都建好了。")
        }
        onChecklist(job.id)
        return runPhase(wf, job, phases[index + 1], feedback = null)
    }

    /** 流程绑的计划被删、或第一段的草稿没了 —— 这段流程已经续不下去。 */
    private fun flowStale(job: PlanWorkflowStore.Job): Boolean {
        val planId = job.payload.stringField("planId")
        if (planId != null) {
            val svc = service() ?: return false
            // 用 plans() 而不是 planState()：软删的计划还在库里，只是列表里不出现。
            // 已被放弃/结束（status != active）的计划也续不下去 —— 流程该作废。
            return runCatching {
                svc.plans().firstOrNull { it.id == planId }?.status != "active"
            }.getOrDefault(false)
        }
        val draftId = job.payload.stringField("draftId") ?: return false
        val store = docStore() ?: return false
        return runCatching { store.readDraft(draftId) }.getOrNull() == null
    }

    private suspend fun revise(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult(noOpenMessage(wf), isError = true)
        val current = job.steps.lastOrNull { it.state == "running" || it.state == "failed" }
            ?: return ToolResult("这段流程没有可重做的阶段。", isError = true)
        // 三段都是「文档 → 文档」：哪一段都能按用户意见重做，重做只覆盖这一段的产物。
        val feedback = arguments.str("feedback")?.trim()
        if (feedback.isNullOrBlank()) return ToolResult("revise 需要 feedback：用户想改什么。", isError = true)
        // 用户发现截止写错了（例如把更远的考试日期当成了计划截止）可以直接改，不用重开流程。
        val deadline = normalizeDeadline(arguments.str("deadline"))
        // 「确定目标」段没有文档可重做：退回对话继续问，问好再交新摘要；顺手允许改 goal / deadline。
        if (current.step == "goal") {
            val extra = mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement>>()
            arguments.str("goal")?.trim()?.takeIf { it.isNotBlank() }?.let { extra += "goal" to JsonPrimitive(it) }
            if (deadline.isNotBlank()) extra += "deadline" to JsonPrimitive(deadline)
            if (extra.isNotEmpty()) wf.updatePayload(job.id, payloadWith(job.payload, *extra.toTypedArray()))
            wf.setStep(job.id, "goal", "running")
            wf.setJobState(job.id, "running")
            onChecklist(job.id)
            return ToolResult(
                "目标还没定下来，流程退回对话：继续问清用户想改的那部分，问好后重新调 action=brief 交摘要。" +
                    "这次的修改意见：" + feedback,
            )
        }
        val updated = if (deadline.isNotBlank()) {
            wf.updatePayload(job.id, payloadWith(job.payload, "deadline" to JsonPrimitive(deadline)))
            wf.load(job.id) ?: job
        } else {
            job
        }
        return runPhase(wf, updated, current.step, feedback)
    }

    private fun status(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult(noOpenMessage(wf), isError = true)
        return ToolResult(
            buildString {
                appendLine("计划流程 " + job.id + "（状态 " + job.state + "）：")
                job.steps.forEachIndexed { index, step ->
                    appendLine(
                        "  " + (index + 1) + ". " + phaseLabel(step.step) + " — " + when (step.state) {
                            "done" -> "已完成"
                            "failed" -> "失败" + (step.error?.let { e -> "：" + e } ?: "")
                            "running" -> if (job.state == "awaiting_user") "等待确认" else "进行中"
                            else -> "还没开始"
                        },
                    )
                }
                job.payload.stringField("title")?.let { appendLine("计划标题：" + it) }
                job.payload.stringField("planId")?.let { appendLine("计划：" + it) }
                job.payload.stringField("week")?.let { appendLine("本周：" + it) }
            }.trimEnd(),
        )
    }

    private fun cancel(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult(noOpenMessage(wf), isError = true)
        wf.setJobState(job.id, "cancelled")
        onChecklist(job.id)
        // 草稿还没落成计划（第二段没跑）时，取消就把草稿删掉：它只是这段流程的中间产物，
        // 留着会在知识库里挂一份永远点不开的「总计划（草稿）」（2026-11-19 用户反馈）。
        // 已经落成计划的文档是计划资产，不在这里删 —— 放弃/删除计划时一起清。
        val hasPlan = !job.payload.stringField("planId").isNullOrBlank()
        val draftId = job.payload.stringField("draftId")
        if (!hasPlan) draftId?.let { docStore()?.deleteDraft(it) }
        return when {
            hasPlan -> ToolResult("已取消这段计划流程。计划的文档和节点都保留；要删计划去计划页。")
            draftId != null -> ToolResult("已取消这段计划流程，这段的草稿也一起删掉了。")
            else -> ToolResult("已取消这段计划流程。目标还没定完，没有生成任何文档。")
        }
    }

    // ---------------------------------------------------------------- 三段执行

    private suspend fun runPhase(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        phase: String,
        feedback: String?,
    ): ToolResult {
        val result = when (phase) {
            "master" -> runMaster(wf, job, feedback)
            "week" -> runWeek(wf, job, feedback)
            "units" -> runUnits(wf, job, feedback)
            else -> ToolResult("不认识的阶段：" + phase, isError = true)
        }
        if (result.isError) {
            onLog("计划流程·" + phaseLabel(phase) + "失败：" + result.content.take(200))
            wf.setStep(job.id, phase, "failed", error = result.content)
            wf.setJobState(job.id, "running")
            onChecklist(job.id)
            return result
        }
        val index = phases.indexOf(phase)
        val isLast = index == phases.lastIndex
        wf.setStep(job.id, phase, if (isLast) "done" else "running", output = result.content)
        if (isLast) {
            wf.setJobState(job.id, "done")
            onChecklist(job.id)
            return ToolResult(
                result.content + "\n三段都完成了：总计划文档、周文档、本周知识点都已就绪。" +
                    "本周节点进的是队列，完成一个就记到当天；不需要再排到具体日期。",
            )
        }
        wf.setJobState(job.id, "awaiting_user")
        onChecklist(job.id)
        val next = phases[index + 1]
        // 选项由宿主摆：模型不能替用户决定要不要往下走。
        onAskConfirm(
            job.id,
            phase,
            "「" + phaseLabel(next) + "」还没做，要现在开始吗？",
            listOf(ADVANCE_LABEL, "我要改一下", "先不建"),
        )
        return ToolResult(
            result.content + "\n\n下一步（" + phaseLabel(next) + "）还没做。选项已经摆在对话里，" +
                "把这一段的产出讲给用户听，等用户拍板：用户点「" + ADVANCE_LABEL + "」或回「继续 / 好」都算确认，" +
                "然后才能调 action=advance。",
        )
    }


    // ---- 第一段：调研结果 → 总计划文档

    private suspend fun runMaster(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        feedback: String?,
    ): ToolResult {
        val goal = job.payload.stringField("goal").orEmpty()
        val deadline = job.payload.stringField("deadline").orEmpty()
        val daily = job.payload.stringField("dailyMinutes")?.toIntOrNull() ?: 60
        // 「确定目标」段问出来的情况：阶段划分要按它调整（起点、预算、验收、时限）。
        val brief = job.payload.stringField("brief").orEmpty()
        // 调研结果 = 宿主检索到的候选资源 + 用户的补充要求（revise 时带上）。
        val candidates = scout(goal)
        val draft = draftMasterDoc(goal, deadline, daily, brief, candidates, feedback)
            ?: run {
                val reason = lastDocReject.ifBlank { lastCallFailure }.ifBlank { "模型没有返回可用内容" }
                onLog("计划流程·总计划文档失败：" + reason)
                return ToolResult(
                    "总计划文档没生成出来：" + reason + "。这次没写成，可以 revise 重做这一段" +
                        "（feedback 写具体一点更容易过，例如「精简到 3 个阶段、每阶段 2 个主题」）。",
                    isError = true,
                )
            }
        onLog("计划流程·总计划文档完成：" + draft.title)
        val store = docStore() ?: return ToolResult("计划文档存储没准备好，这次没写成。", isError = true)
        val draftId = "draft_" + UUID.randomUUID().toString().replace("-", "").take(16)
        runCatching { store.saveDraft(draftId, draft.markdown) }
            .onFailure { return ToolResult("总计划文档没写成：" + (it.message ?: "未知原因"), isError = true) }
        wf.updatePayload(
            job.id,
            payloadWith(
                job.payload,
                "title" to JsonPrimitive(draft.title),
                "draftId" to JsonPrimitive(draftId),
                "summary" to JsonPrimitive(draft.summary),
            ),
        )
        onDocLink(draftId, "总计划文档", null)
        return ToolResult(draft.summary)
    }

    // ---- 第二段：总计划文档 → 周文档

    private suspend fun runWeek(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        feedback: String?,
    ): ToolResult {
        val svc = service() ?: return ToolResult(noService(), isError = true)
        val store = docStore() ?: return ToolResult("计划文档存储没准备好，这次没写成。", isError = true)
        val draftId = job.payload.stringField("draftId")
            ?: return ToolResult("流程里没有总计划文档（第一段没完成），先 revise 重做第一段。", isError = true)
        // 草稿在第二段成功后会被收养（落成正式总文档并删除）；重做这一段时从正式文档读回来。
        val existingPlanId = job.payload.stringField("planId")
        val master = store.readDraft(draftId) ?: existingPlanId?.let { store.read(it) }
            ?: return ToolResult("总计划文档读不到了（草稿可能被删），先 revise 重做第一段。", isError = true)
        val title = job.payload.stringField("title") ?: "学习计划"
        val goal = job.payload.stringField("goal").orEmpty()
        val deadline = job.payload.stringField("deadline")?.takeIf { it.isNotBlank() }
        val daily = job.payload.stringField("dailyMinutes")?.toIntOrNull() ?: 60
        val week = job.payload.stringField("week") ?: isoWeekOf(clock.today())

        // 计划本体在这段建：周文档要按 planId 存，周文档也必须挂在某个计划下。
        val planId = job.payload.stringField("planId") ?: run {
            val applied = svc.createShell(env("plan.workflow.shell"), title, goal, deadline)
            val id = applied.value?.planId
                ?: return ToolResult("建计划失败：" + (applied.error?.message ?: "未知原因"), isError = true)
            // 新计划走周队列：节点不排到天，完成时记到当天。
            runCatching { svc.setScheduleMode(env("plan.workflow.queue"), id, "queue") }
            runCatching { store.adoptDraft(draftId, id) }
                .onFailure { return ToolResult("总计划文档没落成正式文档：" + (it.message ?: "未知原因"), isError = true) }
            // 阶段从总计划文档里解析（格式见 PlanDocStore）；解析不到就只留空壳。
            runCatching { svc.setStages(env("plan.workflow.stages"), id, stageDraftsOf(store, master)) }
            onChanged()
            id
        }

        // 这一周排哪个阶段：第一个还没完成的阶段（刚建的计划就是阶段 1；老计划按 done/total 判）。
        val stageViews = runCatching { svc.stages(planId) }.getOrDefault(emptyList())
        val currentStage = currentStageOf(stageViews)
        val stageName = currentStage?.name?.takeIf { it.isNotBlank() } ?: "本周"
        val stageTopics = currentStage?.topics.orEmpty()
        val weekDoc = buildWeekDoc(week, goal, master, daily, stageName, stageTopics, feedback)
            ?: run {
                onLog("计划流程·周文档失败：" + lastCallFailure.ifBlank { "两次输出都不成形的 Markdown" })
                return ToolResult(
                    "周文档没生成出来" +
                        (lastCallFailure.takeIf { it.isNotBlank() }?.let { "：" + it } ?: "（两次输出都不成形的 Markdown）") +
                        "。可以 revise 重做这一段。",
                    isError = true,
                )
            }
        runCatching { store.saveWeek(planId, week, weekDoc.markdown) }
            .onFailure { return ToolResult("周文档没写成：" + (it.message ?: "未知原因"), isError = true) }
        wf.updatePayload(
            job.id,
            payloadWith(
                job.payload,
                "planId" to JsonPrimitive(planId),
                "week" to JsonPrimitive(week),
                "stage" to JsonPrimitive(stageName),
            ),
        )
        onDocLink(planId, "本周周文档（" + week + "）", week)
        onChanged()
        return ToolResult(
            buildString {
                appendLine("周文档写好了（" + week + "，共 " + weekDoc.items + " 条）：")
                appendLine(preview(weekDoc.markdown))
                append("计划「").append(title).append("」也建好了（周队列）：节点还没进计划库，等下一步。")
            },
        )
    }

    // ---- 第三段：周文档 → 计划节点

    private suspend fun runUnits(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        feedback: String?,
    ): ToolResult {
        val svc = service() ?: return ToolResult(noService(), isError = true)
        val store = docStore() ?: return ToolResult("计划文档存储没准备好。", isError = true)
        val planId = job.payload.stringField("planId")
            ?: return ToolResult("流程里没有计划 id（第二段没完成），先 revise 重做第二段。", isError = true)
        val week = job.payload.stringField("week") ?: isoWeekOf(clock.today())
        // 节点已经进过队列就不重做这一段：再次 append 会重复建一批节点。
        if (!feedback.isNullOrBlank()) {
            val queued = runCatching { svc.week(planId, week) }.getOrNull()?.commitment?.units.orEmpty()
            if (queued.isNotEmpty()) {
                return ToolResult(
                    "这周的节点已经建好了（" + queued.size + " 条），不能再整段重做（会重复建）。" +
                        "要改就在计划页拖/删节点，或告诉我要改哪一条。",
                    isError = true,
                )
            }
        }
        val weekText = store.readWeek(planId, week).orEmpty()
        val items = store.parse(weekText).weeks[week].orEmpty()
        if (items.isEmpty()) {
            return ToolResult("周文档里没有 " + week + " 的条目（第二段可能没写成），先 revise 重做第二段。", isError = true)
        }
        // 章节 = 「阶段 · 主题」：阶段进度靠它聚合（主题全做完才算主题完成）。
        val stageName = job.payload.stringField("stage")
            ?: store.stageForWeek(planId, week)?.name
            ?: "本周"
        // 节点只装「要学的点」：kind 一律 study，不生成复习/实践类条目。
        val drafts = items.map { item ->
            val topic = item.topic?.takeIf { it.isNotBlank() }
            UnitDraft(
                chapter = if (topic != null) stageName + " · " + topic else stageName,
                title = item.text.take(60),
                // 周文档里的体量档（小/中/大）已经换算成分钟；老文档的裸分钟数也照用。
                estimateMinutes = PlanUnitBand.clamp(item.minutesHint ?: PlanUnitBand.DEFAULT),
                kind = "study",
            )
        }
        val appended = svc.appendUnits(env("plan.workflow.nodes"), planId, drafts)
        if (!appended.ok) return ToolResult("写计划节点失败：" + (appended.error?.message ?: "未知原因"), isError = true)
        val ids = appended.value.orEmpty()
        val queued = runCatching { svc.enqueueUnits(env("plan.workflow.queue"), planId, week, ids) }
            .getOrElse { return ToolResult("节点已写入，但进本周队列失败：" + (it.message ?: "未知原因"), isError = true) }
        if (!queued.ok) return ToolResult("节点已写入，但进本周队列失败：" + (queued.error?.message ?: "未知原因"), isError = true)
        onChanged()
        return ToolResult(
            "本周（" + week + "）要学的点已经进队列，共 " + drafts.size + " 条：" +
                drafts.joinToString("、") { it.title } + "。完成后记到当天。",
        )
    }


    // ---------------------------------------------------------------- 三段产物的生成

    private data class MasterDraft(val title: String, val markdown: String, val summary: String)

    private suspend fun draftMasterDoc(
        goal: String,
        deadline: String,
        daily: Int,
        brief: String,
        candidates: List<Candidate>,
        feedback: String?,
    ): MasterDraft? {
        val today = clock.today()
        val thisWeek = isoWeekOf(today)
        val budgetDays = availableDays(deadline)
        val system = buildString {
            appendLine("你是学习规划师。输入是一句目标、用户的情况和一批候选学习资源，输出一份总计划文档。")
            if (brief.isNotBlank()) {
                appendLine("用户已经说清自己的情况（见 <user_situation>）：阶段划分必须贴合他的起点和投入 ——")
                appendLine("他已经会的内容不要重复安排；每天投入和完成时限是硬约束，验收标准按他说过的来写。")
            }
            appendLine("只输出一个 JSON 对象，不要代码块、不要解释：")
            appendLine("{\"title\":\"计划标题（≤16 字）\",\"doc\":\"整份总计划 Markdown\"}")
            appendLine("doc 必须严格是这个结构（解析器只认这几个标题）：")
            appendLine("# 目标与验收")
            appendLine("- 目标：<一句目标 + 时限口径>")
            if (deadline.isNotBlank()) appendLine("- 截止：<" + deadline + ">")
            appendLine("- 验收：<怎么算学完，可自测>")
            appendLine("## 阶段")
            appendLine("- 阶段 1 · <名称>（约 N 天）：<这一阶段聚焦什么>")
            appendLine("  - 主题：<主题名>（约 M 天）")
            appendLine("  - 主题：<主题名 2>（约 M 天）")
            appendLine("  - 资源：")
            appendLine("    - [<资源名>](<url>) — <一句话说明>")
            appendLine("（阶段 2、3… 同样写法。不写周、不写具体日期 —— 日期由宿主按主题体量算。）")
            appendLine("## 资源清单")
            appendLine("### <类型：视频/文章/仓库/文档/问答>")
            appendLine("- [<资源名>](<url>) — <来源>")
            appendLine()
            appendLine("约束：")
            appendLine("1. 阶段 = 内容分组：名称 + 聚焦 + 主题清单 + 资源；不写 ISO 周，也不写日期。")
            appendLine("2. 主题 = 一块能连续学 1–5 天的内容（通常 2–4 天）：名字写具体内容（如「进程状态与上下文切换」），")
            appendLine("   不写「学习 / 掌握 / 复习」这类动作；体量写「（约 N 天）」。")
            appendLine("   如果一块内容本身要 6 天以上（完整项目、综合练习），拆成几个相邻主题，每个仍 ≤5 天")
            appendLine("   （例：「项目二 · 页面与路由」「项目二 · 数据请求与联调」）；超过 5 天的主题会被退回。")
            appendLine("3. 主题数量不设上下限：资料能自然切出几块就写几块，切不出更多就少写，")
            appendLine("   不要为了凑数拆分或合并；一个阶段通常 2–8 个主题。")
            appendLine("4. 主题必须能在候选资料的标题 / 目录 / 分集里找到依据；找不到依据的不许写。")
            appendLine("5. 阶段数也不设下限：按主题量自然分组（通常 2–6 个阶段）。")
            appendLine("6. 资源只能从用户消息里的 <resource_candidates> 里挑，url 必须逐字照抄；每个阶段 1–3 条。")
            appendLine("   概念/系统课优先视频；查阅/术语优先文档；动手练优先仓库；经验/考点优先文章或问答。")
            appendLine("7. 同一份资料可以用在多个阶段：一门系统课 / 一本书通常覆盖好几个阶段，按内容相关性挂就行。")
            appendLine("   不要为了「避免重复」漏写或少写；**没有「一个资源最多出现在几个阶段」的限制**。")
            appendLine("8. 目标行只写要学什么和时限：时限必须和截止一致。如果目标句里的时限和截止对不上")
            appendLine("   （例：句子里说「一周内」，但截止在两个多月后），以截止日期为准，目标行不要写「一周内」。")
            if (budgetDays != null) {
                appendLine("9. 从今天到截止一共 " + budgetDays + " 天：所有阶段的主题天数合计不要超过这个数；")
                appendLine("   装不下就缩减范围，不要硬塞。")
            } else {
                appendLine("9. 没有截止：按内容自然分阶段，主题天数合计控制在 4–10 周内即可，后面按周推进。")
            }
        }
        val user = buildString {
            appendLine("目标：" + goal)
            appendLine("今天：" + today + "，本周：" + thisWeek)
            if (deadline.isNotBlank()) {
                appendLine("截止日期：" + deadline + (budgetDays?.let { "（从今天到截止共 " + it + " 天）" } ?: ""))
            } else {
                appendLine("截止日期：没给（按内容自然分阶段）")
            }
            appendLine("每天投入：" + daily + " 分钟")
            if (brief.isNotBlank()) {
                appendLine()
                appendLine("<user_situation>")
                appendLine(brief)
                appendLine("</user_situation>")
            }
            if (!feedback.isNullOrBlank()) appendLine("用户对上一版的修改意见：" + feedback)
            appendLine()
            appendLine("<resource_candidates>")
            if (candidates.isEmpty()) {
                appendLine("（这次没检索到可用资源：阶段资源给空，不要编造链接；资源清单可以省略。）")
            } else {
                candidates.forEachIndexed { index, candidate ->
                    append("[").append(index + 1).append("] [").append(candidate.type).append("] ")
                    append(candidate.title).append(" | ").append(candidate.url)
                    if (candidate.source.isNotBlank()) append(" | ").append(candidate.source)
                    if (candidate.note.isNotBlank()) append(" | ").append(candidate.note)
                    appendLine()
                }
            }
            appendLine("</resource_candidates>")
            append("请输出 JSON：title 和 doc。")
        }
        fun parseRoot(text: String): JsonObject? = runCatching {
            Json.parseToJsonElement(extractJsonObject(text).orEmpty()) as? JsonObject
        }.getOrNull()
        /**
         * 阶段/主题结构验收：主题必须有、每个 1–5 天、合计不超预算。
         *
         * 「数量」不做硬约束（不设上下限）——数量应该由资料和预算决定，硬写数字只会逼模型凑数。
         * 2026-11-18：阶段与周解绑，阶段不再写周区间，所以这里校验的是主题体量而不是周区间。
         */
        fun stageRejectReason(markdown: String): String? {
            val stages = docStore()?.parse(markdown)?.stages.orEmpty()
            if (stages.isEmpty()) return "文档里解析不出「## 阶段」"
            stages.firstOrNull { it.topics.isEmpty() }?.let {
                return "阶段「" + it.name + "」没有主题清单"
            }
            val topics = stages.flatMap { it.topics }
            if (topics.any { it.name.isBlank() }) return "有主题没有名字"
            topics.firstOrNull { (it.days ?: 0) !in 1..5 }?.let {
                return "主题「" + it.name + "」的体量不在 1–5 天"
            }
            val total = topics.sumOf { (it.days ?: 0).coerceIn(1, 5) }
            if (total <= 0) return "主题天数合计为 0"
            if (budgetDays != null && total > budgetDays) {
                return "主题天数合计 " + total + " 天，超过截止前可用的 " + budgetDays + " 天"
            }
            if (budgetDays == null && total > 120) return "主题天数合计 " + total + " 天，超过 120 天"
            return null
        }
        val raw = structuredCall(
            timeoutMs = MASTER_TIMEOUT_MS,
            retryHint = buildString {
                append("上一次的输出不可用：要么不是合法 JSON / 被截断，要么阶段里没有主题、主题天数不在 1–5，")
                append("或主题天数合计超出预算。这次只输出 JSON、不要代码块；每个阶段至少 1 个「主题：名称（约 N 天）」，")
                if (budgetDays != null) append("全部主题天数合计不超过 " + budgetDays + " 天；")
                append("主题数量由资料决定，不要凑数。")
            },
            accept = { text ->
                val doc = parseRoot(text)?.get("doc")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                lastDocReject = if (doc.isBlank()) {
                    "输出不是完整 JSON（可能被截断）"
                } else {
                    stageRejectReason(doc).orEmpty()
                }
                lastDocReject.isBlank()
            },
        ) { extra ->
            docLlm.complete(listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user + extra)))
        } ?: return null
        val root = parseRoot(raw) ?: return null
        val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(24)
            .ifBlank { goal.take(16) }
        val markdown = root["doc"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (markdown.isBlank()) return null
        val stages = docStore()?.parse(markdown)?.stages.orEmpty()
        val fixed = if (stages.isEmpty()) markdown else fixStageDayTotals(markdown, stages)
        val stageCount = stages.size
        val topicCount = stages.sumOf { it.topics.size }
        val totalDays = stages.sumOf { stage -> stage.topics.sumOf { (it.days ?: 2).coerceIn(1, 5) } }
        return MasterDraft(
            title = title,
            markdown = fixed,
            summary = buildString {
                appendLine("总计划文档写好了：计划「" + title + "」" +
                    (if (stageCount > 0) "，共 " + stageCount + " 个阶段、" + topicCount + " 个主题" else "") + "。")
                if (totalDays > 0) {
                    append("按主题体量合计约 ").append(totalDays).append(" 天")
                    if (budgetDays != null) append("（截止前共有 ").append(budgetDays).append(" 天）")
                    appendLine("；实际快慢按周调整。")
                }
                appendLine(preview(fixed))
                append("文档可以在知识库「计划文档」里打开；下一步是把它变成本周的周文档。")
            },
        )
    }

    private data class WeekDoc(val markdown: String, val items: Int)

    private suspend fun buildWeekDoc(
        week: String,
        goal: String,
        master: String,
        daily: Int,
        stageName: String,
        topics: List<StageTopicView>,
        feedback: String?,
    ): WeekDoc? {
        val topicLines = topics.map { "- " + it.name + "（约 " + it.days + " 天）" }
        val system = buildString {
            appendLine("你是学习规划师。输入是一份总计划文档和当前阶段，输出这一周的周文档。")
            appendLine("只输出周文档 Markdown 本身（不要代码块、不要解释），结构严格如下：")
            appendLine("# " + week + " 周计划")
            appendLine("- 本周重点：<一句话>")
            appendLine("## " + week)
            appendLine("### 主题：<本周主题里的一个名字，逐字照抄>")
            appendLine("- <要学的知识点>（<小|中|大> · 约 N 分钟）")
            appendLine("（这个主题下 2–6 行；换下一个主题时再起一个「### 主题：…」）")
            appendLine()
            appendLine("约束：")
            appendLine("1. 只列这一周要学的知识点 —— 具体的知识/技能主题（名词短语，如「进程调度算法」")
            appendLine("   「闭包与作用域链」），标题不超过 24 个字。")
            appendLine("   不要写复习、练习、实验、做题、复盘这类动作条目 —— 计划节点只装要学的内容。")
            appendLine("   也不要写「…范围划分 / …盘点 / …排序 / …排期 / …自评 / …框架搭建 / …方法与诀窍 /")
            appendLine("   …时间分配」这类元内容：它们回答「怎么安排学习」，不是「学什么」。")
            appendLine("2. 主题分组：「### 主题：」后面必须逐字用下面「本周主题」里的名字，不要自创主题；")
            appendLine("   按顺序从第一个主题开始取，取到这一周的投入量为止（可以只取部分主题，也可以跨到下一个主题）。")
            appendLine("   一周取不满是正常的，不要为了填满预算把每条都写成一样的量。")
            appendLine("3. 每条标体量档：小 ≈ 15 分钟、中 ≈ 30 分钟、大 ≈ 45 分钟。45 是单块上限，内容再多就拆成两条；")
            appendLine("   分钟数就写档位对应的那个数，不要自己另编。")
            appendLine("4. 内容必须落在总计划文档里当前阶段（" + stageName + "）的主题范围内，不要提前学后面的阶段。")
        }
        val user = buildString {
            appendLine("目标：" + goal)
            appendLine("本周：" + week)
            appendLine("每天投入：" + daily + " 分钟（只做参考，取不满没关系）")
            appendLine("当前阶段：" + stageName)
            appendLine("本周主题（按顺序取）：")
            if (topicLines.isEmpty()) {
                appendLine("（这个阶段没有主题清单：按总计划文档里当前阶段的内容自己列，主题名自己拟但要具体）")
            } else {
                topicLines.forEach { appendLine(it) }
            }
            if (!feedback.isNullOrBlank()) appendLine("用户对上一版的修改意见：" + feedback)
            appendLine()
            appendLine("<master_doc>")
            appendLine(master)
            appendLine("</master_doc>")
        }
        val store = docStore()
        val markdown = structuredCall(
            timeoutMs = WEEK_TIMEOUT_MS,
            retryHint = "上一次的输出不是可用的周文档（可能被截断）。这次只输出周文档 Markdown 本身，" +
                "不要代码块、不要解释；每个「### 主题：」下面放 2–6 条知识点，主题名逐字用上面给的名字。",
            accept = { text ->
                val normalized = normalizeWeekDoc(week, text)
                val items = store?.parse(normalized)?.weeks?.get(week).orEmpty()
                lastDocReject = when {
                    items.isEmpty() -> "解析不出「## " + week + "」下的条目"
                    items.any { it.text.isBlank() } -> "有条目是空行"
                    else -> ""
                }
                lastDocReject.isBlank()
            },
        ) { extra ->
            docLlm.completeText(listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user + extra)))
        } ?: return null
        val doc = normalizeWeekDoc(week, markdown, daily)
        val items = docStore()?.parse(doc)?.weeks?.get(week).orEmpty()
        if (items.isEmpty()) return null
        return WeekDoc(doc, items.size)
    }

    /**
     * 周文档只保留「标题 + 本周重点 + 投入参考 + 主题分组 + 条目」，模型多写的段落一律丢掉。
     *
     * 投入参考这一行由宿主按设置里的每天投入算，不让模型写：模型一旦拿到预算就会把它
     * 除以条数，变成一串一模一样的分钟数。主题分组是小标题，保留下来给节点定章节（阶段 · 主题）。
     */
    private fun normalizeWeekDoc(week: String, raw: String, dailyMinutes: Int = 0): String {
        val items = mutableListOf<Pair<String?, String>>()
        var focus = ""
        var topic: String? = null
        var inWeek = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("# ") -> Unit
                trimmed.startsWith("- 本周重点") ->
                    focus = trimmed.removePrefix("- 本周重点").trim().trimStart('：', ':').trim()
                WEEK_HEADING.matches(trimmed) -> {
                    inWeek = true
                    topic = null
                }
                inWeek && WEEK_TOPIC_HEADING.matches(trimmed) -> {
                    topic = trimmed.substringAfter("：").substringAfter(":").trim().takeIf { it.isNotBlank() }
                }
                trimmed.startsWith("## ") -> {
                    inWeek = false
                    topic = null
                }
                inWeek && trimmed.startsWith("### ") -> Unit
                inWeek && BULLET.matches(trimmed) -> items += topic to trimmed.removePrefix("- ").trim()
            }
        }
        return buildString {
            appendLine("# " + week + " 周计划")
            if (focus.isNotBlank()) appendLine("- 本周重点：" + focus)
            weeklyBudgetLine(dailyMinutes)?.let { appendLine(it) }
            appendLine()
            appendLine("## " + week)
            var lastTopic: String? = null
            items.forEach { (itemTopic, text) ->
                if (itemTopic != null && itemTopic != lastTopic) {
                    if (lastTopic != null) appendLine()
                    appendLine("### 主题：" + itemTopic)
                    lastTopic = itemTopic
                }
                appendLine("- " + text)
            }
        }.trimEnd()
    }

    /** 周级投入参考：约 7 小时（每天 60 分钟，可多可少）；没设每天投入就不写。 */
    private fun weeklyBudgetLine(dailyMinutes: Int): String? {
        if (dailyMinutes <= 0) return null
        val total = dailyMinutes * 7
        val hours = total / 60
        val rest = total % 60
        val text = when {
            rest == 0 -> "约 " + hours + " 小时"
            hours == 0 -> "约 " + total + " 分钟"
            else -> "约 " + hours + " 小时 " + rest + " 分钟"
        }
        return "- 本周投入参考：" + text + "（每天 " + dailyMinutes + " 分钟，可多可少）"
    }

    /** 从总计划文档解析阶段 + 主题 → 计划库的阶段草稿（不写周区间：阶段与周解绑）。 */
    private fun stageDraftsOf(store: PlanDocStore, master: String): List<StageDraft> =
        store.parse(master).stages.mapIndexed { index, stage ->
            StageDraft(
                name = stage.name,
                focus = stage.focus,
                sortOrder = index,
                topics = stage.topics.mapIndexed { topicIndex, topic ->
                    StageTopicDraft(
                        name = topic.name,
                        days = (topic.days ?: 2).coerceIn(1, 5),
                        sortOrder = topicIndex,
                    )
                },
            )
        }

    /** 从今天到截止（含首尾）一共几天；没截止 / 格式不对返回 null。 */
    private fun availableDays(deadline: String): Int? {
        if (deadline.isBlank()) return null
        val date = runCatching { LocalDate.parse(deadline) }.getOrNull() ?: return null
        return (ChronoUnit.DAYS.between(clock.today(), date) + 1).toInt().coerceAtLeast(1)
    }

    /** 当前阶段：第一个没完成的（没主题的老阶段按 done/total 判）；全完成就落在最后一个。 */
    private fun currentStageOf(stages: List<StageView>): StageView? =
        stages.firstOrNull { !it.complete } ?: stages.lastOrNull()

    /**
     * 把每个阶段标题里的「约 N 天」改成主题天数之和。
     *
     * 模型只负责「切哪些主题、每个主题几天」，阶段总天数由宿主求和 —— 否则又是一类
     * 「42 分钟」式的假数字（自己算错还写进文档）。找不到标题里的天数就补在阶段名后面。
     */
    private fun fixStageDayTotals(markdown: String, stages: List<PlanDocStage>): String {
        val lines = markdown.lines().toMutableList()
        val used = mutableSetOf<Int>()
        stages.forEach { stage ->
            val days = stage.topics.sumOf { (it.days ?: 2).coerceIn(1, 5) }
            if (days <= 0) return@forEach
            var index = -1
            for (i in lines.indices) {
                if (i in used) continue
                val trimmed = lines[i].trim()
                if (trimmed.startsWith("- 阶段") && trimmed.contains(stage.name)) {
                    index = i
                    break
                }
            }
            if (index < 0) return@forEach
            used += index
            val line = lines[index]
            lines[index] = if (STAGE_DAYS.containsMatchIn(line)) {
                line.replace(STAGE_DAYS, "（约 " + days + " 天）")
            } else {
                line.replaceFirst(stage.name, stage.name + "（约 " + days + " 天）")
            }
        }
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- 资源聚合（调研）

    /** 一条候选资源。模型只能从这些里挑，所以类型和来源是宿主标好的。 */
    internal data class Candidate(
        val title: String,
        val url: String,
        val type: String,
        val source: String,
        val note: String = "",
        val score: Int = 0,
    )

    /**
     * 资源聚合：网络检索只留用户点名的站点，B 站单独走检索接口，按类型分组后挑代表。
     *
     * 不做「所有结果都丢给模型」：资料一多，模型就会挑标题好看的，而不是挑有用的。
     * 宿主先按来源和类型分层（视频 / 文档 / 仓库 / 文章 / 问答），每类只留前几条，
     * 模型再在每类里按阶段挑 —— 选择面小了，编造 URL 的机会也小了。
     */
    private suspend fun scout(goal: String): List<Candidate> {
        val hits = mutableListOf<Candidate>()
        val net = gateway()
        if (net != null) {
            val queries = listOf(
                goal,
                goal + " 教程 site:csdn.net OR site:zhihu.com OR site:cnblogs.com",
                goal + " 入门 site:developer.aliyun.com OR site:cloud.tencent.com",
                goal + " 项目 site:github.com",
            )
            val rawHits = withTimeoutOrNull(SCOUT_TIMEOUT_MS) {
                queries.flatMap { query -> runCatching { net.search(query, 6) }.getOrDefault(emptyList()) }
            }.orEmpty()
            rawHits.forEach { hit ->
                hits += Candidate(
                    title = hit.title.ifBlank { hit.siteName },
                    url = hit.url,
                    type = typeOf(hit.url),
                    source = hit.siteName.ifBlank { hostOf(hit.url) },
                    note = hit.summary.take(120),
                    score = hit.authority,
                )
            }
        }
        // B 站：单独检索（搜索接口按播放量排序，分集数就是天然大纲）。
        runCatching {
            bilibili.search(searchKeyword(goal), "click", 5).forEach { video ->
                hits += Candidate(
                    title = video.title,
                    url = "https://www.bilibili.com/video/" + video.bvid,
                    type = "视频",
                    source = "B站 · " + video.author,
                    note = "播放 " + video.playText + (if (video.parts > 0) " · " + video.parts + " 集" else ""),
                    score = video.parts,
                )
            }
        }
        return pickPerType(hits)
    }

    /** 类型分层：视频 / 文档 / 仓库 / 文章 / 问答 / 其他，每类最多 3 条，总共最多 12 条。 */
    internal fun pickPerType(all: List<Candidate>): List<Candidate> {
        val seen = mutableSetOf<String>()
        val unique = all.filter { it.url.isNotBlank() && seen.add(it.url) }
        val order = listOf("视频", "文档", "仓库", "文章", "问答", "其他")
        val cap = mapOf("视频" to 3, "文档" to 2, "仓库" to 2, "文章" to 3, "问答" to 2, "其他" to 2)
        return order.flatMap { type ->
            unique.filter { it.type == type }
                .sortedByDescending { it.score }
                .take(cap[type] ?: 2)
        }.take(12)
    }

    internal fun typeOf(url: String): String {
        val host = hostOf(url)
        return when {
            host.contains("bilibili.com") -> "视频"
            host.contains("github.com") -> "仓库"
            host.contains("developer.aliyun.com") || host.contains("cloud.tencent.com") -> "文档"
            host.contains("zhihu.com") -> "问答"
            host.contains("csdn.net") || host.contains("cnblogs.com") -> "文章"
            else -> "其他"
        }
    }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI.create(url).host.orEmpty() }.getOrDefault("")

    /** 把一句目标压成能搜的词：「我要学习操作系统，应对两个月后的期末考试」→「操作系统 期末」。 */
    internal fun searchKeyword(goal: String): String {
        val cleaned = goal
            .replace(Regex("[，,。；;！!？?]"), " ")
            .replace(Regex("我要|我想|想要|准备|学习|复习|备考|应对|关于|大概|还有|之后|的|了|吧"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.split(" ").filter { it.isNotBlank() }.take(3).joinToString(" ").ifBlank { goal.take(12) }
    }

    // ---------------------------------------------------------------- 小件

    /** 给用户看的摘要：去掉 Markdown 标题符号，只留前几行，免得把整份文档糊进对话。 */
    private fun preview(markdown: String): String =
        markdown.lineSequence()
            .filter { it.isNotBlank() }
            .take(8)
            .joinToString("\n") { line -> line.trim().trimStart('#', '-', ' ').trim() }

    private fun extractJsonObject(raw: String): String? {
        val fence = FENCE
        val text = raw.trim().removePrefix(fence + "json").removePrefix(fence).trimEnd().removeSuffix(fence).trim()
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

    private fun normalizeDeadline(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        val parsed = runCatching { LocalDate.parse(text) }.getOrNull() ?: return ""
        return if (parsed.isBefore(clock.today())) "" else parsed.toString()
    }

    private fun resolveJob(wf: PlanWorkflowStore, arguments: JsonObject): PlanWorkflowStore.Job? {
        val id = arguments.str("jobId")
        return if (id != null) wf.load(id) else wf.latestOpen("plan_flow")
    }

    /** 找不到 job 时的回话：带上真正开着的流程，模型就不会拿着编出来的 jobId 打转。 */
    private fun noOpenMessage(wf: PlanWorkflowStore): String {
        val open = runCatching { wf.latestOpen("plan_flow") }.getOrNull()
            ?: return "现在没有进行中的计划流程；新建用 action=start。"
        val current = open.steps.firstOrNull { it.state == "running" }
            ?.let { phaseLabel(it.step) } ?: "进行中"
        return "找不到这个 jobId。当前开着的是 " + open.id + "（" + current + "）；" +
            "不传 jobId 就会用它。"
    }

    private fun noService(): String {
        val reason = dbError()?.takeIf { it.isNotBlank() }
        return "计划库打不开，这次没写任何东西。" + (reason?.let { r -> "具体原因：" + r + "。" } ?: "")
    }

    private fun env(origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        origin = origin,
    )

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun String.stringField(key: String): String? {
        val root = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return null
        return root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun payloadWith(payload: String, vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): String {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return JsonObject(root + pairs.toMap()).toString()
    }

    internal companion object {
        /** 「开始下一步」是宿主摆的固定选项；点了它（或明确说继续）才放行 advance。 */
        const val ADVANCE_LABEL: String = "开始下一步"

        /**
         * 「用户这句话算不算同意继续」的判定提示词。
         *
         * 不用白名单硬匹配：自然语言千变万化，白名单既脆又容易误判。多花一次「是/否」
         * 小调用换确定性和可解释性；模型只输出 `{"confirm":true|false}`，宿主按 JSON 读。
         * 解析失败 / 没配模型 / 超时一律当 false —— 误判成同意会让流程在用户没想清楚时
         * 往下跑，比多问一次贵得多。
         */
        fun confirmVerdictMessages(
            question: String,
            options: List<String>,
            reply: String,
        ): List<LlmMessage> = listOf(
            LlmMessage(
                LlmRole.System,
                buildString {
                    appendLine("你是确认判定器。宿主要求用户确认一件事，用户回了一句话。")
                    appendLine("判断用户是否明确同意现在继续/开始，只输出一个 JSON 对象：")
                    appendLine("{\"confirm\":true} 或 {\"confirm\":false}")
                    appendLine("规则：")
                    appendLine("1. 只有明确表示「继续 / 开始 / 可以」才为 true；只是复述、反问、含糊其辞，都为 false。")
                    appendLine("2. 用户想改任何内容（阶段、主题、时间、范围、资源）都为 false。")
                    appendLine("3. 用户想先看看、等一下、考虑一下，都为 false。")
                    appendLine("4. 拿不准就 false。")
                },
            ),
            LlmMessage(
                LlmRole.User,
                buildString {
                    appendLine("等待确认的问题：" + question.ifBlank { "要进入下一步吗？" })
                    if (options.isNotEmpty()) appendLine("界面上的选项：" + options.joinToString(" / "))
                    appendLine("用户回复：「" + reply.trim() + "」")
                    append("只输出 JSON。")
                },
            ),
        )

        /** 读判定结果：只认 JSON 里的 confirm=true；解析不出来就是 false。 */
        fun parseConfirmVerdict(raw: String): Boolean {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return false
            val root = runCatching {
                Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
            }.getOrNull() ?: return false
            return root["confirm"]?.jsonPrimitive?.booleanOrNull == true
        }

        const val SCOUT_TIMEOUT_MS = 45_000L
        const val MASTER_TIMEOUT_MS = 60_000L
        const val WEEK_TIMEOUT_MS = 60_000L
        val FENCE: String = String(CharArray(3) { Char(96) })
        val WEEK_HEADING = Regex("^##\\s*\\d{4}-W\\d{2}\\s*$")

        /** 周文档里的主题分组标题：`### 主题：进程状态`。 */
        val WEEK_TOPIC_HEADING = Regex("^###\\s*主题\\s*[：:]\\s*.+$")

        /** 阶段标题里的体量：`（约 9 天）`。 */
        val STAGE_DAYS = Regex("[（(]\\s*约\\s*\\d+\\s*天\\s*[）)]")
        val BULLET = Regex("^(?:[-*+]|\\d+[.)])\\s+.+$")
    }
}

