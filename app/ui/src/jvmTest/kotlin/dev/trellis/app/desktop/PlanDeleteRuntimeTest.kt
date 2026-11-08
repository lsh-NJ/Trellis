package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.LearningPlanService
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 「删除已放弃的计划」的运行时验收。
 *
 * 计划页只读 `AppRuntime.planAll`，所以这里验证的是：放弃 → 删除后列表真的干净、
 * 焦点不会悬在一个已经消失的计划上；而进行中的计划走同一个入口会被后端拒绝。
 */
class PlanDeleteRuntimeTest {

    private class Harness(
        val runtime: AppRuntime,
        val store: LearningPlanService,
        val planId: String,
    )

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-delete").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.delete",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    private suspend fun harness(home: File, today: LocalDate, title: String): Harness {
        val zone = ZoneId.systemDefault()
        val runtime = AppRuntime.create(home)
        runtime.applySimulatedToday(today)
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { runtime.clock.nowMs() },
            zone,
        )
        val created = store.createProcess(
            env(),
            title,
            "删除验收",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val week = weekOf(today)
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(UnitDraft("第 1 章", "第 1 节", 20)),
            listOf(CommitmentDraft(week, listOf(0))),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()
        return Harness(runtime, store, created.planId)
    }

    @Test
    fun anAbandonedPlanDisappearsFromTheListAfterDeleting() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val harness = harness(home, monday, "放弃后删除的计划")
        try {
            harness.runtime.refreshPlan()
            assertNotNull(harness.runtime.planAll.firstOrNull { it.id == harness.planId })

            harness.runtime.abandonPlan(harness.planId)
            val abandoned = harness.runtime.planAll.firstOrNull { it.id == harness.planId }
            assertNotNull(abandoned, "放弃后左栏还应该能看到（灰色「已放弃」）")
            assertEquals("abandoned", abandoned.status)

            harness.runtime.deleteAbandonedPlan(harness.planId)
            assertTrue(
                harness.runtime.planAll.none { it.id == harness.planId },
                "删除后计划列表里不应再有它，planAll=${harness.runtime.planAll}",
            )
            assertNull(harness.runtime.planFocusId, "删掉当前计划后焦点要清空")
            assertTrue(
                harness.store.plans().none { it.id == harness.planId },
                "后端读侧也要过滤 deleted_at，重开程序不会再出现",
            )
        } finally {
            harness.runtime.shutdown()
        }
    }

    @Test
    fun anActivePlanCannotBeDeletedByTheSameEntry() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val harness = harness(home, monday, "还没放弃的计划")
        try {
            harness.runtime.refreshPlan()
            harness.runtime.deleteAbandonedPlan(harness.planId)

            assertTrue(
                harness.runtime.planAll.any { it.id == harness.planId },
                "进行中的计划不许删，列表里必须还在",
            )
            val notice = harness.runtime.planNotice
            assertNotNull(notice, "拒绝时要在页面上给出原因")
            assertTrue(notice.contains("只有已放弃"), "提示要说清只能删已放弃的计划，实际=$notice")
        } finally {
            harness.runtime.shutdown()
        }
    }
}
