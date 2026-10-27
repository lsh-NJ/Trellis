package dev.trellis.feature.learning

import dev.trellis.domain.plan.CompletedOccurrence
import dev.trellis.domain.plan.CommitmentUnitView
import dev.trellis.domain.plan.CommitmentView
import dev.trellis.domain.plan.ContinueEntry
import dev.trellis.domain.plan.DayOccurrence
import dev.trellis.domain.plan.DayView
import dev.trellis.domain.plan.EntryView
import dev.trellis.domain.plan.FactView
import dev.trellis.domain.plan.MapUnit
import dev.trellis.domain.plan.MapView
import dev.trellis.domain.plan.MapWeek
import dev.trellis.domain.plan.Milestone
import dev.trellis.domain.plan.MinimalVersion
import dev.trellis.domain.plan.OriginLabel
import dev.trellis.domain.plan.PlanDiff
import dev.trellis.domain.plan.PlanStateView
import dev.trellis.domain.plan.ReviewView
import dev.trellis.domain.plan.SuggestionView
import dev.trellis.domain.plan.TodoItem
import dev.trellis.domain.plan.TodoView
import dev.trellis.domain.plan.WeekDay
import dev.trellis.domain.plan.WeekDayItem
import dev.trellis.domain.plan.WeekView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

// ---------------------------------------------------------------------------
// 今天
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.buildDayView(planId: String?, day: String): DayView {
    val date = PlanTime.parseDay(day) ?: return DayView(day, "", "日期不对。")
    val label = PlanTime.dayLabel(date)
    if (planId == null) {
        return DayView(day, label, "还没有计划。", todos = queryTodos(null).map { TodoItem(it.id, it.title, it.status) })
    }
    val occurrences = plannedOccurrences(planId, day)
    // 「哪天做完的」与「哪天该做」是两笔账：天条上写「周一 1 件」是按安排的日，
    // 所以正文也要把「安排在这天、已经勾掉」的那些列出来，否则看起来像凭空少了一条。
    val completed = (completedOccurrences(planId, day) + doneScheduledOn(planId, day)).distinctBy { it.id }
    val todos = queryTodos(planId)
    // 看的可能不是今天（周卡片可以点未来的周），别把「今天」当成所有日子的代词。
    val isToday = date == todayDate()
    val word = if (isToday) "今天" else "这天"
    val headline = when {
        occurrences.size == 1 -> "${word}一件事：${occurrences.first().title} · ${occurrences.first().minutes} 分钟。"
        occurrences.size > 1 -> "${word} ${occurrences.size} 件事，先做${occurrences.first().title}。"
        completed.isNotEmpty() -> "${word}已经做完了。"
        else -> "${word}没有安排。"
    }
    return DayView(
        day = day,
        weekdayLabel = label,
        headline = headline,
        occurrences = occurrences,
        completed = completed,
        todos = todos.map { TodoItem(it.id, it.title, it.status) },
        undo = latestUndoView(planId),
    )
}

private fun SqliteLearningPlanService.plannedOccurrences(planId: String, day: String): List<DayOccurrence> =
    connection.prepareStatement(
        "SELECT d.id, d.unit_title, d.chapter, d.estimate_minutes, d.mode, d.status, d.min_version_minutes, " +
            "d.moved_from, d.carried_from, d.todo_title FROM v_occurrence_detail d " +
            "WHERE d.plan_id = ? AND d.day = ? AND d.status = 'planned' ORDER BY d.created_at",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, day)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val minutes = rs.getInt(4)
                    val title = rs.getString(2) ?: rs.getString(10) ?: "待办"
                    val minimal = rs.getInt(7).takeIf { it > 0 } ?: 10
                    add(
                        DayOccurrence(
                            id = rs.getString(1),
                            title = title,
                            chapter = rs.getString(3) ?: "",
                            minutes = minutes,
                            mode = rs.getString(5),
                            status = rs.getString(6),
                            minimal = MinimalVersion(available = minutes > minimal, minutes = minimal),
                            movedCount = if (rs.getString(8) != null) 1 else 0,
                            carried = rs.getString(9) != null,
                            origin = OriginLabel(
                                kind = if (rs.getString(9) != null) "carried" else "commitment",
                                label = if (rs.getString(9) != null) "从上周结转" else "本周承诺",
                            ),
                            actions = listOf("complete", "move", "split", "shorten", "skip"),
                        ),
                    )
                }
            }
        }
    }

