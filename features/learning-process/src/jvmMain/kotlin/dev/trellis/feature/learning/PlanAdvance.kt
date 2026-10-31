package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.RhythmResult
import dev.trellis.domain.plan.fail
import dev.trellis.domain.plan.ok

/**
 * 计划自己往前滚一格：结算旧周 → 从池里补承诺 → 生成复盘 → 铺天。
 *
 * 这是「总路线 → 每周 → 每天」里**周**那一层的引擎，刻意不做任何 AI 调用：
 * 没有 key、断网、不想花钱时它也必须能跑。AI 的介入点在别处（建路线、周复盘时给建议）。
 *
 * 幂等由每一步自己保证（`settleWeek` / `generateReview` / `extendCommitment` /
 * `scheduleWeek` 都是幂等的）；没有实际动作时不写库、不写审计。
 */
internal fun SqliteLearningPlanService.advanceRhythmInternal(
    env: CommandEnvelope,
    planId: String,
): CommandResult<RhythmResult> {
    if (planId.isBlank()) return fail(ErrorCode.E_INVALID, "要指定是哪个计划。")
    val status = planStatus(planId) ?: return fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    val week = currentWeek()
    if (status == "ended") {
        return ok(RhythmResult(planId, week, note = "这个计划已经结束，不用再推进。"))
    }
    if (status == "paused") {
        return ok(RhythmResult(planId, week, note = "这个计划在暂停中，节奏先停。"))
    }
    if (env.dryRun) {
        // 子命令各自会 rollback，但节奏是「一串命令」，预览没法合成一份可信的结果；
        // 与其假装能预览，不如直说这次不跑。
        return ok(RhythmResult(planId, week, note = "dry-run：节奏推进不提供预览，真跑时才会对账。"))
    }
    val versionId = currentVersion(planId)
        ?: return ok(RhythmResult(planId, week, note = "计划还没有生效的版本，先建一版草稿。"))

    val warnings = mutableListOf<String>()
    val settledWeeks = mutableListOf<String>()
    val reviewedWeeks = mutableListOf<String>()
    val appendedWeeks = mutableListOf<String>()
    val scheduledCounts = linkedMapOf<String, Int>()
    var appendedUnits = 0
    var missed = 0
    var carried = 0
    var carryPending = 0

    val next = PlanTime.nextWeek(week)
    fun ensureCommitment(target: String) {
        if (commitmentExists(versionId, target)) return
        val picks = pickPoolUnitsForWeek(planId, versionId)
        if (picks.isEmpty()) return
        val result = cmdExtendCommitment(
            env.derive(":extend:$target", Actor.SYSTEM),
            planId = planId,
            week = target,
            unitIds = picks,
            reason = "节奏引擎：从待安排池补 $target",
        )
        val count = result.value
        if (result.ok && count != null) {
            appendedWeeks += target
            appendedUnits += count
        } else {
            warnings += "给 $target 补承诺没成功：" + (result.error?.message ?: "未知原因")
        }
    }

    // ① 先保证「本周」有承诺：长时间没打开应用时本周可能还没有承诺，
    //    而结算上一周时要结转的节点要落进本周——这条必须排在结算之前。
    ensureCommitment(week)

    // ② 结算所有已经结束的周：missed 标记 + 最多结转 2 个进本周。
    val pastWeeks = commitmentWeeksOfVersion(versionId).filter { it < week }
    pastWeeks.forEach { past ->
        // 已经结过账的周不再重复结算（cmdSettleWeek 本身幂等，但会把历史数字当「这次结的」）。
        if (hasSettled(planId, past)) return@forEach
        val settleEnv = env.derive(":settle:$past", Actor.SYSTEM)
        val result = cmdSettleWeek(settleEnv, planId, past, today().toString())
        val value = result.value
        if (result.ok && value != null) {
            settledWeeks += past
            missed += value.missedCount
            carried += value.carriedCount
            carryPending += value.pendingCarry
        } else {
            warnings += "结算 $past 没成功：" + (result.error?.message ?: "未知原因")
        }
    }

    // ③ 再补下一周：结算完拿到上周完成率之后，选量才知道要不要减。
    ensureCommitment(next)

    // ④ 复盘：把「已结束但还没生成复盘」的周补上（只取最近两段，翻旧账没意义）。
    pastWeeks.takeLast(2).forEach { past ->
        if (reviewExists(planId, past)) return@forEach
        val result = cmdGenerateReview(env.derive(":review:$past", Actor.SYSTEM), planId, past, today().toString())
        if (result.ok && result.value != null) reviewedWeeks += past
        else warnings += "生成 $past 的复盘没成功：" + (result.error?.message ?: "未知原因")
    }

    // ⑤ 铺天：本周必铺；周日把下周也提前铺掉（周一打开时就不会一片空白）。
    val scheduleTargets = buildList {
        add(week)
        if (PlanTime.isWeekEnd(today()) && commitmentExists(versionId, next)) add(next)
    }.distinct()
    scheduleTargets.forEach { target ->
        if (!commitmentExists(versionId, target)) return@forEach
        val result = cmdScheduleWeekCount(env.derive(":schedule:$target", Actor.SYSTEM), target)
        val count = result.value
        if (result.ok && count != null && count > 0) {
            scheduledCounts[target] = count
        } else if (!result.ok) {
            warnings += "铺 $target 没成功：" + (result.error?.message ?: "未知原因")
        }
    }

    val poolRemaining = poolRows(planId).size
    val lastWeekStats = pastWeeks.lastOrNull()?.let { weekCompletion(planId, versionId, it) }
    val note = buildRhythmNote(
        week = week,
        settledWeeks = settledWeeks,
        missed = missed,
        carried = carried,
        appendedWeeks = appendedWeeks,
        appendedUnits = appendedUnits,
        scheduledCounts = scheduledCounts,
        poolRemaining = poolRemaining,
        lastWeekStats = lastWeekStats,
    )
    return ok(
        RhythmResult(
            planId = planId,
            week = week,
            settledWeeks = settledWeeks,
            reviewedWeeks = reviewedWeeks,
            appendedWeeks = appendedWeeks,
            appendedUnits = appendedUnits,
            scheduledWeeks = scheduledCounts.keys.toList(),
            missed = missed,
            carried = carried,
            carryPending = carryPending,
            poolRemaining = poolRemaining,
            note = note,
        ),
        warnings = warnings,
    )
}

