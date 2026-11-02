package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue

/**
 * 整篇正文的「行内 WYSIWYG」投影（纯逻辑，可单测）。
 *
 * 编辑态把**整篇正文**塞进一个 `BasicTextField`：一个文档一个编辑面，光标移动、跨行选区、
 * 全选、上下键这些编辑行为全部交给平台文本框（按块切分、每块一个输入框的老路子做不出这些）。
 * 这个文件负责把原文投影成「隐藏了标记的可见文本」：
 *
 * - 行内标记（`**`、`*`、`` ` ``、`~~`、`[..](..)`、`$..$`、`[[概念]]`）在光标不在 token
 *   里（也不紧挨着）时隐藏，只留样式；光标进去时标记还原、可以逐字编辑；
 * - 标题的 `## ` / 引用的 `> ` 在光标不在那一行时隐藏（整行按标题/引用样式显示）；
 * - 列表的 `- ` 换成 `• `（1 个字符换 1 个字符，长度不变，映射不用改）；
 * - 代码块、表格、`:::ai` 块、`$$` 公式块的内容原样保留，只做等宽/配色（它们的结构
 *   本身就是语法，折标记只会让内容看不懂）。
 *
 * 投影只负责「显示成什么 + 光标怎么映射」，样式与绘制在 [MarkdownDocumentEditor] 里套。
 */

/** 行内标记的种类；[MarkdownDocumentEditor] 按它挑样式。 */
internal enum class InlineMarkKind { Bold, Italic, Code, Strike, Link, Math, Concept, ConceptPlain }

/** 可见文本里的样式区间（坐标是投影后文本的偏移）。 */
internal data class InlineMarkRange(val start: Int, val end: Int, val kind: InlineMarkKind)

/**
 * 原文里一个行内 token；[contentStart]..[contentEnd] 是内容，两侧是标记。
 *
 * [innerHidden] 是内容里「只在编辑时出现、平时该藏起来」的部分（`[[原文=>概念名]]` 的
 * `=> 概念名`）——折叠时藏掉，光标进来时按语法色显示。
 */
internal data class InlineTokenRange(
    val start: Int,
    val end: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val kind: InlineMarkKind,
    val innerHidden: IntRange? = null,
)

/** 一行正文的类别（决定显示样式和回车行为）。 */
internal enum class DocumentLineKind {
    Paragraph,
    Heading,
    BulletItem,
    OrderedItem,
    Quote,
    CodeFence,
    CodeContent,
    Table,
    AiMarker,
    AiContent,
    MathFence,
    MathContent,
}

/** 扫描出来的一行；[end] 不含换行符。 */
internal data class DocumentLine(
    val start: Int,
    val end: Int,
    val kind: DocumentLineKind,
    val headingLevel: Int = 0,
    /** 行首结构标记（标题 `## `、引用 `> `）在原文里的区间；列表没有。 */
    val prefix: IntRange? = null,
    /** 列表圆点 `-`/`*`/`+` 在原文里的下标；任务列表为空。 */
    val bullet: Int? = null,
    val isTask: Boolean = false,
)

/**
 * 一次投影的结果。
 *
 * - [text]：给 `BasicTextField` 显示的文本（隐藏了标记）
 * - [marks]：可见文本里的样式区间（加粗/斜体/等宽/删除线/链接/公式/概念）
 * - [syntax]：可见文本里「临时还原」的标记区间（画成暗色，提示这是语法）
 * - [tokens]：原文坐标的 token（退格/Delete 需要知道标记藏在哪）
 * - [headings]：可见文本里的标题行区间 + 级别
 * - [quotes] / [codeLines] / [tables] / [aiLines] / [mathLines]：可见文本里的整行样式区间
 * - [chips]：可见文本里的行内代码内容区间（画圆角底）
 * - [hidden]：原文里被隐藏的区间（测试用）
 * - [caretKind]：光标所在行的类别（回车行为要按它分流）
 * - [mapping]：原文 offset ↔ 投影文本 offset
 */
