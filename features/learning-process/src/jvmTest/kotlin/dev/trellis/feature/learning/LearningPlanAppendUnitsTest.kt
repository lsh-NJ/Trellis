package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.UnitDraft
import java.sql.SQLException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 计划文档同步：`appendUnits` 给当前生效版本追加单元（INSERT-only）。
 *
 * 时间用注入时钟，不 sleep：走 2026-10-26（周一，W44）。
 */
class LearningPlanAppendUnitsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private var nowMillis: Long = at("2026-10-26T08:00")

    private fun at(local: String): Long = OffsetDateTime.parse("$local+08:00").toInstant().toEpochMilli()

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

    private fun draft(title: String, minutes: Int = 20, chapter: String = "第 1 章"): UnitDraft =
        UnitDraft(chapter, title, minutes, 10)

    /**
     * 一个已生效的计划：4 个单元，前 3 个承诺到 W44/W45，第 4 个「待安排资料」留在池里，
     * 这样追加前后的池子变化看得清。
     */
    private fun seedAcceptedPlan(svc: SqliteLearningPlanService): String {
        val created = svc.createProcess(env(), "把 TypeScript 学扎实", "能独立写一个全栈玩具项目")
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId
        val saved = svc.saveDraft(
            env(planId),
            planId,
            null,
            listOf(
                UnitDraft("第 1 章", "类型与推断", 20, 10),
                UnitDraft("第 1 章", "泛型入门", 20, 10),
                UnitDraft("第 2 章", "模块与工程化", 25, 10),
                UnitDraft("第 2 章", "待安排资料", 30, 10),
            ),
            listOf(
                CommitmentDraft("2026-W44", listOf(0, 1)),
                CommitmentDraft("2026-W45", listOf(2)),
            ),
        )
        assertTrue(saved.ok, saved.error?.message)
        val accepted = svc.acceptDraft(env(planId), saved.value!!.versionId, "2026-W44")
        assertTrue(accepted.ok, accepted.error?.message)
        return planId
    }

    @Test
    fun appendingToAnAcceptedVersionAddsPoolUnitsWithoutSchedulingThem() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)
        val versionId = svc.currentVersion(planId)!!
        // 前置：追加的目标版本不是 draft —— 下面的 INSERT 成功本身就验证了触发器只拦 UPDATE/DELETE。
        assertEquals("active", svc.versionStatus(versionId))
        assertEquals(listOf("待安排资料"), svc.pool(planId).map { it.title })
        val todayBefore = svc.today(planId, "2026-10-26").occurrences.map { it.title }

        val result = svc.appendUnits(
            env(planId),
            planId,
            listOf(
                draft("计算机网络 3 节", 90),
                draft("物理 2 节", 40, chapter = ""),
            ),
        )
        assertTrue(result.ok, result.error?.message)
        val ids = result.value!!
        assertEquals(2, ids.size)
        assertEquals(2, ids.distinct().size)
        assertTrue(ids.all { it.startsWith("pu_") }, "单元 id 沿用 pu_ 前缀：$ids")
        assertEquals(versionId, svc.unitRow(ids[0])!!.versionId)
        assertEquals(versionId, svc.unitRow(ids[1])!!.versionId)

        val pool = svc.pool(planId)
        assertEquals(listOf("待安排资料", "计算机网络 3 节", "物理 2 节"), pool.map { it.title })
        // 90 分钟被列约束夹到 45；40 原样保留；空章节记成「未分类」。
        assertEquals(45, pool[1].estimateMinutes)
        assertEquals(40, pool[2].estimateMinutes)
        assertEquals("未分类", pool[2].chapter)

        // 追加不自动铺天：今天还是原来那条。
        assertEquals(todayBefore, svc.today(planId, "2026-10-26").occurrences.map { it.title })
    }

    @Test
    fun appendingTwiceKeepsGrowingPastTheExistingSortOrder() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)

        val first = svc.appendUnits(env(planId), planId, listOf(draft("计算机网络 3 节", 40)))
        assertTrue(first.ok, first.error?.message)
        val second = svc.appendUnits(env(planId), planId, listOf(draft("物理 2 节", 35), draft("数据结构实验", 30)))
        assertTrue(second.ok, second.error?.message)

        assertEquals(
            listOf("待安排资料", "计算机网络 3 节", "物理 2 节", "数据结构实验"),
            svc.pool(planId).map { it.title },
        )
        // sort_order 接在已有单元（0..3）后面，不从头再来、不撞 UNIQUE。
        assertTrue(svc.unitSortOrder(first.value!!.single()) < svc.unitSortOrder(second.value!![0]))
        assertTrue(svc.unitSortOrder(second.value!![0]) < svc.unitSortOrder(second.value!![1]))
    }

    @Test
    fun appendedUnitsAreInsertOnlyAndCannotBeUndone() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)
        val result = svc.appendUnits(env(planId), planId, listOf(draft("计算机网络 3 节", 90)))
        assertTrue(result.ok, result.error?.message)
        val unitId = result.value!!.single()

        // 非草稿版本的 plan_unit 有 no_rewrite / no_delete 触发器：只允许 INSERT，没有可撤销的路径。
        val update = assertFailsWith<SQLException> {
            svc.connection.prepareStatement("UPDATE plan_unit SET estimate_minutes = 30 WHERE id = ?").use { ps ->
                ps.setString(1, unitId)
                ps.executeUpdate()
            }
        }
        assertTrue(update.message!!.contains("immutable"), "更新应被触发器拦下：${update.message}")
        val delete = assertFailsWith<SQLException> {
            svc.connection.prepareStatement("DELETE FROM plan_unit WHERE id = ?").use { ps ->
                ps.setString(1, unitId)
                ps.executeUpdate()
            }
        }
        assertTrue(delete.message!!.contains("immutable"), "删除应被触发器拦下：${delete.message}")

        // 上面两条失败没有动到数据。
        assertEquals(45, svc.unitRow(unitId)!!.estimateMinutes)
    }

    @Test
    fun appendRejectsEmptyTooManyAndBlankTitledDrafts() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)

        val empty = svc.appendUnits(env(planId), planId, emptyList())
        assertFalse(empty.ok)
        assertEquals(ErrorCode.E_INVALID, empty.error?.code)

        val tooMany = svc.appendUnits(env(planId), planId, List(51) { draft("条目 ${it + 1}") })
        assertFalse(tooMany.ok)
        assertEquals(ErrorCode.E_INVALID, tooMany.error?.code)

        val blank = svc.appendUnits(env(planId), planId, listOf(draft("   ")))
        assertFalse(blank.ok)
        assertEquals(ErrorCode.E_INVALID, blank.error?.code)

        // 校验失败没有写坏池子：还是只有 seed 的那一条。
        assertEquals(listOf("待安排资料"), svc.pool(planId).map { it.title })
    }

    @Test
    fun aPlanWithoutAnAcceptedVersionCannotAppendUnits() = runTest {
        val svc = service()
        val created = svc.createProcess(env(), "还没开始", "先建个计划")
        assertTrue(created.ok, created.error?.message)
        val planId = created.value!!.planId

        val result = svc.appendUnits(env(planId), planId, listOf(draft("条目")))
        assertFalse(result.ok)
        assertEquals(ErrorCode.E_STATE, result.error?.code)
        assertTrue(result.error!!.message.contains("生效的版本"))
    }

    @Test
    fun anEndedPlanCannotAppendUnits() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)
        val ended = svc.endPlan(env(planId), planId, "不学了")
        assertTrue(ended.ok, ended.error?.message)

        val result = svc.appendUnits(env(planId), planId, listOf(draft("条目")))
        assertFalse(result.ok)
        assertEquals(ErrorCode.E_STATE, result.error?.code)
        assertTrue(result.error!!.message.contains("结束了"))
    }

    @Test
    fun aMissingPlanIsReportedAsNotFound() = runTest {
        val svc = service()
        val result = svc.appendUnits(env("pln_missing"), "pln_missing", listOf(draft("条目")))
        assertFalse(result.ok)
        assertEquals(ErrorCode.E_NOT_FOUND, result.error?.code)
    }

    @Test
    fun dryRunValidatesWithoutWritingAnything() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)
        val poolBefore = svc.pool(planId)

        val dry = svc.appendUnits(
            env(planId, dryRun = true),
            planId,
            listOf(draft("计算机网络 3 节", 90)),
        )
        assertTrue(dry.ok, dry.error?.message)
        assertEquals(1, dry.value!!.size)
        assertTrue(dry.warnings.isNotEmpty(), "dry-run 要提示没有落库")
        assertEquals(poolBefore, svc.pool(planId))
        assertNull(svc.unitRow(dry.value!!.single()))
    }

    @Test
    fun anAppendedUnitCanBeScheduledFromThePool() = runTest {
        val svc = service()
        val planId = seedAcceptedPlan(svc)
        val result = svc.appendUnits(env(planId), planId, listOf(draft("计算机网络 3 节", 45)))
        assertTrue(result.ok, result.error?.message)
        val unitId = result.value!!.single()

        val placed = svc.placeUnit(env(planId), planId, "2026-W44", unitId, "2026-10-30")
        assertTrue(placed.ok, placed.error?.message)
        assertEquals(
            listOf("计算机网络 3 节"),
            svc.today(planId, "2026-10-30").occurrences.map { it.title },
        )
        // 排进天之后就从池子里出去了。
        assertEquals(listOf("待安排资料"), svc.pool(planId).map { it.title })
    }
}
