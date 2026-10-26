package dev.trellis.feature.plan

import dev.trellis.domain.PlanService
import dev.trellis.domain.Task
import dev.trellis.domain.TaskId
import dev.trellis.domain.TaskQuery
import dev.trellis.domain.TaskStatus
import dev.trellis.kernel.action.ActionArgs
import dev.trellis.kernel.action.ActionParam
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.time.KernelClock

/**
 * 把「任务 id」解析成一条真任务。
 *
 * 除了 id，还认识两种选择器：
 *  - `oldest`         最老的一条未完成任务（按创建时间）
 *  - `oldestOverdue`  最早逾期的一条（只算没做完、有截止时间、且已经过点的）
 *
 * 选择器是给「没有查询能力」开的一扇小门。模型没法让宿主按条件批量操作，
 * 但它可以说「动最老的那条」——需求窄，却是真实需求，
 * 比逼它编一个参数写死的假按钮强。
 */
private suspend fun PlanService.findTaskTarget(target: String, nowMs: Long): Task? = when (target) {
    "oldest" -> tasks(TaskQuery(includeDone = false)).minByOrNull { it.createdAt }
    "oldestOverdue" -> tasks(TaskQuery(includeDone = false))
        .mapNotNull { task -> task.dueAt?.let { due -> task to due } }
        .filter { (_, due) -> due < nowMs }
        .minByOrNull { (_, due) -> due }
        ?.first
    else -> task(TaskId(target))
}

/** 三个改任务的动作共用的参数说明：id 之外还能按选择器指代。 */
private const val TARGET_HELP = "任务 id；也可以写 oldest（最老的一条未完成任务）或 oldestOverdue（最早逾期的一条）"

/**
 * 学习计划这个领域能做的**原子动作**。
 *
 * 注意它们是**第一方实现**：自动生成出来的插件只能「选一个 + 填参数」，
 * 发明不出新动作。这就是「插件能做事，但做不了坏事」的边界。
 *
 * 一开始只有「新建 / 记时间 / 完成」三个，结果模型想改期、改标题、删任务时
 * 只能回一句「我做不到」。动作面窄不是安全，是**把手脚绑起来再要求它跳舞**。
 */
internal class AddTaskAction(
    private val plan: PlanService,
    private val clock: KernelClock,
) : KernelAction {
    override val id: String = "addTask"
    override val order: Int = 100
    override val label: String = "新建任务"
    override val params: List<ActionParam> = listOf(
        ActionParam("title", "任务标题"),
        ActionParam(
            "dueDays",
            "可选：几天后到期（0 今天、1 明天；不传表示不设截止时间）",
            required = false,
        ),
    )
    override val capability: CapabilityId? = CapabilityId("write_data")

    override suspend fun execute(args: ActionArgs): String {
        val title = args.require("title")
        val dueDays = args.get("dueDays")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            it.toIntOrNull() ?: throw IllegalArgumentException("dueDays 必须是整数")
        }
        if (dueDays != null && dueDays < 0) throw IllegalArgumentException("dueDays 不能是负数")
        val dueAt = dueDays?.let { days -> clock.nowMs() + days * DAY_MS }
        plan.addTask(title, dueAt = dueAt)
        return if (dueDays == null) {
            "新建了任务「" + title + "」"
        } else if (dueDays == 0) {
            "新建了任务「" + title + "」，今天到期"
        } else {
            "新建了任务「" + title + "」，" + dueDays + " 天后到期"
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

internal class LogTimeAction(private val plan: PlanService) : KernelAction {
    override val id: String = "logTime"
    override val order: Int = 110
    override val label: String = "记录投入时间"
    override val params: List<ActionParam> = listOf(
        ActionParam("taskId", "任务 id"),
        ActionParam("minutes", "分钟数"),
    )
    override val capability: CapabilityId? = CapabilityId("write_data")

    override suspend fun execute(args: ActionArgs): String {
        val id = TaskId(args.require("taskId"))
        val minutes = args.require("minutes").toIntOrNull()
            ?: throw IllegalArgumentException("minutes 必须是整数")
        val task = plan.logTime(id, minutes) ?: throw IllegalStateException("没有这个任务")
        return "给「" + task.title + "」记了 " + minutes + " 分钟"
    }
}

internal class CompleteTaskAction(private val plan: PlanService) : KernelAction {
    override val id: String = "completeTask"
    override val order: Int = 120
    override val label: String = "把任务标记为完成"
    override val params: List<ActionParam> = listOf(ActionParam("taskId", "任务 id"))
    override val capability: CapabilityId? = CapabilityId("write_data")

    override suspend fun execute(args: ActionArgs): String {
        val id = TaskId(args.require("taskId"))
        val task = plan.setStatus(id, TaskStatus.DONE)
            ?: throw IllegalStateException("没有这个任务")
        return "完成了「" + task.title + "」"
    }
}

internal class RenameTaskAction(
    private val plan: PlanService,
    private val clock: KernelClock,
) : KernelAction {
    override val id: String = "renameTask"
    override val order: Int = 115
    override val label: String = "改任务标题"
    override val params: List<ActionParam> = listOf(
        ActionParam("taskId", TARGET_HELP),
        ActionParam("title", "新的标题"),
    )
    override val capability: CapabilityId? = CapabilityId("write_data")

    override suspend fun execute(args: ActionArgs): String {
        val current = plan.findTaskTarget(args.require("taskId"), clock.nowMs())
            ?: throw IllegalStateException("没有找到要改的任务")
        val title = args.require("title")
        plan.save(current.copy(title = title))
        return "把「" + current.title + "」改成了「" + title + "」"
    }
}

internal class RescheduleTaskAction(
    private val plan: PlanService,
    private val clock: KernelClock,
) : KernelAction {
    override val id: String = "rescheduleTask"
    override val order: Int = 125
    override val label: String = "改截止时间"
    override val params: List<ActionParam> = listOf(
        ActionParam("taskId", TARGET_HELP),
        ActionParam("days", "几天后到期（整数；0 表示从此刻起，1 就是明天）"),
    )
    override val capability: CapabilityId? = CapabilityId("write_data")

    override suspend fun execute(args: ActionArgs): String {
        val days = args.require("days").toIntOrNull()
            ?: throw IllegalArgumentException("days 必须是整数")
        if (days < 0) throw IllegalArgumentException("days 不能是负数")
        val task = plan.findTaskTarget(args.require("taskId"), clock.nowMs())
            ?: throw IllegalStateException("没有找到要改期的任务")
        plan.save(task.copy(dueAt = clock.nowMs() + days * DAY_MS))
        return if (days == 0) {
            "把「" + task.title + "」的截止时间改到了现在"
        } else {
            "把「" + task.title + "」的截止时间改到了 " + days + " 天后"
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}

internal class DeleteTaskAction(
    private val plan: PlanService,
    private val clock: KernelClock,
) : KernelAction {
    override val id: String = "deleteTask"
    override val order: Int = 130
    override val label: String = "删除任务"
    override val params: List<ActionParam> = listOf(ActionParam("taskId", TARGET_HELP))
    override val capability: CapabilityId? = CapabilityId("delete_data")

    override suspend fun execute(args: ActionArgs): String {
        val task = plan.findTaskTarget(args.require("taskId"), clock.nowMs())
            ?: throw IllegalStateException("没有找到要删除的任务")
        plan.deleteTask(task.id)
        return "删除了「" + task.title + "」"
    }
}
