package dev.trellis.feature.learning

import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandError
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.ErrorCode
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.domain.plan.PlanStateView
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.OpenNode
import dev.trellis.domain.plan.OverdueView
import dev.trellis.domain.plan.PoolUnit
import dev.trellis.domain.plan.UndoInfo
import dev.trellis.domain.plan.UndoView
import dev.trellis.domain.plan.ok
import dev.trellis.domain.plan.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

/**
 * `LearningPlanService` 的 SQLite 实现。
 *
 * 三条不变量（对应 `design/plan-system-backend.md` §2）：
 * 1. **事实只追加**：所有状态变化先写 `event`，投影表（plan/occurrence/…）在同事务内跟改变；
 *    event 表有 UPDATE/DELETE 触发器，改历史会直接 ABORT。
 * 2. **计划版本不可变**：改承诺 = 新 draft 版本 + `parent_id`；active/archived 版本的
 *    unit/commitment 一律不可改、不可删。
 * 3. **日层只减/移/缩**：一个 occurrence 的身份（unit/day/commitment）永不改写；
 *    顺延/拆分/缩短一律「旧行 superseded + 新行 moved_from」。
 *
 * 撤销用内存闭包（单进程、单用户）：撤销窗口 5 秒，`changeCommitment` 有效期 1 小时。
 * 进程重启后旧撤销失效 —— 补偿事件仍完整保留在 event 日志里，可审计。
 */
