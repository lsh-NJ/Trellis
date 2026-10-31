package dev.trellis.app.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange

/**
 * 编辑器的「格式」入口，只有两份清单：
 *
 * - [markdownCommands]：斜杠菜单里的命令。用户敲 `/` 唤起、打几个字过滤、回车插入，
 *   编辑界面上**不再摆一排工具栏按钮**（按钮占一行高度，而且十个按钮也放不下十六种语法）；
 * - [markdownKeyBindings]：直接可用的快捷键。键位只在这里定义一次，
 *   键盘分发和「快捷键清单」都读它，不会出现「说明书写的和实际按键不一致」。
 *
 * 命令都是纯函数（文本 + 选区 → 新文本 + 新选区），所以能在单测里钉住。
 */

/** 菜单里的分组，只影响排序和分组标题。 */
internal enum class MarkdownCommandGroup(val label: String) {
    Block("块级"),
    Inline("行内"),
    Insert("插入"),
}

internal data class MarkdownCommand(
    val id: String,
    val label: String,
    /** 命令的补充说明，菜单第二行显示。 */
    val hint: String,
    /** 过滤用的别名（拼音首字母、英文、常见叫法）。 */
    val keywords: List<String>,
    val group: MarkdownCommandGroup,
    val apply: (String, TextRange) -> MarkdownEditResult,
)

internal val markdownCommands: List<MarkdownCommand> = listOf(
    MarkdownCommand(
        id = "h1",
        label = "一级标题",
        hint = "# 大标题",
        keywords = listOf("h1", "biaoti", "bt", "title", "heading"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> setHeadingLevel(text, selection, 1) },
    ),
    MarkdownCommand(
        id = "h2",
        label = "二级标题",
        hint = "## 小节",
        keywords = listOf("h2", "biaoti", "bt", "heading"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> setHeadingLevel(text, selection, 2) },
    ),
    MarkdownCommand(
        id = "h3",
        label = "三级标题",
        hint = "### 更小的节",
        keywords = listOf("h3", "biaoti", "bt", "heading"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> setHeadingLevel(text, selection, 3) },
    ),
    MarkdownCommand(
        id = "paragraph",
        label = "正文",
        hint = "去掉标题层级",
        keywords = listOf("zhengwen", "zw", "text", "paragraph"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> clearHeading(text, selection) },
    ),
    MarkdownCommand(
        id = "bullet",
        label = "无序列表",
        hint = "- 一项",
        keywords = listOf("liebiao", "lb", "list", "bullet", "wuxu"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> toggleLineMarkdown(text, selection, "- ") },
    ),
    MarkdownCommand(
        id = "numbered",
        label = "有序列表",
        hint = "1. 第一步",
        keywords = listOf("liebiao", "lb", "list", "number", "youxu", "bianhao"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> toggleNumberedList(text, selection) },
    ),
    MarkdownCommand(
        id = "task",
        label = "任务清单",
        hint = "- [ ] 待办",
        keywords = listOf("renwu", "rw", "todo", "task", "qingdan", "checkbox"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> toggleTaskList(text, selection) },
    ),
    MarkdownCommand(
        id = "quote",
        label = "引用",
        hint = "> 引用一段",
        keywords = listOf("yinyong", "yy", "quote", "blockquote"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> toggleLineMarkdown(text, selection, "> ") },
    ),
    MarkdownCommand(
        id = "rule",
        label = "分割线",
        hint = "--- 横向分隔",
        keywords = listOf("fenge", "fg", "rule", "hr", "divider", "hengxian"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> insertHorizontalRule(text, selection) },
    ),
    MarkdownCommand(
        id = "codeblock",
        label = "代码块",
        hint = "``` 围栏",
        keywords = listOf("daima", "dm", "code", "fence", "codeblock"),
        group = MarkdownCommandGroup.Block,
        apply = { text, selection -> insertCodeBlock(text, selection) },
    ),
    MarkdownCommand(
        id = "bold",
        label = "粗体",
        hint = "**加粗**",
        keywords = listOf("cuti", "ct", "bold", "strong", "b"),
        group = MarkdownCommandGroup.Inline,
        apply = { text, selection -> toggleInlineMarkdown(text, selection, "**", "粗体") },
    ),
    MarkdownCommand(
        id = "italic",
        label = "斜体",
        hint = "*斜体*",
        keywords = listOf("xieti", "xt", "italic", "em", "i"),
        group = MarkdownCommandGroup.Inline,
        apply = { text, selection -> toggleInlineMarkdown(text, selection, "*", "斜体") },
    ),
    MarkdownCommand(
        id = "strike",
        label = "删除线",
        hint = "~~删掉~~",
        keywords = listOf("shanchuxian", "scx", "strike", "del"),
        group = MarkdownCommandGroup.Inline,
        apply = { text, selection -> toggleInlineMarkdown(text, selection, "~~", "删除线") },
    ),
    MarkdownCommand(
        id = "code",
        label = "行内代码",
        hint = "`代码`",
        keywords = listOf("daima", "dm", "code", "inline"),
        group = MarkdownCommandGroup.Inline,
        apply = { text, selection -> toggleInlineMarkdown(text, selection, "`", "代码") },
    ),
    MarkdownCommand(
        id = "link",
        label = "链接",
        hint = "[文字](url)",
        keywords = listOf("lianjie", "lj", "link", "url", "a"),
        group = MarkdownCommandGroup.Inline,
        apply = { text, selection -> insertMarkdownLink(text, selection) },
    ),
    MarkdownCommand(
        id = "table",
        label = "表格",
        hint = "2×2 空表",
        keywords = listOf("biaoge", "bg", "table"),
        group = MarkdownCommandGroup.Insert,
        apply = { text, selection -> insertMarkdownTable(text, selection) },
    ),
    MarkdownCommand(
        id = "image",
        label = "图片",
        hint = "![说明](url)",
        keywords = listOf("tupian", "tp", "image", "img", "pic"),
        group = MarkdownCommandGroup.Insert,
        apply = { text, selection -> insertMarkdownImage(text, selection) },
    ),
)

internal fun markdownCommand(id: String): MarkdownCommand? = markdownCommands.firstOrNull { it.id == id }

/**
 * 斜杠触发：光标前一个字符正好是 `/`，且它在行首或空白之后。
 *
 * 不做「打字打到一半的斜杠」判断，是因为 `10/3`、`docs/a.md` 这类内容太常见；
 * 要求 `/` 前面是空白（或行首），才能把「打命令」和「写路径」分开。
 */
internal fun slashTriggerStart(text: String, cursor: Int): Int? {
    if (cursor <= 0 || cursor > text.length) return null
    if (text[cursor - 1] != '/') return null
    if (insideCodeFence(text, cursor)) return null
    val before = cursor - 2
    if (before >= 0 && !text[before].isWhitespace()) return null
    return cursor - 1
}

/** 光标还在 `/{query}` 这段里就返回 query；移出去（或打了空格/换行）返回 null。 */
internal fun slashQueryAt(text: String, slashStart: Int, cursor: Int): String? {
    if (slashStart < 0 || cursor <= slashStart || cursor > text.length) return null
    if (slashStart >= text.length || text[slashStart] != '/') return null
    val query = text.substring(slashStart + 1, cursor)
    if (query.any { it.isWhitespace() || it == '/' }) return null
    return query
}

/**
 * 按 query 过滤命令：空的给全部，否则按「匹配得多像」排序。
 *
 * 排序规则：标签前缀 > 命令名前缀 > 别名前缀 > 标签包含 > 命令名包含 > 别名包含。
 * 这样敲 `b` 第一项就是粗体（而不是「代码块」），敲 `ct` 第一项是粗体、`picture`
 * 这类边缘命中排在后面——菜单默认选中第一项，排序直接决定回车插入了什么。
 */
internal fun filterMarkdownCommands(query: String): List<MarkdownCommand> {
    val keyword = query.trim().lowercase()
    if (keyword.isEmpty()) return markdownCommands
    data class Scored(val command: MarkdownCommand, val rank: Int)

    return markdownCommands.mapNotNull { command ->
        val label = command.label.lowercase()
        val rank = when {
            label.startsWith(keyword) -> 0
            command.id.startsWith(keyword) -> 1
            command.keywords.any { it.startsWith(keyword) } -> 2
            label.contains(keyword) -> 3
            command.id.contains(keyword) -> 4
            command.keywords.any { it.contains(keyword) } -> 5
            else -> return@mapNotNull null
        }
        Scored(command, rank)
    }.sortedWith(compareBy({ it.rank }, { it.command.id.length })).map { it.command }
}

/**
 * 选中命令：先把 `/{query}` 整段删掉，再把折叠光标放在 `/` 原来的位置，最后跑命令。
 *
 * 这样命令看到的正文是干净的（`**` 不会跟 `/粗` 粘在一起），插入的占位词也落在用户
 * 敲 `/` 的那一行。
 */
