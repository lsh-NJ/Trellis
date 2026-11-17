package dev.trellis.app.desktop

/**
 * 计划工作流清单的纯映射：`job_step` 状态 → 右栏清单行。
 *
 * 为什么单独抽出来：清单必须**完全**由 job 的真状态推导（跨轮、重启、换会话都不能脱节），
 * 而这件事以前散在「每轮会清空的 activePlanId + 模型自己调的 step_done」里，出了两个真实事故：
 *  1. 第 2、3 轮里宿主更新清单全是空操作，第 1 步永远停在「等待确认」；
 *  2. 模型调 `step_done` 把流程的第 2 步提前勾成「完成」。
 * 抽成纯函数后，这段映射可以被直接钉死，不再依赖 UI 状态。
 */
internal data class WorkflowChecklistStep(
    val text: String,
    val state: PlanStepState,
    val note: String = "",
    val impact: String = "",
)

/** 流程的一段：名字（`master/week/units`）、job_step 状态、失败原因。 */
internal data class WorkflowPhaseSnapshot(
    val phase: String,
    val state: String,
    val error: String? = null,
)

internal fun workflowPhaseTitle(phase: String): String = when (phase) {
    "master" -> "调研结果 → 总计划文档"
    "week" -> "总计划文档 → 周文档"
    "units" -> "周文档 → 计划节点"
    else -> phase
}

internal fun workflowPhaseImpact(phase: String): String = when (phase) {
    "master" -> "写完总计划文档停下来等你确认"
    "week" -> "建计划本体并生成本周周文档"
    "units" -> "把要学的知识点放进本周队列"
    else -> ""
}

/**
 * 清单行：`awaiting_user` 时当前段显示「等待确认」，终态时全部收尾。
 *
 * @param jobState `job.state`（running / awaiting_user / done / cancelled / failed）
 */
internal fun workflowChecklistSteps(
    jobState: String,
    phases: List<WorkflowPhaseSnapshot>,
): List<WorkflowChecklistStep> {
    val awaiting = jobState == "awaiting_user"
    return phases.map { phase ->
        val state = when (phase.state) {
            "done" -> PlanStepState.Done
            "failed" -> PlanStepState.Failed
            "running" -> if (awaiting) PlanStepState.Awaiting else PlanStepState.Running
            else -> PlanStepState.Pending
        }
        WorkflowChecklistStep(
            text = workflowPhaseTitle(phase.phase),
            state = state,
            note = when {
                phase.error != null -> phase.error.orEmpty()
                state == PlanStepState.Awaiting -> "等待确认 · 点「继续」进入下一段"
                else -> ""
            },
            impact = workflowPhaseImpact(phase.phase),
        )
    }
}

/** 流程是否已经结束（清单该显示「已完成」/收起）。 */
internal fun workflowChecklistSettled(jobState: String): Boolean =
    jobState == "done" || jobState == "cancelled" || jobState == "failed"
