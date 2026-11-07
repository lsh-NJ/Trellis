package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.StageDraft
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 阶段（`plan_stage`）：一段连续周 + 一句重点，进度由它覆盖到的承诺单元现算。
 *
 * 阶段不是新的排期单位 —— 它只是给「第 1–2 周打基础」这种说法一个落点，
 * 所以 done/total 必须从 occurrence 状态推出来，而不是另外记一份计数（拖拽/撤销会让计数失真）。
 */
class LearningPlanStageTest {

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

    /** W44 两个单元、W45 一个单元；W44 周一（2026-10-26）接受，W44 的单元当天铺进日历。 */
    private fun seed(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(env(), "阶段测试计划", "把这一门课学完", weeklyCapacityMinutes = 60 * 7)
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
    fun stageProgressCountsCommittedUnitsInItsWeekRange() {
        val svc = service()
        val planId = seed(svc)

        val set = svc.setStages(
            env(planId),
            planId,
            listOf(
                StageDraft("打基础", "2026-W44", "2026-W44", focus = "把类型系统吃透"),
                StageDraft("进阶", "2026-W45", "2026-W45"),
            ),
        )
        assertTrue(set.ok, set.error?.message)
        assertEquals(2, set.value, "返回写入的阶段数")

        val stages = svc.stages(planId)
        assertEquals(listOf("打基础", "进阶"), stages.map { it.name })
        assertEquals("2026-W44", stages[0].weekStart)
        assertEquals("2026-W44", stages[0].weekEnd)
        assertEquals("把类型系统吃透", stages[0].focus)
        assertEquals(2, stages[0].total, "W44 承诺了 2 个单元")
        assertEquals(0, stages[0].done)
        assertEquals(1, stages[1].total, "W45 承诺了 1 个单元")
        assertEquals(0, stages[1].done)

        // 完成 W44 的一个单元：阶段进度跟着 occurrence 状态走。
        val day = svc.today(planId, "2026-10-26")
        val first = day.occurrences.first { it.unitId != null }
        val done = svc.completeOccurrence(env(planId), first.id, null)
        assertTrue(done.ok, done.error?.message)

        val after = svc.stages(planId)
        assertEquals(1, after[0].done, "完成一个 → 阶段进度 1/2")
        assertEquals(2, after[0].total)
        assertEquals(0, after[1].done, "另一个阶段不受影响")
    }

    @Test
    fun setStagesValidatesFormatRangeAndDuplicateNames() {
        val svc = service()
        val planId = seed(svc)
        fun stage(name: String, start: String, end: String) = StageDraft(name, start, end)

        assertEquals(
            ErrorCode.E_INVALID,
            svc.setStages(env(planId), planId, listOf(stage("A", "2026-44", "2026-W45"))).error?.code,
            "周格式不对要拒绝",
        )
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setStages(env(planId), planId, listOf(stage("A", "2026-W45", "2026-W44"))).error?.code,
            "起始周晚于结束周要拒绝",
        )
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setStages(env(planId), planId, listOf(stage("A", "2026-W44", "2026-W46"))).error?.code,
            "区间超出承诺的周要拒绝",
        )
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setStages(
                env(planId),
                planId,
                listOf(stage("A", "2026-W44", "2026-W44"), stage("A", "2026-W45", "2026-W45")),
            ).error?.code,
            "重名要拒绝",
        )
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setStages(env(planId), planId, listOf(stage("  ", "2026-W44", "2026-W44"))).error?.code,
            "空名字要拒绝",
        )
        assertTrue(svc.stages(planId).isEmpty(), "校验失败时一条都不该落库")
    }

    @Test
    fun shellPlansWithoutCommitmentsMayUseAnyWeekRangeAndReplacementReplaces() {
        val svc = service()
        val shell = svc.createShell(env(), "空壳计划", "把线性代数补完", null)
        assertTrue(shell.ok, shell.error?.message)
        val planId = shell.value!!.planId

        // 空壳计划还没有承诺任何周：阶段区间不跟承诺比，只校验格式与先后。
        val set = svc.setStages(
            env(planId),
            planId,
            listOf(
                StageDraft("下半程", "2026-W49", "2026-W52", sortOrder = 2),
                StageDraft("上半程", "2026-W44", "2026-W48", sortOrder = 1),
            ),
        )
        assertTrue(set.ok, set.error?.message)
        assertEquals(listOf("上半程", "下半程"), svc.stages(planId).map { it.name }, "按 sortOrder 排")
        assertEquals(0, svc.stages(planId).first().total, "还没排单元，进度是 0/0")

        // 再设一次是整表替换，不是追加。
        val replaced = svc.setStages(env(planId), planId, listOf(StageDraft("只剩一个", "2026-W44", "2026-W50")))
        assertTrue(replaced.ok, replaced.error?.message)
        assertEquals(1, svc.stages(planId).size)
        assertEquals("只剩一个", svc.stages(planId).single().name)
    }
}
