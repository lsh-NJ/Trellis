package dev.trellis.app.desktop

import dev.trellis.domain.plan.CommitmentUnitView
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.PoolUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanNodesTest {
    private fun plan(id: String, title: String, status: String = "active") =
        LearningPlanInfo(id = id, title = title, status = status)

    private fun unit(
        id: String,
        title: String,
        chapter: String = "第一章",
        minutes: Int = 20,
        state: String = "this_week",
    ) = CommitmentUnitView(
        id = id,
        title = title,
        chapter = chapter,
        estimateMinutes = minutes,
        state = state,
        stateLabel = state,
    )

    private fun pool(id: String, title: String, chapter: String = "第二章", minutes: Int = 30) =
        PoolUnit(id = id, title = title, chapter = chapter, estimateMinutes = minutes)

    @Test
    fun 每个没结束的计划各成一组() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代"), plan("p2", "英语")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { id, _ -> if (id == "p1") listOf(unit("u1", "矩阵")) else listOf(unit("u2", "听力")) },
            poolOf = { emptyList() },
        )

        assertEquals(2, groups.size)
        assertEquals("p1", groups[0].planId)
        assertEquals("线代", groups[0].planTitle)
        assertEquals("矩阵", groups[0].nodes.single().title)
        assertEquals("英语", groups[1].planTitle)
    }

    @Test
    fun 结束的计划不出现在待办里() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代", status = "ended"), plan("p2", "英语", status = "paused")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { id, _ -> listOf(unit("u-$id", "节点 " + id)) },
            poolOf = { emptyList() },
        )

        assertEquals(listOf("p2"), groups.map { it.planId })
    }

    @Test
    fun 本周已经做完的单元不摊到待办() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { _, _ ->
                listOf(
                    unit("u1", "矩阵", state = "done"),
                    unit("u2", "行列式", state = "this_week"),
                )
            },
            poolOf = { emptyList() },
        )

        assertEquals(listOf("行列式"), groups.single().nodes.map { it.title })
    }

    @Test
    fun 节点池的单元排在周承诺之后并标成待安排() {
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { _, _ -> listOf(unit("u1", "矩阵")) },
            poolOf = { listOf(pool("u9", "特征值")) },
        ).single()

        assertEquals(listOf("矩阵", "特征值"), group.nodes.map { it.title })
        assertEquals(listOf(true, false), group.nodes.map { it.scheduled })
        assertEquals("第二章", group.nodes[1].chapter)
        assertEquals(30, group.nodes[1].minutes)
    }

    @Test
    fun 超过上限只留前十条但总数是真实值() {
        val units = (1..12).map { unit("u" + it, "节点 " + it, minutes = 10) }
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { _, _ -> units },
            poolOf = { emptyList() },
        ).single()

        assertEquals(PLAN_NODES_PER_PLAN, group.nodes.size)
        assertEquals(12, group.total)
        assertEquals(2, group.hidden)
        assertEquals(100, group.minutes)
        assertEquals("节点 1", group.nodes.first().title)
    }

    @Test
    fun 没有任何节点的计划不出组() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代"), plan("p2", "英语")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { id, _ -> if (id == "p2") listOf(unit("u2", "听力")) else emptyList() },
            poolOf = { emptyList() },
        )

        assertEquals(listOf("p2"), groups.map { it.planId })
    }

    @Test
    fun 拿不到当前周时只看节点池() {
        var askedWeek = 0
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { null },
            weekUnitsOf = { _, _ -> askedWeek++; listOf(unit("u1", "矩阵")) },
            poolOf = { listOf(pool("u9", "特征值")) },
        ).single()

        assertEquals(0, askedWeek)
        assertEquals(listOf("特征值"), group.nodes.map { it.title })
        assertTrue(group.nodes.single().scheduled.not())
    }

    @Test
    fun 每行都带所属计划的名字() {
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { _, _ -> listOf(unit("u1", "矩阵")) },
            poolOf = { listOf(pool("u9", "特征值")) },
        ).single()

        assertTrue(group.nodes.all { it.planTitle == "线代" })
    }

    @Test
    fun 排到具体某天的节点带上那一次_occurrence() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { "2026-W44" },
            weekUnitsOf = { _, _ -> listOf(unit("u1", "矩阵"), unit("u2", "行列式")) },
            poolOf = { listOf(pool("u9", "还没排的")) },
            occurrenceOf = { planId, week, unitId ->
                if (planId == "p1" && week == "2026-W44" && unitId == "u1") "occ_1" else null
            },
        )

        val nodes = groups.single().nodes
        assertEquals("occ_1", nodes[0].occurrenceId)
        assertEquals(null, nodes[1].occurrenceId)
        assertEquals(null, nodes[2].occurrenceId)
    }

    @Test
    fun 待安排的节点不查_occurrence() {
        var asked = 0
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            currentWeekOf = { null },
            weekUnitsOf = { _, _ -> error("没有当前周就不该查承诺") },
            poolOf = { listOf(pool("u9", "还没排的")) },
            occurrenceOf = { _, _, _ ->
                asked++
                "occ_x"
            },
        )

        val node = groups.single().nodes.single()
        assertEquals(0, asked)
        assertEquals(false, node.scheduled)
        assertEquals(null, node.occurrenceId)
    }

}
