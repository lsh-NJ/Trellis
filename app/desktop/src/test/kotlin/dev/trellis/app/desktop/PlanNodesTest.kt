package dev.trellis.app.desktop

import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.OpenNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanNodesTest {
    private fun plan(id: String, title: String, status: String = "active") =
        LearningPlanInfo(id = id, title = title, status = status)

    private fun node(
        occurrenceId: String,
        title: String,
        day: String,
        chapter: String = "第一章",
        minutes: Int = 20,
    ) = OpenNode(
        occurrenceId = occurrenceId,
        title = title,
        chapter = chapter,
        minutes = minutes,
        day = day,
    )

    @Test
    fun 今天到期和之前逾期都摊出来() {
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            today = "2026-10-28",
            openNodesOf = {
                listOf(
                    node("o1", "矩阵", "2026-10-27"),
                    node("o2", "行列式", "2026-10-28"),
                )
            },
        ).single()

        assertEquals(listOf("行列式", "矩阵"), group.nodes.map { it.title })
        assertEquals(listOf(0, 1), group.nodes.map { it.daysLate })
    }

    @Test
    fun 逾期的按最近的在前() {
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            today = "2026-10-28",
            openNodesOf = {
                listOf(
                    node("o1", "上周三", "2026-10-21"),
                    node("o2", "昨天", "2026-10-27"),
                    node("o3", "今天", "2026-10-28"),
                    node("o4", "前天", "2026-10-26"),
                )
            },
        ).single()

        assertEquals(listOf("今天", "昨天", "前天", "上周三"), group.nodes.map { it.title })
    }

    @Test
    fun 逾期天数按排到的那一天算() {
        val row = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            today = "2026-10-28",
            openNodesOf = { listOf(node("o1", "矩阵", "2026-10-21")) },
        ).single().nodes.single()

        assertEquals(7, row.daysLate)
        assertEquals("2026-10-21", row.day)
        assertTrue(row.today.not())
    }

    @Test
    fun 今天排到的算今天() {
        val row = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            today = "2026-10-28",
            openNodesOf = { listOf(node("o1", "矩阵", "2026-10-28")) },
        ).single().nodes.single()

        assertEquals(0, row.daysLate)
        assertTrue(row.today)
    }

    @Test
    fun 结束的计划不出现在待办里() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代", status = "ended"), plan("p2", "英语", status = "paused")),
            today = "2026-10-28",
            openNodesOf = { id -> listOf(node("o-" + id, "节点 " + id, "2026-10-28")) },
        )

        assertEquals(listOf("p2"), groups.map { it.planId })
    }

    @Test
    fun 没有任何节点的计划不出组() {
        val groups = planNodeGroups(
            plans = listOf(plan("p1", "线代"), plan("p2", "英语")),
            today = "2026-10-28",
            openNodesOf = { id ->
                if (id == "p2") listOf(node("o2", "听力", "2026-10-28")) else emptyList()
            },
        )

        assertEquals(listOf("p2"), groups.map { it.planId })
    }

    @Test
    fun 超过上限只留前十条但总数是真实值() {
        val nodes = (1..12).map { node("o" + it, "节点 " + it, "2026-10-28", minutes = 10) }
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            today = "2026-10-28",
            openNodesOf = { nodes },
        ).single()

        assertEquals(PLAN_NODES_PER_PLAN, group.nodes.size)
        assertEquals(12, group.total)
        assertEquals(2, group.hidden)
        assertEquals(100, group.minutes)
        assertEquals("节点 1", group.nodes.first().title)
    }

    @Test
    fun 每行都带所属计划的名字和那一次_occurrence() {
        val group = planNodeGroups(
            plans = listOf(plan("p1", "线代")),
            today = "2026-10-28",
            openNodesOf = { listOf(node("occ_1", "矩阵", "2026-10-28", chapter = "第三章", minutes = 30)) },
        ).single()

        val row = group.nodes.single()
        assertEquals("p1", row.planId)
        assertEquals("线代", row.planTitle)
        assertEquals("occ_1", row.occurrenceId)
        assertEquals("第三章", row.chapter)
        assertEquals(30, row.minutes)
    }
}
