package dev.trellis.app.desktop

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * 该不该自动跑一次「周计划」。
 *
 * 规则（用户口径：每周一开始时，在计划自己的会话里排这一周；管家可代跑）：
 * - 用户关掉「每周自动排周计划」就不跑；
 * - 同一周只跑一次（`lastWeek` 记着上次跑的是哪一周）；
 * - 周日 18:00 之后提前排，周一全天兜底（周一早上打开应用就会跑）。
 *
 * 抽成纯函数是为了能直接测时间边界，不用真的等周一。
 */
internal fun shouldRunWeeklyPlan(
    enabled: Boolean,
    today: LocalDate,
    hour: Int,
    lastWeek: String?,
): Boolean {
    if (!enabled) return false
    val week = isoWeekOf(today)
    if (lastWeek == week) return false
    return when (today.dayOfWeek) {
        DayOfWeek.SUNDAY -> hour >= 18
        DayOfWeek.MONDAY -> true
        else -> false
    }
}
