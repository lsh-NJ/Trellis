package dev.trellis.feature.learning

import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.FactView
import dev.trellis.domain.plan.ReviewRef
import dev.trellis.domain.plan.SettleResult
import dev.trellis.domain.plan.SuggestionView
import dev.trellis.domain.plan.fail
import dev.trellis.domain.plan.ok
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// 周结算：只把「已经过去的日子」标 missed，再最多结转 2 个单元
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.cmdSettleWeek(
    env: CommandEnvelope,
    planId: String,
    week: String,
    today: String,
): CommandResult<SettleResult> {
    if (!PlanTime.isWeekFormat(week)) return fail(ErrorCode.E_INVALID, "周要写成 2026-W44 这种格式。")
    val todayDate = PlanTime.parseDay(today) ?: today()
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        val existing = connection.prepareStatement(
            "SELECT payload_json FROM event WHERE type = 'plan.settled' AND plan_id = ? " +
                "AND json_extract(payload_json, '$.week') = ? ORDER BY seq DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (existing != null) {
            val obj = json().parseToJsonElement(existing).jsonObject
            return@write ok(
                SettleResult(
                    week = week,
                    missedCount = obj["missed"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    carriedCount = obj["carried"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                    pendingCarry = obj["pending"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                ),
                warnings = listOf("这周已经结过一次，没有重复扣账。"),
            )
        }

        val missed = mutableListOf<OccurrenceRow>()
        connection.prepareStatement(
            "SELECT o.id FROM occurrence o JOIN commitment c ON c.id = o.commitment_id " +
                "JOIN plan_version pv ON pv.id = c.plan_version_id " +
                "LEFT JOIN plan_unit u ON u.id = o.unit_id " +
                "WHERE pv.plan_id = ? AND c.week = ? AND o.status = 'planned' AND o.day < ? " +
                "ORDER BY u.sort_order, o.day, o.created_at",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, week)
            ps.setString(3, todayDate.toString())
            ps.executeQuery().use { rs ->
                while (rs.next()) missed += occurrenceRow(rs.getString(1)) ?: continue
            }
        }
        missed.forEach { row ->
            connection.prepareStatement(
                "UPDATE occurrence SET status = 'missed' WHERE id = ? AND status = 'planned'",
            ).use { ps -> ps.setString(1, row.id); ps.executeUpdate() }
            appendEvent(
                type = "occurrence.missed",
                entity = "occurrence",
                entityId = row.id,
                planId = planId,
                payload = eventJson(
                    "week" to JsonPrimitive(week),
                    "day" to JsonPrimitive(row.day),
                    "settledBy" to JsonPrimitive("system"),
                ),
                actor = dev.trellis.domain.plan.Actor.SYSTEM,
                commandId = env.commandId,
            )
        }

        val next = PlanTime.nextWeek(week)
        // 结算可能晚于下一周才跑（长时间没开应用）：结转要落进「下一周，或本周」，
        // 不能落进已经过去的周，否则承诺查不到、全变成欠账。
        val carryWeek = maxOf(next, currentWeek())
        val nextVersion = currentVersion(planId)
        val nextCommitment = nextVersion?.let { version ->
            connection.prepareStatement("SELECT id FROM commitment WHERE plan_version_id = ? AND week = ?").use { ps ->
                ps.setString(1, version)
                ps.setString(2, carryWeek)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }
        var carried = 0
        var inserted = 0
        var pending = 0
        val nextStart = PlanTime.weekStart(carryWeek)
        val targetDay = maxOf(nextStart, todayDate).toString()
        missed.forEach { row ->
            val unitId = row.unitId
            if (nextCommitment == null || unitId == null) {
                pending++
                appendEvent(
                    type = "carry.pending",
                    entity = "occurrence",
                    entityId = row.id,
                    planId = planId,
                    payload = eventJson("week" to JsonPrimitive(week), "toWeek" to JsonPrimitive(carryWeek)),
                    actor = dev.trellis.domain.plan.Actor.SYSTEM,
                    commandId = null,
                )
                return@forEach
            }
            // 单元已经被下一周的承诺收走（节奏引擎先补承诺、再结算），
            // 下一周的周计划里已经有它，不用再插一条 occurrence。
            val inNextCommitment = connection.prepareStatement(
                "SELECT 1 FROM commitment_unit WHERE commitment_id = ? AND unit_id = ? LIMIT 1",
            ).use { ps ->
                ps.setString(1, nextCommitment)
                ps.setString(2, unitId)
                ps.executeQuery().use { it.next() }
            }
            if (inNextCommitment) {
                carried++
                return@forEach
            }
            val duplicate = connection.prepareStatement(
                "SELECT 1 FROM occurrence o JOIN commitment c ON c.id = o.commitment_id " +
                    "WHERE c.week = ? AND o.unit_id = ? AND o.status IN ('planned','done') LIMIT 1",
            ).use { ps ->
                ps.setString(1, carryWeek)
                ps.setString(2, unitId)
                ps.executeQuery().use { it.next() }
            }
            if (duplicate) {
                carried++
                return@forEach
            }
            if (inserted >= 2) {
                pending++
                appendEvent(
                    type = "carry.pending",
                    entity = "occurrence",
                    entityId = row.id,
                    planId = planId,
                    payload = eventJson("week" to JsonPrimitive(week), "toWeek" to JsonPrimitive(carryWeek)),
                    actor = dev.trellis.domain.plan.Actor.SYSTEM,
                    commandId = null,
                )
                return@forEach
            }
            val newId = insertOccurrence(
                commitmentId = nextCommitment,
                unitId = unitId,
                todoId = null,
                day = targetDay,
                minutes = row.minutes,
                mode = row.mode,
                carriedFrom = row.id,
            )
            inserted++
            carried++
            appendEvent(
                type = "occurrence.carried",
                entity = "occurrence",
                entityId = newId,
                planId = planId,
                payload = eventJson(
                    "from" to JsonPrimitive(row.id),
                    "fromWeek" to JsonPrimitive(week),
                    "toWeek" to JsonPrimitive(carryWeek),
                    "day" to JsonPrimitive(targetDay),
                ),
                actor = dev.trellis.domain.plan.Actor.SYSTEM,
                commandId = null,
            )
        }

        appendEvent(
            type = "plan.settled",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "week" to JsonPrimitive(week),
                "missed" to JsonPrimitive(missed.size),
                "carried" to JsonPrimitive(carried),
                "pending" to JsonPrimitive(pending),
            ),
            actor = dev.trellis.domain.plan.Actor.SYSTEM,
            commandId = env.commandId,
        )
        ok(
            SettleResult(week = week, missedCount = missed.size, carriedCount = carried, pendingCarry = pending),
            warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------
// 周复盘：数字在这一刻固化；之后任务再变，也只读快照
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.cmdGenerateReview(
    env: CommandEnvelope,
    planId: String,
    week: String,
    today: String,
): CommandResult<ReviewRef> {
    if (!PlanTime.isWeekFormat(week)) return fail(ErrorCode.E_INVALID, "周要写成 2026-W44 这种格式。")
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        val existing = connection.prepareStatement(
            "SELECT json_extract(payload_json, '$.reviewId') FROM event WHERE type = 'review.generated' " +
                "AND plan_id = ? AND json_extract(payload_json, '$.week') = ? ORDER BY seq DESC LIMIT 1",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (existing != null) {
            return@write ok(ReviewRef(planId, week, existing), warnings = listOf("这周的复盘已经生成过了。"))
        }

        val completedUnits = connection.prepareStatement(
            "SELECT COUNT(DISTINCT json_extract(payload_json, '$.unitId')) FROM event " +
                "WHERE type = 'occurrence.completed' AND plan_id = ? " +
                "AND json_extract(payload_json, '$.week') = ?",
        ).use { ps ->
            ps.setString(1, planId); ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val completedMinutes = connection.prepareStatement(
            "SELECT COALESCE(SUM(json_extract(payload_json, '$.minutes')), 0) FROM event " +
                "WHERE type = 'occurrence.completed' AND plan_id = ? " +
                "AND json_extract(payload_json, '$.week') = ?",
        ).use { ps ->
            ps.setString(1, planId); ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val activeDays = connection.prepareStatement(
            "SELECT COUNT(DISTINCT json_extract(payload_json, '$.day')) FROM event " +
                "WHERE type = 'occurrence.completed' AND plan_id = ? " +
                "AND json_extract(payload_json, '$.week') = ?",
        ).use { ps ->
            ps.setString(1, planId); ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val committedUnits = connection.prepareStatement(
            "SELECT COUNT(*) FROM commitment c JOIN commitment_unit cu ON cu.commitment_id = c.id " +
                "JOIN plan_version pv ON pv.id = c.plan_version_id " +
                "WHERE pv.plan_id = ? AND c.week = ?",
        ).use { ps ->
            ps.setString(1, planId); ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val deferred = connection.prepareStatement(
            "SELECT COUNT(*) FROM event WHERE type = 'occurrence.moved' AND plan_id = ? " +
                "AND substr(json_extract(payload_json, '$.fromDay'), 1, 10) BETWEEN ? AND ?",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, PlanTime.weekStart(week).toString())
            ps.setString(3, PlanTime.weekEnd(week).toString())
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

        val narrative = buildString {
            append("这一周你完成了 $completedUnits / $committedUnits 个单元，投入约 $completedMinutes 分钟，有 $activeDays 天动过手。")
            if (deferred > 0) append("顺延了 $deferred 次。")
            if (completedUnits < committedUnits) {
                append("没做完的不是能力问题，是这周安排本来就偏多。")
            }
        }
        val question = "下周想留一晚不安排，还是保持现在的节奏？"
        val suggestion = when {
            deferred >= 2 -> SuggestionView(
                action = "把最常被顺延的那个单元拆成两段",
                cost = "5 分钟",
                expectedEffect = "少一次整块顺延",
            )
            completedUnits < committedUnits -> SuggestionView(
                action = "下周只承诺 ${committedUnits - 1} 个单元，空出来的那天不补",
                cost = "0",
                expectedEffect = "完成率回到 8 成以上",
            )
            else -> null
        }
        val facts = listOf(
            FactView("完成单元", "$completedUnits / $committedUnits"),
            FactView("投入时间", "$completedMinutes 分钟"),
            FactView("动手天数", "$activeDays 天"),
            FactView("顺延次数", "$deferred 次"),
        )

        val reviewId = Ids.next("rev_")
        appendEvent(
            type = "review.generated",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = buildJsonObject {
                put("week", JsonPrimitive(week))
                put("reviewId", JsonPrimitive(reviewId))
                put("narrative", JsonPrimitive(narrative))
                put("question", JsonPrimitive(question))
                put(
                    "suggestion",
                    suggestion?.let {
                        buildJsonObject {
                            put("action", JsonPrimitive(it.action))
                            put("cost", JsonPrimitive(it.cost))
                            put("expectedEffect", JsonPrimitive(it.expectedEffect))
                        }
                    } ?: JsonObject(emptyMap()),
                )
                put(
                    "facts",
                    buildJsonArray {
                        facts.forEach { fact ->
                            add(
                                buildJsonObject {
                                    put("label", JsonPrimitive(fact.label))
                                    put("value", JsonPrimitive(fact.value))
                                },
                            )
                        }
                    },
                )
            },
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(
            ReviewRef(planId, week, reviewId),
            warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList(),
        )
    }
}

// ---------------------------------------------------------------------------
// 迁移：导出事件日志（只读、可审计）
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.exportEvents(planId: String): String {
    val lines = mutableListOf<String>()
    connection.prepareStatement(
        "SELECT seq,event_id,type,entity,entity_id,payload_json,occurred_at,recorded_at,actor,command_id,undo_of " +
            "FROM event WHERE plan_id = ? ORDER BY seq",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs ->
            while (rs.next()) {
                val line = buildJsonObject {
                    put("seq", JsonPrimitive(rs.getLong(1)))
                    put("eventId", JsonPrimitive(rs.getString(2)))
                    put("type", JsonPrimitive(rs.getString(3)))
                    put("entity", JsonPrimitive(rs.getString(4)))
                    put("entityId", JsonPrimitive(rs.getString(5)))
                    put("payload", json().parseToJsonElement(rs.getString(6)))
                    put("occurredAt", JsonPrimitive(rs.getString(7)))
                    put("recordedAt", JsonPrimitive(rs.getString(8)))
                    put("actor", JsonPrimitive(rs.getString(9)))
                    put("commandId", JsonPrimitive(rs.getString(10) ?: ""))
                    put("undoOf", JsonPrimitive(rs.getString(11) ?: ""))
                }
                lines += line.toString()
            }
        }
    }
    lines += buildJsonObject {
        put("type", JsonPrimitive("snapshot"))
        put("planId", JsonPrimitive(planId))
        put("lastSeq", JsonPrimitive(latestSeq(planId) ?: 0))
        put("exportedAt", JsonPrimitive(nowIso()))
        put("count", JsonPrimitive(lines.size))
    }.toString()
    return lines.joinToString("\n")
}
