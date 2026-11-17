package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 计划工作流清单的状态映射（2026-11-17 两个真实事故的回归测试）：
 *
 * 1. 每段跑完必须显示「等待确认」（Awaiting），用户点了「继续」、这一段 mark done 之后才变 Done；
 * 2. 流程结束时清单要收尾（settled），不能永远停在「1/3」；
 * 3. 失败段要标失败并带上原因。
 */
class PlanWorkflowChecklistTest {

    private fun phases(vararg states: Pair<String, String>) =
        states.map { (phase, state) -> WorkflowPhaseSnapshot(phase, state) }

    @Test
    fun awaitingPhaseShowsWaitInsteadOfRunningOrDone() {
        val steps = workflowChecklistSteps(
            jobState = "awaiting_user",
            phases = phases(
                "master" to "running",
                "week" to "pending",
                "units" to "pending",
            ),
        )
        assertEquals(PlanStepState.Awaiting, steps[0].state)
        assertTrue(steps[0].note.contains("等待确认"), steps[0].note)
        assertEquals(PlanStepState.Pending, steps[1].state)
        assertEquals(PlanStepState.Pending, steps[2].state)
        // 等待确认不是终态：清单不能收起。
        assertFalse(workflowChecklistSettled("awaiting_user"))
    }

    @Test
    fun finishedPhasesAreAllDoneWhenJobCompletes() {
        val steps = workflowChecklistSteps(
            jobState = "done",
            phases = phases(
                "master" to "done",
                "week" to "done",
                "units" to "done",
            ),
        )
        assertTrue(steps.all { it.state == PlanStepState.Done }, steps.toString())
        assertTrue(workflowChecklistSettled("done"))
    }

    @Test
    fun runningJobMarksOnlyTheCurrentPhaseAsRunning() {
        val steps = workflowChecklistSteps(
            jobState = "running",
            phases = phases(
                "master" to "done",
                "week" to "running",
                "units" to "pending",
            ),
        )
        assertEquals(PlanStepState.Done, steps[0].state)
        assertEquals(PlanStepState.Running, steps[1].state)
        assertEquals(PlanStepState.Pending, steps[2].state)
    }

    @Test
    fun failedPhaseCarriesTheErrorAndDoesNotLookDone() {
        val steps = workflowChecklistSteps(
            jobState = "running",
            phases = listOf(
                WorkflowPhaseSnapshot("master", "done"),
                WorkflowPhaseSnapshot("week", "failed", error = "周文档没写成"),
                WorkflowPhaseSnapshot("units", "pending"),
            ),
        )
        assertEquals(PlanStepState.Failed, steps[1].state)
        assertEquals("周文档没写成", steps[1].note)
        assertFalse(workflowChecklistSettled("running"))
    }

    @Test
    fun phaseTitlesMatchTheThreeDocumentTransformations() {
        assertEquals("调研结果 → 总计划文档", workflowPhaseTitle("master"))
        assertEquals("总计划文档 → 周文档", workflowPhaseTitle("week"))
        assertEquals("周文档 → 计划节点", workflowPhaseTitle("units"))
    }
}
