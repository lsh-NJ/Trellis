package dev.trellis.feature.learning

import dev.trellis.domain.plan.CompletedOccurrence
import dev.trellis.domain.plan.CommitmentUnitView
import dev.trellis.domain.plan.CommitmentView
import dev.trellis.domain.plan.ContinueEntry
import dev.trellis.domain.plan.DayOccurrence
import dev.trellis.domain.plan.DayView
import dev.trellis.domain.plan.EntryView
import dev.trellis.domain.plan.FactView
import dev.trellis.domain.plan.GoalView
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.MilestoneView
import dev.trellis.domain.plan.MapUnit
import dev.trellis.domain.plan.MapView
import dev.trellis.domain.plan.MapWeek
import dev.trellis.domain.plan.Milestone
import dev.trellis.domain.plan.MinimalVersion
import dev.trellis.domain.plan.OpenNode
import dev.trellis.domain.plan.OriginLabel
import dev.trellis.domain.plan.PlanDiff
import dev.trellis.domain.plan.PlanStateView
import dev.trellis.domain.plan.ReviewView
import dev.trellis.domain.plan.StageView
import dev.trellis.domain.plan.SuggestionView
import dev.trellis.domain.plan.TodoItem
import dev.trellis.domain.plan.TodoView
import dev.trellis.domain.plan.UnitResourceView
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

private fun SqliteLearningPlanService.plannedOccurrences(planId: String, day: String): List<DayOccurrence> {
    val raw = connection.prepareStatement(
        "SELECT d.id, d.unit_title, d.chapter, d.estimate_minutes, d.mode, d.status, d.min_version_minutes, " +
            "d.moved_from, d.carried_from, d.todo_title, d.unit_id, d.unit_kind FROM v_occurrence_detail d " +
            "LEFT JOIN plan_unit pu ON pu.id = d.unit_id " +
            "WHERE d.plan_id = ? AND d.day = ? AND d.status = 'planned' " +
            // 排期顺序 = 路线顺序（plan_unit.sort_order），同一天里靠前的单元先出现；
            // 待办没有 sort_order，用 created_at 兜底放到最后。
            "ORDER BY COALESCE(pu.sort_order, 2147483647), d.created_at",
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
                            unitId = rs.getString(11),
                            kind = rs.getString(12) ?: "study",
                        ),
                    )
                }
            }
        }
    }
    // 资料按单元一次批量取（IN 查询），不在这里逐条查库。
    val resources = resourcesOf(raw.mapNotNull { it.unitId }.distinct())
    return raw.map { it.copy(resources = resources[it.unitId].orEmpty()) }
}

/**
 * 一次批量取一批单元的资料，按 unit_id 分组。
 *
 * 列表视图（当天 / 天条 / 本周承诺）都可能带资料，逐条 occurrence 里查会把 N+1 打满，
 * 所以统一走这条 IN 查询；调用方自己 distinct 后传进来。
 */
