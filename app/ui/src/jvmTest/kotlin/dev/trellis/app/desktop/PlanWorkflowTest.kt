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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 计划工作流的三段：调研+总计划 → 计划本体+周文档 → 本周节点。
 *
 * 这里钉住三件事：
 * 1. 第一段只产出草稿文档，不写计划库；
 * 2. 第二段才建计划本体 + 周文档，而且**不建节点**；
 * 3. 第三段才把周文档的条目变成本周队列里的节点。
 *
 * 每段结束都必须停在 awaiting_user —— 「一步出结果」就是从这漏出去的。
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
        override suspend fun complete(messages: List<LlmMessage>): String {
            val reply = replies.getOrElse(calls) { replies.last() }
            calls++
            return reply
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
        val docLinks: MutableList<String>,
    )

    private fun masterJson(): String =
        """{"title":"操作系统备考","dailyMinutes":60,"stages":[""" +
            """{"name":"基础","weeks":1,"focus":"中断与系统调用"},{"name":"进程与内存","weeks":2,"focus":"调度与分页","resources":[]}""" +
            """]}"""

    private fun weekJson(): String =
        """{"items":[{"title":"中断与系统调用","minutes":30,"kind":"study"},""" +
            """{"title":"进程调度算法","minutes":25,"kind":"study"},""" +
            """{"title":"页面置换计算","minutes":40,"kind":"practice"}]}"""

    private var harnessRef: Harness? = null

    private fun harness(): Harness {
        val home = Files.createTempDirectory("plan-workflow").toFile()
        val clock = FixedClock(wednesday, zone)
        val service = LearningPlanStore.open(File(home, "plans/plan.db").toPath(), { clock.nowMs() }, zone)
        val docStore = PlanDocStore(home)
        val workflow = PlanWorkflowStore(home)
        val llm = FakeLlm(listOf(masterJson(), weekJson()))
        val research = ResearchTools(
            service = { service },
            planDocStore = { docStore },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = llm,
            research = null,
        )
        val checklists = mutableListOf<String>()
        val docLinks = mutableListOf<String>()
        val flow = PlanWorkflow(
            service = { service },
            workflow = { workflow },
            docStore = { docStore },
            research = { research },
            clock = clock,
            llm = llm,
            gateway = { NoopResearch() },
            onChanged = {},
            onChecklist = { phase, state -> checklists += phase.toString() + ":" + state },
            onDocLink = { id, _ -> docLinks += id },
        )
        val h = Harness(home, service, docStore, workflow, flow.all.single(), llm, checklists, docLinks)
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

    private suspend fun start(h: Harness): String {
        val result = h.tool.call(
            buildJsonObject {
                put("action", "start")
                put("goal", "两个月后期末考试，复习操作系统")
            },
        )
        assertTrue(!result.isError, result.content)
        return h.workflow.latestOpen("plan_flow")?.id ?: error("没有建出流程")
    }

    private suspend fun confirm(h: Harness) {
        val result = h.tool.call(
            buildJsonObject {
                put("action", "confirm")
                put("confirmed", true)
            },
        )
        assertTrue(!result.isError, result.content)
    }

    @Test
    fun researchPhaseOnlyWritesADraftDocAndWaits() = runTest {
        val h = harness()
        val jobId = start(h)

        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("awaiting_user", job.state)
        assertEquals("running", job.steps.first { it.step == "research" }.state)
        // 第一段只出草稿：计划库还没有计划，文档在草稿目录。
        assertTrue(h.service.plans().isEmpty())
        assertTrue(h.docStore.draftIds().isNotEmpty())
        assertTrue(h.docLinks.single().startsWith("draft_"))
        assertTrue(h.checklists.contains("0:awaiting"), h.checklists.toString())
    }

    @Test
    fun planPhaseCreatesPlanAndWeekDocButNoNodes() = runTest {
        val h = harness()
        val jobId = start(h)
        confirm(h)

        // 第二段：计划本体 + 周文档，节点还没进计划库。
        val plans = h.service.plans()
        assertEquals(1, plans.size, plans.toString())
        val planId = plans.first().id
        assertEquals("awaiting_user", assertNotNull(h.workflow.load(jobId)).state)
        val weekText = h.docStore.readWeek(planId, thisWeek).orEmpty()
        val weekDoc = h.docStore.parse(weekText).weeks[thisWeek].orEmpty()
        assertEquals(3, weekDoc.size, weekDoc.joinToString { it.text })

        val week = assertNotNull(h.service.week(planId, thisWeek))
        assertTrue(week.commitment?.units.isNullOrEmpty(), "第二段不该建节点")
    }

    @Test
    fun weekPhaseTurnsWeekDocIntoQueuedNodes() = runTest {
        val h = harness()
        val jobId = start(h)
        confirm(h)
        confirm(h)

        val planId = h.service.plans().first().id
        val job = assertNotNull(h.workflow.load(jobId))
        assertEquals("done", job.state)
        val week = assertNotNull(h.service.week(planId, thisWeek))
        val units = week.commitment?.units.orEmpty()
        assertEquals(3, units.size, units.joinToString { it.title })
        assertTrue(units.any { it.title.contains("页面置换") }, units.joinToString { it.title })
        assertTrue(h.checklists.contains("2:done"), h.checklists.toString())
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
}
