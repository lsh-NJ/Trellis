package dev.trellis.app.desktop

import dev.trellis.ai.AuthoredPlugin
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextStyle

/**
 * 把「装上之后会画成什么样」摊成一段给模型看的文字。
 *
 * 为什么需要它：`try_plugin` 以前只回「跑通了」，模型看不见卡片长什么样就敢
 * `deploy` —— 用户的每条需求有没有落到卡片上、按钮点下去交给谁，全靠猜。
 * 装完也不核对，于是「在读的书 1.0」这种错要等用户自己发现。
 *
 * 这里做**纯数据**的摊平：输入是 [UiNode]，输出是多行文字。不碰 Compose、
 * 不碰内核，所以可以单测，也可以在后台线程里跑。
 * 脚本卡片的节点从哪来见 [ScriptSelfTest] / [PluginTools.prepare]。
 */
internal object PluginCardPreview {

    /**
     * 一张脚本卡片。
     *
     * @param cardId 脚本里 `trellis.register` 声明的卡 id
     * @param pointId 挂载点，例如 `slot.today.body`
     * @param node 用示例数据渲染出来的节点树
     */
    fun describe(cardId: String, pointId: String, node: UiNode): String {
        val lines = mutableListOf<String>()
        walk(node, 1, lines)
        val title = titleOf(node)
        val header = buildString {
            append("- 卡片 ").append(cardId).append("（挂载点 ").append(pointId).append("）")
            if (title.isNotBlank() && title != cardId) append(" 标题：").append(title)
        }
        return if (lines.isEmpty()) {
            header + "\n  （这张卡画出来是空的：没有文字、数值、按钮或输入框）"
        } else {
            header + "\n" + lines.joinToString("\n")
        }
    }

    /** 渲染失败时的预览行：给出错因，而不是让 try_plugin 整个炸掉。 */
    fun failed(cardId: String, pointId: String, reason: String): String =
        "- 卡片 " + cardId + "（挂载点 " + pointId + "）渲染失败：" + reason

    /**
     * 渲染预览用的假数据。
     *
     * 键名必须和 [ScriptSelfTest.sampleData] / `ScriptPlugin.read()` **一模一样**，
     * 否则预览渲染通过、真跑白屏，等于没预览。
     */
    fun sampleData(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        return linkedMapOf(
            "now" to now,
            "dayStart" to now,
            "dayEnd" to now,
            "summary" to linkedMapOf(
                "total" to 3,
                "done" to 1,
                "doing" to 0,
                "todo" to 2,
                "overdue" to 0,
                "dueToday" to 1,
                "spentMinutes" to 75,
            ),
            "tasks" to listOf(
                linkedMapOf(
                    "id" to "sample-task-1",
                    "title" to "示例任务",
                    "status" to "TODO",
                    "spentMinutes" to 0,
                ),
            ),
            "store" to linkedMapOf<String, String>(),
        )
    }

