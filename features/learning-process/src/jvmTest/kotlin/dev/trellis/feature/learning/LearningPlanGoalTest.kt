package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.ErrorCode
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 目标 / 项目实体（G-A1）的验收：
 *
 * - 目标的增改查（活跃/归档过滤）；
 * - 里程碑的添加、完成、撤销、删除；
 * - 计划关联到目标 / 解绑；归档后不能再关联；
 * - 输入校验（标题、kind、status、deadline、parent）。
 */
class LearningPlanGoalTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var nowMillis: Long = at("2026-11-07T09:00")

    private fun at(local: String): Long = OffsetDateTime.parse("$local+08:00").toInstant().toEpochMilli()

    private fun service(): SqliteLearningPlanService =
        SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { nowMillis }, zone = zone)

    private fun env(planId: String? = null): CommandEnvelope =
        CommandEnvelope(
            commandId = "cmd_" + UUID.randomUUID(),
            actor = Actor.USER,
            planId = planId,
            origin = "test",
        )

    @Test
    fun aGoalCanBeCreatedAndRead() {
        val svc = service()
        val created = svc.createGoal(
            env(),
            title = "半年内能独立做 RAG 项目",
            kind = "goal",
            intent = "能自己搭一条检索增强回答链路",
            success = "能用自建语料回答一个具体问题，并说清召回为什么按这个来",
            deadline = "2027-05-01",
        )
        assertTrue(created.ok, created.error?.message)
        val goalId = created.value!!.goalId

        val goal = assertNotNull(svc.goal(goalId), "刚建好的目标要能查到")
        assertEquals("半年内能独立做 RAG 项目", goal.title)
        assertEquals("goal", goal.kind)
        assertEquals("2027-05-01", goal.deadline)
        assertEquals("active", goal.status)
        assertEquals(listOf(goal), svc.goals().filter { it.id == goalId })
    }

    @Test
    fun invalidGoalInputIsRejected() {
        val svc = service()
        assertFalse(svc.createGoal(env(), title = "  ").ok, "空标题要拒绝")
        assertFalse(svc.createGoal(env(), title = "x".repeat(81)).ok, "标题过长要拒绝")
        assertFalse(svc.createGoal(env(), title = "目标", kind = "metaphor").ok, "kind 只能是 goal/project")
        assertFalse(svc.createGoal(env(), title = "目标", deadline = "2026/12/31").ok, "日期格式要统一")
        assertFalse(svc.createGoal(env(), title = "目标", parentId = "goal_not_exists").ok, "父目标必须存在")
    }

    @Test
    fun milestonesCanBeCheckedAndUndone() {
        val svc = service()
        val goalId = svc.createGoal(env(), title = "搭一个 RAG 项目").value!!.goalId
        val first = svc.addMilestone(env(), goalId, "跑通最小检索链路", deadline = "2026-11-20")
        assertTrue(first.ok, first.error?.message)
        val milestoneId = first.value!!.milestoneId
        svc.addMilestone(env(), goalId, "换一种向量模型再对比")

        val before = assertNotNull(svc.goal(goalId))
        assertEquals(2, before.milestones.size)
        assertEquals("open", before.milestones[0].status)
        assertEquals("2026-11-20", before.milestones[0].deadline)

        val done = svc.completeMilestone(env(), milestoneId)
        assertTrue(done.ok, done.error?.message)
        assertEquals("done", assertNotNull(svc.goal(goalId)).milestones[0].status)

        val undo = svc.undoLast(env(), done.undo!!.token)
        assertTrue(undo.ok, undo.error?.message)
        assertEquals("open", assertNotNull(svc.goal(goalId)).milestones[0].status)
    }

    @Test
    fun aMilestoneCanBeReopenedAndRemoved() {
        val svc = service()
        val goalId = svc.createGoal(env(), title = "搭一个 RAG 项目").value!!.goalId
        val milestoneId = svc.addMilestone(env(), goalId, "跑通最小检索链路").value!!.milestoneId
        svc.completeMilestone(env(), milestoneId)

        assertTrue(svc.uncompleteMilestone(env(), milestoneId).ok)
        assertEquals("open", assertNotNull(svc.goal(goalId)).milestones.single().status)

        val removed = svc.removeMilestone(env(), milestoneId)
        assertTrue(removed.ok, removed.error?.message)
        assertTrue(assertNotNull(svc.goal(goalId)).milestones.isEmpty())
        assertNull(svc.removeMilestone(env(), milestoneId).value, "删过的里程碑不能再删")
    }

    @Test
    fun aPlanCanBeLinkedToAGoalAndUnlinked() {
        val svc = service()
        val goalId = svc.createGoal(env(), title = "搭一个 RAG 项目", kind = "project").value!!.goalId
        val planId = svc.createProcess(env(), "RAG 入门", "先跑通最小链路").value!!.planId

        val linked = svc.linkPlanToGoal(env(planId), planId, goalId)
        assertTrue(linked.ok, linked.error?.message)
        val goal = assertNotNull(svc.goal(goalId))
        assertEquals(listOf(planId), goal.plans.map { it.id })

        val planState = svc.planState(planId)
        assertEquals(planId, planState.planId, "关联目标不影响计划状态可查")

        val unlinked = svc.linkPlanToGoal(env(planId), planId, null)
        assertTrue(unlinked.ok, unlinked.error?.message)
        assertTrue(assertNotNull(svc.goal(goalId)).plans.isEmpty())
    }

    @Test
    fun archivedGoalsAreHiddenFromTheDefaultList() {
        val svc = service()
        val goalId = svc.createGoal(env(), title = "考过软考").value!!.goalId
        assertEquals(1, svc.goals().size)
        assertTrue(svc.updateGoal(env(), goalId, status = "archived").ok)
        assertEquals(0, svc.goals().size)
        assertEquals(1, svc.goals(includeArchived = true).size)

        val planId = svc.createProcess(env(), "软考计划", "过一次软考").value!!.planId
        val rejected = svc.linkPlanToGoal(env(planId), planId, goalId)
        assertFalse(rejected.ok, "归档目标不能再关联新计划")
        assertEquals(ErrorCode.E_INVALID, rejected.error?.code)
    }
}