private fun SqliteLearningPlanService.completedOccurrences(planId: String, day: String): List<CompletedOccurrence> =
    connection.prepareStatement(
        "SELECT d.id, d.unit_title, o.note FROM v_occurrence_detail d " +
            "JOIN occurrence o ON o.id = d.id " +
            "WHERE d.plan_id = ? AND d.status = 'done' AND substr(o.completed_at, 1, 10) = ? " +
            "ORDER BY o.completed_at",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, day)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(CompletedOccurrence(rs.getString(1), rs.getString(2) ?: "任务", rs.getString(3)))
            }
        }
    }

/** 安排在这一天、并且已经勾掉的那些（不管哪天勾的）。 */
private fun SqliteLearningPlanService.doneScheduledOn(planId: String, day: String): List<CompletedOccurrence> =
    connection.prepareStatement(
        "SELECT d.id, d.unit_title, o.note FROM v_occurrence_detail d " +
            "JOIN occurrence o ON o.id = d.id " +
            "WHERE d.plan_id = ? AND d.status = 'done' AND d.day = ? " +
            "ORDER BY d.created_at",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, day)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(CompletedOccurrence(rs.getString(1), rs.getString(2) ?: "任务", rs.getString(3)))
            }
        }
    }

// ---------------------------------------------------------------------------
// 本周
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.buildWeekView(planId: String, week: String): WeekView {
    val start = PlanTime.weekStart(week)
    val today = today()
    val commitmentUnits = commitmentUnitsOf(planId, week)
    val commitment = CommitmentView(
        label = "${week} · ${PlanTime.weekLabel(week)}",
        units = commitmentUnits.map { unit ->
            val state = unitStateIn(planId, week, unit.first)
            CommitmentUnitView(
                id = unit.first,
                title = unit.second,
                chapter = unit.third,
                estimateMinutes = unit.fourth,
                state = state,
                stateLabel = when (state) {
                    "done" -> "已完成"
                    "this_week" -> "本周内"
                    else -> "之后"
                },
            )
        },
        summary = if (commitmentUnits.isEmpty()) {
            "这周还没有承诺。"
        } else {
            "这周 ${commitmentUnits.size} 个单元，约 ${commitmentUnits.sumOf { it.fourth }} 分钟。"
        },
    )
    val days = (0..6).map { offset ->
        val date = start.plusDays(offset.toLong())
        val items = dayItems(planId, date.toString())
        WeekDay(
            day = date.toString(),
            label = PlanTime.weekdayLabel(date) + " " + date.dayOfMonth,
            today = date == today,
            items = items,
        )
    }
    val weekEnded = start.plusDays(6).isBefore(today)
    val reviewed = reviewExists(planId, week)
    val scheduled = days.any { it.items.isNotEmpty() } ||
        connection.prepareStatement(
            "SELECT 1 FROM occurrence o JOIN commitment c ON c.id = o.commitment_id " +
                "WHERE c.plan_version_id = (SELECT current_version_id FROM plan WHERE id = ?) " +
                "AND c.week = ? LIMIT 1",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, week)
            ps.executeQuery().use { it.next() }
        }
    return WeekView(
        week = week,
        rangeLabel = PlanTime.weekLabel(week),
        commitment = commitment,
        days = days,
        scheduled = scheduled,
        reviewEntry = EntryView(
            available = weekEnded,
            label = when {
                reviewed -> "查看周复盘"
                weekEnded -> "生成周复盘"
                else -> "周复盘在周日开放"
            },
            at = null,
            enabled = weekEnded,
        ),
        changeCommitmentEntry = EntryView(
            available = week >= currentWeek(),
            label = if (week >= currentWeek()) "改这周的承诺" else "过去的一周已结账",
            enabled = week >= currentWeek(),
        ),
    )
}

