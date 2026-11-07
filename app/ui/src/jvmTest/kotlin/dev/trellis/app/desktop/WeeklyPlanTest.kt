package dev.trellis.app.desktop

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「每周自动排周计划」的时间判断：周日 18:00 后或周一，同一周只跑一次。 */
class WeeklyPlanTest {

    private val sunday = LocalDate.of(2026, 11, 8)
    private val monday = LocalDate.of(2026, 11, 9)
    private val tuesday = LocalDate.of(2026, 11, 10)

    @Test
    fun disabledNeverRuns() {
        assertFalse(shouldRunWeeklyPlan(enabled = false, sunday, hour = 20, lastWeek = null))
        assertFalse(shouldRunWeeklyPlan(enabled = false, monday, hour = 9, lastWeek = null))
    }

    @Test
    fun sundayWaitsUntilTheEvening() {
        assertFalse(shouldRunWeeklyPlan(enabled = true, sunday, hour = 17, lastWeek = null))
        assertTrue(shouldRunWeeklyPlan(enabled = true, sunday, hour = 18, lastWeek = null))
        assertTrue(shouldRunWeeklyPlan(enabled = true, sunday, hour = 23, lastWeek = null))
    }

    @Test
    fun mondayRunsAnyTime() {
        assertTrue(shouldRunWeeklyPlan(enabled = true, monday, hour = 0, lastWeek = null))
        assertTrue(shouldRunWeeklyPlan(enabled = true, monday, hour = 9, lastWeek = null))
        assertTrue(shouldRunWeeklyPlan(enabled = true, monday, hour = 23, lastWeek = null))
    }

    @Test
    fun otherDaysDoNotRun() {
        assertFalse(shouldRunWeeklyPlan(enabled = true, tuesday, hour = 9, lastWeek = null))
    }

    @Test
    fun theSameWeekRunsOnlyOnce() {
        // 周日 11-08 属于 W45：W45 已经跑过，就不再跑。
        assertFalse(shouldRunWeeklyPlan(enabled = true, sunday, hour = 20, lastWeek = "2026-W45"))
        // 周一 11-09 属于 W46：W46 已经跑过，就不再跑。
        assertFalse(shouldRunWeeklyPlan(enabled = true, monday, hour = 9, lastWeek = "2026-W46"))
        // 上一周（W45）跑过，周一（W46）就轮到这一周。
        assertTrue(shouldRunWeeklyPlan(enabled = true, monday, hour = 9, lastWeek = "2026-W45"))
    }
}