internal fun SqliteLearningPlanService.resourcesOf(unitIds: List<String>): Map<String, List<UnitResourceView>> {
    if (unitIds.isEmpty()) return emptyMap()
    val placeholders = unitIds.joinToString(",") { "?" }
    return connection.prepareStatement(
        "SELECT unit_id, kind, title, url, locator FROM unit_resource " +
            // rowid 是插入顺序：同一秒里写入的多条资料（第 1 集、第 2 集…）也保持调用方给的顺序。
            "WHERE unit_id IN ($placeholders) ORDER BY rowid",
    ).use { ps ->
        unitIds.forEachIndexed { index, id -> ps.setString(index + 1, id) }
        ps.executeQuery().use { rs ->
            val result = mutableMapOf<String, MutableList<UnitResourceView>>()
            while (rs.next()) {
                result.getOrPut(rs.getString(1)) { mutableListOf() }.add(
                    UnitResourceView(
                        kind = rs.getString(2),
                        title = rs.getString(3),
                        url = rs.getString(4) ?: "",
                        locator = rs.getString(5) ?: "",
                    ),
                )
            }
            result.mapValues { it.value.toList() }
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
        units = commitmentUnits,
        summary = if (commitmentUnits.isEmpty()) {
            "这周还没有承诺。"
        } else {
            "这周 ${commitmentUnits.size} 个单元，约 ${commitmentUnits.sumOf { it.estimateMinutes }} 分钟。"
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
    // 周日当天本周也算「到期」：这一天就给生成入口，不用等到下周一。
    val reviewOpen = weekEnded || (week == currentWeek() && PlanTime.isWeekEnd(today))
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
            available = reviewOpen,
            label = when {
                reviewed -> "查看周复盘"
                weekEnded -> "生成周复盘"
                reviewOpen -> "生成本周复盘"
                else -> "周复盘在周日开放"
            },
            at = null,
            enabled = reviewOpen,
        ),
        changeCommitmentEntry = EntryView(
            available = week >= currentWeek(),
            label = if (week >= currentWeek()) "改这周的承诺" else "过去的一周已结账",
            enabled = week >= currentWeek(),
        ),
    )
}

private fun SqliteLearningPlanService.commitmentUnitsOf(planId: String, week: String): List<CommitmentUnitView> {
    val version = currentVersion(planId) ?: return emptyList()
    data class Row(val id: String, val title: String, val chapter: String, val minutes: Int, val kind: String)
    val rows = connection.prepareStatement(
        "SELECT u.id, u.title, u.chapter, u.estimate_minutes, u.kind FROM commitment c " +
            "JOIN commitment_unit cu ON cu.commitment_id = c.id " +
            "JOIN plan_unit u ON u.id = cu.unit_id " +
            "WHERE c.plan_version_id = ? AND c.week = ? ORDER BY cu.sort_order",
    ).use { ps ->
        ps.setString(1, version)
        ps.setString(2, week)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5) ?: "study"))
                }
            }
        }
    }
    val resources = resourcesOf(rows.map { it.id })
    return rows.map { row ->
        val state = unitStateIn(planId, week, row.id)
        CommitmentUnitView(
            id = row.id,
            title = row.title,
            chapter = row.chapter,
            estimateMinutes = row.minutes,
            kind = row.kind,
            state = state,
            stateLabel = when (state) {
                "done" -> "已完成"
                "this_week" -> "本周内"
                else -> "之后"
            },
            resources = resources[row.id].orEmpty(),
        )
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

/**
 * 这个计划「今天到期和之前逾期」的节点，按天升序。
 *
 * 只取 `planned`：周结算会把过了日子的标成 `missed` 并（最多两条）结转进下一周，
 * 那些再摊到待办就和结转出来的那一份重复了。
 *
 * 排除 todo 型 occurrence：待办板摊的是计划节点（单元），排进日历的待办任务归任务那一组。
 */
internal fun SqliteLearningPlanService.openNodesOf(planId: String, untilDay: String): List<OpenNode> =
    connection.prepareStatement(
        "SELECT d.id, COALESCE(d.unit_title, ''), COALESCE(d.chapter, ''), d.estimate_minutes, d.day " +
            "FROM v_occurrence_detail d " +
            "WHERE d.plan_id = ? AND d.status = 'planned' AND d.day <= ? AND d.unit_id IS NOT NULL " +
            "ORDER BY d.day, d.id",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, untilDay)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(OpenNode(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)))
                }
            }
        }
    }

private fun SqliteLearningPlanService.dayItems(planId: String, day: String): List<WeekDayItem> {
    val pairs = connection.prepareStatement(
        "SELECT d.id, COALESCE(d.unit_title, d.todo_title), d.status, d.estimate_minutes, d.carried_from, d.unit_kind, d.unit_id " +
            "FROM v_occurrence_detail d LEFT JOIN plan_unit pu ON pu.id = d.unit_id " +
            "WHERE d.plan_id = ? AND d.day = ? AND d.status <> 'superseded' " +
            // 同上：天条里的顺序也按路线顺序，保证「索引靠前的节点排在当天前面」。
            "ORDER BY COALESCE(pu.sort_order, 2147483647), d.created_at",
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
                            kind = rs.getString(6) ?: "study",
                        ) to rs.getString(7),
                    )
                }
            }
        }
    }
    val resources = resourcesOf(pairs.mapNotNull { it.second }.distinct())
    return pairs.map { (item, unitId) -> item.copy(resources = resources[unitId].orEmpty()) }
}