internal data class DocumentProjection(
    val text: String,
    val marks: List<InlineMarkRange> = emptyList(),
    val syntax: List<IntRange> = emptyList(),
    val tokens: List<InlineTokenRange> = emptyList(),
    val headings: List<Pair<IntRange, Int>> = emptyList(),
    val quotes: List<IntRange> = emptyList(),
    val codeLines: List<IntRange> = emptyList(),
    val tables: List<IntRange> = emptyList(),
    val aiLines: List<IntRange> = emptyList(),
    val mathLines: List<IntRange> = emptyList(),
    val chips: List<IntRange> = emptyList(),
    val lines: List<DocumentLine> = emptyList(),
    val hidden: List<IntRange> = emptyList(),
    val caretKind: DocumentLineKind = DocumentLineKind.Paragraph,
    val mapping: OffsetMapping = identityOffsetMapping,
)

private val identityOffsetMapping = object : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset

    override fun transformedToOriginal(offset: Int): Int = offset
}

/**
 * 原文 offset ↔ 投影文本 offset。
 *
 * 隐藏区内部的 offset 一律落到区段起点：这样光标从左往右经过被折叠的 `**` 时不会出现
 * 「坐标跳回去」；`transformedToOriginal` 也只会落在可见字符上，所以不会误删标记
 * （边界处理见 [projectedBackspace] / [projectedDelete]）。
 */
internal class InlineOffsetMapping(
    private val rawToVisible: IntArray,
    private val visibleToRaw: IntArray,
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int =
        rawToVisible[offset.coerceIn(0, rawToVisible.lastIndex)]

    override fun transformedToOriginal(offset: Int): Int =
        visibleToRaw[offset.coerceIn(0, visibleToRaw.lastIndex)]
}

private val headingPrefixPattern = Regex("(#{1,6})(\\s+)")
private val bulletPrefixPattern = Regex("([-*+])(\\s+)")
private val orderedPrefixPattern = Regex("(\\d+[.)])(\\s+)")
private val quotePrefixPattern = Regex("(>)(\\s?)")

/** 这些行的内容不做行内标记折叠（结构本身是语法，藏起来只会让内容看不懂）。 */
private val rawContentKinds = setOf(
    DocumentLineKind.CodeFence,
    DocumentLineKind.CodeContent,
    DocumentLineKind.Table,
    DocumentLineKind.AiMarker,
    DocumentLineKind.AiContent,
    DocumentLineKind.MathFence,
    DocumentLineKind.MathContent,
)

/**
 * 把正文扫描成一行一行，标出类别和行首标记的位置。
 *
 * 围栏代码块/`:::ai`/`$$` 用状态机配对；没闭合就一直延续到文末（和预览的规则一致）。
 */
internal fun scanDocumentLines(body: String): List<DocumentLine> {
    val lines = mutableListOf<DocumentLine>()
    var lineStart = 0
    var inCode = false
    var inAi = false
    var inMath = false
    while (lineStart <= body.length) {
        val newline = body.indexOf('\n', lineStart)
        val lineEnd = if (newline < 0) body.length else newline
        val raw = body.substring(lineStart, lineEnd)
        val trimmed = raw.trimStart()
        val indent = raw.length - trimmed.length
        val line = when {
            inCode -> {
                if (trimmed.startsWith("```")) {
                    inCode = false
                    DocumentLine(lineStart, lineEnd, DocumentLineKind.CodeFence)
                } else {
                    DocumentLine(lineStart, lineEnd, DocumentLineKind.CodeContent)
                }
            }

            inAi -> {
                if (trimmed == ":::") {
                    inAi = false
                    DocumentLine(lineStart, lineEnd, DocumentLineKind.AiMarker)
                } else {
                    DocumentLine(lineStart, lineEnd, DocumentLineKind.AiContent)
                }
            }

            inMath -> {
                if (trimmed.startsWith("$$")) {
                    inMath = false
                    DocumentLine(lineStart, lineEnd, DocumentLineKind.MathFence)
                } else {
                    DocumentLine(lineStart, lineEnd, DocumentLineKind.MathContent)
                }
            }

            trimmed.startsWith("```") -> {
                inCode = true
                DocumentLine(lineStart, lineEnd, DocumentLineKind.CodeFence)
            }

            trimmed.startsWith(":::ai") -> {
                inAi = true
                DocumentLine(lineStart, lineEnd, DocumentLineKind.AiMarker)
            }

            trimmed.startsWith("$$") -> {
                inMath = true
                DocumentLine(lineStart, lineEnd, DocumentLineKind.MathFence)
            }

            trimmed.startsWith("|") -> DocumentLine(lineStart, lineEnd, DocumentLineKind.Table)

            else -> structuralLine(body, lineStart, lineEnd, raw, indent)
        }
        lines += line
        if (newline < 0) break
        lineStart = newline + 1
    }
    return lines
}

