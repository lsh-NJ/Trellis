package dev.trellis.app.desktop

import dev.trellis.domain.plan.CommitmentUnitView
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.PoolUnit

/**
 * 待办板里的一行「计划节点」。
 *
 * 节点 = 学习计划里的一个单元。被某一周承诺收进去的算 [scheduled] = true（本周要做），
 * 还没被任何一周收进去的还在节点池里，是「待安排」。
 *
 * 每行都带 [planTitle]：待办板是跨计划的，只写节点名看不出它属于哪个计划。
 */
internal data class PlanNodeRow(
    val planId: String,
    val planTitle: String,
    val unitId: String,
    val title: String,
    val chapter: String,
    val minutes: Int,
    val scheduled: Boolean,
    /** 本周排到的那一次 occurrence；待安排的节点还没排期，是 null。 */
    val occurrenceId: String? = null,
)

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
 * [occurrenceId] 是 session 唯一能挂的东西，所以点 ⏱ 的那一刻就得先拿到它；
 * 拿不到（还没排到具体某天）就不给计时器入口。
 */
internal data class TimerNodeTarget(
    val planId: String,
    val occurrenceId: String,
    val title: String,
)

/** 每个计划最多摊几条：待办板是首页的一块卡片，不是计划地图。 */
internal const val PLAN_NODES_PER_PLAN: Int = 10

/**
 * 把所有计划的未完成节点摊成待办板要的几组。
 *
 * 只取两份：本周承诺里还没做完的单元 + 节点池里还没排期的单元。更远的周不摊 ——
 * 那是计划页「地图」的活，摆进待办只会变成噪声。
 *
 * 入参是几个取值函数而不是 [dev.trellis.domain.plan.LearningPlanService]，
 * 是为了这个函数能用纯数据测，不必拖一个 SQLite 库进来。
 *
 * [occurrenceOf] 给出每个节点本周排到的那一次 occurrence；没排到具体某天的节点拿不到，
 * 界面上也就不给计时器。
 */
internal fun planNodeGroups(
    plans: List<LearningPlanInfo>,
    currentWeekOf: (String) -> String?,
    weekUnitsOf: (String, String) -> List<CommitmentUnitView>,
    poolOf: (String) -> List<PoolUnit>,
    occurrenceOf: (String, String, String) -> String? = { _, _, _ -> null },
): List<PlanNodeGroup> = plans
    .filter { it.status != "ended" }
    .mapNotNull { plan ->
        val week = currentWeekOf(plan.id)
        val thisWeek = if (week == null) {
            emptyList()
        } else {
            weekUnitsOf(plan.id, week)
                .filter { it.state != "done" }
                .map { unit ->
                    PlanNodeRow(
                        planId = plan.id,
                        planTitle = plan.title,
                        unitId = unit.id,
                        title = unit.title,
                        chapter = unit.chapter,
                        minutes = unit.estimateMinutes,
                        scheduled = true,
                        occurrenceId = occurrenceOf(plan.id, week, unit.id),
                    )
                }
        }
        val pool = poolOf(plan.id).map { unit ->
            PlanNodeRow(
                planId = plan.id,
                planTitle = plan.title,
                unitId = unit.id,
                title = unit.title,
                chapter = unit.chapter,
                minutes = unit.estimateMinutes,
                scheduled = false,
            )
        }
        val all = thisWeek + pool
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
