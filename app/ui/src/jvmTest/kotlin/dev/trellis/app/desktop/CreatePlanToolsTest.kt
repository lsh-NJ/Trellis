package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.LearningPlanService
import java.util.UUID
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * create_plan 四步流程：每步都停在等确认，前两步不写节点；
 * 第三步才追加单元，第四步只定钟点。
 */
class CreatePlanToolsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val wednesday: LocalDate = LocalDate.parse("2026-11-04")
    private val thisWeek: String = "2026-W45"

    private class FixedClock(private val date: LocalDate, private val minuteOfDay: Int, override val zone: ZoneId) : TrellisClock {
        private val millis: Long = date.atStartOfDay(zone).toInstant().toEpochMilli() + minuteOfDay * 60_000L

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
        override suspend fun complete(messages: List<LlmMessage>): String {
            val reply = replies.getOrElse(calls) { replies.last() }
            calls++
            return reply
        }
    }

    private class FakeResearch : ResearchGateway {
        override suspend fun gather(goal: String): ResearchSources = ResearchSources(
            hits = listOf(
                ResearchHit(
                    title = "学习路线",
                    url = "https://example.com/a",
                    siteName = "example.com",
                    summary = "先打基础，再做项目",
                    authority = 3,
                ),
            ),
            pages = listOf(ResearchPage("https://example.com/a", "学习路线", "# 路线")),
        )
    }

    private class Harness(
        val home: File,
        val service: LearningPlanService,
        val docStore: PlanDocStore,
        val workflow: PlanWorkflowStore,
        val tool: dev.trellis.ai.tool.AgentTool,
        val llm: FakeLlm,
    )

    private fun shellJson(): String = """{"title":"数据应用起步","dailyMinutes":60,"stages":""" +
        """[{"name":"基础","weeks":2,"focus":"命令行与 Python"},{"name":"项目","weeks":2,"focus":"做一个服务"}]}"""

    private fun weekJson(): String = """{"focus":"命令行起步","items":[""" +
        """{"title":"手写一个命令行工具","chapter":"基础","kind":"practice","minutes":30},""" +
        """{"title":"读 Python 官方教程第 1 章","chapter":"基础","kind":"study","minutes":30},""" +
        """{"title":"复盘本周","chapter":"复盘","kind":"review","minutes":15}]}"""

    private fun tutorJson(): String = """{"prerequisites":[]}"""

    private var harnessRef: Harness? = null

    private fun harness(): Harness {
        val home = Files.createTempDirectory("create-plan-tools").toFile()
        val clock = FixedClock(wednesday, 10 * 60 + 20, zone)
        val service = LearningPlanStore.open(File(home, "plans/plan.db").toPath(), { clock.nowMs() }, zone)
        val docStore = PlanDocStore(home)
        val workflow = PlanWorkflowStore(home)
        val llm = FakeLlm(listOf(shellJson(), weekJson(), tutorJson()))
        val research = ResearchTools(
            service = { service },
            planDocStore = { docStore },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = llm,
            research = FakeResearch(),
        )
        val weekTools = PlanWeekTools(
            service = { service },
            planDocStore = { docStore },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = llm,
            onChanged = {},
            selectedPlanId = { null },
        )
        val weekTool = weekTools.all.single { it.name == "plan_week" }
        val create = CreatePlanTools(
            service = { service },
            workflow = { workflow },
            research = { research },
            clock = clock,
            dispatch = { name, args ->
                if (name == "plan_week") weekTool.call(args)
                else dev.trellis.ai.tool.ToolResult("缺少 " + name, isError = true)
            },
            onChanged = {},
        )
        val h = Harness(home, service, docStore, workflow, create.all.single(), llm)
        harnessRef = h
        return h
    }

    @AfterTest
    fun tearDown() {
        harnessRef?.run {
            docStore.close()
            workflow.close()
            service.close()
            home.deleteRecursively()
        }
        harnessRef = null
    }

    private suspend fun call(
        h: Harness,
        action: String,
        confirmed: Boolean? = null,
        jobId: String? = null,
        extra: (kotlinx.serialization.json.JsonObjectBuilder) -> Unit = {},
    ): dev.trellis.ai.tool.ToolResult = h.tool.call(
        buildJsonObject {
            put("action", action)
            if (action == "start") put("goal", "我想做数据应用")
            confirmed?.let { put("confirmed", it) }
            jobId?.let { put("jobId", it) }
            extra(this)
        },
    )

    private fun env(planId: String): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_test_" + UUID.randomUUID(),
        actor = Actor.USER,
        planId = planId,
        origin = "test",
    )

    private fun jobIdOf(result: dev.trellis.ai.tool.ToolResult): String {
        val match = Regex("jobId=([A-Za-z0-9_]+)").find(result.content)
        return assertNotNull(match, "回执里要带 jobId：" + result.content).groupValues[1]
    }

    @Test
    fun startOnlyResearchesAndWritesNothing() = runTest {
        val h = harness()
        val result = call(h, "start")
        assertFalse(result.isError, result.content)
        assertTrue(result.content.contains("还没写进计划库"), result.content)
        assertTrue(h.service.plans().isEmpty(), "第一步不该建计划")
        assertEquals(1, h.llm.calls, "第一步只调一次模型")
        val job = assertNotNull(h.workflow.latestOpen("create_plan"))
        assertEquals("awaiting_user", job.state)
        assertEquals("running", job.step("research")?.state)
        assertTrue(job.payload.contains("raw"), "调研结果要存进 job，第二步才不用重跑模型")
        assertTrue(h.docStore.planIds().isEmpty(), "第一步不该写文档")
    }

    @Test
    fun advanceRequiresExplicitConfirmation() = runTest {
        val h = harness()
        val started = call(h, "start")
        val jobId = jobIdOf(started)
        val refused = call(h, "advance", jobId = jobId)
        assertTrue(refused.isError, refused.content)
        assertTrue(h.service.plans().isEmpty(), "没确认就不能建计划")
    }

    @Test
    fun eachConfirmedAdvanceRunsExactlyOneStep() = runTest {
        val h = harness()
        val started = call(h, "start")
        val jobId = jobIdOf(started)

        val planStep = call(h, "advance", confirmed = true, jobId = jobId)
        assertFalse(planStep.isError, planStep.content)
        val planId = assertNotNull(h.service.activePlanId(), "第二步要建出计划")
        assertTrue(h.service.week(planId, thisWeek).commitment.units.isEmpty(), "第二步不排节点")
        assertNotNull(h.docStore.read(planId), "第二步要写总文档")

        val weekStep = call(h, "advance", confirmed = true, jobId = jobId)
        assertFalse(weekStep.isError, weekStep.content)
        assertTrue(
            h.service.week(planId, thisWeek).commitment.units.isNotEmpty(),
            "第三步要排本周：" + weekStep.content,
        )
        assertNotNull(h.docStore.readWeek(planId, thisWeek), "第三步要写周文档")

        // 第三步结束：节点进了本周队列，但没有铺到具体天。
        assertEquals("done", h.workflow.load(jobId)?.state)
        val units = h.service.week(planId, thisWeek).commitment.units
        assertTrue(units.isNotEmpty(), "本周队列要有节点")
        assertTrue(h.service.week(planId, thisWeek).days.all { it.items.isEmpty() }, "queue 模式不排到天")

        // 勾掉一个：它应该出现在今天完成记录里。
        val first = units.first()
        val recorded = h.service.completeUnit(env(planId), planId, first.id)
        assertTrue(recorded.ok, recorded.error?.message)
        assertTrue(
            h.service.today(planId, wednesday.toString()).completed.any { it.title == first.title },
            "完成后要记到当天",
        )
    }

    @Test
    fun statusShowsTheCurrentStep() = runTest {
        val h = harness()
        val started = call(h, "start")
        val jobId = jobIdOf(started)
        val status = call(h, "status", jobId = jobId)
        assertFalse(status.isError, status.content)
        assertTrue(status.content.contains("等确认"), status.content)
        assertTrue(status.content.contains("调研目标与资料"), status.content)
    }

    @Test
    fun cancelStopsTheFlowWithoutTouchingThePlanDatabase() = runTest {
        val h = harness()
        val jobId = jobIdOf(call(h, "start"))
        val cancelled = call(h, "cancel", jobId = jobId)
        assertFalse(cancelled.isError, cancelled.content)
        assertEquals("cancelled", h.workflow.load(jobId)?.state)
        assertNull(h.workflow.latestOpen("create_plan"))
        assertTrue(h.service.plans().isEmpty())
    }
}
