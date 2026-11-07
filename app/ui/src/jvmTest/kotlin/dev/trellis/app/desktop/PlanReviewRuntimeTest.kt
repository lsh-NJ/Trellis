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
 * 周复盘的运行时验收：时间推进一周后，选中上一周要能直接拿到「ready」的复盘。
 *
 * 计划页的复盘卡片只读 `AppRuntime.planReview`，所以这里验证的是刷新链路：
 * `refreshPlan()`（触发节奏引擎补复盘）→ `selectPlanWeek(上一周)` → 卡片有数据。
 */
class PlanReviewRuntimeTest {

    private class Harness(
        val runtime: AppRuntime,
        val store: LearningPlanService,
        val planId: String,
    )

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-plan-review").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.plan.review",
    )

    private fun weekOf(date: LocalDate): String =
        "%04d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    /**
     * 起运行时 → 把「今天」钉住 → 在同一个 plan.db 里建一个计划并接受草稿。
     *
     * 计划库的时钟跟着 `runtime.clock` 走，推进模拟日期时两边看到的是同一个「今天」。
     */
    private suspend fun harness(home: File, today: LocalDate): Harness {
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
            "复盘测试",
            "周复盘验收",
            weeklyCapacityMinutes = 60 * 7,
        ).unwrap()
        val week = weekOf(today)
        val nextWeek = weekOf(today.plusWeeks(1))
        val draft = store.saveDraft(
            env(created.planId),
            created.planId,
            null,
            listOf(
                UnitDraft("第 1 章", "第 1 节", 20),
                UnitDraft("第 1 章", "第 2 节", 20),
                UnitDraft("第 2 章", "第 3 节", 20),
            ),
            listOf(
                CommitmentDraft(week, listOf(0, 1)),
                CommitmentDraft(nextWeek, listOf(2)),
            ),
        ).unwrap()
        store.acceptDraft(env(created.planId), draft.versionId, week).unwrap()
        return Harness(runtime, store, created.planId)
    }

    @Test
    fun aFinishedWeekShowsItsReviewAfterTheClockMovesOn() = runTest {
        val home = tempHome()
        val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val harness = harness(home, monday)
        try {
            harness.runtime.refreshPlan()
            assertNull(harness.runtime.planReview, "当周还没结束，不该显示复盘")
            assertNull(harness.runtime.planPendingReviewWeek, "不欠复盘时不该有周号")

            // 把应用内时间推到下一周：节奏引擎会结算上一周并补一份复盘。
            harness.runtime.applySimulatedToday(monday.plusWeeks(1))
            harness.runtime.refreshPlan()
            assertNull(harness.runtime.planPendingReviewWeek, "引擎自动补过复盘后就不该再欠")

            // 选回上一周：卡片应拿到 ready 的复盘正文。
            harness.runtime.selectPlanWeek(weekOf(monday))
            val review = assertNotNull(harness.runtime.planReview, "选中上一周应拿到复盘")
            assertEquals("ready", review.state)
            assertTrue(review.narrative.isNotBlank(), "复盘要有正文")
        } finally {
            harness.runtime.shutdown()
        }
    }
}
