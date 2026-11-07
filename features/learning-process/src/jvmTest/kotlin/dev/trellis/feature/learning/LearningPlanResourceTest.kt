package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.ResourceDraft
import dev.trellis.domain.plan.UnitDraft
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 单元资料（`unit_resource`）：一个单元挂几条可点的视频/网页/文档。
 *
 * 资料挂在单元上、视图里跟 occurrence 一起带出来：当天列表和本周视图都直接能点，
 * 不需要 AI 再解释「第 1 集在哪」。替换是整批换，不做单条增删。
 */
class LearningPlanResourceTest {

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

    /** W44 两个单元（当天铺进日历）、W45 一个单元、1 个待安排单元。 */
    private fun seed(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(env(), "资料测试计划", "把这一门课学完", weeklyCapacityMinutes = 60 * 7)
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val draft = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(unit("A"), unit("B"), unit("C"), unit("待安排资料")),
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
    fun resourcesShowUpInDayAndWeekViewsAndReplacementClearsOldOnes() {
        val svc = service()
        val planId = seed(svc)
        val weekUnits = svc.week(planId, "2026-W44").commitment.units
        val unitA = weekUnits.first()
        val unitB = weekUnits[1]

        val set = svc.setUnitResources(
            env(planId),
            planId,
            unitA.id,
            listOf(
                ResourceDraft(
                    kind = "bilibili",
                    title = "第 1 集",
                    url = "https://www.bilibili.com/video/BV1xx",
                    locator = "BV1xx",
                    source = "B 站",
                ),
            ),
        )
        assertTrue(set.ok, set.error?.message)
        assertEquals(1, set.value, "返回写入的资料条数")

        val occurrence = svc.today(planId, "2026-10-26").occurrences.first { it.unitId == unitA.id }
        assertEquals(1, occurrence.resources.size, "当天视图要带出资料")
        assertEquals("bilibili", occurrence.resources.single().kind)
        assertEquals("第 1 集", occurrence.resources.single().title)
        assertEquals("https://www.bilibili.com/video/BV1xx", occurrence.resources.single().url)
        assertEquals("BV1xx", occurrence.resources.single().locator)

        val refreshed = svc.week(planId, "2026-W44").commitment.units
        assertEquals(1, refreshed.first { it.id == unitA.id }.resources.size, "本周视图也带资料")
        assertTrue(refreshed.first { it.id == unitB.id }.resources.isEmpty(), "没设资料的单元是空列表")

        // 替换：旧资料整批换掉，不是追加。
        val replace = svc.setUnitResources(
            env(planId),
            planId,
            unitA.id,
            listOf(
                ResourceDraft("视频", "第 1 集"),
                ResourceDraft("pdf", "讲义", url = "https://example.com/notes.pdf"),
            ),
        )
        assertTrue(replace.ok, replace.error?.message)
        assertEquals(2, replace.value)
        val after = svc.today(planId, "2026-10-26").occurrences.first { it.unitId == unitA.id }.resources
        assertEquals(listOf("bilibili", "doc"), after.map { it.kind }, "外部写法归一后按写入顺序返回")
        assertEquals(listOf("第 1 集", "讲义"), after.map { it.title })

        // 空列表 = 清空该单元的资料。
        val cleared = svc.setUnitResources(env(planId), planId, unitA.id, emptyList())
        assertTrue(cleared.ok, cleared.error?.message)
        assertTrue(
            svc.today(planId, "2026-10-26").occurrences.first { it.unitId == unitA.id }.resources.isEmpty(),
        )
    }

    @Test
    fun resourceKindsAreNormalizedToTheFourAllowedValues() {
        val svc = service()
        val planId = seed(svc)
        val unitA = svc.week(planId, "2026-W44").commitment.units.first()

        val set = svc.setUnitResources(
            env(planId),
            planId,
            unitA.id,
            listOf(
                ResourceDraft("bili", "B 站合集"),
                ResourceDraft("链接", "官网"),
                ResourceDraft("文档", "讲义"),
                ResourceDraft("markdown", "笔记"),
                ResourceDraft("随便", "别的"),
            ),
        )
        assertTrue(set.ok, set.error?.message)
        val resources = svc.today(planId, "2026-10-26").occurrences.first { it.unitId == unitA.id }.resources
        assertEquals(listOf("bilibili", "web", "doc", "doc", "other"), resources.map { it.kind })
    }

    @Test
    fun rejectsUnknownUnitsOldVersionUnitsAndBadInput() {
        val svc = service()
        val planId = seed(svc)
        val oldUnit = svc.week(planId, "2026-W44").commitment.units.first()

        assertEquals(
            ErrorCode.E_NOT_FOUND,
            svc.setUnitResources(env(planId), planId, "unit_nope", listOf(ResourceDraft("doc", "x"))).error?.code,
        )
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setUnitResources(env(planId), planId, oldUnit.id, (1..21).map { ResourceDraft("doc", "资料 $it") }).error?.code,
            "一个单元最多 20 条资料",
        )
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setUnitResources(env(planId), planId, oldUnit.id, listOf(ResourceDraft("doc", " "))).error?.code,
            "资料必须有标题",
        )

        // 新开一版并接受：旧版本的单元不再接受资料（视图都按当前版本过滤）。
        val next = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(unit("A2")),
            listOf(CommitmentDraft("2026-W44", listOf(0))),
        )
        assertTrue(next.ok, next.error?.message)
        val accepted = svc.acceptDraft(env(planId), next.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        assertEquals(
            ErrorCode.E_INVALID,
            svc.setUnitResources(env(planId), planId, oldUnit.id, listOf(ResourceDraft("doc", "x"))).error?.code,
            "旧版本的单元要拒绝",
        )
    }
}
