package dev.trellis.feature.learning

import dev.trellis.domain.plan.AcceptedPlan
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.CommitmentDraft
import dev.trellis.domain.plan.CreatedProcess
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.GoalRef
import dev.trellis.domain.plan.MilestoneRef
import dev.trellis.domain.plan.OccurrenceRef
import dev.trellis.domain.plan.PlanDiff
import dev.trellis.domain.plan.ReviewRef
import dev.trellis.domain.plan.ResourceDraft
import dev.trellis.domain.plan.SavedDraft
import dev.trellis.domain.plan.SessionRef
import dev.trellis.domain.plan.SettleResult
import dev.trellis.domain.plan.SplitPart
import dev.trellis.domain.plan.StageDraft
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

internal fun SqliteLearningPlanService.cmdCreateShell(
    env: CommandEnvelope,
    title: String,
    goal: String,
    deadline: String?,
): CommandResult<CreatedProcess> {
    if (title.isBlank()) return fail(ErrorCode.E_INVALID, "计划得有个名字。")
    if (goal.isBlank()) return fail(ErrorCode.E_INVALID, "写一句目标，之后复盘要对照它。")
    if (deadline != null && PlanTime.parseDay(deadline) == null) {
        return fail(ErrorCode.E_INVALID, "截止日期要写成 2026-12-31 这种格式。")
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
            ps.setNull(5, java.sql.Types.INTEGER)
            ps.setString(6, now)
            ps.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO plan(id,process_id,status,created_at) VALUES(?,?, 'active', ?)",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setString(2, processId)
            ps.setString(3, now)
            ps.executeUpdate()
        }
        val versionId = Ids.next("pv_")
        connection.prepareStatement(
            "INSERT INTO plan_version(id,plan_id,parent_id,reason,diff_json,effective_from_week,status,created_at) " +
                "VALUES(?,?,?,?,?,?, 'active', ?)",
        ).use { ps ->
            ps.setString(1, versionId)
            ps.setString(2, planId)
            ps.setString(3, null)
            ps.setString(4, "空壳计划：先定阶段，单元每周再排")
            ps.setString(5, null)
            ps.setString(6, currentWeek())
            ps.setString(7, now)
            ps.executeUpdate()
        }
        connection.prepareStatement("UPDATE plan SET current_version_id = ? WHERE id = ?").use { ps ->
            ps.setString(1, versionId)
            ps.setString(2, planId)
            ps.executeUpdate()
        }
        appendEvent("process.created", "process", processId, planId, eventJson(
            "title" to JsonPrimitive(title),
            "goal" to JsonPrimitive(goal),
            "deadline" to JsonPrimitive(deadline ?: ""),
        ), env.actor, env.commandId)
        appendEvent("plan.created", "plan", planId, planId, eventJson(
            "processId" to JsonPrimitive(processId),
        ), env.actor, env.commandId)
        appendEvent("plan.shell_created", "plan", planId, planId, eventJson(
            "versionId" to JsonPrimitive(versionId),
        ), env.actor, env.commandId)
        ok(CreatedProcess(processId, planId), warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
    }
}

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
                    "min_version_minutes,material_segment_id,created_at,kind) VALUES(?,?,?,?,?,?,?,?,?,?)",
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
                ps.setString(10, unitKindOrStudy(draft.kind))
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
    val weekEnd = weekStart.plusDays(6)
    // 被顺延/拖到**别的周**的单元也不能再补回来：旧行 superseded，新行（moved_from 非空）
    // 挂在目标周承诺上，只看本承诺会以为「这周还没排」，又把它插回原来那天
    // （现场复现：顺延 9 条后当天仍是 20 件，旧周还多出一条 planned）。
    val movedAway = connection.prepareStatement(
        "SELECT DISTINCT unit_id FROM occurrence WHERE unit_id IS NOT NULL AND status = 'planned' " +
            "AND moved_from IS NOT NULL AND (day < ? OR day > ?)",
    ).use { ps ->
        ps.setString(1, weekStart.toString())
        ps.setString(2, weekEnd.toString())
        ps.executeQuery().use { rs ->
            buildSet { while (rs.next()) add(rs.getString(1)) }
        }
    }
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
        if (unitId in placed || unitId in movedAway) return@forEach
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
            "SELECT id, chapter, title, sort_order, estimate_minutes, min_version_minutes, material_segment_id, kind " +
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
                                kind = rs.getString(8),
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
                    "min_version_minutes,material_segment_id,created_at,kind) VALUES(?,?,?,?,?,?,?,?,?,?)",
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
                ps.setString(10, unitKindOrStudy(unit.kind))
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
    // 被放弃的计划不接受「恢复」：状态已经 ended，再拉回 active 会和 abandoned 标记打架。
    val abandonedAt = connection.prepareStatement(
        "SELECT abandoned_at FROM process WHERE id = (SELECT process_id FROM plan WHERE id = ?)",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
    if (abandonedAt != null) {
        return@write fail(ErrorCode.E_STATE, "这个计划已经放弃了，不能恢复；要重新开始请新建计划。")
    }
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

/**
 * 放弃计划：先放弃再换方向时用。
 *
 * 不新增 status 取值（`plan`/`process` 的 CHECK 是 v1 契约，改它要迁移所有旧库）：
 * 状态仍记 `ended`，另写 `process.abandoned_at`；读侧（[plans]）据此把这条映射成
 * `abandoned`。放弃后计划已是 ended，排期/加单元都会在各自的 ended 检查处被拒。
 */
internal fun SqliteLearningPlanService.cmdAbandonPlan(
    env: CommandEnvelope,
    planId: String,
    reason: String,
): CommandResult<Unit> = write(env) {
    val status = planStatus(planId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    if (status != "active" && status != "paused") {
        return@write fail(ErrorCode.E_STATE, "只有进行中或暂停的计划才能放弃（当前状态：$status）。")
    }
    connection.prepareStatement("UPDATE plan SET status = 'ended' WHERE id = ?").use { ps ->
        ps.setString(1, planId); ps.executeUpdate()
    }
    connection.prepareStatement(
        "UPDATE process SET status = 'ended', abandoned_at = ? " +
            "WHERE id = (SELECT process_id FROM plan WHERE id = ?)",
    ).use { ps ->
        ps.setString(1, nowIso())
        ps.setString(2, planId)
        ps.executeUpdate()
    }
    appendEvent(
        type = "plan.abandoned", entity = "plan", entityId = planId, planId = planId,
        payload = eventJson("reason" to JsonPrimitive(reason)),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit, warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
}

/**
 * 删除一个「已放弃」的计划：只删可见性（软删除）。
 *
 * 事件溯源不允许物理删（`plan_version`/`plan_unit`/`occurrence`/`event` 都有 no-delete 触发器），
 * 所以这里只写 `process.deleted_at`，读侧过滤掉；计划数据与事件都留在库里，便于追溯。
 */
internal fun SqliteLearningPlanService.cmdDeletePlan(
    env: CommandEnvelope,
    planId: String,
): CommandResult<Unit> = write(env) {
    planStatus(planId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    val row = connection.prepareStatement(
        "SELECT pr.abandoned_at, pr.deleted_at FROM plan p JOIN process pr ON pr.id = p.process_id " +
            "WHERE p.id = ?",
    ).use { ps ->
        ps.setString(1, planId)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
    } ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    val (abandonedAt, deletedAt) = row
    if (deletedAt != null) return@write fail(ErrorCode.E_STATE, "这个计划已经删除了。")
    if (abandonedAt == null) {
        return@write fail(ErrorCode.E_STATE, "只有已放弃的计划可以删除；进行中或已结束的计划不删。")
    }
    connection.prepareStatement(
        "UPDATE process SET deleted_at = ? WHERE id = (SELECT process_id FROM plan WHERE id = ?)",
    ).use { ps ->
        ps.setString(1, nowIso())
        ps.setString(2, planId)
        ps.executeUpdate()
    }
    appendEvent(
        type = "plan.deleted", entity = "plan", entityId = planId, planId = planId,
        payload = eventJson("soft" to JsonPrimitive(true)),
        actor = env.actor, commandId = env.commandId,
    )
    ok(Unit, warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
}

/** 一个计划最多几个阶段。阶段是「几个星期攻什么」的粗粒度，太多说明该拆计划了。 */
private const val MAX_STAGES = 20

/**
 * 幂等替换当前版本的阶段列表。
 *
 * 「替换」而不是增删：调用方（研究建计划 / 计划文档同步）每次给的都是完整阶段表，
 * 按整表换最简单，重复调用结果一致。阶段读的是版本，换版后旧阶段自然留在旧版本里。
 *
 * 两条区间规则：`weekStart <= weekEnd`；有承诺时阶段区间必须落在承诺的周范围内
 * （阶段是给「这些周」贴的标签，越过承诺边界进度永远是 0/0）；计划还没有任何承诺时
 * 允许任意合法区间 —— 空壳计划先定阶段、每周再排单元就靠这条。
 */
internal fun SqliteLearningPlanService.cmdSetStages(
    env: CommandEnvelope,
    planId: String,
    stages: List<StageDraft>,
): CommandResult<Int> {
    if (stages.size > MAX_STAGES) {
        return fail(ErrorCode.E_INVALID, "一个计划最多 $MAX_STAGES 个阶段，多了拆成两个计划。")
    }
    // 与库无关的校验先做：一次把错误说清，别让调用方逐条试。
    val names = mutableSetOf<String>()
    stages.forEachIndexed { index, draft ->
        val name = draft.name.trim()
        if (name.isBlank()) return fail(ErrorCode.E_INVALID, "第 ${index + 1} 个阶段没有名字。")
        if (!names.add(name)) return fail(ErrorCode.E_INVALID, "阶段名不能重复：「$name」出现了两次。")
        if (!PlanTime.isWeekFormat(draft.weekStart) || !PlanTime.isWeekFormat(draft.weekEnd)) {
            return fail(ErrorCode.E_INVALID, "阶段「$name」的周要写成 2026-W44 这种格式。")
        }
        if (draft.weekStart > draft.weekEnd) {
            return fail(ErrorCode.E_INVALID, "阶段「$name」的起始周不能晚于结束周。")
        }
    }
    return write(env) {
        val status = planStatus(planId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        if (status == "ended") return@write fail(ErrorCode.E_STATE, "这个计划已经结束了，不能再改阶段。")
        val version = currentVersion(planId)
            ?: return@write fail(ErrorCode.E_STATE, "这个计划还没有生效的版本；先建计划或先接受草稿。")
        val committedRange = connection.prepareStatement(
            "SELECT MIN(week), MAX(week) FROM commitment WHERE plan_version_id = ?",
        ).use { ps ->
            ps.setString(1, version)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1)?.let { first -> rs.getString(2)?.let { first to it } } else null
            }
        }
        if (committedRange != null) {
            stages.forEach { draft ->
                if (draft.weekStart < committedRange.first || draft.weekEnd > committedRange.second) {
                    return@write fail(
                        ErrorCode.E_INVALID,
                        "阶段「${draft.name.trim()}」的周区间（${draft.weekStart}–${draft.weekEnd}）" +
                            "超出了计划承诺的周（${committedRange.first}–${committedRange.second}）。",
                    )
                }
            }
        }
        connection.prepareStatement("DELETE FROM plan_stage WHERE plan_version_id = ?").use { ps ->
            ps.setString(1, version)
            ps.executeUpdate()
        }
        val createdAt = nowIso()
        stages.forEachIndexed { index, draft ->
            connection.prepareStatement(
                "INSERT INTO plan_stage(id,plan_version_id,name,week_start,week_end,focus,sort_order,created_at) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, Ids.next("st_"))
                ps.setString(2, version)
                ps.setString(3, draft.name.trim())
                ps.setString(4, draft.weekStart)
                ps.setString(5, draft.weekEnd)
                ps.setString(6, draft.focus.trim())
                // sortOrder 显式填了就用它；整列都是 0（调用方没填）时按传进来的顺序排。
                ps.setInt(7, if (draft.sortOrder != 0) draft.sortOrder else index)
                ps.setString(8, createdAt)
                ps.executeUpdate()
            }
        }
        appendEvent(
            type = "plan.stages_set",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "count" to JsonPrimitive(stages.size),
                "names" to JsonPrimitive(stages.joinToString(",") { it.name.trim() }),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(stages.size, warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
    }
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

/**
 * 把「待安排池」里的单元拖到某一周的某一天。
 *
 * 为什么不能直接用 [cmdScheduleUnit]：它要求单元已经挂在该周的承诺上，而欠账单元是在
 * 「放弃 = 放回未安排桶」之后回到池子里的，不在任何「本周及以后」的承诺里。
 * 为什么不走 changeCommitment：那会新开一版、换掉 unit id，已经铺过的 occurrence 全对不上。
 *
 * 所以这里只做增量：目标周已有承诺就在 `commitment_unit` 末尾追加一行（INSERT 新的，
 * 触发器只拦 UPDATE/DELETE，不拦插入），没有承诺就按 [cmdExtendCommitment] 的写法建一条；
 * 然后铺一条 occurrence。过去周拒绝——结算过的周再动，欠账语义会乱。
 */
internal fun SqliteLearningPlanService.cmdPlaceUnit(
    env: CommandEnvelope,
    planId: String,
    week: String,
    unitId: String,
    day: String,
): CommandResult<OccurrenceRef> {
    if (!PlanTime.isWeekFormat(week)) {
        return fail(ErrorCode.E_INVALID, "周要写成 ISO 周（例：2026-W44）。")
    }
    val dayDate = PlanTime.parseDay(day)
        ?: return fail(ErrorCode.E_INVALID, "日期要写成 2026-10-28 这种格式。")
    if (PlanTime.isoWeek(dayDate) != week) {
        return fail(ErrorCode.E_INVALID, "这一天不在 $week 这一周里。")
    }
    if (week < currentWeek()) {
        return fail(ErrorCode.E_STATE, "已经过去的周不能再重排了。")
    }
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        if (planStatus(planId) == "ended") {
            return@write fail(ErrorCode.E_STATE, "这个计划已经结束了，不能再重排。")
        }
        val versionId = currentVersion(planId)
            ?: return@write fail(ErrorCode.E_STATE, "这个计划还没有生效的版本。")
        val unit = unitRow(unitId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个单元。")
        if (unit.versionId != versionId) {
            return@write fail(ErrorCode.E_INVALID, "「${unit.title}」不在当前版本里。")
        }
        val plannedDay = connection.prepareStatement(
            "SELECT day FROM occurrence WHERE unit_id = ? AND status = 'planned' LIMIT 1",
        ).use { ps ->
            ps.setString(1, unitId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (plannedDay != null) {
            return@write fail(
                ErrorCode.E_STATE,
                "「${unit.title}」已经排在某天了（$plannedDay），直接拖那一天就行。",
            )
        }
        if (unitId in committedUnitIds(versionId, currentWeek())) {
            return@write fail(ErrorCode.E_INVALID, "「${unit.title}」已经排在别的周了。")
        }

        val existingCommitment = connection.prepareStatement(
            "SELECT id FROM commitment WHERE plan_version_id = ? AND week = ?",
        ).use { ps ->
            ps.setString(1, versionId)
            ps.setString(2, week)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        val commitmentId = existingCommitment ?: Ids.next("cm_").also { id ->
            connection.prepareStatement(
                "INSERT INTO commitment(id,plan_version_id,week,created_at) VALUES(?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, versionId)
                ps.setString(3, week)
                ps.setString(4, nowIso())
                ps.executeUpdate()
            }
        }
        val nextSort = connection.prepareStatement(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM commitment_unit WHERE commitment_id = ?",
        ).use { ps ->
            ps.setString(1, commitmentId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        connection.prepareStatement(
            "INSERT INTO commitment_unit(commitment_id,unit_id,sort_order) VALUES(?,?,?)",
        ).use { ps ->
            ps.setString(1, commitmentId)
            ps.setString(2, unitId)
            ps.setInt(3, nextSort)
            ps.executeUpdate()
        }

        val id = insertOccurrence(commitmentId, unitId, null, day, unit.estimateMinutes, "full")
        appendEvent(
            type = "occurrence.scheduled", entity = "occurrence", entityId = id, planId = planId,
            payload = eventJson(
                "day" to JsonPrimitive(day),
                "week" to JsonPrimitive(week),
                "unitId" to JsonPrimitive(unitId),
                "minutes" to JsonPrimitive(unit.estimateMinutes),
                "reason" to JsonPrimitive("拖拽重排"),
            ),
            actor = env.actor, commandId = env.commandId,
        )
        val undo = registerUndo(env, planId, "撤销重排") {
            supersede(id, id)
            appendEvent(
                type = "occurrence.undo_scheduled", entity = "occurrence", entityId = id, planId = planId,
                payload = JsonObject(emptyMap()), actor = dev.trellis.domain.plan.Actor.USER, commandId = null,
            )
            "已撤销这次重排"
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
        // 跨周挪（管家「无限重排」可以挪到下周）：occurrence 是挂在「某周的承诺」上的。
        // 只把日期改到下一周、承诺还留在旧周，会出现「周卡在旧周、日条在新周」的错位；
        // 这里把单元挂进目标周的承诺（没有承诺就建一个），日期和承诺始终同一周。
        val targetWeek = PlanTime.isoWeek(PlanTime.parseDay(toDay)!!)
        var targetCommitmentId = row.commitmentId
        if (row.week != null && row.week != targetWeek) {
            val planId = row.planId ?: return@write fail(ErrorCode.E_STATE, "这条任务没有关联计划，挪不了。")
            val versionId = currentVersion(planId)
                ?: return@write fail(ErrorCode.E_STATE, "计划还没有生效版本，挪不了。")
            targetCommitmentId = connection.prepareStatement(
                "SELECT id FROM commitment WHERE plan_version_id = ? AND week = ?",
            ).use { ps ->
                ps.setString(1, versionId)
                ps.setString(2, targetWeek)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            } ?: run {
                val newCommitmentId = Ids.next("cm_")
                connection.prepareStatement(
                    "INSERT INTO commitment(id, plan_version_id, week, created_at) VALUES(?,?,?,?)",
                ).use { ps ->
                    ps.setString(1, newCommitmentId)
                    ps.setString(2, versionId)
                    ps.setString(3, targetWeek)
                    ps.setString(4, nowIso())
                    ps.executeUpdate()
                }
                newCommitmentId
            }
            val alreadyQueued = connection.prepareStatement(
                "SELECT 1 FROM commitment_unit WHERE commitment_id = ? AND unit_id = ?",
            ).use { ps ->
                ps.setString(1, targetCommitmentId)
                ps.setString(2, row.unitId)
                ps.executeQuery().use { rs -> rs.next() }
            }
            if (!alreadyQueued) {
                val nextOrder = connection.prepareStatement(
                    "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM commitment_unit WHERE commitment_id = ?",
                ).use { ps ->
                    ps.setString(1, targetCommitmentId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
                connection.prepareStatement(
                    "INSERT INTO commitment_unit(commitment_id, unit_id, sort_order) VALUES(?,?,?)",
                ).use { ps ->
                    ps.setString(1, targetCommitmentId)
                    ps.setString(2, row.unitId)
                    ps.setInt(3, nextOrder)
                    ps.executeUpdate()
                }
            }
        }
        val newId = insertOccurrence(
            commitmentId = targetCommitmentId, unitId = row.unitId, todoId = null,
            day = toDay, minutes = row.minutes, mode = row.mode, movedFrom = row.id,
        )
        supersede(row.id, newId)
        appendEvent(
            type = "occurrence.moved", entity = "occurrence", entityId = newId, planId = row.planId,
            payload = eventJson(
                "fromDay" to JsonPrimitive(row.day),
                "toDay" to JsonPrimitive(toDay),
                "week" to JsonPrimitive(targetWeek),
                "fromWeek" to JsonPrimitive(row.week ?: ""),
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
 * 幂等：(版本, 周) 已有承诺时返回 0 + warning；单元必须属于当前版本、且没被「本周及以后」
 * 的某周收走。过去周没做完的单元允许重新补进未来周——「放弃 = 放回未安排桶」之后，
 * 欠账还要能再被排进计划，不能因为它在过去某周的承诺里就永远排不动。
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
        val committed = committedUnitIds(versionId, currentWeek())
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

/** 当前版本里已经挂到 [fromWeek]（含）以后某周承诺上的单元 id。 */
internal fun SqliteLearningPlanService.committedUnitIds(versionId: String, fromWeek: String): Set<String> =
    connection.prepareStatement(
        "SELECT cu.unit_id FROM commitment_unit cu JOIN commitment c ON c.id = cu.commitment_id " +
            "WHERE c.plan_version_id = ? AND c.week >= ?",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.setString(2, fromWeek)
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

// ---------------------------------------------------------------------------
// 计划文档同步：给当前生效版本追加单元（INSERT-only，不可撤销）
// ---------------------------------------------------------------------------

/** 一次最多追加多少个单元。AI 工具一次塞太多，用户没法逐条确认。 */
private const val MAX_APPEND_UNITS = 50

/**
 * 给当前生效版本追加 plan_unit：计划文档里的新条目 → 待安排池里的新单元。
 *
 * 为什么不新开版本：单元身份按版本隔离，新开一版会换掉 unit id，已经铺过天的 occurrence
 * 还挂在旧 id 上（occurrence 的 unit_id 不可 UPDATE），计划页「已完成」会对不上。
 * 追加是纯 INSERT，不动任何旧行。
 *
 * 不可撤销：非草稿版本的 plan_unit 有 no_rewrite / no_delete 触发器（见 PlanSchema），
 * 只允许 INSERT —— 追加进去的单元删不掉、改不了，所以调用方写入前必须让用户确认。
 *
 * 返回新 unit id，顺序与 [drafts] 一致。
 */
internal fun SqliteLearningPlanService.cmdAppendUnits(
    env: CommandEnvelope,
    planId: String,
    drafts: List<UnitDraft>,
): CommandResult<List<String>> {
    if (drafts.isEmpty()) return fail(ErrorCode.E_INVALID, "没有要追加的条目。")
    if (drafts.size > MAX_APPEND_UNITS) {
        return fail(ErrorCode.E_INVALID, "一次最多追加 $MAX_APPEND_UNITS 个单元，多了分几次。")
    }
    drafts.forEach { draft ->
        if (draft.title.isBlank()) return fail(ErrorCode.E_INVALID, "要追加的条目得有标题。")
    }
    return write(env) {
        if (planStatus(planId) == null) return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        if (planStatus(planId) == "ended") return@write fail(ErrorCode.E_STATE, "这个计划已经结束了，不能再加单元。")
        val version = currentVersion(planId)
            ?: return@write fail(ErrorCode.E_STATE, "这个计划还没有生效的版本；先把草稿接受。")
        // 接当前版本 sort_order 最大值往后排；该版本还没有单元时从 0 开始。
        val firstOrder = connection.prepareStatement(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM plan_unit WHERE plan_version_id = ?",
        ).use { ps ->
            ps.setString(1, version)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        val createdAt = nowIso()
        val unitIds = mutableListOf<String>()
        drafts.forEachIndexed { index, draft ->
            val id = Ids.next("pu_")
            unitIds += id
            connection.prepareStatement(
                "INSERT INTO plan_unit(id,plan_version_id,chapter,title,sort_order,estimate_minutes," +
                    "min_version_minutes,material_segment_id,created_at,kind) VALUES(?,?,?,?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, id)
                ps.setString(2, version)
                ps.setString(3, draft.chapter.trim().ifBlank { "未分类" })
                ps.setString(4, draft.title.trim())
                ps.setInt(5, firstOrder + index)
                // 列的 CHECK 是 10..45 / 5..15（见 PlanSchema）：文档里写「约 90 分钟」也要夹进来，
                // 否则命令会栽在约束上（E_STORE），调用方拿不到能指导修改的提示。
                ps.setInt(6, draft.estimateMinutes.coerceIn(10, 45))
                ps.setInt(7, draft.minVersionMinutes.coerceIn(5, 15))
                ps.setString(8, draft.materialSegmentId)
                ps.setString(9, createdAt)
                ps.setString(10, unitKindOrStudy(draft.kind))
                ps.executeUpdate()
            }
        }
        appendEvent(
            type = "plan.units_appended",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "count" to JsonPrimitive(unitIds.size),
                "unitIds" to JsonPrimitive(unitIds.joinToString(",")),
                "reason" to JsonPrimitive(env.origin),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(unitIds, warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
    }
}

/** 一个单元最多几条资料：视频分集 + 讲义 + 题单已经够了，再多说明该拆单元。 */
private const val MAX_UNIT_RESOURCES = 20

/**
 * 替换一个单元的整套资料。
 *
 * 全量替换（DELETE + INSERT）而不是单条增删：资料是 AI 一次性给的列表，按整表换最简单，
 * 也天然幂等 —— 同一个命令重放结果一致。单元必须属于当前生效版本：旧版本的单元挂上
 * 资料也没有视图会读（视图都按当前版本过滤）。
 */
internal fun SqliteLearningPlanService.cmdSetUnitResources(
    env: CommandEnvelope,
    planId: String,
    unitId: String,
    resources: List<ResourceDraft>,
): CommandResult<Int> {
    if (resources.size > MAX_UNIT_RESOURCES) {
        return fail(ErrorCode.E_INVALID, "一个单元最多挂 $MAX_UNIT_RESOURCES 条资料，多了分到别的单元。")
    }
    resources.forEachIndexed { index, draft ->
        if (draft.title.isBlank()) return fail(ErrorCode.E_INVALID, "第 ${index + 1} 条资料没有标题。")
        if (draft.url.length > 2048) {
            return fail(ErrorCode.E_INVALID, "第 ${index + 1} 条资料的链接太长了（2048 字符以内）。")
        }
    }
    return write(env) {
        val status = planStatus(planId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
        if (status == "ended") return@write fail(ErrorCode.E_STATE, "这个计划已经结束了，不能再改单元资料。")
        val version = currentVersion(planId)
            ?: return@write fail(ErrorCode.E_STATE, "这个计划还没有生效的版本。")
        val unit = unitRow(unitId) ?: return@write fail(ErrorCode.E_NOT_FOUND, "找不到这个单元。")
        if (unit.versionId != version) {
            return@write fail(ErrorCode.E_INVALID, "「${unit.title}」不在当前版本里。")
        }
        connection.prepareStatement("DELETE FROM unit_resource WHERE unit_id = ?").use { ps ->
            ps.setString(1, unitId)
            ps.executeUpdate()
        }
        val createdAt = nowIso()
        val kinds = mutableListOf<String>()
        resources.forEach { draft ->
            val kind = normalizeResourceKind(draft.kind)
            kinds += kind
            connection.prepareStatement(
                "INSERT INTO unit_resource(id,unit_id,kind,title,url,locator,source,created_at) VALUES(?,?,?,?,?,?,?,?)",
            ).use { ps ->
                ps.setString(1, Ids.next("res_"))
                ps.setString(2, unitId)
                ps.setString(3, kind)
                ps.setString(4, draft.title.trim())
                ps.setString(5, draft.url.trim())
                ps.setString(6, draft.locator.trim())
                ps.setString(7, draft.source.trim())
                ps.setString(8, createdAt)
                ps.executeUpdate()
            }
        }
        appendEvent(
            type = "plan.unit_resources_set",
            entity = "unit",
            entityId = unitId,
            planId = planId,
            payload = eventJson(
                "unitId" to JsonPrimitive(unitId),
                "count" to JsonPrimitive(resources.size),
                "kinds" to JsonPrimitive(kinds.joinToString(",")),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(resources.size, warnings = if (env.dryRun) listOf("dry-run：没有落库。") else emptyList())
    }
}

// ---------------------------------------------------------------------------
// 目标 / 项目命令（2026-11-07，G-A1）
// ---------------------------------------------------------------------------

private val goalKinds = setOf("goal", "project")
private val goalStatuses = setOf("active", "paused", "archived")

internal fun SqliteLearningPlanService.cmdCreateGoal(
    env: CommandEnvelope,
    title: String,
    kind: String,
    intent: String,
    success: String,
    deadline: String?,
    parentId: String?,
): CommandResult<GoalRef> {
    val name = title.trim()
    if (name.isBlank()) return fail(ErrorCode.E_INVALID, "目标得有个名字。")
    if (name.length > 80) return fail(ErrorCode.E_INVALID, "目标名太长了，80 字以内。")
    if (kind !in goalKinds) return fail(ErrorCode.E_INVALID, "kind 只能是 goal 或 project。")
    if (deadline != null && PlanTime.parseDay(deadline) == null) {
        return fail(ErrorCode.E_INVALID, "截止日期要写成 2026-12-31 这种格式。")
    }
    if (intent.length > 500 || success.length > 500) {
        return fail(ErrorCode.E_INVALID, "意图和验收各写 500 字以内。")
    }
    if (parentId != null && goalRow(parentId) == null) {
        return fail(ErrorCode.E_INVALID, "找不到上级目标：$parentId")
    }
    return write(env) {
        val goalId = Ids.next("goal_")
        val now = nowIso()
        connection.prepareStatement(
            "INSERT INTO goal(id,title,kind,intent,success,deadline,status,parent_id,created_at,updated_at) " +
                "VALUES(?,?,?,?,?,?,'active',?,?,?)",
        ).use { ps ->
            ps.setString(1, goalId)
            ps.setString(2, name)
            ps.setString(3, kind)
            ps.setString(4, intent.trim())
            ps.setString(5, success.trim())
            ps.setString(6, deadline)
            ps.setString(7, parentId)
            ps.setString(8, now)
            ps.setString(9, now)
            ps.executeUpdate()
        }
        appendEvent(
            type = "goal.created",
            entity = "goal",
            entityId = goalId,
            planId = null,
            payload = eventJson(
                "title" to JsonPrimitive(name),
                "kind" to JsonPrimitive(kind),
                "deadline" to JsonPrimitive(deadline ?: ""),
                "parentId" to JsonPrimitive(parentId ?: ""),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(GoalRef(goalId))
    }
}

internal fun SqliteLearningPlanService.cmdUpdateGoal(
    env: CommandEnvelope,
    goalId: String,
    title: String?,
    intent: String?,
    success: String?,
    deadline: String?,
    status: String?,
): CommandResult<GoalRef> {
    val row = goalRow(goalId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个目标：$goalId")
    val name = title?.trim() ?: row.title
    if (name.isBlank()) return fail(ErrorCode.E_INVALID, "目标得有个名字。")
    if (name.length > 80) return fail(ErrorCode.E_INVALID, "目标名太长了，80 字以内。")
    val nextStatus = status ?: row.status
    if (nextStatus !in goalStatuses) return fail(ErrorCode.E_INVALID, "status 只能是 active / paused / archived。")
    val nextDeadline = deadline ?: row.deadline
    if (nextDeadline != null && PlanTime.parseDay(nextDeadline) == null) {
        return fail(ErrorCode.E_INVALID, "截止日期要写成 2026-12-31 这种格式。")
    }
    val nextIntent = intent?.trim() ?: row.intent
    val nextSuccess = success?.trim() ?: row.success
    return write(env) {
        connection.prepareStatement(
            "UPDATE goal SET title=?, intent=?, success=?, deadline=?, status=?, updated_at=? WHERE id=?",
        ).use { ps ->
            ps.setString(1, name)
            ps.setString(2, nextIntent)
            ps.setString(3, nextSuccess)
            ps.setString(4, nextDeadline)
            ps.setString(5, nextStatus)
            ps.setString(6, nowIso())
            ps.setString(7, goalId)
            ps.executeUpdate()
        }
        appendEvent(
            type = "goal.updated",
            entity = "goal",
            entityId = goalId,
            planId = null,
            payload = eventJson(
                "title" to JsonPrimitive(name),
                "status" to JsonPrimitive(nextStatus),
                "deadline" to JsonPrimitive(nextDeadline ?: ""),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(GoalRef(goalId))
    }
}

internal fun SqliteLearningPlanService.cmdAddMilestone(
    env: CommandEnvelope,
    goalId: String,
    title: String,
    deadline: String?,
): CommandResult<MilestoneRef> {
    val goal = goalRow(goalId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个目标：$goalId")
    if (goal.status == "archived") return fail(ErrorCode.E_INVALID, "这个目标已经归档了，先恢复再改。")
    val name = title.trim()
    if (name.isBlank()) return fail(ErrorCode.E_INVALID, "里程碑得有个名字。")
    if (name.length > 80) return fail(ErrorCode.E_INVALID, "里程碑名太长了，80 字以内。")
    if (deadline != null && PlanTime.parseDay(deadline) == null) {
        return fail(ErrorCode.E_INVALID, "截止日期要写成 2026-12-31 这种格式。")
    }
    return write(env) {
        val sortOrder = connection.prepareStatement(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM goal_milestone WHERE goal_id = ?",
        ).use { ps ->
            ps.setString(1, goalId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
        val milestoneId = Ids.next("ms_")
        val now = nowIso()
        connection.prepareStatement(
            "INSERT INTO goal_milestone(id,goal_id,title,deadline,status,sort_order,created_at,updated_at) " +
                "VALUES(?,?,?,?,'open',?,?,?)",
        ).use { ps ->
            ps.setString(1, milestoneId)
            ps.setString(2, goalId)
            ps.setString(3, name)
            ps.setString(4, deadline)
            ps.setInt(5, sortOrder)
            ps.setString(6, now)
            ps.setString(7, now)
            ps.executeUpdate()
        }
        appendEvent(
            type = "goal.milestone_added",
            entity = "goal_milestone",
            entityId = milestoneId,
            planId = null,
            payload = eventJson(
                "goalId" to JsonPrimitive(goalId),
                "title" to JsonPrimitive(name),
                "deadline" to JsonPrimitive(deadline ?: ""),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(MilestoneRef(goalId, milestoneId))
    }
}

internal fun SqliteLearningPlanService.cmdCompleteMilestone(
    env: CommandEnvelope,
    milestoneId: String,
): CommandResult<MilestoneRef> {
    val row = milestoneRow(milestoneId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个里程碑：$milestoneId")
    if (row.status == "done") return fail(ErrorCode.E_INVALID, "这个里程碑已经完成了。")
    return write(env) {
        connection.prepareStatement(
            "UPDATE goal_milestone SET status='done', updated_at=? WHERE id=? AND status='open'",
        ).use { ps ->
            ps.setString(1, nowIso())
            ps.setString(2, milestoneId)
            ps.executeUpdate()
        }
        appendEvent(
            type = "goal.milestone_completed",
            entity = "goal_milestone",
            entityId = milestoneId,
            planId = null,
            payload = eventJson("goalId" to JsonPrimitive(row.goalId), "title" to JsonPrimitive(row.title)),
            actor = env.actor,
            commandId = env.commandId,
        )
        val undo = registerUndo(env, null, "撤销完成里程碑") {
            connection.prepareStatement(
                "UPDATE goal_milestone SET status='open', updated_at=? WHERE id=? AND status='done'",
            ).use { ps ->
                ps.setString(1, nowIso())
                ps.setString(2, milestoneId)
                ps.executeUpdate()
            }
            appendEvent(
                type = "goal.milestone_reopened",
                entity = "goal_milestone",
                entityId = milestoneId,
                planId = null,
                payload = eventJson("goalId" to JsonPrimitive(row.goalId), "title" to JsonPrimitive(row.title)),
                actor = Actor.USER,
                commandId = env.commandId,
                undoOf = env.commandId,
            )
            "已撤销：里程碑改回未完成"
        }
        ok(MilestoneRef(row.goalId, milestoneId), undo = undo)
    }
}

internal fun SqliteLearningPlanService.cmdUncompleteMilestone(
    env: CommandEnvelope,
    milestoneId: String,
): CommandResult<MilestoneRef> {
    val row = milestoneRow(milestoneId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个里程碑：$milestoneId")
    if (row.status != "done") return fail(ErrorCode.E_INVALID, "这个里程碑还没完成。")
    return write(env) {
        connection.prepareStatement(
            "UPDATE goal_milestone SET status='open', updated_at=? WHERE id=? AND status='done'",
        ).use { ps ->
            ps.setString(1, nowIso())
            ps.setString(2, milestoneId)
            ps.executeUpdate()
        }
        appendEvent(
            type = "goal.milestone_reopened",
            entity = "goal_milestone",
            entityId = milestoneId,
            planId = null,
            payload = eventJson("goalId" to JsonPrimitive(row.goalId), "title" to JsonPrimitive(row.title)),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(MilestoneRef(row.goalId, milestoneId))
    }
}

internal fun SqliteLearningPlanService.cmdRemoveMilestone(
    env: CommandEnvelope,
    milestoneId: String,
): CommandResult<Unit> {
    val row = milestoneRow(milestoneId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个里程碑：$milestoneId")
    return write(env) {
        connection.prepareStatement("DELETE FROM goal_milestone WHERE id = ?").use { ps ->
            ps.setString(1, milestoneId)
            ps.executeUpdate()
        }
        appendEvent(
            type = "goal.milestone_removed",
            entity = "goal_milestone",
            entityId = milestoneId,
            planId = null,
            payload = eventJson("goalId" to JsonPrimitive(row.goalId), "title" to JsonPrimitive(row.title)),
            actor = env.actor,
            commandId = env.commandId,
        )
        ok(Unit)
    }
}

internal fun SqliteLearningPlanService.cmdLinkPlanToGoal(
    env: CommandEnvelope,
    planId: String,
    goalId: String?,
): CommandResult<Unit> {
    val processId = processIdOfPlan(planId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个计划：$planId")
    if (goalId != null) {
        val goal = goalRow(goalId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个目标：$goalId")
        if (goal.status == "archived") return fail(ErrorCode.E_INVALID, "这个目标已经归档了，先恢复再关联。")
    }
    val previous = goalIdOfPlan(planId)
    return write(env) {
        connection.prepareStatement("UPDATE process SET goal_id = ? WHERE id = ?").use { ps ->
            if (goalId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, goalId)
            ps.setString(2, processId)
            ps.executeUpdate()
        }
        appendEvent(
            type = "plan.goal_linked",
            entity = "plan",
            entityId = planId,
            planId = planId,
            payload = eventJson(
                "goalId" to JsonPrimitive(goalId ?: ""),
                "previousGoalId" to JsonPrimitive(previous ?: ""),
            ),
            actor = env.actor,
            commandId = env.commandId,
        )
        val undo = registerUndo(env, planId, "撤销关联目标") {
            connection.prepareStatement("UPDATE process SET goal_id = ? WHERE id = ?").use { ps ->
                if (previous == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, previous)
                ps.setString(2, processId)
                ps.executeUpdate()
            }
            "已撤销：计划不挂在那个目标上了"
        }
        ok(Unit, undo = undo)
    }
}

/** 单元类型：study 学习 / practice 实践 / review 复习；空白与未知值一律按学习算。 */
internal fun unitKindOrStudy(raw: String?): String = when (raw?.trim()?.lowercase()) {
    "practice", "实践" -> "practice"
    "review", "复习" -> "review"
    else -> "study"
}

/** 资料类型归一：库里只存 bilibili / web / doc / other，外部写法（视频、pdf、链接…）都收进来。 */
internal fun normalizeResourceKind(raw: String): String {
    val value = raw.trim().lowercase()
    return when {
        value.startsWith("bilibili") || value in setOf("bili", "b站", "视频", "video") -> "bilibili"
        value in setOf("web", "url", "link", "网页", "网站", "链接") -> "web"
        value in setOf("doc", "pdf", "文档", "资料", "book", "markdown") -> "doc"
        else -> "other"
    }
}
