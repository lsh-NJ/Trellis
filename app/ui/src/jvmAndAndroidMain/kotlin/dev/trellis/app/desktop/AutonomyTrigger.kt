package dev.trellis.app.desktop

import dev.trellis.domain.plan.StageView

/**
 * 管家可以主动开口的两类时机。
 *
 * 只看「计划状态变了没有」，不看时间——什么时候轮询、两次之间隔多久由运行时决定，
 * 判定本身是纯函数，测试不用起时钟。
 */
internal enum class AutonomyEventKind { StageDone, PlanDone }

/**
 * 一次被判定为「值得开口」的变化。
 *
 * @property stage 触发这次事件的那个阶段；[AutonomyEventKind.PlanDone] 时可能是 null
 *   （阶段本来就是空的计划，或者整计划是跨多个阶段一起完成的）。
 * @property planDone 整份计划是否已经完成（所有阶段都有单元且都做完，待安排池也空了）。
 */
internal data class AutonomyEvent(
    val kind: AutonomyEventKind,
    val stage: StageView?,
    val planDone: Boolean,
)

/**
 * 比较「刷新前 / 刷新后」的阶段进度，判断有没有值得让管家主动说话的事。
 *
 * 规则（故意保守，宁可不说也不打扰）：
 * - 刷新后没有阶段 → 无事发生；
 * - 整份计划完成 = 每个阶段都有单元（`total > 0`）且都做完，且待安排池为空 → 优先报 [AutonomyEventKind.PlanDone]；
 * - 否则只看「刚从没做完变成做完」的阶段：`before` 里同一个 id 的阶段原来有单元且没做完，
 *   `after` 里变成有单元且做完了 → 报 [AutonomyEventKind.StageDone]；
 * - 都没有 → null（包括空列表、total=0 的阶段、以及刷新前就已完成的状态）。
 */
internal fun detectAutonomyEvent(
    before: List<StageView>,
    after: List<StageView>,
    poolEmpty: Boolean,
): AutonomyEvent? {
    if (after.isEmpty()) return null

    val justDoneStage = after.firstOrNull { stage ->
        val previous = before.firstOrNull { it.id == stage.id } ?: return@firstOrNull false
        previous.total > 0 &&
            previous.done < previous.total &&
            stage.total > 0 &&
            stage.done >= stage.total
    }

    val planDone = after.all { it.total > 0 && it.done >= it.total } && poolEmpty

    return when {
        planDone -> AutonomyEvent(AutonomyEventKind.PlanDone, justDoneStage, planDone = true)
        justDoneStage != null -> AutonomyEvent(AutonomyEventKind.StageDone, justDoneStage, planDone = false)
        else -> null
    }
}
