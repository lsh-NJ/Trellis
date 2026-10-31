package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.PlanDiff
import dev.trellis.domain.plan.SplitPart
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/**
 * 按 `design/plan-system-backend.md` §13 的里程碑写的验收：
 * P0 垂直切片、P1 移动/拆分/缩短/撤销、P2 结算与复盘、并发与不变量。
 *
 * 时间用注入时钟，不 sleep：走 2026-10-26（周一）→ 11-01（周日）→ 11-02（下周一）。
 */
class LearningPlanServiceTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var nowMillis: Long = at("2026-10-26T08:00")

    private fun at(local: String): Long = OffsetDateTime.parse("$local+08:00").toInstant().toEpochMilli()

    private fun moveClockTo(local: String) {
        nowMillis = at(local)
    }

    private fun service(): SqliteLearningPlanService =
        SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { nowMillis }, zone = zone)

    private fun env(planId: String? = null, expected: Long? = null, dryRun: Boolean = false): CommandEnvelope =
        CommandEnvelope(
            commandId = "cmd_" + UUID.randomUUID(),
            actor = Actor.USER,
            planId = planId,
            expectedLastSeq = expected,
            dryRun = dryRun,
            origin = "test",
        )

    private fun seedTwoWeekPlan(svc: SqliteLearningPlanService): Triple<String, String, String> {
        val created = svc.createProcess(env(), "把 TypeScript 学扎实", "能独立写一个全栈玩具项目")
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(
                UnitDraft("第 1 章", "类型与推断", 20, 10),
                UnitDraft("第 1 章", "泛型入门", 20, 10),
                UnitDraft("第 2 章", "模块与工程化", 25, 10),
            ),
            listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2)),
            ),
        )
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        return Triple(planId, draft.value!!.versionId, accepted.value!!.versionId)
    }

    @Test
    fun theDailyMinutesCommandIsAPlanPropertyThatDrivesLaterPacking() {
        val svc = service()

        // 计划 A：不设每天投入，老规矩一天一个。
        val plain = svc.createProcess(env(), "不设节奏", "一天一个")
        assertTrue(plain.ok, plain.error?.message)
        val plainId = plain.value!!.planId
        val plainDraft = svc.saveDraft(
            env(plainId),
            plainId,
            null,
            listOf(
                UnitDraft("第 1 章", "20 分钟", 20, 10),
                UnitDraft("第 1 章", "25 分钟", 25, 10),
            ),
            listOf(CommitmentDraft("2026-W44", listOf(0, 1))),
        )
        assertTrue(plainDraft.ok, plainDraft.error?.message)
        assertTrue(svc.acceptDraft(env(plainId), plainDraft.value!!.versionId, "2026-W44").ok)
        assertEquals(listOf("20 分钟"), svc.today(plainId, "2026-10-26").occurrences.map { it.title })
        assertEquals(listOf("25 分钟"), svc.today(plainId, "2026-10-27").occurrences.map { it.title })

        // 计划 B：先设每天 60 分钟，再铺天 → 20+25 塞进同一天。
        val paced = svc.createProcess(env(), "按自己的节奏", "每天 60")
        assertTrue(paced.ok, paced.error?.message)
        val pacedId = paced.value!!.planId
        assertTrue(svc.setPlanDailyMinutes(env(pacedId), pacedId, 60).ok)
        assertEquals(60, svc.planState(pacedId).dailyMinutes)
        val pacedDraft = svc.saveDraft(
            env(pacedId),
            pacedId,
            null,
            listOf(
                UnitDraft("第 1 章", "20 分钟", 20, 10),
                UnitDraft("第 1 章", "25 分钟", 25, 10),
            ),
            listOf(CommitmentDraft("2026-W44", listOf(0, 1))),
        )
        assertTrue(pacedDraft.ok, pacedDraft.error?.message)
        assertTrue(svc.acceptDraft(env(pacedId), pacedDraft.value!!.versionId, "2026-W44").ok)
        assertEquals(
            listOf("20 分钟", "25 分钟"),
            svc.today(pacedId, "2026-10-26").occurrences.map { it.title },
        )

        // 越界要报人话。
        val tooMany = svc.setPlanDailyMinutes(env(pacedId), pacedId, 600)
        assertFalse(tooMany.ok)
        assertEquals(ErrorCode.E_INVALID, tooMany.error?.code)
    }

    @Test
    fun daysArePackedUpToTheDailyBudget() {
        val svc = service()
        val created = svc.createProcess(
            env(),
            "按每天投入排",
            "验证一天能塞几个",
            weeklyCapacityMinutes = 60 * 7,
        )
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(
                UnitDraft("第 1 章", "20 分钟", 20, 10),
                UnitDraft("第 1 章", "25 分钟", 25, 10),
                UnitDraft("第 1 章", "30 分钟", 30, 10),
                UnitDraft("第 1 章", "35 分钟", 35, 10),
            ),
            listOf(CommitmentDraft("2026-W44", listOf(0, 1, 2, 3))),
        )
        assertTrue(draft.ok, draft.error?.message)
        val accepted = svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)

        // 每天 60 分钟：20+25 放第一天；再加 30 会超，所以 30 顺到第二天、35 顺到第三天。
        assertEquals(listOf("20 分钟", "25 分钟"), svc.today(planId, "2026-10-26").occurrences.map { it.title })
        assertEquals(listOf("30 分钟"), svc.today(planId, "2026-10-27").occurrences.map { it.title })
        assertEquals(listOf("35 分钟"), svc.today(planId, "2026-10-28").occurrences.map { it.title })
    }

    // ---------------------------------------------------------------- P0

    @Test
    fun theVerticalSliceCreatesSchedulesCompletesAndUndoes() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)

        val today = svc.today(planId, "2026-10-26")
        assertEquals(1, today.occurrences.size, "一天只排一个单元")
        assertEquals("今天一件事：类型与推断 · 20 分钟。", today.headline)
        assertEquals(1, svc.today(planId, "2026-10-27").occurrences.size, "第二个单元排在周二")

        val first = today.occurrences.first()
        val done = svc.completeOccurrence(env(planId), first.id, "读完了第一章")
        assertTrue(done.ok, done.error?.message)
        assertNotNull(done.undo, "完成之后 5 秒内应该能撤销")

        val afterDone = svc.today(planId, "2026-10-26")
        assertEquals(0, afterDone.occurrences.size)
        assertEquals(1, afterDone.completed.size)
        assertEquals("读完了第一章", afterDone.completed.first().note)

        val undone = svc.undoLast(env(planId), done.undo!!.token)
        assertTrue(undone.ok, undone.error?.message)
        val afterUndo = svc.today(planId, "2026-10-26")
        assertEquals(1, afterUndo.occurrences.size, "撤销后应该回到没完成")
        assertEquals(0, afterUndo.completed.size)
    }

    @Test
    fun aCompletedOccurrenceCanBeMarkedUndoneOnPurpose() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val first = svc.today(planId, "2026-10-26").occurrences.first()

        val done = svc.completeOccurrence(env(planId), first.id, null)
        assertTrue(done.ok, done.error?.message)
        assertEquals(0, svc.today(planId, "2026-10-26").occurrences.size)

        // 不靠 5 秒撤销窗口：点错了要随时能改回来。
        val back = svc.uncompleteOccurrence(env(planId), first.id)
        assertTrue(back.ok, back.error?.message)

        val afterUndone = svc.today(planId, "2026-10-26")
        assertEquals(1, afterUndone.occurrences.size, "应该回到没做")
        assertEquals(0, afterUndone.completed.size)
        assertEquals("planned", afterUndone.occurrences.first().status.toString())
    }

    @Test
    fun theSameCommandIdNeverWritesTwice() {
        val svc = service()
        val created = svc.createProcess(env(), "计划", "目标")
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId), planId, null,
            listOf(UnitDraft("第 1 章", "一", 20, 10)),
            listOf(CommitmentDraft("2026-W44", listOf(0))),
        )
        assertTrue(draft.ok, draft.error?.message)
        val acceptEnv = env(planId)
        val accepted = svc.acceptDraft(acceptEnv, draft.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        assertEquals(1, svc.today(planId, "2026-10-26").occurrences.size)
        val second = svc.acceptDraft(acceptEnv, draft.value!!.versionId, "2026-W44")
        assertTrue(second.ok && second.warnings.isNotEmpty(), "重复命令应返回提示而不是再写一次")
        assertEquals(1, svc.today(planId, "2026-10-26").occurrences.size)
    }

    @Test
    fun aStaleExpectedSeqIsRejectedWithConflict() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val latest = svc.latestSeq(planId)!!
        // 先让别人改一次
        val today = svc.today(planId, "2026-10-26")
        val result = svc.moveOccurrence(env(planId, expected = latest), today.occurrences.first().id, "2026-10-27", "忙")
        assertTrue(result.ok, result.error?.message)
        // 再用旧 seq 改：应冲突
        val other = svc.today(planId, "2026-10-27").occurrences.first { it.id != result.value!!.occurrenceId }
        val stale = svc.moveOccurrence(env(planId, expected = latest), other.id, "2026-10-28", "忙")
        assertFalse(stale.ok)
        assertEquals(ErrorCode.E_CONFLICT, stale.error?.code)
        assertEquals(svc.latestSeq(planId), stale.error?.latestSeq)
    }

    @Test
    fun aDryRunWritesNothing() {
        val svc = service()
        val created = svc.createProcess(env(), "计划", "目标")
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId, dryRun = true), planId, null,
            listOf(UnitDraft("第 1 章", "一", 20, 10)),
            listOf(CommitmentDraft("2026-W44", listOf(0))),
        )
        assertTrue(draft.ok)
        assertFalse(svc.planState(planId).pendingDraft, "dry-run 不应该留下草稿")
    }

    // ---------------------------------------------------------------- P1

    @Test
    fun movingAnOccurrenceKeepsItsIdentityAndCanBeUndone() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val original = svc.today(planId, "2026-10-26").occurrences.first()

        val moved = svc.moveOccurrence(env(planId), original.id, "2026-10-27", "今天加班")
        assertTrue(moved.ok, moved.error?.message)
        val newId = moved.value!!.occurrenceId
        assertNotEquals(original.id, newId, "顺延是新行，不是改旧行")
        assertEquals(0, svc.today(planId, "2026-10-26").occurrences.size, "原天不再有这一步")
        assertEquals(2, svc.today(planId, "2026-10-27").occurrences.size, "周二原有一步加上顺延来的一步")

        val undone = svc.undoLast(env(planId), moved.undo!!.token)
        assertTrue(undone.ok, undone.error?.message)
        assertEquals(1, svc.today(planId, "2026-10-26").occurrences.size)
        assertEquals(1, svc.today(planId, "2026-10-27").occurrences.size)
    }

    @Test
    fun splittingKeepsTheTotalAndTheUndoRestoresOneRow() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val target = svc.today(planId, "2026-10-26").occurrences.first()

        val split = svc.splitOccurrence(
            env(planId), target.id,
            listOf(SplitPart("2026-10-26", 10), SplitPart("2026-10-27", 10)),
        )
        assertTrue(split.ok, split.error?.message)
        assertEquals(2, split.value!!.size)
        assertEquals(1, svc.today(planId, "2026-10-26").occurrences.size)
        assertEquals(2, svc.today(planId, "2026-10-27").occurrences.size)

        // 日视图现在按路线索引排序，10-27 第一条是刚拆出来的 10 分钟片段；
        // 这里要挑原来 20 分钟那一步来验「拆分总量必须相等」。
        val twentyMinute = svc.today(planId, "2026-10-27").occurrences.first { it.minutes == 20 }
        val wrongTotal = svc.splitOccurrence(
            env(planId), twentyMinute.id,
            listOf(SplitPart("2026-10-27", 5), SplitPart("2026-10-28", 5)),
        )
        assertFalse(wrongTotal.ok)
        assertEquals(ErrorCode.E_INVALID, wrongTotal.error?.code)

        val undone = svc.undoLast(env(planId), split.undo!!.token)
        assertTrue(undone.ok, undone.error?.message)
        assertEquals(1, svc.today(planId, "2026-10-26").occurrences.size)
        assertEquals(1, svc.today(planId, "2026-10-27").occurrences.size, "拆开的两段应该换回原来的一行")
    }

    @Test
    fun shorteningAStepBelowTheMinimalVersionFlipsToMinimalMode() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val target = svc.today(planId, "2026-10-26").occurrences.first()
        val shortened = svc.shortenOccurrence(env(planId), target.id, 10)
        assertTrue(shortened.ok, shortened.error?.message)
        val row = svc.today(planId, "2026-10-26").occurrences.first()
        assertEquals(10, row.minutes)
        assertEquals("minimal", row.mode)
    }

    // ---------------------------------------------------------------- P2

    @Test
    fun settlingTwiceDoesNotDoubleCountAndCapsTheCarryAtTwo() {
        val svc = service()
        val created = svc.createProcess(env(), "计划", "目标")
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId), planId, null,
            listOf(
                UnitDraft("第 1 章", "一", 20, 10),
                UnitDraft("第 1 章", "二", 20, 10),
                UnitDraft("第 1 章", "三", 20, 10),
                UnitDraft("第 2 章", "四", 25, 10),
            ),
            listOf(
                CommitmentDraft("2026-W44", listOf(0, 1, 2)),
                CommitmentDraft("2026-W45", listOf(3)),
            ),
        )
        svc.acceptDraft(env(planId), draft.value!!.versionId, "2026-W44")

        moveClockTo("2026-11-01T20:00")
        val settled = svc.settleWeek(env(planId), planId, "2026-W44", "2026-11-01")
        assertTrue(settled.ok, settled.error?.message)
        assertEquals(3, settled.value!!.missedCount)
        assertEquals(2, settled.value!!.carriedCount, "结转最多 2 个")
        assertEquals(1, settled.value!!.pendingCarry)

        val again = svc.settleWeek(env(planId), planId, "2026-W44", "2026-11-01")
        assertTrue(again.ok)
        assertEquals(settled.value!!.missedCount, again.value!!.missedCount)
        assertTrue(again.warnings.isNotEmpty())

        val nextWeek = svc.week(planId, "2026-W45")
        val carried = nextWeek.days.flatMap { it.items }.filter { it.status == "planned" }
        assertEquals(2, carried.size, "W45 应该只有结转来的两步")
        assertTrue(carried.all { it.carried }, "这两步都标了结转")
    }

    @Test
    fun theReviewFreezesItsNumbersAndIsIdempotent() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val first = svc.today(planId, "2026-10-26").occurrences.first()
        svc.completeOccurrence(env(planId), first.id, null)

        moveClockTo("2026-11-02T09:00")
        val review = svc.generateReview(env(planId), planId, "2026-W44", "2026-11-02")
        assertTrue(review.ok, review.error?.message)
        val view = svc.review(planId, "2026-W44")
        assertEquals("ready", view.state)
        assertTrue(view.facts.any { it.label == "完成单元" && it.value == "1 / 2" }, "事实应固化完成 1/2")
        assertTrue(view.narrative.contains("1 / 2"))

        val second = svc.generateReview(env(planId), planId, "2026-W44", "2026-11-02")
        assertEquals(review.value!!.reviewId, second.value!!.reviewId)
        assertTrue(second.warnings.isNotEmpty())
    }

    @Test
    fun asOfStatisticsOnlyCountEventsUpToTheGivenSeq() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val first = svc.today(planId, "2026-10-26").occurrences
        val second = svc.today(planId, "2026-10-27").occurrences
        svc.completeOccurrence(env(planId), first[0].id, null)
        val afterFirst = svc.latestSeq(planId)!!
        svc.completeOccurrence(env(planId), second[0].id, null)

        assertEquals(1, svc.asOfCompletedCount(planId, "2026-W44", afterFirst))
        assertEquals(2, svc.asOfCompletedCount(planId, "2026-W44", svc.latestSeq(planId)!!))
    }

    @Test
    fun changingACommitmentWritesANewVersionAndKeepsTheOldOne() {
        val svc = service()
        val (planId, _, activeVersion) = seedTwoWeekPlan(svc)
        val week = svc.week(planId, "2026-W44")
        val unitIds = week.commitment.units.map { it.id }

        val changed = svc.changeCommitment(
            env(planId), activeVersion, "2026-W44", listOf(unitIds.first()),
            "这周只剩两个晚上",
            PlanDiff("这周少放一个单元", emptyList()),
        )
        assertTrue(changed.ok, changed.error?.message)
        val newVersion = changed.value!!.versionId
        assertNotEquals(activeVersion, newVersion)
        assertEquals(newVersion, svc.planState(planId).currentVersion)
        assertEquals(1, svc.week(planId, "2026-W44").commitment.units.size)

        val undone = svc.undoLast(env(planId), changed.undo!!.token)
        assertTrue(undone.ok, undone.error?.message)
        assertEquals(activeVersion, svc.planState(planId).currentVersion)
        assertEquals(2, svc.week(planId, "2026-W44").commitment.units.size)
    }

    // ---------------------------------------------------------------- 不变量

    @Test
    fun theEventLogRejectsUpdatesAndDeletes() {
        val db = PlanDatabase.inMemory()
        val svc = SqliteLearningPlanService(db, clock = { nowMillis }, zone = zone)
        svc.createProcess(env(), "计划", "目标")
        val statement = db.connection.createStatement()
        assertFailsWith<java.sql.SQLException> { statement.executeUpdate("UPDATE event SET type = 'hacked'") }
        assertFailsWith<java.sql.SQLException> { statement.executeUpdate("DELETE FROM event") }
    }

    @Test
    fun anActiveVersionCannotBeRewritten() {
        val db = PlanDatabase.inMemory()
        val svc = SqliteLearningPlanService(db, clock = { nowMillis }, zone = zone)
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val statement = db.connection.createStatement()
        assertFailsWith<java.sql.SQLException> {
            statement.executeUpdate("UPDATE plan_unit SET title = '改了' WHERE plan_version_id = (SELECT current_version_id FROM plan WHERE id = '$planId')")
        }
    }

    @Test
    fun anOccurrenceIdentityCannotBeMovedByUpdate() {
        val db = PlanDatabase.inMemory()
        val svc = SqliteLearningPlanService(db, clock = { nowMillis }, zone = zone)
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val occurrence = svc.today(planId, "2026-10-26").occurrences.first()
        val statement = db.connection.createStatement()
        assertFailsWith<java.sql.SQLException> {
            statement.executeUpdate("UPDATE occurrence SET day = '2026-12-01' WHERE id = '${occurrence.id}'")
        }
    }

    @Test
    fun exportingTheLogProducesOneJsonPerEventAndASnapshot() {
        val svc = service()
        val (planId, _, _) = seedTwoWeekPlan(svc)
        val lines = svc.exportJsonl(planId).lines()
        assertTrue(lines.size > 3)
        assertTrue(lines.first().contains("\"entity\""))
        assertTrue(lines.last().contains("\"type\":\"snapshot\""))
    }
}
