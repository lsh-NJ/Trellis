package dev.trellis.feature.goal

import kotlinx.serialization.Serializable

/** 一天的摘要。原始明细不进上下文，这条才进。 */
@Serializable
data class DayDigest(
    val date: String,
    val done: Int,
    val total: Int,
    val missed: List<String>,
    /** 用户给的没做完原因；不知道就写「未记录」，不编。 */
    val reason: String = "",
    /** 偏离度：实际耗时 / 计划耗时。0 表示没有数据。 */
    val deviation: Double = 0.0,
) {
    fun render(): String {
        val head = date.takeLast(5) + " 完成 " + done + "/" + total
        val tail = buildString {
            if (missed.isNotEmpty()) {
                append(" 未完成：").append(missed.take(2).joinToString("、"))
                if (missed.size > 2) append("等 ").append(missed.size).append(" 项")
            }
            if (reason.isNotBlank()) append("；原因：").append(reason)
            if (deviation > 0.0) append("；耗时偏差 ").append(percent(deviation))
        }
        return GoalCompressor.clamp(head + tail, MAX_DAY_CHARS)
    }

    private fun percent(value: Double): String {
        val pct = (value * 100).toInt()
        return (if (pct >= 0) "+" else "") + pct + "%"
    }

    companion object {
        const val MAX_DAY_CHARS = 60
    }
}

/** 一周的摘要。 */
@Serializable
data class WeekDigest(
    val weekOf: String,
    val text: String,
)

/** 一个阶段的摘要。 */
@Serializable
data class PhaseDigest(
    val fromDate: String,
    val toDate: String,
    val text: String,
)

/**
 * 三级压缩：天 → 周 → 阶段。
 *
 * 压缩**不是删除**：原始 DayPlan 一直在本地，用户能展开回看。这里的产物只有一个用途 ——
 * 塞进 AI 上下文，让「上周定了什么」在 800 字里还活着。
 *
 * 为什么必须限长：上下文是有限资源，一份 30 天的明细能轻松吃掉整个窗口，
 * 结果模型既看不见今天、也看不见目标契约。
 */
object GoalCompressor {

    /** 整个上下文块的硬预算。 */
    const val CONTEXT_BUDGET = 800

    const val MAX_WEEK_CHARS = 120
    const val MAX_PHASE_CHARS = 200

    fun dayDigest(plan: DayPlan, reason: String = "", deviation: Double = 0.0): DayDigest =
        DayDigest(
            date = plan.date,
            done = plan.doneCount,
            total = plan.entries.size,
            missed = plan.entries.filter { !it.done }.map { it.text },
            reason = reason,
            deviation = deviation,
        )

    /** 7 天滚成一周：只留完成率、最常卡住的那一条、一句趋势。 */
    fun weekDigest(days: List<DayDigest>): WeekDigest {
        if (days.isEmpty()) return WeekDigest("", "")
        val done = days.sumOf { it.done }
        val total = days.sumOf { it.total }
        val rate = if (total == 0) 0 else (done * 100 / total)
        val stuck = days.flatMap { it.missed }.groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key
        val text = buildString {
            append(if (days.size >= 7) "本周" else days.size.toString() + " 天")
            append("完成 ").append(done).append("/").append(total)
            append("（").append(rate).append("%）")
            if (stuck != null) append("；反复没做的是「").append(stuck).append("」")
            val reasons = days.mapNotNull { it.reason.takeIf { r -> r.isNotBlank() } }.distinct()
            if (reasons.isNotEmpty()) append("；原因集中在：").append(reasons.take(2).joinToString("、"))
        }
        return WeekDigest(days.last().date, clamp(text, MAX_WEEK_CHARS))
    }

    /** 4 周滚成阶段：只留总量与方向。 */
    fun phaseDigest(weeks: List<WeekDigest>): PhaseDigest {
        if (weeks.isEmpty()) return PhaseDigest("", "", "")
        val text = buildString {
            append("最近 ").append(weeks.size).append(" 周：")
            append(weeks.joinToString("；") { it.text })
        }
        return PhaseDigest(weeks.first().weekOf, weeks.last().weekOf, clamp(text, MAX_PHASE_CHARS))
    }

    /**
     * 组装进 AI 上下文的那一块。
     *
     * 预算吃紧时的取舍顺序是写死的，不是看哪段长：
     * 1. 先砍历史摘要（阶段 → 周 → 天，从远到近）；
     * 2. 再砍明日条目；
     * 3. 最后才动今日条目 —— 它是这一轮对话唯一「现在就能做」的东西。
     *
     * 反过来砍（先砍今天）会让模型答得头头是道却没有一条能执行。
     */
    fun contextBlock(
        phase: PhaseDigest?,
        days: List<DayDigest>,
        today: DayPlan?,
        tomorrow: DayPlan?,
    ): String {
        val header = StringBuilder()
        if (phase != null && phase.text.isNotBlank()) {
            header.append("【阶段】").append(phase.text).append('\n')
        }
        // 最近 3 条，从近到远排，读起来才是「昨天、前天、大前天」。
        val recent = days.takeLast(3).reversed()
        if (recent.isNotEmpty()) {
            header.append("【最近】").append(recent.joinToString(" / ") { it.render() }).append('\n')
        }

        val todayBlock = today?.let { renderDay("今天", it) }.orEmpty()
        val tomorrowBlock = tomorrow?.let { renderDay("明天", it) }.orEmpty()

        fun assemble(withTomorrow: Boolean, withRecent: Boolean, withPhase: Boolean): String {
            val sb = StringBuilder()
            if (withPhase && phase != null && phase.text.isNotBlank()) {
                sb.append("【阶段】").append(phase.text).append('\n')
            }
            if (withRecent && recent.isNotEmpty()) {
                sb.append("【最近】").append(recent.joinToString(" / ") { it.render() }).append('\n')
            }
            sb.append(todayBlock)
            if (withTomorrow) sb.append(tomorrowBlock)
            return sb.toString().trimEnd()
        }

        // 从最全的一版开始，逐级退让，第一个装得下的就用。
        val attempts = listOf(
            assemble(true, true, true),
            assemble(true, false, false),
            assemble(false, false, false),
            clamp(todayBlock.trimEnd(), CONTEXT_BUDGET),
        )
        return attempts.firstOrNull { it.length <= CONTEXT_BUDGET } ?: attempts.last()
    }

    private fun renderDay(label: String, plan: DayPlan): String = buildString {
        append('【').append(label).append("】").append(plan.date).append(' ')
        if (plan.entries.isEmpty()) {
            append("（还没排）")
            return@buildString
        }
        append(plan.entries.joinToString("；") { entry ->
            (if (entry.done) "✓" else "○") + entry.text + " " + entry.minutes + "分 → " + entry.output
        })
        append('\n')
    }

    /** 按字符截断，末尾留一个省略号，避免出现半个词。 */
    fun clamp(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take((limit - 1).coerceAtLeast(1)) + "…"
}
