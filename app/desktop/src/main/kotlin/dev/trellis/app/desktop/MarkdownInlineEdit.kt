package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping

/**
 * 块内编辑态的「行内 WYSIWYG」投影（纯逻辑，可单测）。
 *
 * 块编辑器默认把一块的原文塞进 `BasicTextField`，于是编辑时看到的是裸 Markdown
 * （`别礼**战技**起手`）。这里按 marktext 的做法做投影：行内标记（`**`、`*`、`` ` ``、
 * `~~`、`[..](..)`、`$..$`）在光标不在该 token 内时隐藏、只留样式；光标进入 token 时
 * 把标记还原显示（可以逐字编辑）。标题/引用/列表的行首标记同理。
 *
 * 投影只负责「显示成什么 + 光标怎么映射」，样式与渲染在 [MarkdownBlockEditor] 里按
 * [InlineEditProjection.marks] / [InlineEditProjection.syntax] 套。
 */
internal enum class InlineMarkKind { Bold, Italic, Code, Strike, Link, Math, Concept }

/** 可见文本里的样式区间（坐标是投影后文本的偏移）。 */
internal data class InlineMarkRange(val start: Int, val end: Int, val kind: InlineMarkKind)

/** 原文里一个行内 token；[contentStart]..[contentEnd] 是内容，两侧是标记。 */
internal data class InlineTokenRange(
    val start: Int,
    val end: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val kind: InlineMarkKind,
)

/**
 * 一次投影的结果。
 *
 * - [text]：给 `BasicTextField` 显示的文本（隐藏了标记）
 * - [marks]：可见文本里的样式区间（加粗/斜体/等宽/删除线/链接/公式）
 * - [syntax]：可见文本里「被临时还原」的标记区间（画成暗色，提示这是语法）
 * - [tokens]：原文坐标的 token（退格/Delete 需要知道标记藏在哪）
 * - [headingLevel]：标题级别（1..6），不是标题为 null
 * - [isQuote]：引用块
 * - [hidden]：原文里被隐藏的区间（测试用）
 * - [mapping]：原文 offset ↔ 投影文本 offset
 */
internal data class InlineEditProjection(
    val text: String,
    val marks: List<InlineMarkRange>,
    val syntax: List<IntRange> = emptyList(),
    val tokens: List<InlineTokenRange> = emptyList(),
    val headingLevel: Int? = null,
    val isQuote: Boolean = false,
    /** 行首结构标记（标题的 `## ` / 引用的 `> `）的原文区间；列表没有。 */
    val prefix: IntRange? = null,
    val hidden: List<IntRange> = emptyList(),
    val mapping: OffsetMapping = identityOffsetMapping,
)

private val headingPrefixPattern = Regex("(#{1,6})(\\s+)")

private val identityOffsetMapping = object : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset
    override fun transformedToOriginal(offset: Int): Int = offset
}

/**
 * 原文 offset ↔ 投影文本 offset。
 *
 * 隐藏区内部的 offset 一律落到区段起点：这样光标从左往右经过被折叠的 `**` 时不会
 * 出现「坐标跳回去」；`transformedToOriginal` 只会落在可见字符上，所以编辑不会误删
 * 被藏起来的标记（退格/Delete 的边界处理见 [MarkdownBlockEditor] 的 handleKey）。
 */
internal class InlineEditMapping(
    private val rawToVisible: IntArray,
    private val visibleToRaw: IntArray,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int =
        rawToVisible[offset.coerceIn(0, rawToVisible.lastIndex)]

    override fun transformedToOriginal(offset: Int): Int =
        visibleToRaw[offset.coerceIn(0, visibleToRaw.lastIndex)]
}

/**
 * 把一块原文投影成「隐藏了标记的可见文本」。
 *
 * [selection] 用原文坐标；光标在某个 token 里时该 token 的标记保持可见（可编辑）。
 * 代码块/公式块/表格/AI 块不做行内投影，原样返回（内容本身是特殊语法，藏标记只会更乱）。
 */
