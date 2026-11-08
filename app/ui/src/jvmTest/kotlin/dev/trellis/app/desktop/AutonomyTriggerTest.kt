package dev.trellis.app.desktop

import dev.trellis.domain.plan.StageView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「什么时候值得让管家主动开口」的纯函数。
 *
 * 判定故意保守：只有「某个阶段刚做完」或「整份计划刚做完」才返回事件，
 * 已经完成过的状态、空壳阶段、total=0 都不重复触发。
 */
class AutonomyTriggerTest {

    private fun stage(
        id: String,
        done: Int,
        total: Int,
        name: String = id,
    ) = StageView(
        id = id,
        name = name,
        weekStart = "2026-01-05",
        weekEnd = "2026-01-11",
        done = done,
        total = total,
    )

    @Test
    fun aStageThatJustFinishedIsReported() {
        val before = listOf(stage("s1", 1, 3), stage("s2", 0, 2))
        val after = listOf(stage("s1", 3, 3), stage("s2", 0, 2))

        val event = detectAutonomyEvent(before, after, poolEmpty = false)

        assertEquals(AutonomyEventKind.StageDone, event?.kind)
        assertEquals("s1", event?.stage?.id)
        assertEquals(false, event?.planDone)
    }

    @Test
    fun theWholePlanFinishingWinsOverASingleStage() {
        val before = listOf(stage("s1", 0, 2), stage("s2", 1, 2))
        val after = listOf(stage("s1", 2, 2), stage("s2", 2, 2))

        val event = detectAutonomyEvent(before, after, poolEmpty = true)

        assertEquals(AutonomyEventKind.PlanDone, event?.kind)
        assertTrue(event!!.planDone)
        // 顺手带上第一个刚完成的阶段，方便运行时说清「做完的是哪一段」
        assertEquals("s1", event?.stage?.id)
    }

    @Test
    fun aPlanThatWasAlreadyFinishedIsStillReportedOnceWithNoStage() {
        val before = listOf(stage("s1", 2, 2))
        val after = listOf(stage("s1", 2, 2))

        val event = detectAutonomyEvent(before, after, poolEmpty = true)

        assertEquals(AutonomyEventKind.PlanDone, event?.kind)
        assertTrue(event!!.planDone)
        assertNull(event?.stage)
    }

    @Test
    fun aFinishedPlanWithLeftoversInThePoolIsOnlyAStageEvent() {
        val before = listOf(stage("s1", 1, 2))
        val after = listOf(stage("s1", 2, 2))

        val event = detectAutonomyEvent(before, after, poolEmpty = false)

        assertEquals(AutonomyEventKind.StageDone, event?.kind)
        assertEquals("s1", event?.stage?.id)
        assertEquals(false, event?.planDone)
    }

    @Test
    fun nothingChangedMeansNoEvent() {
        val before = listOf(stage("s1", 1, 2))
        val after = listOf(stage("s1", 1, 2))

        assertNull(detectAutonomyEvent(before, after, poolEmpty = false))
    }

    @Test
    fun anEmptyAfterListIsNeverAnEvent() {
        assertNull(detectAutonomyEvent(listOf(stage("s1", 1, 2)), emptyList(), poolEmpty = true))
        assertNull(detectAutonomyEvent(emptyList(), emptyList(), poolEmpty = true))
    }

    @Test
    fun stagesWithNoUnitsAreIgnored() {
        // 空壳阶段 total=0：既不算完成，也不该让 planDone 成立
        val before = listOf(stage("s1", 0, 0))
        val after = listOf(stage("s1", 0, 0))

        assertNull(detectAutonomyEvent(before, after, poolEmpty = true))
    }

    @Test
    fun oneStageFinishingDoesNotCountAsPlanDoneWhileAnotherHasNoUnits() {
        val before = listOf(stage("s1", 1, 2), stage("s2", 0, 0))
        val after = listOf(stage("s1", 2, 2), stage("s2", 0, 0))

        val event = detectAutonomyEvent(before, after, poolEmpty = true)

        assertEquals(AutonomyEventKind.StageDone, event?.kind)
        assertEquals(false, event?.planDone)
    }

    @Test
    fun aStageThatWasAlreadyDoneDoesNotFireAgain() {
        val before = listOf(stage("s1", 3, 3))
        val after = listOf(stage("s1", 3, 3))

        assertNull(detectAutonomyEvent(before, after, poolEmpty = false))
    }

    @Test
    fun aStageThatOnlyAppearsInAfterIsNotTreatedAsJustFinished() {
        // 刷新后新出现的阶段（比如刚建计划）没有 before 可比，不算「刚完成」
        val before = emptyList<StageView>()
        val after = listOf(stage("s1", 2, 2))

        assertNull(detectAutonomyEvent(before, after, poolEmpty = false))
    }
}
