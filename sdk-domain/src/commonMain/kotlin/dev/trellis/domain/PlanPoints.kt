package dev.trellis.domain

import dev.trellis.kernel.extension.Cardinality
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.OrderedContribution
import dev.trellis.kernel.core.ExtensionPointId

/**
 * 领域扩展点。
 *
 * 这里是「学习方法像插件一样可插拔」的落点：内核只认识「扩展点」这个概念，
 * 而「排程策略」「统计指标」这些**业务挂载位**由领域层定义。
 *
 * 第三方插件（包括 Agent 生成的）声明并实现它们，就能改变程序的行为，
 * 而不需要改任何一行内核或领域代码。
 */
object PlanPoints {

    /** 排程策略：给一批待办，重新安排它们的截止时间。 */
    val ScheduleStrategy: ExtensionPoint<ScheduleStrategy> = ExtensionPoint(
        ExtensionPointId("plan.schedule.strategy"),
        Cardinality.MANY,
        "ScheduleStrategy",
    )

    /** 统计指标：算出一个可以展示的数字或短句。 */
    val Metric: ExtensionPoint<MetricProvider> = ExtensionPoint(
        ExtensionPointId("plan.metric"),
        Cardinality.MANY,
        "MetricProvider",
    )
}

/**
 * 排程策略。
 *
 * 典型实现：艾宾浩斯复习、优先级排序、精力曲线、教师布置的固定节奏。
 * 内核与领域层都不知道它们的存在  这就是可插拔。
 */
interface ScheduleStrategy : OrderedContribution {

    val title: String

    /**
     * 返回**调整过 [Task.dueAt] 的任务**。不修改传进来的列表。
     * 依赖顺序时（比如先做前置任务）可以自己看 [Task.planId]。
     */
    suspend fun arrange(pending: List<Task>, nowMs: Long): List<Task>
}

/** 统计指标。返回值直接展示给用户，所以是人类可读的字符串。 */
interface MetricProvider : OrderedContribution {

    val title: String

    suspend fun compute(service: PlanService, nowMs: Long): String
}
