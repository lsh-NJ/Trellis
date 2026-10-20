package dev.trellis.feature.plan

import dev.trellis.domain.PlanService
import dev.trellis.domain.TaskId
import dev.trellis.domain.TaskStatus
import dev.trellis.kernel.action.ActionArgs
import dev.trellis.kernel.action.ActionParam
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.core.CapabilityId

/**
 * 学习计划这个领域能做的三件**原子动作**。
 *
 * 注意它们是**第一方实现**：自动生成出来的插件只能「选一个 + 填参数」，
 * 发明不出新动作。这就是「插件能做事，但做不了坏事」的边界。
 */
internal class AddTaskAction(private val plan: PlanService) : KernelAction {
    override val id: String = "addTask"
    override val order: Int = 100
    override val label: String = "新建任务"
    override val params: List<ActionParam> = listOf(ActionParam("title", "任务标题"))
    override val capability: CapabilityId? = CapabilityId("write_data")

    override suspend fun execute(args: ActionArgs): String {
        val title = args.require("title")
        plan.addTask(title)
        return "新建了任务「" + title + "」"
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
