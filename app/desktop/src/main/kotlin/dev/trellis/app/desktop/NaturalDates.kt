package dev.trellis.app.desktop

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 从一句人话里认出日期。
 *
 * 只认「相对今天很明确」的说法：今天/明天/后天、周几、N 天后、M 月 D 日。
 * 认不出就返回 null，绝不猜「下个月那阵子」——猜错的日期比没有日期更糟。
 *
 * 同时把认出来的那一段从标题里剪掉（「周三前把方案发出去」→「把方案发出去」），
 * 剪完为空的标题保留原文，免得用户只写了「明天」时任务名变成空字符串。
 */
internal data class NaturalDate(
    val title: String,
    val date: LocalDate?,
)

private val DAY_OFFSETS = mapOf(
    "今天" to 0,
    "明天" to 1,
    "后天" to 2,
    "大后天" to 3,
)

private val WEEKDAYS = mapOf(
    "周一" to DayOfWeek.MONDAY,
    "周二" to DayOfWeek.TUESDAY,
    "周三" to DayOfWeek.WEDNESDAY,
    "周四" to DayOfWeek.THURSDAY,
    "周五" to DayOfWeek.FRIDAY,
    "周六" to DayOfWeek.SATURDAY,
    "周日" to DayOfWeek.SUNDAY,
    "周天" to DayOfWeek.SUNDAY,
    "星期一" to DayOfWeek.MONDAY,
    "星期二" to DayOfWeek.TUESDAY,
    "星期三" to DayOfWeek.WEDNESDAY,
    "星期四" to DayOfWeek.THURSDAY,
    "星期五" to DayOfWeek.FRIDAY,
    "星期六" to DayOfWeek.SATURDAY,
    "星期日" to DayOfWeek.SUNDAY,
    "星期天" to DayOfWeek.SUNDAY,
)

/** 「N 天后」「N 周后」。故意只收 1..365，别让人写出「999 天后」。 */
private val IN_DAYS = Regex("(\\d{1,3})\\s*天后")
private val IN_WEEKS = Regex("(\\d{1,2})\\s*周后")
private val MONTH_DAY = Regex("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?")
private val SLASH_DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})\\b")

/**
 * 解析结果里的日期一律是「当天」；写进任务时由调用方换算成当天 23:59:59。
 */
internal fun parseNaturalDate(text: String, today: LocalDate): NaturalDate {
    val raw = text.trim()
    if (raw.isEmpty()) return NaturalDate(raw, null)

    // 1. 今天/明天/后天/大后天（长的先匹配：「大后天」不能被子串「后天」抢走）
    for ((word, offset) in DAY_OFFSETS.entries.sortedByDescending { it.key.length }) {
        val index = raw.indexOf(word)
        if (index >= 0) {
            val date = today.plusDays(offset.toLong())
            return NaturalDate(cut(raw, index, word.length), date)
        }
    }

    // 2. 周几 / 下周几 / 本周几
    for ((word, day) in WEEKDAYS) {
        val index = raw.indexOf(word)
        if (index >= 0) {
            val prefix = raw.substring(0, index)
            val nextWeek = prefix.endsWith("下")
            val thisWeek = prefix.endsWith("本") || prefix.endsWith("这")
            val base = when {
                nextWeek || thisWeek -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .plusWeeks(if (nextWeek) 1 else 0)
                else -> today
            }
            var target = base.with(TemporalAdjusters.nextOrSame(day))
            // 「周三」说在今天之后最近的那个周三；今天正好是周三时就是今天。
            if (!nextWeek && !thisWeek && target.isBefore(today)) target = target.plusWeeks(1)
            return NaturalDate(cut(raw, index, word.length), target)
        }
    }

    // 3. N 天后 / N 周后
    IN_DAYS.find(raw)?.let { match ->
        val days = match.groupValues[1].toIntOrNull() ?: return@let
        if (days in 1..365) {
            return NaturalDate(cut(raw, match.range.first, match.value.length), today.plusDays(days.toLong()))
        }
    }
    IN_WEEKS.find(raw)?.let { match ->
        val weeks = match.groupValues[1].toIntOrNull() ?: return@let
        if (weeks in 1..52) {
            return NaturalDate(cut(raw, match.range.first, match.value.length), today.plusWeeks(weeks.toLong()))
        }
    }

    // 4. M 月 D 日 / M/D
    fun monthDay(month: Int, day: Int): LocalDate? = runCatching { LocalDate.of(today.year, month, day) }.getOrNull()

    MONTH_DAY.find(raw)?.let { match ->
        val month = match.groupValues[1].toIntOrNull() ?: return@let
        val day = match.groupValues[2].toIntOrNull() ?: return@let
        val date = monthDay(month, day) ?: return@let
        // 只写月日、已经过去 → 指明年。
        val adjusted = if (date.isBefore(today)) monthDay(month, day)?.plusYears(1) ?: date else date
        return NaturalDate(cut(raw, match.range.first, match.value.length), adjusted)
    }
    SLASH_DATE.find(raw)?.let { match ->
        val month = match.groupValues[1].toIntOrNull() ?: return@let
        val day = match.groupValues[2].toIntOrNull() ?: return@let
        val date = monthDay(month, day) ?: return@let
        val adjusted = if (date.isBefore(today)) monthDay(month, day)?.plusYears(1) ?: date else date
        return NaturalDate(cut(raw, match.range.first, match.value.length), adjusted)
    }

    return NaturalDate(raw, null)
}

/** 剪掉一段，顺手把首尾多余的空格、冒号、逗号收掉；「周三前」的「前」一起剪。 */
private fun cut(text: String, index: Int, length: Int): String {
    val end = if (text.startsWith("前", index + length)) index + length + 1 else index + length
    val kept = (text.substring(0, index) + text.substring(end))
        .replace(Regex("^[\\s，,：:、]+"), "")
        .replace(Regex("[\\s，,：:、]+$"), "")
        .trim()
    return kept.ifBlank { text.trim() }
}
