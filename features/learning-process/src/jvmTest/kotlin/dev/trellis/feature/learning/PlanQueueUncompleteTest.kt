package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 2026-11-19 用户反馈：队列里的节点勾完成后会绑到「今天」，取消完成时日期绑定没取消，
 * 节点一直挂在今天那天上。期望：取消完成 = 连日期绑定一起取消、回到本周队列。
 */
class PlanQueueUncompleteTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMillis: Long = OffsetDateTime.parse("2026-11-19T10:00+08:00").toInstant().toEpochMilli()
    private val week = "2026-W47"

    private fun env(planId: String? = null): CommandEnvelope =
        CommandEnvelope(commandId = "cmd_" + UUID.randomUUID(), actor = Actor.USER, planId = planId, origin = "test")

    private fun service(): SqliteLearningPlanService =
        SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { nowMillis }, zone = zone)

    @Test
    fun uncompletingAQueueNodeRemovesItsDateBinding() = runTest {
        val svc = service()
        val created = svc.createShell(env(), "队列取消完成", "测日期绑定")
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        assertTrue(svc.setScheduleMode(env(planId), planId, "queue").ok)
        val appended = svc.appendUnits(env(planId), planId, listOf(UnitDraft("第一章", "一个节点", 30, 10)))
        assertTrue(appended.ok, appended.error?.message)
        val unitId = appended.value!!.single()
        assertTrue(svc.enqueueUnits(env(planId), planId, week, listOf(unitId)).ok)

        val completed = svc.completeUnit(env(planId), planId, unitId)
        assertTrue(completed.ok, completed.error?.message)
        val occurrenceId = completed.value!!.occurrenceId
        // 完成时绑到今天：周视图里今天有这一条，节点 state=done。
        assertEquals("done", svc.week(planId, week).commitment.units.single().state)
        val today = svc.week(planId, week).days.first { it.day == "2026-11-19" }
        assertTrue(today.items.any { it.occurrenceId == occurrenceId }, "完成时应该绑到今天")

        val undone = svc.uncompleteOccurrence(env(planId), occurrenceId)
        assertTrue(undone.ok, undone.error?.message)

        val after = svc.week(planId, week)
        assertNotEquals("done", after.commitment.units.single().state, "取消完成后节点不该还是已完成")
        assertTrue(
            after.days.flatMap { it.items }.none { it.occurrenceId == occurrenceId },
            "取消完成后不该再挂在任何一天：" + after.days.flatMap { it.items }.map { it.title },
        )
    }

    /** 「撤销完成」按钮走的也是同一条路：队列模式撤销后同样不该再绑在今天。 */
    @Test
    fun undoingAQueueCompletionAlsoRemovesTheDateBinding() = runTest {
        val svc = service()
        val created = svc.createShell(env(), "队列撤销完成", "测日期绑定")
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        assertTrue(svc.setScheduleMode(env(planId), planId, "queue").ok)
        val appended = svc.appendUnits(env(planId), planId, listOf(UnitDraft("第一章", "一个节点", 30, 10)))
        val unitId = appended.value!!.single()
        assertTrue(svc.enqueueUnits(env(planId), planId, week, listOf(unitId)).ok)

        val completed = svc.completeUnit(env(planId), planId, unitId)
        assertTrue(completed.ok, completed.error?.message)
        val undoToken = completed.undo?.token ?: error("完成命令应该带撤销凭据")
        val occurrenceId = completed.value!!.occurrenceId

        val undone = svc.undoLast(env(planId), undoToken)
        assertTrue(undone.ok, undone.error?.message)

        val after = svc.week(planId, week)
        assertNotEquals("done", after.commitment.units.single().state)
        assertTrue(
            after.days.flatMap { it.items }.none { it.occurrenceId == occurrenceId },
            "撤销完成后不该再挂在任何一天",
        )
    }
}
