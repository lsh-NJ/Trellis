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
 * 计划自己往前滚（周层节奏引擎）的验收。
 *
 * 时间用注入时钟，不 sleep：W44（2026-10-26 周一）建计划，假时钟推到 W45 / W46 / W47，
 * 验证「一周过后下一周 / 下下周一还能正常安排」——这正是之前在真机上没验证过的那一段。
 */
class LearningPlanRhythmTest {

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

    /**
     * 建一份计划并生效：默认每周投入 60 分钟 × 7 天 = 420 分钟（`dailyMinutes = 60`）。
     * 返回 planId。
     */
    private fun seed(
        svc: SqliteLearningPlanService,
        units: List<UnitDraft>,
        commitments: List<CommitmentDraft>,
    ): String {
        val created = svc.createProcess(
            env(), "测试计划", "把这一段学完", weeklyCapacityMinutes = 60 * 7,
        )
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(env(planId), planId, null, units, commitments)
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, commitments.first().week)
        assertTrue(accepted.ok, accepted.error?.message)
        return planId
    }

    @Test
    fun thePlanRollsForwardWeekByWeek() {
        val svc = service()
        val planId = seed(
            svc,
            units = List(6) { unit("U${it + 1}") },
            commitments = listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2, 3)),
            ),
        )

        // W44 第一天完成一个，剩下的没做完 → 到 W45 应该有 missed + 结转。
        val w44 = svc.today(planId, "2026-10-26").occurrences
        assertEquals(2, w44.size, "两分钟数之和 40 ≤ 60，应该排在同一天")
        svc.completeOccurrence(env(planId), w44.first().id, null)

        moveClockTo("2026-11-02T09:00")
        val first = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(first.ok, first.error?.message)
        val rolled = first.value!!
        assertEquals(listOf("2026-W44"), rolled.settledWeeks)
        assertEquals(listOf("2026-W44"), rolled.reviewedWeeks)
        assertEquals(listOf("2026-W46"), rolled.appendedWeeks)
        assertEquals(2, rolled.appendedUnits, "池里剩下两个单元应该全部排进 W46")
        assertEquals(listOf("2026-W45"), rolled.scheduledWeeks)
        assertEquals(1, rolled.missed)
        assertEquals(1, rolled.carried)
        assertEquals(0, rolled.poolRemaining)
        assertTrue(rolled.note.contains("上周完成 1/2"), rolled.note)

        assertEquals(
            listOf("2026-W44", "2026-W45", "2026-W46"),
            svc.map(planId).weeks.map { it.week },
            "追加下周承诺不能把旧周从总览里弄丢",
        )
        val w45 = svc.week(planId, "2026-W45")
        assertEquals(2, w45.commitment.units.size)
        assertEquals(3, w45.days.sumOf { it.items.size }, "W45 = 2 个本单元 + 1 个结转")
        assertEquals(2, svc.week(planId, "2026-W46").commitment.units.size)
        assertEquals("ready", svc.review(planId, "2026-W44").state)

        // 同一个周再跑一遍：不能重复铺、不能重复写事件。
        val seqBefore = svc.latestSeq(planId)
        val again = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(again.ok, again.error?.message)
        assertEquals(emptyList<String>(), again.value!!.appendedWeeks)
        assertEquals(emptyList<String>(), again.value!!.scheduledWeeks)
        assertEquals(seqBefore, svc.latestSeq(planId), "没有实际动作时不该写事件")

        // 第二周过去后，W46 也要能正常铺（W45 的未完成最多结转 2 个到 W46）。
        moveClockTo("2026-11-09T09:00")
        val second = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(second.ok, second.error?.message)
        val rolled2 = second.value!!
        assertEquals(listOf("2026-W45"), rolled2.settledWeeks)
        assertEquals(listOf("2026-W46"), rolled2.scheduledWeeks)
        assertEquals(2, rolled2.carried)
        assertEquals(1, rolled2.carryPending)
        val w46 = svc.week(planId, "2026-W46")
        assertEquals(4, w46.days.sumOf { it.items.size }, "W46 = 2 个本单元 + 2 个结转")
        assertEquals(4, w46.days.flatMap { it.items }.map { it.occurrenceId }.distinct().size)

        // 再往后：没承诺也没池子，只结算、报告「路线走完」，不建空承诺。
        moveClockTo("2026-11-16T09:00")
        val third = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(third.ok, third.error?.message)
        assertEquals(emptyList<String>(), third.value!!.appendedWeeks)
        assertEquals(emptyList<String>(), third.value!!.scheduledWeeks)
        assertTrue(third.value!!.note.contains("路线里的单元已经全部排进周计划"), third.value!!.note)
    }

    @Test
    fun aCarriedOccurrenceDoesNotBlockTheRestOfTheWeek() {
        val svc = service()
        // W44 三个都没做 → 到 W45 结转 2 个、第 3 个记 pending；
        // W45 自己还有一个单元，以前「承诺里已经有行」会让它永远不铺。
        val planId = seed(
            svc,
            units = List(4) { unit("U${it + 1}") },
            commitments = listOf(
                CommitmentDraft("2026-W44", listOf(0, 1, 2)),
                CommitmentDraft("2026-W45", listOf(3)),
            ),
        )

        moveClockTo("2026-11-02T09:00")
        val result = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(result.ok, result.error?.message)
        val rolled = result.value!!
        assertEquals(3, rolled.missed)
        assertEquals(2, rolled.carried)
        assertEquals(1, rolled.carryPending)
        assertEquals(listOf("2026-W45"), rolled.scheduledWeeks)

        val w45 = svc.week(planId, "2026-W45")
        assertEquals(1, w45.commitment.units.size)
        assertEquals(3, w45.days.sumOf { it.items.size }, "2 个结转 + W45 自己的 1 个都要铺")
        assertTrue(w45.days.flatMap { it.items }.any { it.title == "U4" }, "W45 自己的单元不能被结转挤掉")
    }

    @Test
    fun sundayAdvancePreSchedulesNextWeekWithoutDuplicatingOnMonday() {
        val svc = service()
        val planId = seed(
            svc,
            units = List(3) { unit("U${it + 1}") },
            commitments = listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2)),
            ),
        )

        // W44 周日：提前把 W45 铺到天。
        moveClockTo("2026-11-01T10:00")
        val sunday = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(sunday.ok, sunday.error?.message)
        assertEquals(listOf("2026-W45"), sunday.value!!.scheduledWeeks)
        assertEquals(1, svc.week(planId, "2026-W45").days.sumOf { it.items.size })

        // 周一再跑：W45 已经铺过，不能出现第二份。
        moveClockTo("2026-11-02T09:00")
        val monday = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(monday.ok, monday.error?.message)
        assertEquals(emptyList<String>(), monday.value!!.scheduledWeeks, "已经铺过的周不该重铺")
        val w45 = svc.week(planId, "2026-W45")
        assertEquals(3, w45.days.sumOf { it.items.size }, "2 个结转 + 1 个本单元")
        assertEquals(
            3,
            w45.days.flatMap { it.items }.map { it.occurrenceId }.distinct().size,
            "同一单元不能出现两条 occurrence",
        )
    }

    @Test
    fun changingACommitmentKeepsTheOtherWeeks() {
        val svc = service()
        val planId = seed(
            svc,
            units = List(3) { unit("U${it + 1}") },
            commitments = listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2)),
            ),
        )
        val versionId = svc.planState(planId).currentVersion!!
        val w44Units = svc.week(planId, "2026-W44").commitment.units

        val changed = svc.changeCommitment(
            env(planId), versionId, "2026-W44", listOf(w44Units.first().id), "这周只剩一个晚上",
        )
        assertTrue(changed.ok, changed.error?.message)
        assertEquals(1, svc.week(planId, "2026-W44").commitment.units.size)
        assertEquals(
            listOf("2026-W44", "2026-W45"),
            svc.map(planId).weeks.map { it.week },
            "改本周承诺不能把下周弄丢",
        )
        assertEquals(1, svc.week(planId, "2026-W45").commitment.units.size)
        assertEquals("U3", svc.week(planId, "2026-W45").commitment.units.first().title)
    }

    @Test
    fun theWeeklyPickIsCappedAtSevenUnits() {
        val svc = service()
        // 池里 10 个 30 分钟的单元：周预算 60×7×80% = 336 分钟，按耗时能放 11 个，
        // 但一周最多 7 个（再多也做不完）。
        val planId = seed(
            svc,
            units = List(12) { unit("U${it + 1}", minutes = 30) },
            commitments = listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2)),
            ),
        )
        moveClockTo("2026-11-02T09:00")
        val result = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(result.ok, result.error?.message)
        assertEquals(7, result.value!!.appendedUnits)
        assertEquals(2, result.value!!.poolRemaining, "12 个单元里 3 个已承诺、7 个排进下周，池里剩 2 个")
    }

    @Test
    fun aLongAbsenceStillRollsForwardInsteadOfStalling() {
        val svc = service()
        // 只在 W44 建过承诺，然后两周没打开应用：本周（W46）本来没有承诺。
        // 引擎要先把本周从池子里补出来，再结算旧周/铺天，而不是停住。
        val planId = seed(
            svc,
            units = List(5) { unit("U${it + 1}") },
            commitments = listOf(CommitmentDraft("2026-W44", listOf(0, 1))),
        )
        moveClockTo("2026-11-09T09:00")
        val result = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(result.ok, result.error?.message)
        val rolled = result.value!!
        assertEquals(listOf("2026-W44"), rolled.settledWeeks)
        assertEquals(listOf("2026-W46"), rolled.appendedWeeks)
        assertEquals(listOf("2026-W46"), rolled.scheduledWeeks)
        assertEquals(0, rolled.poolRemaining)
        assertEquals(3, svc.week(planId, "2026-W46").commitment.units.size)
        assertEquals(3, svc.week(planId, "2026-W46").days.sumOf { it.items.size })
        assertEquals("ready", svc.review(planId, "2026-W44").state)
    }

    @Test
    fun aPausedPlanDoesNotAdvance() {
        val svc = service()
        val planId = seed(
            svc,
            units = listOf(unit("U1")),
            commitments = listOf(CommitmentDraft("2026-W44", listOf(0))),
        )
        assertTrue(svc.pausePlan(env(planId), planId, "先停一周").ok)
        moveClockTo("2026-11-09T09:00")
        val result = svc.advanceRhythm(scheduler(planId), planId)
        assertTrue(result.ok, result.error?.message)
        assertTrue(result.value!!.note.contains("暂停"), result.value!!.note)
        assertEquals(emptyList<String>(), result.value!!.settledWeeks)
    }
}
