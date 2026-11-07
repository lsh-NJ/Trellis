package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 计划文档三只工具（read / write / sync）：写入可覆盖但要确认，同步只追加本周条目、且同样要确认。
 */
class PlanDocToolsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val today = LocalDate.of(2026, 11, 5) // 周四，ISO 2026-W45

    private class FixedClock(private val day: LocalDate, override val zone: ZoneId) : TrellisClock {
        override fun nowMs(): Long = day.atStartOfDay(zone).toInstant().toEpochMilli()
        override fun monotonicMs(): Long = 0L
        override fun today(): LocalDate = day
        override fun isoWeek(): String = "%04d-W%02d".format(
            day.get(java.time.temporal.IsoFields.WEEK_BASED_YEAR),
            day.get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        override fun simulatedDate(): LocalDate? = day
    }

    private class Harness(
        val service: LearningPlanService,
        val planId: String,
        val store: PlanDocStore,
        val tools: PlanDocTools,
    )

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-doc").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.doc",
    )

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    private suspend fun harness(): Harness {
        val home = tempHome()
        val service = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { today.atStartOfDay(zone).toInstant().toEpochMilli() },
            zone,
        )
        val created = service.createProcess(env(), "文档测试", "由面到点验收", weeklyCapacityMinutes = 60 * 7).unwrap()
        val draft = service.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(UnitDraft(chapter = "第一周", title = "已经排进承诺的单元", estimateMinutes = 20)),
            listOf(CommitmentDraft("2026-W45", listOf(0))),
        ).unwrap()
        service.acceptDraft(env(created.planId), draft.versionId, "2026-W45").unwrap()
        val store = PlanDocStore(home)
        val tools = PlanDocTools(
            service = { service },
            docStore = { store },
            clock = FixedClock(today, zone),
        )
        return Harness(service, created.planId, store, tools)
    }

    private suspend fun PlanDocTools.tool(name: String, args: JsonObject) : dev.trellis.ai.tool.ToolResult =
        all.first { it.name == name }.call(args)

    private val doc = """
        # 目标与验收
        - 学会 Linux 常用命令
        - 验收：能独立完成一次文件整理

        ## 2026-W45
        - 文件与目录（约 20 分钟）
        - 权限与用户（约 25 分钟）

        ## 待安排
        - 之后再看的内容
    """.trimIndent()

    @Test
    fun writeThenReadReturnsTheDocument() = runTest {
        val h = harness()
        val write = h.tools.tool("write_plan_doc", buildJsonObject { put("markdown", doc) })
        assertFalse(write.isError, write.content)
        assertEquals(doc.trim(), h.store.read(h.planId)?.trim())

        val read = h.tools.tool("read_plan_doc", buildJsonObject { })
        assertFalse(read.isError, read.content)
        assertTrue(read.content.contains("文件与目录"), read.content)
        assertTrue(read.content.contains("2026-W45"), read.content)
    }

    @Test
    fun overwritingNeedsConfirmation() = runTest {
        val h = harness()
        h.tools.tool("write_plan_doc", buildJsonObject { put("markdown", doc) })

        val rejected = h.tools.tool("write_plan_doc", buildJsonObject { put("markdown", doc + "\n- 追加一行") })
        assertTrue(rejected.isError, "非空文档覆盖必须先确认")
        assertTrue(rejected.content.contains("confirmed"), rejected.content)
        assertEquals(doc.trim(), h.store.read(h.planId)?.trim(), "拒绝时不能落盘")

        val confirmed = h.tools.tool(
            "write_plan_doc",
            buildJsonObject {
                put("markdown", doc + "\n- 追加一行")
                put("confirmed", true)
            },
        )
        assertFalse(confirmed.isError, confirmed.content)
        assertTrue(h.store.read(h.planId)!!.contains("追加一行"))
    }

    @Test
    fun syncRequiresConfirmation() = runTest {
        val h = harness()
        h.tools.tool("write_plan_doc", buildJsonObject { put("markdown", doc) })

        val rejected = h.tools.tool("sync_plan_week", buildJsonObject { put("week", "2026-W45") })
        assertTrue(rejected.isError, "appendUnits 不可撤销，必须先确认")
        assertTrue(h.service.pool(h.planId).isEmpty(), "拒绝时不能落库")
    }

    @Test
    fun syncAppendsTheWeekItemsIntoThePool() = runTest {
        val h = harness()
        h.tools.tool("write_plan_doc", buildJsonObject { put("markdown", doc) })

        val result = h.tools.tool(
            "sync_plan_week",
            buildJsonObject {
                put("week", "2026-W45")
                put("confirmed", true)
            },
        )
        assertFalse(result.isError, result.content)
        assertEquals(
            setOf("文件与目录", "权限与用户"),
            h.service.pool(h.planId).map { it.title }.toSet(),
        )
        assertTrue(result.content.contains("待安排"), result.content)
    }

    @Test
    fun syncWithoutThatWeekReportsAvailableWeeks() = runTest {
        val h = harness()
        h.tools.tool("write_plan_doc", buildJsonObject { put("markdown", doc) })

        val result = h.tools.tool(
            "sync_plan_week",
            buildJsonObject {
                put("week", "2026-W46")
                put("confirmed", true)
            },
        )
        assertTrue(result.isError, result.content)
        assertTrue(result.content.contains("2026-W45"), result.content)
        assertTrue(h.service.pool(h.planId).isEmpty())
    }
}
