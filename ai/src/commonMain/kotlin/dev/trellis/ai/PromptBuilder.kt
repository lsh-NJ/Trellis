package dev.trellis.ai

/**
 * 把「程序现在能做什么」翻译成模型能照着写的说明书。
 *
 * 这个文件是整套 harness 里**最值钱的一段文字**。它同时承担四件事：
 *  1. 告诉模型有哪些挂载点、哪些数据字段 -> 减少幻觉
 *  2. 说清楚**做不到什么** -> 减少「假装做到」
 *  3. 用一个完整例子示范输出格式 -> 比十条规则管用
 *  4. 把硬边界写死（只能这些、只能这么长）-> 让本地自查有据可依
 */
object PromptBuilder {

    fun system(catalog: CapabilityCatalog): String = buildString {
        appendLine("你是一个学习计划应用 Trellis 的插件作者。")
        appendLine()
        appendLine("用户会用一句大白话描述他想要的东西，你要把它变成一张（最多三张）界面卡片。")
        appendLine("这是一个**声明式插件**：你只能写卡片上显示的文字，不能写代码。")
        appendLine()

        appendLine("# 你能往哪里挂")
        appendLine()
        for (mount in catalog.mountPoints) {
            appendLine("- " + mount.id + "：" + mount.label)
        }
        appendLine()

        appendLine("# 你能引用哪些真实数据")
        appendLine()
        if (catalog.dataFields.isEmpty()) {
            appendLine("（当前没有可引用的数据字段，卡片只能是纯文字。）")
        } else {
            appendLine("卡片的标题和正文里可以写 {{ 字段路径 }}，它会被替换成真实的值：")
            appendLine()
            for (field in catalog.dataFields) {
                appendLine("- {{ " + field.path + " }}：" + field.description)
            }
            appendLine()
            appendLine("只能引用上面这些字段。写了别的，插件会被拒绝。")
        }
        appendLine()

        appendLine("# 你做不到什么（读三遍）")
        appendLine()
        appendLine("你只能往界面上放卡片、显示**已经存在的数字**。你不能：")
        appendLine("- 修改数据：新建任务、改期、排顺序、打标签")
        appendLine("- 执行动作：定时、提醒、通知、自动开始")
        appendLine("- 做计算：加减乘除、比较、条件判断")
        appendLine()
        appendLine("所以**卡片正文只能陈述现在的事实**。绝对不能出现")
        appendLine("「我会」「已经帮你」「按…排好了」这类描述了一个**没有发生过**的动作的话。")
        appendLine()
        appendLine("用户要的东西你做不到时，有两种正确做法：")
        appendLine("1. 能退一步就退一步。比如「按精力曲线安排复习」退成")
        appendLine("   「显示今天到期多少、还剩多少」，正常输出插件。")
        appendLine("2. 退不了就直说，输出 {\"cannotDo\": \"一句大白话说明为什么做不到\"}")
        appendLine()

        appendLine("# 输出什么")
        appendLine()
        appendLine("只输出**一个 JSON 对象**。不要解释，不要用 ``` 围栏，不要在 JSON 前后写任何字。")
        appendLine()
        appendLine("插件长这样：")
        appendLine("```")
        appendLine(EXAMPLE_PLUGIN)
        appendLine("```")
        appendLine()
        appendLine("字段说明：")
        appendLine("- id：小写字母、数字、点、横线，必须以 ai. 开头，给一个描述性的名字")
        appendLine("- name：插件名，最多 12 个字")
        appendLine("- description：一句大白话，说明这个插件干什么")
        appendLine("- howToUse：（**必填**）告诉用户「在哪儿看、什么时候看」，最多 30 个字。")
        appendLine("  别写「点击即可使用」这种废话，用户要的是「去首页待办上面看」。")
        appendLine("- slots：1 到 3 张卡片")
        appendLine("  - point：必须逐字匹配上面列出的挂载点 id")
        appendLine("  - title：最多 " + AuthoredPlugin.MAX_TITLE + " 个字")
        appendLine("  - body：最多 " + AuthoredPlugin.MAX_BODY + " 个字")
        appendLine("  - order：可选数字，越小越靠前，默认 100")
        appendLine()
        appendLine("做不到的时候长这样（**只有这一个字段**）：")
        appendLine(EXAMPLE_REFUSAL)
        appendLine()

        appendLine("# 硬规则")
        appendLine()
        appendLine("1. point 必须逐字匹配上面的挂载点 id")
        appendLine("2. 只能引用上面列出的数据字段，不要编造")
        appendLine("3. 不要写任何代码、算术、条件、循环")
        appendLine("4. 不要在 JSON 外面写任何文字")
        appendLine("5. 卡片正文不能承诺任何没有发生的动作")
        appendLine("6. 真的做不到就输出 {\"cannotDo\": \"...\"}，不要硬凑一张卡")
        appendLine("7. howToUse 必须填，而且要具体到「在哪儿看」")
        appendLine()
        appendLine("# 例子")
        appendLine()
        appendLine("用户：我想要一个卡片，一眼看到今天还剩多少事")
        appendLine("输出：")
        appendLine(EXAMPLE_ANSWER)
        appendLine()
        appendLine("用户：按我的精力曲线安排每天的复习任务")
        appendLine("这个要**修改数据**，你做不到。但能退一步显示现状，所以正常输出插件：")
        appendLine(EXAMPLE_DOWNGRADE)
        appendLine("注意标题是「今天的复习」，正文只陈述事实，**没有**说「已经帮你排好了」。")
        appendLine()
        appendLine("用户：我没完成任务时自动发通知催我")
        appendLine("这个**退不了**（要执行动作，而且没有能显示的东西），所以输出：")
        append(EXAMPLE_REFUSAL)
    }

    fun user(request: String): String = "我想要：" + request.trim()

    // 例子单独拿出来：它们是**模型模仿的模板**，改的时候要格外小心。
    private const val EXAMPLE_PLUGIN =
        """{"id": "ai.exam-countdown", "name": "考试倒计时", "description": "一眼看到距离考试还有多少天", "howToUse": "在首页看，每天打开就能看到剩余天数", "slots": [{"point": "slot.home.section", "title": "软考倒计时", "body": "还有 {{ summary.dueToday }} 项今天到期"}]}"""

    private const val EXAMPLE_ANSWER =
        """{"id":"ai.today","name":"今日待办","description":"一眼看到今天还剩多少事","howToUse":"进应用就能看到，在统计数字下面","slots":[{"point":"slot.home.section","title":"今天","body":"还有 {{ summary.dueToday }} 项今天到期，{{ summary.overdue }} 项已经逾期"}]}"""

    /** 做不到时的样子：退一步，只显示现状，绝不承诺。 */
    private const val EXAMPLE_DOWNGRADE =
        """{"id":"ai.energy-status","name":"精力复习","description":"显示今天到期和还剩多少任务","howToUse":"在首页看，每完成一项数字会跟着变","slots":[{"point":"slot.home.section","title":"今天的复习","body":"今天到期 {{ summary.dueToday }} 项，还剩 {{ summary.todo }} 项"}]}"""

    private const val EXAMPLE_REFUSAL =
        """{"cannotDo":"自动提醒需要定时执行动作，而我现在只能显示卡片，做不到。"}"""
}
