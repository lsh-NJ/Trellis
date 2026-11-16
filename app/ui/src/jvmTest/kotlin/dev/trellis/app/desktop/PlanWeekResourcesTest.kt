package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.PlanResourceDraft
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 周规划拿到资源切片：节点带 resources id 时，落库后挂到 unit_resource；坏 id 丢掉。 */
class PlanWeekResourcesTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val monday = LocalDate.parse("2026-11-02")
    private val week = "2026-W45"
    private var home: File? = null
    private var docStore: PlanDocStore? = null

    private class Clock(private val date: LocalDate, override val zone: ZoneId) : TrellisClock {
        private val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        override fun nowMs() = millis
        override fun monotonicMs() = millis
        override fun today() = date
        override fun isoWeek() = "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        override fun simulatedDate(): LocalDate? = null
    }

    private class OneShotLlm(private val reply: String) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String = reply
    }

    @AfterTest
    fun tearDown() {
        docStore?.close()
        home?.deleteRecursively()
    }

    @Test
    fun nodesReferenceResourcesByIdAndBadIdsAreDropped() = runTest {
        val dir = Files.createTempDirectory("plan-week-resources").toFile()
        home = dir
        val clock = Clock(monday, zone)
        val svc = LearningPlanStore.open(File(dir, "plans/plan.db").toPath(), { clock.nowMs() }, zone)
        val store = PlanDocStore(dir)
        docStore = store
        val planId = svc.createShell(
            CommandEnvelope("cmd_" + UUID.randomUUID(), Actor.USER, null, origin = "test"),
            "资源周计划", "把网络学明白",
        ).value!!.planId
        svc.setScheduleMode(
            CommandEnvelope("cmd_" + UUID.randomUUID(), Actor.USER, planId, origin = "test"), planId, "queue",
        )
        val ids = svc.upsertResources(
            CommandEnvelope("cmd_" + UUID.randomUUID(), Actor.USER, planId, origin = "test"),
            planId,
            listOf(
                PlanResourceDraft("part", "1.1 什么是网络", "https://b23.tv/BV1?p=1", "第 1 集", 12, source = "bilibili"),
                PlanResourceDraft("article", "RAG 综述", "https://example.com/rag", "第 1–3 节", 25),
            ),
        ).value!!
        val reply = """{"focus":"网络分层","items":[""" +
            """{"title":"TCP/IP 四层模型","chapter":"第 1 章","kind":"study","minutes":30,"resources":["${ids[0]}","res_bad"]},""" +
            """{"title":"RAG 的召回与重排","chapter":"检索","kind":"study","minutes":25,"resources":["${ids[1]}"]}""" +
            """]}"""
        val tools = PlanWeekTools(
            service = { svc },
            planDocStore = { store },
            weeklyCapacity = { 60 * 7 },
            clock = clock,
            llm = OneShotLlm(reply),
            onChanged = {},
            selectedPlanId = { planId },
        )
        val result = tools.all.single { it.name == "plan_week" }.call(
            kotlinx.serialization.json.buildJsonObject {
                put("planId", planId)
                put("week", week)
            },
        )
        assertTrue(!result.isError, result.content)
        val units = svc.week(planId, week).commitment.units
        assertEquals(listOf("TCP/IP 四层模型", "RAG 的召回与重排"), units.map { it.title })
        assertEquals(listOf("1.1 什么是网络"), units[0].resources.map { it.title }, "坏 id 要丢掉")
        assertEquals(listOf("RAG 综述"), units[1].resources.map { it.title })
        assertEquals("bilibili", units[0].resources.single().kind)
    }
}