internal fun applySlashCommand(
    text: String,
    slashStart: Int,
    cursor: Int,
    command: MarkdownCommand,
): MarkdownEditResult {
    val hasToken = slashStart in 0 until text.length &&
        cursor > slashStart &&
        cursor <= text.length &&
        text[slashStart] == '/'
    if (!hasToken) {
        return command.apply(text, TextRange(cursor.coerceIn(0, text.length)))
    }
    val cleared = text.removeRange(slashStart, cursor)
    return command.apply(cleared, TextRange(slashStart))
}

/** 光标是否落在围栏代码块里（```/~~~）：代码块里不唤起命令菜单。 */
internal fun insideCodeFence(text: String, cursor: Int): Boolean {
    var open = false
    var index = 0
    val limit = cursor.coerceIn(0, text.length)
    while (index < limit) {
        val lineEnd = text.indexOf('\n', index).let { if (it < 0) text.length else it }
        val line = text.substring(index, lineEnd).trimStart()
        if (line.startsWith("```") || line.startsWith("~~~")) open = !open
        index = lineEnd + 1
    }
    return open
}

/**
 * 一条快捷键：`Ctrl/Cmd + key`（[shift] 为 true 时还要按住 Shift）。
 *
 * Windows/Linux 用 Ctrl、macOS 用 Cmd，[label] 统一写成 `Ctrl/Cmd+X`。
 */
internal data class MarkdownKeyBinding(
    val key: Key,
    val shift: Boolean,
    val label: String,
    val commandId: String,
)

internal val markdownKeyBindings: List<MarkdownKeyBinding> = listOf(
    MarkdownKeyBinding(Key.B, false, "Ctrl/Cmd+B", "bold"),
    MarkdownKeyBinding(Key.I, false, "Ctrl/Cmd+I", "italic"),
    MarkdownKeyBinding(Key.K, false, "Ctrl/Cmd+K", "link"),
    MarkdownKeyBinding(Key.X, true, "Ctrl/Cmd+Shift+X", "strike"),
    MarkdownKeyBinding(Key.C, true, "Ctrl/Cmd+Shift+C", "code"),
    MarkdownKeyBinding(Key.One, false, "Ctrl/Cmd+1", "h1"),
    MarkdownKeyBinding(Key.Two, false, "Ctrl/Cmd+2", "h2"),
    MarkdownKeyBinding(Key.Three, false, "Ctrl/Cmd+3", "h3"),
    MarkdownKeyBinding(Key.Zero, false, "Ctrl/Cmd+0", "paragraph"),
    MarkdownKeyBinding(Key.L, true, "Ctrl/Cmd+Shift+L", "bullet"),
    MarkdownKeyBinding(Key.O, true, "Ctrl/Cmd+Shift+O", "numbered"),
    MarkdownKeyBinding(Key.Q, true, "Ctrl/Cmd+Shift+Q", "quote"),
    MarkdownKeyBinding(Key.R, true, "Ctrl/Cmd+Shift+R", "rule"),
    MarkdownKeyBinding(Key.T, true, "Ctrl/Cmd+Shift+T", "table"),
)

internal fun markdownBindingFor(key: Key, shift: Boolean): MarkdownKeyBinding? =
    markdownKeyBindings.firstOrNull { it.key == key && it.shift == shift }

/** 「快捷键清单」里的一行；[owned] 为 false 的是页面级快捷键（不在编辑器里分发）。 */
internal data class ShortcutHint(val keys: String, val action: String, val owned: Boolean = true)

/** 编辑器自己处理的按键，和页面级快捷键一起显示在一张清单里。 */
internal val markdownShortcutHints: List<ShortcutHint> = buildList {
    for (binding in markdownKeyBindings) {
        val command = markdownCommand(binding.commandId) ?: continue
        add(ShortcutHint(binding.label, command.label))
    }
    add(
        ShortcutHint(
            "Ctrl/Cmd+S · Ctrl/Cmd+N · Ctrl/Cmd+E",
            "保存 · 新建笔记 · 编辑/预览切换（页面级）",
            owned = false,
        ),
    )
    add(ShortcutHint("Ctrl/Cmd+/", "打开/关闭这份清单（页面级）", owned = false))
    add(ShortcutHint("/", "打开命令菜单（行首或空格后，代码块里不触发）"))
    add(ShortcutHint("↑ ↓ · 回车", "在命令菜单里选择"))
    add(ShortcutHint("回车", "自动续写列表/引用，空项回车退出"))
    add(ShortcutHint("Esc", "关掉命令菜单或这份清单"))
}