private fun SqliteLearningPlanService.commitmentUnitsOf(planId: String, week: String): List<Quad> {
    val version = currentVersion(planId) ?: return emptyList()
    return connection.prepareStatement(
        "SELECT u.id, u.title, u.chapter, u.estimate_minutes FROM commitment c " +
            "JOIN commitment_unit cu ON cu.commitment_id = c.id " +
            "JOIN plan_unit u ON u.id = cu.unit_id " +
            "WHERE c.plan_version_id = ? AND c.week = ? ORDER BY cu.sort_order",
    ).use { ps ->
        ps.setString(1, version)
        ps.setString(2, week)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(Quad(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
            }
        }
    }
}

private fun SqliteLearningPlanService.unitStateIn(planId: String, week: String, unitId: String): String {
    val done = connection.prepareStatement(
        "SELECT 1 FROM occurrence o JOIN commitment c ON c.id = o.commitment_id " +
            "JOIN plan_version pv ON pv.id = c.plan_version_id " +
            "WHERE pv.plan_id = ? AND c.week = ? AND o.unit_id = ? AND o.status = 'done' LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId); ps.setString(2, week); ps.setString(3, unitId)
        ps.executeQuery().use { it.next() }
    }
    if (done) return "done"
    return if (week == currentWeek()) "this_week" else "future"
}

private fun SqliteLearningPlanService.dayItems(planId: String, day: String): List<WeekDayItem> =
    connection.prepareStatement(
        "SELECT d.id, COALESCE(d.unit_title, d.todo_title), d.status, d.estimate_minutes, d.carried_from " +
            "FROM v_occurrence_detail d WHERE d.plan_id = ? AND d.day = ? AND d.status <> 'superseded' " +
            "ORDER BY d.created_at",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, day)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        WeekDayItem(
                            occurrenceId = rs.getString(1),
                            title = rs.getString(2) ?: "任务",
                            status = rs.getString(3),
                            minutes = rs.getInt(4),
                            carried = rs.getString(5) != null,
                        ),
                    )
                }
            }
        }
    }

// ---------------------------------------------------------------------------
// 地图
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.buildMapView(planId: String): MapView {
    val title = planTitle(planId)
    val weeks = connection.prepareStatement(
        "SELECT DISTINCT c.week FROM commitment c JOIN plan_version pv ON pv.id = c.plan_version_id " +
            "WHERE pv.plan_id = ? ORDER BY c.week",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }
    val current = currentWeek()
    val mapWeeks = weeks.map { week ->
        val units = commitmentUnitsOf(planId, week).map { unit ->
            MapUnit(unit.second, unitStateIn(planId, week, unit.first))
        }
        MapWeek(
            week = week,
            state = when {
                week == current -> "current"
                week < current -> "past"
                else -> "future"
            },
            label = PlanTime.weekLabel(week),
            units = units,
        )
    }
    val last = mapWeeks.lastOrNull()
    val milestone = Milestone(
        label = last?.units?.lastOrNull()?.title ?: "还没排到终点",
        state = "future",
    )
    val next = connection.prepareStatement(
        "SELECT d.id, d.unit_title FROM v_occurrence_detail d WHERE d.plan_id = ? " +
            "AND d.status = 'planned' AND d.day >= ? ORDER BY d.day LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, today().toString())
        ps.executeQuery().use { rs ->
            if (rs.next()) rs.getString(1) to rs.getString(2) else null
        }
    }
    return MapView(
        planTitle = title,
        routeLabel = "共 ${mapWeeks.size} 周 · 到 ${last?.label ?: "—"}",
        weeks = mapWeeks,
        milestone = milestone,
        continueEntry = if (next != null) {
            ContinueEntry(next.first, "继续：${next.second ?: "下一件"}")
        } else {
            ContinueEntry(null, "从今天开始")
        },
    )
}