/**
 * 从待安排池里挑下一周要承诺的单元。
 *
 * 规则只有这一份（选量逻辑散在 UI/AI 里，两边就会排得不一样）：
 * - 周容量 = 每天投入 × 7 × 80%，留 20% 缓冲；
 * - 按路线顺序取，尽量不在一章中间停；
 * - 最近一段过去的周完成率 < 60% 就再缩 15%（「这周本来就排多了」）；
 * - 一周最多 7 个；单个单元再长也要先取一个，不然永远排不动。
 */
internal fun SqliteLearningPlanService.pickPoolUnitsForWeek(planId: String, versionId: String): List<String> {
    val pool = poolRows(planId)
    if (pool.isEmpty()) return emptyList()
    val daily = planDailyMinutes(planId) ?: 60
    var budget = (daily * 7 * 0.8).toInt().coerceAtLeast(60)
    val lastPastWeek = commitmentWeeksOfVersion(versionId).lastOrNull { it < currentWeek() }
    if (lastPastWeek != null) {
        val stats = weekCompletion(planId, versionId, lastPastWeek)
        if (stats != null && stats.second > 0 && stats.first.toDouble() / stats.second < 0.6) {
            budget = (budget * 0.85).toInt().coerceAtLeast(60)
        }
    }
    val picked = mutableListOf<String>()
    var sum = 0
    var chapter = ""
    for (unit in pool) {
        if (picked.isNotEmpty() && sum + unit.minutes > budget) break
        if (picked.isNotEmpty() && unit.chapter != chapter && sum >= budget * 0.6) break
        picked += unit.id
        sum += unit.minutes
        chapter = unit.chapter
        if (picked.size >= 7) break
    }
    return picked
}

/** 这一周有没有结过账（`plan.settled` 事件）。 */
internal fun SqliteLearningPlanService.hasSettled(planId: String, week: String): Boolean =
    connection.prepareStatement(
        "SELECT 1 FROM event WHERE type = 'plan.settled' AND plan_id = ? " +
            "AND json_extract(payload_json, '$.week') = ? LIMIT 1",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, week)
        ps.executeQuery().use { it.next() }
    }

