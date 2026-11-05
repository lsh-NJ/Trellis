package dev.trellis.app.mobile

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 手机端的日期/时长文案。
 *
 * 只在这一层拼中文：领域层只认 epoch 毫秒与 `yyyy-MM-dd`，展示层负责把它变成
 * 「今天 20:00」。反过来做（领域层吐中文）会让时区和语言都变成领域层的责任。
 *
 * 所有带「现在」的函数都要求调用方传 `nowMs`：应用支持「模拟今天」，
 * 用 `System.currentTimeMillis()` 会让模拟时间下的文案自相矛盾。
 */

private val zone: ZoneId get() = ZoneId.systemDefault()

private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

fun startOfDayMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

fun parseDay(raw: String?): LocalDate? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** 「今天 20:00」/「明天」/「10月30日」。 */
fun dueLabel(dueAt: Long?, nowMs: Long): String {
    if (dueAt == null) return "没有日期"
    val today = dayOf(nowMs)
    val date = dayOf(dueAt)
    val time = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalTime().format(hhmm)
    val midnight = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalTime().toSecondOfDay() == 0
    return when {
        date == today -> if (midnight) "今天" else "今天 $time"
        date == today.plusDays(1) -> if (midnight) "明天" else "明天 $time"
        date == today.minusDays(1) -> "昨天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

/** 「逾期 2 天」/「刚逾期」。 */
fun overdueLabel(dueAt: Long, nowMs: Long): String {
    val diff = nowMs - dueAt
    if (diff <= 0) return ""
    val hours = diff / 3_600_000L
    return when {
        hours < 1 -> "刚逾期"
        hours < 24 -> "逾期 $hours 小时"
        else -> "逾期 ${hours / 24} 天"
    }
}

/** 「1 小时 20 分」/「45 分」/「—」。 */
fun minutesLabel(minutes: Int): String = when {
    minutes <= 0 -> "—"
    minutes < 60 -> "$minutes 分"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "${minutes / 60} 小时 ${minutes % 60} 分"
}

fun progressLabel(done: Int, total: Int): String = if (total == 0) "还没有任务" else "$done/$total 完成"

/** 还有几天到目标日。 */
fun daysLeftLabel(targetDate: Long?, nowMs: Long): String {
    if (targetDate == null) return "没有目标日"
    val days = java.time.temporal.ChronoUnit.DAYS.between(dayOf(nowMs), dayOf(targetDate))
    return when {
        days > 1 -> "还有 $days 天"
        days == 1L -> "明天到期"
        days == 0L -> "今天到期"
        else -> "已过期 ${-days} 天"
    }
}

/** 相对时间：「刚刚」/「3 分钟前」/「昨天」。 */
fun agoLabel(atMs: Long, nowMs: Long): String {
    val diff = nowMs - atMs
    if (diff < 60_000L) return "刚刚"
    val minutes = diff / 60_000L
    if (minutes < 60) return "$minutes 分钟前"
    val hours = minutes / 60
    if (hours < 24) return "$hours 小时前"
    val days = hours / 24
    return if (days == 1L) "昨天" else "$days 天前"
}

/** 计划页与聊天的短日期：`11-05`。 */
fun dayShort(raw: String?): String {
    val date = parseDay(raw) ?: return raw?.takeIf { it.isNotBlank() } ?: "未排期"
    return "%d-%02d".format(date.monthValue, date.dayOfMonth)
}

private val weekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun weekdayText(date: LocalDate): String =
    date.monthValue.toString() + "月" + date.dayOfMonth + "日 " + weekdayNames[date.dayOfWeek.value - 1]

fun weekdayOnly(date: LocalDate): String = weekdayNames[date.dayOfWeek.value - 1]

fun timeText(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().format(hhmm)

/** 计时器：`12:05`。 */
fun clockText(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}