// ---------------------------------------------------------------------------
// 复盘 / 状态 / 待办
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.reviewExists(planId: String, week: String): Boolean =
    connection.prepareStatement(
        "SELECT 1 FROM event WHERE type = 'review.generated' AND plan_id = ? " +
            "AND json_extract(payload_json, '$.week') = ? LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, week)
        ps.executeQuery().use { it.next() }
    }

internal fun SqliteLearningPlanService.buildReviewView(planId: String, week: String): ReviewView {
    val payload = connection.prepareStatement(
        "SELECT payload_json FROM event WHERE type = 'review.generated' AND plan_id = ? " +
            "AND json_extract(payload_json, '$.week') = ? ORDER BY seq DESC LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, week)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    } ?: return ReviewView(
        week = week,
        state = "not_ready",
        narrative = "这周的复盘还没生成。",
        question = "",
    )
    val obj = json().parseToJsonElement(payload).jsonObject
    val suggestion = obj["suggestion"]?.takeIf { it is JsonObject }?.jsonObject?.let {
        SuggestionView(
            action = it["action"]?.jsonPrimitive?.contentOrNull ?: "",
            cost = it["cost"]?.jsonPrimitive?.contentOrNull ?: "",
            expectedEffect = it["expectedEffect"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }
    val facts = obj["facts"]?.jsonArray?.map {
        val fact = it.jsonObject
        FactView(
            label = fact["label"]?.jsonPrimitive?.contentOrNull ?: "",
            value = fact["value"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    } ?: emptyList()
    val diff = obj["planDiff"]?.takeIf { it is JsonObject }?.let {
        runCatching { json().decodeFromString(PlanDiff.serializer(), it.toString()) }.getOrNull()
    }
    return ReviewView(
        week = week,
        state = "ready",
        narrative = obj["narrative"]?.jsonPrimitive?.contentOrNull ?: "",
        question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
        suggestion = suggestion,
        facts = facts,
        planDiff = diff,
    )
}

internal fun SqliteLearningPlanService.buildPlanState(planId: String): PlanStateView {
    val status = planStatus(planId) ?: "missing"
    val pendingDraft = connection.prepareStatement(
        "SELECT 1 FROM plan_version WHERE plan_id = ? AND status = 'draft' LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { it.next() }
    }
    val lastWeek = connection.prepareStatement(
        "SELECT DISTINCT c.week FROM commitment c JOIN plan_version pv ON pv.id = c.plan_version_id " +
            "WHERE pv.plan_id = ? ORDER BY c.week DESC LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
    val pendingReview = lastWeek != null &&
        PlanTime.weekEnd(lastWeek).isBefore(today()) &&
        !reviewExists(planId, lastWeek)
    val carryPending = connection.prepareStatement(
        "SELECT COUNT(*) FROM event WHERE type = 'carry.pending' AND plan_id = ?",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }
    return PlanStateView(
        planId = planId,
        status = status,
        currentVersion = currentVersion(planId),
        week = currentWeek(),
        pendingDraft = pendingDraft,
        pendingReview = pendingReview,
        carryPending = carryPending,
        title = planTitle(planId),
    )
}

internal fun SqliteLearningPlanService.queryTodos(planId: String?): List<TodoView> =
    connection.prepareStatement(
        "SELECT id, title, status, estimate_minutes FROM todo WHERE status IN ('open','scheduled') " +
            "ORDER BY created_at",
    ).use { ps ->
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        TodoView(
                            id = rs.getString(1),
                            title = rs.getString(2),
                            status = rs.getString(3),
                            estimateMinutes = rs.getInt(4).takeIf { !rs.wasNull() },
                        ),
                    )
                }
            }
        }
    }

internal data class Quad(val first: String, val second: String, val third: String, val fourth: Int)

private fun SqliteLearningPlanService.planExists(planId: String?): Boolean =
    planId != null && planStatus(planId) != null

internal fun SqliteLearningPlanService.dayDate(day: String): LocalDate? = PlanTime.parseDay(day)
