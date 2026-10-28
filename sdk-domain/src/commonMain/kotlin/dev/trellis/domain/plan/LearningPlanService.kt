package dev.trellis.domain.plan

/**
 * 计划系统的读写契约（`plan-system-backend.md` §5/§12）。
 *
 * 实现放在 `:features:learning-process`：领域 + 命令 + 查询 + 迁移，
 * 存储适配器（SQLite）只在这一层下面，契约不暴露表结构。
 *
 * 所有写方法接收 [CommandEnvelope]；返回 [CommandResult]，失败带人话错误。
 * 查询方法只读投影表（当前态），历史统计由 [asOfCompletedCount] / 复盘快照负责。
 */
interface LearningPlanService {

    // ---------------- 查询 ----------------

    /** 今天：occurrences + 已完成 + 待办。`planId` 为空时取唯一活跃计划。 */
    fun today(planId: String?, day: String): DayView
    fun week(planId: String, week: String): WeekView

    fun map(planId: String): MapView

    /** 周复盘；未生成时返回 state = not_ready 的空视图。 */
    fun review(planId: String, week: String): ReviewView

    fun planState(planId: String): PlanStateView

    /**
     * 当前唯一活跃计划的 id；没有计划时返回 null。
     *
     * 给界面用：进入计划页时先问「现在是哪个计划」，再拿 [map] / [week] / [today] 取内容。
     * 与 [today] 的 `planId = null` 语义一致（唯一活跃计划）。
     */
    fun activePlanId(): String?

    /** 所有计划（不分活跃/归档）；计划页左栏「全部计划」用。 */
    fun plans(): List<LearningPlanInfo>

    /** 还没被任何一周承诺收进去的单元（节点池）；空列表 = 全排完了。 */
    fun pool(planId: String): List<PoolUnit>

    /**
     * 某个单元本周被排到的那一次 occurrence；还没排到具体某天就是 null。
     *
     * 计时器要把时间落成 session，而 session 只能挂在 occurrence 上，所以界面得先拿到它。
     * 默认返回 null：不实现学习计划的适配器不必关心这件事。
     */
    fun unitOccurrenceId(planId: String, week: String, unitId: String): String? = null

    fun todos(planId: String? = null): List<TodoView>

    /** 截至事件序号 [seq] 的某周完成单元数（历史不可变，用于审计/黄金测试）。 */
    fun asOfCompletedCount(planId: String, week: String, seq: Long): Int

    // ---------------- 计划层命令 ----------------

    fun createProcess(env: CommandEnvelope, title: String, goal: String, deadline: String? = null, weeklyCapacityMinutes: Int? = null): CommandResult<CreatedProcess>

    fun saveDraft(env: CommandEnvelope, planId: String, versionId: String?, units: List<UnitDraft>, commitments: List<CommitmentDraft>): CommandResult<SavedDraft>

    fun acceptDraft(env: CommandEnvelope, versionId: String, firstWeek: String): CommandResult<AcceptedPlan>

    fun changeCommitment(env: CommandEnvelope, versionId: String, week: String, unitIds: List<String>, reason: String, diff: PlanDiff? = null): CommandResult<VersionRef>

    fun pausePlan(env: CommandEnvelope, planId: String, reason: String): CommandResult<Unit>

    fun resumePlan(env: CommandEnvelope, planId: String): CommandResult<Unit>

    fun endPlan(env: CommandEnvelope, planId: String, reason: String): CommandResult<Unit>

    // ---------------- 日层命令（只减/移/缩，不加） ----------------

    fun scheduleUnit(env: CommandEnvelope, commitmentId: String, unitId: String, day: String): CommandResult<OccurrenceRef>

    fun moveOccurrence(env: CommandEnvelope, occurrenceId: String, toDay: String, reason: String? = null): CommandResult<OccurrenceRef>

    fun splitOccurrence(env: CommandEnvelope, occurrenceId: String, parts: List<SplitPart>): CommandResult<List<OccurrenceRef>>

    fun shortenOccurrence(env: CommandEnvelope, occurrenceId: String, toEstimateMinutes: Int): CommandResult<OccurrenceRef>

    fun skipOccurrence(env: CommandEnvelope, occurrenceId: String, reasonChip: String): CommandResult<Unit>

    fun completeOccurrence(env: CommandEnvelope, occurrenceId: String, note: String? = null): CommandResult<OccurrenceRef>

    /** 把「已完成」改回「没做」（误点恢复）。只允许 done → planned。 */
    fun uncompleteOccurrence(env: CommandEnvelope, occurrenceId: String): CommandResult<OccurrenceRef>

    fun undoLast(env: CommandEnvelope, undoToken: String): CommandResult<Unit>

    fun startSession(env: CommandEnvelope, occurrenceId: String, source: String = "manual"): CommandResult<SessionRef>

    fun endSession(env: CommandEnvelope, sessionId: String): CommandResult<Unit>

    // ---------------- 待办 ----------------

    fun addTodo(env: CommandEnvelope, title: String, estimateMinutes: Int? = null): CommandResult<TodoRef>

    fun completeTodo(env: CommandEnvelope, todoId: String): CommandResult<Unit>

    fun closeTodo(env: CommandEnvelope, todoId: String): CommandResult<Unit>

    /** 待办排期：复制成 occurrence（relation=scheduled_from），待办转 scheduled。 */
    fun scheduleTodo(env: CommandEnvelope, todoId: String, day: String, planId: String, commitmentId: String, estimateMinutes: Int = 25): CommandResult<OccurrenceRef>

    // ---------------- 周节奏（系统命令，幂等） ----------------

    /**
     * 把当前版本某一周的承诺铺到天（一天一个单元；已有 occurrence 则不动）。
     *
     * 触发时机由调用方决定：这周变成当周、或周日提前铺下周。幂等，可重复调。
     */
    fun scheduleWeek(env: CommandEnvelope, week: String): CommandResult<Unit>

    fun settleWeek(env: CommandEnvelope, planId: String, week: String, today: String): CommandResult<SettleResult>

    fun generateReview(env: CommandEnvelope, planId: String, week: String, today: String): CommandResult<ReviewRef>

    // ---------------- 迁移 ----------------

    fun exportJsonl(planId: String): String

    fun importJsonl(jsonl: String): CommandResult<Unit>
}

/** 草稿里的一章/一个单元；时长 10–45 分钟。 */
@kotlinx.serialization.Serializable
data class UnitDraft(
    val chapter: String,
    val title: String,
    val estimateMinutes: Int = 25,
    val minVersionMinutes: Int = 10,
    val materialSegmentId: String? = null,
)

/** 草稿里的一周承诺：`week` 是 ISO 周，`unitIndexes` 指向 [UnitDraft] 列表下标。 */
@kotlinx.serialization.Serializable
data class CommitmentDraft(
    val week: String,
    val unitIndexes: List<Int>,
)

@kotlinx.serialization.Serializable
data class SplitPart(val day: String, val estimateMinutes: Int)
