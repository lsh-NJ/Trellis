package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 放弃计划：语义上是「ended + 时间戳」，不是新状态。
 *
 * 这样不用改 plan.status 的 CHECK 约束（旧库不用重建表），读侧按 `abandoned_at`
 * 映射成 `"abandoned"`；放弃后一切排期命令都按 ended 拒绝。
 */
class LearningPlanAbandonTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var nowMillis: Long = at("2026-10-26T08:00")

    private fun at(local: String): Long = OffsetDateTime.parse("$local+08:00").toInstant().toEpochMilli()

    private fun service(): SqliteLearningPlanService =
        SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { nowMillis }, zone = zone)

    private fun env(planId: String? = null): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID(),
        actor = Actor.USER,
        planId = planId,
        origin = "test",
    )

    private fun unit(title: String, minutes: Int = 20): UnitDraft =
        UnitDraft(chapter = "第 1 章", title = title, estimateMinutes = minutes, minVersionMinutes = 10)

    private fun seed(svc: SqliteLearningPlanService, title: String = "放弃测试计划"): String {
        val created = svc.createProcess(env(), title, "把这一门课学完", weeklyCapacityMinutes = 60 * 7)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(unit("A"), unit("B"), unit("C"), unit("待安排资料")),
            listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2)),
            ),
        )
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        return planId
    }

    @Test
    fun abandonedPlanReadsAsAbandonedAndRejectsScheduling() = runTest {
        val svc = service()
        val planId = seed(svc)
        val poolUnit = svc.pool(planId).first { it.title == "待安排资料" }

        val abandoned = svc.abandonPlan(env(planId), planId, "换方向了")
        assertTrue(abandoned.ok, abandoned.error?.message)
        assertEquals("abandoned", svc.plans().single { it.id == planId }.status, "列表要分得清「放弃」和「结束」")
        assertEquals("ended", svc.planState(planId).status, "底层状态仍是 ended，只有读侧映射 abandoned")

        val appended = svc.appendUnits(env(planId), planId, listOf(unit("D2")))
        assertFalse(appended.ok, "放弃后不能再加单元")
        assertEquals(ErrorCode.E_STATE, appended.error?.code)

        val placed = svc.placeUnit(env(planId), planId, "2026-W44", poolUnit.id, "2026-10-27")
        assertFalse(placed.ok, "放弃后不能再排")
        assertEquals(ErrorCode.E_STATE, placed.error?.code)

        val resumed = svc.resumePlan(env(planId), planId)
        assertFalse(resumed.ok, "放弃的计划不能恢复")
        assertEquals(ErrorCode.E_STATE, resumed.error?.code)

        val again = svc.abandonPlan(env(planId), planId)
        assertFalse(again.ok, "已经放弃的不能再放弃一次")
        assertEquals(ErrorCode.E_STATE, again.error?.code)
    }

    @Test
    fun pausedPlanCanBeAbandonedButNormallyEndedPlanCannot() {
        val svc = service()

        val paused = seed(svc, "暂停后放弃")
        val pause = svc.pausePlan(env(paused), paused, "先歇一下")
        assertTrue(pause.ok, pause.error?.message)
        val abandoned = svc.abandonPlan(env(paused), paused, "不学了")
        assertTrue(abandoned.ok, abandoned.error?.message)
        assertEquals("abandoned", svc.plans().single { it.id == paused }.status)

        val ended = seed(svc, "正常结束")
        val end = svc.endPlan(env(ended), ended, "学完了")
        assertTrue(end.ok, end.error?.message)
        assertEquals("ended", svc.plans().single { it.id == ended }.status, "正常结束不是放弃")
        val rejected = svc.abandonPlan(env(ended), ended)
        assertFalse(rejected.ok)
        assertEquals(ErrorCode.E_STATE, rejected.error?.code)
    }

    @Test
    fun onlyAnAbandonedPlanCanBeDeletedFromTheList() {
        val svc = service()

        // 进行中的计划不能删：误删代价太大，必须先放弃。
        val active = seed(svc, "进行中的计划")
        val rejected = svc.deletePlan(env(active), active)
        assertFalse(rejected.ok, "进行中的计划不能直接删除")
        assertEquals(ErrorCode.E_STATE, rejected.error?.code)
        assertEquals(active, svc.activePlanId(), "被拒绝后计划仍在使用中")

        // 放弃之后才允许删；删完列表与「当前计划」都看不到它。
        val abandoned = svc.abandonPlan(env(active), active, "换方向了")
        assertTrue(abandoned.ok, abandoned.error?.message)
        val deleted = svc.deletePlan(env(active), active)
        assertTrue(deleted.ok, deleted.error?.message)
        assertTrue(svc.plans().none { it.id == active }, "删除后不应再出现在计划列表里")
        assertEquals(null, svc.activePlanId(), "删除后不应再被当成当前计划")
        assertTrue(svc.exportJsonl(active).contains("plan.deleted"), "删除要写事件，留痕可追溯")

        // 重复删除报错，不再写事件。
        val again = svc.deletePlan(env(active), active)
        assertFalse(again.ok, "重复删除应被拒绝")
        assertEquals(ErrorCode.E_STATE, again.error?.code)
        assertEquals(1, svc.exportJsonl(active).split("plan.deleted").size - 1, "重复删除不应再写一条事件")
    }

    @Test
    fun deletingTheOnlyPlanOfAGoalAlsoHidesTheGoal() {
        val svc = service()
        val planId = seed(svc, "带目标的计划")
        val goal = svc.createGoal(env(planId), "成为 RAG 工程师", intent = "转行", success = "能独立做出 RAG 应用")
        assertTrue(goal.ok, goal.error?.message)
        val goalId = goal.value!!.goalId
        assertTrue(svc.linkPlanToGoal(env(planId), planId, goalId).ok)
        assertTrue(svc.addMilestone(env(planId), goalId, "第一阶段").ok)
        assertEquals(goalId, svc.goal(goalId)?.id)

        assertTrue(svc.abandonPlan(env(planId), planId, "换方向了").ok)
        assertTrue(svc.deletePlan(env(planId), planId).ok)

        // 计划删掉、且目标下没有别的未删计划：目标跟着软删，目标页不该留一个孤立目标。
        assertNull(svc.goal(goalId), "唯一计划被删后，目标也该从目标页消失")
        assertTrue(svc.goals(includeArchived = true).none { it.id == goalId })
        val events = svc.exportJsonl(planId)
        assertTrue(events.contains("goal.deleted"), "级联删除要写事件，留痕可追溯")
        assertTrue(events.contains("\"milestones\""), "事件里要带上随目标隐藏的里程碑数量")
    }

    @Test
    fun deletingAPlanLeavesAGoalThatIsStillUsedByAnotherPlan() {
        val svc = service()
        val first = seed(svc, "计划 A")
        val second = seed(svc, "计划 B")
        val goal = svc.createGoal(env(first), "共用的目标")
        assertTrue(goal.ok, goal.error?.message)
        val goalId = goal.value!!.goalId
        assertTrue(svc.linkPlanToGoal(env(first), first, goalId).ok)
        assertTrue(svc.linkPlanToGoal(env(second), second, goalId).ok)

        assertTrue(svc.abandonPlan(env(first), first, "换方向了").ok)
        assertTrue(svc.deletePlan(env(first), first).ok)

        val after = svc.goal(goalId)
        assertNotNull(after, "还有别的计划用这个目标，目标不能跟着删")
        assertEquals(1, after!!.plans.size, "被删的计划不该再挂在目标下面")
        assertEquals(second, after.plans.single().id)
        assertTrue(svc.exportJsonl(first).contains("plan.goal_unlinked"), "只解绑也要留事件")
    }

    @Test
    fun aNormallyEndedPlanCannotBeDeletedEither() {
        val svc = service()
        val ended = seed(svc, "正常结束的计划")
        val end = svc.endPlan(env(ended), ended, "学完了")
        assertTrue(end.ok, end.error?.message)
        val rejected = svc.deletePlan(env(ended), ended)
        assertFalse(rejected.ok, "正常结束不等于放弃，要删也先放弃")
        assertEquals(ErrorCode.E_STATE, rejected.error?.code)
        assertEquals(1, svc.plans().count { it.id == ended })
    }
}