private fun structuralLine(
    body: String,
    lineStart: Int,
    lineEnd: Int,
    raw: String,
    indent: Int,
): DocumentLine {
    // 注意：prefix / bullet 一律存**正文里的绝对下标**（行首缩进也要算进去），
    // 投影层拿它直接去整个 body 里藏标记/换圆点；存相对下标会把第一行的字符换掉。
    val heading = headingPrefixPattern.find(raw, indent)
    if (heading != null && heading.range.first == indent) {
        val level = heading.groups[1]!!.value.length
        val prefixEnd = lineStart + indent + heading.groups[1]!!.value.length + heading.groups[2]!!.value.length - 1
        return DocumentLine(
            lineStart,
            lineEnd,
            DocumentLineKind.Heading,
            headingLevel = level,
            prefix = (lineStart + indent)..prefixEnd,
        )
    }
    val bullet = bulletPrefixPattern.find(raw, indent)
    if (bullet != null && bullet.range.first == indent) {
        val contentStart = indent + bullet.groups[1]!!.value.length + bullet.groups[2]!!.value.length
        val isTask = body.getOrNull(lineStart + contentStart) == '[' &&
            body.getOrNull(lineStart + contentStart + 2) == ']'
        return DocumentLine(
            lineStart,
            lineEnd,
            DocumentLineKind.BulletItem,
            bullet = if (isTask) null else lineStart + indent,
            isTask = isTask,
        )
    }
    val ordered = orderedPrefixPattern.find(raw, indent)
    if (ordered != null && ordered.range.first == indent) {
        return DocumentLine(lineStart, lineEnd, DocumentLineKind.OrderedItem)
    }
    val quote = quotePrefixPattern.find(raw, indent)
    if (quote != null && quote.range.first == indent) {
        val prefixEnd = lineStart + indent + quote.groups[1]!!.value.length + quote.groups[2]!!.value.length - 1
        return DocumentLine(
            lineStart,
            lineEnd,
            DocumentLineKind.Quote,
            prefix = (lineStart + indent)..prefixEnd,
        )
    }
    return DocumentLine(lineStart, lineEnd, DocumentLineKind.Paragraph)
}

/**
 * 把整篇正文投影成「隐藏了标记的可见文本」。
 *
 * [selection] 用原文坐标；标题/引用的行首标记按「光标是否在这一行」决定显示还是隐藏，
 * 行内 token 按「光标是否在 token 里或前后紧挨一格」决定。
 */
