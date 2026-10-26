package dev.trellis.app.desktop

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 自然语言日期：只测「相对今天」的确定性，不测猜日期。
 * 固定 today = 2026-10-24（周六），所以每个用例都能手算出期望值。
 */
class NaturalDatesTest {
    private val today = LocalDate.of(2026, 10, 24)

    @Test
    fun todayTomorrowAndTheDayAfterAreOffsetDays() {
        assertEquals(today, parseNaturalDate("今天写完周报", today).date)
        assertEquals(today.plusDays(1), parseNaturalDate("明天给甲方回电话", today).date)
        assertEquals(today.plusDays(2), parseNaturalDate("后天体检", today).date)
        assertEquals(today.plusDays(3), parseNaturalDate("大后天复盘", today).date)
    }

    @Test
    fun theDateWordsAreCutOutOfTheTitle() {
        assertEquals("给甲方回电话", parseNaturalDate("明天给甲方回电话", today).title)
        assertEquals("把方案发出去", parseNaturalDate("周三前把方案发出去", today).title)
        assertEquals("交周报", parseNaturalDate("3 天后交周报", today).title)
    }

    @Test
    fun aTitleThatIsOnlyADateFallsBackToTheOriginalText() {
        assertEquals("明天", parseNaturalDate("明天", today).title)
        assertEquals(today.plusDays(1), parseNaturalDate("明天", today).date)
    }

    @Test
    fun weekdaysMeanTheNextOneNotThePastOne() {
        // 2026-10-24 是周六：下一个周二是 10-27，下一个周三是 10-28。
        assertEquals(LocalDate.of(2026, 10, 27), parseNaturalDate("周二开会", today).date)
        assertEquals(LocalDate.of(2026, 10, 28), parseNaturalDate("周三前把方案发出去", today).date)
        // 今天就是周六，说周六指的是今天。
        assertEquals(today, parseNaturalDate("周六跑步", today).date)
    }

    @Test
    fun nextWeekCountsFromMonday() {
        // 下周一是 10-26，下周三 10-28。
        assertEquals(LocalDate.of(2026, 10, 26), parseNaturalDate("下周一交材料", today).date)
        assertEquals(LocalDate.of(2026, 10, 28), parseNaturalDate("下周三评审", today).date)
    }

    @Test
    fun inNDaysAndInNWeeksWork() {
        assertEquals(today.plusDays(5), parseNaturalDate("5 天后复查", today).date)
        assertEquals(today.plusWeeks(2), parseNaturalDate("2 周后交论文", today).date)
        // 超出范围的数字不认，避免写出「999 天后」这种任务。
        assertNull(parseNaturalDate("999 天后再说", today).date)
    }

    @Test
    fun monthDayAndSlashDatesWork() {
        assertEquals(LocalDate.of(2026, 11, 3), parseNaturalDate("11月3日考试", today).date)
        assertEquals(LocalDate.of(2026, 11, 3), parseNaturalDate("11/3 考试", today).date)
        // 只写月日、但今年已经过去 → 指明年。
        assertEquals(LocalDate.of(2027, 1, 5), parseNaturalDate("1月5日交报告", today).date)
    }

    @Test
    fun aSentenceWithoutADateKeepsEverything() {
        val parsed = parseNaturalDate("把方案发出去", today)
        assertNull(parsed.date)
        assertEquals("把方案发出去", parsed.title)
    }
}
