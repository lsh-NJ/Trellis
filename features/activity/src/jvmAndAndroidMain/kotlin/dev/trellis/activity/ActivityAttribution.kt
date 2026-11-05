package dev.trellis.activity

/**
 * 一段活动时间归到某个任务。
 *
 * [taskRef] 对 activity 模块是不透明字符串：桌面端写 `occurrence:<id>` / `todo:<id>`，
 * 模块本身不解释领域语义（activity 模块不认识学习计划、也不认识任务）。
 *
 * ### 为什么按时间区间归因，而不是绑到 raw_event
 *
 * 片段边界会随 AFK 扣除、跨 bucket 去重、碎片合并变化；绑事件的归因在一次重算后就错位了。
 * 绑时间区间只依赖时间本身：原始数据重拉、分类规则改了、AFK 记录补上了，归因都还在原位。
 * AI 建议、手动标记、将来的规则也全部产出一段区间，写进同一张表。
 *
 * [status] 区分三种状态：AI 先写 [AttributionStatus.PROPOSED]（页面里逐条确认），
 * 用户接受后变 [AttributionStatus.APPLIED]，被否掉的是 [AttributionStatus.REJECTED]。
 * 已应用与已否掉的区间都算「这段已经判断过」，AI 不会反复分析同一段。
 */
data class Attribution(
    val fromMs: Long,
    val toMs: Long,
    val taskRef: String,
    val source: String = AttributionSource.MANUAL,
    val confidence: Double = 1.0,
    val status: String = AttributionStatus.APPLIED,
    val reason: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 数据库主键；新行写 0，由 [ActivityStore.insertAttribution] 分配。 */
    val id: Long = 0,
) {
    val durationMs: Long get() = (toMs - fromMs).coerceAtLeast(0)

    /** 明确「这段时间与任务无关」的归因：任何任务都不算它，也不再让 AI 反复分析。 */
    val isNoTask: Boolean get() = taskRef == NO_TASK_REF
}

object AttributionSource {
    const val MANUAL: String = "manual"
    const val RULE: String = "rule"
    const val AI: String = "ai"
}

object AttributionStatus {
    const val PROPOSED: String = "proposed"
    const val APPLIED: String = "applied"
    const val REJECTED: String = "rejected"
}

/** 用户/AI 明确判断「这一段不是任何任务」，写进归因表用于跳过重复分析。 */
const val NO_TASK_REF: String = "#none"

/** 区间归因的纯函数：谁覆盖了这一段、哪段还没被判断过、时长怎么算。 */
object Attributions {

    /** 已生效归因的优先级：手动 > 规则 > AI；同源按重叠时长，再按更晚写入。 */
    fun best(fromMs: Long, toMs: Long, rows: List<Attribution>): Attribution? =
        rows.filter { it.status == AttributionStatus.APPLIED }
            .filter { overlapMs(it, fromMs, toMs) > 0 }
            .maxWithOrNull(
                compareBy<Attribution> { sourceRank(it.source) }
                    .thenBy { overlapMs(it, fromMs, toMs) }
                    .thenBy { it.id },
            )

    /** 与 `[fromMs, toMs)` 重叠的毫秒数。 */
    fun overlapMs(row: Attribution, fromMs: Long, toMs: Long): Long =
        (minOf(row.toMs, toMs) - maxOf(row.fromMs, fromMs)).coerceAtLeast(0)

    /** 某个任务的归因总时长（同一区间被多条重叠归因覆盖时只算一次）。 */
    fun durationFor(taskRef: String, rows: List<Attribution>): Long {
        val ranges = rows
            .filter { it.status == AttributionStatus.APPLIED && it.taskRef == taskRef }
            .map { it.fromMs until it.toMs }
        return mergeRanges(ranges).sumOf { it.last - it.first + 1L }
    }

    /**
     * 还没被判断过的时间段：把归因表里所有行（含 proposed / rejected）当成已覆盖，
     * 在 `[fromMs, toMs)` 里取补集。
     *
     * 这就是用户说的「不能绑定的活动不需要重复分析」：AI 只在这份补集上跑；
     * 用户点过「无关」或否掉过建议的区间，下次不会再出现。
     */
    fun uncovered(fromMs: Long, toMs: Long, rows: List<Attribution>): List<LongRange> {
        if (toMs <= fromMs) return emptyList()
        val covered = mergeRanges(
            rows.mapNotNull {
                val start = maxOf(it.fromMs, fromMs)
                val end = minOf(it.toMs, toMs)
                if (end > start) start until end else null
            },
        )
        if (covered.isEmpty()) return listOf(fromMs until toMs)

        val out = ArrayList<LongRange>()
        var cursor = fromMs
        for (range in covered) {
            if (range.first > cursor) out += cursor until range.first
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < toMs) out += cursor until toMs
        return out
    }

    /** 合并重叠/相邻区间（`LongRange` 用 `until` 语义，last 是开区间边界 - 1）。 */
    fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = ArrayList<LongRange>()
        var start = sorted.first().first
        var endExclusive = sorted.first().last + 1
        for (range in sorted.drop(1)) {
            val rangeEnd = range.last + 1
            if (range.first <= endExclusive) {
                endExclusive = maxOf(endExclusive, rangeEnd)
            } else {
                out += start until endExclusive
                start = range.first
                endExclusive = rangeEnd
            }
        }
        out += start until endExclusive
        return out
    }

    private fun sourceRank(source: String): Int = when (source) {
        AttributionSource.MANUAL -> 3
        AttributionSource.RULE -> 2
        AttributionSource.AI -> 1
        else -> 0
    }
}