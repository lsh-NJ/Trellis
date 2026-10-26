package dev.trellis.app.desktop

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 截止时间的显示与换算。
 *
 * 两件事分开放，是因为测试要直接钉住它们：
 *  - [endOfDayMs]：界面上选的是「哪一天」，存的是那天 23:59:59。
 *    用 00:00 当截止时间会出现「今天到期」在凌晨就变逾期。
 *  - [dueLabel]：给人看的一句话。日期越远越具体（10-27），越近越相对（今天/明天），
 *    逾期不喊「已逾期」——用一个轻一点的「逾期 N 天」。
 */
internal fun endOfDayMs(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli()

internal fun dateLabel(date: LocalDate): String =
    "%02d-%02d".format(date.monthValue, date.dayOfMonth)

internal fun dueLabel(
    dueAt: Long?,
    done: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (dueAt == null) return null
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val dueDay = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, dueDay)
    return when {
        done && days < 0 -> "原本 " + dateLabel(dueDay) + " 到期"
        done -> dateLabel(dueDay) + " 完成"
        days == 0L -> "今天到期"
        days == 1L -> "明天到期"
        days > 1L -> dateLabel(dueDay) + " 到期"
        else -> "逾期 " + (-days) + " 天"
    }
}
