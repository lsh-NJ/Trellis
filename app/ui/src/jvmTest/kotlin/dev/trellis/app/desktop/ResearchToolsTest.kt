package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `research_plan` 的回归测试：只有一句目标时，要落成「空壳计划 + 阶段表 + 大纲文档 + 目标/里程碑 + 排好的第一周」。
 *
 * 建完计划不能留一个空周：同一回合要复用 plan_week 的共享逻辑把第一周排出来（先写周文档、再铺到天）；
 * 后续周才留给 plan_week。失败路径必须明确报错且不留半成品计划。
 */
class ResearchToolsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-04 是周三，所在周 = 2026-W45。 */
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

    /** 按调用次序回话：第 1 次是阶段表，第 2 次是第一周的条目。 */
    private class FakeLlm(private val replies: List<String>, private val delayMs: Long = 0) : LlmClient {
        val seen = mutableListOf<List<LlmMessage>>()
        private var calls = 0

        override suspend fun complete(messages: List<LlmMessage>): String {
            seen += messages
            val reply = replies.getOrElse(calls) { replies.last() }
            calls++
            if (delayMs > 0) delay(delayMs)
            return reply
        }
    }

    private class FakeResearch : ResearchGateway {
        var goalRequested: String? = null

        override suspend fun gather(goal: String): ResearchSources {
            goalRequested = goal
            return ResearchSources(
                hits = listOf(
                    ResearchHit(
                        title = "AI 应用工程师 学习路线",
                        url = "https://example.com/roadmap",
                        siteName = "example.com",
                        summary = "先打基础：Python、Linux、HTTP；再做 RAG 项目，最后准备面试。",
                        authority = 3,
                    ),
                    ResearchHit(
                        title = "招聘要求：AI 应用工程师",
                        url = "https://example.com/jobs",
                        siteName = "example.com",
                        summary = "要求熟悉 LangChain、向量库与检索增强生成。",
                        authority = 2,
                    ),
                ),
                pages = listOf(
                    ResearchPage(
                        url = "https://example.com/roadmap",
                        title = "AI 应用工程师 学习路线",
                        markdown = "# 学习路线\n1. 基础：Python / Linux\n2. 项目：RAG\n3. 面试",
                    ),
                ),
            )
        }
    }

    private class Harness(
        val home: File,
        val clock: FixedClock,
        val service: LearningPlanService,
        val docStore: PlanDocStore,
        val tools: ResearchTools,
        val llm: FakeLlm,
    )

    private fun harness(
        llmReply: String,
        delayMs: Long = 0,
        research: ResearchGateway? = null,
        weekReply: String = weekItemsJson(),
    ): Harness {
        val home = Files.createTempDirectory("research-tools").toFile()
        val clock = FixedClock(wednesday, zone)
        val service = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { clock.nowMs() },
            zone,
        )
        val docStore = PlanDocStore(home)
        val llm = FakeLlm(listOf(llmReply, weekReply), delayMs)
        val tools = ResearchTools(
            service = { service },
            planDocStore = { docStore },
            planDbError = { null },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = llm,
            research = research,
        )
        return Harness(home, clock, service, docStore, tools, llm)
    }

    /** 3 个阶段、共 5 周；带 dailyMinutes 建议。 */
    private fun shellJson(stages: String = threeStages()): String =
        """{"title":"数据应用工程师起步","dailyMinutes":90,"stages":""" + stages + "}"

    private fun threeStages(): String =
        """[""" +
            """{"name":"基础与工具","weeks":2,"focus":"命令行、Python、数据处理"}, """ +
            """{"name":"做一个可运行的小项目","weeks":2,"focus":"用真实数据做一个能跑的服务"}, """ +
            """{"name":"收尾与复盘","weeks":1,"focus":"补弱项、重做错题"}""" +
            """]"""

    /** research_plan 排第一周时模型给的内容（同一次调用里的第二次 LLM）。 */
    private fun weekItemsJson(): String =
        """{"focus":"命令行与 Python 起步","items":[""" +
            """{"title":"命令行 30 讲","chapter":"基础","kind":"study","minutes":30}, """ +
            """{"title":"写一个最小 RAG","chapter":"实践","kind":"practice","minutes":45}, """ +
            """{"title":"本周复盘","chapter":"复盘","kind":"review","minutes":15}""" +
            """]}"""

    private suspend fun call(
        harness: Harness,
        deadline: String? = null,
        dailyMinutes: Int? = null,
    ) = harness.tools.all
        .single { it.name == "research_plan" }
        .call(
            buildJsonObject {
                put("goal", "我想当 AI 应用工程师")
                if (deadline != null) put("deadline", deadline)
                if (dailyMinutes != null) put("dailyMinutes", dailyMinutes)
            },
        )

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_test_" + System.nanoTime(),
        actor = dev.trellis.domain.plan.Actor.AGENT,
        planId = planId,
        origin = "test",
    )

    @Test
    fun validStagesCreateShellPlanGoalMilestonesAndDoc() = runTest {
        val harness = harness(shellJson())
        val result = call(harness)
        assertFalse(result.isError, result.content)

        val planId = harness.service.activePlanId()
        assertNotNull(planId, "空壳计划要成为进行中的计划")
        assertEquals(1, harness.service.plans().size)
        assertTrue(result.content.contains("空壳") && result.content.contains("每天"), result.content)

        // 阶段区间落库：从本周起按各阶段周数依次铺（2+2+1 周）。
        val stages = harness.service.stages(planId)
        assertEquals(3, stages.size, stages.toString())
        assertEquals(listOf("基础与工具", "做一个可运行的小项目", "收尾与复盘"), stages.map { it.name })
        assertEquals(thisWeek, stages.first().weekStart)
        assertEquals("2026-W46", stages[0].weekEnd)
        assertEquals("2026-W47", stages[1].weekStart)
        assertEquals("2026-W49", stages[2].weekEnd)
        assertTrue(stages.all { it.weekStart <= it.weekEnd })

        // 大纲文档照旧。
        val doc = harness.docStore.read(planId)
        assertNotNull(doc, "大纲文档要写出来")
        assertTrue(doc.contains("# 目标与验收"), doc)
        assertTrue(doc.contains("基础与工具"), doc)

        // 节奏由用户拍板：工具里没传 dailyMinutes 时**不排本周**，
        // 等 AGENT 用 ask_choices 问完、set_plan_pace 之后再 plan_week。
        val week = harness.service.week(planId, thisWeek)
        assertTrue(week.commitment.units.isEmpty(), "没定节奏前不该排第一周：" + week.commitment.summary)
        assertTrue(week.days.none { it.items.isNotEmpty() }, "没定节奏前不该铺到天")
        assertNull(harness.docStore.readWeek(planId, thisWeek), "没定节奏前不该写周文档")
        assertTrue(result.content.contains("ask_choices"), result.content)
        assertTrue(result.content.contains("plan_week"), result.content)

        val goal = harness.service.goals().singleOrNull()
        assertNotNull(goal, "要顺手建一个目标")
        assertEquals(3, goal.milestones.size)
        assertTrue(goal.plans.any { it.id == planId })
    }

    @Test
    fun whenDailyMinutesIsGivenTheFirstWeekIsScheduledAtOnce() = runTest {
        val harness = harness(shellJson())
        val result = call(harness, dailyMinutes = 90)
        assertFalse(result.isError, result.content)
        val planId = assertNotNull(harness.service.activePlanId())

        // 用户这句话里已经给了时长：写进节奏，并同轮把本周排好，不再问。
        val week = harness.service.week(planId, thisWeek)
        assertEquals(
            setOf("命令行 30 讲", "写一个最小 RAG", "本周复盘"),
            week.commitment.units.map { it.title }.toSet(),
        )
        assertTrue(week.days.any { it.items.isNotEmpty() }, "给了时长就要铺到天")
        val weekDoc = harness.docStore.readWeek(planId, thisWeek)
        assertNotNull(weekDoc, "给了时长就要写周文档")
        assertTrue(result.content.contains("每天 90 分钟"), result.content)
    }

    @Test
    fun webSearchSourcesAreFedIntoThePlanningPrompt() = runTest {
        val research = FakeResearch()
        val harness = harness(shellJson(), research = research)
        val result = call(harness)
        assertFalse(result.isError, result.content)
        assertEquals("我想当 AI 应用工程师", research.goalRequested)

        val prompt = harness.llm.seen.first().joinToString("\n") { it.content }
        assertTrue(prompt.contains("<research_sources>"), "资料要进提示词：$prompt")
        assertTrue(prompt.contains("AI 应用工程师 学习路线"), prompt)
        assertTrue(prompt.contains("RAG"), prompt)

        assertTrue(result.content.contains("参考了网上搜到的"), result.content)
    }

    @Test
    fun shellPlanCanBeFilledLaterByAppendingUnits() = runTest {
        val harness = harness(shellJson())
        val result = call(harness)
        assertFalse(result.isError, result.content)
        val planId = assertNotNull(harness.service.activePlanId())

        val appended = harness.service.appendUnits(
            env(planId),
            planId,
            listOf(
                UnitDraft(chapter = "基础", title = "命令行 30 讲", estimateMinutes = 30, kind = "study"),
                UnitDraft(chapter = "实践", title = "写一个最小 RAG", estimateMinutes = 45, kind = "practice"),
            ),
        )
        assertTrue(appended.ok, appended.error?.message)
        assertEquals(2, harness.service.pool(planId).size, "新单元先落在待安排池")
    }

    @Test
    fun invalidReplyIsErrorAndCreatesNothing() = runTest {
        val harness = harness("我建议你先学 Python。")
        val result = call(harness)
        assertTrue(result.isError)
        assertTrue(harness.service.plans().isEmpty())
    }

    @Test
    fun timeoutIsErrorAndCreatesNothing() = runTest {
        val harness = harness(shellJson(), delayMs = 31_000)
        val result = call(harness)
        assertTrue(result.isError, result.content)
        assertTrue(harness.service.plans().isEmpty())
    }

    @Test
    fun stagesLongerThanDeadlineAreRejected() = runTest {
        // 截止日在本周（11-06）之内，但阶段一共 5 周 → 覆盖不到，解析失败、不建计划。
        val harness = harness(shellJson())
        val result = call(harness, deadline = "2026-11-06")
        assertTrue(result.isError)
        assertTrue(harness.service.plans().isEmpty())
    }

    @Test
    fun pastDeadlineIsTreatedAsAbsentInsteadOfFailingTheWholeRound() = runTest {
        val harness = harness(shellJson())
        val result = call(harness, deadline = "2026-10-01")
        // 模型偶尔会自己编一个过期日期；过去就按「没给截止日」处理，别让整轮调研白跑。
        assertFalse(result.isError, result.content)
        assertTrue(result.content.contains("已建好"), result.content)
        assertEquals(1, harness.service.plans().size)
    }
}