internal fun projectDocument(
    body: String,
    selection: TextRange,
    conceptNames: List<String> = emptyList(),
): DocumentProjection {
    val lines = scanDocumentLines(body)
    val caretIndex = lines.indexOfLast { it.start <= selection.min }.coerceAtLeast(0)
    val caretLine = lines.getOrNull(caretIndex)
    val caretKind = caretLine?.kind ?: DocumentLineKind.Paragraph

    val hiddenRanges = mutableListOf<IntRange>()
    val syntaxRanges = mutableListOf<IntRange>()
    val replacements = HashMap<Int, Char>()

    for (line in lines) {
        if (line.start >= line.end) continue
        val onCaretLine = line === caretLine
        val lineRange = line.start..(line.end - 1)
        when (line.kind) {
            DocumentLineKind.Heading, DocumentLineKind.Quote -> {
                val prefix = line.prefix ?: continue
                if (onCaretLine) syntaxRanges += prefix else hiddenRanges += prefix
            }

            DocumentLineKind.BulletItem -> {
                if (line.isTask) continue
                val bullet = line.bullet ?: continue
                if (onCaretLine) {
                    // 光标在这一行时显示原文的 `-`，方便直接改标记。
                    syntaxRanges += bullet..bullet
                } else {
                    replacements[bullet] = '•'
                }
            }

            DocumentLineKind.CodeFence,
            DocumentLineKind.AiMarker,
            DocumentLineKind.MathFence,
            -> syntaxRanges += lineRange

            else -> Unit
        }
    }

    val tokens = mutableListOf<InlineTokenRange>()
    val marks = mutableListOf<InlineMarkRange>()
    for (match in inlinePattern.findAll(body)) {
        val start = match.range.first
        val end = match.range.last + 1
        val line = lines.firstOrNull { start >= it.start && start < it.end } ?: continue
        if (line.kind in rawContentKinds) continue
        val groups = match.groups
        val kind: InlineMarkKind
        val content: IntRange
        var innerHidden: IntRange? = null
        when {
            // 图片保持原样（图不是文本，折叠 URL 反而看不懂）。
            groups["imageAlt"] != null -> continue

            groups["bolditalic"] != null -> {
                content = groups["bolditalic"]!!.range
                val token = InlineTokenRange(start, end, content.first, content.last + 1, InlineMarkKind.Bold)
                tokens += token
                marks += InlineMarkRange(token.contentStart, token.contentEnd, InlineMarkKind.Bold)
                marks += InlineMarkRange(token.contentStart, token.contentEnd, InlineMarkKind.Italic)
                applyTokenMarkers(token, selection, hiddenRanges, syntaxRanges)
                continue
            }

            groups["bold"] != null -> {
                kind = InlineMarkKind.Bold
                content = groups["bold"]!!.range
            }

            groups["code"] != null -> {
                kind = InlineMarkKind.Code
                content = groups["code"]!!.range
            }

            groups["italic"] != null -> {
                kind = InlineMarkKind.Italic
                content = groups["italic"]!!.range
            }

            groups["linkLabel"] != null -> {
                kind = InlineMarkKind.Link
                content = groups["linkLabel"]!!.range
            }

            groups["concept"] != null -> {
                val conceptRange = groups["concept"]!!.range
                kind = InlineMarkKind.Concept
                content = conceptRange
                val contentText = body.substring(conceptRange.first, conceptRange.last + 1)
                val displayLength = conceptAnnotationDisplayLength(contentText)
                if (displayLength in 0 until contentText.length) {
                    innerHidden = (conceptRange.first + displayLength)..conceptRange.last
                }
            }

            groups["strike"] != null -> {
                kind = InlineMarkKind.Strike
                content = groups["strike"]!!.range
            }

            groups["math"] != null -> {
                kind = InlineMarkKind.Math
                content = groups["math"]!!.range
            }

            else -> continue
        }
        val token = InlineTokenRange(start, end, content.first, content.last + 1, kind, innerHidden)
        tokens += token
        marks += InlineMarkRange(token.contentStart, token.contentEnd, kind)
        applyTokenMarkers(token, selection, hiddenRanges, syntaxRanges)
    }

    // 概念名/别名在正文里的普通出现：只染色、不加下划线（标注过的 `[[…]]` 才有下划线）。
    if (conceptNames.isNotEmpty()) {
        val annotatedContents = tokens
            .filter { it.kind == InlineMarkKind.Concept }
            .map { it.contentStart until it.contentEnd }
        val plainMarks = conceptHits(body, conceptNames).mapNotNull { hit ->
            val start = hit.first
            val end = hit.last + 1
            val line = lines.firstOrNull { start >= it.start && start < it.end } ?: return@mapNotNull null
            if (line.kind in rawContentKinds) return@mapNotNull null
            if (annotatedContents.any { start < it.last + 1 && end > it.first }) return@mapNotNull null
            InlineMarkRange(start, end, InlineMarkKind.ConceptPlain)
        }
        if (plainMarks.isNotEmpty()) marks.addAll(0, plainMarks)
    }

    val isHidden = BooleanArray(body.length)
    for (range in hiddenRanges) {
        for (index in range.first..range.last) {
            if (index in body.indices) isHidden[index] = true
        }
    }

    val visible = StringBuilder(body.length)
    val rawToVisible = IntArray(body.length + 1)
    val visibleToRaw = IntArray(body.length + 1)
    for (index in body.indices) {
        rawToVisible[index] = visible.length
        if (isHidden[index]) continue
        visibleToRaw[visible.length] = index
        visible.append(replacements[index] ?: body[index])
    }
    rawToVisible[body.length] = visible.length
    visibleToRaw[visible.length] = body.length

    fun toVisible(range: IntRange): IntRange? {
        val from = rawToVisible[range.first]
        val to = rawToVisible[range.last + 1]
        return if (to > from) from..(to - 1) else null
    }

    val headings = lines
        .filter { it.kind == DocumentLineKind.Heading && it.start < it.end }
        .mapNotNull { line -> toVisible(line.start..(line.end - 1))?.let { it to line.headingLevel } }
    val quotes = lines
        .filter { it.kind == DocumentLineKind.Quote && it.start < it.end }
        .mapNotNull { line -> toVisible(line.start..(line.end - 1)) }
    val codeLines = lines
        .filter { it.kind == DocumentLineKind.CodeContent && it.start < it.end }
        .mapNotNull { line -> toVisible(line.start..(line.end - 1)) }
    val tables = lines
        .filter { it.kind == DocumentLineKind.Table && it.start < it.end }
        .mapNotNull { line -> toVisible(line.start..(line.end - 1)) }
    val aiLines = lines
        .filter { it.kind == DocumentLineKind.AiContent && it.start < it.end }
        .mapNotNull { line -> toVisible(line.start..(line.end - 1)) }
    val mathLines = lines
        .filter { it.kind == DocumentLineKind.MathContent && it.start < it.end }
        .mapNotNull { line -> toVisible(line.start..(line.end - 1)) }

    val visibleMarks = marks
        .map { InlineMarkRange(rawToVisible[it.start], rawToVisible[it.end], it.kind) }
        .filter { it.start < it.end }
    val visibleChips = visibleMarks.filter { it.kind == InlineMarkKind.Code }
        .map { it.start..(it.end - 1) }
    val visibleSyntax = mergeRanges(syntaxRanges).mapNotNull(::toVisible)

    return DocumentProjection(
        text = visible.toString(),
        marks = visibleMarks,
        syntax = visibleSyntax,
        tokens = tokens,
        headings = headings,
        quotes = quotes,
        codeLines = codeLines,
        tables = tables,
        aiLines = aiLines,
        mathLines = mathLines,
        chips = visibleChips,
        lines = lines,
        hidden = mergeRanges(hiddenRanges),
        caretKind = caretKind,
        mapping = InlineOffsetMapping(rawToVisible, visibleToRaw),
    )
}

