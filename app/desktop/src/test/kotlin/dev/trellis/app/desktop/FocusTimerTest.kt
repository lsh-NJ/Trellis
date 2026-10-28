package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusTimerTest {
    @Test
    fun theClockNeverShowsMoreDigitsThanNeeded() {
        assertEquals("00:00", formatClock(0))
        assertEquals("00:59", formatClock(59))
        assertEquals("01:05", formatClock(65))
        assertEquals("59:59", formatClock(3599))
        assertEquals("1:00:00", formatClock(3600))
        assertEquals("1:02:05", formatClock(3725))
    }

    @Test
    fun aNegativeClockIsTreatedAsZero() {
        assertEquals("00:00", formatClock(-5))
    }

    @Test
    fun onlyTheCountdownHasProgress() {
        assertEquals(0.25f, timerProgress(15, 60, FocusMode.Countdown), 0.0001f)
        assertEquals(1f, timerProgress(120, 60, FocusMode.Countdown), 0.0001f)
        // 正计时没有总时长，也就没有进度条——这条规则写死在模型里，界面只是照着画。
        assertEquals(0f, timerProgress(90, 0, FocusMode.Stopwatch))
        assertEquals(0f, timerProgress(90, 120, FocusMode.Stopwatch))
        assertEquals(0f, timerProgress(10, 0, FocusMode.Countdown))
    }

    @Test
    fun theCountdownStopsAtZeroAndMarksItselfFinished() {
        val first = advanceTimer(elapsedSeconds = 10, seconds = 5, totalSeconds = 20, mode = FocusMode.Countdown)
        assertEquals(15, first.elapsedSeconds)
        assertFalse(first.finished)

        val last = advanceTimer(elapsedSeconds = 15, seconds = 5, totalSeconds = 20, mode = FocusMode.Countdown)
        assertEquals(20, last.elapsedSeconds)
        assertTrue(last.finished)
        assertEquals(0, remainingSeconds(last.elapsedSeconds, 20))

        // 走过头的也不会变成负数。
        val past = advanceTimer(elapsedSeconds = 20, seconds = 7, totalSeconds = 20, mode = FocusMode.Countdown)
        assertEquals(20, past.elapsedSeconds)
        assertTrue(past.finished)
    }

    @Test
    fun theStopwatchJustKeepsCounting() {
        var elapsed = 0
        repeat(120) { elapsed = advanceTimer(elapsed, 1, 0, FocusMode.Stopwatch).elapsedSeconds }
        assertEquals(120, elapsed)
        var finished = false
        repeat(60) { finished = advanceTimer(elapsed, 1, 0, FocusMode.Stopwatch).finished }
        assertFalse(finished)
    }

    @Test
    fun loggedMinutesRoundUpSoShortSessionsStillCount() {
        assertEquals(0, minutesToLog(0))
        assertEquals(1, minutesToLog(1))
        assertEquals(1, minutesToLog(60))
        assertEquals(2, minutesToLog(61))
        assertEquals(61, minutesToLog(3661))
    }

    @Test
    fun pomodoroLengthStaysInAReasonableRange() {
        assertEquals(5, clampTimerMinutes(1))
        assertEquals(5, clampTimerMinutes(0))
        assertEquals(25, clampTimerMinutes(25))
        assertEquals(180, clampTimerMinutes(999))
    }
}
