package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Markdown 编辑器的文本变换。
 *
 * 全部是纯函数：输入「文本 + 选区」，输出「新文本 + 新选区」。
 * 这样斜杠命令、快捷键、回车续列表共用同一份逻辑，也能在单元测试里钉住
 * 「选区在中间时不能把字吃到标记里」这类事故。
 */
internal data class MarkdownEditResult(val text: String, val selection: TextRange)

private fun bounds(selection: TextRange): Pair<Int, Int> = selection.min to selection.max

private fun lineStartOf(text: String, index: Int): Int =
    if (index <= 0) 0 else text.lastIndexOf('\n', index - 1) + 1

private fun lineEndOf(text: String, index: Int): Int =
    text.indexOf('\n', index).let { if (it < 0) text.length else it }

/**
 * 行内包裹：粗体 `**` / 斜体 `*` / 行内代码 `` ` `` / 删除线 `~~`。
 *
 * - 有选区：包起来（已经包着就取消，已经选到标记外也识别）；
 * - 没选区：插入「标记 + 占位词 + 标记」，并选中占位词，接着打字就是替换它。
 */
internal fun toggleInlineMarkdown(
    text: String,
    selection: TextRange,
    marker: String,
    placeholder: String,
): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val before = text.substring(maxOf(0, start - marker.length), start)
    val after = text.substring(end, minOf(text.length, end + marker.length))

    // 光标正好在「空标记对」中间：再点一次就是关掉。
    if (start == end && before == marker && after == marker) {
        return MarkdownEditResult(
            text.removeRange(start - marker.length, end + marker.length),
            TextRange(start - marker.length),
        )
    }

    if (start == end) {
        val clipped = text.substring(0, start) + marker + placeholder + marker + text.substring(end)
        val selectedFrom = start + marker.length
        return MarkdownEditResult(clipped, TextRange(selectedFrom, selectedFrom + placeholder.length))
    }

    // 选区外已经包着标记：只脱掉两侧标记，保留选中的文字。
    if (before == marker && after == marker) {
        val inner = text.substring(start, end)
        return MarkdownEditResult(
            text.substring(0, start - marker.length) + inner + text.substring(end + marker.length),
            TextRange(start - marker.length, end - marker.length),
        )
    }
    // 选中的内容自带标记（用户把 `**加粗**` 一起选中了）：只脱掉里面这层。
    if (selectedHasMarker(text, start, end, marker)) {
        val inner = text.substring(start + marker.length, end - marker.length)
        return MarkdownEditResult(
            text.substring(0, start) + inner + text.substring(end),
            TextRange(start, start + inner.length),
        )
    }

    val wrapped = text.substring(0, start) + marker + text.substring(start, end) + marker + text.substring(end)
    return MarkdownEditResult(wrapped, TextRange(start + marker.length, end + marker.length))
}

private fun selectedHasMarker(text: String, start: Int, end: Int, marker: String): Boolean =
    end - start > marker.length * 2 &&
        text.regionMatches(start, marker, 0, marker.length) &&
        text.regionMatches(end - marker.length, marker, 0, marker.length)

/**
 * 按行加/去前缀（无序列表 `- `、引用 `> `）。
 *
 * 选区里每一行都已有前缀 = 整体取消；否则给没有前缀的行补上。
 * 光标折叠时只动当前行，光标跟着前缀一起挪。
 */
internal fun toggleLineMarkdown(text: String, selection: TextRange, prefix: String): MarkdownEditResult =
    applyToSelectedLines(text, selection) { lines ->
        if (lines.all { it.startsWith(prefix) }) {
            lines.map { it.removePrefix(prefix) }
        } else {
            lines.map { if (it.startsWith(prefix)) it else prefix + it }
        }
    }

/** 有序列表：整块应用时按 1、2、3 重新编号；取消时只去标记。 */
internal fun toggleNumberedList(text: String, selection: TextRange): MarkdownEditResult {
    val numbered = Regex("^\\d+[.)]\\s+")
    return applyToSelectedLines(text, selection) { lines ->
        if (lines.all { numbered.containsMatchIn(it) }) {
            lines.map { it.replaceFirst(numbered, "") }
        } else {
            lines.mapIndexed { index, line ->
                (index + 1).toString() + ". " + line.replaceFirst(numbered, "")
            }
        }
    }
}