internal fun projectInlineEdit(
    raw: String,
    selection: TextRange,
    kind: BlockKind,
): InlineEditProjection {
    if (kind != BlockKind.Paragraph && kind != BlockKind.Heading &&
        kind != BlockKind.ListItem && kind != BlockKind.Quote
    ) {
        return InlineEditProjection(raw, emptyList())
    }

    val hidden = mutableListOf<IntRange>()
    val syntax = mutableListOf<IntRange>()
    var replacements: Map<Int, Char>? = null
    var headingLevel: Int? = null
    var isQuote = false
    var prefix: IntRange? = null
    val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) raw.length else it }

    when (kind) {
        BlockKind.Heading -> {
            val match = headingPrefixPattern.find(raw, indent)
            if (match != null && match.range.first == indent) {
                headingLevel = match.groups[1]!!.value.length
                val end = match.range.last + 1
                prefix = indent..(end - 1)
                // 标题的 `#` 一直显示成暗色源码：编辑这一块时能看到它是几级标题（预览态仍渲染成大字）。
                syntax += indent..(end - 1)
            }
        }

        BlockKind.ListItem -> {
            val marker = raw.getOrNull(indent)
            val afterMarker = raw.getOrNull(indent + 1)
            val isTask = raw.getOrNull(indent + 2) == '['
            if ((marker == '-' || marker == '*' || marker == '+') &&
                afterMarker != null && afterMarker.isWhitespace() && !isTask
            ) {
                // 换成真圆点，看起来是列表而不是一个 `-` 字符；1 个字符换 1 个字符，长度不变。
                replacements = mapOf(indent to '•')
            }
        }

        BlockKind.Quote -> {
            if (raw.startsWith("> ", indent)) {
                isQuote = true
                val end = indent + 2
                prefix = indent..(end - 1)
                // 标题的 `#` 一直显示成暗色源码：编辑这一块时能看到它是几级标题（预览态仍渲染成大字）。
                syntax += indent..(end - 1)
            }
        }

        else -> Unit
    }

    val defs = mutableListOf<Pair<InlineTokenRange, List<InlineMarkKind>>>()
    for (match in inlinePattern.findAll(raw)) {
        val groups = match.groups
        val start = match.range.first
        val end = match.range.last + 1
        when {
            // 图片保持原样（图不是文本，折叠 URL 反而看不懂）。
            groups["imageAlt"] != null -> Unit

            groups["bolditalic"] != null -> {
                val content = groups["bolditalic"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Bold) to
                    listOf(InlineMarkKind.Bold, InlineMarkKind.Italic)
            }

            groups["bold"] != null -> {
                val content = groups["bold"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Bold) to
                    listOf(InlineMarkKind.Bold)
            }

            groups["code"] != null -> {
                val content = groups["code"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Code) to
                    listOf(InlineMarkKind.Code)
            }

            groups["italic"] != null -> {
                val content = groups["italic"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Italic) to
                    listOf(InlineMarkKind.Italic)
            }

            groups["linkLabel"] != null -> {
                val content = groups["linkLabel"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Link) to
                    listOf(InlineMarkKind.Link)
            }

            groups["strike"] != null -> {
                val content = groups["strike"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Strike) to
                    listOf(InlineMarkKind.Strike)
            }

            groups["math"] != null -> {
                val content = groups["math"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Math) to
                    listOf(InlineMarkKind.Math)
            }

            // `[[概念]]`：AI/人手动标的概念，编辑时和别的标记一样隐藏 `[[ ]]`、只留概念名。
            groups["concept"] != null -> {
                val content = groups["concept"]!!.range
                defs += InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Concept) to
                    listOf(InlineMarkKind.Concept)
            }
        }
    }

    val marks = mutableListOf<InlineMarkRange>()
    val tokens = mutableListOf<InlineTokenRange>()
    for ((token, markKinds) in defs) {
        tokens += token
        marks += markKinds.map { InlineMarkRange(token.contentStart, token.contentEnd, it) }

        // 光标在 token 里、或紧挨着 token（前后各一格）都把源码露出来：
        // 正好对应「光标在里面或紧挨 ` 就显示出 `」的交互习惯。
        val revealed = if (selection.collapsed) {
            selection.start in maxOf(0, token.start - 1)..token.end
        } else {
            selection.min < token.end && selection.max > token.start
        }
        val leftMarker = token.start until token.contentStart
        val rightMarker = token.contentEnd until token.end
        if (revealed) {
            if (!leftMarker.isEmpty()) syntax += leftMarker.first..leftMarker.last
            if (!rightMarker.isEmpty()) syntax += rightMarker.first..rightMarker.last
        } else {
            if (!leftMarker.isEmpty()) hidden += leftMarker.first..leftMarker.last
            if (!rightMarker.isEmpty()) hidden += rightMarker.first..rightMarker.last
        }
    }

    val isHidden = BooleanArray(raw.length)
    for (range in hidden) {
        for (index in range.first..range.last) {
            if (index in raw.indices) isHidden[index] = true
        }
    }

    val visible = StringBuilder(raw.length)
    val rawToVisible = IntArray(raw.length + 1)
    val visibleToRaw = IntArray(raw.length + 1)
    for (index in raw.indices) {
        rawToVisible[index] = visible.length
        if (isHidden[index]) continue
        visibleToRaw[visible.length] = index
        visible.append(replacements?.get(index) ?: raw[index])
    }
    rawToVisible[raw.length] = visible.length
    visibleToRaw[visible.length] = raw.length

    fun toVisible(range: IntRange): IntRange = rawToVisible[range.first]..(rawToVisible[range.last + 1] - 1)

    return InlineEditProjection(
        text = visible.toString(),
        marks = marks.map { InlineMarkRange(rawToVisible[it.start], rawToVisible[it.end], it.kind) }
            .filter { it.start < it.end },
        syntax = mergeRanges(syntax).map(::toVisible).filter { !it.isEmpty() },
        tokens = tokens,
        headingLevel = headingLevel,
        isQuote = isQuote,
        prefix = prefix,
        hidden = mergeRanges(hidden),
        mapping = InlineEditMapping(rawToVisible, visibleToRaw),
    )
}

/** 合并相邻/重叠区间，避免显示层出现一堆碎区间；输入是原文坐标。 */
private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
    val merged = mutableListOf<IntRange>()
    for (range in ranges.filter { !it.isEmpty() }.sortedBy { it.first }) {
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
    }
    return merged
}
