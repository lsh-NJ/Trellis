package dev.trellis.ai

/**
 * 把「程序现在能做什么」翻译成模型能照着写的说明书。
 *
 * 这个文件是整套 harness 里**最值钱的一段文字**。它同时承担三件事：
 *  1. 告诉模型有哪些挂载点、哪些数据字段 —— 减少幻觉
 *  2. 用一个完整例子示范输出格式 —— 比十条规则管用
 *  3. 把硬边界写死（只能这些、只能这么长）—— 让本地自查有据可依
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

        appendLine("# 输出什么")
        appendLine()
        appendLine("只输出**一个 JSON 对象**。不要解释，不要用 ``` 围栏，不要在 JSON 前后写任何字。")
        appendLine()
        appendLine("```")
        appendLine("{\"id\": \"ai.exam-countdown\", \"name\": \"考试倒计时\", \"description\": \"一眼看到距离考试还有多少天\", \"slots\": [{\"point\": \"slot.home.section\", \"title\": \"软考倒计时\", \"body\": \"还有 {{ summary.dueToday }} 项今天到期\"}]}")
        appendLine("```")
        appendLine()
        appendLine("字段说明：")
        appendLine("- id：小写字母、数字、点、横线，必须以 ai. 开头，给一个描述性的名字")
        appendLine("- name：插件名，最多 12 个字")
        appendLine("- description：一句大白话，说明这个插件干什么")
        appendLine("- slots：1 到 3 张卡片")
        appendLine("  - point：必须逐字匹配上面列出的挂载点 id，一个字都不能差")
        appendLine("  - title：最多 " + AuthoredPlugin.MAX_TITLE + " 个字")
        appendLine("  - body：最多 " + AuthoredPlugin.MAX_BODY + " 个字")
        appendLine("  - order：可选数字，越小越靠前，默认 100")
        appendLine()

        appendLine("# 硬规则")
        appendLine()
        appendLine("1. point 必须逐字匹配上面的挂载点 id")
        appendLine("2. 只能引用上面列出的数据字段，不要编造")
        appendLine("3. 不要写任何代码、算术、条件、循环")
        appendLine("4. 不要在 JSON 外面写任何文字")
        appendLine()
        appendLine("# 例子")
        appendLine()
        appendLine("用户：我想要一个卡片，一眼看到今天还剩多少事")
        appendLine("输出：")
        append("{\"id\":\"ai.today\",\"name\":\"今日待办\",\"description\":\"一眼看到今天还剩多少事\",\"slots\":[{\"point\":\"slot.home.section\",\"title\":\"今天\",\"body\":\"还有 {{ summary.dueToday }} 项今天到期，{{ summary.overdue }} 项已经逾期\"}]}")
    }

    fun user(request: String): String =
        "我想要：" + request.trim()
}
