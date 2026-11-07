package dev.trellis.app.desktop

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 目标 / 项目页面的运行时验收：建目标、加里程碑、勾掉/取消、挂计划、归档。
 *
 * 页面只读 `AppRuntime.goals`，所以这里验证的是「动作 → 重新读库 → 状态更新」这条链路。
 */
class GoalRuntimeTest {

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-goal-runtime").toFile().apply { deleteOnExit() }

    private fun env(planId: String? = null) = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = planId,
        origin = "test.goals",
    )

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    private suspend fun runtimeWithPlan(home: File): Pair<AppRuntime, String> {
        val runtime = AppRuntime.create(home)
        runtime.applySimulatedToday(LocalDate.parse("2026-11-07"))
        val store: LearningPlanService = LearningPlanStore.open(
            File(home, "plans/plan.db").toPath(),
            { runtime.clock.nowMs() },
            ZoneId.systemDefault(),
        )
        val planId = store.createProcess(env(), "RAG 入门", "先跑通最小链路").unwrap().planId
        return runtime to planId
    }

    @Test
    fun aGoalCanBeCreatedAndOpenedFromThePlanPage() = runTest {
        val home = tempHome()
        val (runtime, _) = runtimeWithPlan(home)
        try {
            val goalId = assertNotNull(
                runtime.createGoal(
                    title = "半年内能独立做 RAG 项目",
                    kind = "goal",
                    intent = "把学到的检索增强知识真正用起来",
                    success = "能用自建语料回答一个具体问题",
                    deadline = "2027-05-01",
                ),
                runtime.goalMessage,
            )
            assertTrue(runtime.goals.any { it.id == goalId })

            runtime.openGoalScreen()
            assertEquals(TAB_GOALS, runtime.currentTab)
            runtime.closeGoalScreen()
            assertEquals(TAB_PLAN, runtime.currentTab)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun milestonesCanBeToggledAndRemoved() = runTest {
        val home = tempHome()
        val (runtime, _) = runtimeWithPlan(home)
        try {
            val goalId = assertNotNull(runtime.createGoal("搭一个 RAG 项目", "project", "", "跑通一条问答链路", null))
            runtime.addGoalMilestone(goalId, "跑通最小检索链路", "2026-11-20")
            val goal = runtime.goals.first { it.id == goalId }
            val milestone = goal.milestones.single()
            assertNotNull(milestone.deadline)

            runtime.completeGoalMilestone(milestone.id)
            assertEquals("done", runtime.goals.first { it.id == goalId }.milestones.single().status)

            runtime.uncompleteGoalMilestone(milestone.id)
            assertEquals("open", runtime.goals.first { it.id == goalId }.milestones.single().status)

            runtime.removeGoalMilestone(milestone.id)
            assertTrue(runtime.goals.first { it.id == goalId }.milestones.isEmpty())
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun aPlanCanBeLinkedToAGoalAndTheGoalArchived() = runTest {
        val home = tempHome()
        val (runtime, planId) = runtimeWithPlan(home)
        try {
            val goalId = assertNotNull(runtime.createGoal("半年内能独立做 RAG 项目", "goal", "", "能跑通问答", null))
            runtime.linkPlanToGoal(planId, goalId)
            assertEquals(
                listOf(planId),
                runtime.goals.first { it.id == goalId }.plans.map { it.id },
            )

            runtime.setGoalStatus(goalId, "archived")
            assertEquals(
                "archived",
                runtime.goals.first { it.id == goalId }.status,
            )
            runtime.refreshPlan()
            // 计划页头部只数进行中的目标。
            assertEquals(0, runtime.goals.count { it.status == "active" })
        } finally {
            runtime.shutdown()
        }
    }
}