/** 标题：给行加 `#`*level；整块都已是这一级 = 取消。 */
internal fun setHeadingLevel(text: String, selection: TextRange, level: Int): MarkdownEditResult {
    val prefix = "#".repeat(level.coerceIn(1, 6)) + " "
    val heading = Regex("^#{1,6}\\s+")
    return applyToSelectedLines(text, selection) { lines ->
        if (lines.all { it.startsWith(prefix) }) {
            lines.map { it.removePrefix(prefix) }
        } else {
            lines.map { line ->
                if (heading.containsMatchIn(line)) heading.replace(line, prefix) else prefix + line
            }
        }
    }
}

private fun applyToSelectedLines(
    text: String,
    selection: TextRange,
    transform: (List<String>) -> List<String>,
): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val from = lineStartOf(text, start)
    val to = lineEndOf(text, end)
    val oldBlock = text.substring(from, to)
    val oldLines = oldBlock.split('\n')
    val newLines = transform(oldLines)
    val newBlock = newLines.joinToString("\n")
    val newText = text.substring(0, from) + newBlock + text.substring(to)

    val collapsed = start == end
    val newSelection = if (collapsed) {
        // 折叠时只改了一行：光标按「行长度变化量」平移。
        val delta = newLines.first().length - oldLines.first().length
        val cursor = (start + delta).coerceIn(from, from + newLines.first().length)
        TextRange(cursor)
    } else {
        // 范围：变换后把整块选中，用户能接着再点一次取消。
        TextRange(from, from + newBlock.length)
    }
    return MarkdownEditResult(newText, newSelection)
}

/** 链接：有选区时给选中的词配空 URL；没选区时插占位词并选中它。 */
internal fun insertMarkdownLink(text: String, selection: TextRange): MarkdownEditResult {
    val (start, end) = bounds(selection)
    return if (start == end) {
        val snippet = "[链接文字](url)"
        val newText = text.substring(0, start) + snippet + text.substring(end)
        MarkdownEditResult(newText, TextRange(start + 1, start + 5))
    } else {
        val label = text.substring(start, end)
        val snippet = "[" + label + "](url)"
        val newText = text.substring(0, start) + snippet + text.substring(end)
        // 选中 url，直接打字替换。
        val urlStart = start + label.length + 3
        MarkdownEditResult(newText, TextRange(urlStart, urlStart + 3))
    }
}

/** 代码块：包住选区；没选区时插入空围栏并把光标放到中间。 */
internal fun insertCodeBlock(text: String, selection: TextRange): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val selected = text.substring(start, end)
    val lead = if (start == 0 || text[start - 1] == '\n') "" else "\n"
    val snippet = lead + "```\n" + selected + "\n```"
    val newText = text.substring(0, start) + snippet + text.substring(end)
    val contentStart = start + lead.length + 4
    val contentEnd = contentStart + selected.length
    return MarkdownEditResult(newText, TextRange(contentStart, contentEnd))
}

/** 图片：`![说明](url)`；没选区时插占位说明并选中它。 */
internal fun insertMarkdownImage(text: String, selection: TextRange): MarkdownEditResult {
    val (start, end) = bounds(selection)
    return if (start == end) {
        val snippet = "![图片说明](url)"
        val newText = text.substring(0, start) + snippet + text.substring(end)
        MarkdownEditResult(newText, TextRange(start + 2, start + 6))
    } else {
        val label = text.substring(start, end)
        val snippet = "![" + label + "](url)"
        val newText = text.substring(0, start) + snippet + text.substring(end)
        val urlStart = start + label.length + 4
        MarkdownEditResult(newText, TextRange(urlStart, urlStart + 3))
    }
}

/**
 * 分割线 `---`。
 *
 * 必须独占一行：`---` 粘在上一段末尾时，渲染器会把它当成段落的一部分（CommonMark 里
 * 那甚至会变成 setext 标题的下划线）。所以前后各补一个换行，光标停在横线后面。
 */
internal fun insertHorizontalRule(text: String, selection: TextRange): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val lead = if (start == 0 || text[start - 1] == '\n') "" else "\n"
    val trail = if (end >= text.length || text[end] == '\n') "" else "\n"
    val snippet = lead + "---" + trail
    val newText = text.substring(0, start) + snippet + text.substring(end)
    return MarkdownEditResult(newText, TextRange(start + lead.length + 3))
}

/** 任务清单：`- [ ] ` 前缀，整块切换；已勾选（`- [x] `）也算已有前缀。 */
internal fun toggleTaskList(text: String, selection: TextRange): MarkdownEditResult {
    val task = Regex("^(\\s*)- \\[[ xX]\\] ")
    return applyToSelectedLines(text, selection) { lines ->
        if (lines.all { task.containsMatchIn(it) }) {
            lines.map { it.replaceFirst(task, "\$1") }
        } else {
            lines.map { if (task.containsMatchIn(it)) it else "- [ ] " + it }
        }
    }
}

/** 正文：去掉行首 1–6 级标题标记。 */
internal fun clearHeading(text: String, selection: TextRange): MarkdownEditResult {
    val heading = Regex("^#{1,6}\\s+")
    return applyToSelectedLines(text, selection) { lines -> lines.map { it.replaceFirst(heading, "") } }
}

/** 表格：插一个 2×2 模板，选中第一格文字。 */
internal fun insertMarkdownTable(text: String, selection: TextRange): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val lead = if (start == 0 || text[start - 1] == '\n') "" else "\n"
    val snippet = lead + "| 列 1 | 列 2 |\n| --- | --- |\n|  |  |"
    val newText = text.substring(0, start) + snippet + text.substring(end)
    val cellStart = start + lead.length + 2
    return MarkdownEditResult(newText, TextRange(cellStart, cellStart + 3))
}

/**
 * 回车续列表。
 *
 * - `- 内容` 回车 → 下一行同标记；
 * - `- `（空项）回车 → 删掉标记退出列表；
 * - 引用 `> 内容` 同理。
 *
 * 不满足条件返回 null，交给 BasicTextField 正常换行。
 */
internal fun continueMarkdownList(value: TextFieldValue): TextFieldValue? {
    val (start, end) = bounds(value.selection)
    if (start != end) return null
    val cursor = start
    val text = value.text
    if (cursor < text.length && text[cursor] != '\n') return null

    val from = lineStartOf(text, cursor)
    val line = text.substring(from, cursor)

    val bullet = Regex("^(\\s*)([-*+])\\s+(.*)$").matchEntire(line)
    if (bullet != null) {
        val indent = bullet.groupValues[1]
        val marker = bullet.groupValues[2]
        val content = bullet.groupValues[3]
        return if (content.isBlank()) {
            TextFieldValue(text.removeRange(from, cursor), TextRange(from))
        } else {
            insertAtCursor(text, cursor, "\n" + indent + marker + " ")
        }
    }

    val ordered = Regex("^(\\s*)(\\d+)([.)])\\s+(.*)$").matchEntire(line)
    if (ordered != null) {
        val indent = ordered.groupValues[1]
        val next = (ordered.groupValues[2].toIntOrNull() ?: 0) + 1
        val dot = ordered.groupValues[3]
        val content = ordered.groupValues[4]
        return if (content.isBlank()) {
            TextFieldValue(text.removeRange(from, cursor), TextRange(from))
        } else {
            insertAtCursor(text, cursor, "\n" + indent + next + dot + " ")
        }
    }

    val quote = Regex("^(\\s*)>\\s+(.*)$").matchEntire(line)
    if (quote != null) {
        val indent = quote.groupValues[1]
        val content = quote.groupValues[2]
        return if (content.isBlank()) {
            TextFieldValue(text.removeRange(from, cursor), TextRange(from))
        } else {
            insertAtCursor(text, cursor, "\n" + indent + "> ")
        }
    }

    return null
}

