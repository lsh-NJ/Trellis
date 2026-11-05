package dev.trellis.app.desktop

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 管家建议的规则：只动少量、只放在本周剩下的天、没有可动的就不建议。 */
class StewardProposalsTest {

    // 2026-11-03 是周二；本周周日是 11-08。
    private val today = LocalDate.parse("2026-11-03")
    private val weekEnd = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))

    private fun overdue(id: String, day: String = "2026-11-02") = StewardOverdueItem(id, "课时$id", day)
    private fun backlog(id: String) = StewardBacklogItem(id, "欠账$id")

    @Test
    fun movesAtMostThreeOverdueItemsToTheRestOfTheWeek() {
        val proposal = StewardProposals.build(
            today = today,
            weekEnd = weekEnd,
            overdue = listOf(overdue("1"), overdue("2"), overdue("3"), overdue("4")),
            backlog = emptyList(),
            id = "p1",
        )
        assertNotNull(proposal)
        assertEquals(3, proposal.changes.size)
        assertEquals(
            listOf("2026-11-04", "2026-11-05", "2026-11-06"),
            proposal.changes.map { it.day },
        )
        assertTrue(proposal.changes.all { it is StewardChange.Move }, "逾期条目只该产生 Move")
        assertTrue(proposal.title.contains("3 件"), proposal.title)
        assertTrue(proposal.detail.contains("本周还有 4 件没做"), proposal.detail)
    }

    @Test
    fun placesAtMostTwoBacklogUnits() {
        val proposal = StewardProposals.build(
            today = today,
            weekEnd = weekEnd,
            overdue = emptyList(),
            backlog = listOf(backlog("a"), backlog("b"), backlog("c")),
            id = "p2",
        )
        assertNotNull(proposal)
        assertEquals(2, proposal.changes.size)
        assertTrue(proposal.changes.all { it is StewardChange.Place })
        assertEquals(listOf("2026-11-04", "2026-11-05"), proposal.changes.map { it.day })
        assertTrue(proposal.title.contains("2 个"), proposal.title)
    }

    @Test
    fun nothingToDoMeansNoProposal() {
        assertNull(StewardProposals.build(today, weekEnd, emptyList(), emptyList(), "p3"))
    }

    @Test
    fun sundayFallsBackToToday() {
        val sunday = LocalDate.parse("2026-11-08")
        val proposal = StewardProposals.build(
            today = sunday,
            weekEnd = sunday,
            overdue = listOf(overdue("1", day = "2026-11-01")),
            backlog = emptyList(),
            id = "p4",
        )
        assertNotNull(proposal)
        assertEquals("2026-11-08", proposal.changes.single().day)
    }

    @Test
    fun itemsAlreadyOnTheTargetDayAreSkipped() {
        // 两条都已经在「明天/后天」上：没有可挪的，就不建议。
        assertNull(
            StewardProposals.build(
                today = today,
                weekEnd = weekEnd,
                overdue = listOf(overdue("1", day = "2026-11-04"), overdue("2", day = "2026-11-05")),
                backlog = emptyList(),
                id = "p5",
            ),
        )
    }
}
