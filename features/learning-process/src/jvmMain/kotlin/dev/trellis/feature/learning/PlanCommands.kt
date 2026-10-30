package dev.trellis.feature.learning

import dev.trellis.domain.plan.AcceptedPlan
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.CreatedProcess
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.OccurrenceRef
import dev.trellis.domain.plan.PlanDiff
import dev.trellis.domain.plan.ReviewRef
import dev.trellis.domain.plan.SavedDraft
import dev.trellis.domain.plan.SessionRef
import dev.trellis.domain.plan.SettleResult
import dev.trellis.domain.plan.SplitPart
import dev.trellis.domain.plan.TodoRef
import dev.trellis.domain.plan.UnitDraft
import dev.trellis.domain.plan.VersionRef
import dev.trellis.domain.plan.fail
import dev.trellis.domain.plan.ok
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---------------------------------------------------------------------------
// 计划层命令
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.cmdCreateProcess(
    env: CommandEnvelope,
    title: String,
    goal: String,
    deadline: String?,
    weeklyCapacityMinutes: Int?,
): CommandResult<CreatedProcess> {
    if (title.isBlank()) return fail(ErrorCode.E_INVALID, "计划得有个名字。")
    if (goal.isBlank()) return fail(ErrorCode.E_INVALID, "写一句目标，之后复盘要对照它。")
    if (deadline != null && PlanTime.parseDay(deadline) == null) {
        return fail(ErrorCode.E_INVALID, "截止日期要写成 2026-12-31 这种格式。")
    }
    if (weeklyCapacityMinutes != null && weeklyCapacityMinutes !in 30..3000) {
        return fail(ErrorCode.E_INVALID, "每周可用时间要在 30–3000 分钟之间。")
    }
    return write(env) {
        val now = nowIso()
        val processId = Ids.next("prc_")
        val planId = Ids.next("pln_")
        connection.prepareStatement(
            "INSERT INTO process(id,title,goal,deadline,weekly_capacity_minutes,status,created_at) " +
                "VALUES(?,?,?,?,?, 'active', ?)",
        ).use { ps ->
            ps.setString(1, processId)
            ps.setString(2, title)
            ps.setString(3, goal)
            ps.setString(4, deadline)
            if (weeklyCapacityMinutes == null) ps.setNull(5, java.sql.Types.INTEGER) else ps.setInt(5, weeklyCapacityMinutes)
            ps.setString(6, now)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO plan(id,process_id,status,created_at) VALUES(?,?, 'draft', ?)",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, processId)
            ps.setString(3, now)
            ps.executeUpdate()
        }
        appendEvent(
            type = "process.created",
            entity = "process",
            entityId = processId,
            planId = planId,
            payload = eventJson(
                "title" to JsonPrimitive(title),
                "goal" to JsonPrimitive(goal),
                "deadline" to JsonPrimitive(deadline ?: ""),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        appendEvent(
            type = "plan.created",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson("processId" to JsonPrimitive(processId)),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(CreatedProcess(processId, planId), warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
    }
}

internal fun SqliteLearningPlanService.cmdSaveDraft(
    env: CommandEnvelope,
    planId: String,
    versionId: String?,
    units: List<UnitDraft>,
    commitments: List<CommitmentDraft>,
): CommandResult<SavedDraft> {
    if (units.isEmpty()) return fail(ErrorCode.E_INVALID, "草稿里至少要有一个单元。")
    units.forEach {
        if (it.estimateMinutes !in 10..45) return fail(ErrorCode.E_INVALID, "「${it.title}」的时长要在 10–45 分钟。")
        if (it.minVersionMinutes !in 5..15) return fail(ErrorCode.E_INVALID, "「${it.title}」的最小版本要在 5–15 分钟。")
        if (it.title.isBlank() || it.chapter.isBlank()) return fail(ErrorCode.E_INVALID, "单元要有章节和标题。")
    }
    commitments.forEach { commitment ->
        if (!PlanTime.isWeekFormat(commitment.week)) return fail(ErrorCode.E_INVALID, "周要写成 2026-W44 这种格式。")
        if (commitment.unitIndexes.isEmpty()) return fail(ErrorCode.E_INVALID, "${commitment.week} 这周至少放一个单元。")
        if (commitment.unitIndexes.any { it !in units.indices }) {
            return fail(ErrorCode.E_INVALID, "${commitment.week} 引用了不存在的单元。")
        }
        if (commitment.unitIndexes.distinct().size != commitment.unitIndexes.size) {
            return fail(ErrorCode.E_INVALID, "${commitment.week} 里同一个单元重复了。")
        }
    }
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        if (planStatus(planId) == "ended") return@write fail(ErrorCode.E_STATE, "这个计划已经结束了，不能再改。")
        val version: String
        if (versionId == null) {
            version = Ids.next("pv_")
            val effectiveWeek = commitments.minByOrNull { it.week }?.week ?: currentWeek()
            connection.prepareStatement(
                "INSERT INTO plan_version(id,plan_id,parent_id,reason,diff_json,effective_from_week,status,created_at) " +
                    "VALUES(?,?,?,?,?,?, 'draft', ?)",
            ).use { ps ->
                ps.setString(1, version)
                ps.setString(2, planId)
                ps.setString(3, currentVersion(planId))
                ps.setString(4, "初版草稿")
                ps.setString(5, null)
                ps.setString(6, effectiveWeek)
                ps.setString(7, nowIso())
                ps.executeUpdate()
            }
        } else {
            if (planOfVersion(versionId) != planId) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个草稿版本。")
            if (versionStatus(versionId) != "draft") {
                return@write fail(ErrorCode.E_STATE, "这个版本已经生效，不能再改；改承诺请走「改本周」。")
            }
            version = versionId
            connection.prepareStatement("DELETE FROM plan_unit WHERE plan_version_id = ?").use { ps ->
                ps.setString(1, version); ps.executeUpdate()
            }
            connection.prepareStatement("DELETE FROM commitment WHERE plan_version_id = ?").use { ps ->
                ps.setString(1, version); ps.executeUpdate()
            }
        }
        val unitIds = mutableListOf<String>()
        units.forEachIndexed { index, draft ->
            val id = Ids.next("pu_")
            unitIds += id
            connection.prepareStatement(
                "INSERT INTO plan_unit(id,plan_version_id,chapter,title,sort_order,estimate_minutes," +
                    "min_version_minutes,material_segment_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, version)
                ps.setString(3, draft.chapter)
                ps.setString(4, draft.title)
                ps.setInt(5, index)
                ps.setInt(6, draft.estimateMinutes)
                ps.setInt(7, draft.minVersionMinutes)
                ps.setString(8, draft.materialSegmentId)
                ps.setString(9, nowIso())
                ps.executeUpdate()
            }
        }
        commitments.forEach { draft ->
            val commitmentId = Ids.next("cm_")
            connection.prepareStatement(
                "INSERT INTO commitment(id,plan_version_id,week,created_at) VALUES(?,?,?,?)",
            ).use { ps ->
                ps.setString(1, commitmentId)
                ps.setString(2, version)
                ps.setString(3, draft.week)
                ps.setString(4, nowIso())
                ps.executeUpdate()
            }
            draft.unitIndexes.forEachIndexed { order, unitIndex ->
                connection.prepareStatement(
                    "INSERT INTO commitment_unit(commitment_id,unit_id,sort_order) VALUES(?,?,?)",
                ).use { ps ->
                    ps.setString(1, commitmentId)
                    ps.setString(2, unitIds[unitIndex])
                    ps.setInt(3, order)
                    ps.executeUpdate()
                }
            }
        }
        appendEvent(
            type = "plan.draft_saved",
            entity = "plan_version",
            entityId = version,
            planId = planId,
            payload = eventJson(
                "versionId" to JsonPrimitive(version),
                "unitCount" to JsonPrimitive(units.size),
                "weeks" to JsonPrimitive(commitments.joinToString(",") { it.week }),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(SavedDraft(planId, version), warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
    }
}

internal fun SqliteLearningPlanService.cmdAcceptDraft(
    env: CommandEnvelope,
    versionId: String,
    firstWeek: String,
): CommandResult<AcceptedPlan> {
    if (!PlanTime.isWeekFormat(firstWeek)) return fail(ErrorCode.E_INVALID, "周要写成 2026-W44 这种格式。")
    return write(env) {
        val planId = planOfVersion(versionId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个草稿。")
        if (versionStatus(versionId) != "draft") return@write fail(ErrorCode.E_STATE, "这个版本已经生效过了。")
        val record = connection.prepareStatement(
            "SELECT effective_from_week FROM plan_version WHERE id = ?",
        ).use { ps ->
            ps.setString(1, versionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个草稿。")
        if (record != firstWeek) {
            return@write fail(ErrorCode.E_INVALID, "这个草稿从 $record 生效，接受时不能改成 $firstWeek。")
        }
        val previous = currentVersion(planId)
        if (previous != null) {
            connection.prepareStatement(
                "UPDATE plan_version SET status = 'archived' WHERE id = ? AND status = 'active'",
            ).use { ps -> ps.setString(1, previous); ps.executeUpdate() }
        }
        connection.prepareStatement("UPDATE plan_version SET status = 'active' WHERE id = ?").use { ps ->
            ps.setString(1, versionId); ps.executeUpdate()
        }
        connection.prepareStatement(
            "UPDATE plan SET current_version_id = ?, status = 'active' WHERE id = ?",
        ).use { ps ->
            ps.setString(1, versionId); ps.setString(2, planId); ps.executeUpdate()
        }
        val created = materializeWeek(planId, versionId, firstWeek)
        appendEvent(
            type = "plan.draft_accepted",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "versionId" to JsonPrimitive(versionId),
                "firstWeek" to JsonPrimitive(firstWeek),
                "previousVersion" to JsonPrimitive(previous ?: ""),
                "occurrences" to JsonPrimitive(created),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(
            AcceptedPlan(planId, versionId, firstWeek),
            warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList(),
        )
    }
}

/**
 * 这个计划「每天投入多少分钟」：周容量 / 7。
 *
 * @return null 表示用户没设过投入时间——这时保持老规矩「一天只排一个单元」，
 * 不去猜容量，免得把没设置的计划塞成一天两三个。
 */
internal fun SqliteLearningPlanService.planDailyMinutes(planId: String): Int? =
    connection.prepareStatement(
        "SELECT p.weekly_capacity_minutes FROM process p JOIN plan pl ON pl.process_id = p.id WHERE pl.id = ?",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs ->
            if (rs.next()) {
                val weekly = rs.getInt(1)
                if (!rs.wasNull() && weekly > 0) (weekly / 7).coerceAtLeast(15) else null
            } else {
                null
            }
        }
    }

/**
 * 把某周承诺铺成日任务：从今天开始，按「每天投入分钟数」往一天里塞，塞满换下一天。
 *
 * 幂等靠「单元级」判断而不是「这张承诺有没有任何行」：结转 / 顺延 / 拆分都会在同一张
 * 承诺下留下行，以前那种「有一条就整周不铺」的判重会把整周剩下的单元吞掉。
 * 已经排过的单元跳过；当天已经被占用的分钟数（典型来源：上周结转过来的两步）算进预算。
 */
internal fun SqliteLearningPlanService.materializeWeek(planId: String, versionId: String, week: String): Int {
    val commitmentId = connection.prepareStatement(
        "SELECT id FROM commitment WHERE plan_version_id = ? AND week = ?",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.setString(2, week)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    } ?: return 0

    val units = connection.prepareStatement(
        "SELECT u.id, u.title, u.estimate_minutes, u.min_version_minutes FROM commitment_unit cu " +
            "JOIN plan_unit u ON u.id = cu.unit_id WHERE cu.commitment_id = ? ORDER BY cu.sort_order",
    ).use { ps ->
        ps.setString(1, commitmentId)
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(Triple(rs.getString(1), rs.getInt(3), rs.getInt(4)))
            }
        }
    }
    if (units.isEmpty()) return 0

    // 已经在这个承诺下落过地的单元：planned/done/missed/skipped 都算「处理过」。
    // superseded 不算——顺延/拆分时旧行被 superseded，但同一 unit 会有一条新的 planned 行。
    val placed = connection.prepareStatement(
        "SELECT DISTINCT unit_id FROM occurrence WHERE commitment_id = ? AND unit_id IS NOT NULL " +
            "AND status IN ('planned','done','missed','skipped')",
    ).use { ps ->
        ps.setString(1, commitmentId)
        ps.executeQuery().use { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }
    }

    val weekStart = PlanTime.weekStart(week)
    val today = today()
    var day = if (today in weekStart..weekStart.plusDays(6)) today else weekStart
    val dailyBudget = planDailyMinutes(planId)
    // 当天已被这张承诺占用的分钟数（结转行最常落在这天），从预算里先扣掉。
    var usedToday = connection.prepareStatement(
        "SELECT COALESCE(SUM(estimate_minutes), 0) FROM occurrence WHERE commitment_id = ? AND day = ? " +
            "AND status IN ('planned','done')",
    ).use { ps ->
        ps.setString(1, commitmentId)
        ps.setString(2, day.toString())
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }
    var created = 0
    fun nextDay() {
        if (day < weekStart.plusDays(6)) day = day.plusDays(1)
        usedToday = 0
    }
    units.forEach { (unitId, minutes, _) ->
        if (unitId in placed) return@forEach
        // 设了「每天投入」就按容量打包：当天再放会超预算就换下一天；单个单元本身超预算就独占一天。
        // 没设过投入时间时保持老规矩：一天只排一个。
        if (dailyBudget == null) {
            if (usedToday > 0) nextDay()
        } else if (usedToday > 0 && usedToday + minutes > dailyBudget) {
            nextDay()
        }
        val dayText = day.toString()
        val id = insertOccurrence(
            commitmentId = commitmentId,
            unitId = unitId,
            todoId = null,
            day = dayText,
            minutes = minutes,
            mode = "full",
        )
        appendEvent(
            type = "occurrence.scheduled",
            entity = "occurrence",
            entityId = id,
            planId = planId,
            payload = eventJson(
                "week" to JsonPrimitive(week),
                "day" to JsonPrimitive(dayText),
                "unitId" to JsonPrimitive(unitId),
            ),
            actor = dev.trellis.domain.plan.Actor.SYSTEM,
            commandId = null,
        )
        created++
        usedToday += minutes
        if (dailyBudget != null && usedToday >= dailyBudget) nextDay()
    }
    return created
}

internal fun SqliteLearningPlanService.cmdChangeCommitment(
    env: CommandEnvelope,
    versionId: String,
    week: String,
    unitIds: List<String>,
    reason: String,
    diff: PlanDiff?,
): CommandResult<VersionRef> {
    if (!PlanTime.isWeekFormat(week)) return fail(ErrorCode.E_INVALID, "周要写成 2026-W44 这种格式。")
    if (unitIds.isEmpty()) return fail(ErrorCode.E_INVALID, "这周至少留一个单元，不然就变成放假了。")
    if (reason.isBlank()) return fail(ErrorCode.E_INVALID, "改承诺要写一句原因，复盘时要用。")
    return write(env) {
        val planId = planOfVersion(versionId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个版本。")
        if (versionStatus(versionId) != "active") return@write fail(ErrorCode.E_STATE, "只有当前生效的版本才能改承诺。")
        if (week < currentWeek()) return@write fail(ErrorCode.E_STATE, "过去的一周已经结账，不能再改。")
        unitIds.forEach { unitId ->
            if (unitRow(unitId)?.versionId != versionId) {
                return@write fail(ErrorCode.E_INVALID, "这个单元不在当前版本里。")
            }
        }
        val newVersion = Ids.next("pv_")
        connection.prepareStatement(
            "INSERT INTO plan_version(id,plan_id,parent_id,reason,diff_json,effective_from_week,status,created_at) " +
                "VALUES(?,?,?,?,?,?, 'draft', ?)",
        ).use { ps ->
            ps.setString(1, newVersion)
            ps.setString(2, planId)
            ps.setString(3, versionId)
            ps.setString(4, reason)
            ps.setString(5, diff?.let { json().encodeToString(PlanDiff.serializer(), it) })
            ps.setString(6, week)
            ps.setString(7, nowIso())
            ps.executeUpdate()
        }
        val parentUnits = connection.prepareStatement(
            "SELECT id, chapter, title, sort_order, estimate_minutes, min_version_minutes, material_segment_id " +
                "FROM plan_unit WHERE plan_version_id = ? ORDER BY sort_order",
        ).use { ps ->
            ps.setString(1, versionId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            UnitRow(
                                id = rs.getString(1),
                                versionId = versionId,
                                chapter = rs.getString(2),
                                title = rs.getString(3),
                                estimateMinutes = rs.getInt(5),
                                minVersionMinutes = rs.getInt(6),
                                materialSegmentId = rs.getString(7),
                            ),
                        )
                    }
                }
            }
        }
        val mapping = mutableMapOf<String, String>()
        parentUnits.forEach { unit ->
            val newId = Ids.next("pu_")
            mapping[unit.id] = newId
            connection.prepareStatement(
                "INSERT INTO plan_unit(id,plan_version_id,chapter,title,sort_order,estimate_minutes," +
                    "min_version_minutes,material_segment_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, newId)
                ps.setString(2, newVersion)
                ps.setString(3, unit.chapter)
                ps.setString(4, unit.title)
                ps.setInt(5, parentUnits.indexOf(unit))
                ps.setInt(6, unit.estimateMinutes)
                ps.setInt(7, unit.minVersionMinutes)
                ps.setString(8, unit.materialSegmentId)
                ps.setString(9, nowIso())
                ps.executeUpdate()
            }
        }
        val mapped = unitIds.map { mapping[it] ?: return@write fail(ErrorCode.E_NOT_FOUND, "这个单元不在当前版本里。") }

        // 把目标周之外的承诺原样复制到新版本。
        // 以前只复制单元、只插目标周一条承诺：改一次本周，下周的承诺就从活跃版本里消失，
        // 计划页的下周卡片会突然回到「待安排」，滚动推进直接断掉。
        val otherCommitments = connection.prepareStatement(
            "SELECT id, week FROM commitment WHERE plan_version_id = ? AND week <> ? ORDER BY week",
        ).use { ps ->
            ps.setString(1, versionId)
            ps.setString(2, week)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }
        otherCommitments.forEach { (oldCommitmentId, otherWeek) ->
            val newCommitmentId = Ids.next("cm_")
            connection.prepareStatement(
                "INSERT INTO commitment(id,plan_version_id,week,created_at) VALUES(?,?,?,?)",
            ).use { ps ->
                ps.setString(1, newCommitmentId); ps.setString(2, newVersion)
                ps.setString(3, otherWeek); ps.setString(4, nowIso()); ps.executeUpdate()
            }
            connection.prepareStatement(
                "SELECT unit_id FROM commitment_unit WHERE commitment_id = ? ORDER BY sort_order",
            ).use { ps ->
                ps.setString(1, oldCommitmentId)
                ps.executeQuery().use { rs ->
                    var order = 0
                    while (rs.next()) {
                        val mappedUnit = mapping[rs.getString(1)] ?: continue
                        connection.prepareStatement(
                            "INSERT INTO commitment_unit(commitment_id,unit_id,sort_order) VALUES(?,?,?)",
                        ).use { ins ->
                            ins.setString(1, newCommitmentId)
                            ins.setString(2, mappedUnit)
                            ins.setInt(3, order++)
                            ins.executeUpdate()
                        }
                    }
                }
            }
        }

        val commitmentId = Ids.next("cm_")
        connection.prepareStatement(
            "INSERT INTO commitment(id,plan_version_id,week,created_at) VALUES(?,?,?,?)",
        ).use { ps ->
            ps.setString(1, commitmentId); ps.setString(2, newVersion)
            ps.setString(3, week); ps.setString(4, nowIso()); ps.executeUpdate()
        }
        mapped.forEachIndexed { order, unitId ->
            connection.prepareStatement(
                "INSERT INTO commitment_unit(commitment_id,unit_id,sort_order) VALUES(?,?,?)",
            ).use { ps ->
                ps.setString(1, commitmentId); ps.setString(2, unitId); ps.setInt(3, order); ps.executeUpdate()
            }
        }
        connection.prepareStatement("UPDATE plan_version SET status = 'archived' WHERE id = ?").use { ps ->
            ps.setString(1, versionId); ps.executeUpdate()
        }
        connection.prepareStatement("UPDATE plan_version SET status = 'active' WHERE id = ?").use { ps ->
            ps.setString(1, newVersion); ps.executeUpdate()
        }
        connection.prepareStatement("UPDATE plan SET current_version_id = ? WHERE id = ?").use { ps ->
            ps.setString(1, newVersion); ps.setString(2, planId); ps.executeUpdate()
        }
        appendEvent(
            type = "plan.commitment_changed",
            entity = "plan_version",
            entityId = newVersion,
            planId = planId,
            payload = eventJson(
                "fromVersion" to JsonPrimitive(versionId),
                "toVersion" to JsonPrimitive(newVersion),
                "week" to JsonPrimitive(week),
                "unitIds" to JsonPrimitive(unitIds.joinToString(",")),
                "reason" to JsonPrimitive(reason),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        val undo = registerUndo(env, planId, "恢复上周的承诺", ttlMillis = 3_600_000) {
            connection.prepareStatement("UPDATE plan_version SET status = 'archived' WHERE id = ?").use { ps ->
                ps.setString(1, newVersion); ps.executeUpdate()
            }
            connection.prepareStatement("UPDATE plan_version SET status = 'active' WHERE id = ?").use { ps ->
                ps.setString(1, versionId); ps.executeUpdate()
            }
            connection.prepareStatement("UPDATE plan SET current_version_id = ? WHERE id = ?").use { ps ->
                ps.setString(1, versionId); ps.setString(2, planId); ps.executeUpdate()
            }
            appendEvent(
                type = "plan.commitment_reverted",
                entity = "plan_version",
                entityId = versionId,
                planId = planId,
                payload = eventJson("revertedVersion" to JsonPrimitive(newVersion)),
                actor = dev.trellis.domain.plan.Actor.USER,
                commandId = null,
            )
            "已恢复上一版的承诺"
        }
        ok(
            VersionRef(planId, newVersion, week),
            undo = undo,
            diff = diff,
            warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList(),
        )
    }
}

internal fun SqliteLearningPlanService.cmdSetPlanDailyMinutes(
    env: CommandEnvelope,
    planId: String,
    dailyMinutes: Int,
): CommandResult<Unit> {
    if (dailyMinutes !in 15..240) {
        return fail(ErrorCode.E_INVALID, "每天投入要在 15–240 分钟之间（写 15 到 240 的数字）。")
    }
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        val weekly = dailyMinutes * 7
        connection.prepareStatement(
            "UPDATE process SET weekly_capacity_minutes = ? WHERE id = " +
                "(SELECT process_id FROM plan WHERE id = ?)",
        ).use { ps ->
            ps.setInt(1, weekly)
            ps.setString(2, planId)
            ps.executeUpdate()
        }
        appendEvent(
            type = "plan.capacity_changed", entity = "plan", entityId = planId, planId = planId,
            payload = eventJson(
                "dailyMinutes" to JsonPrimitive(dailyMinutes),
                "weeklyCapacityMinutes" to JsonPrimitive(weekly),
            ),
            actor = env.actor, commandId = env.commandId,
        )
        ok(Unit)
    }
}

internal fun SqliteLearningPlanService.cmdPausePlan(
    env: CommandEnvelope,
    planId: String,
    reason: String,
): CommandResult<Unit> = write(env) {
    if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    connection.prepareStatement("UPDATE plan SET status = 'paused' WHERE id = ?").use { ps ->
        ps.setString(1, planId); ps.executeUpdate()
    }
    appendEvent(
        type = "plan.paused", entity = "plan", entityId = planId, planId = planId,
        payload = eventJson("reason" to JsonPrimitive(reason)),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

internal fun SqliteLearningPlanService.cmdResumePlan(env: CommandEnvelope, planId: String): CommandResult<Unit> = write(env) {
    if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    connection.prepareStatement("UPDATE plan SET status = 'active' WHERE id = ?").use { ps ->
        ps.setString(1, planId); ps.executeUpdate()
    }
    appendEvent(
        type = "plan.resumed", entity = "plan", entityId = planId, planId = planId,
        payload = JsonObject(emptyMap()), actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

internal fun SqliteLearningPlanService.cmdEndPlan(
    env: CommandEnvelope,
    planId: String,
    reason: String,
): CommandResult<Unit> = write(env) {
    if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    connection.prepareStatement("UPDATE plan SET status = 'ended' WHERE id = ?").use { ps ->
        ps.setString(1, planId); ps.executeUpdate()
    }
    appendEvent(
        type = "plan.ended", entity = "plan", entityId = planId, planId = planId,
        payload = eventJson("reason" to JsonPrimitive(reason)),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

// ---------------------------------------------------------------------------
// 日层命令：只减 / 移 / 缩，不新增学习量
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.cmdScheduleUnit(
    env: CommandEnvelope,
    commitmentId: String,
    unitId: String,
    day: String,
): CommandResult<OccurrenceRef> {
    if (PlanTime.parseDay(day) == null) return fail(ErrorCode.E_INVALID, "日期要写成 2026-10-28 这种格式。")
    return write(env) {
        val commitment = commitmentRow(commitmentId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这周的承诺。")
        if (commitment.versionId != currentVersion(commitment.planId)) {
            return@write fail(ErrorCode.E_STATE, "这是旧版本里的承诺，当前不再生效。")
        }
        val belongs = connection.prepareStatement(
            "SELECT 1 FROM commitment_unit WHERE commitment_id = ? AND unit_id = ?",
        ).use { ps ->
            ps.setString(1, commitmentId); ps.setString(2, unitId)
            ps.executeQuery().use { it.next() }
        }
        if (!belongs) return@write fail(ErrorCode.E_INVALID, "这个单元不在这一周的承诺里。")
        val duplicate = connection.prepareStatement(
            "SELECT 1 FROM occurrence WHERE unit_id = ? AND day = ? AND status = 'planned' LIMIT 1",
        ).use { ps ->
            ps.setString(1, unitId); ps.setString(2, day)
            ps.executeQuery().use { it.next() }
        }
        if (duplicate) return@write fail(ErrorCode.E_STATE, "这一天已经有这个单元了。")
        val unit = unitRow(unitId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个单元。")
        val id = insertOccurrence(commitmentId, unitId, null, day, unit.estimateMinutes, "full")
        appendEvent(
            type = "occurrence.scheduled", entity = "occurrence", entityId = id, planId = commitment.planId,
            payload = eventJson(
                "day" to JsonPrimitive(day),
                "unitId" to JsonPrimitive(unitId),
                "minutes" to JsonPrimitive(unit.estimateMinutes),
            ),
            actor = env.actor, commandId = env.commandId,
        )
        val undo = registerUndo(env, commitment.planId, "撤销排期") {
            supersede(id, id)
            appendEvent(
                type = "occurrence.undo_scheduled", entity = "occurrence", entityId = id, planId = commitment.planId,
                payload = JsonObject(emptyMap()), actor = dev.trellis.domain.plan.Actor.USER, commandId = null,
            )
            "已撤销这次排期"
        }
        ok(OccurrenceRef(id), undo = undo)
    }
}

internal fun SqliteLearningPlanService.cmdMoveOccurrence(
    env: CommandEnvelope,
    occurrenceId: String,
    toDay: String,
    reason: String?,
): CommandResult<OccurrenceRef> {
    if (PlanTime.parseDay(toDay) == null) return fail(ErrorCode.E_INVALID, "日期要写成 2026-10-28 这种格式。")
    return write(env) {
        val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
        if (row.status != "planned") return@write fail(ErrorCode.E_STATE, "这条已经处理过了，不能再顺延。")
        if (row.day == toDay) return@write fail(ErrorCode.E_STATE, "它已经在这一天了。")
        if (row.unitId == null) return@write fail(ErrorCode.E_STATE, "待办请重新排期，不要顺延。")
        val newId = insertOccurrence(
            commitmentId = row.commitmentId, unitId = row.unitId, todoId = null,
            day = toDay, minutes = row.minutes, mode = row.mode, movedFrom = row.id,
        )
        supersede(row.id, newId)
        appendEvent(
            type = "occurrence.moved", entity = "occurrence", entityId = newId, planId = row.planId,
            payload = eventJson(
                "fromDay" to JsonPrimitive(row.day),
                "toDay" to JsonPrimitive(toDay),
                "week" to JsonPrimitive(row.week ?: ""),
                "reason" to JsonPrimitive(reason ?: ""),
            ),
            actor = env.actor, commandId = env.commandId,
        )
        val undo = registerUndo(env, row.planId, "撤销顺延") {
            supersede(newId, newId)
            val back = insertOccurrence(
                commitmentId = row.commitmentId, unitId = row.unitId, todoId = null,
                day = row.day, minutes = row.minutes, mode = row.mode, movedFrom = newId,
            )
            appendEvent(
                type = "occurrence.moved_back", entity = "occurrence", entityId = back, planId = row.planId,
                payload = eventJson("undoOf" to JsonPrimitive(newId)),
                actor = dev.trellis.domain.plan.Actor.USER, commandId = null,
            )
            "已把它放回 ${row.day}"
        }
        ok(OccurrenceRef(newId), undo = undo)
    }
}

internal fun SqliteLearningPlanService.cmdSplitOccurrence(
    env: CommandEnvelope,
    occurrenceId: String,
    parts: List<SplitPart>,
): CommandResult<List<OccurrenceRef>> {
    if (parts.size !in 2..3) return fail(ErrorCode.E_INVALID, "拆成 2–3 段就够了，太碎会更难开始。")
    if (parts.any { PlanTime.parseDay(it.day) == null }) return fail(ErrorCode.E_INVALID, "日期格式不对。")
    if (parts.any { it.estimateMinutes < 5 }) return fail(ErrorCode.E_INVALID, "每段至少 5 分钟。")
    return write(env) {
        val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
        if (row.status != "planned") return@write fail(ErrorCode.E_STATE, "这条已经处理过了，不能再拆。")
        if (parts.sumOf { it.estimateMinutes } != row.minutes) {
            return@write fail(ErrorCode.E_INVALID, "拆开后总时长要和原来一样（${row.minutes} 分钟）。")
        }
        if (parts.any { it.estimateMinutes >= row.minutes }) {
            return@write fail(ErrorCode.E_INVALID, "拆出来的每一段都要比原来短。")
        }
        val ids = parts.map { part ->
            insertOccurrence(
                commitmentId = row.commitmentId, unitId = row.unitId, todoId = null,
                day = part.day, minutes = part.estimateMinutes, mode = row.mode, movedFrom = row.id,
            )
        }
        supersede(row.id, ids.first())
        appendEvent(
            type = "occurrence.split", entity = "occurrence", entityId = row.id, planId = row.planId,
            payload = buildJsonObject {
                put("parts", JsonArray(parts.map { p ->
                    buildJsonObject {
                        put("day", JsonPrimitive(p.day))
                        put("minutes", JsonPrimitive(p.estimateMinutes))
                    }
                }))
            },
            actor = env.actor, commandId = env.commandId,
        )
        val undo = registerUndo(env, row.planId, "撤销拆分") {
            ids.forEach { supersede(it, it) }
            val back = insertOccurrence(
                commitmentId = row.commitmentId, unitId = row.unitId, todoId = null,
                day = row.day, minutes = row.minutes, mode = row.mode, movedFrom = ids.first(),
            )
            appendEvent(
                type = "occurrence.unsplit", entity = "occurrence", entityId = back, planId = row.planId,
                payload = eventJson("undoOf" to JsonPrimitive(ids.joinToString(","))),
                actor = dev.trellis.domain.plan.Actor.USER, commandId = null,
            )
            "已合回原来那一段"
        }
        ok(ids.map { OccurrenceRef(it) }, undo = undo)
    }
}

internal fun SqliteLearningPlanService.cmdShortenOccurrence(
    env: CommandEnvelope,
    occurrenceId: String,
    toEstimateMinutes: Int,
): CommandResult<OccurrenceRef> {
    if (toEstimateMinutes < 5) return fail(ErrorCode.E_INVALID, "至少留 5 分钟。")
    return write(env) {
        val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
        if (row.status != "planned") return@write fail(ErrorCode.E_STATE, "这条已经处理过了。")
        if (toEstimateMinutes >= row.minutes) return@write fail(ErrorCode.E_INVALID, "缩短后的时长要比现在少。")
        val unit = row.unitId?.let { unitRow(it) }
        val minimal = unit?.minVersionMinutes ?: 10
        val mode = if (toEstimateMinutes <= minimal) "minimal" else row.mode
        val newId = insertOccurrence(
            commitmentId = row.commitmentId, unitId = row.unitId, todoId = null,
            day = row.day, minutes = toEstimateMinutes, mode = mode, movedFrom = row.id,
        )
        supersede(row.id, newId)
        appendEvent(
            type = "occurrence.shortened", entity = "occurrence", entityId = newId, planId = row.planId,
            payload = eventJson(
                "fromMinutes" to JsonPrimitive(row.minutes),
                "toMinutes" to JsonPrimitive(toEstimateMinutes),
                "mode" to JsonPrimitive(mode),
            ),
            actor = env.actor, commandId = env.commandId,
        )
        val undo = registerUndo(env, row.planId, "撤销缩短") {
            supersede(newId, newId)
            val back = insertOccurrence(
                commitmentId = row.commitmentId, unitId = row.unitId, todoId = null,
                day = row.day, minutes = row.minutes, mode = row.mode, movedFrom = newId,
            )
            appendEvent(
                type = "occurrence.unshortened", entity = "occurrence", entityId = back, planId = row.planId,
                payload = eventJson("undoOf" to JsonPrimitive(newId)),
                actor = dev.trellis.domain.plan.Actor.USER, commandId = null,
            )
            "已恢复 ${row.minutes} 分钟"
        }
        ok(OccurrenceRef(newId), undo = undo)
    }
}

internal fun SqliteLearningPlanService.cmdSkipOccurrence(
    env: CommandEnvelope,
    occurrenceId: String,
    reasonChip: String,
): CommandResult<Unit> = write(env) {
    val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
    if (row.status != "planned") return@write fail(ErrorCode.E_STATE, "这条已经处理过了。")
    connection.prepareStatement(
        "UPDATE occurrence SET status = 'skipped', note = ? WHERE id = ? AND status = 'planned'",
    ).use { ps ->
        ps.setString(1, reasonChip.take(200))
        ps.setString(2, occurrenceId)
        ps.executeUpdate()
    }
    appendEvent(
        type = "occurrence.skipped", entity = "occurrence", entityId = occurrenceId, planId = row.planId,
        payload = eventJson("chip" to JsonPrimitive(reasonChip), "week" to JsonPrimitive(row.week ?: "")),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

internal fun SqliteLearningPlanService.cmdCompleteOccurrence(
    env: CommandEnvelope,
    occurrenceId: String,
    note: String?,
): CommandResult<OccurrenceRef> = write(env) {
    val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
    if (row.status != "planned") return@write fail(ErrorCode.E_STATE, "这条已经处理过了。")
    val at = nowIso()
    connection.prepareStatement(
        "UPDATE occurrence SET status = 'done', completed_at = ?, note = COALESCE(?, note) WHERE id = ? AND status = 'planned'",
    ).use { ps ->
        ps.setString(1, at)
        ps.setString(2, note?.take(200))
        ps.setString(3, occurrenceId)
        ps.executeUpdate()
    }
    val eventId = appendEvent(
        type = "occurrence.completed", entity = "occurrence", entityId = occurrenceId, planId = row.planId,
        payload = eventJson(
            "week" to JsonPrimitive(row.week ?: ""),
            "day" to JsonPrimitive(row.day),
            "unitId" to JsonPrimitive(row.unitId ?: ""),
            "minutes" to JsonPrimitive(row.minutes),
            "note" to JsonPrimitive(note ?: ""),
        ),
        actor = env.actor, commandId = env.commandId,
    )
    val undo = registerUndo(env, row.planId, "撤销完成") {
        connection.prepareStatement(
            "UPDATE occurrence SET status = 'planned', completed_at = NULL WHERE id = ? AND status = 'done'",
        ).use { ps -> ps.setString(1, occurrenceId); ps.executeUpdate() }
        appendEvent(
            type = "occurrence.undone", entity = "occurrence", entityId = occurrenceId, planId = row.planId,
            payload = eventJson("undoOf" to JsonPrimitive(eventId.toString())),
            actor = dev.trellis.domain.plan.Actor.USER, commandId = null, undoOf = eventId.toString(),
        )
        "已撤回完成"
    }
    ok(OccurrenceRef(occurrenceId), undo = undo)
}

internal fun SqliteLearningPlanService.cmdUncompleteOccurrence(
    env: CommandEnvelope,
    occurrenceId: String,
): CommandResult<OccurrenceRef> = write(env) {
    val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
    if (row.status != "done") return@write fail(ErrorCode.E_STATE, "这条还没做完，不用改回来。")
    // 表上有 CHECK ((status='done') = (completed_at IS NOT NULL))，所以必须一起清掉完成时间。
    connection.prepareStatement(
        "UPDATE occurrence SET status = 'planned', completed_at = NULL WHERE id = ? AND status = 'done'",
    ).use { ps ->
        ps.setString(1, occurrenceId)
        ps.executeUpdate()
    }
    appendEvent(
        type = "occurrence.uncompleted", entity = "occurrence", entityId = occurrenceId, planId = row.planId,
        payload = eventJson(
            "week" to JsonPrimitive(row.week ?: ""),
            "day" to JsonPrimitive(row.day),
            "unitId" to JsonPrimitive(row.unitId ?: ""),
            "minutes" to JsonPrimitive(row.minutes),
        ),
        actor = env.actor, commandId = env.commandId,
    )
    ok(OccurrenceRef(occurrenceId))
}

internal fun SqliteLearningPlanService.cmdUndoLast(
    env: CommandEnvelope,
    undoToken: String,
): CommandResult<Unit> = write(env) {
    val record = takeUndo(undoToken) ?: return@write fail(ErrorCode.E_UNDO_EXPIRED, "这个撤销已经用过了。")
    if (record.expiresAtMillis in 1..nowMillis()) {
        dropUndo(record)
        return@write fail(ErrorCode.E_UNDO_EXPIRED, "撤销窗口过去了，这一条已经定稿。")
    }
    val label = record.action()
    dropUndo(record)
    appendEvent(
        type = "undo.applied", entity = "undo", entityId = undoToken, planId = record.planId,
        payload = eventJson("label" to JsonPrimitive(label)),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit, warnings = listOf(label))
}

// ---------------------------------------------------------------------------
// 计时
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.cmdStartSession(
    env: CommandEnvelope,
    occurrenceId: String,
    source: String,
): CommandResult<SessionRef> {
    if (source !in setOf("manual", "timer", "activity")) return fail(ErrorCode.E_INVALID, "未知的计时来源。")
    return write(env) {
        val row = occurrenceRow(occurrenceId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条任务。")
        if (row.status != "planned" && row.status != "done") {
            return@write fail(ErrorCode.E_STATE, "这条任务已经不在今天了。")
        }
        val open = connection.prepareStatement(
            "SELECT id FROM session WHERE occurrence_id = ? AND ended_at IS NULL LIMIT 1",
        ).use { ps ->
            ps.setString(1, occurrenceId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (open != null) return@write fail(ErrorCode.E_STATE, "已经在计时了。")
        val id = Ids.next("ses_")
        val now = nowIso()
        connection.prepareStatement(
            "INSERT INTO session(id,occurrence_id,started_at,source,created_at) VALUES(?,?,?,?,?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, occurrenceId)
            ps.setString(3, now); ps.setString(4, source); ps.setString(5, now)
            ps.executeUpdate()
        }
        appendEvent(
            type = "session.started", entity = "session", entityId = id, planId = row.planId,
            payload = eventJson("occurrenceId" to JsonPrimitive(occurrenceId)),
            actor = env.actor, commandId = env.commandId,
        )
        ok(SessionRef(id))
    }
}

internal fun SqliteLearningPlanService.cmdEndSession(env: CommandEnvelope, sessionId: String): CommandResult<Unit> = write(env) {
    val startedAt = connection.prepareStatement(
        "SELECT s.started_at, o.id, pv.plan_id FROM session s " +
            "JOIN occurrence o ON o.id = s.occurrence_id " +
            "LEFT JOIN commitment c ON c.id = o.commitment_id " +
            "LEFT JOIN plan_version pv ON pv.id = c.plan_version_id WHERE s.id = ? AND s.ended_at IS NULL",
    ).use { ps ->
        ps.setString(1, sessionId)
        ps.executeQuery().use { rs ->
            if (rs.next()) Triple(rs.getString(1), rs.getString(2), rs.getString(3)) else null
        }
    } ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这段正在跑的计时。")
    val now = nowIso()
    connection.prepareStatement("UPDATE session SET ended_at = ? WHERE id = ? AND ended_at IS NULL").use { ps ->
        ps.setString(1, now); ps.setString(2, sessionId); ps.executeUpdate()
    }
    val minutes = java.time.Duration.between(
        java.time.OffsetDateTime.parse(startedAt.first),
        java.time.OffsetDateTime.parse(now),
    ).toMinutes().toInt()
    appendEvent(
        type = "session.ended", entity = "session", entityId = sessionId, planId = startedAt.third,
        payload = eventJson(
            "occurrenceId" to JsonPrimitive(startedAt.second),
            "minutes" to JsonPrimitive(minutes.coerceAtLeast(0)),
        ),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

// ---------------------------------------------------------------------------
// 待办
// ---------------------------------------------------------------------------

internal fun SqliteLearningPlanService.cmdAddTodo(
    env: CommandEnvelope,
    title: String,
    estimateMinutes: Int?,
): CommandResult<TodoRef> {
    if (title.isBlank()) return fail(ErrorCode.E_INVALID, "待办得有个标题。")
    if (estimateMinutes != null && estimateMinutes !in 5..120) {
        return fail(ErrorCode.E_INVALID, "待办时长要在 5–120 分钟。")
    }
    return write(env) {
        val id = Ids.next("td_")
        connection.prepareStatement(
            "INSERT INTO todo(id,title,status,estimate_minutes,created_at) VALUES(?,?, 'open', ?,?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, title)
            if (estimateMinutes == null) ps.setNull(3, java.sql.Types.INTEGER) else ps.setInt(3, estimateMinutes)
            ps.setString(4, nowIso())
            ps.executeUpdate()
        }
        appendEvent(
            type = "todo.added", entity = "todo", entityId = id, planId = env.planId,
            payload = eventJson("title" to JsonPrimitive(title)),
            actor = env.actor, commandId = env.commandId,
        )
        ok(TodoRef(id))
    }
}

internal fun SqliteLearningPlanService.cmdCompleteTodo(env: CommandEnvelope, todoId: String): CommandResult<Unit> = write(env) {
    val status = connection.prepareStatement("SELECT status FROM todo WHERE id = ?").use { ps ->
        ps.setString(1, todoId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    } ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条待办。")
    if (status == "done") return@write fail(ErrorCode.E_STATE, "这条已经完成了。")
    if (status == "closed") return@write fail(ErrorCode.E_STATE, "这条已经关掉了。")
    connection.prepareStatement("UPDATE todo SET status = 'done', completed_at = ? WHERE id = ?").use { ps ->
        ps.setString(1, nowIso()); ps.setString(2, todoId); ps.executeUpdate()
    }
    appendEvent(
        type = "todo.completed", entity = "todo", entityId = todoId, planId = env.planId,
        payload = JsonObject(emptyMap()), actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

internal fun SqliteLearningPlanService.cmdCloseTodo(env: CommandEnvelope, todoId: String): CommandResult<Unit> = write(env) {
    val status = connection.prepareStatement("SELECT status FROM todo WHERE id = ?").use { ps ->
        ps.setString(1, todoId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    } ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条待办。")
    if (status == "closed") return@write fail(ErrorCode.E_STATE, "这条已经关掉了。")
    connection.prepareStatement("UPDATE todo SET status = 'closed' WHERE id = ?").use { ps ->
        ps.setString(1, todoId); ps.executeUpdate()
    }
    appendEvent(
        type = "todo.closed", entity = "todo", entityId = todoId, planId = env.planId,
        payload = JsonObject(emptyMap()), actor = env.actor, commandId = env.commandId,
    )
    ok(Unit)
}

internal fun SqliteLearningPlanService.cmdScheduleTodo(
    env: CommandEnvelope,
    todoId: String,
    day: String,
    planId: String,
    commitmentId: String,
    estimateMinutes: Int,
): CommandResult<OccurrenceRef> {
    if (PlanTime.parseDay(day) == null) return fail(ErrorCode.E_INVALID, "日期格式不对。")
    if (estimateMinutes !in 5..60) return fail(ErrorCode.E_INVALID, "排期时长要在 5–60 分钟。")
    return write(env) {
        val todo = connection.prepareStatement("SELECT title, status FROM todo WHERE id = ?").use { ps ->
            ps.setString(1, todoId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
        } ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这条待办。")
        if (todo.second == "done" || todo.second == "closed") {
            return@write fail(ErrorCode.E_STATE, "这条待办已经结束了。")
        }
        val commitment = commitmentRow(commitmentId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这周的承诺。")
        if (commitment.planId != planId) return@write fail(ErrorCode.E_INVALID, "计划和承诺不匹配。")
        val id = insertOccurrence(commitmentId, null, todoId, day, estimateMinutes, "full")
        connection.prepareStatement(
            "INSERT OR IGNORE INTO todo_occurrence(todo_id,occurrence_id,relation) VALUES(?,?, 'scheduled_from')",
        ).use { ps ->
            ps.setString(1, todoId); ps.setString(2, id); ps.executeUpdate()
        }
        connection.prepareStatement("UPDATE todo SET status = 'scheduled' WHERE id = ?").use { ps ->
            ps.setString(1, todoId); ps.executeUpdate()
        }
        appendEvent(
            type = "occurrence.scheduled_from_todo", entity = "occurrence", entityId = id, planId = planId,
            payload = eventJson(
                "todoId" to JsonPrimitive(todoId),
                "day" to JsonPrimitive(day),
                "minutes" to JsonPrimitive(estimateMinutes),
            ),
            actor = env.actor, commandId = env.commandId,
        )
        val undo = registerUndo(env, planId, "撤销待办排期") {
            supersede(id, id)
            connection.prepareStatement("UPDATE todo SET status = 'open' WHERE id = ?").use { ps ->
                ps.setString(1, todoId); ps.executeUpdate()
            }
            appendEvent(
                type = "occurrence.undo_from_todo", entity = "occurrence", entityId = id, planId = planId,
                payload = eventJson("todoId" to JsonPrimitive(todoId)),
                actor = dev.trellis.domain.plan.Actor.USER, commandId = null,
            )
            "已撤销这条待办的排期"
        }
        ok(OccurrenceRef(id), undo = undo)
    }
}
// ---------------------------------------------------------------------------
// 周节奏：把某一周的承诺铺到天（幂等）
// ---------------------------------------------------------------------------

/**
 * 只负责铺天，不改承诺。触发时机由调用方决定（这周变成当周、或周日提前铺下周）：
 * 重复调用不会重新排，也不会动已完成的行。
 *
 * 返回这次真铺出来的 occurrence 数；0 = 已经排过（或这周没承诺）。**没有新行就不写事件**，
 * 因为节奏引擎每次启动都会调一遍，零动作也写审计会把 event 表刷满。
 */
internal fun SqliteLearningPlanService.cmdScheduleWeekCount(
    env: CommandEnvelope,
    week: String,
): CommandResult<Int> = write(env) {
    val planId = env.planId ?: return@write fail(ErrorCode.E_INVALID, "要指定是哪个计划。")
    if (!PlanTime.isWeekFormat(week)) {
        return@write fail(ErrorCode.E_INVALID, "周要写成 ISO 周（例：2026-W44）。")
    }
    if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    val versionId = currentVersion(planId)
        ?: return@write fail(ErrorCode.E_STATE, "这个计划还没有生效的版本。")
    val created = materializeWeek(planId, versionId, week)
    if (created > 0) {
        appendEvent(
            type = "week.scheduled",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "week" to JsonPrimitive(week),
                "occurrences" to JsonPrimitive(created),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
    }
    ok(
        created,
        warnings = if (created == 0) listOf("这周已经排过了，没有重复排。") else emptyList(),
    )
}

internal fun SqliteLearningPlanService.cmdScheduleWeek(
    env: CommandEnvelope,
    week: String,
): CommandResult<Unit> {
    val result = cmdScheduleWeekCount(env, week)
    return if (result.ok) {
        ok(Unit, warnings = result.warnings)
    } else {
        CommandResult<Unit>(ok = false, error = result.error)
    }
}

// ---------------------------------------------------------------------------
// 周节奏：给活跃版本追加一条未来周的承诺（滚动的核心）
// ---------------------------------------------------------------------------

/**
 * 在不新开版本的前提下，给当前活跃版本追加一条「某周」的承诺，单元从待安排池里来。
 *
 * 为什么不开新版本：`plan_unit` / `commitment` 的身份是按版本隔离的，新开一版会换掉 unit id，
 * 已经铺过天的 occurrence 还挂在旧 unit id 上（身份列不可 UPDATE），计划页的「已完成」状态
 * 和周卡片就会对不上。追加一条未来周的承诺不改任何旧行，是所有滚动排周里最安全的写法。
 *
 * 幂等：(版本, 周) 已有承诺时返回 0 + warning；单元必须属于当前版本、且还没被任何一周收走。
 * 返回这次真正加进去的单元数。
 */
internal fun SqliteLearningPlanService.cmdExtendCommitment(
    env: CommandEnvelope,
    planId: String,
    week: String,
    unitIds: List<String>,
    reason: String,
): CommandResult<Int> {
    if (!PlanTime.isWeekFormat(week)) {
        return fail(ErrorCode.E_INVALID, "周要写成 ISO 周（例：2026-W44）。")
    }
    if (unitIds.isEmpty()) return fail(ErrorCode.E_INVALID, "这一周至少放一个单元，不然就变成放假了。")
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        if (planStatus(planId) == "ended") return@write fail(ErrorCode.E_STATE, "这个计划已经结束了，不能再加周。")
        val versionId = currentVersion(planId)
            ?: return@write fail(ErrorCode.E_STATE, "这个计划还没有生效的版本。")
        if (commitmentExists(versionId, week)) {
            return@write ok(0, warnings = listOf("$week 已经有承诺了，没有重复加。"))
        }
        val committed = committedUnitIds(versionId)
        val ordered = mutableListOf<Pair<Int, String>>()
        unitIds.distinct().forEach { unitId ->
            val row = unitRow(unitId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到单元「$unitId」。")
            if (row.versionId != versionId) return@write fail(ErrorCode.E_INVALID, "「${row.title}」不在当前版本里。")
            if (unitId in committed) return@write fail(ErrorCode.E_INVALID, "「${row.title}」已经排在别的周了。")
            ordered += unitSortOrder(unitId) to unitId
        }
        val commitmentId = Ids.next("cm_")
        connection.prepareStatement(
            "INSERT INTO commitment(id,plan_version_id,week,created_at) VALUES(?,?,?,?)",
        ).use { ps ->
            ps.setString(1, commitmentId)
            ps.setString(2, versionId)
            ps.setString(3, week)
            ps.setString(4, nowIso())
            ps.executeUpdate()
        }
        ordered.sortedBy { it.first }.forEachIndexed { index, (_, unitId) ->
            connection.prepareStatement(
                "INSERT INTO commitment_unit(commitment_id,unit_id,sort_order) VALUES(?,?,?)",
            ).use { ps ->
                ps.setString(1, commitmentId)
                ps.setString(2, unitId)
                ps.setInt(3, index)
                ps.executeUpdate()
            }
        }
        appendEvent(
            type = "plan.commitment_extended",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "week" to JsonPrimitive(week),
                "unitIds" to JsonPrimitive(ordered.joinToString(",") { it.second }),
                "reason" to JsonPrimitive(reason),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(ordered.size)
    }
}

/** 当前版本里已经挂到某一周承诺上的单元 id。 */
internal fun SqliteLearningPlanService.committedUnitIds(versionId: String): Set<String> =
    connection.prepareStatement(
        "SELECT cu.unit_id FROM commitment_unit cu JOIN commitment c ON c.id = cu.commitment_id " +
            "WHERE c.plan_version_id = ?",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.executeQuery().use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }

/** 当前版本里所有周承诺的周（升序）。 */
internal fun SqliteLearningPlanService.commitmentWeeksOfVersion(versionId: String): List<String> =
    connection.prepareStatement(
        "SELECT week FROM commitment WHERE plan_version_id = ? ORDER BY week",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

internal fun SqliteLearningPlanService.commitmentExists(versionId: String, week: String): Boolean =
    connection.prepareStatement(
        "SELECT 1 FROM commitment WHERE plan_version_id = ? AND week = ? LIMIT 1",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.setString(2, week)
        ps.executeQuery().use { it.next() }
    }

internal fun SqliteLearningPlanService.unitSortOrder(unitId: String): Int =
    connection.prepareStatement("SELECT sort_order FROM plan_unit WHERE id = ?").use { ps ->
        ps.setString(1, unitId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }