package dev.trellis.feature.plan

import dev.trellis.domain.PlanService
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.extension.DataField
import dev.trellis.kernel.extension.DataProvider
import dev.trellis.kernel.time.KernelClock

/**
 * 把 [PlanService] 的统计暴露给声明式插件，命名空间是 `summary`。
 *
 * 于是任何一个人（或 Agent）写的 JSON 卡片都能写：
 *
 * ```
 * { "body": "{{ summary.dueToday }} 项今天到期，{{ summary.overdue }} 项已逾期" }
 * ```
 *
 * ### 关于「今天」
 *
 * `dueToday` 是有的，但**它不猜时区**  边界从 [DataContext] 里来，
 * 而那个上下文是渲染层用用户的时区算好传下来的。
 * 这一层只负责「按给定的边界数一数」，不负责「现在算哪一天」。
 */
internal class PlanSummaryDataProvider(
    private val plan: PlanService,
    private val clock: KernelClock,
) : DataProvider {

    override val id: String = NAMESPACE

    /** 排在其他提供者前面：它是核心数据，冲突时该它赢。 */
    override val order: Int = 10

    override val fields: List<DataField> = listOf(
        DataField("total", "任务总数"),
        DataField("done", "已完成数量"),
        DataField("doing", "进行中数量"),
        DataField("todo", "还没完成的数量"),
        DataField("overdue", "已经逾期还没完成的数量"),
        DataField("dueToday", "今天到期（截至今天结束）的数量"),
        DataField("spentMinutes", "已经投入的总分钟数"),
        DataField("streakDays", "连续多少天有完成记录（断一天就归零）"),
        DataField("completionRate", "完成率，0 到 100 的整数（百分比）"),
        DataField("oldestOpenTask", "最早创建、还没完成的那条任务的标题"),
    )

    override suspend fun resolve(path: String, context: DataContext): String? {
        val summary = plan.summary(context.nowMs, context.dayEndMs)
        return when (path) {
            "total" -> summary.total.toString()
            "done" -> summary.done.toString()
            "doing" -> summary.doing.toString()
            "todo" -> (summary.total - summary.done).toString()
            "overdue" -> summary.overdue.toString()
            "dueToday" -> summary.dueToday.toString()
            "spentMinutes" -> summary.spentMinutes.toString()
            "streakDays" -> summary.streakDays.toString()
            "completionRate" -> summary.completionRate.toString()
            "oldestOpenTask" -> summary.oldestOpenTask
            else -> null
        }
    }

    private companion object {
        const val NAMESPACE = "summary"
    }
}