// ---------------------------------------------------------------------------
// 地图
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.buildMapView(planId: String): MapView {
    val title = planTitle(planId)
    val weeks = connection.prepareStatement(
        // 只认当前生效版本：重排会把周次写成新的一版，旧版本的 2025-W01 不该继续出现在总览里。
        "SELECT DISTINCT c.week FROM commitment c " +
            "JOIN plan_version pv ON pv.id = c.plan_version_id " +
            "JOIN plan p ON p.id = pv.plan_id AND p.current_version_id = pv.id " +
            "WHERE pv.plan_id = ? ORDER BY c.week",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }
        }
    }
    val current = currentWeek()
    val mapWeeks = weeks.map { week ->
        val units = commitmentUnitsOf(planId, week).map { MapUnit(it.title, it.state) }
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
    EventPayloads.of(connection, "review.generated", planId).any { it.str("week") == week }

internal fun SqliteLearningPlanService.buildReviewView(planId: String, week: String): ReviewView {
    val payload = EventPayloads.of(connection, "review.generated", planId, newestFirst = true)
        .firstOrNull { it.str("week") == week }
        ?: return ReviewView(
            week = week,
            state = "not_ready",
            narrative = "这周的复盘还没生成。",
            question = "",
        )
    val obj = payload
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
    // 复盘欠账看「最近一个已经结束的周」，而不是最大承诺周：
    // 全局周表会把未来几周也建出来，取最大周会把这个信号永久压成 false。
    val lastEndedWeek = connection.prepareStatement(
        "SELECT DISTINCT c.week FROM commitment c JOIN plan_version pv ON pv.id = c.plan_version_id " +
            "WHERE pv.plan_id = ? AND c.week < ? ORDER BY c.week DESC LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, currentWeek())
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
    val pendingReview = lastEndedWeek != null && !reviewExists(planId, lastEndedWeek)
    // 周日当天：本周已经到期，可以现在就把这一周的总结做了（周六还不算）。
    val todayDate = today()
    val current = currentWeek()
    val reviewableWeek =
        if (PlanTime.isWeekEnd(todayDate) && !reviewExists(planId, current)) current else null
    val carryPending = connection.prepareStatement(
        "SELECT COUNT(*) FROM event WHERE type = 'carry.pending' AND plan_id = ?",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }
    // 计划级的「每天投入」存在 process.weekly_capacity_minutes 里（×7 写入），这里反推成年。
    val dailyMinutes = connection.prepareStatement(
        "SELECT p.weekly_capacity_minutes FROM process p " +
            "JOIN plan pl ON pl.process_id = p.id WHERE pl.id = ?",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs ->
            if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() && it > 0 }?.div(7) else null
        }
    }
    return PlanStateView(
        planId = planId,
        status = status,
        currentVersion = currentVersion(planId),
        week = currentWeek(),
        pendingDraft = pendingDraft,
        pendingReview = pendingReview,
        pendingReviewWeek = lastEndedWeek.takeIf { pendingReview },
        reviewableWeek = reviewableWeek,
        carryPending = carryPending,
        title = planTitle(planId),
        dailyMinutes = dailyMinutes,
    )
}

/**
 * 当前版本的阶段 + 每个阶段周区间内的完成数 / 承诺数。
 *
 * done/total 用 occurrence 状态现算（不落地计数列）：拖拽、跳过、撤销都会改状态，
 * 存一份缓存只会跟事实对不上。total = 区间内被承诺过的单元数（DISTINCT），
 * done = 其中至少有一次 occurrence 是 done 的单元数。
 */