internal class SqliteLearningPlanService(
    private val db: PlanDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : LearningPlanService, AutoCloseable {

    internal val connection: Connection get() = db.connection

    /** 「今天」按注入的时钟与主时区算，查询层判断「是不是今天」时用它。 */
    internal fun todayDate(): LocalDate =
        java.time.Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val commandCache = mutableMapOf<String, CommandResult<*>>()
    private val undos = LinkedHashMap<String, UndoRecord>()
    private val undoByPlan = mutableMapOf<String, String>()
    private val syntheticActor = AtomicLong()

    internal class UndoRecord(
        val token: String,
        val label: String,
        val expiresAtMillis: Long,
        val planId: String?,
        val action: () -> String,
    )

    // ---------------------------------------------------------------- 时间与基础

    internal fun nowMillis(): Long = clock()

    internal fun nowIso(): String = PlanTime.isoTime(nowMillis(), zone)

    internal fun today(): LocalDate = PlanTime.now(::nowMillis, zone)

    internal fun currentWeek(): String = PlanTime.isoWeek(today())

    internal fun actorOf(env: CommandEnvelope): Actor = env.actor

    // ---------------------------------------------------------------- 事务与命令外壳

    /**
     * 写命令统一外壳：幂等（command_id）→ 乐观并发（expectedLastSeq）→ 事务 → 事件/补偿。
     *
     * `dryRun` 时同样的代码在事务里跑完再 rollback：预览走的是真逻辑，不会出现
     * 「预览说能改、真改报错」。
     */
    internal fun <T> write(env: CommandEnvelope, body: () -> CommandResult<T>): CommandResult<T> {
        if (env.commandId.isBlank()) {
            return fail(ErrorCode.E_INVALID, "缺 commandId，这次没动任何东西。")
        }
        commandCache[env.commandId]?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            return replay(cached as CommandResult<T>)
        }

        synchronized(lock) {
            commandCache[env.commandId]?.let { cached ->
                @Suppress("UNCHECKED_CAST")
                return replay(cached as CommandResult<T>)
            }

            if (knownCommand(env.commandId)) {
                return CommandResult(
                    ok = true,
                    warnings = listOf("这个命令之前已经执行过了，这次没有重复改动。"),
                )
            }

            connection.autoCommit = false
            try {
                val conflict: CommandResult<T>? = conflictOf(env)
                if (conflict != null) {
                    connection.rollback()
                    return conflict
                }
                val result = body()
                if (result.ok) {
                    if (env.dryRun) connection.rollback() else connection.commit()
                } else {
                    connection.rollback()
                }
                if (result.ok && !env.dryRun) {
                    commandCache[env.commandId] = result
                }
                return result
            } catch (t: Throwable) {
                runCatching { connection.rollback() }
                val message = t.message?.takeIf { it.isNotBlank() } ?: t::class.simpleName.orEmpty()
                return fail(ErrorCode.E_STORE, "没改成，数据没动：$message", retryable = true)
            } finally {
                connection.autoCommit = true
            }
        }
    }

    private fun <T> replay(cached: CommandResult<T>): CommandResult<T> =
        if (cached.ok && cached.warnings.isEmpty()) {
            cached.copy(warnings = listOf("这个命令之前已经执行过了，这次没有重复改动。"))
        } else {
            cached
        }

    private fun knownCommand(commandId: String): Boolean =
        connection.prepareStatement("SELECT 1 FROM event WHERE command_id = ? LIMIT 1").use { ps ->
            ps.setString(1, commandId)
            ps.executeQuery().use { rs -> rs.next() }
        }

    private fun <T> conflictOf(env: CommandEnvelope): CommandResult<T>? {
        val expected = env.expectedLastSeq ?: return null
        val planId = env.planId ?: return null
        val actual = latestSeq(planId) ?: 0L
        if (actual == expected) return null
        return fail(
            ErrorCode.E_CONFLICT,
            "刚刚在别处改过了，刷新一下再试。",
            retryable = true,
            latestSeq = actual,
        )
    }

    internal fun latestSeq(planId: String): Long? =
        connection.prepareStatement("SELECT MAX(seq) FROM event WHERE plan_id = ?").use { ps ->
            ps.setString(1, planId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1).takeIf { !rs.wasNull() } else null }
        }

    // ---------------------------------------------------------------- 事件

    internal fun appendEvent(
        type: String,
        entity: String,
        entityId: String?,
        planId: String?,
        payload: JsonObject,
        actor: Actor,
        commandId: String?,
        undoOf: String? = null,
    ): Long {
        val at = nowIso()
        connection.prepareStatement(
            "INSERT INTO event(event_id,type,entity,entity_id,payload_json,occurred_at,recorded_at," +
                "actor,command_id,undo_of,plan_id) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            PreparedStatement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setString(1, Ids.next("ev_"))
            ps.setString(2, type)
            ps.setString(3, entity)
            ps.setString(4, entityId)
            ps.setString(5, payload.toString())
            ps.setString(6, at)
            ps.setString(7, at)
            ps.setString(8, actor.name.lowercase())
            ps.setString(9, commandId)
            ps.setString(10, undoOf)
            ps.setString(11, planId)
            ps.executeUpdate()
            ps.generatedKeys.use { rs -> rs.next(); return rs.getLong(1) }
        }
    }

    internal fun eventJson(vararg pairs: Pair<String, JsonPrimitive>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // ---------------------------------------------------------------- 撤销

    internal fun registerUndo(
        env: CommandEnvelope,
        planId: String?,
        label: String,
        ttlMillis: Long = 5_000,
        action: () -> String,
    ): UndoInfo {
        val token = env.commandId
        val expiresAt = nowMillis() + ttlMillis
        undos[token] = UndoRecord(token, label, expiresAt, planId, action)
        planId?.let { undoByPlan[it] = token }
        return UndoInfo(token = token, label = label, expiresAt = PlanTime.isoTime(expiresAt, zone))
    }

    internal fun latestUndoView(planId: String?): UndoView? {
        val token = planId?.let { undoByPlan[it] } ?: return null
        val record = undos[token] ?: return null
        if (record.expiresAtMillis in 1..nowMillis()) {
            undos.remove(token)
            undoByPlan.remove(planId)
            return null
        }
        return UndoView(token = record.token, label = record.label, expiresAt = PlanTime.isoTime(record.expiresAtMillis, zone))
    }

    internal fun takeUndo(token: String): UndoRecord? = undos[token]

    internal fun dropUndo(record: UndoRecord) {
        undos.remove(record.token)
        record.planId?.let { if (undoByPlan[it] == record.token) undoByPlan.remove(it) }
    }

    // ---------------------------------------------------------------- 读取底层

    internal fun planStatus(planId: String): String? =
        connection.prepareStatement("SELECT status FROM plan WHERE id = ?").use { ps ->
            ps.setString(1, planId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    internal fun planOfVersion(versionId: String): String? =
        connection.prepareStatement("SELECT plan_id FROM plan_version WHERE id = ?").use { ps ->
            ps.setString(1, versionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    internal fun versionStatus(versionId: String): String? =
        connection.prepareStatement("SELECT status FROM plan_version WHERE id = ?").use { ps ->
            ps.setString(1, versionId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    internal fun currentVersion(planId: String): String? =
        connection.prepareStatement("SELECT current_version_id FROM plan WHERE id = ?").use { ps ->
            ps.setString(1, planId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    internal fun planTitle(planId: String): String =
        connection.prepareStatement(
            "SELECT p.title FROM process p JOIN plan pl ON pl.process_id = p.id WHERE pl.id = ?",
        ).use { ps ->
            ps.setString(1, planId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
        }

    internal fun occurrenceRow(id: String): OccurrenceRow? =
        connection.prepareStatement(
            "SELECT o.id, o.commitment_id, o.unit_id, o.todo_id, o.day, o.estimate_minutes, o.mode, " +
                "o.status, o.note, o.moved_from, o.carried_from, o.created_at, o.completed_at, " +
                "pv.plan_id, c.week FROM occurrence o " +
                "LEFT JOIN commitment c ON c.id = o.commitment_id " +
                "LEFT JOIN plan_version pv ON pv.id = c.plan_version_id WHERE o.id = ?",
        ).use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toOccurrence() else null }
        }

    internal fun commitmentRow(commitmentId: String): CommitmentRow? =
        connection.prepareStatement(
            "SELECT c.id, c.week, pv.plan_id, pv.id, pv.status FROM commitment c " +
                "JOIN plan_version pv ON pv.id = c.plan_version_id WHERE c.id = ?",
        ).use { ps ->
            ps.setString(1, commitmentId)
            ps.executeQuery().use { rs ->
                if (rs.next()) CommitmentRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) else null
            }
        }

    internal fun unitRow(unitId: String): UnitRow? =
        connection.prepareStatement(
            "SELECT u.id, u.plan_version_id, u.chapter, u.title, u.estimate_minutes, u.min_version_minutes, " +
                "u.material_segment_id FROM plan_unit u WHERE u.id = ?",
        ).use { ps ->
            ps.setString(1, unitId)
            ps.executeQuery().use { rs ->
                if (rs.next()) UnitRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getInt(5), rs.getInt(6), rs.getString(7),
                ) else null
            }
        }

    internal fun insertOccurrence(
        commitmentId: String?,
        unitId: String?,
        todoId: String?,
        day: String,
        minutes: Int,
        mode: String,
        movedFrom: String? = null,
        carriedFrom: String? = null,
    ): String {
        val id = Ids.next("occ_")
        connection.prepareStatement(
            "INSERT INTO occurrence(id,commitment_id,unit_id,todo_id,day,estimate_minutes,mode,status," +
                "moved_from,carried_from,created_at) VALUES(?,?,?,?,?,?,?, 'planned',?,?,?)",
        ).use { ps ->
            ps.setString(1, id)
            ps.setString(2, commitmentId)
            ps.setString(3, unitId)
            ps.setString(4, todoId)
            ps.setString(5, day)
            ps.setInt(6, minutes)
            ps.setString(7, mode)
            ps.setString(8, movedFrom)
            ps.setString(9, carriedFrom)
            ps.setString(10, nowIso())
            ps.executeUpdate()
        }
        return id
    }

    internal fun supersede(oldId: String, newId: String) {
        connection.prepareStatement(
            "UPDATE occurrence SET status = 'superseded', superseded_by = ? WHERE id = ? AND status = 'planned'",
        ).use { ps ->
            ps.setString(1, newId)
            ps.setString(2, oldId)
            ps.executeUpdate()
        }
    }

    internal fun requirePlan(planId: String?): CommandResult<String> {
        if (planId.isNullOrBlank()) return fail(ErrorCode.E_NOT_FOUND, "还没有计划，先建一个。")
        return planStatus(planId)?.let { ok(planId) } ?: fail(ErrorCode.E_NOT_FOUND, "找不到这个计划。")
    }

    internal fun effectivePlanForToday(planId: String?): String? = planId ?: connection.prepareStatement(
        "SELECT id FROM plan WHERE status = 'active' ORDER BY created_at LIMIT 1",
    ).use { ps ->
        ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    // ---------------------------------------------------------------- 查询（投影）

    override fun today(planId: String?, day: String): dev.trellis.domain.plan.DayView =
        buildDayView(planId = effectivePlanForToday(planId), day = day)

    override fun week(planId: String, week: String): dev.trellis.domain.plan.WeekView =
        buildWeekView(planId, week)

    override fun map(planId: String): dev.trellis.domain.plan.MapView = buildMapView(planId)

    override fun review(planId: String, week: String): dev.trellis.domain.plan.ReviewView =
        buildReviewView(planId, week)

    override fun planState(planId: String): PlanStateView = buildPlanState(planId)

    override fun activePlanId(): String? = effectivePlanForToday(null)

    override fun plans(): List<LearningPlanInfo> = connection.prepareStatement(
        "SELECT p.id, pr.title, p.status FROM plan p JOIN process pr ON pr.id = p.process_id ORDER BY p.created_at",
    ).use { ps ->
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(LearningPlanInfo(rs.getString(1), rs.getString(2), rs.getString(3)))
            }
        }
    }

    override fun openNodes(planId: String, untilDay: String): List<OpenNode> = openNodesOf(planId, untilDay)

    override fun overdue(planId: String, today: String): OverdueView = overdueOf(planId, today)

    /**
     * 待安排：没被「本周及以后」的承诺收走的单元。
     *
     * 与旧语义的区别：过去周没做完的单元也算在这里（用户定的「放弃 = 放回未安排桶」）。
     * 已经有一个 `done` occurrence 的单元不算欠账，不再回到池里。
     */
    override fun pool(planId: String): List<PoolUnit> {
        val versionId = currentVersion(planId) ?: return emptyList()
        return connection.prepareStatement(
            "SELECT u.id, u.title, u.chapter, u.estimate_minutes FROM plan_unit u " +
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
                    while (rs.next()) add(PoolUnit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
                }
            }
        }
    }

    override fun todos(planId: String?): List<dev.trellis.domain.plan.TodoView> = queryTodos(planId)

    override fun asOfCompletedCount(planId: String, week: String, seq: Long): Int {
        connection.prepareStatement(
            "SELECT COUNT(DISTINCT json_extract(payload_json, '$.unitId')) FROM event " +
                "WHERE type = 'occurrence.completed' AND plan_id = ? AND seq <= ? " +
                "AND json_extract(payload_json, '$.week') = ?",
        ).use { ps ->
            ps.setString(1, planId)
            ps.setLong(2, seq)
            ps.setString(3, week)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    // ---------------------------------------------------------------- 命令（转交 PlanCommands）

    override fun createProcess(
        env: CommandEnvelope, title: String, goal: String, deadline: String?, weeklyCapacityMinutes: Int?,
    ) = cmdCreateProcess(env, title, goal, deadline, weeklyCapacityMinutes)

    override fun saveDraft(
        env: CommandEnvelope, planId: String, versionId: String?,
        units: List<dev.trellis.domain.plan.UnitDraft>, commitments: List<dev.trellis.domain.plan.CommitmentDraft>,
    ) = cmdSaveDraft(env, planId, versionId, units, commitments)

    override fun acceptDraft(env: CommandEnvelope, versionId: String, firstWeek: String) =
        cmdAcceptDraft(env, versionId, firstWeek)

    override fun changeCommitment(
        env: CommandEnvelope, versionId: String, week: String, unitIds: List<String>,
        reason: String, diff: dev.trellis.domain.plan.PlanDiff?,
    ) = cmdChangeCommitment(env, versionId, week, unitIds, reason, diff)

    override fun pausePlan(env: CommandEnvelope, planId: String, reason: String) = cmdPausePlan(env, planId, reason)

    override fun setPlanDailyMinutes(env: CommandEnvelope, planId: String, dailyMinutes: Int) =
        cmdSetPlanDailyMinutes(env, planId, dailyMinutes)

    override fun resumePlan(env: CommandEnvelope, planId: String) = cmdResumePlan(env, planId)

    override fun endPlan(env: CommandEnvelope, planId: String, reason: String) = cmdEndPlan(env, planId, reason)

    override fun scheduleUnit(env: CommandEnvelope, commitmentId: String, unitId: String, day: String) =
        cmdScheduleUnit(env, commitmentId, unitId, day)

    override fun placeUnit(
        env: CommandEnvelope, planId: String, week: String, unitId: String, day: String,
    ) = cmdPlaceUnit(env, planId, week, unitId, day)

    override fun moveOccurrence(env: CommandEnvelope, occurrenceId: String, toDay: String, reason: String?) =
        cmdMoveOccurrence(env, occurrenceId, toDay, reason)

    override fun splitOccurrence(
        env: CommandEnvelope, occurrenceId: String, parts: List<dev.trellis.domain.plan.SplitPart>,
    ) = cmdSplitOccurrence(env, occurrenceId, parts)

    override fun shortenOccurrence(env: CommandEnvelope, occurrenceId: String, toEstimateMinutes: Int) =
        cmdShortenOccurrence(env, occurrenceId, toEstimateMinutes)

    override fun skipOccurrence(env: CommandEnvelope, occurrenceId: String, reasonChip: String) =
        cmdSkipOccurrence(env, occurrenceId, reasonChip)

    override fun completeOccurrence(env: CommandEnvelope, occurrenceId: String, note: String?) =
        cmdCompleteOccurrence(env, occurrenceId, note)

    override fun uncompleteOccurrence(env: CommandEnvelope, occurrenceId: String) =
        cmdUncompleteOccurrence(env, occurrenceId)

    override fun undoLast(env: CommandEnvelope, undoToken: String) = cmdUndoLast(env, undoToken)

    override fun startSession(env: CommandEnvelope, occurrenceId: String, source: String) =
        cmdStartSession(env, occurrenceId, source)

    override fun endSession(env: CommandEnvelope, sessionId: String) = cmdEndSession(env, sessionId)

    override fun addTodo(env: CommandEnvelope, title: String, estimateMinutes: Int?) =
        cmdAddTodo(env, title, estimateMinutes)

    override fun completeTodo(env: CommandEnvelope, todoId: String) = cmdCompleteTodo(env, todoId)

    override fun closeTodo(env: CommandEnvelope, todoId: String) = cmdCloseTodo(env, todoId)

    override fun scheduleTodo(
        env: CommandEnvelope, todoId: String, day: String, planId: String, commitmentId: String, estimateMinutes: Int,
    ) = cmdScheduleTodo(env, todoId, day, planId, commitmentId, estimateMinutes)

    override fun scheduleWeek(env: CommandEnvelope, week: String) = cmdScheduleWeek(env, week)

    override fun advanceRhythm(env: CommandEnvelope, planId: String) = advanceRhythmInternal(env, planId)

    override fun settleWeek(env: CommandEnvelope, planId: String, week: String, today: String) =
        cmdSettleWeek(env, planId, week, today)

    override fun generateReview(env: CommandEnvelope, planId: String, week: String, today: String) =
        cmdGenerateReview(env, planId, week, today)

    override fun exportJsonl(planId: String): String = exportEvents(planId)

    override fun importJsonl(jsonl: String): CommandResult<Unit> =
        fail(ErrorCode.E_UNSUPPORTED, "导入还没做：这一版只支持导出事件日志（可审计），恢复通道后面补。")

    override fun close() {
        db.close()
    }

    internal fun newActorSuffix(): Long = syntheticActor.incrementAndGet()

    internal fun json() = json
}

// -------------------------------------------------------------------- 行映射

internal data class OccurrenceRow(
    val id: String,
    val commitmentId: String?,
    val unitId: String?,
    val todoId: String?,
    val day: String,
    val minutes: Int,
    val mode: String,
    val status: String,
    val note: String?,
    val movedFrom: String?,
    val carriedFrom: String?,
    val createdAt: String,
    val completedAt: String?,
    val planId: String?,
    val week: String?,
)

internal data class CommitmentRow(
    val id: String,
    val week: String,
    val planId: String,
    val versionId: String,
    val versionStatus: String,
)

internal data class UnitRow(
    val id: String,
    val versionId: String,
    val chapter: String,
    val title: String,
    val estimateMinutes: Int,
    val minVersionMinutes: Int,
    val materialSegmentId: String?,
)

internal fun ResultSet.toOccurrence(): OccurrenceRow = OccurrenceRow(
    id = getString(1),
    commitmentId = getString(2),
    unitId = getString(3),
    todoId = getString(4),
    day = getString(5),
    minutes = getInt(6),
    mode = getString(7),
    status = getString(8),
    note = getString(9),
    movedFrom = getString(10),
    carriedFrom = getString(11),
    createdAt = getString(12),
    completedAt = getString(13),
    planId = getString(14),
    week = getString(15),
)

internal fun CommandResult<*>.errorMessage(): String? = error?.message
internal fun CommandError.asFailure(): CommandResult<Unit> = fail(code, message, retryable, latestSeq)
