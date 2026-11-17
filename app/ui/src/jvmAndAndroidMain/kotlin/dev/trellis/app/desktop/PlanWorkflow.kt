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
import dev.trellis.feature.learning.PlanWorkflowStore
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 计划工作流：把「计划创建」从管理员手里剥出来，做成一条固定的三段流水线。
 *
 * 为什么单独一个模块：管理员负责「这一轮该不该动手」，计划则是一次跨多轮的固定流程 ——
 * 调研 + 资源聚合 → 总计划 → 计划本体 + 周文档 → 本周节点。流程推进不交给模型猜，
 * 每一步的产物都落盘（job/job_step + 计划文档），哪一步等确认、下一步做什么由宿主决定。
 *
 * 三个节点（用户定）：
 * 1. 调研 + 资源聚合 + 总计划：一次做完，出总计划文档（知识库「计划文档」里的草稿）并等确认；
 * 2. 计划本体 + 周计划文档：建空壳计划、绑定目标/里程碑、草稿落成正式总文档、写本周文档；
 * 3. 安排本周节点：把周文档里的条目追加成节点、进本周队列（不排到天）。
 *
 * 资源策略：网络资源重点关照 CSDN / 知乎 / 博客园 / 阿里云开发者 / 腾讯云 / GitHub，
 * B 站单独走检索接口（按播放量 + 分集数挑）。候选项按类型分组后再交给模型，模型只能从
 * 候选项里挑 —— 编不出 URL，也编不出不存在的课程。
 */
