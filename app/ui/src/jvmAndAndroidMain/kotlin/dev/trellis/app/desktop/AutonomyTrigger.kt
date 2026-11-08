package dev.trellis.app.desktop

import dev.trellis.domain.plan.StageView
import dev.trellis.domain.plan.UnitProgress

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
 *   （没有阶段的计划，或者整计划是跨多个阶段一起完成的）。
 * @property planDone 整份计划是否已经完成（有阶段：每个阶段都有单元且都做完；无阶段：所有单元都做完；
 *   两种情况都要求待安排池为空）。
 */
internal data class AutonomyEvent(
    val kind: AutonomyEventKind,
    val stage: StageView?,
    val planDone: Boolean,
)

/**
 * 计划是否已经「完成」。
 *
 * 这是完成态的**唯一定义**：不读 `plan.status`（那是用户主动放弃/归档的标记，和进度无关），
 * 只看进度本身，且待安排池必须为空：
 * - 有阶段：每个阶段都有单元（`total > 0`）且都做完；
 * - 没有阶段：计划里确实有单元，且每个单元都至少有过一次 `done`（[unitProgress]）；
 *   0 单元的空计划不算完成。
 *
 * [detectAutonomyEvent] 判 `PlanDone` 用的就是同一个口径（直接调它），
 * 所以「能不能再改这份计划」和「要不要为完成开口」不会给出互相矛盾的答案。
 */
internal fun isPlanComplete(
    stages: List<StageView>,
    poolEmpty: Boolean,
    unitProgress: UnitProgress = UnitProgress(),
): Boolean {
    if (!poolEmpty) return false
    if (stages.isNotEmpty()) return stages.all { it.total > 0 && it.done >= it.total }
    return unitProgress.total > 0 && unitProgress.done >= unitProgress.total
}

/**
 * 比较「刷新前 / 刷新后」的阶段进度，判断有没有值得让管家主动说话的事。
 *
 * 规则（故意保守，宁可不说也不打扰）：
 * - 整份计划完成 = [isPlanComplete]：有阶段时看每个阶段 `total > 0` 且都做完；没有阶段时看
 *   [unitProgress] 的单元都做完；两种情况都要求待安排池为空 → 优先报 [AutonomyEventKind.PlanDone]；
 * - 否则只看「刚从没做完变成做完」的阶段：`before` 里同一个 id 的阶段原来有单元且没做完，
 *   `after` 里变成有单元且做完了 → 报 [AutonomyEventKind.StageDone]；
 * - 都没有 → null（包括没有阶段且单元没做完、total=0 的阶段、以及刷新前就已完成的状态）。
 *
 * 没有阶段的计划 `after` 是空列表，但整份计划仍可能刚完成，所以不能因为 `after` 为空就直接返回 null。
 */
internal fun detectAutonomyEvent(
    before: List<StageView>,
    after: List<StageView>,
    poolEmpty: Boolean,
    unitProgress: UnitProgress = UnitProgress(),
): AutonomyEvent? {
    val justDoneStage = if (after.isEmpty()) {
        null
    } else {
        after.firstOrNull { stage ->
            val previous = before.firstOrNull { it.id == stage.id } ?: return@firstOrNull false
            previous.total > 0 &&
                previous.done < previous.total &&
                stage.total > 0 &&
                stage.done >= stage.total
        }
    }

    val planDone = isPlanComplete(after, poolEmpty, unitProgress)

    return when {
        planDone -> AutonomyEvent(AutonomyEventKind.PlanDone, justDoneStage, planDone = true)
        justDoneStage != null -> AutonomyEvent(AutonomyEventKind.StageDone, justDoneStage, planDone = false)
        else -> null
    }
}