/** 光标在 token 里或紧挨着它（前后各一格）时露源码，否则把两侧标记藏起来。 */
private fun applyTokenMarkers(
    token: InlineTokenRange,
    selection: TextRange,
    hidden: MutableList<IntRange>,
    syntax: MutableList<IntRange>,
) {
    val revealed = if (selection.collapsed) {
        selection.start in maxOf(0, token.start - 1)..token.end
    } else {
        selection.min < token.end && selection.max > token.start
    }
    val left = token.start until token.contentStart
    val right = token.contentEnd until token.end
    val leftRange = if (left.isEmpty()) null else left.first..left.last
    val rightRange = if (right.isEmpty()) null else right.first..right.last
    if (revealed) {
        leftRange?.let(syntax::add)
        rightRange?.let(syntax::add)
        token.innerHidden?.let(syntax::add)
    } else {
        leftRange?.let(hidden::add)
        rightRange?.let(hidden::add)
        token.innerHidden?.let(hidden::add)
    }
}

/** 合并相邻/重叠区间，避免显示层出现一堆碎区间。 */
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

// ---- `[[` 自动补全（纯函数，方便单测；编辑器只负责弹菜单、把选择塞回去） ----

/** 候选项里代表 `=>` 的那一项：把左边原文映射到右边概念名（不是真的概念名）。 */
internal const val CONCEPT_COMPLETION_SEPARATOR: String = "=>"

/** 一次 `[[` 补全要补什么；[openStart] 是 `[[` 的起始下标。 */
internal data class ConceptCompletion(
    val openStart: Int,
    /** 当前正在补的名字片段在原文里的起点。 */
    val nameStart: Int,
    /** 已经输入的名字片段（空串表示刚敲完 `=>`）。 */
    val query: String,
    /** 已经敲过 `=>`，现在补的是右边的概念名。 */
    val afterSeparator: Boolean,
    val candidates: List<String>,
)

private const val CONCEPT_COMPLETION_LIMIT = 6
private val conceptCompletionSeparator = Regex("(?:=>|→)")

/**
 * 光标如果落在一条还没闭合的 `[[…]]` 里，给出候选；否则返回 null。
 *
 * 两条防打扰规则（用户定的）：没敲过 `=>` 时已输入字数必须 > 1；名字已经完整匹配到
 * 唯一候选时不再弹（否则打全了还挂着一个「补全」很烦）。
 */
