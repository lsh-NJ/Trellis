package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.tool.AgentTool
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.StageDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.feature.learning.PlanDocStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 「已完成的计划对 AGENT 只读」验收：完成态（所有阶段都有单元且都做完 + 待安排池空，见
 * [isPlanComplete]）一旦成立，三组写工具都必须原话拒绝，且**不调模型、不落库、不改数据**；
 * 只读的 get_learning_plan / read_plan_doc 不受影响。
 *
 * 计划用内存版 `LearningPlanStore` 真跑后端命令（stage/commitment/occurrence），
 * 周文档用临时目录的 `PlanDocStore`——被拒时文件里不该出现周文档。
 */
class PlanCompletedGuardTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-11-04 是周三，所在周 = 2026-W45（和 PlanWeekToolsTest 同一口径）。 */
    private val today: LocalDate = LocalDate.parse("2026-11-04")
    private val thisWeek: String = "2026-W45"

    private class FixedClock(private val date: LocalDate, override val zone: ZoneId) : TrellisClock {
        override fun nowMs(): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
        override fun monotonicMs(): Long = 0L
        override fun today(): LocalDate = date
        override fun isoWeek(): String = "%04d-W%02d".format(
            date.get(IsoFields.WEEK_BASED_YEAR),
            date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
        )
        override fun simulatedDate(): LocalDate? = null
    }

    private class FakeLlm(private val reply: String) : LlmClient {
        var calls = 0

        override suspend fun complete(messages: List<LlmMessage>): String {
            calls++
            return reply
        }
    }

    private class Harness(
        val home: File,
        val service: LearningPlanService,
        val planId: String,
        val docStore: PlanDocStore,
        val clock: TrellisClock,
        val llm: FakeLlm,
    )

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_test_" + UUID.randomUUID(),
        actor = Actor.USER,
        planId = planId,
        origin = "test",
    )

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /**
     * 造一份**已完成**的计划：单个阶段覆盖本周、唯一单元已承诺并铺天、然后把那几条 occurrence 全部勾掉。
     * 这样 `stages()` = 1/1，`pool()` 为空 —— 正是 [isPlanComplete] 的完成态。
     */
    private suspend fun completedHarness(): Harness {
        val home = Files.createTempDirectory("plan-completed-guard").toFile().apply { deleteOnExit() }
        val clock = FixedClock(today, zone)
        val svc = LearningPlanStore.inMemory({ clock.nowMs() }, zone)
        val created = svc.createProcess(
            env(),
            "操作系统速通",
            "把这门课学完",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val planId = created.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(UnitDraft(chapter = "第 1 章", title = "唯一节点", estimateMinutes = 20)),
            listOf(CommitmentDraft(thisWeek, listOf(0))),
        ).unwrap()
        svc.acceptDraft(env(planId), draft.versionId, thisWeek).unwrap()
        svc.scheduleWeek(env(planId), thisWeek).unwrap()
        // 阶段要等承诺生效之后再写，阶段进度才从承诺里现算得出。
        svc.setStages(env(planId), planId, listOf(StageDraft("全程", thisWeek, thisWeek))).unwrap()

        val occurrences = svc.week(planId, thisWeek).days.flatMap { it.items }
        assertTrue(occurrences.isNotEmpty(), "铺天之后本周应该有 occurrence")
        occurrences.forEach { svc.completeOccurrence(env(planId), it.occurrenceId).unwrap() }

        return Harness(home, svc, planId, PlanDocStore(home), clock, FakeLlm("""{"focus":"进程","items":[]}"""))
    }

    private fun assertPlanIsComplete(h: Harness) {
        val stages = h.service.stages(h.planId)
        assertTrue(stages.isNotEmpty(), "阶段为空不算完成")
        assertTrue(stages.all { it.total > 0 && it.done >= it.total }, "阶段进度要是全完成：" + stages)
        assertTrue(h.service.pool(h.planId).isEmpty(), "待安排池要为空")
        assertTrue(isPlanComplete(stages, poolEmpty = true), "isPlanComplete 要判定为完成（与工具守卫同一口径）")
    }

    private fun planWeekTool(h: Harness): AgentTool =
        PlanWeekTools({ h.service }, { h.docStore }, { 60 * 7 }, h.clock, h.llm)
            .all.single { it.name == "plan_week" }

    private fun planDocTool(h: Harness, name: String): AgentTool =
        PlanDocTools(service = { h.service }, docStore = { h.docStore }, clock = h.clock)
            .all.single { it.name == name }

    private fun planAdminTool(h: Harness, name: String): AgentTool =
        PlanAdminTools({ h.service }, {}).all.single { it.name == name }

    private val completedHint = "已完成，不再追加"

    @Test
    fun planWeekRefusesACompletedPlanBeforeCallingTheModelOrWriting() = runTest {
        val h = completedHarness()
        assertPlanIsComplete(h)
        val occurrencesBefore = h.service.week(h.planId, thisWeek).days.sumOf { it.items.size }

        val result = planWeekTool(h).call(buildJsonObject { put("week", thisWeek) })

        assertTrue(result.isError, result.content)
        assertTrue(result.content.contains("计划「操作系统速通」$completedHint"), result.content)
        assertTrue(result.content.contains("新开一份"), "要告诉模型拓展=新开计划：" + result.content)
        assertTrue(result.content.contains("create_learning_plan / research_plan"), result.content)
        assertEquals(0, h.llm.calls, "已完成计划不该叫模型")
        assertNull(h.docStore.readWeek(h.planId, thisWeek), "被拒时不该写周文档")
        assertEquals(occurrencesBefore, h.service.week(h.planId, thisWeek).days.sumOf { it.items.size }, "不该铺新节点")
        assertTrue(h.service.pool(h.planId).isEmpty(), "不该往待安排池里追加单元")
    }

    @Test
    fun planDocWritesAreRefusedButTheDocStaysReadable() = runTest {
        val h = completedHarness()
        val original = "# 目标与验收\n- 学完操作系统\n\n## 待安排\n- （已经全部完成）\n"
        h.docStore.save(h.planId, original)

        val write = planDocTool(h, "write_plan_doc").call(
            buildJsonObject {
                put("markdown", "# 目标与验收\n- 新目标")
                put("confirmed", true)
            },
        )
        assertTrue(write.isError, write.content)
        assertTrue(write.content.contains(completedHint), write.content)
        assertEquals(original, h.docStore.read(h.planId), "被拒时不能覆盖计划文档")

        val sync = planDocTool(h, "sync_plan_week").call(
            buildJsonObject {
                put("week", thisWeek)
                put("confirmed", true)
            },
        )
        assertTrue(sync.isError, sync.content)
        assertTrue(sync.content.contains(completedHint), sync.content)
        assertTrue(h.service.pool(h.planId).isEmpty(), "被拒时不能往待安排池追加单元")

        // 读不受影响：完成计划的文档仍然要能看。
        val read = planDocTool(h, "read_plan_doc").call(buildJsonObject {})
        assertFalse(read.isError, read.content)
        assertTrue(read.content.contains("学完操作系统"), "read_plan_doc 要还能读到原文档：" + read.content)
    }

    @Test
    fun planAdminWritesAreRefusedWhileGetLearningPlanStillReads() = runTest {
        val h = completedHarness()
        val dailyBefore = h.service.planState(h.planId).dailyMinutes
        assertEquals(60, dailyBefore, "建计划时写的每天投入是 60")

        val pace = planAdminTool(h, "set_plan_pace").call(
            buildJsonObject {
                put("dailyMinutes", 120)
                put("confirmed", true)
            },
        )
        assertTrue(pace.isError, pace.content)
        assertTrue(pace.content.contains(completedHint), pace.content)
        assertEquals(dailyBefore, h.service.planState(h.planId).dailyMinutes, "被拒时不能改每天投入")

        val next = planAdminTool(h, "plan_next_week").call(buildJsonObject {})
        assertTrue(next.isError, next.content)
        assertTrue(next.content.contains(completedHint), next.content)

        val occurrenceId = h.service.week(h.planId, thisWeek).days.flatMap { it.items }.first().occurrenceId
        val complete = planAdminTool(h, "complete_plan_node").call(
            buildJsonObject {
                put("occurrenceId", occurrenceId)
                put("confirmed", true)
            },
        )
        assertTrue(complete.isError, complete.content)
        assertTrue(complete.content.contains(completedHint), complete.content)

        val move = planAdminTool(h, "move_plan_node").call(
            buildJsonObject {
                put("occurrenceId", occurrenceId)
                put("toDay", "2026-11-10")
                put("confirmed", true)
            },
        )
        assertTrue(move.isError, move.content)
        assertTrue(move.content.contains(completedHint), move.content)

        // 只读工具放行：状态、节点、池子、复盘都还能读到（不会被完成态守卫挡住）。
        val get = planAdminTool(h, "get_learning_plan").call(buildJsonObject {})
        assertFalse(get.isError, get.content)
        assertTrue(get.content.contains("操作系统速通"), get.content)
        assertTrue(get.content.contains("待安排池：0 个单元"), get.content)
        assertFalse(get.content.contains(completedHint), "get_learning_plan 只读，不该出现拒绝文案")

        val review = planAdminTool(h, "review_last_week").call(buildJsonObject {})
        assertFalse(review.content.contains(completedHint), "review_last_week 只读放行：" + review.content)
    }

    @Test
    fun aPlanWithUnitsLeftInThePoolIsNotTreatedAsComplete() = runTest {
        val h = completedHarness()
        // 所有已承诺单元都做完了，但还有没被任何承诺收进去的单元：池子非空 → 不算完成。
        h.service.appendUnits(
            env(h.planId),
            h.planId,
            listOf(UnitDraft(chapter = "拓展", title = "还没安排的拓展内容", estimateMinutes = 20)),
        ).unwrap()
        assertTrue(h.service.stages(h.planId).all { it.total > 0 && it.done >= it.total }, "阶段进度仍是全完成")
        assertTrue(h.service.pool(h.planId).isNotEmpty(), "池里还有一个单元")

        val write = planDocTool(h, "write_plan_doc").call(
            buildJsonObject { put("markdown", "# 目标与验收\n- 补一条文档") },
        )
        assertFalse(write.isError, "待安排池非空就不算完成，写文档不该被拦：" + write.content)
    }
}
