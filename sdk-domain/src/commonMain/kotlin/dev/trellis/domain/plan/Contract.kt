package dev.trellis.domain.plan

import kotlinx.serialization.Serializable

/**
 * 计划系统的写命令 / 读视图契约（工程层权威）。
 *
 * 对应设计文档 `design/plan-system-backend.md`（v0.1）与
 * `design/plan-ui-contract.md`：UI 只拿 [DayView]/[WeekView]/[MapView]/[ReviewView] 这类
 * view model 与 [CommandResult]，不读库、不算统计。
 *
 * 所有 ID 是不透明字符串（`t_` + ULID），日期 `2026-10-28`，周 `2026-W44`，
 * 时间戳 ISO 8601 带偏移。展示时区由前端负责，服务端按注入的时钟算「今天/本周」。
 */
enum class Actor { USER, AGENT, SYSTEM }

/** 统一命令信封：所有写操作必须走它（`plan-system-backend.md` §5）。 */
@Serializable
data class CommandEnvelope(
    /** ULID；幂等键。 */
    val commandId: String,
    val actor: Actor,
    val planId: String? = null,
    /** 乐观并发：计划内最后事件 seq；null = 不检查。 */
    val expectedLastSeq: Long? = null,
    /** true = 只返回 diff/预览，不落库。 */
    val dryRun: Boolean = false,
    /** ui.today / ui.week / chat / review / scheduler。 */
    val origin: String,
)

@Serializable
enum class ErrorCode {
    E_INVALID,
    E_NOT_FOUND,
    E_CONFLICT,
    E_FORBIDDEN,
    E_STATE,
    E_UNDO_EXPIRED,
    E_STORE,
    E_UNSUPPORTED,
}

@Serializable
data class CommandError(
    val code: ErrorCode,
    /** 人话：UI 只展示它，按 [code] 决定是否给重试/撤销。 */
    val message: String,
    val retryable: Boolean = false,
    /** 冲突时返回最新 seq，调用方可刷新后重试。 */
    val latestSeq: Long? = null,
)

@Serializable
data class DiffChange(
    val summary: String,
    val from: String,
    val to: String,
    val reason: String = "",
)

@Serializable
data class PlanDiff(
    val summary: String,
    val changes: List<DiffChange> = emptyList(),
)

@Serializable
data class UndoInfo(
    val token: String,
    val label: String,
    /** 过期时刻（ISO 8601）。 */
    val expiresAt: String,
)

@Serializable
data class CommandResult<T>(
    val ok: Boolean,
    val value: T? = null,
    val undo: UndoInfo? = null,
    val diff: PlanDiff? = null,
    val warnings: List<String> = emptyList(),
    val error: CommandError? = null,
)

fun <T> ok(value: T, undo: UndoInfo? = null, diff: PlanDiff? = null, warnings: List<String> = emptyList()): CommandResult<T> =
    CommandResult(ok = true, value = value, undo = undo, diff = diff, warnings = warnings)

fun <T> fail(code: ErrorCode, message: String, retryable: Boolean = false, latestSeq: Long? = null): CommandResult<T> =
    CommandResult(ok = false, error = CommandError(code, message, retryable, latestSeq))

/** 计划聚合根的返回句柄。 */
@Serializable
data class CreatedProcess(val processId: String, val planId: String)

@Serializable
data class SavedDraft(val planId: String, val versionId: String)

@Serializable
data class AcceptedPlan(val planId: String, val versionId: String, val firstWeek: String)

@Serializable
data class VersionRef(val planId: String, val versionId: String, val week: String)

@Serializable
data class OccurrenceRef(val occurrenceId: String)

@Serializable
data class SessionRef(val sessionId: String)

@Serializable
data class TodoRef(val todoId: String)

@Serializable
data class SettleResult(val week: String, val missedCount: Int, val carriedCount: Int, val pendingCarry: Int)

@Serializable
data class ReviewRef(val planId: String, val week: String, val reviewId: String)
