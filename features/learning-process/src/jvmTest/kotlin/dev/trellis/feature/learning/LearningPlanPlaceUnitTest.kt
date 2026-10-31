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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 拖拽重排的后端验收：[dev.trellis.domain.plan.LearningPlanService.placeUnit]。
 *
 * 拖的是「待安排池」里的欠账单元——它们不在任何「本周及以后」的承诺里，
 * 所以这条命令要先把单元增量追加进目标周的承诺，再铺 occurrence；
 * 同时验「日视图按路线索引排序」，拖完同一天里靠前的节点排在前面。
 */
class LearningPlanPlaceUnitTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var nowMillis: Long = at("2026-10-26T08:00")

    private fun at(local: String): Long = OffsetDateTime.parse("$local+08:00").toInstant().toEpochMilli()

    private fun moveClockTo(local: String) {
        nowMillis = at(local)
    }

    private fun service(): SqliteLearningPlanService =
        SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { nowMillis }, zone = zone)

    private fun env(planId: String? = null): CommandEnvelope = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID(),
        actor = Actor.USER,
        planId = planId,
        origin = "test",
    )

    private fun unit(title: String, minutes: Int = 20, chapter: String = "第 1 章"): UnitDraft =
        UnitDraft(chapter = chapter, title = title, estimateMinutes = minutes, minVersionMinutes = 10)

    private fun seed(
        svc: SqliteLearningPlanService,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
    ): String {
        val created = svc.createProcess(env(), "测试计划", "把这一段学完", weeklyCapacityMinutes = 60 * 7)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(env(planId), planId, null, units, commitments)
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, commitments.first().week)
        assertTrue(accepted.ok, accepted.error?.message)
        return planId
    }

    /**
     * 5 个单元：本周承诺前 2 个（acceptDraft 会把它们铺到本周），下周承诺第 3 个，
     * 剩下 2 个留在待安排池里——正好模拟「上周没做完回池」的拖拽对象。
     */
    private fun seedPoolPlan(svc: SqliteLearningPlanService): String = seed(
        svc = svc,
        units = (1..5).map { unit("课时$it") },
        commitments = listOf(
            CommitmentDraft("2026-W44", listOf(0, 1)),
            CommitmentDraft("2026-W45", listOf(2)),
        ),
    )

    private fun committedUnitId(svc: SqliteLearningPlanService, planId: String, week: String, title: String): String =
        svc.week(planId, week).commitment!!.units.first { it.title == title }.id

    @Test
    fun aPoolUnitCanBeDraggedOntoADayThisWeek() {
        val svc = service()
        val planId = seedPoolPlan(svc)
        val pool = svc.pool(planId)
        assertEquals(listOf("课时4", "课时5"), pool.map { it.title })
        val target = pool.first()

        val placed = svc.placeUnit(env(planId), planId, "2026-W44", target.id, "2026-10-28")
        assertTrue(placed.ok, placed.error?.message)
        assertNotNull(placed.value)

        val day = svc.today(planId, "2026-10-28")
        assertEquals(listOf("课时4"), day.occurrences.map { it.title })
        assertEquals(listOf("课时5"), svc.pool(planId).map { it.title })
    }

    @Test
    fun draggingIntoANewWeekAppendsToThatWeeksCommitment() {
        val svc = service()
        val planId = seedPoolPlan(svc)
        val target = svc.pool(planId).first { it.title == "课时5" }

        val placed = svc.placeUnit(env(planId), planId, "2026-W45", target.id, "2026-11-02")
        assertTrue(placed.ok, placed.error?.message)

        val week = svc.week(planId, "2026-W45")
        assertNotNull(week.commitment)
        assertEquals(listOf("课时3", "课时5"), week.commitment!!.units.map { it.title })
        assertEquals(listOf("课时5"), svc.today(planId, "2026-11-02").occurrences.map { it.title })
    }

    @Test
    fun placingTheSameUnitTwiceIsRejected() {
        val svc = service()
        val planId = seedPoolPlan(svc)
        val target = svc.pool(planId).first()

        assertTrue(svc.placeUnit(env(planId), planId, "2026-W44", target.id, "2026-10-28").ok)
        val again = svc.placeUnit(env(planId), planId, "2026-W44", target.id, "2026-10-29")
        assertFalse(again.ok)
        assertTrue(again.error!!.message.contains("已经排在某天"), again.error!!.message)
    }

    @Test
    fun placesAreValidatedAgainstWeekPastAndCommitment() {
        val svc = service()
        val planId = seedPoolPlan(svc)
        val target = svc.pool(planId).first()

        // 日期和 ISO 周不一致
        val mismatch = svc.placeUnit(env(planId), planId, "2026-W45", target.id, "2026-10-28")
        assertFalse(mismatch.ok)

        // 过去周
        val past = svc.placeUnit(env(planId), planId, "2026-W43", target.id, "2026-10-21")
        assertFalse(past.ok)
        assertTrue(past.error!!.message.contains("过去的周"), past.error!!.message)

        // 已经在下周承诺里、但还没铺天的单元，不能走「从池子拖进来」的路径
        val committed = committedUnitId(svc, planId, "2026-W45", "课时3")
        val alreadyCommitted = svc.placeUnit(env(planId), planId, "2026-W44", committed, "2026-10-28")
        assertFalse(alreadyCommitted.ok)
        assertTrue(alreadyCommitted.error!!.message.contains("已经排在别的周了"), alreadyCommitted.error!!.message)
    }

    /** 重排过的单元再被系统铺天（materializeWeek）时不能重复铺。 */
    @Test
    fun materializingTheWeekAgainDoesNotDuplicateAPlacedUnit() {
        val svc = service()
        val planId = seedPoolPlan(svc)
        val target = svc.pool(planId).first()
        assertTrue(svc.placeUnit(env(planId), planId, "2026-W44", target.id, "2026-10-28").ok)

        val scheduled = svc.scheduleWeek(env(planId), "2026-W44")
        assertTrue(scheduled.ok, scheduled.error?.message)

        val day = svc.today(planId, "2026-10-28")
        assertEquals(1, day.occurrences.count { it.title == "课时4" })
    }

    /** 同一天里按路线索引排序：把后面的单元先挪过来，靠前的单元仍要排在前列。 */
    @Test
    fun theDayViewIsOrderedByRouteIndexNotCreationOrder() {
        val svc = service()
        val planId = seed(
            svc = svc,
            units = (1..3).map { unit("课时$it") },
            commitments = listOf(CommitmentDraft("2026-W44", listOf(0, 1, 2))),
        )
        assertTrue(svc.scheduleWeek(env(planId), "2026-W44").ok)
        val week = svc.week(planId, "2026-W44")
        fun occurrenceOf(title: String): String =
            week.days.flatMap { it.items }.first { it.title == title }.occurrenceId

        // 先把 10-28 上的课时3挪走腾出位置，再把课时2、课时1先后挪到 10-28，
        // 这样 created_at 的顺序正好和路线顺序相反。
        assertTrue(svc.moveOccurrence(env(planId), occurrenceOf("课时3"), "2026-10-29", "腾位置").ok)
        assertTrue(svc.moveOccurrence(env(planId), occurrenceOf("课时2"), "2026-10-28", "拖拽重排").ok)
        assertTrue(svc.moveOccurrence(env(planId), occurrenceOf("课时1"), "2026-10-28", "拖拽重排").ok)

        val day = svc.today(planId, "2026-10-28")
        assertEquals(listOf("课时1", "课时2"), day.occurrences.map { it.title })
    }
}
