package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.StageDraft
import dev.trellis.domain.plan.StageTopicDraft
import dev.trellis.domain.plan.UnitDraft
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 2026-11-18：阶段与周解绑 + 阶段主题清单。
 *
 * 验两件事：
 * 1. 阶段可以不写 ISO 周区间，只带主题（主题体量按天）；
 * 2. 阶段进度按「阶段 · 主题」的 chapter 前缀聚合（主题全做完才算完成，没展开不算）。
 */
class PlanStageTopicsTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val nowMillis: Long = OffsetDateTime.parse("2026-11-18T08:00+08:00").toInstant().toEpochMilli()

    private fun env(planId: String? = null): CommandEnvelope =
        CommandEnvelope(commandId = "cmd_" + UUID.randomUUID(), actor = Actor.USER, planId = planId, origin = "test")

    private fun service(): SqliteLearningPlanService =
        SqliteLearningPlanService(PlanDatabase.inMemory(), clock = { nowMillis }, zone = zone)

    private fun newPlan(svc: SqliteLearningPlanService): String {
        // 空壳计划：建出来就有生效版本，能直接写阶段（和计划工作流第二段同一条路）。
        val created = svc.createShell(env(), "操作系统复习", "能自己讲清五层结构", null)
        assertTrue(created.ok, created.error?.message)
        return created.value!!.planId
    }

    @Test
    fun stagesCanBeTopicOnlyAndProgressAggregatesByStageTopicChapter() = runTest {
        val svc = service()
        val planId = newPlan(svc)
        val written = svc.setStages(
            env(planId),
            planId,
            listOf(
                StageDraft(
                    name = "进程与线程",
                    focus = "进程模型",
                    topics = listOf(
                        StageTopicDraft("进程状态", 2),
                        StageTopicDraft("PCB 与进程控制", 2),
                    ),
                ),
                StageDraft(name = "内存管理", topics = listOf(StageTopicDraft("分页与页表", 3))),
            ),
        )
        assertTrue(written.ok, written.error?.message)

        val appended = svc.appendUnits(
            env(planId),
            planId,
            listOf(
                UnitDraft("进程与线程 · 进程状态", "进程的五状态", 30, 10),
                UnitDraft("进程与线程 · PCB 与进程控制", "PCB 组成", 30, 10),
            ),
        )
        assertTrue(appended.ok, appended.error?.message)

        val stages = svc.stages(planId)
        assertEquals(2, stages.size, stages.toString())
        assertNull(stages[0].weekStart, "新阶段不写周区间")
        assertNull(stages[0].weekEnd)
        assertEquals(listOf("进程状态", "PCB 与进程控制"), stages[0].topics.map { it.name })
        assertEquals(listOf(2, 2), stages[0].topics.map { it.days })
        assertEquals(2, stages[0].total, "阶段下已知节点数")
        assertEquals(0, stages[0].done)
        assertEquals(1, stages[0].topics[0].total, "主题下的节点数")
        assertFalse(stages[0].topics[0].complete, "还没做完")
        assertFalse(stages[0].complete)
        assertEquals(0, stages[1].total, "没展开的阶段 total = 0")
        assertFalse(stages[1].complete, "没展开不等于完成")
    }

    @Test
    fun topicsRejectBadBodySizesAndDuplicates() {
        val svc = service()
        val planId = newPlan(svc)
        val tooBig = svc.setStages(
            env(planId),
            planId,
            listOf(StageDraft(name = "阶段A", topics = listOf(StageTopicDraft("太长了", 9)))),
        )
        assertFalse(tooBig.ok)
        assertEquals(ErrorCode.E_INVALID, tooBig.error?.code)

        val duplicated = svc.setStages(
            env(planId),
            planId,
            listOf(
                StageDraft(
                    name = "阶段A",
                    topics = listOf(StageTopicDraft("进程状态", 2), StageTopicDraft("进程状态", 3)),
                ),
            ),
        )
        assertFalse(duplicated.ok)
        assertEquals(ErrorCode.E_INVALID, duplicated.error?.code)
    }
    /**
     * 旧库迁移：老 plan_stage 的周列是 NOT NULL，打开时应该重建表（可空）并建好主题表。
     * 2026-11-18 之前的用户库都是这个形状。
     */
    @Test
    fun oldStageTableWithNotNullWeeksIsRebuiltOnOpen() {
        val dir = Files.createTempDirectory("plan-migrate").toFile()
        val path = File(dir, "plan.db").toPath()
        Sqlite.open(path.toString()).use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    "CREATE TABLE plan_stage (id TEXT PRIMARY KEY, " +
                        "plan_version_id TEXT NOT NULL REFERENCES plan_version(id), name TEXT NOT NULL, " +
                        "week_start TEXT NOT NULL CHECK (week_start GLOB '[0-9][0-9][0-9][0-9]-W[0-9][0-9]'), " +
                        "week_end TEXT NOT NULL CHECK (week_end GLOB '[0-9][0-9][0-9][0-9]-W[0-9][0-9]'), " +
                        "focus TEXT NOT NULL DEFAULT '', sort_order INTEGER NOT NULL, created_at TEXT NOT NULL)",
                )
            }
        }
        PlanDatabase.open(path).use { db ->
            val notNull = mutableMapOf<String, Int>()
            db.connection.createStatement().use { st ->
                st.executeQuery("PRAGMA table_info(plan_stage)").use { rs ->
                    while (rs.next()) notNull[rs.getString("name").lowercase()] = rs.getInt("notnull")
                }
            }
            assertEquals(0, notNull["week_start"], "周区间要改成可空")
            val tables = mutableSetOf<String>()
            db.connection.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='table'").use { rs ->
                    while (rs.next()) tables += rs.getString("name")
                }
            }
            assertTrue("plan_stage_topic" in tables, "要建好主题表：" + tables)
        }
        dir.deleteRecursively()
    }
}