private fun insertAtCursor(text: String, cursor: Int, insertion: String): TextFieldValue {
    val newText = text.substring(0, cursor) + insertion + text.substring(cursor)
    return TextFieldValue(newText, TextRange(cursor + insertion.length))
}

/** 缩进单位：列表/普通行 2 空格，代码块 4 空格。 */
internal const val LIST_INDENT: String = "  "
internal const val CODE_INDENT: String = "    "

/**
 * Tab：给选中行的行首加一个 [unit]。
 *
 * 只动行首，行中内容和列表编号原样保留；折叠光标时只处理当前行，光标跟着整行一起右移，
 * 相对行首的偏移不变。
 *
 * [indentEmptyLines] 决定空行加不加：正文/列表里不加（免得留下看不见的空白、删起来也烦），
 * 代码块里要加——空行按 Tab 补 4 空格是代码编辑器的常态，不补就成了「按了没反应」。
 */
internal fun indentMarkdownLines(
    text: String,
    selection: TextRange,
    unit: String,
    indentEmptyLines: Boolean = false,
): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val from = lineStartOf(text, start)
    val to = selectedLinesEnd(text, start, end)
    val lines = text.substring(from, to).split('\n')
    val newBlock = lines.joinToString("\n") { if (it.isEmpty() && !indentEmptyLines) it else unit + it }
    val newText = text.substring(0, from) + newBlock + text.substring(to)
    return if (start == end) {
        // 折叠时只碰了光标所在行：空行按口径跳过，否则光标整体右移一个 unit。
        val cursor = when {
            lines.first().isEmpty() && !indentEmptyLines -> start
            else -> start + unit.length
        }
        MarkdownEditResult(newText, TextRange(cursor))
    } else {
        // 选区：行首插入了 unit，返回的选区仍盖住原来那几行。
        MarkdownEditResult(newText, TextRange(from, from + newBlock.length))
    }
}

/**
 * 选区覆盖到哪一行的行尾。
 *
 * 选区的 [end] 经常正好停在**下一行行首**（Shift+↓、鼠标从下一行行首往回拖都是这个形状）：
 * 这时上一行的换行符在 [end] - 1 上，要按上一行结尾算，否则会多改一行——多缩进一行还能看出来，
 * 多反缩进一行就是「删了没选中的缩进」。
 */
private fun selectedLinesEnd(text: String, start: Int, end: Int): Int =
    if (end > start && end > 0 && text[end - 1] == '\n') lineEndOf(text, end - 1) else lineEndOf(text, end)

/**
 * Shift+Tab：每行最多删掉行首一个 [unit]。
 *
 * 行首是 Tab 就删一个 Tab，否则删掉行首最多 unit.length 个空格；不足一个单位就删已有的，
 * 删不到正文。折叠光标时若光标在行首空白之后则跟着前移，但不会落进空白里。
 */
internal fun outdentMarkdownLines(text: String, selection: TextRange, unit: String): MarkdownEditResult {
    val (start, end) = bounds(selection)
    val from = lineStartOf(text, start)
    val to = selectedLinesEnd(text, start, end)
    val lines = text.substring(from, to).split('\n')
    val cuts = lines.map { leadingIndentLength(it, unit) }
    val newBlock = lines.mapIndexed { index, line -> line.substring(cuts[index]) }.joinToString("\n")
    val newText = text.substring(0, from) + newBlock + text.substring(to)
    return if (start == end) {
        // 折叠时只改当前行：光标跟着前移，但不许越过行首。
        MarkdownEditResult(newText, TextRange((start - cuts.first()).coerceAtLeast(from)))
    } else {
        MarkdownEditResult(newText, TextRange(from, from + newBlock.length))
    }
}

/** 行首要删掉几个字符：Tab 算一个单位，其余最多删 unit.length 个空格。 */
private fun leadingIndentLength(line: String, unit: String): Int {
    if (line.isNotEmpty() && line[0] == '\t') return 1
    var spaces = 0
    while (spaces < line.length && spaces < unit.length && line[spaces] == ' ') spaces++
    return spaces
}
