package dev.trellis.feature.plan

import dev.trellis.domain.PlanService
import dev.trellis.kernel.extension.DataProvider
import dev.trellis.kernel.time.KernelClock

private const val DAY_MS = 24L * 60L * 60L * 1000L

/**
 * 把 [PlanService] 的统计暴露给声明式插件，命名空间是 `summary`。
 *
 * 于是任何一个人（或 Agent）写的 JSON 卡片都能写：
 *
 * ```
 * { "body": "{{ summary.todo }} 项待办，{{ summary.overdue }} 项逾期" }
 * ```
 *
 * ### 为什么刻意没有 dueToday
 *
 * 因为「今天」需要时区，而时区是**展示层**的信息  领域层和内核都不该猜。
 * 所以这里给的是 `dueSoon`（未来 24 小时内），一个不需要时区就能定义清楚的概念。
 *
 * 想要真正的「今天」，那是渲染层拿着时区算出来再传进来的事。
 */
internal class PlanSummaryDataProvider(
    private val plan: PlanService,
    private val clock: KernelClock,
) : DataProvider {

    override val id: String = NAMESPACE

    /** 排在其他提供者前面：它是核心数据，冲突时该它赢。 */
    override val order: Int = 10

    override suspend fun resolve(path: String): String? {
        val now = clock.nowMs()
        val s = plan.summary(now, now + DAY_MS)
        return when (path) {
            "total" -> s.total.toString()
            "done" -> s.done.toString()
            "doing" -> s.doing.toString()
            "todo" -> (s.total - s.done).toString()
            "overdue" -> s.overdue.toString()
            "dueSoon" -> s.dueToday.toString()
            "spentMinutes" -> s.spentMinutes.toString()
            else -> null
        }
    }

    private companion object {
        const val NAMESPACE = "summary"
    }
}
