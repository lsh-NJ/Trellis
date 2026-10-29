package dev.trellis.feature.goal

/**
 * 「这个问题给哪几个选项」。
 *
 * 为什么选项写死在插件里，而不是每轮让模型自由发挥：
 *
 * 1. **答案要能一键写回契约。** 选项是枚举值，点一下就落库；模型自由生成的是句子，
 *    同一个意思十种写法，写进去之后既没法比较也没法统计。
 * 2. **同一句话问两次，选项必须一样。** 模型每轮重新编，用户会觉得它在乱问。
 * 3. **卡片只有 260dp 宽。** 每个选项都要短到一行放得下，长句子会被折成两行，看着像坏了。
 *
 * 选项不是封闭集合：卡片上还有一个输入框，用户想自己说就自己说；也可以点「跳过」，
 * 记成 UNKNOWN，两次之后这个字段就不再问了（见 [Confidence]）。
 */
object GoalQuestions {

    /** 选项的硬上限：一行两个，最多两行。再多就把侧条撑爆了。 */
    const val MAX_OPTIONS = 4

    /** 单个选项的字数上限。超过它，卡片上就会折行。 */
    const val MAX_OPTION_CHARS = 10

    fun options(key: FieldKey): List<String> = when (key) {
        // 通用
        FieldKey.DAILY_MINUTES -> listOf("半小时", "1 小时", "2 小时", "4 小时以上")
        FieldKey.DEADLINE -> listOf("3 个月内", "半年内", "一年内", "还没定")
        FieldKey.CURRENT_LEVEL -> listOf("零基础", "学过一点", "有一定基础", "比较熟")
        FieldKey.HARD_CONSTRAINTS -> listOf("在职，只有晚上", "时间很零散", "全职备考", "没什么限制")

        // 学业
        FieldKey.MAJOR -> listOf("本专业", "相近专业", "跨专业", "还没定")
        FieldKey.TARGET_TIER -> listOf("本校", "211 及以上", "985", "还没定")
        FieldKey.WEAK_SUBJECTS -> listOf("数学", "英语", "专业课", "政治")
        FieldKey.EXAM_SUBJECTS -> listOf("四门全考", "英语加专业课", "只考专业课", "还没查")

        // 求职
        FieldKey.TARGET_ROLE -> listOf("后端", "前端", "算法", "产品")
        FieldKey.TARGET_INDUSTRY -> listOf("互联网", "国企央企", "外企", "还没定")
        FieldKey.EXPERIENCE -> listOf("应届", "1–3 年", "3 年以上", "转行")
        FieldKey.PORTFOLIO -> listOf("有项目可讲", "只有练手项目", "还没有", "有线上作品")
    }

    /** 「不知道 / 跳过」不算选项，它是单独一个按钮：语义不同，不能混进答案里。 */
    const val SKIP_LABEL = "不知道，跳过"
}
