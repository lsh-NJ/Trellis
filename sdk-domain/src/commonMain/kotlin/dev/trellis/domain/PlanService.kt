package dev.trellis.domain

/**
 * 计划与任务的读写契约。
 *
 * **这是永久契约，不是实现。** 插件通过它读写数据，而不是直接碰存储 
 * 所以将来把底层的 KV 换成 SQLite 时，所有第三方插件一行都不用改。
 *
 * 它由内置插件 `core.plan` 实现（`required = true`），并在内核里以服务的形式发布：
 *
 * ```kotlin
 * val plan = ctx.services.require(PlanService::class)
 * val due = plan.tasks(TaskQuery(dueBefore = tomorrow))
 * ```
 */
interface PlanService {

    // ---- 计划 ----

    suspend fun createPlan(title: String, targetDate: Long? = null): Plan

    suspend fun plans(): List<Plan>

    suspend fun plan(id: PlanId): Plan?

    suspend fun deletePlan(id: PlanId)

    // ---- 任务 ----

    suspend fun addTask(
        title: String,
        planId: PlanId? = null,
        dueAt: Long? = null,
        estimateMinutes: Int? = null,
    ): Task

    suspend fun task(id: TaskId): Task?

    suspend fun tasks(query: TaskQuery = TaskQuery()): List<Task>

    /** 整条覆盖保存。用于改标题、改截止时间这类编辑。 */
    suspend fun save(task: Task): Task

    suspend fun setStatus(id: TaskId, status: TaskStatus): Task?

    /** 追加投入的时间（时间追踪）。 */
    suspend fun logTime(id: TaskId, minutes: Int): Task?

    /** 写下任务结束后的总结。 */
    suspend fun summarize(id: TaskId, note: String): Task?

    suspend fun deleteTask(id: TaskId)

    // ---- 统计 ----

    /**
     * 统计。
     *
     * [dayEndMs] 由调用方给（通常是 UI 层算出的「今天结束」时刻）
     * 领域层不该猜时区，那是展示层的信息。
     */
    suspend fun summary(nowMs: Long, dayEndMs: Long): PlanSummary
}
