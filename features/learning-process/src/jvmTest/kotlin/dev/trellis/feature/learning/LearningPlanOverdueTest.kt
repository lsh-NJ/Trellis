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
import kotlin.test.assertTrue

/**
 * 逾期投影（[dev.trellis.domain.plan.LearningPlanService.overdue]）的验收。
 *
 * 只验「事实」：本周哪天该做没做、过去周欠了多少、今天起还剩多少容量。
 * 不验建议或重排——那是聊天里协商之后发命令的事。
 */
class LearningPlanOverdueTest {

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

    private fun scheduler(planId: String): CommandEnvelope = CommandEnvelope(
        commandId = "rhythm_" + UUID.randomUUID(),
        actor = Actor.SYSTEM,
        planId = planId,
        origin = "scheduler",
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

    @Test
    fun overdueThisWeekIsOrderedByRouteAndKnowsRemainingCapacity() {
        val svc = service()
        val planId = seed(
            svc,
            units = List(5) { unit("U${it + 1}") },
            commitments = listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2, 3)),
            ),
        )

        // W45 周一推进一次：W44 没做的两个结转进来，W45 的承诺铺到天。
        moveClockTo("2026-11-02T09:00")
        assertTrue(svc.advanceRhythm(scheduler(planId), planId).ok)
        // 周三再查：周一、周二该做没做的都算「本周隔日」。
        moveClockTo("2026-11-04T09:00")

        val seqBefore = svc.latestSeq(planId)
        val view = svc.overdue(planId, "2026-11-04")
        assertEquals(seqBefore, svc.latestSeq(planId), "逾期投影是只读的，不该写事件")

        assertEquals("2026-W45", view.week)
        assertEquals(60, view.dailyMinutes)
        assertEquals(
            listOf("U1", "U2", "U3", "U4"),
            view.thisWeek.map { it.title },
            "按路线顺序排，不按日期/创建时间",
        )
        assertEquals(listOf("2026-11-02", "2026-11-02", "2026-11-02", "2026-11-03"), view.thisWeek.map { it.day })
        assertEquals(5, view.remainingDays, "周三是 W45 剩下 5 天（周三到周日）")
        assertEquals(300, view.remainingMinutes, "5 天 × 60 分钟，后面还没排东西")
        assertEquals(emptyList(), view.backlog)
    }

    @Test
    fun leftoversReturnToThePoolAndShowUpAsBacklog() {
        val svc = service()
        // W44 承诺 10 个单元一个都没做，W45 只承诺了 3 个：本周塞不下全部欠账，
        // 结算后该回「未安排桶」的，逾期投影要能看见它们。
        val planId = seed(
            svc,
            units = List(20) { unit("U${it + 1}") },
            commitments = listOf(
                CommitmentDraft("2026-W44", (0..9).toList()),
                CommitmentDraft("2026-W45", listOf(10, 11, 12)),
            ),
        )

        moveClockTo("2026-11-02T09:00")
        val rolled = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(rolled.ok, rolled.error?.message)
        assertEquals(10, rolled.value!!.missed)
        assertEquals(2, rolled.value!!.carried, "自动结转最多 2 个进本周")
        assertEquals(8, rolled.value!!.carryPending)

        val view = svc.overdue(planId, "2026-11-02")
        assertEquals(emptyList(), view.thisWeek, "周一早上还没有「过了日子」的排期")

        // U10（路线第 10 个）是 W44 错过、又被 W46 的预算挤回池里的那一个；
        // U2 虽然也错过，但已经被 W46 收走，不算欠账。
        assertEquals(1, view.backlog.size, "池子里有 8 个，但其中只有 U10 属于「过去周错过且没被收走」")
        val back = view.backlog.first()
        assertEquals("U10", back.title)
        assertEquals(9, back.sortOrder)
        assertEquals("2026-W44", back.missedInWeek)
        assertEquals(8, svc.pool(planId).size)
    }

    @Test
    fun aShortCourseThatFilledOneWeekKeepsGoingNextWeek() {
        val svc = service()
        // 短课程：7 个单元一周就排满了（原来到下一周会卡死，7×missed + 7×carry.pending 但无容器）。
        val planId = seed(
            svc,
            units = List(7) { unit("U${it + 1}") },
            commitments = listOf(CommitmentDraft("2026-W44", (0..6).toList())),
        )

        moveClockTo("2026-11-02T09:00")
        val rolled = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(rolled.ok, rolled.error?.message)
        val value = rolled.value!!
        assertEquals(listOf("2026-W44"), value.settledWeeks)
        assertEquals(listOf("2026-W45"), value.appendedWeeks, "整门课回未安排桶后，要能被本周重新收走")
        assertEquals(7, value.appendedUnits)
        assertEquals(listOf("2026-W45"), value.scheduledWeeks)
        assertEquals(7, value.missed)
        assertEquals(7, value.carried, "本周承诺已经收走，结算时按「结转」算，不报欠账")
        assertEquals(0, value.carryPending)
        assertEquals(0, value.poolRemaining)

        val w45 = svc.week(planId, "2026-W45")
        assertEquals(7, w45.commitment.units.size)
        assertEquals(7, w45.days.sumOf { it.items.size })
        assertEquals(emptyList(), svc.pool(planId))
        assertEquals(emptyList(), svc.overdue(planId, "2026-11-02").thisWeek)
    }
}
