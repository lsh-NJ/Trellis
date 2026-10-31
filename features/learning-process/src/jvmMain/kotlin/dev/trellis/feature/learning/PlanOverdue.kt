package dev.trellis.feature.learning

import dev.trellis.domain.plan.BacklogUnit
import dev.trellis.domain.plan.OverdueOccurrence
import dev.trellis.domain.plan.OverdueView

/**
 * 「计划现在欠了什么」的只读投影。
 *
 * 两拨东西不能混在一起算：
 * - **本周隔日**：本周承诺里 `planned` 且日子已经过去的排期。它们还在承诺里，
 *   重排用 `moveOccurrence`（不动版本）或者改本周承诺。
 * - **隔周欠账**：过去周结过账、没做完、也没被本周/以后任何一周重新收走的单元。
 *   它们已经回到 [pool][dev.trellis.domain.plan.LearningPlanService.pool] 那个桶，
 *   要排进未来周，走的是「补承诺」而不是「搬 occurrence」。
 *
 * 容量只算事实（今天到周日每天还空多少分钟），不给建议：重排怎么排由上层协商后发命令。
 */
internal fun SqliteLearningPlanService.overdueOf(planId: String, today: String): OverdueView {
    val todayDate = PlanTime.parseDay(today) ?: today()
    val day = todayDate.toString()
    val week = PlanTime.isoWeek(todayDate)
    val weekStart = PlanTime.weekStart(week)
    val daily = planDailyMinutes(planId) ?: FALLBACK_DAILY_MINUTES

    var remainingDays = 0
    var remainingMinutes = 0
    var cursor = todayDate
    while (!cursor.isAfter(PlanTime.weekEnd(week))) {
        remainingDays++
        remainingMinutes += (daily - plannedMinutesOn(planId, cursor.toString())).coerceAtLeast(0)
        cursor = cursor.plusDays(1)
    }

    return OverdueView(
        planId = planId,
        today = day,
        week = week,
        dailyMinutes = daily,
        remainingDays = remainingDays,
        remainingMinutes = remainingMinutes,
        thisWeek = overdueOccurrencesOf(planId, weekStart.toString(), day),
        backlog = backlogUnitsOf(planId, week),
    )
}

/** 每天投入没设过时的兜底值和周节奏引擎保持一致。 */
private const val FALLBACK_DAILY_MINUTES = 60

/** 本周承诺里已经过了日子还没做的排期，按路线顺序（不是按日期/创建时间）。 */
private fun SqliteLearningPlanService.overdueOccurrencesOf(
    planId: String,
    weekStart: String,
    today: String,
): List<OverdueOccurrence> = connection.prepareStatement(
    "SELECT d.id, d.unit_id, COALESCE(d.unit_title, ''), COALESCE(d.chapter, ''), " +
        "d.estimate_minutes, d.day, u.sort_order " +
        "FROM v_occurrence_detail d JOIN plan_unit u ON u.id = d.unit_id " +
        "WHERE d.plan_id = ? AND d.status = 'planned' AND d.unit_id IS NOT NULL " +
        "AND d.day >= ? AND d.day < ? " +
        "ORDER BY u.sort_order, d.day, d.id",
).use { ps ->
    ps.setString(1, planId)
    ps.setString(2, weekStart)
    ps.setString(3, today)
    ps.executeQuery().use { rs ->
        buildList {
            while (rs.next()) {
                add(
                    OverdueOccurrence(
                        occurrenceId = rs.getString(1),
                        unitId = rs.getString(2),
                        title = rs.getString(3),
                        chapter = rs.getString(4),
                        minutes = rs.getInt(5),
                        day = rs.getString(6),
                        sortOrder = rs.getInt(7),
                    ),
                )
            }
        }
    }
}

/**
 * 过去周没完成、也没被本周/以后任何一周收走的单元，按路线顺序。
 *
 * 「没完成」的定义和计划页一致：这个单元没有任何一条 `done` 的 occurrence。
 * 只认结过账后留下的 `missed`：还在 `planned` 的过期排期是本周隔日那一拨，
 * 周结算会先把它标成 missed，这里不重复计。
 */
private fun SqliteLearningPlanService.backlogUnitsOf(planId: String, week: String): List<BacklogUnit> {
    val versionId = currentVersion(planId) ?: return emptyList()
    return connection.prepareStatement(
        "SELECT u.id, u.title, u.chapter, u.estimate_minutes, u.sort_order, MIN(c.week) " +
            "FROM plan_unit u " +
            "JOIN commitment_unit cu ON cu.unit_id = u.id " +
            "JOIN commitment c ON c.id = cu.commitment_id " +
            "WHERE u.plan_version_id = ? AND c.week < ? " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM commitment_unit cu2 JOIN commitment c2 ON c2.id = cu2.commitment_id " +
            "WHERE cu2.unit_id = u.id AND c2.plan_version_id = u.plan_version_id AND c2.week >= ?) " +
            "AND EXISTS (" +
            "SELECT 1 FROM occurrence o JOIN commitment c3 ON c3.id = o.commitment_id " +
            "WHERE o.unit_id = u.id AND o.status = 'missed') " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM occurrence o2 JOIN commitment c4 ON c4.id = o2.commitment_id " +
            "WHERE o2.unit_id = u.id AND o2.status = 'done' AND c4.plan_version_id = u.plan_version_id) " +
            // 已经被本周/未来某条 occurrence 重新排掉的（例如结算结转进本周），不算欠账。
            "AND NOT EXISTS (" +
            "SELECT 1 FROM occurrence o3 WHERE o3.unit_id = u.id AND o3.status = 'planned') " +
            "GROUP BY u.id, u.title, u.chapter, u.estimate_minutes, u.sort_order " +
            "ORDER BY u.sort_order",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.setString(2, week)
        ps.setString(3, week)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        BacklogUnit(
                            unitId = rs.getString(1),
                            title = rs.getString(2),
                            chapter = rs.getString(3),
                            estimateMinutes = rs.getInt(4),
                            sortOrder = rs.getInt(5),
                            missedInWeek = rs.getString(6),
                        ),
                    )
                }
            }
        }
    }
}

/** 某一天已经被 `planned` 的单元排期占掉的分钟数（待办型 occurrence 不占计划容量）。 */
private fun SqliteLearningPlanService.plannedMinutesOn(planId: String, day: String): Int =
    connection.prepareStatement(
        "SELECT COALESCE(SUM(d.estimate_minutes), 0) FROM v_occurrence_detail d " +
            "WHERE d.plan_id = ? AND d.day = ? AND d.status = 'planned' AND d.unit_id IS NOT NULL",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, day)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }
