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
import kotlin.test.assertTrue

/**
 * `research_plan` 的回归测试：只有一句目标时，要落成「空壳计划 + 大纲文档 + 目标/里程碑 + 时长建议」。
 *
 * 单元不在这一步排（拆阶段、建空壳是这一步的全部职责）；具体单元留给 plan_week。
 * 失败路径必须明确报错且不留半成品计划。
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

    private class FakeLlm(private val reply: String, private val delayMs: Long = 0) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String {
            if (delayMs > 0) delay(delayMs)
            return reply
        }
    }

    private class Harness(
        val home: File,
        val clock: FixedClock,
        val service: LearningPlanService,
        val docStore: PlanDocStore,
        val tools: ResearchTools,
    )

    private fun harness(llmReply: String, delayMs: Long = 0): Harness {
        val home = Files.createTempDirectory("research-tools").toFile()
        val clock = FixedClock(wednesday, zone)
        val service = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { clock.nowMs() },
            zone,
        )
        val docStore = PlanDocStore(home)
        val tools = ResearchTools(
            service = { service },
            planDocStore = { docStore },
            planDbError = { null },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = FakeLlm(llmReply, delayMs),
        )
        return Harness(home, clock, service, docStore, tools)
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

    private suspend fun call(harness: Harness, deadline: String? = null) = harness.tools.all
        .single { it.name == "research_plan" }
        .call(
            buildJsonObject {
                put("goal", "我想当 AI 应用工程师")
                if (deadline != null) put("deadline", deadline)
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
        assertTrue(harness.service.pool(planId).isEmpty(), "空壳计划还没有单元")
        assertTrue(harness.service.map(planId).weeks.isEmpty(), "空壳计划还没有承诺")
        assertTrue(result.content.contains("空壳") && result.content.contains("每天"), result.content)

        val doc = harness.docStore.read(planId)
        assertNotNull(doc, "大纲文档要写出来")
        assertTrue(doc.contains("# 目标与验收"), doc)
        assertTrue(doc.contains("基础与工具"), doc)

        val goal = harness.service.goals().singleOrNull()
        assertNotNull(goal, "要顺手建一个目标")
        assertEquals(3, goal.milestones.size)
        assertTrue(goal.plans.any { it.id == planId })
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
    fun pastDeadlineIsRejected() = runTest {
        val harness = harness(shellJson())
        val result = call(harness, deadline = "2026-10-01")
        assertTrue(result.isError)
        assertTrue(result.content.contains("已经过去了"), result.content)
        assertTrue(harness.service.plans().isEmpty())
    }
}
