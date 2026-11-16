package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 节点的钟点：排期时可带、可改、可清，日视图按时间排；日期改动时时间跟着走。 */
class LearningPlanTimeSlotTest {

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
        UnitDraft("第 1 章", title, minutes, 10)

    /** 两个单元进本周承诺（acceptDraft 铺到天），第三个留在池里。 */
    private fun seed(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(env(), "钟点测试", "把时间排明白", weeklyCapacityMinutes = 60 * 7)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(unit("早读"), unit("刷题"), unit("复盘")),
            listOf(CommitmentDraft("2026-W44", listOf(0, 1))),
        )
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        return planId
    }

    private fun occurrenceId(svc: SqliteLearningPlanService, planId: String, day: String, title: String): String =
        svc.today(planId, day).occurrences.first { it.title == title }.id

    @Test
    fun anOccurrenceCanBePlacedWithAClockTime() {
        val svc = service()
        val planId = seed(svc)
        val pool = svc.pool(planId).single()

        val placed = svc.placeUnit(env(planId), planId, "2026-W44", pool.id, "2026-10-26", startMinute = 9 * 60)
        assertTrue(placed.ok, placed.error?.message)

        val day = svc.today(planId, "2026-10-26")
        assertEquals(9 * 60, day.occurrences.first { it.title == "复盘" }.startMinute)
        assertNull(day.occurrences.first { it.title == "早读" }.startMinute)
    }

    @Test
    fun theDayViewOrdersByClockTimeBeforeRouteOrder() {
        val svc = service()
        val planId = seed(svc)
        val pool = svc.pool(planId).single()
        assertTrue(svc.placeUnit(env(planId), planId, "2026-W44", pool.id, "2026-10-26", startMinute = 7 * 60).ok)

        // 早读原本靠路线顺序排在前面，但定了 10:00；复盘 7:00 应该排到它前面。
        val early = occurrenceId(svc, planId, "2026-10-26", "早读")
        assertTrue(svc.setOccurrenceStart(env(planId), early, 10 * 60).ok)

        val dayTitles = svc.today(planId, "2026-10-26").occurrences.map { it.title }
        assertTrue(dayTitles.indexOf("复盘") < dayTitles.indexOf("早读"), "7:00 应该排在 10:00 前面：" + dayTitles)
        val weekTitles = svc.week(planId, "2026-W44").days.first { it.day == "2026-10-26" }.items.map { it.title }
        assertTrue(weekTitles.indexOf("复盘") < weekTitles.indexOf("早读"), "周视图同一套顺序：" + weekTitles)
    }

    @Test
    fun settingAndClearingTheClockTimeGoesThroughTheService() {
        val svc = service()
        val planId = seed(svc)
        val id = occurrenceId(svc, planId, "2026-10-26", "早读")

        assertTrue(svc.setOccurrenceStart(env(planId), id, 8 * 60 + 30).ok)
        assertEquals(8 * 60 + 30, svc.today(planId, "2026-10-26").occurrences.first { it.id == id }.startMinute)

        assertTrue(svc.setOccurrenceStart(env(planId), id, null).ok)
        assertNull(svc.today(planId, "2026-10-26").occurrences.first { it.id == id }.startMinute)

        val invalid = svc.setOccurrenceStart(env(planId), id, 1440)
        assertFalse(invalid.ok)
        assertEquals(ErrorCode.E_INVALID, invalid.error?.code)
    }

    @Test
    fun movingAnOccurrenceKeepsItsClockTime() {
        val svc = service()
        val planId = seed(svc)
        val id = occurrenceId(svc, planId, "2026-10-26", "早读")
        assertTrue(svc.setOccurrenceStart(env(planId), id, 9 * 60).ok)

        val moved = svc.moveOccurrence(env(planId), id, "2026-10-28")
        assertTrue(moved.ok, moved.error?.message)
        val newId = moved.value!!.occurrenceId
        assertEquals(9 * 60, svc.today(planId, "2026-10-28").occurrences.first { it.id == newId }.startMinute)
    }

    @Test
    fun aCompletedOccurrenceCannotGetANewTime() {
        val svc = service()
        val planId = seed(svc)
        val id = occurrenceId(svc, planId, "2026-10-26", "早读")
        assertTrue(svc.completeOccurrence(env(planId), id).ok)

        val result = svc.setOccurrenceStart(env(planId), id, 9 * 60)
        assertFalse(result.ok)
        assertEquals(ErrorCode.E_STATE, result.error?.code)
    }
}
