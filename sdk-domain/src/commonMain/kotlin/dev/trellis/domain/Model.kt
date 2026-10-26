package dev.trellis.domain

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class PlanId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
@Serializable
value class TaskId(val value: String) {
    override fun toString(): String = value
}

/**
 * 一个计划 / 目标。比如「6 周通过软考中级」。
 *
 * [targetDate] 是 epoch 毫秒。用 Long 而不是富时间类型：领域层不该替存储层
 * 决定时间怎么表示，而内核与存储都只认 Long。
 */
@Serializable
data class Plan(
    val id: PlanId,
    val title: String,
    val targetDate: Long? = null,
    val createdAt: Long,
)

enum class TaskStatus {
    TODO,
    DOING,
    DONE,
}

/**
 * 一条待办。对应 DESIGN.MD 里的「任务管理与追踪」。
 *
 * 三个字段直接对应需求里的三句话：
 *  - [dueAt]         「任务进行前的提醒」的依据
 *  - [spentMinutes]  「期间可选对时间的追踪」
 *  - [note]          「任务结束后可选是否进行总结」
 */
@Serializable
data class Task(
    val id: TaskId,
    val title: String,
    val planId: PlanId? = null,
    val dueAt: Long? = null,
    val status: TaskStatus = TaskStatus.TODO,
    val estimateMinutes: Int? = null,
    val spentMinutes: Int = 0,
    val note: String = "",
    val createdAt: Long,
    val completedAt: Long? = null,
) {
    val isDone: Boolean get() = status == TaskStatus.DONE
}

/** 查询条件。刻意做成值对象而不是一堆可选参数  它会被跨模块传递。 */
@Serializable
data class TaskQuery(
    val planId: PlanId? = null,
    val status: TaskStatus? = null,
    /** 只取截止时间早于它的任务。 */
    val dueBefore: Long? = null,
    val includeDone: Boolean = true,
)

/** 给「简介的数据统计」用。 */
@Serializable
data class PlanSummary(
    val total: Int = 0,
    val done: Int = 0,
    val doing: Int = 0,
    val overdue: Int = 0,
    val dueToday: Int = 0,
    val spentMinutes: Int = 0,
    /** 连续多少天有完成记录（含今天）；断一天就归零。 */
    val streakDays: Int = 0,
    /** 完成率，0 到 100 的整数（百分比）。 */
    val completionRate: Int = 0,
    /** 最早创建、还没完成的那条任务的标题；一条都没有时为空串。 */
    val oldestOpenTask: String = "",
)
