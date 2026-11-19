package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import dev.trellis.feature.learning.PlanWorkflowStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 计划工作流 = 三段文档转换：
 *
 *   调研结果   → 总计划文档（只写草稿，不动计划库）
 *   总计划文档 → 周文档（建计划本体 + 写周文档，不建节点）
 *   周文档     → 计划节点（只有要学的点，kind 一律 study）
 *
 * 这里钉住两件容易回归的事：
 * 1. 每段结束都停在 awaiting，**没点「开始下一步」不能推进**（consent 由宿主发）；
 * 2. 节点只装知识点：周文档里不出现复习/练习，落库的 kind 只有 study。
 */
class PlanWorkflowTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val wednesday: LocalDate = LocalDate.parse("2026-11-04")
    private val thisWeek: String = "2026-W45"

    private class FixedClock(private val date: LocalDate, override val zone: ZoneId) : TrellisClock {
        private val millis: Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
        override fun nowMs(): Long = millis
        override fun monotonicMs(): Long = millis
        override fun today(): LocalDate = date
        override fun isoWeek(): String = "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        override fun simulatedDate(): LocalDate? = null
    }

    private class FakeLlm(private val replies: List<String>) : LlmClient {
        var calls = 0
        val userMessages = mutableListOf<String>()
        override suspend fun complete(messages: List<LlmMessage>): String {
            calls++
            userMessages += messages.lastOrNull { it.role == dev.trellis.ai.LlmRole.User }?.content.orEmpty()
            return replies.getOrElse(calls - 1) { replies.last() }
        }
    }

    private class NoopResearch : ResearchGateway {
        override suspend fun gather(goal: String): ResearchSources? = null
    }

    private class Harness(
        val home: File,
        val service: LearningPlanService,
        val docStore: PlanDocStore,
        val workflow: PlanWorkflowStore,
        val tool: dev.trellis.ai.tool.AgentTool,
        val llm: FakeLlm,
        val checklists: MutableList<String>,
        val docLinks: MutableList<Triple<String, String, String?>>,
        val gates: MutableList<Pair<String, String>>,
        /** true = 模拟「用户点了开始下一步」；false = 用户没点，advance 应该被拒。 */
        var autoConsent: Boolean = true,
    )

    /**
     * 新口径的总计划文档：阶段 = 主题清单（约 N 天），不写周区间、不写日期。
     * 2026-11-18 起阶段与周解绑，周文档的阶段主题从这里取。
     */
    private fun masterJson(): String {
        val doc = listOf(
            "# 目标与验收",
            "- 目标：两个月后期末考试，复习操作系统",
            "- 验收：每章能自己复述并做出例题",
            "## 阶段",
            "- 阶段 1 · 基础（约 3 天）：中断与系统调用",
            "  - 主题：中断与系统调用（约 3 天）",
            "- 阶段 2 · 进程（约 5 天）：调度与同步",
            "  - 主题：进程状态（约 2 天）",
            "  - 主题：调度算法（约 3 天）",
        ).joinToString("\n")
        return "{\"title\":\"操作系统备考\",\"doc\":\"" + doc.replace("\n", "\\n") + "\"}"
    }

    private fun weekDoc(): String = listOf(
        "# " + thisWeek + " 周计划",
        "- 本周重点：中断与系统调用",
        "",
        "## " + thisWeek,
        "### 主题：中断与系统调用",
        // 2026-11-17 起条目只标体量档（小/中/大 = 15/30/45），不再写由预算摊出来的分钟数。
        "- 中断与系统调用（中 · 约 30 分钟）",
        "- 中断处理流程（小 · 约 15 分钟）",
        "- 系统调用过程（大 · 约 45 分钟）",
    ).joinToString("\n")

    /** 临时拼一份总计划文档 JSON：阶段行由调用方给。 */
    private fun masterDoc(vararg stages: String): String {
        val doc = (listOf(
            "# 目标与验收",
            "- 目标：复习操作系统",
            "- 验收：能复述主干",
            "## 阶段",
        ) + stages).joinToString("\n")
        return "{\"title\":\"操作系统备考\",\"doc\":\"" + doc.replace("\n", "\\n") + "\"}"
    }

    private var harnessRef: Harness? = null

    private fun harness(replies: List<String> = listOf(masterJson(), weekDoc(), masterJson())): Harness {
        val home = Files.createTempDirectory("plan-workflow").toFile()
        val clock = FixedClock(wednesday, zone)
        val service = LearningPlanStore.open(File(home, "plans/plan.db").toPath(), { clock.nowMs() }, zone)
        val docStore = PlanDocStore(home)
        val workflow = PlanWorkflowStore(home)
        val llm = FakeLlm(replies)
        val checklists = mutableListOf<String>()
        val docLinks = mutableListOf<Triple<String, String, String?>>()
        val gates = mutableListOf<Pair<String, String>>()
        val consents = mutableSetOf<Pair<String, String>>()
        lateinit var h: Harness
        val flow = PlanWorkflow(
            service = { service },
            workflow = { workflow },
            docStore = { docStore },
            clock = clock,
            llm = llm,
            gateway = { NoopResearch() },
            onChanged = {},
            onChecklist = { jobId -> checklists += jobId },
            onDocLink = { id, label, week -> docLinks += Triple(id, label, week) },
            onAskConfirm = { jobId, phase, _, _ ->
                gates += jobId to phase
                // 测试里默认模拟「用户点了继续」：凭据备好，advance 才能过。
                // 需要验证「没点不能推进」的用例把 autoConsent 关掉。
                if (h.autoConsent) consents += jobId to phase
            },
            consumeConsent = { jobId, phase -> consents.remove(jobId to phase) },
        )
        h = Harness(home, service, docStore, workflow, flow.all.single(), llm, checklists, docLinks, gates)
        harnessRef = h
        return h
    }

    @AfterTest
    fun tearDown() {
        harnessRef?.let {
            runCatching { it.docStore.close() }
            runCatching { it.workflow.close() }
            runCatching { (it.service as? AutoCloseable)?.close() }
            runCatching { it.home.deleteRecursively() }
        }
        harnessRef = null
    }

    /** 默认当成「目标已经说清楚」：带 brief 跳过提问段，直接进调研→总计划。 */
    private val clearBrief: String =
        "目标：两个月后期末考试复习操作系统\n动机：补基础\n起点：学过 C 语言\n" +
            "约束：每天 60 分钟\n验收：每章能复述并做例题"

    private suspend fun start(h: Harness, deadline: String? = null, brief: String? = clearBrief): String {
        val result = h.tool.call(
            buildJsonObject {
                put("action", "start")
                put("goal", "两个月后期末考试，复习操作系统")
                if (deadline != null) put("deadline", deadline)
                if (brief != null) put("brief", brief)
            },
        )
        assertTrue(!result.isError, result.content)
        return h.workflow.latestOpen("plan_flow")?.id ?: error("没有建出流程")
    }

    /** 模拟「问清了，把摘要交上去」：这一步只等用户确认，不写文档。 */
    private suspend fun submitBrief(h: Harness) {
        val result = h.tool.call(
            buildJsonObject {
                put("action", "brief")
                put("goal", "三个月内达到能投前端实习的 JS 基础")
                put("brief", "目标：三个月内能投前端实习\n动机：现在的岗位没成长\n起点：会一点 HTML/CSS\n" +
                    "约束：每天 90 分钟\n验收：能独立做两个页面并部署\n时限：2027-02-19 前")
                put("dailyMinutes", 90)
                put("deadline", "2027-02-19")
            },
        )
        assertTrue(!result.isError, result.content)
    }

    private suspend fun advance(h: Harness) {
        val result = h.tool.call(buildJsonObject { put("action", "advance") })
        assertTrue(!result.isError, result.content)
    }

    @Test
    fun masterPhaseOnlyWritesDraftAndWaits() = runTest {
        val h = harness()
        val jobId = start(h)

        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("awaiting_user", job.state)
        assertEquals("running", job.steps.first { it.step == "master" }.state)
        // 第一段只出文档：计划库还没有计划，文档在草稿目录。
        assertTrue(h.service.plans().isEmpty())
        assertTrue(h.docStore.draftIds().isNotEmpty())
        assertTrue(h.docLinks.single().first.startsWith("draft_"))
        assertTrue(h.checklists.isNotEmpty(), h.checklists.toString())
        // 宿主摆了「开始下一步」这一个按钮（推进不由模型决定）。
        assertTrue(h.gates.single().second == "master", h.gates.toString())
    }

    @Test
    fun advanceWithoutUserClickIsRejected() = runTest {
        val h = harness()
        h.autoConsent = false
        start(h)
        // 用户没点「开始下一步」：模型自己调 advance 必须被拒，流程停在原地。
        val rejected = h.tool.call(buildJsonObject { put("action", "advance") })
        assertTrue(rejected.isError, rejected.content)
        assertTrue(rejected.content.contains("还没确认"), rejected.content)
        assertEquals("awaiting_user", assertNotNull(h.workflow.latestOpen("plan_flow")).state)
    }

    @Test
    fun weekPhaseCreatesPlanAndWeekDocButNoNodes() = runTest {
        val h = harness()
        val jobId = start(h)
        advance(h)

        val plans = h.service.plans()
        assertEquals(1, plans.size, plans.toString())
        val planId = plans.first().id
        assertEquals("awaiting_user", assertNotNull(h.workflow.load(jobId)).state)
        val weekText = h.docStore.readWeek(planId, thisWeek).orEmpty()
        assertTrue(weekText.contains("中断处理流程"), weekText)
        assertTrue(weekText.contains("### 主题：中断与系统调用"), weekText)
        // 投入参考由宿主按每天投入算（60 × 7 = 7 小时），不摊到条目上。
        assertTrue(weekText.contains("本周投入参考：约 7 小时"), weekText)
        assertFalse(weekText.contains("每天投入："), weekText)
        // 第一段草稿已经落成正式总文档。
        assertTrue(h.docStore.read(planId).orEmpty().contains("## 阶段"))
        // 第二段只写文档，节点还没进计划库。
        val week = assertNotNull(h.service.week(planId, thisWeek))
        assertTrue(week.commitment?.units.isNullOrEmpty(), "第二段不该建节点")
    }

    @Test
    fun unitsPhaseTurnsWeekDocIntoStudyOnlyNodes() = runTest {
        val h = harness()
        val jobId = start(h)
        advance(h)
        advance(h)

        val planId = h.service.plans().first().id
        assertEquals("done", assertNotNull(h.workflow.load(jobId)).state)
        val units = assertNotNull(h.service.week(planId, thisWeek)).commitment?.units.orEmpty()
        assertEquals(3, units.size, units.joinToString { it.title })
        assertTrue(units.any { it.title.contains("系统调用") }, units.joinToString { it.title })
        // 节点章节写成「阶段 · 主题」：阶段进度靠它按主题聚合（2026-11-18 起阶段不绑周）。
        assertTrue(
            units.all { it.chapter == "基础 · 中断与系统调用" },
            units.joinToString { it.title + "@" + it.chapter },
        )
        // 节点只装要学的点：没有复习/练习类条目，kind 一律 study。
        assertTrue(units.all { it.kind == "study" }, units.joinToString { it.kind })
        assertTrue(units.none { it.title.contains("复习") || it.title.contains("练习") })
        // 时长按体量档落库：小 15 / 中 30 / 大 45，不同条目不再被摊成同一个数。
        assertEquals(
            listOf(30, 15, 45),
            units.map { it.estimateMinutes },
            units.joinToString { it.title + "=" + it.estimateMinutes },
        )
        // 周文档链接要指到「那一周」，不是总文档；标签也不能再是「总计划文档」。
        val weekLink = h.docLinks.last()
        assertEquals(thisWeek, weekLink.third, weekLink.toString())
        assertTrue(weekLink.second.contains("周文档"), weekLink.second)
    }

    /**
     * 真实事故（2026-11-17）：计划没建完、用户把计划删了，流程还「开着」，
     * 再开新对话 start 会被「已经有一段计划流程在进行中」挡住。
     * 期望：删计划后旧流程自动作废，start 能继续。
     */
    @Test
    fun staleFlowDoesNotBlockNewStartAfterPlanDeleted() = runTest {
        val h = harness()
        val oldJob = start(h)
        advance(h) // 第二段建出计划本体
        val planId = h.service.plans().single().id

        // 放弃并删除这份计划（和界面上的「放弃 → 删除」同一条路）。
        // 注意命令要各用各的 commandId：同一个 id 会被幂等层当成「已经执行过」直接回放。
        fun cmd(origin: String) = dev.trellis.domain.plan.CommandEnvelope(
            commandId = "cmd_test_" + origin + "_" + planId,
            actor = dev.trellis.domain.plan.Actor.USER,
            planId = planId,
            origin = origin,
        )
        assertTrue(h.service.abandonPlan(cmd("test.abandon"), planId).ok, "放弃失败")
        assertTrue(h.service.deletePlan(cmd("test.delete"), planId).ok, "删除失败")
        assertTrue(h.service.plans().none { it.id == planId }, "计划应已从列表消失")

        // 新的 start 不能被旧流程挡住；旧流程要被取消。
        val result = h.tool.call(
            buildJsonObject {
                put("action", "start")
                put("goal", "重新开始复习操作系统")
            },
        )
        assertFalse(result.content.contains("已经有一段计划流程在进行中"), result.content)
        val open = assertNotNull(h.workflow.latestOpen("plan_flow"))
        assertTrue(open.id != oldJob, "应该是一段新流程")
        assertEquals("cancelled", assertNotNull(h.workflow.load(oldJob)).state)
    }

    /** 只「放弃」不删除也要能让流程作废：否则 start 同样被挡。 */
    @Test
    fun staleFlowDoesNotBlockNewStartAfterPlanAbandoned() = runTest {
        val h = harness()
        val oldJob = start(h)
        advance(h)
        val planId = h.service.plans().single().id
        val env = dev.trellis.domain.plan.CommandEnvelope(
            commandId = "cmd_test_abandon_" + planId,
            actor = dev.trellis.domain.plan.Actor.USER,
            planId = planId,
            origin = "test.abandon",
        )
        assertTrue(h.service.abandonPlan(env, planId).ok, "放弃失败")

        val result = h.tool.call(
            buildJsonObject {
                put("action", "start")
                put("goal", "换一份计划重新开始")
            },
        )
        assertFalse(result.content.contains("已经有一段计划流程在进行中"), result.content)
        assertEquals("cancelled", assertNotNull(h.workflow.load(oldJob)).state)
    }

    /** 计划删除后清理 hook：cancelOpenForPlan 只取消绑在这份计划上的流程。 */
    @Test
    fun cancelOpenForPlanOnlyTouchesBoundJobs() = runTest {
        val h = harness()
        val job = start(h)
        assertEquals(0, h.workflow.cancelOpenForPlan("pln_other"))
        assertEquals(0, h.workflow.cancelOpenForPlan("prc_not_bound_either"))
        assertEquals("awaiting_user", assertNotNull(h.workflow.load(job)).state)
        h.workflow.updatePayload(job, "{\"planId\":\"pln_bound\"}")
        assertEquals(1, h.workflow.cancelOpenForPlan("pln_bound"))
        assertEquals("cancelled", assertNotNull(h.workflow.load(job)).state)
    }

    /** 截止在两周后（15 天预算）：masterJson 的主题合计 10 天，不该重试；阶段不写周区间。 */
    @Test
    fun topicsWithinTheBudgetDoNotRetry() = runTest {
        val h = harness(listOf(masterJson(), weekDoc()))
        start(h, deadline = "2026-11-20")
        assertEquals(1, h.llm.calls, "主题天数没超预算就不该重试")
        advance(h)
        val planId = h.service.plans().single().id
        val stages = h.service.stages(planId)
        assertEquals(2, stages.size)
        assertEquals(null, stages.first().weekStart)
        assertEquals(listOf(3, 5), stages.map { stage -> stage.topics.sumOf { it.days } })
    }

    /**
     * 真实事故（2026-11-18）：用户说「一周内过一遍」，总文档却切成三个阶段、主题合计远超一周。
     * 现在阶段不绑周：校验「主题天数合计 ≤ 截止前的可用天数」，超了就重试一次。
     */
    @Test
    fun masterRetriesWhenTopicsOverrunTheDeadline() = runTest {
        val tooLong = masterDoc(
            "- 阶段 1 · 体系结构（约 5 天）：五层框架",
            "  - 主题：体系结构与分层（约 3 天）",
            "  - 主题：物理层与链路层（约 2 天）",
            "- 阶段 2 · 网络层（约 4 天）：转发",
            "  - 主题：IP 与路由（约 4 天）",
            "- 阶段 3 · 应用层（约 4 天）：收尾",
            "  - 主题：应用层协议（约 4 天）",
        )
        val fits = masterDoc(
            "- 阶段 1 · 全程通读（约 3 天）：五层结构 + 主干概念",
            "  - 主题：五层结构与主干概念（约 3 天）",
        )
        val h = harness(listOf(tooLong, fits, weekDoc()))
        start(h, deadline = "2026-11-06")
        assertEquals(2, h.llm.calls, "主题天数超出截止预算应当重试一次")
        // 重试要告诉模型「上一次具体错在哪」：只说「不合格」它会盲撞第二次。
        assertTrue(h.llm.userMessages.last().contains("主题天数合计"), h.llm.userMessages.last())
        advance(h)
        val planId = h.service.plans().single().id
        val stages = h.service.stages(planId)
        assertEquals(1, stages.size, "一周的计划只能有 1 个阶段")
        assertEquals(null, stages.single().weekStart)
        assertEquals(1, stages.single().topics.size)
        assertEquals(3, stages.single().topics.single().days)
    }

    /**
     * 真实事故（2026-11-17 真跑）：开思考时模型把输出预算全花在推理上，总计划文档的
     * JSON 被截断（正文只剩 572 字），流程直接报「模型没回上来」。现在结构化输出会重试一次。
     */
    @Test
    fun masterRetriesOnceWhenTheJsonIsTruncated() = runTest {
        val h = harness(listOf("{\"title\":\"操作系统备考\",\"doc\":\"# 目标与验收", masterJson(), weekDoc()))
        val jobId = start(h)
        assertEquals(2, h.llm.calls, "第一段应该重试一次")
        assertEquals("awaiting_user", assertNotNull(h.workflow.load(jobId)).state)
        assertTrue(h.docStore.draftIds().isNotEmpty(), "重试后草稿应该写出来")
    }

    /** 截止写错了（把两个月后的考试当成计划截止）不用重开流程：revise 带 deadline 就能改。 */
    @Test
    fun reviseCanFixTheDeadline() = runTest {
        val h = harness(listOf(masterJson(), masterJson()))
        val jobId = start(h, deadline = "2027-01-18")
        val result = h.tool.call(
            buildJsonObject {
                put("action", "revise")
                put("feedback", "截止应该是一周后，不是考试那天")
                put("deadline", "2026-11-22")
            },
        )
        assertTrue(!result.isError, result.content)
        assertTrue(
            assertNotNull(h.workflow.load(jobId)).payload.contains("2026-11-22"),
            assertNotNull(h.workflow.load(jobId)).payload,
        )
    }

    /** 周文档被截断时同样重试；重试成功后周文档照常落盘。 */
    @Test
    fun weekRetriesWhenTheDocIsUnusable() = runTest {
        val h = harness(listOf(masterJson(), "这是周计划，但格式不对。", weekDoc()))
        val jobId = start(h)
        advance(h)
        assertEquals(3, h.llm.calls, "第二段应该重试一次")
        assertEquals("awaiting_user", assertNotNull(h.workflow.load(jobId)).state)
        val planId = h.service.plans().single().id
        assertTrue(h.docStore.readWeek(planId, thisWeek).orEmpty().contains("中断处理流程"))
    }

    @Test
    fun secondStartIsRejectedWhileAJobIsOpen() = runTest {
        val h = harness()
        start(h)
        val again = h.tool.call(
            buildJsonObject {
                put("action", "start")
                put("goal", "再开一份")
            },
        )
        assertTrue(again.isError)
        assertTrue(again.content.contains("已经有一段计划流程"), again.content)
    }
    /**
     * 打字确认不再硬匹配白名单：交给一次「是/否」调用，宿主只认 JSON 里的 confirm=true。
     * 解析不出来、模型没按格式说，一律当没说（宁可不推进）。
     */
    @Test
    fun confirmVerdictAcceptsOnlyExplicitJsonTrue() {
        assertTrue(PlanWorkflow.parseConfirmVerdict("{\"confirm\":true}"))
        assertTrue(PlanWorkflow.parseConfirmVerdict("`@json\n{\"confirm\": true}\n`@"))
        assertFalse(PlanWorkflow.parseConfirmVerdict("{\"confirm\":false}"))
        assertFalse(PlanWorkflow.parseConfirmVerdict("好，继续吧"))
        assertFalse(PlanWorkflow.parseConfirmVerdict(""))
        val messages = PlanWorkflow.confirmVerdictMessages(
            "「总计划文档 → 周文档」还没做，要现在开始吗？",
            listOf("开始下一步", "我要改一下", "先不建"),
            "行，那就往下走吧",
        )
        val user = messages.last().content
        assertTrue(user.contains("要现在开始吗"), user)
        assertTrue(user.contains("行，那就往下走吧"), user)
    }

    /**
     * 取消流程要把还没落成计划的草稿删掉：它是这段流程的中间产物，
     * 留着会在知识库左栏挂一份永远点不开的「总计划（草稿）」（2026-11-19 用户反馈）。
     */
    @Test
    fun cancellingAFlowDeletesItsUnadoptedDraft() = runTest {
        val h = harness()
        start(h)
        assertTrue(h.docStore.draftIds().isNotEmpty(), "第一段应该先写出草稿")
        val cancelled = h.tool.call(buildJsonObject { put("action", "cancel") })
        assertFalse(cancelled.isError, cancelled.content)
        assertTrue(h.docStore.draftIds().isEmpty(), "取消后草稿应该删掉：" + h.docStore.draftIds())
    }

    // ---------------------------------------------------------------- 「确定目标」段

    /**
     * 空泛目标（「我想学编程」）不能直接进文档转换：流程先停在「确定目标」，
     * 不写文档、不建计划、不调模型 —— 问题由模型自己在对话里问。
     */
    @Test
    fun vagueGoalEntersClarifyPhaseWithoutWritingAnything() = runTest {
        val h = harness()
        val jobId = start(h, brief = null)

        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("running", job.state)
        assertEquals("running", job.steps.first { it.step == "goal" }.state)
        assertTrue(h.service.plans().isEmpty(), "澄清阶段不能建计划")
        assertTrue(h.docStore.draftIds().isEmpty(), "澄清阶段不能写文档")
        assertTrue(h.gates.isEmpty(), "澄清阶段不该摆「开始下一步」卡：" + h.gates)
        assertEquals(0, h.llm.calls, "澄清阶段不调模型写文档")
    }

    /** 交摘要只把目标记进流程并摆确认卡：用户点头之前不开始调研。 */
    @Test
    fun submittingTheGoalBriefStopsForConfirmation() = runTest {
        val h = harness()
        val jobId = start(h, brief = null)
        submitBrief(h)

        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("awaiting_user", job.state)
        assertEquals("goal", h.gates.single().second, h.gates.toString())
        assertTrue(job.payload.contains("三个月内能投前端实习"), job.payload)
        assertTrue(job.payload.contains("2027-02-19"), job.payload)
        assertTrue(job.payload.contains("dailyMinutes"), job.payload)
        assertTrue(h.service.plans().isEmpty(), "确认前不能建计划")
        assertTrue(h.docStore.draftIds().isEmpty(), "确认前不能写文档")
    }

    /** 摘要交过之后模型又调 brief：不能把确认卡重新摆一遍，要提示它 advance。 */
    @Test
    fun submittingTheBriefAgainWhileWaitingIsRejected() = runTest {
        val h = harness()
        val jobId = start(h, brief = null)
        submitBrief(h)
        val again = h.tool.call(
            buildJsonObject {
                put("action", "brief")
                put("brief", "目标：又交了一遍\n动机：转行\n起点：零基础\n约束：每天两小时\n验收：做出两个项目")
            },
        )
        assertTrue(again.isError, again.content)
        assertTrue(again.content.contains("advance"), again.content)
        assertEquals("awaiting_user", assertNotNull(h.workflow.load(jobId)).state)
        assertEquals(1, h.gates.size, h.gates.toString())
    }

    /** 用户点了确认之后才跑调研/总计划：goal 收尾、master 停下等确认。 */
    @Test
    fun advancingAfterTheGoalConfirmationStartsTheResearchPhase() = runTest {
        val h = harness()
        val jobId = start(h, brief = null)
        submitBrief(h)
        advance(h)

        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("done", job.steps.first { it.step == "goal" }.state)
        assertEquals("awaiting_user", job.state)
        assertTrue(h.docStore.draftIds().isNotEmpty(), "确认之后应该写出总计划草稿")
        assertEquals("master", h.gates.last().second, h.gates.toString())
        // 问出来的情况要进总计划提示词（阶段划分按起点/预算来）。
        assertTrue(h.service.plans().isEmpty(), "总计划这一段只写草稿")
    }

    /** 用户说「我要改一下」：流程退回对话继续问，不往下跑文档。 */
    @Test
    fun revisingTheGoalGoesBackToTheConversation() = runTest {
        val h = harness()
        val jobId = start(h, brief = null)
        submitBrief(h)

        val revised = h.tool.call(
            buildJsonObject {
                put("action", "revise")
                put("feedback", "每天其实只有 40 分钟，而且想先做后端而不是前端")
                put("goal", "四个月内达到能投后端实习的基础")
            },
        )
        assertTrue(!revised.isError, revised.content)
        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("running", job.state)
        assertEquals("running", job.steps.first { it.step == "goal" }.state)
        assertTrue(job.payload.contains("四个月内"), job.payload)
        assertEquals(1, h.gates.size, "退回对话后不该再多摆一张卡")
        assertTrue(h.docStore.draftIds().isEmpty())
    }

    /**
     * 总计划文档不合格时，错误里要写清哪一条不合格：
     * 只知道「没生成出来」的模型下一轮还会盲撞同一个错。
     */
    @Test
    fun masterFailureSaysWhyTheDocWasRejected() = runTest {
        val noTopics = "{\"title\":\"前端\",\"doc\":\"# 目标与验收\\n- 目标：找实习\\n## 阶段\\n- 阶段 1 · 基础（约 30 天）：HTML\"}"
        val h = harness(listOf(noTopics, noTopics))
        val result = h.tool.call(
            buildJsonObject {
                put("action", "start")
                put("goal", "学前端，找实习")
                put("brief", "目标：找前端实习\n动机：转行\n起点：零基础\n约束：每天 2 小时\n验收：做出两个项目")
            },
        )
        assertTrue(result.isError, result.content)
        assertTrue(result.content.contains("没有主题清单"), result.content)
    }

    /** 目标本来就说清楚：start 带 brief，跳过提问段直接进调研。 */
    @Test
    fun aClearGoalSkipsTheClarifyPhase() = runTest {
        val h = harness()
        val jobId = start(h)

        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("done", job.steps.first { it.step == "goal" }.state)
        assertEquals("awaiting_user", job.state)
        assertEquals("master", h.gates.single().second, h.gates.toString())
        assertTrue(h.docStore.draftIds().isNotEmpty())
    }
}