internal fun conceptCompletionAt(
    text: String,
    cursor: Int,
    names: List<String>,
): ConceptCompletion? {
    if (cursor <= 0 || cursor > text.length) return null
    var open = text.lastIndexOf(CONCEPT_OPEN, cursor - 1)
    while (open >= 0) {
        val inner = text.substring(open + 2, cursor)
        if (!inner.contains(CONCEPT_CLOSE) && !inner.contains('\n')) break
        open = text.lastIndexOf(CONCEPT_OPEN, open - 1)
    }
    if (open < 0 || open + 2 > cursor) return null
    val inner = text.substring(open + 2, cursor)
    if (inner.contains(CONCEPT_CLOSE) || inner.contains('\n')) return null

    val separator = conceptCompletionSeparator.findAll(inner).lastOrNull()
    val afterSeparator = separator != null
    val queryPart = if (separator != null) {
        inner.substring(separator.range.last + 1)
    } else {
        inner
    }
    val query = queryPart.trim()
    if (!afterSeparator && query.length <= 1) return null
    val rawStart = open + 2 + (separator?.let { it.range.last + 1 } ?: 0)
    val nameStart = rawStart + (queryPart.length - queryPart.trimStart().length)

    val matches = names.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { conceptKey(it) }
        .filter { query.isEmpty() || it.contains(query, ignoreCase = true) }
        .sortedWith(
            compareByDescending<String> { it.startsWith(query, ignoreCase = true) }
                .thenBy { it.length }
                .thenBy { it },
        )
        .take(CONCEPT_COMPLETION_LIMIT)
        .toMutableList()
    if (!afterSeparator && matches.any { it.equals(query, ignoreCase = true) } && matches.size <= 1) {
        // 完全匹配：用户已经把名字打全了，别再弹一个只有自己的候选。
        return null
    }
    if (!afterSeparator) matches += CONCEPT_COMPLETION_SEPARATOR
    if (matches.isEmpty()) return null
    return ConceptCompletion(open, nameStart, query, afterSeparator, matches)
}

/**
 * 把补全选择写回文本。
 *
 * - 选 `=>`：只在光标处插 ` => `（左边的原文原样保留），光标停在 `=>` 后面继续补概念名；
 * - 选概念名：替换已输入的名字片段，并补上 `]]`（后面已经是 `]]` 就不重复补）。
 */
internal fun applyConceptCompletion(
    value: TextFieldValue,
    completion: ConceptCompletion,
    choice: String,
): TextFieldValue {
    val text = value.text
    val cursor = value.selection.max.coerceIn(0, text.length)
    val isSeparator = choice == CONCEPT_COMPLETION_SEPARATOR
    val alreadyClosed = text.startsWith(CONCEPT_CLOSE, cursor)
    val insert = when {
        isSeparator -> " " + CONCEPT_SEPARATOR + " "
        alreadyClosed -> choice
        else -> choice + CONCEPT_CLOSE
    }
    val start = if (isSeparator) cursor else completion.nameStart.coerceIn(0, cursor)
    val newText = text.substring(0, start) + insert + text.substring(cursor)
    return TextFieldValue(newText, TextRange(start + insert.length))
}

// ---- 回车行为（纯函数，方便单测；编辑器只负责把结果塞回 BasicTextField） ----

/** 列表/引用的行首标记；拆行时用来算「内容从哪开始」。 */
private val bulletPrefix = Regex("^(\\s*)([-*+])(\\s+)")
private val orderedPrefix = Regex("^(\\s*)(\\d+)([.)])(\\s+)")
private val quotePrefix = Regex("^(\\s*)(>)(\\s?)")

/**
 * 列表项/引用行从中间拆开时，新行要带什么前缀。
 *
 * 有序列表下一个数字 +1；任务列表 `- [ ]` 拆开就只是普通列表项。
 */
internal fun splitMarkerFor(body: String, line: DocumentLine): String? {
    val raw = body.substring(line.start, line.end)
    val indent = raw.takeWhile { it.isWhitespace() }
    return when (line.kind) {
        DocumentLineKind.BulletItem -> "$indent- "
        DocumentLineKind.OrderedItem -> {
            val match = orderedPrefix.find(raw) ?: return null
            "$indent${(match.groupValues[2].toIntOrNull() ?: 0) + 1}${match.groupValues[3]} "
        }

        DocumentLineKind.Quote -> "$indent> "
        else -> null
    }
}

