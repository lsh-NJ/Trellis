package dev.trellis.feature.goal

/** 一条拒绝理由。[field] 指向具体哪个字段，模型照着改就能过。 */
data class Rejection(val field: String, val reason: String) {
    override fun toString(): String = field + "：" + reason
}

/**
 * 日计划的写入校验。
 *
 * 为什么要在**写入前**拦：空泛条目一旦落库，第二天就会变成一条勾不掉的待办，
 * 而用户会以为是自己没做完。拦在门口、把原因原样退回给模型重写，比事后纠错便宜得多。
 *
 * 所有规则都是纯函数，没有一条依赖「模型觉得」。
 */
object DayPlanValidator {

    const val MIN_MINUTES = 5
    const val MAX_MINUTES = 240
    const val MIN_ENTRIES = 2
    const val MAX_ENTRIES = 5
    const val MAX_TEXT_CHARS = 24

    /** 每日投入的容忍上限：排到 1.2 倍还算「今天能做完」，再多就是自欺欺人。 */
    const val OVERLOAD_RATIO = 1.2

    /** 空洞产出词：写了等于没写。 */
    val HOLLOW_WORDS = listOf("复习完", "学完", "看一遍", "了解一下")

    /** 模糊词：说明这条计划还没想清楚。 */
    val VAGUE_WORDS = listOf("之类", "相关", "一些")

    /**
     * 常见动词开头。只用来做「不像一条动作」的软提示，列表外的不算错 ——
     * 中文没有词性标注，硬判会把「背 30 个单词」这类正常条目误杀。
     */
    private val VERBS = listOf(
        "背", "读", "写", "做", "练", "算", "查", "画", "列", "跑", "看", "听", "说",
        "整理", "总结", "复述", "推导", "默写", "翻译", "刷", "过", "拆", "标", "记",
        "完成", "提交", "输出", "产出", "对比", "调研", "复盘", "改", "补", "抄", "算清",
    )

    /** 单条校验。 */
    fun validateEntry(entry: DayEntry): List<Rejection> {
        val out = mutableListOf<Rejection>()

        if (entry.minutes < MIN_MINUTES || entry.minutes > MAX_MINUTES) {
            out += Rejection(
                "minutes",
                "必须在 " + MIN_MINUTES + "–" + MAX_MINUTES + " 分钟之间，现在是 " + entry.minutes +
                    "；请按这件事的真实耗时给一个数",
            )
        }

        if (entry.output.isBlank()) {
            out += Rejection("output", "不能为空：写清楚做完之后**手上有什么**（一页笔记 / 20 道题 / 一张对比表）")
        } else {
            HOLLOW_WORDS.firstOrNull { entry.output.contains(it) }?.let { word ->
                out += Rejection(
                    "output",
                    "「" + word + "」不是产出，是状态。改成能拿在手上的东西，" +
                        "比如「一页 A4 的公式清单」「20 道错题订正」",
                )
            }
        }

        val text = entry.text.trim()
        if (text.isEmpty()) {
            out += Rejection("text", "不能为空")
        } else {
            if (text.length > MAX_TEXT_CHARS) {
                out += Rejection(
                    "text",
                    "不超过 " + MAX_TEXT_CHARS + " 字，现在 " + text.length + " 字；一条只做一件事",
                )
            }
            hitsVague(text)?.let { word ->
                out += Rejection("text", "含模糊词「" + word + "」，说明这条还没想清楚，请换成具体动作")
            }
            if (!startsWithVerb(text)) {
                out += Rejection(
                    "text",
                    "要以动词开头（背 / 写 / 算 / 整理 / 查 / 对比…），现在读起来像一个名词短语",
                )
            }
        }

        if (entry.doneCriteria.isBlank()) {
            out += Rejection("doneCriteria", "不能为空：写清楚「怎样算做完」，比如「能不看答案复述 5 个概念」")
        }

        if (entry.goalRef.isBlank()) {
            out += Rejection("goalRef", "必须指向一个阶段目标；没有归属的条目不算计划")
        }

        return out
    }

    /** 整天校验（含条数与总量）。 */
    fun validate(plan: DayPlan, dailyMinutes: Int): List<Rejection> {
        val out = mutableListOf<Rejection>()

        if (plan.entries.size < MIN_ENTRIES) {
            out += Rejection("entries", "一天至少 " + MIN_ENTRIES + " 条，现在 " + plan.entries.size + " 条")
        }
        if (plan.entries.size > MAX_ENTRIES) {
            out += Rejection(
                "entries",
                "一天最多 " + MAX_ENTRIES + " 条，现在 " + plan.entries.size + " 条；超了会变成做不完的清单",
            )
        }
        plan.entries.forEachIndexed { index, entry ->
            validateEntry(entry).forEach { rejection ->
                out += Rejection("entries[" + index + "]." + rejection.field, rejection.reason)
            }
        }

        if (dailyMinutes > 0) {
            val cap = (dailyMinutes * OVERLOAD_RATIO).toInt()
            if (plan.totalMinutes > cap) {
                out += Rejection(
                    "totalMinutes",
                    "合计 " + plan.totalMinutes + " 分钟，超过每日投入 " + dailyMinutes + " 分钟的 " +
                        OVERLOAD_RATIO + " 倍（上限 " + cap + "）；请砍掉或缩短几条",
                )
            }
        }
        return out
    }

    /** 命中模糊词就返回那个词。 */
    fun hitsVague(text: String): String? {
        VAGUE_WORDS.firstOrNull { text.contains(it) }?.let { return it }
        // 「等」只在列举收尾时才算模糊：「读书、写字等」「整理资料等等」。
        // 「等待 5 分钟」「等于」不是模糊，误杀会让正常条目写不进来。
        if (Regex("等{1,2}(?=[，。；、！？\\s]|$)").containsMatchIn(text)) return "等"
        return null
    }

    /** 软判定：是不是以常见动词开头。列表外的按通过处理。 */
    fun startsWithVerb(text: String): Boolean = VERBS.any { text.startsWith(it) }
}
