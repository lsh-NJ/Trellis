package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 「周次写在过去」的修复：旧计划搬成一份从本周开始的新计划。
 *
 * 这里不起界面，只是把后端与运行时串起来：先造一份 2025 年的计划，
 * 再看 `planStartsInPast` 是否为真、`repairPastPlan()` 之后第一周是否变成本周。
 */
class PlanRepairTest {

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-repair").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.repair",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    @Test
    fun repairPastPlanMovesThePlanToThisWeek() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        // 造一份「去年第一周」的旧计划：两周、四个单元。
        val store = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            System::currentTimeMillis,
            ZoneId.systemDefault(),
        )
        val created = store.createProcess(env(), "旧计划", "去年写错的计划").unwrap()
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(
                UnitDraft("第 1 章", "什么是操作系统", 25),
                UnitDraft("第 1 章", "中断与系统调用", 30),
                UnitDraft("第 2 章", "进程概念", 20),
                UnitDraft("第 2 章", "线程调度", 35),
            ),
            listOf(CommitmentDraft("2025-W01", listOf(0, 1)), CommitmentDraft("2025-W02", listOf(2, 3))),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, "2025-W01").unwrap()

        val runtime = AppRuntime.create(home)
        runtime.refreshPlan()

        assertEquals("2025-W01", runtime.planWeekCards.first().week)
        assertTrue(runtime.planStartsInPast, "旧计划的周次落在过去，应当被标出来")

        runtime.repairPastPlan()

        assertEquals(null, runtime.planMessage, "重排不该有错误提示")
        assertEquals(2, runtime.planWeekCards.size, "周数应当搬过来")
        assertEquals(weekOf(monday), runtime.planWeekCards.first().week, "第一周应当挪到本周")
        assertFalse(runtime.planStartsInPast, "重排之后不该再显示「过去」")
        assertEquals(4, runtime.planWeekCards.sumOf { it.unitCount }, "四个单元一个都不能丢")

        runtime.shutdown()
    }
}
