package dev.trellis.feature.plan

import dev.trellis.domain.Plan
import dev.trellis.domain.PlanId
import dev.trellis.domain.PlanService
import dev.trellis.domain.PlanSummary
import dev.trellis.domain.Task
import dev.trellis.domain.TaskId
import dev.trellis.domain.TaskQuery
import dev.trellis.domain.TaskStatus
import dev.trellis.kernel.event.KernelEvent
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.time.KernelClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * [PlanService] 在 KV 存储上的实现。
 *
 * ### 为什么存储格式的 JSON 策略和插件清单**相反**
 *
 * 插件清单用严格模式（未知字段就报错），因为那是**人或 Agent 写的**，
 * 把 title 拼成 tittle 必须当场炸出来。
 *
 * 但存储数据是**程序自己写的**，里面的未知字段只可能来自版本升级
 * （新版本写了新字段，用户回退到旧版本）。这时候宽容才不会丢数据 
 * 所以这里用 `ignoreUnknownKeys = true`。
 *
 * 同一个库、两个相反的策略，因为**失败模式不同**：一个是作者写错，一个是版本错位。
 *
 * ### 已知的低效
 *
 * 列表查询是「列出所有 task: 键 -> 逐个读取」。任务量大时会慢。
 * 等真实的 SQL 后端落地时，这一层会换成一条带索引的查询 
 * 而因为上层只依赖 [PlanService] 接口，**所有调用方一行都不用改**。
 */
