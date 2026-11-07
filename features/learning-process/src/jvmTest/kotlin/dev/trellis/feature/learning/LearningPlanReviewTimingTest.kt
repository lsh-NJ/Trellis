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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 复盘时机：一周的最后一天（周日）当天就能生成这一周的总结，周六不行。
 *
 * `reviewableWeek` 是「现在就能做」的那一周（周日当天），`pendingReviewWeek` 是
 * 「已经欠着」的旧周（上周及更早）——两者可以同时存在，UI 各显示一个入口。
 */
class LearningPlanReviewTimingTest {

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

    private fun unit(title: String, minutes: Int = 20): UnitDraft =
        UnitDraft(chapter = "第 1 章", title = title, estimateMinutes = minutes, minVersionMinutes = 10)

    /** W44（10/26–11/1）两个单元、W45（11/2–11/8）一个单元。 */
    private fun seed(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(env(), "复盘时机测试", "把这一段学完", weeklyCapacityMinutes = 60 * 7)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(unit("A"), unit("B"), unit("C")),
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
    fun currentWeekIsReviewableOnSundayButNotOnSaturday() {
        val svc = service()
        val planId = seed(svc)

        // 周六（2026-11-07，W45）：本周还没过完，只能补 W44 的旧账。
        moveClockTo("2026-11-07T08:00")
        val saturday = svc.planState(planId)
        assertNull(saturday.reviewableWeek, "周六本周还不能做总结")
        assertEquals("2026-W44", saturday.pendingReviewWeek, "旧账不变")
        assertFalse(svc.week(planId, "2026-W45").reviewEntry.available, "周六周复盘入口不开放")

        val early = svc.generateReview(env(planId), planId, "2026-W45", "2026-11-07")
        assertFalse(early.ok, "提前生成要被拒绝")
        assertEquals(ErrorCode.E_STATE, early.error?.code)
        assertEquals("not_ready", svc.review(planId, "2026-W45").state)

        // 周日（2026-11-08）：W45 的最后一天，本周可以现在做总结；旧的 W44 欠账还在。
        moveClockTo("2026-11-08T08:00")
        val sunday = svc.planState(planId)
        assertEquals("2026-W45", sunday.reviewableWeek, "周日当天本周可以生成总结")
        assertEquals("2026-W44", sunday.pendingReviewWeek, "旧账仍然保留")
        val sundayEntry = svc.week(planId, "2026-W45").reviewEntry
        assertTrue(sundayEntry.available, "周日周复盘入口开放")
        assertEquals("生成本周复盘", sundayEntry.label)

        val generated = svc.generateReview(env(planId), planId, "2026-W45", "2026-11-08")
        assertTrue(generated.ok, generated.error?.message)
        assertEquals("ready", svc.review(planId, "2026-W45").state)
        assertNull(svc.planState(planId).reviewableWeek, "生成之后不再提示")
        assertEquals("2026-W44", svc.planState(planId).pendingReviewWeek, "W45 生成了不影响 W44 的旧账")
    }

    @Test
    fun endedWeeksCanStillBeReviewedMidWeek() {
        val svc = service()
        val planId = seed(svc)

        // 周三（2026-11-04，W45）：W44 已经结束，随时能补。
        moveClockTo("2026-11-04T08:00")
        assertNull(svc.planState(planId).reviewableWeek, "工作日不能提前做本周总结")
        val wednesday = svc.generateReview(env(planId), planId, "2026-W45", "2026-11-04")
        assertFalse(wednesday.ok)
        assertEquals(ErrorCode.E_STATE, wednesday.error?.code)

        val lastWeek = svc.generateReview(env(planId), planId, "2026-W44", "2026-11-04")
        assertTrue(lastWeek.ok, "已结束的周随时可以补")
        assertEquals("ready", svc.review(planId, "2026-W44").state)
    }
}