/**
 * 回车：在光标处拆出「一个空行」，空行在 Markdown 里就是段落边界。
 *
 * 三种落点分开处理，否则总有一种「回车没反应」：
 *  - 段的中间 → 交界处补到两个换行，光标落在后半段开头（新段落里）；
 *  - 行的开头（光标前正好是换行）→ 在当前行上方留出一个**完整空段落**：
 *    交界处补到 `上段\n\n新段\n\n本行`，光标落在两个空行之间，打出来的字自成一整段；
 *  - 整篇开头 → 光标留在最前面，打出来的字就是新段落。
 * 光标前只有一个换行（软换行）时多补一个换行，保证新打的字不会和上一段粘成一段。
 */
internal fun breakParagraphAt(value: TextFieldValue): TextFieldValue {
    val text = value.text
    val selection = value.selection
    val before = text.substring(0, selection.min)
    val after = text.substring(selection.max)
    val atLineStart = before.isEmpty() || before.last() == '\n'
    if (atLineStart) {
        // 行首：在光标处补出一个「空段落」（上下各两个换行），光标停在这个空段落里。
        val singleBreak = before.isNotEmpty() && !before.endsWith("\n\n")
        val insertion = if (singleBreak) "\n\n\n" else "\n\n"
        val caret = before.length + if (singleBreak) 1 else 0
        return TextFieldValue(before + insertion + after, TextRange(caret))
    }
    val trailingNewlines = before.length - before.trimEnd('\n').length
    val leadingNewlines = after.length - after.trimStart('\n').length
    val insertion = if (trailingNewlines + leadingNewlines >= 2) {
        "\n"
    } else {
        "\n".repeat(2 - trailingNewlines - leadingNewlines)
    }
    return TextFieldValue(
        before + insertion + after,
        TextRange(before.length + insertion.length),
    )
}

/**
 * 列表项/引用行回车：按光标位置拆成两个同类行（marktext 的行为）。
 *
 * - 光标在内容里 → 把这一行从光标处拆成两个同类项（有序列表自动 +1）；
 * - 光标在行首标记里 → 在当前行**上面**新开一个空项，光标停在新项标记之后，
 *   有序列表顺手把当前行编号 +1，免得两项同号。
 */
internal fun splitListLineAt(value: TextFieldValue, lines: List<DocumentLine>): TextFieldValue? {
    val caret = value.selection.min
    val body = value.text
    val line = lines.firstOrNull { caret >= it.start && caret <= it.end } ?: return null
    val raw = body.substring(line.start, line.end)
    val marker = splitMarkerFor(body, line) ?: return null
    val prefixLength = when (line.kind) {
        DocumentLineKind.BulletItem -> bulletPrefix.find(raw)?.value?.length
        DocumentLineKind.OrderedItem -> orderedPrefix.find(raw)?.value?.length
        DocumentLineKind.Quote -> quotePrefix.find(raw)?.value?.length
        else -> null
    } ?: return null
    val contentStart = line.start + prefixLength
    if (caret >= contentStart) {
        val newText = body.substring(0, caret) + "\n" + marker + body.substring(caret)
        return TextFieldValue(newText, TextRange(caret + 1 + marker.length))
    }

    val head = body.substring(0, line.start)
    val tail = body.substring(line.end)
    val newItemMarker: String
    val currentLine: String
    if (line.kind == DocumentLineKind.OrderedItem) {
        val match = orderedPrefix.find(raw)
        if (match == null) {
            newItemMarker = marker
            currentLine = raw
        } else {
            val indent = match.groupValues[1]
            val number = match.groupValues[2].toIntOrNull() ?: 0
            val punctuation = match.groupValues[3]
            val gap = match.groupValues[4]
            newItemMarker = "$indent$number$punctuation$gap"
            currentLine = "$indent${number + 1}$punctuation$gap" + raw.substring(match.value.length)
        }
    } else {
        newItemMarker = marker
        currentLine = raw
    }
    val newText = head + newItemMarker + "\n" + currentLine + tail
    return TextFieldValue(newText, TextRange(head.length + newItemMarker.length))
}