internal class KvPlanService(
    private val kv: KvStore,
    private val clock: KernelClock,
    private val log: (String) -> Unit = {},
    private val emit: suspend (KernelEvent) -> Unit = {},
) : PlanService {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 串行化自己的写操作。主要是为了保护自增序列，避免并发创建拿到同一个 id。 */
    private val writeLock = Mutex()

    // ---------------------------------------------------------------- 计划

    override suspend fun createPlan(title: String, targetDate: Long?): Plan = writeLock.withLock {
        val plan = Plan(
            id = PlanId(nextId(PLAN_ID_PREFIX)),
            title = title.trim(),
            targetDate = targetDate,
            createdAt = clock.nowMs(),
        )
        write(planKey(plan.id), Plan.serializer(), plan)
        plan
    }

    override suspend fun plans(): List<Plan> =
        readAll(PLAN_KEY_PREFIX, Plan.serializer()).sortedBy { it.createdAt }

    override suspend fun plan(id: PlanId): Plan? = read(planKey(id), Plan.serializer())

    override suspend fun deletePlan(id: PlanId) = writeLock.withLock {
        plan(id) ?: return@withLock
        kv.delete(planKey(id))
        // 计划没了，它的任务降级成「不属于任何计划」的普通待办，而不是被连带删掉。
        // 用户的笔记不该因为整理归类而消失。
        for (task in tasks(TaskQuery(planId = id))) {
            write(taskKey(task.id), Task.serializer(), task.copy(planId = null))
        }
    }

    // ---------------------------------------------------------------- 任务

    override suspend fun addTask(
        title: String,
        planId: PlanId?,
        dueAt: Long?,
        estimateMinutes: Int?,
    ): Task = writeLock.withLock {
        val task = Task(
            id = TaskId(nextId(TASK_ID_PREFIX)),
            title = title.trim(),
            planId = planId,
            dueAt = dueAt,
            estimateMinutes = estimateMinutes,
            createdAt = clock.nowMs(),
        )
        write(taskKey(task.id), Task.serializer(), task)
        task
    }

    override suspend fun task(id: TaskId): Task? = read(taskKey(id), Task.serializer())

    override suspend fun tasks(query: TaskQuery): List<Task> {
        // 先把查询条件读进局部变量：Kotlin 不允许对**跨模块的公开属性**做智能转换
        // （它可能是自定义 getter，每次读都不同）。这是跨模块开发很常见的一个坑。
        val planId = query.planId
        val status = query.status
        val dueBefore = query.dueBefore
        val includeDone = query.includeDone

        return readAll(TASK_KEY_PREFIX, Task.serializer())
            .filter { planId == null || it.planId == planId }
            .filter { status == null || it.status == status }
            .filter { includeDone || it.status != TaskStatus.DONE }
            .filter { dueBefore == null || it.dueAt?.let { due -> due <= dueBefore } == true }
            .sortedWith(
                // 没做完的在前；都做完或都没做完时按截止时间；再按创建时间兜底（保证顺序确定）
                compareBy(
                    { it.status == TaskStatus.DONE },
                    { it.dueAt ?: Long.MAX_VALUE },
                    { it.createdAt },
                ),
            )
    }

    override suspend fun save(task: Task): Task = writeLock.withLock {
        write(taskKey(task.id), Task.serializer(), task)
        task
    }

    override suspend fun setStatus(id: TaskId, status: TaskStatus): Task? = writeLock.withLock {
        val current = task(id) ?: return@withLock null
        val updated = current.copy(
            status = status,
            completedAt = if (status == TaskStatus.DONE) clock.nowMs() else null,
        )
        write(taskKey(id), Task.serializer(), updated)

        // 只在「第一次变成完成」时广播。重复设成完成不重复发，
        // 否则一条规则会在用户连点几下时被触发好几次。
        if (status == TaskStatus.DONE && current.status != TaskStatus.DONE) {
            emit(
                KernelEvent(
                    "task.completed",
                    mapOf("taskId" to updated.id.value, "title" to updated.title),
                ),
            )
        }
        updated
    }

    override suspend fun logTime(id: TaskId, minutes: Int): Task? = writeLock.withLock {
        require(minutes > 0) { "追加的时间必须是正数" }
        val current = task(id) ?: return@withLock null
        val updated = current.copy(spentMinutes = current.spentMinutes + minutes)
        write(taskKey(id), Task.serializer(), updated)
        updated
    }

    override suspend fun summarize(id: TaskId, note: String): Task? = writeLock.withLock {
        val current = task(id) ?: return@withLock null
        val updated = current.copy(note = note.trim())
        write(taskKey(id), Task.serializer(), updated)
        updated
    }

    override suspend fun deleteTask(id: TaskId) = writeLock.withLock {
        kv.delete(taskKey(id))
        Unit
    }

    // ---------------------------------------------------------------- 统计

    override suspend fun summary(nowMs: Long, dayEndMs: Long): PlanSummary {
        val all = readAll(TASK_KEY_PREFIX, Task.serializer())
        return PlanSummary(
            total = all.size,
            done = all.count { it.status == TaskStatus.DONE },
            doing = all.count { it.status == TaskStatus.DOING },
            overdue = all.count { !it.isDone && it.dueAt?.let { due -> due < nowMs } == true },
            dueToday = all.count {
                !it.isDone && it.dueAt?.let { due -> due >= nowMs && due < dayEndMs } == true
            },
            spentMinutes = all.sumOf { it.spentMinutes },
        )
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun nextId(prefix: String): String {
        val next = (kv.get(SEQ_KEY)?.toLongOrNull() ?: 0L) + 1L
        kv.put(SEQ_KEY, next.toString())
        return prefix + next.toString().padStart(6, '0')
    }

    private suspend fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val text = kv.get(key) ?: return null
        return try {
            json.decodeFromString(serializer, text)
        } catch (t: Exception) {
            // 单条记录坏掉不该让整个列表打不开。记进插件日志，诊断面板能看到。
            log("跳过无法解析的记录 " + key + "：" + t.message)
            null
        }
    }

    private suspend fun <T> readAll(prefix: String, serializer: KSerializer<T>): List<T> =
        kv.keys(prefix).mapNotNull { read(it, serializer) }

    private suspend fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        kv.put(key, json.encodeToString(serializer, value))
    }

    private fun planKey(id: PlanId): String = PLAN_KEY_PREFIX + id.value

    private fun taskKey(id: TaskId): String = TASK_KEY_PREFIX + id.value

    private companion object {
        // 用固定前缀 + 补零序号，而不是随机 id：
        // 顺序确定、可读、测试里能直接断言，而且不需要平台随机源。
        // id 前缀用于生成主键，键前缀用于在 KV 里分类存放  两者不要混用
        const val PLAN_ID_PREFIX = "plan-"
        const val TASK_ID_PREFIX = "task-"
        const val SEQ_KEY = "meta:seq"

        const val PLAN_KEY_PREFIX = "plan:"
        const val TASK_KEY_PREFIX = "task:"
    }
}
