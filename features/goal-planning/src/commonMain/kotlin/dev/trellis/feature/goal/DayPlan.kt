package dev.trellis.feature.goal

import kotlinx.serialization.Serializable

/**
 * 一天在视野里的位置。
 *
 * 四态不是显示细节，是**权限**：FUTURE 的条目既不在界面出现，也不进 AI 上下文。
 * 做成「只是灰一点」的话，模型照样能读到后天要干什么，然后提前替用户决定 ——
 * 那正是这个功能想避免的「发散」。
 */
@Serializable
enum class DayState { PAST, TODAY, TOMORROW, FUTURE }

/** 条目类型。决策任务也是条目，只是它的产出是「一个决定」而不是「一页笔记」。 */
@Serializable
enum class EntryKind { STUDY, DECISION }

/**
 * 一条日计划。
 *
 * 五项必填不是形式主义：[output] 和 [doneCriteria] 是「这条到底算不算做完」的定义，
 * 没有它们的条目会在第二天变成一条永远勾不掉的待办。[goalRef] 是它挂在哪，
 * 断掉的引用意味着这条计划已经不是为那个目标服务的了。
 */
@Serializable
data class DayEntry(
    val id: String,
    val text: String,
    val output: String,
    val minutes: Int,
    val goalRef: String,
    val doneCriteria: String,
    val done: Boolean = false,
    val kind: EntryKind = EntryKind.STUDY,
)

/** 一天的安排。 */
@Serializable
data class DayPlan(
    val date: String,
    val entries: List<DayEntry> = emptyList(),
) {
    val totalMinutes: Int get() = entries.sumOf { it.minutes }
    val doneCount: Int get() = entries.count { it.done }
}

/** ISO 日期的纯函数运算：common 里没有 java.time，又不值得为四个函数引一个依赖。 */
object IsoDate {

    fun isValid(text: String): Boolean = parse(text) != null

    fun parse(text: String): Triple<Int, Int, Int>? {
        val parts = text.split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31 || y < 1970 || y > 9999) return null
        // 允许 2 月 30 日会算出错误的 epochDay，这里用回算校验。
        val epoch = daysFromCivil(y, m, d)
        val back = civilFromDays(epoch)
        return if (back == Triple(y, m, d)) Triple(y, m, d) else null
    }

    /** Howard Hinnant 的 days_from_civil：1970-01-01 起的天数。 */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097L + doe - 719468L
    }

    fun civilFromDays(epochDay: Long): Triple<Int, Int, Int> {
        val z = epochDay + 719468L
        val era = (if (z >= 0) z else z - 146096L) / 146097L
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    fun epochDay(text: String): Long? = parse(text)?.let { (y, m, d) -> daysFromCivil(y, m, d) }

    fun fromEpochDay(epochDay: Long): String {
        val (y, m, d) = civilFromDays(epochDay)
        return pad(y, 4) + "-" + pad(m, 2) + "-" + pad(d, 2)
    }

    fun plusDays(text: String, days: Int): String? =
        epochDay(text)?.let { fromEpochDay(it + days) }

    fun daysBetween(from: String, to: String): Long? {
        val a = epochDay(from) ?: return null
        val b = epochDay(to) ?: return null
        return b - a
    }

    private fun pad(value: Int, width: Int): String {
        val s = value.toString()
        return if (s.length >= width) s else "0".repeat(width - s.length) + s
    }
}

/**
 * 视野窗口：**过去全见 + 明天可见 + 更远不可见**。
 *
 * 对外只有一个入口 [visibleDays]，越界查询返回 [WindowResult.OutOfWindow] 而不是空列表 ——
 * 「查不到」和「没有」必须能分开：前者是权限，后者是事实。混成一个空数组，
 * 上层只能猜，猜错就会把「还没排」显示成「排了但空」。
 */
object DayWindow {

    fun stateOf(date: String, today: String): DayState? {
        val target = IsoDate.epochDay(date) ?: return null
        val base = IsoDate.epochDay(today) ?: return null
        return when {
            target < base -> DayState.PAST
            target == base -> DayState.TODAY
            target == base + 1 -> DayState.TOMORROW
            else -> DayState.FUTURE
        }
    }

    /** 窗口右边界：today + 1。 */
    fun lastVisible(today: String): String? = IsoDate.plusDays(today, 1)

    /** 某一天能不能被看见。 */
    fun isVisible(date: String, today: String): Boolean {
        val state = stateOf(date, today) ?: return false
        return state != DayState.FUTURE
    }

    /**
     * 全部可见日期（升序）。传进来的 [existing] 是已经排过的日期集合。
     *
     * 「最早」取的是真实存在的最早一天，而不是固定回看 N 天 —— 用户只记了两周，
     * 就没有必要为他虚构两周的空档。
     */
    fun visibleDays(today: String, existing: Set<String>): List<String> {
        val last = lastVisible(today) ?: return emptyList()
        val lastEpoch = IsoDate.epochDay(last) ?: return emptyList()
        return existing
            .mapNotNull { date -> IsoDate.epochDay(date)?.let { date to it } }
            .filter { (_, epoch) -> epoch <= lastEpoch }
            .sortedBy { it.second }
            .map { it.first }
    }

    /**
     * 按窗口取计划。
     *
     * 越界时 [OutOfWindow] 带一句能照着改的原因 —— 报错必须能指导修改，
     * 只回一个空数组等于让调用方自己猜哪里错了。
     */
    fun select(book: Map<String, DayPlan>, today: String, from: String, to: String): WindowResult {
        val last = lastVisible(today)
            ?: return WindowResult.OutOfWindow(from, "今天的日期不是合法 ISO 日期：" + today)
        val toEpoch = IsoDate.epochDay(to)
            ?: return WindowResult.OutOfWindow(to, "结束日期不是合法 ISO 日期：" + to)
        val lastEpoch = IsoDate.epochDay(last) ?: 0L
        if (toEpoch > lastEpoch) {
            return WindowResult.OutOfWindow(
                to,
                "超出视野：最多只能看到 " + last + "（今天 + 1）；" + to + " 属于更远的未来，不提供数据",
            )
        }
        val fromEpoch = IsoDate.epochDay(from)
            ?: return WindowResult.OutOfWindow(from, "开始日期不是合法 ISO 日期：" + from)
        if (fromEpoch > toEpoch) {
            return WindowResult.OutOfWindow(from, "开始日期晚于结束日期：" + from + " > " + to)
        }
        val days = generatedSequence(fromEpoch, toEpoch)
            .map { IsoDate.fromEpochDay(it) }
            .mapNotNull { date -> book[date] }
        return WindowResult.Ok(days)
    }

    private fun generatedSequence(from: Long, to: Long): List<Long> {
        val out = ArrayList<Long>((to - from + 1).toInt())
        var cursor = from
        while (cursor <= to) {
            out += cursor
            cursor += 1
        }
        return out
    }
}

/** 窗口查询结果。 */
sealed interface WindowResult {
    data class Ok(val days: List<DayPlan>) : WindowResult
    data class OutOfWindow(val requested: String, val reason: String) : WindowResult
}
