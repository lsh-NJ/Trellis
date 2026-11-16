package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * queue 模式：节点只在周队列里，不铺到天；完成时落当天；结算把没做完的整队顺延。
 */
class LearningPlanQueueTest {

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

    private fun seedQueue(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(env(), "队列计划", "一周学完", weeklyCapacityMinutes = 7 * 60)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        assertTrue(svc.setScheduleMode(env(planId), planId, "queue").ok)
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(UnitDraft("第 1 章", "TCP 三次握手", 25), UnitDraft("第 1 章", "TCP 四次挥手", 20)),
            listOf(CommitmentDraft("2026-W44", listOf(0, 1))),
        )
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        return planId
    }

    @Test
    fun acceptingAQueuePlanDoesNotPlaceAnythingOnDays() {
        val svc = service()
        val planId = seedQueue(svc)
        val week = svc.week(planId, "2026-W44")
        assertEquals(listOf("TCP 三次握手", "TCP 四次挥手"), week.commitment.units.map { it.title })
        assertTrue(week.days.all { it.items.isEmpty() }, "queue 模式不该有按天的计划：" + week.days)
        assertTrue(svc.today(planId, "2026-10-26").occurrences.isEmpty())
    }

    @Test
    fun completingAUnitWritesItToToday() {
        val svc = service()
        val planId = seedQueue(svc)
        val unit = svc.week(planId, "2026-W44").commitment.units.first()

        val done = svc.completeUnit(env(planId), planId, unit.id)
        assertTrue(done.ok, done.error?.message)
        val today = svc.today(planId, "2026-10-26")
        assertEquals(listOf("TCP 三次握手"), today.completed.map { it.title })
        assertTrue(today.occurrences.isEmpty(), "完成记录不该再变成待做")
        assertEquals("done", svc.week(planId, "2026-W44").commitment.units.first { it.id == unit.id }.state)
        assertEquals(listOf("TCP 三次握手"), svc.week(planId, "2026-W44").days.first { it.day == "2026-10-26" }.items.map { it.title })

        // 幂等：再点一次不重复记。
        val again = svc.completeUnit(env(planId), planId, unit.id)
        assertTrue(again.ok)
        assertEquals(1, svc.today(planId, "2026-10-26").completed.size)
    }

    @Test
    fun settlingAQueueWeekCarriesUnfinishedUnitsIntoTheNextWeek() {
        val svc = service()
        val planId = seedQueue(svc)
        val first = svc.week(planId, "2026-W44").commitment.units.first()
        assertTrue(svc.completeUnit(env(planId), planId, first.id).ok)

        nowMillis = at("2026-11-02T08:00") // 下一周
        val settled = svc.settleWeek(env(planId), planId, "2026-W44", "2026-11-02")
        assertTrue(settled.ok, settled.error?.message)
        assertEquals(1, settled.value?.carriedCount)

        val next = svc.week(planId, "2026-W45")
        assertEquals(listOf("TCP 四次挥手"), next.commitment.units.map { it.title })
        assertTrue(next.commitment.units.single().state != "done")
    }

    @Test
    fun switchingADayPlanToQueueSupersedesFuturePlannedOccurrences() {
        val svc = service()
        // 先用旧模式建一份：accept 会把本周铺到天。
        val created = svc.createProcess(env(), "按天计划", "先按天", weeklyCapacityMinutes = 7 * 60)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId), planId, null,
            listOf(UnitDraft("第 1 章", "函数与类型", 20)),
            listOf(CommitmentDraft("2026-W44", listOf(0))),
        )
        assertTrue(draft.ok)
        assertTrue(svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44").ok)
        assertFalse(svc.week(planId, "2026-W44").days.all { it.items.isEmpty() })

        // 同一天切：当天及以后还没做的排期都作废（之前的完成记录不动）。
        assertTrue(svc.setScheduleMode(env(planId), planId, "queue").ok)
        val week = svc.week(planId, "2026-W44")
        assertEquals("queue", svc.planState(planId).scheduleMode)
        assertTrue(week.days.all { it.items.isEmpty() }, "切换到 queue 后未来排期要作废：" + week.days)
        assertEquals(1, week.commitment.units.size, "单元还在队列里")
    }
}
