package dev.trellis.feature.learning

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * 日期/周的口径只在服务端算：UI 拿到的是字符串，不自己推日期。
 *
 * - 日：`2026-10-28`
 * - 周：ISO 8601 `2026-W44`（周一为一周之始）
 * - 时间戳：带偏移的 ISO 8601，展示时区由注入的 [ZoneId] 决定
 */
internal object PlanTime {

    val ISO_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    private val WEEK_FIELD: WeekFields = WeekFields.ISO
    private val WEEKDAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun now(clock: () -> Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    fun isoTime(atMillis: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(atMillis).atZone(zone).format(ISO_INSTANT)

    fun isoWeek(date: LocalDate): String {
        val week = date.get(WEEK_FIELD.weekOfWeekBasedYear())
        val year = date.get(WEEK_FIELD.weekBasedYear())
        return "%04d-W%02d".format(year, week)
    }

    fun weekStart(week: String): LocalDate {
        val year = week.substring(0, 4).toInt()
        val number = week.substring(6).toInt()
        val jan4 = LocalDate.of(year, 1, 4)
        val week1Monday = jan4.with(WEEK_FIELD.dayOfWeek(), 1)
        return week1Monday.plusWeeks((number - 1).toLong())
    }

    fun weekEnd(week: String): LocalDate = weekStart(week).plusDays(6)

    fun weekLabel(week: String): String {
        val start = weekStart(week)
        val end = weekEnd(week)
        return "%02d.%02d – %02d.%02d".format(
            start.monthValue, start.dayOfMonth, end.monthValue, end.dayOfMonth,
        )
    }

    fun dayLabel(day: LocalDate): String =
        WEEKDAY_CN[day.dayOfWeek.value - 1] + " · " + "${day.monthValue} 月 ${day.dayOfMonth} 日"

    fun weekdayLabel(day: LocalDate): String = WEEKDAY_CN[day.dayOfWeek.value - 1]

    fun nextWeek(week: String): String = isoWeek(weekStart(week).plusWeeks(1))

    fun isWeekEnd(day: LocalDate): Boolean = day.dayOfWeek == DayOfWeek.SUNDAY

    fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()

    fun isWeekFormat(week: String): Boolean = Regex("^\\d{4}-W\\d{2}$").matches(week)
}