internal class PlanWorkflow(
    private val service: () -> LearningPlanService?,
    private val workflow: () -> PlanWorkflowStore?,
    private val docStore: () -> PlanDocStore?,
    private val research: () -> ResearchTools?,
    private val clock: TrellisClock,
    private val llm: LlmClient,
    private val gateway: () -> ResearchGateway?,
    private val onChanged: () -> Unit,
    private val dbError: () -> String? = { null },
    private val onChecklist: (phase: Int, state: String) -> Unit = { _, _ -> },
    private val onDocLink: (id: String, label: String) -> Unit = { _, _ -> },
    private val bilibili: BilibiliClient = BilibiliClient(),
) {

    val guidance: String = listOf(
        "<how_to_plan_workflow>",
        "1. 用户要「定计划 / 学一门课 / 备考 / 安排学习」时，别自己拆阶段也别自己建计划，调 plan_workflow 接手。",
        "   第一次 action=start（goal 一句话；用户说清 deadline / 每天投入才传 dailyMinutes）；之后一步一次 action=confirm + confirmed=true。",
        "2. 这个流程固定三段：调研+资源+总计划 → 计划本体+周文档 → 安排本周节点。每段跑完都会返回结果和「下一步」，",
        "   你要把结果讲给用户看，然后**停下来等用户确认**（用 ask_choices 给「按这个建 / 我要改 / 先不建」），用户点了才调 confirm。",
        "3. 用户要改当前这一步：action=revise + feedback，不要自己调 update_plan 绕过去。",
        "4. 每一步都会更新右侧执行清单；清单必须看到「等待确认」才算停住。",
        "5. 用户中途不要了：action=cancel。",
        "</how_to_plan_workflow>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(Tool())

    private val phases: List<String> = listOf("research", "plan", "week")

    private fun phaseLabel(phase: String): String = when (phase) {
        "research" -> "调研 + 资源聚合 + 总计划"
        "plan" -> "创建计划本体 + 周计划文档"
        "week" -> "安排本周节点"
        else -> phase
    }

    private inner class Tool : AgentTool {
        override val name: String = "plan_workflow"
        override val description: String =
            "接一段计划：固定三段（调研+资源+总计划 → 计划本体+周文档 → 安排本周节点），每段都停下来等用户确认。" +
                "第一次 action=start（带 goal）；之后 action=confirm + confirmed=true 推进一步；要改当前段用 action=revise + feedback。"
        override val parameters: String = """
            action: start | confirm | revise | status | cancel（默认 start）
            goal: start 时必填，一句话目标（例：两个月后期末考试，复习操作系统）
            deadline: 可选，YYYY-MM-DD（用户说了才传，按今天换算）
            dailyMinutes: 可选，30–240（用户说清每天投入才传）
            feedback: revise 时用，用户对当前这一步的修改意见
            confirmed: confirm 必须为 true（用户已明确同意进入下一步）
            jobId: 可选，不传就用最近一个没结束的计划流程
        """.trimIndent()

        override suspend fun call(arguments: JsonObject): ToolResult = runCatching {
            val wf = workflow() ?: return@runCatching ToolResult("计划库打不开，这次没开始。", isError = true)
            when (val action = arguments.str("action") ?: "start") {
                "start" -> start(wf, arguments)
                "confirm" -> confirm(wf, arguments)
                "revise" -> revise(wf, arguments)
                "status" -> status(wf, arguments)
                "cancel" -> cancel(wf, arguments)
                else -> ToolResult("action 只能是 start / confirm / revise / status / cancel。", isError = true)
            }
        }.getOrElse { ToolResult("计划流程出错：" + (it.message ?: it), isError = true) }
    }

    // ---------------------------------------------------------------- 五个动作

    private suspend fun start(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        wf.latestOpen("plan_flow")?.let { open ->
            return ToolResult(
                "已经有一段计划流程在进行中（jobId=" + open.id + "，当前：" +
                    (open.steps.firstOrNull { it.state == "running" }?.let { phaseLabel(it.step) } ?: "进行中") +
                    "）。先推进或取消它，再开新的。",
                isError = true,
            )
        }
        val goal = arguments.str("goal")?.trim().orEmpty()
        if (goal.isEmpty()) return ToolResult("start 需要 goal（一句话目标）。", isError = true)
        val deadline = normalizeDeadline(arguments.str("deadline"))
        val daily = arguments.int("dailyMinutes")?.coerceIn(30, 240)
        val payload = buildJsonObject {
            put("goal", goal)
            put("deadline", deadline)
            daily?.let { put("dailyMinutes", it) }
        }.toString()
        val jobId = wf.create("plan_flow", payload, phases)
        onChecklist(-1, "start")
        return runPhase(wf, wf.load(jobId)!!, "research", feedback = null)
    }

    private suspend fun confirm(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        if (arguments.bool("confirmed") != true) {
            return ToolResult(
                "这次没有往下走：进入下一段之前要让用户明确同意。先把这一步的结果讲给用户听，问一句要不要继续；" +
                    "用户说「继续 / 可以」之后再带 confirmed=true。",
                isError = true,
            )
        }
        val job = resolveJob(wf, arguments) ?: return ToolResult("没有进行中的计划流程；先 action=start。", isError = true)
        if (job.state != "awaiting_user") {
            return ToolResult("这段流程现在不在等确认（状态：" + job.state + "）。用 action=status 看进度。", isError = true)
        }
        val current = job.steps.firstOrNull { it.state == "running" }
            ?: return ToolResult("流程状态不完整，用 action=status 看一下。", isError = true)
        val index = phases.indexOf(current.step)
        if (index < 0) return ToolResult("不认识的阶段：" + current.step, isError = true)
        // 用户确认 = 这一段验收通过：清单打勾，再跑下一段。
        wf.setStep(job.id, current.step, "done")
        onChecklist(index, "done")
        if (index == phases.lastIndex) {
            wf.setJobState(job.id, "done")
            return ToolResult("三段都完成了：计划本体、总计划文档和本周节点都建好了。")
        }
        return runPhase(wf, job, phases[index + 1], feedback = null)
    }

    private suspend fun revise(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("没有进行中的计划流程；先 action=start。", isError = true)
        val current = job.steps.lastOrNull { it.state == "running" || it.state == "failed" }
            ?: return ToolResult("这段流程没有可重做的阶段。", isError = true)
        val feedback = arguments.str("feedback")?.trim()
        // 只有第一段能整体重做（调研结果就是总计划）；后两段要改请说明改什么，走 update_plan。
        if (current.step != "research") {
            return ToolResult(
                "「" + phaseLabel(current.step) + "」已经产出，不能整段重跑；要改计划内容直接跟用户确认改哪里，" +
                    "然后用 update_plan 改（或告诉用户到计划页拖）。",
                isError = true,
            )
        }
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
                            "failed" -> "失败" + (step.error?.let { "：" + it } ?: "")
                            "running" -> if (job.state == "awaiting_user") "等待确认" else "进行中"
                            else -> "还没开始"
                        },
                    )
                }
                job.payload.stringField("planId")?.let { appendLine("计划：" + it) }
                job.payload.stringField("draftId")?.let { appendLine("总计划文档：" + it) }
            }.trimEnd(),
        )
    }

    private fun cancel(wf: PlanWorkflowStore, arguments: JsonObject): ToolResult {
        val job = resolveJob(wf, arguments) ?: return ToolResult("现在没有进行中的计划流程。", isError = true)
        wf.setJobState(job.id, "cancelled")
        onChecklist(-1, "cancel")
        // 草稿和空壳计划都保留：草稿是总计划，计划本体可能已经建好，删不删交给用户。
        return ToolResult("已取消这段计划流程。已经写好的计划/文档不会自动删；要删计划去计划页。")
    }

    // ---------------------------------------------------------------- 三段

    private suspend fun runPhase(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        phase: String,
        feedback: String?,
    ): ToolResult {
        val result = when (phase) {
            "research" -> runResearch(wf, job, feedback)
            "plan" -> runPlan(wf, job)
            "week" -> runWeek(wf, job)
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
            return ToolResult(result.content + "\n三段都完成了。")
        }
        wf.setJobState(job.id, "awaiting_user")
        onChecklist(index, "awaiting")
        val next = phases[index + 1]
        return ToolResult(
            result.content + "\n\n下一步（" + phaseLabel(next) + "）还没做。先把上面的结果讲给用户看，" +
                "问一句要不要继续（ask_choices 给「按这个建 / 我要改 / 先不建」）；" +
                "用户同意后再调 plan_workflow：action=confirm，jobId=" + job.id + "，confirmed=true。",
        )
    }

    // ---- 第一段：调研 + 资源聚合 + 总计划

    private suspend fun runResearch(
        wf: PlanWorkflowStore,
        job: PlanWorkflowStore.Job,
        feedback: String?,
    ): ToolResult {
        val goal = job.payload.stringField("goal").orEmpty()
        val deadline = job.payload.stringField("deadline").orEmpty()
        val daily = job.payload.stringField("dailyMinutes")?.toIntOrNull() ?: 60
        val effectiveGoal = if (feedback.isNullOrBlank()) goal else goal + "\n用户补充要求：" + feedback

        val candidates = scout(effectiveGoal)
        val raw = generateMasterPlan(effectiveGoal, deadline, daily, candidates)
            ?: return ToolResult("调研模型没回上来（或超时），这次没写成。可以重试。", isError = true)
        val rt = research() ?: return ToolResult("调研工具没准备好，这次没开始。", isError = true)
        val outcome = rt.restoreDraft(goal, deadline.takeIf { it.isNotBlank() }, raw)
            ?: return ToolResult("调研结果没解析成阶段表（模型可能没说 JSON），这次没写成；可以 revise 重做这一步。", isError = true)
        val store = docStore() ?: return ToolResult("计划文档存储没准备好，这次没写成。", isError = true)
        val draftId = "draft_" + UUID.randomUUID().toString().replace("-", "").take(16)
        val week = isoWeekOf(clock.today())
        val markdown = masterDoc(effectiveGoal, deadline, outcome.resolved, candidates, week)
        runCatching { store.saveDraft(draftId, markdown) }
            .onFailure { return ToolResult("总计划文档没写成：" + (it.message ?: "未知原因"), isError = true) }

        val summary = renderMaster(outcome.resolved, candidates, markdown.length)
        wf.updatePayload(
            job.id,
            payloadWith(
                job.payload,
                "raw" to JsonPrimitive(raw),
                "draftId" to JsonPrimitive(draftId),
                "summary" to JsonPrimitive(summary),
                "goal" to JsonPrimitive(effectiveGoal),
            ),
        )
        onDocLink(draftId, "总计划文档")
        return ToolResult(summary)
    }

    // ---- 第二段：计划本体 + 周文档

    private suspend fun runPlan(wf: PlanWorkflowStore, job: PlanWorkflowStore.Job): ToolResult {
        val svc = service() ?: return ToolResult(noService(), isError = true)
        val rt = research() ?: return ToolResult("调研工具没准备好。", isError = true)
        val goal = job.payload.stringField("goal").orEmpty()
        val deadline = job.payload.stringField("deadline")?.takeIf { it.isNotBlank() }
        val raw = job.payload.stringField("raw")
            ?: return ToolResult("流程里没有调研结果（第一步可能没完成），先 revise 重做第一步。", isError = true)
        val outcome = rt.restoreDraft(goal, deadline, raw)
            ?: return ToolResult("调研结果读不回来了（可能被截断），先 revise 重做第一步。", isError = true)
        val daily = job.payload.stringField("dailyMinutes")?.toIntOrNull()
        val applied = rt.applyShell(svc, outcome, daily) {}
            ?: return ToolResult("建计划失败：计划库没有返回计划 id。", isError = true)
        val planId = applied.planId
        // 新计划走周队列：节点不排到天，完成时记到当天。
        runCatching { svc.setScheduleMode(env(planId, "plan.workflow.queue"), planId, "queue") }
        // 草稿落成正式总文档（覆盖 applyShell 生成的大纲，保留资源清单）。
        val store = docStore()
        val draftId = job.payload.stringField("draftId")
        if (store != null && draftId != null) {
            runCatching { store.adoptDraft(draftId, planId) }
                .onFailure { return ToolResult("总计划文档没落成正式文档：" + (it.message ?: "未知原因"), isError = true) }
        }
        val week = job.payload.stringField("week") ?: isoWeekOf(clock.today())
        // 周文档：先用模型生成这一周的条目（不建节点），第三步再落到计划库。
        val items = generateWeekItems(goal, outcome.resolved, week, daily ?: 60)
        if (store != null && items.isNotEmpty()) {
            runCatching { store.saveWeek(planId, week, weekDoc(week, outcome.resolved.stages.firstOrNull()?.focus.orEmpty(), items)) }
                .onFailure { return ToolResult("周计划文档没写成：" + (it.message ?: "未知原因"), isError = true) }
        }
        wf.updatePayload(
            job.id,
            payloadWith(job.payload, "planId" to JsonPrimitive(planId), "week" to JsonPrimitive(week)),
        )
        onDocLink(planId, "总计划文档")
        onChanged()
        return ToolResult(
            buildString {
                append("计划「").append(outcome.resolved.title).append("」已建好：")
                append(outcome.resolved.stages.size).append(" 个阶段、共约 ")
                append(outcome.resolved.stages.sumOf { it.weeks }).append(" 周。")
                if (items.isNotEmpty()) append("本周（").append(week).append("）的周计划文档也写好了，共 ").append(items.size).append(" 条。")
                append("这一步只建计划和文档，还没往计划库塞节点。")
            },
        )
    }

    // ---- 第三段：把周文档条目变成本周节点

    private suspend fun runWeek(wf: PlanWorkflowStore, job: PlanWorkflowStore.Job): ToolResult {
        val svc = service() ?: return ToolResult(noService(), isError = true)
        val planId = job.payload.stringField("planId")
            ?: return ToolResult("流程里没有计划 id，先完成第二步。", isError = true)
        val week = job.payload.stringField("week") ?: isoWeekOf(clock.today())
        // 周文档是单独一行（kind=week），不能从总文档的 load() 里找；先读这一周再解析。
        val store = docStore()
        val weekText = store?.readWeek(planId, week).orEmpty()
        val items = if (weekText.isBlank()) emptyList() else store?.parse(weekText)?.weeks?.get(week).orEmpty()
        if (items.isEmpty()) {
            return ToolResult("周计划文档里没有 " + week + " 的条目（第二步可能没写成），先 revise 第一步重来。", isError = true)
        }
        val chapter = store?.stageForWeek(planId, week)?.name ?: "本周"
        val drafts = items.map { item ->
            UnitDraft(
                chapter = chapter,
                title = item.text.take(60),
                estimateMinutes = (item.minutesHint ?: 30).coerceIn(10, 45),
                kind = inferKind(item.text),
            )
        }
        val appended = svc.appendUnits(env(planId, "plan.workflow.nodes"), planId, drafts)
        if (!appended.ok) return ToolResult("追加节点失败：" + (appended.error?.message ?: "未知原因"), isError = true)
        val ids = appended.value.orEmpty()
        val queued = runCatching { svc.enqueueUnits(env(planId, "plan.workflow.queue"), planId, week, ids) }
            .getOrElse { return ToolResult("节点已追加，但进本周队列失败：" + (it.message ?: "未知原因"), isError = true) }
        if (!queued.ok) return ToolResult("节点已追加，但进本周队列失败：" + (queued.error?.message ?: "未知原因"), isError = true)
        onChanged()
        return ToolResult(
            "本周（" + week + "）安排了 " + drafts.size + " 个节点（进本周队列，不排到具体天）：" +
                drafts.joinToString("、") { it.title } + "。完成后系统会记到当天。",
        )
    }

    // ---------------------------------------------------------------- 资源聚合

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
            val keyword = searchKeyword(goal)
            bilibili.search(keyword, "click", 5).forEach { video ->
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

    private suspend fun generateMasterPlan(
        goal: String,
        deadline: String,
        daily: Int,
        candidates: List<Candidate>,
    ): String? {
        val today = clock.today()
        val week = isoWeekOf(today)
        val deadlineWeek = deadline.takeIf { it.isNotBlank() }?.let {
            runCatching { isoWeekOf(LocalDate.parse(it)) }.getOrNull()
        }
        val system = buildString {
            appendLine("你是学习规划师。用户只有一句目标，你要先做全局规划：把目标拆成阶段、估算每阶段周数，再从给定资源里挑最有用的。")
            appendLine("只输出一个 JSON 对象，不要 markdown、不要解释：")
            appendLine("{\"title\":\"计划标题（≤16 字）\",\"dailyMinutes\":" + daily + ",\"stages\":[{\"name\":\"阶段名（≤16 字）\",\"weeks\":2,\"focus\":\"聚焦什么（≤30 字）\",\"resources\":[{\"title\":\"资源名\",\"url\":\"https://…\",\"note\":\"一句话说明\"}]}]}")
            appendLine("约束：阶段 1–8 个，每个阶段 1–12 周，总周数 ≤ 40；这个计划从 " + week + " 这一周开始。")
            if (deadlineWeek != null) appendLine("用户给了截止日期（" + deadline + "，即 " + deadlineWeek + "），总周数必须覆盖到那之前。")
            appendLine("写法要求：")
            appendLine("1. 阶段只写名称、周数、聚焦和资源；不要写交付物、过关标准。")
            appendLine("2. resources 只能从下面给出的候选项里挑（url 必须逐字照抄），每个阶段 1–3 条；优先选：")
            appendLine("   - 概念/系统课 → 视频；查阅/术语 → 文档；动手练 → 仓库；经验/考点 → 文章或问答。")
            appendLine("3. 不同阶段尽量用不同资源；同一门课最多出现在两个阶段。")
            appendLine("4. 不要写来源站点名、内部工具名。")
        }
        val user = buildString {
            appendLine("目标：" + goal)
            appendLine("今天：" + today + "，本周：" + week)
            if (deadline.isNotBlank()) appendLine("截止日期：" + deadline)
            appendLine("每天投入：" + daily + " 分钟")
            appendLine()
            appendLine("<resource_candidates>")
            if (candidates.isEmpty()) {
                appendLine("（这次没有检索到可用资源；resources 给空数组，不要编造。）")
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
            appendLine("请按上述 JSON 格式输出阶段表。")
        }
        return withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            llm.complete(
                listOf(
                    LlmMessage(LlmRole.System, system),
                    LlmMessage(LlmRole.User, user),
                ),
            )
        }
    }

    private suspend fun generateWeekItems(
        goal: String,
        draft: ResearchTools.ShellDraft,
        week: String,
        daily: Int,
    ): List<WeekItem> {
        val system = buildString {
            appendLine("你是学习规划师。现在只排这一周的具体学习条目（不是整份计划）。")
            appendLine("只输出 JSON：{\"items\":[{\"title\":\"条目（具体内容/知识点，≤24 字）\",\"minutes\":30,\"kind\":\"study|practice|review\"}]}")
            appendLine("约束：5–12 条；每条 10–45 分钟；本周总时长约 " + (daily * 7) + " 分钟，按 " + daily + " 分钟/天摊；")
            appendLine("标题写具体内容或知识点（如「进程调度算法」「页面置换计算」），不要写「学习/复习/了解」这种动作词。")
        }
        val user = buildString {
            appendLine("目标：" + goal)
            appendLine("本周：" + week)
            appendLine("总计划阶段：")
            draft.stages.forEach { stage ->
                append("- ").append(stage.name).append("（约 ").append(stage.weeks).append(" 周）")
                if (stage.focus.isNotBlank()) append("：").append(stage.focus)
                appendLine()
            }
            appendLine("请只排本周的条目。")
        }
        val raw = withTimeoutOrNull(PLAN_TIMEOUT_MS) {
            llm.complete(listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user)))
        } ?: return emptyList()
        return parseWeekItems(raw)
    }

    // ---------------------------------------------------------------- 文档与格式

    private fun masterDoc(
        goal: String,
        deadline: String,
        draft: ResearchTools.ShellDraft,
        candidates: List<Candidate>,
        thisWeek: String,
    ): String = buildString {
        appendLine("# 目标与验收")
        appendLine("- 目标：" + goal)
        if (deadline.isNotBlank()) appendLine("- 截止：" + deadline)
        appendLine("- 验收：按阶段推进；每阶段结束时能用自己的话讲清重点并完成对应练习。")
        appendLine()
        appendLine("## 阶段")
        var cursor = isoWeekStart(thisWeek)
        draft.stages.forEachIndexed { index, stage ->
            val start = cursor
            val end = start?.plusWeeks(stage.weeks.toLong())?.minusDays(1)
            append("- 阶段 ").append(index + 1).append(" · ").append(stage.name)
            if (start != null && end != null) {
                append("（").append(isoWeekOf(start)).append(" – ").append(isoWeekOf(end)).append("，约 ").append(stage.weeks).append(" 周）")
            } else {
                append("（约 ").append(stage.weeks).append(" 周）")
            }
            if (stage.focus.isNotBlank()) append("：").append(stage.focus)
            appendLine()
            if (stage.resources.isNotEmpty()) {
                appendLine("  - 资源：")
                stage.resources.forEach { resource ->
                    append("    - [").append(resource.title).append("](").append(resource.url).append(")")
                    if (resource.note.isNotBlank()) append(" — ").append(resource.note)
                    appendLine()
                }
            }
            if (start != null) cursor = start.plusWeeks(stage.weeks.toLong())
        }
        if (candidates.isNotEmpty()) {
            appendLine()
            appendLine("## 资源清单")
            appendLine("下面是这次调研聚合到的候选资源，可按需补充：")
            candidates.groupBy { it.type }.forEach { (type, list) ->
                appendLine()
                appendLine("### " + type)
                list.forEach { candidate ->
                    append("- [").append(candidate.title).append("](").append(candidate.url).append(")")
                    if (candidate.source.isNotBlank()) append(" — ").append(candidate.source)
                    appendLine()
                }
            }
        }
    }

    private fun renderMaster(draft: ResearchTools.ShellDraft, candidates: List<Candidate>, chars: Int): String = buildString {
        appendLine("调研完成：计划「" + draft.title + "」，共 " + draft.stages.size + " 个阶段，约 " + draft.stages.sumOf { it.weeks } + " 周。")
        draft.stages.forEachIndexed { index, stage ->
            append((index + 1).toString()).append(". ").append(stage.name).append("（约 ").append(stage.weeks).append(" 周）")
            if (stage.focus.isNotBlank()) append("：").append(stage.focus)
            appendLine()
            if (stage.resources.isNotEmpty()) {
                appendLine("   资源：" + stage.resources.joinToString("；") { it.title })
            }
        }
        if (candidates.isNotEmpty()) {
            appendLine("这次聚合到 " + candidates.size + " 条候选资源（" + candidates.groupBy { it.type }.entries.joinToString("、") { it.key + " " + it.value.size } + "）。")
        } else {
            appendLine("这次没有检索到可用资源；资源先空着，不编造链接。")
        }
        append("总计划文档已生成（" + chars + " 字），可在知识库「计划文档」里查看和修改意见。")
    }

    private data class WeekItem(val title: String, val minutes: Int, val kind: String)

    private fun parseWeekItems(raw: String): List<WeekItem> {
        val jsonText = extractJsonObject(raw) ?: return emptyList()
        val root = runCatching { Json.parseToJsonElement(jsonText) as? JsonObject }.getOrNull() ?: return emptyList()
        val array = root["items"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            WeekItem(
                title = title.take(60),
                minutes = (obj["minutes"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(10, 45),
                kind = when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
                    "practice" -> "practice"
                    "review" -> "review"
                    else -> "study"
                },
            )
        }.take(12)
    }

    private fun weekDoc(week: String, focus: String, items: List<WeekItem>): String = buildString {
        appendLine("# " + week + " 周计划")
        if (focus.isNotBlank()) appendLine("- 本周重点：" + focus)
        appendLine()
        appendLine("## " + week)
        items.forEach { item ->
            append("- ").append(item.title)
            if (item.kind == "practice") append("（练习）")
            if (item.kind == "review") append("（复习）")
            append("（约 ").append(item.minutes).append(" 分钟）")
            appendLine()
        }
    }

    private fun inferKind(text: String): String = when {
        text.contains("复习") || text.contains("回顾") -> "review"
        text.contains("练习") || text.contains("题") || text.contains("实验") -> "practice"
        else -> "study"
    }

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

    // ---------------------------------------------------------------- 小件

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
        return "计划库打不开，这次没写任何东西。" + (reason?.let { "具体原因：$it。" } ?: "")
    }

    private fun env(planId: String, origin: String) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.AGENT,
        planId = planId,
        origin = origin,
    )

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.let {
        it.content.toBooleanStrictOrNull()
    }

    private fun String.stringField(key: String): String? {
        val root = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return null
        return root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun payloadWith(payload: String, vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): String {
        val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return JsonObject(root + pairs.toMap()).toString()
    }

    private companion object {
        const val SCOUT_TIMEOUT_MS = 45_000L
        const val PLAN_TIMEOUT_MS = 45_000L
        val FENCE: String = String(CharArray(3) { Char(96) })
    }
}
