package dev.trellis.app.desktop

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanRearrangerTest {

    private val monday = LocalDate.parse("2026-11-09")

    private fun item(key: String, minutes: Int, order: Int, day: String? = null): RearrangeItem =
        RearrangeItem(key, key, RearrangeItemKind.Occurrence, day, minutes, order)

    private fun unit(key: String, minutes: Int, order: Int): RearrangeItem =
        RearrangeItem(key, key, RearrangeItemKind.Unit, null, minutes, order)

    @Test
    fun overdueIsPutFirstAndTodaysBlocksMoveAsOnePiece() {
        // 今天 90 分钟（60+30 加时）：3 条逾期 30 分钟正好填满；今天原安排整块后移。
        val plan = buildRearrangePlan(
            overdue = listOf(item("a", 30, 0, "2026-11-09")),
            backlog = listOf(unit("u1", 30, 1), unit("u2", 30, 2)),
            scheduled = mapOf(
                monday to listOf(item("t1", 30, 100, "2026-11-09"), item("t2", 30, 101, "2026-11-09")),
                monday.plusDays(1) to listOf(item("w1", 30, 200, "2026-11-10")),
            ),
            startDay = monday,
            dailyMinutes = 60,
            extraMinutes = 30,
        )
        assertEquals(listOf("a", "u1", "u2"), plan.assignments.take(3).map { it.item.key })
        assertEquals("2026-11-09", plan.assignments.first().day)
        // 今天原有的两条被整体挤到第二天最前面，第二天原来的 w1 继续后移。
        assertEquals(
            listOf("t1", "t2"),
            plan.assignments.drop(3).take(2).map { it.item.key },
        )
        assertEquals("2026-11-10", plan.assignments[3].day)
        assertTrue(plan.moves.any { it.key == "t1" && it.fromDay == "2026-11-09" && it.toDay == "2026-11-10" })
    }

    @Test
    fun overflowGoesToNextWeekWithoutLosingOrder() {
        val plan = buildRearrangePlan(
            overdue = listOf(item("a", 60, 0, "2026-11-09")),
            backlog = (1..7).map { unit("u$it", 60, it) },
            scheduled = mapOf(
                monday.plusDays(5) to listOf(item("sat1", 60, 300, "2026-11-14")),
                monday.plusDays(6) to listOf(item("sun1", 60, 301, "2026-11-15")),
            ),
            startDay = monday,
            dailyMinutes = 60,
            extraMinutes = 0,
        )
        val days = plan.assignments.map { it.day }
        // 逾期和欠账各占一天，周六、周日的原安排被推迟到下一周。
        assertEquals(listOf("2026-11-09", "2026-11-10"), days.take(2))
        assertTrue(plan.moves.first { it.key == "sat1" }.toDay > "2026-11-15")
        assertTrue(plan.moves.first { it.key == "sun1" }.toDay > "2026-11-15")
        assertTrue(plan.overflowCount >= 2)
    }

    @Test
    fun stageOrderIsPreservedAcrossBacklogAndOverdue() {
        val plan = buildRearrangePlan(
            overdue = listOf(item("late", 30, 5, "2026-11-08")),
            backlog = listOf(unit("early", 30, 1), unit("middle", 30, 3)),
            scheduled = emptyMap(),
            startDay = monday,
            dailyMinutes = 90,
            extraMinutes = 0,
        )
        assertEquals(listOf("early", "middle", "late"), plan.assignments.map { it.item.key })
    }

    @Test
    fun singleItemBiggerThanCapacityStillGetsADay() {
        val plan = buildRearrangePlan(
            overdue = listOf(item("big", 120, 0, "2026-11-08")),
            backlog = emptyList(),
            scheduled = emptyMap(),
            startDay = monday,
            dailyMinutes = 30,
            extraMinutes = 0,
        )
        assertEquals("2026-11-09", plan.assignments.single().day)
        assertEquals(1, plan.moves.size)
    }

    @Test
    fun nothingToMoveWhenThereIsNoOverdue() {
        val plan = buildRearrangePlan(
            overdue = emptyList(),
            backlog = emptyList(),
            scheduled = mapOf(monday to listOf(item("t1", 30, 0, "2026-11-09"))),
            startDay = monday,
            dailyMinutes = 60,
            extraMinutes = 0,
        )
        assertTrue(plan.moves.isEmpty())
        assertEquals(0, plan.overflowCount)
    }

    @Test
    fun skipsEmptyDaysWhenNothingIsCarried() {
        val plan = buildRearrangePlan(
            overdue = emptyList(),
            backlog = emptyList(),
            scheduled = mapOf(monday.plusDays(4) to listOf(item("f1", 30, 0, "2026-11-13"))),
            startDay = monday,
            dailyMinutes = 60,
            extraMinutes = 0,
        )
        assertEquals("2026-11-13", plan.assignments.single().day)
        assertTrue(plan.moves.isEmpty())
    }
}
