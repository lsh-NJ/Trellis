package dev.trellis.app.desktop

import dev.trellis.domain.plan.StageView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ---- isPlanComplete：完成态的唯一定义（阶段全完成 + 待安排池空） ----

    @Test
    fun emptyStagesAreNotComplete() {
        assertFalse(isPlanComplete(emptyList(), poolEmpty = true))
    }

    @Test
    fun aStageWithNoUnitsMeansNotComplete() {
        // total = 0 的阶段只是空壳，不算「做完」
        assertFalse(isPlanComplete(listOf(stage("s1", 0, 0)), poolEmpty = true))
        assertFalse(isPlanComplete(listOf(stage("s1", 2, 2), stage("s2", 0, 0)), poolEmpty = true))
    }

    @Test
    fun anUnfinishedStageMeansNotComplete() {
        assertFalse(isPlanComplete(listOf(stage("s1", 1, 2)), poolEmpty = true))
        assertFalse(isPlanComplete(listOf(stage("s1", 2, 2), stage("s2", 1, 3)), poolEmpty = true))
    }

    @Test
    fun aNonEmptyPoolMeansNotComplete() {
        val stages = listOf(stage("s1", 2, 2), stage("s2", 1, 1))

        assertFalse(isPlanComplete(stages, poolEmpty = false))
    }

    @Test
    fun allStagesDoneWithEmptyPoolIsComplete() {
        val stages = listOf(stage("s1", 2, 2), stage("s2", 3, 3), stage("s3", 1, 1))

        assertTrue(isPlanComplete(stages, poolEmpty = true))
    }

    @Test
    fun aSingleStageOneOfOneIsComplete() {
        assertTrue(isPlanComplete(listOf(stage("s1", 1, 1)), poolEmpty = true))
    }

    @Test
    fun doneBeyondTotalStillCountsAsComplete() {
        // 判定用 done >= total，余量不会把完成态判回去
        assertTrue(isPlanComplete(listOf(stage("s1", 4, 3)), poolEmpty = true))
    }

    @Test
    fun planDoneEventAgreesWithIsPlanComplete() {
        val before = listOf(stage("s1", 1, 2), stage("s2", 1, 1))
        val after = listOf(stage("s1", 2, 2), stage("s2", 1, 1))

        assertTrue(isPlanComplete(after, poolEmpty = true))
        val event = detectAutonomyEvent(before, after, poolEmpty = true)
        assertEquals(AutonomyEventKind.PlanDone, event?.kind)
        assertEquals(true, event?.planDone)
    }

    @Test
    fun nonCompletePlanNeverFiresPlanDone() {
        // 阶段都做完了但待安排池还有东西：isPlanComplete 为 false，事件只能是 StageDone
        val before = listOf(stage("s1", 1, 2), stage("s2", 1, 1))
        val after = listOf(stage("s1", 2, 2), stage("s2", 1, 1))

        assertFalse(isPlanComplete(after, poolEmpty = false))
        val event = detectAutonomyEvent(before, after, poolEmpty = false)
        assertEquals(AutonomyEventKind.StageDone, event?.kind)
        assertEquals(false, event?.planDone)

        // 反过来：事件是 PlanDone 时 isPlanComplete 必为 true
        assertTrue(isPlanComplete(after, poolEmpty = true))
    }
}
