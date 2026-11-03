package dev.trellis.app.desktop

/**
 * 管家给的一条模板化建议。
 *
 * 建议不是模型自由发挥：条目由规则从计划事实里挑出来（逾期的最多挪几件、更早欠下的
 * 补排一两个），模型只负责把话说顺。用户点「一键采纳」才会真正执行 —— 执行走的是
 * 计划后端已有的命令（[StewardChange.Move] → `moveOccurrence`、
 * [StewardChange.Place] → `placeUnit`），不是让模型自己去调工具。
 *
 * 卡片渲染在管家消息下面；采纳/先这样之后 [ProposalState] 记下结果，
 * 免得重启后又冒出来一条「要不要采纳」。
 */
data class StewardProposal(
    val id: String,
    /** 一句话标题：要做什么。 */
    val title: String,
    /** 为什么要做：事实依据，保持一两句。 */
    val detail: String,
    val changes: List<StewardChange>,
) {
    val dayCount: Int get() = changes.map { it.day }.distinct().size
}

enum class ProposalState { Open, Adopted, Dismissed }

/** 一条可执行的改动。日期都是 `YYYY-MM-DD`（应用内日期，时钟可被开发者模式拨动）。 */
sealed interface StewardChange {
    val title: String
    val day: String

    /** 把已有的排期挪到另一天。 */
    data class Move(
        override val title: String,
        val occurrenceId: String,
        override val day: String,
    ) : StewardChange

    /** 把还没排进任何一周的欠账节点放到某天。 */
    data class Place(
        override val title: String,
        val unitId: String,
        override val day: String,
    ) : StewardChange
}

/** 从计划视图里摘出来、构造建议要用的最小信息。 */
internal data class StewardOverdueItem(
    val occurrenceId: String,
    val title: String,
    val day: String,
)

internal data class StewardBacklogItem(val unitId: String, val title: String)

/**
 * 规则生成建议。
 *
 * 只做三件保守的事：本周逾期的挪到「明天及以后、本周内」；更早欠下的补排一两个；
 * 一次最多动 3 件 + 2 个，剩下的留给下次。没有可动的就返回 null（宁可不建议）。
 */
internal object StewardProposals {

    private const val MAX_MOVES = 3
    private const val MAX_PLACES = 2

    fun build(
        today: java.time.LocalDate,
        weekEnd: java.time.LocalDate,
        overdue: List<StewardOverdueItem>,
        backlog: List<StewardBacklogItem>,
        id: String,
    ): StewardProposal? {
        if (overdue.isEmpty() && backlog.isEmpty()) return null
        val days = buildList {
            var day = today.plusDays(1)
            while (!day.isAfter(weekEnd)) {
                add(day)
                day = day.plusDays(1)
            }
            // 今天就是周日：本周没有「明天」了，只能放今天（今天的排期还能补）。
            if (isEmpty()) add(today)
        }

        val changes = mutableListOf<StewardChange>()
        overdue.take(MAX_MOVES).forEachIndexed { index, item ->
            val target = days[index % days.size].toString()
            // 已经在这一天就不用挪（理论上不会发生，防御一下）。
            if (target != item.day) changes += StewardChange.Move(item.title, item.occurrenceId, target)
        }
        backlog.take(MAX_PLACES).forEachIndexed { index, item ->
            val target = days[minOf(index, days.size - 1)].toString()
            changes += StewardChange.Place(item.title, item.unitId, target)
        }
        if (changes.isEmpty()) return null

        val moves = changes.count { it is StewardChange.Move }
        val places = changes.count { it is StewardChange.Place }
        val title = when {
            moves > 0 && places > 0 -> "把 $moves 件没做完的往后挪，顺手排 $places 个欠账"
            moves > 0 -> "把 $moves 件没做完的挪到本周剩下的天"
            else -> "把 $places 个更早欠下的排进本周"
        }
        val detail = buildString {
            append("本周还有 ${overdue.size} 件没做")
            if (backlog.isNotEmpty()) append("、更早欠下 ${backlog.size} 个")
            append("；从明天到周日还有 ${days.size} 天可以放。")
            append("先动这几件，剩下的下次再说。")
        }
        return StewardProposal(id = id, title = title, detail = detail, changes = changes)
    }
}
