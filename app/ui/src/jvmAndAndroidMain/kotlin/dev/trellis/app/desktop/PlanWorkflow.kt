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
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.PlanDocStore
import dev.trellis.feature.learning.PlanWorkflowStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 计划工作流：三段「文档 → 文档」的转换，不是三个任务。
 *
 *   调研结果    → 总计划文档
 *   总计划文档  → 周文档（本周）
 *   周文档      → 计划节点（只有要学的知识点）
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
 * 只有用户真的点了「开始下一步」，[consumeConsent] 才会放行 advance。
 * 模型自己写 confirmed=true 已经没用了 —— 那个参数被删掉了。
 */
internal class PlanWorkflow(
    private val service: () -> LearningPlanService?,
    private val workflow: () -> PlanWorkflowStore?,
    private val docStore: () -> PlanDocStore?,
    private val clock: TrellisClock,
    private val llm: LlmClient,
    private val gateway: () -> ResearchGateway?,
    private val onChanged: () -> Unit,
    private val dbError: () -> String? = { null },
    /** 清单（右栏执行清单）状态：段序号 + start / running / awaiting / done / failed / cancel。 */
    private val onChecklist: (phase: Int, state: String) -> Unit = { _, _ -> },
    /** 这一段产出的文档：id 可以是草稿 id 或计划 id，回复里会挂成可点链接。 */
    private val onDocLink: (id: String, label: String) -> Unit = { _, _ -> },
    /** 让用户拍板是否进入下一段：宿主负责摆选项（不是模型自己问）。 */
    private val onAskConfirm: (jobId: String, phase: String, question: String, options: List<String>) -> Unit =
        { _, _, _, _ -> },
    /** 用户是否真的点了「开始下一步」；每段只能放行一次。 */
    private val consumeConsent: (jobId: String, phase: String) -> Boolean = { _, _ -> false },
    private val bilibili: BilibiliClient = BilibiliClient(),
) {

    /** 三段：显示名、清单、状态、提示词共用这一份，改名只改这里。 */
    internal val phases: List<String> = listOf("master", "week", "units")

    internal fun phaseLabel(phase: String): String = when (phase) {
        "master" -> "调研结果 → 总计划文档"
        "week" -> "总计划文档 → 周文档"
        "units" -> "周文档 → 计划节点"
        else -> phase
    }

    val all: List<AgentTool> = listOf(Tool())

    private inner class Tool : AgentTool {
        override val name: String = "plan_workflow"
        override val description: String =
            "建学习计划：把目标交给这条固定流程 —— 调研出总计划文档 → 周文档 → 本周要学的知识点。" +
                "第一次 action=start 带 goal；之后每段的推进由宿主摆按钮给用户点，用户点了才调 action=advance；" +
                "用户要改当前这段用 action=revise + feedback。不要用它之外的写工具另建计划。"
        override val parameters: String = """
            action: start | advance | revise | status | cancel（默认 start）
            goal: start 必填，一句话目标（例：两个月后期末考试，复习操作系统）
            deadline: 可选，YYYY-MM-DD（用户说了才传，按今天换算）
            dailyMinutes: 可选，30–240（用户说清每天投入才传）
            feedback: revise 用，用户对当前这段的修改意见
            jobId: 可选，不传就用最近一段没结束的计划流程
        """.trimIndent()

        override suspend fun call(arguments: JsonObject): ToolResult = runCatching {
            val wf = workflow() ?: return@runCatching ToolResult("计划库打不开，这次没开始。", isError = true)
            when (val action = arguments.str("action") ?: "start") {
                "start" -> start(wf, arguments)
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
            val current = open.steps.firstOrNull { it.state == "running" }?.let { phaseLabel(it.step) } ?: "进行中"
            return ToolResult(
                "已经有一段计划流程在进行中（jobId=" + open.id + "，当前：" + current + "）。先推进或取消它，再开新的。",
                isError = true,
            )
        }
        val goal = arguments.str("goal")?.trim().orEmpty()
        if (goal.isEmpty()) return ToolResult("start 需要 goal（一句话目标）。", isError = true)
        val payload = buildJsonObject {
            put("goal", goal)
            put("deadline", normalizeDeadline(arguments.str("deadline")))
            arguments.int("dailyMinutes")?.let { put("dailyMinutes", it.coerceIn(30, 240)) }
        }.toString()
        val jobId = wf.create("plan_flow", payload, phases)
        onChecklist(-1, "start")
        return runPhase(wf, wf.load(jobId)!!, "master", feedback = null)
    }

    /**
     * 用户点了「开始下一步」之后才允许推进。
     *
     * [consumeConsent] 是宿主记的一次性凭据：只有真的点了那个选项才为 true。
     * 模型自己调 advance（没凭据）会被挡住，并被告知去摆选项 —— 推进权不在模型手里。
     */
    private suspend fun advance(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("没有进行中的计划流程；先 action=start。", isError = true)
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
                "这次没有往下走：用户还没点「" + ADVANCE_LABEL + "」。先把这一段的产出讲清楚；" +
                    "用户点了那个选项之后，再调 action=advance。",
                isError = true,
            )
        }
        val index = phases.indexOf(current.step)
        if (index < 0) return ToolResult("不认识的阶段：" + current.step, isError = true)
        wf.setStep(job.id, current.step, "done")
        onChecklist(index, "done")
        if (index == phases.lastIndex) {
            wf.setJobState(job.id, "done")
            return ToolResult("三段都完成了：总计划文档、周文档和本周计划节点都建好了。")
        }
        return runPhase(wf, job, phases[index + 1], feedback = null)
    }

    private suspend fun revise(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("没有进行中的计划流程；先 action=start。", isError = true)
        val current = job.steps.lastOrNull { it.state == "running" || it.state == "failed" }
            ?: return ToolResult("这段流程没有可重做的阶段。", isError = true)
        // 三段都是「文档 → 文档」：哪一段都能按用户意见重做，重做只覆盖这一段的产物。
        val feedback = arguments.str("feedback")?.trim()
        if (feedback.isNullOrBlank()) return ToolResult("revise 需要 feedback：用户想改什么。", isError = true)
        return runPhase(wf, job, current.step, feedback)
    }

    private fun status(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("现在没有进行中的计划流程。", isError = true)
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
        val job = resolveJob(wf, arguments) ?: return ToolResult("现在没有进行中的计划流程。", isError = true)
        wf.setJobState(job.id, "cancelled")
        onChecklist(-1, "cancel")
        // 已经写好的文档/计划都保留：文档是用户的资产，删不删交给用户。
        return ToolResult("已取消这段计划流程。已经写好的文档和计划不会自动删；要删计划去计划页。")
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
            wf.setStep(job.id, phase, "failed", error = result.content)
            wf.setJobState(job.id, "running")
            onChecklist(phases.indexOf(phase), "failed")
            return result
        }
        val index = phases.indexOf(phase)
        val isLast = index == phases.lastIndex
        wf.setStep(job.id, phase, if (isLast) "done" else "running", output = result.content)
        if (isLast) {
            wf.setJobState(job.id, "done")
            onChecklist(index, "done")
            return ToolResult(result.content)
        }
        wf.setJobState(job.id, "awaiting_user")
        onChecklist(index, "awaiting")
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
                "把这一段的产出讲给用户听，等用户点；用户点了「" + ADVANCE_LABEL + "」之后你才能调 action=advance。",
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
        // 调研结果 = 宿主检索到的候选资源 + 用户的补充要求（revise 时带上）。
        val candidates = scout(goal)
        val draft = draftMasterDoc(goal, deadline, daily, candidates, feedback)
            ?: return ToolResult("调研模型没回上来（或超时），这次没写成。可以 revise 重做这一段。", isError = true)
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
        onDocLink(draftId, "总计划文档")
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

        val weekDoc = buildWeekDoc(week, goal, master, daily, feedback)
            ?: return ToolResult("周文档没生成出来（模型超时或输出不可用）。可以 revise 重做这一段。", isError = true)
        runCatching { store.saveWeek(planId, week, weekDoc.markdown) }
            .onFailure { return ToolResult("周文档没写成：" + (it.message ?: "未知原因"), isError = true) }
        wf.updatePayload(
            job.id,
            payloadWith(job.payload, "planId" to JsonPrimitive(planId), "week" to JsonPrimitive(week)),
        )
        onDocLink(planId, "总计划文档")
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
        val chapter = store.stageForWeek(planId, week)?.name ?: "本周"
        // 节点只装「要学的点」：kind 一律 study，不生成复习/实践类条目。
        val drafts = items.map { item ->
            UnitDraft(
                chapter = chapter,
                title = item.text.take(60),
                estimateMinutes = (item.minutesHint ?: 30).coerceIn(10, 45),
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
        candidates: List<Candidate>,
        feedback: String?,
    ): MasterDraft? {
        val today = clock.today()
        val thisWeek = isoWeekOf(today)
        val system = buildString {
            appendLine("你是学习规划师。输入是一句目标和一批候选学习资源，输出一份总计划文档。")
            appendLine("只输出一个 JSON 对象，不要代码块、不要解释：")
            appendLine("{\"title\":\"计划标题（≤16 字）\",\"doc\":\"整份总计划 Markdown\"}")
            appendLine("doc 必须严格是这个结构（解析器只认这几个标题）：")
            appendLine("# 目标与验收")
            appendLine("- 目标：<一句目标>")
            if (deadline.isNotBlank()) appendLine("- 截止：<" + deadline + ">")
            appendLine("- 验收：<怎么算学完，可自测>")
            appendLine("## 阶段")
            appendLine("- 阶段 1 · <名称>（<起始周> – <结束周>，约 N 周）：<这一阶段聚焦什么>")
            appendLine("  - 资源：")
            appendLine("    - [<资源名>](<url>) — <一句话说明>")
            appendLine("（阶段 2、3… 同样写法，共 3–8 个；周用 " + thisWeek + " 这种 ISO 周格式，从 " + thisWeek + " 开始，各阶段首尾相接）")
            appendLine("## 资源清单")
            appendLine("### <类型：视频/文章/仓库/文档/问答>")
            appendLine("- [<资源名>](<url>) — <来源>")
            appendLine()
            appendLine("约束：")
            appendLine("1. 阶段只写名称、周数、聚焦和资源；不要写交付物、过关标准。")
            appendLine("2. 资源只能从用户消息里的 <resource_candidates> 里挑，url 必须逐字照抄；每个阶段 1–3 条。")
            appendLine("   概念/系统课优先视频；查阅/术语优先文档；动手练优先仓库；经验/考点优先文章或问答。")
            appendLine("3. 同一份资源最多出现在两个阶段；资源清单按类型分组列全。")
            appendLine("4. 不编造资料里没有的链接；不写内部工具名。")
        }
        val user = buildString {
            appendLine("目标：" + goal)
            appendLine("今天：" + today + "，本周：" + thisWeek)
            if (deadline.isNotBlank()) appendLine("截止日期：" + deadline)
            appendLine("每天投入：" + daily + " 分钟")
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
        val raw = withTimeoutOrNull(MASTER_TIMEOUT_MS) {
            llm.complete(listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user)))
        } ?: return null
        val root = runCatching {
            Json.parseToJsonElement(extractJsonObject(raw).orEmpty()) as? JsonObject
        }.getOrNull() ?: return null
        val title = root["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(24)
            .ifBlank { goal.take(16) }
        val markdown = root["doc"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (markdown.isBlank()) return null
        val stageCount = docStore()?.parse(markdown)?.stages?.size ?: 0
        return MasterDraft(
            title = title,
            markdown = markdown,
            summary = buildString {
                appendLine("总计划文档写好了：计划「" + title + "」" +
                    (if (stageCount > 0) "，共 " + stageCount + " 个阶段" else "") + "。")
                appendLine(preview(markdown))
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
        feedback: String?,
    ): WeekDoc? {
        val system = buildString {
            appendLine("你是学习规划师。输入是一份总计划文档，输出这一周的周文档。")
            appendLine("只输出周文档 Markdown 本身（不要代码块、不要解释），结构严格如下：")
            appendLine("# " + week + " 周计划")
            appendLine("- 本周重点：<一句话>")
            appendLine("## " + week)
            appendLine("- <要学的知识点>（约 N 分钟）")
            appendLine("（再列 4–9 行，同样写法）")
            appendLine()
            appendLine("约束：")
            appendLine("1. 只列这一周要学的知识点/内容（如「进程调度算法」「页面置换计算」），标题不超过 24 个字；")
            appendLine("   不要写复习、练习、实验、做题、复盘这类动作条目 —— 计划节点只装要学的内容。")
            appendLine("2. 每条 10–45 分钟；整周总量按每天 " + daily + " 分钟摊开；条目数 5–10 条。")
            appendLine("3. 内容必须落在总计划文档里本周所处的阶段（按阶段周区间判断），不要提前学后面的阶段。")
        }
        val user = buildString {
            appendLine("目标：" + goal)
            appendLine("本周：" + week)
            if (!feedback.isNullOrBlank()) appendLine("用户对上一版的修改意见：" + feedback)
            appendLine()
            appendLine("<master_doc>")
            appendLine(master)
            appendLine("</master_doc>")
        }
        val markdown = withTimeoutOrNull(WEEK_TIMEOUT_MS) {
            llm.completeText(listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user)))
        } ?: return null
        val doc = normalizeWeekDoc(week, markdown)
        val items = docStore()?.parse(doc)?.weeks?.get(week).orEmpty()
        if (items.isEmpty()) return null
        return WeekDoc(doc, items.size)
    }

    /** 周文档只保留「标题 + 本周重点 + 周标题下的条目」，模型多写的段落一律丢掉。 */
    private fun normalizeWeekDoc(week: String, raw: String): String {
        val items = mutableListOf<String>()
        var focus = ""
        var inWeek = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("# ") -> Unit
                trimmed.startsWith("- 本周重点") ->
                    focus = trimmed.removePrefix("- 本周重点").trim().trimStart('：', ':').trim()
                WEEK_HEADING.matches(trimmed) -> inWeek = true
                trimmed.startsWith("## ") -> inWeek = false
                inWeek && BULLET.matches(trimmed) -> items += trimmed.removePrefix("- ").trim()
            }
        }
        return buildString {
            appendLine("# " + week + " 周计划")
            if (focus.isNotBlank()) appendLine("- 本周重点：" + focus)
            appendLine()
            appendLine("## " + week)
            items.forEach { appendLine("- " + it) }
        }.trimEnd()
    }

    /** 从总计划文档解析阶段 → 计划库的阶段区间（解析不到周区间就跳过）。 */
    private fun stageDraftsOf(store: PlanDocStore, master: String): List<StageDraft> =
        store.parse(master).stages.mapIndexedNotNull { index, stage ->
            val start = stage.weekStart ?: return@mapIndexedNotNull null
            val end = stage.weekEnd ?: return@mapIndexedNotNull null
            StageDraft(
                name = stage.name,
                weekStart = start,
                weekEnd = end,
                focus = stage.focus,
                sortOrder = index,
            )
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
        /** 「开始下一步」是宿主摆的固定选项；只有点了它才放行 advance。 */
        const val ADVANCE_LABEL: String = "开始下一步"

        const val SCOUT_TIMEOUT_MS = 45_000L
        const val MASTER_TIMEOUT_MS = 60_000L
        const val WEEK_TIMEOUT_MS = 60_000L
        val FENCE: String = String(CharArray(3) { Char(96) })
        val WEEK_HEADING = Regex("^##\\s*\\d{4}-W\\d{2}\\s*$")
        val BULLET = Regex("^(?:[-*+]|\\d+[.)])\\s+.+$")
    }
}