internal fun SqliteLearningPlanService.buildStages(planId: String): List<StageView> {
    val version = currentVersion(planId) ?: return emptyList()
    return connection.prepareStatement(
        "SELECT s.id, s.name, s.week_start, s.week_end, s.focus, " +
            "(SELECT COUNT(DISTINCT cu.unit_id) FROM commitment c " +
            "JOIN commitment_unit cu ON cu.commitment_id = c.id " +
            "WHERE c.plan_version_id = s.plan_version_id AND c.week BETWEEN s.week_start AND s.week_end) AS total, " +
            "(SELECT COUNT(DISTINCT o.unit_id) FROM occurrence o " +
            "JOIN commitment c ON c.id = o.commitment_id " +
            "WHERE c.plan_version_id = s.plan_version_id AND c.week BETWEEN s.week_start AND s.week_end " +
            "AND o.status = 'done') AS done " +
            "FROM plan_stage s WHERE s.plan_version_id = ? ORDER BY s.sort_order, s.created_at",
    ).use { ps ->
        ps.setString(1, version)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        StageView(
                            id = rs.getString(1),
                            name = rs.getString(2),
                            weekStart = rs.getString(3),
                            weekEnd = rs.getString(4),
                            focus = rs.getString(5) ?: "",
                            done = rs.getInt(7),
                            total = rs.getInt(6),
                        ),
                    )
                }
            }
        }
    }
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

private fun SqliteLearningPlanService.planExists(planId: String?): Boolean =
    planId != null && planStatus(planId) != null

internal fun SqliteLearningPlanService.dayDate(day: String): LocalDate? = PlanTime.parseDay(day)

// ---------------------------------------------------------------------------
// 目标 / 项目（2026-11-07，G-A1）
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.buildGoals(includeArchived: Boolean): List<GoalView> {
    // 已软删除的目标不再出现在目标页；archived 仍按 includeArchived 决定。
    val sql = "SELECT id,title,kind,intent,success,deadline,status,parent_id FROM goal WHERE deleted_at IS NULL " +
        (if (includeArchived) "" else "AND status <> 'archived' ") +
        "ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'paused' THEN 1 ELSE 2 END, updated_at DESC"
    return connection.prepareStatement(sql).use { ps ->
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        GoalView(
                            id = rs.getString(1),
                            title = rs.getString(2),
                            kind = rs.getString(3),
                            intent = rs.getString(4),
                            success = rs.getString(5),
                            deadline = rs.getString(6),
                            status = rs.getString(7),
                            parentId = rs.getString(8),
                            milestones = milestonesOf(rs.getString(1)),
                            plans = plansOfGoal(rs.getString(1)),
                        ),
                    )
                }
            }
        }
    }
}

internal fun SqliteLearningPlanService.buildGoal(goalId: String): GoalView? =
    connection.prepareStatement(
        "SELECT id,title,kind,intent,success,deadline,status,parent_id FROM goal WHERE id = ? AND deleted_at IS NULL",
    ).use { ps ->
        ps.setString(1, goalId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                GoalView(
                    id = rs.getString(1),
                    title = rs.getString(2),
                    kind = rs.getString(3),
                    intent = rs.getString(4),
                    success = rs.getString(5),
                    deadline = rs.getString(6),
                    status = rs.getString(7),
                    parentId = rs.getString(8),
                    milestones = milestonesOf(goalId),
                    plans = plansOfGoal(goalId),
                )
            }
        }
    }

internal fun SqliteLearningPlanService.milestonesOf(goalId: String): List<MilestoneView> =
    connection.prepareStatement(
        "SELECT id,title,deadline,status,sort_order FROM goal_milestone WHERE goal_id = ? " +
            "ORDER BY sort_order, created_at",
    ).use { ps ->
        ps.setString(1, goalId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(MilestoneView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)))
                }
            }
        }
    }

internal fun SqliteLearningPlanService.plansOfGoal(goalId: String): List<LearningPlanInfo> =
    connection.prepareStatement(
        // 放弃过的计划状态字段仍是 ended，读的时候按 abandoned_at 映射成 abandoned，UI 才分得清「结束」和「放弃」。
        "SELECT p.id, pr.title, CASE WHEN pr.abandoned_at IS NOT NULL THEN 'abandoned' ELSE p.status END " +
            "FROM plan p JOIN process pr ON pr.id = p.process_id " +
            // 已删除（软删）的计划不再挂在目标下面。
            "WHERE pr.goal_id = ? AND pr.deleted_at IS NULL ORDER BY p.created_at",
    ).use { ps ->
        ps.setString(1, goalId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(LearningPlanInfo(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
        }
    }