/** 某一周「完成数 / 承诺数」；没有该周承诺时返回 null。 */
internal fun SqliteLearningPlanService.weekCompletion(
    planId: String,
    versionId: String,
    week: String,
): Pair<Int, Int>? {
    val committed = connection.prepareStatement(
        "SELECT COUNT(*) FROM commitment c JOIN commitment_unit cu ON cu.commitment_id = c.id " +
            "WHERE c.plan_version_id = ? AND c.week = ?",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.setString(2, week)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }
    if (committed == 0) return null
    val completed = connection.prepareStatement(
        "SELECT COUNT(DISTINCT json_extract(payload_json, '$.unitId')) FROM event " +
            "WHERE type = 'occurrence.completed' AND plan_id = ? " +
            "AND json_extract(payload_json, '$.week') = ?",
    ).use { ps ->
        ps.setString(1, planId)
        ps.setString(2, week)
        ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
    }
    return completed to committed
}

/**
 * 待安排池：没被「本周及以后」的承诺收走的单元，按路线顺序。
 *
 * 和 `SqliteLearningPlanService.pool` 同一语义：过去周没做完、又没被以后收走的单元也在这里，
 * 所以短课程（7 个单元全排进一周）跨周后不会卡死，下一周还能从欠账里补承诺。
 */
internal fun SqliteLearningPlanService.poolRows(planId: String): List<PoolRow> {
    val versionId = currentVersion(planId) ?: return emptyList()
    return connection.prepareStatement(
        "SELECT u.id, u.estimate_minutes, u.chapter, u.sort_order FROM plan_unit u " +
            "WHERE u.plan_version_id = ? " +
            "AND u.id NOT IN (" +
            "SELECT cu.unit_id FROM commitment_unit cu JOIN commitment c ON c.id = cu.commitment_id " +
            "WHERE c.plan_version_id = ? AND c.week >= ?) " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM occurrence o JOIN commitment c2 ON c2.id = o.commitment_id " +
            "WHERE c2.plan_version_id = ? AND o.unit_id = u.id AND o.status = 'done') " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM occurrence o2 JOIN commitment c3 ON c3.id = o2.commitment_id " +
            "WHERE c3.plan_version_id = ? AND c3.week >= ? AND o2.unit_id = u.id " +
            "AND o2.status = 'planned') " +
            "ORDER BY u.sort_order",
    ).use { ps ->
        ps.setString(1, versionId)
        ps.setString(2, versionId)
        ps.setString(3, currentWeek())
        ps.setString(4, versionId)
        ps.setString(5, versionId)
        ps.setString(6, currentWeek())
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(PoolRow(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getInt(4)))
                }
            }
        }
    }
}

internal data class PoolRow(val id: String, val minutes: Int, val chapter: String, val sortOrder: Int)

/** 子命令命令信封：同一个 rhythm 调用的子命令 id 可预测、可重放。 */
private fun CommandEnvelope.derive(suffix: String, actor: Actor): CommandEnvelope =
    copy(
        commandId = commandId + suffix,
        actor = actor,
        expectedLastSeq = null,
        dryRun = false,
    )

/**
 * 一句人话，给计划页顶部和日志用。
 *
 * 刻意用「做成了什么 + 下一步」的格式，不用「你落后了」这类损失框架；
 * 池子排空时直接说「路线走完」，让用户决定收尾还是加资源，不自动结束计划。
 */
private fun buildRhythmNote(
    week: String,
    settledWeeks: List<String>,
    missed: Int,
    carried: Int,
    appendedWeeks: List<String>,
    appendedUnits: Int,
    scheduledCounts: Map<String, Int>,
    poolRemaining: Int,
    lastWeekStats: Pair<Int, Int>?,
): String {
    val parts = mutableListOf<String>()
    if (lastWeekStats != null) parts += "上周完成 ${lastWeekStats.first}/${lastWeekStats.second}"
    if (settledWeeks.isNotEmpty()) {
        parts += "结算了 ${settledWeeks.size} 周（错过 $missed，结转 $carried）"
    }
    if (appendedWeeks.isNotEmpty()) {
        parts += "给 ${appendedWeeks.joinToString("、")} 补了 $appendedUnits 个单元"
    }
    if (scheduledCounts.isNotEmpty()) {
        parts += scheduledCounts.entries.joinToString("、") { "${it.key} 铺了 ${it.value} 个" }
    }
    parts += if (poolRemaining > 0) "待安排还剩 $poolRemaining 个" else "路线里的单元已经全部排进周计划"
    return if (parts.isEmpty()) {
        "节奏检查过了，没有需要动的地方（$week）。"
    } else {
        parts.joinToString("；") + "。"
    }
}