    /**
     * 声明式草稿的预览。
     *
     * 声明式插件没有「跑一遍」这回事（它只是模板 + 规则），所以直接按草稿字段
     * 列出来：模型仍能一眼核对「需求有没有落地、按钮点名的是哪个宿主动作」。
     */
    fun describeDeclarative(pluginId: String, draft: AuthoredPlugin): List<String> {
        if (draft.slots.isEmpty()) return emptyList()
        return draft.slots.mapIndexed { index, slot ->
            val lines = mutableListOf<String>()
            val title = slot.title.ifBlank { "卡片 " + (index + 1) }
            lines += "- 卡片 " + pluginId + "#" + (index + 1) + "（挂载点 " + slot.point + "） 标题：" + title
            if (slot.body.isNotBlank()) lines += "  文本：" + slot.body
            slot.stat?.let { stat ->
                val unit = if (stat.unit.isBlank()) "" else " " + stat.unit
                lines += "  Stat：" + stat.label.ifBlank { "数值" } + " = " + stat.value + unit
            }
            slot.progress?.let { progress ->
                lines += "  进度：" + progress.desc.ifBlank { "进度" } + " " + progress.value + "/" + progress.max
            }
            slot.badges.filter { it.text.isNotBlank() }.forEach { badge ->
                lines += "  Badge：" + badge.text
            }
            slot.actions.forEach { action ->
                lines += "  按钮：「" + action.label.ifBlank { action.action } + "」，动作 → 宿主动作 " + action.action
            }
            lines.joinToString("\n")
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun walk(node: UiNode, depth: Int, out: MutableList<String>) {
        val indent = "  ".repeat(depth)
        fun add(label: String, text: String) {
            if (text.isNotBlank()) out += indent + label + "：" + text
        }
        fun tap(tap: UiTap): String = when (tap) {
            is UiTap.Host -> {
                val args = if (tap.call.args.isEmpty()) "" else
                    "（参数 " + tap.call.args.entries.joinToString("、") { it.key + "=" + it.value } + "）"
                "宿主动作 " + tap.call.action + args
            }
            is UiTap.Plugin -> {
                val args = if (tap.args.isEmpty()) "" else
                    "（参数 " + tap.args.entries.joinToString("、") { it.key + "=" + it.value } + "）"
                "插件处理函数 " + tap.handler + args
            }
        }

        when (node) {
            is UiNode.Column -> node.children.forEach { walk(it, depth, out) }
            is UiNode.Row -> node.children.forEach { walk(it, depth, out) }
            is UiNode.Section -> {
                add("小标题", node.title)
                node.children.forEach { walk(it, depth, out) }
            }
            is UiNode.Grid -> node.children.forEach { walk(it, depth, out) }
            is UiNode.Cell -> walk(node.child, depth, out)
            is UiNode.Text -> add("文本", node.text)
            is UiNode.Stat -> add("Stat", node.label.ifBlank { "数值" } + " = " + node.value)
            is UiNode.Badge -> add("Badge", node.text)
            is UiNode.Progress -> add("进度", node.label.ifBlank { "进度" } + " " + node.value + "/" + node.max)
            is UiNode.Bars -> add(
                "条形图",
                node.label.ifBlank { "数据" } + "：" +
                    node.items.joinToString("、") { it.label + " " + it.value },
            )
            is UiNode.Sparkline -> add("走势图", node.label.ifBlank { "走势" })
            is UiNode.Columns -> add("柱状图", node.label.ifBlank { "数据" } + "：" + node.values.joinToString(","))
            is UiNode.Area -> add("面积图", node.label.ifBlank { "数据" } + "：" + node.values.joinToString(","))
            is UiNode.Ring -> add("环形进度", node.label.ifBlank { "进度" } + " " + node.value + "/" + node.max)
            is UiNode.DotMatrix -> add("点阵数字", node.value)
            is UiNode.Table -> {
                if (node.caption.isNotBlank()) add("表格", node.caption)
                if (node.headers.isNotEmpty()) add("表头", node.headers.joinToString(" "))
                node.rows.forEach { add("表格行", it.joinToString(" ")) }
            }
            is UiNode.Icon -> add("图标", node.glyph)
            is UiNode.Image -> add("图片", node.alt.ifBlank { node.ref })
            is UiNode.HeatStrip -> add("打卡格", node.values.joinToString(","))
            is UiNode.ListRow -> {
                val text = node.title + (node.subtitle?.let { " · " + it } ?: "") +
                    (node.trailing?.let { "（" + it + "）" } ?: "")
                add("列表行", text + (node.onTap?.let { "，动作 → " + tap(it) } ?: ""))
            }
            is UiNode.Switch -> add(
                "开关",
                node.label + (if (node.checked) "（开）" else "（关）") + "，动作 → " + tap(node.onTap),
            )
            is UiNode.Chip -> add("标签", node.label + (node.onTap?.let { "，动作 → " + tap(it) } ?: ""))
            is UiNode.Button -> add("按钮", "「" + node.label + "」，动作 → " + tap(node.onTap))
            is UiNode.Input -> add(
                if (node.popup) "浮窗输入" else "输入框",
                if (node.popup) {
                    "卡片上只有一行「" + node.submitLabel.ifBlank { "提交" } + "」，点开浮窗后填「" +
                        node.hint + "」，动作 → " + tap(node.onSubmit)
                } else {
                    "提示「" + node.hint + "」，按回车或点「↵ 提交」提交，动作 → " + tap(node.onSubmit)
                },
            )
            is UiNode.Stepper -> add(
                "计步器",
                node.label.ifBlank { "计数" } + " " + node.value + node.unit + "，动作 → " + tap(node.onStep),
            )
            is UiNode.Notice -> add("提示条", node.text)
            is UiNode.Spacer -> Unit
            UiNode.Divider -> Unit
        }
    }

    /** 卡片标题：优先 Section 标题，其次 Title 样式的文字，再次第一段文字。 */
    private fun titleOf(node: UiNode): String {
        var firstText = ""
        var styledTitle = ""
        fun visit(n: UiNode) {
            when (n) {
                is UiNode.Column -> n.children.forEach(::visit)
                is UiNode.Row -> n.children.forEach(::visit)
                is UiNode.Grid -> n.children.forEach(::visit)
                is UiNode.Cell -> visit(n.child)
                is UiNode.Section -> {
                    if (styledTitle.isBlank() && n.title.isNotBlank()) styledTitle = n.title
                    n.children.forEach(::visit)
                }
                is UiNode.Text -> {
                    if (firstText.isBlank()) firstText = n.text
                    if (styledTitle.isBlank() && n.style == UiTextStyle.Title) styledTitle = n.text
                }
                else -> Unit
            }
        }
        visit(node)
        return styledTitle.ifBlank { firstText }
    }
}
