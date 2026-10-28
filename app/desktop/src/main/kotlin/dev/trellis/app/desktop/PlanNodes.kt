package dev.trellis.app.desktop

import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.OpenNode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 待办板里的一行「计划节点」。
 *
 * 只有排到具体某天、还没做完的节点才进待办：今天排到的（[daysLate] = 0），
 * 和之前排到却没做的（[daysLate] > 0）。还没排期的节点留在计划页的「待安排」里，不占待办。
 *
 * 每行都带 [planTitle]：待办板是跨计划的，只写节点名看不出它属于哪个计划。
 */
internal data class PlanNodeRow(
    val planId: String,
    val planTitle: String,
    /** 这一次排期；计时器的 session 只能挂在 occurrence 上，所以它必须一直带着。 */
    val occurrenceId: String,
    val title: String,
    val chapter: String,
    val minutes: Int,
    /** 排到的那一天（`yyyy-MM-dd`）。 */
    val day: String,
    /** 逾期几天；0 就是今天。 */
    val daysLate: Int,
) {
    val today: Boolean get() = daysLate <= 0
}

/**
 * 一个计划在待办板里的分组。
 *
 * [nodes] 只是截断后要显示的那几条，[total] 才是这个计划真正的节点数 ——
 * 界面上用 [hidden] 说一句「还有 N 个」。
 */
internal data class PlanNodeGroup(
    val planId: String,
    val planTitle: String,
    val nodes: List<PlanNodeRow>,
    val total: Int,
) {
    val hidden: Int get() = total - nodes.size
    val minutes: Int get() = nodes.sumOf { it.minutes }
}

/**
 * 计时器正在计的那个计划节点。
 *
 * [occurrenceId] 是 session 唯一能挂的东西，所以点 ⏱ 的那一刻就得先拿到它。
 */
internal data class TimerNodeTarget(
    val planId: String,
    val occurrenceId: String,
    val title: String,
)

/** 每个计划最多摊几条：待办板是首页的一块卡片，不是计划地图。 */
internal const val PLAN_NODES_PER_PLAN: Int = 10

/**
 * 把各计划「今天到期 + 之前逾期」的节点摊成待办板要的几组。
 *
 * 今天排到的排在前面，逾期的接着按「最近的在前」排：截断时先保今天；拖了一个月的那几条
 * 翻上去也没人动，不如让前几天的露在上面。
 *
 * 入参是取值函数而不是 [dev.trellis.domain.plan.LearningPlanService]，是为了这个函数能用
 * 纯数据测，不必拖一个 SQLite 库进来。
 */
internal fun planNodeGroups(
    plans: List<LearningPlanInfo>,
    today: String,
    openNodesOf: (String) -> List<OpenNode>,
): List<PlanNodeGroup> {
    val todayDate = LocalDate.parse(today)
    return plans
        .filter { it.status != "ended" }
        .mapNotNull { plan ->
            val rows = openNodesOf(plan.id).map { node ->
                PlanNodeRow(
                    planId = plan.id,
                    planTitle = plan.title,
                    occurrenceId = node.occurrenceId,
                    title = node.title,
                    chapter = node.chapter,
                    minutes = node.minutes,
                    day = node.day,
                    daysLate = ChronoUnit.DAYS.between(LocalDate.parse(node.day), todayDate).toInt(),
                )
            }
            val dueToday = rows.filter { it.today }
            val overdue = rows.filterNot { it.today }.sortedBy { it.daysLate }
            val all = dueToday + overdue
            if (all.isEmpty()) {
                null
            } else {
                PlanNodeGroup(
                    planId = plan.id,
                    planTitle = plan.title,
                    nodes = all.take(PLAN_NODES_PER_PLAN),
                    total = all.size,
                )
            }
        }
}
