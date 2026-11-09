package dev.trellis.app.desktop

import java.time.LocalDate

/**
 * 早间「智能重排」的纯算法：把逾期项插到今天最前面，后面的安排按天顺序整体后移。
 *
 * 规则（2026-11-09 用户定的口径）：
 * 1. 逾期（更早欠账 → 本周逾期）先排到最前面，保持原来的阶段/路线顺序；
 * 2. 今天原有任务排在它们后面，今天装不下就整块后移，不做跨阶段穿插；
 * 3. 每天都按参考分钟装填，装不下的尾部整块移到下一天最前面；
 * 4. 不要求本周补完，溢出会自然进入下周，下周原来的安排继续往后顺；
 * 5. 只给出「谁去哪天」的方案，写不写由调用方决定（dryRun 用同一份结果）。
 */
internal enum class RearrangeItemKind {
    /** 已经铺到某一天的 occurrence，移动走 `moveOccurrence`。 */
    Occurrence,

    /** 还没铺天的欠账单元，落位走 `placeUnit`。 */
    Unit,
}

internal data class RearrangeItem(
    val key: String,
    val title: String,
    val kind: RearrangeItemKind,
    /** 原来的日期；欠账单元没有日期，是 null。 */
    val day: String?,
    val minutes: Int,
    /** 全局顺序：越小越靠前（阶段顺序 → 路线顺序 → 当天顺序）。 */
    val order: Int,
)

internal data class RearrangeAssignment(
    val item: RearrangeItem,
    val day: String,
)

internal data class RearrangeMove(
    val key: String,
    val title: String,
    val kind: RearrangeItemKind,
    val fromDay: String?,
    val toDay: String,
)

internal data class RearrangePlan(
    val assignments: List<RearrangeAssignment>,
    val moves: List<RearrangeMove>,
    /** 溢出到下周的条数（给回执用）。 */
    val overflowCount: Int,
)

/**
 * @param overdue 本周逾期（按阶段/路线顺序排好，日期比今天早）
 * @param backlog 更早欠账（按路线顺序排好）
 * @param scheduled 今天及以后已经铺到天的 occurrence，按日期分组、组内按当天顺序排好
 */
internal fun buildRearrangePlan(
    overdue: List<RearrangeItem>,
    backlog: List<RearrangeItem>,
    scheduled: Map<LocalDate, List<RearrangeItem>>,
    startDay: LocalDate,
    dailyMinutes: Int,
    extraMinutes: Int,
): RearrangePlan {
    val daily = dailyMinutes.coerceAtLeast(1)
    val extra = extraMinutes.coerceIn(0, 240)
    val assignments = mutableListOf<RearrangeAssignment>()
    val carry = mutableListOf<RearrangeItem>()

    fun fill(day: LocalDate, items: List<RearrangeItem>, capacity: Int) {
        var used = 0
        var index = 0
        while (index < items.size) {
            val item = items[index]
            val minutes = item.minutes.coerceAtLeast(1)
            // 第一项即使超过容量也强行安排，避免出现「一天都不敢放」的死循环。
            if (index == 0 || used + minutes <= capacity) {
                assignments += RearrangeAssignment(item, day.toString())
                used += minutes
                index++
            } else {
                break
            }
        }
        if (index < items.size) carry += items.subList(index, items.size)
    }

    // 补的内容整体按阶段/路线顺序排在最前面：更早欠账和本周逾期混在一起按 order 排，
    // 不能简单地把本周逾期放在最前，否则会把后面的阶段提到前面的阶段之前。
    carry += (overdue + backlog).sortedBy { it.order }

    var day = startDay
    while (carry.isNotEmpty() || scheduled.keys.any { !it.isBefore(day) }) {
        val existing = scheduled[day].orEmpty()
        val list = ArrayList<RearrangeItem>(carry.size + existing.size)
        list += carry
        carry.clear()
        list += existing
        if (list.isEmpty()) {
            // 这一天本来就没安排；没有 carry 的话可以直接跳到下一个有安排的日子。
            val next = scheduled.keys.filter { it.isAfter(day) }.minOrNull()
            if (next == null) break
            day = next
            continue
        }
        val capacity = daily + if (day == startDay) extra else 0
        fill(day, list, capacity)
        if (carry.isEmpty()) {
            val next = scheduled.keys.filter { it.isAfter(day) }.minOrNull()
            if (next == null) break
            day = next
        } else {
            day = day.plusDays(1)
        }
    }

    val startWeek = isoWeekOf(startDay)
    val moves = assignments.mapNotNull { assignment ->
        val item = assignment.item
        if (item.day == assignment.day) return@mapNotNull null
        RearrangeMove(
            key = item.key,
            title = item.title,
            kind = item.kind,
            fromDay = item.day,
            toDay = assignment.day,
        )
    }
    val overflow = assignments.count { isoWeekOf(LocalDate.parse(it.day)) != startWeek }
    return RearrangePlan(assignments, moves, overflow)
}
