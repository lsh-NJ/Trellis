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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 周复盘的验收：一周结束后 `planState` 要指出「欠着哪一周的复盘」，生成后归 null。
 *
 * 计划页的「生成复盘」按钮就靠 `pendingReviewWeek` 定位补哪一周，所以它必须是具体周号，
 * 而不是让前端自己推「上一周」。
 */
class LearningPlanReviewTest {

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

    /** W44 周一建计划：本周 2 个单元，下周 1 个。 */
    private fun seed(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(
            env(), "复盘测试计划", "把这一段学完", weeklyCapacityMinutes = 60 * 7,
        )
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
    fun aFinishedWeekAsksForAReviewAndGeneratingItClearsThat() {
        val svc = service()
        val planId = seed(svc)

        // 还在 W44 里：不欠复盘。
        assertNull(svc.planState(planId).pendingReviewWeek, "周还没结束，不该欠复盘")

        // 进入 W45：W44 已经结束，planState 要点名是哪一周。
        moveClockTo("2026-11-02T08:00")
        val pending = svc.planState(planId)
        assertTrue(pending.pendingReview, "上一周结束了，应该欠一份复盘")
        assertEquals("2026-W44", pending.pendingReviewWeek, "要给出具体周号")

        // 生成之后不再欠；没生成的周仍然是 not_ready。
        val generated = svc.generateReview(env(planId), planId, "2026-W44", "2026-11-02")
        assertTrue(generated.ok, generated.error?.message)
        val cleared = svc.planState(planId)
        assertNull(cleared.pendingReviewWeek)
        assertEquals("ready", svc.review(planId, "2026-W44").state)
        assertEquals("not_ready", svc.review(planId, "2026-W45").state)
    }
}
