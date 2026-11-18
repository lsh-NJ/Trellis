package dev.trellis.app.desktop

/**
 * 结构化分块：把长笔记按「标题 1 > 标题 2 > 本段正文」切开。
 *
 * 两条规则（2026-11-19 用户定的）：
 * 1. **表格不分析**：Markdown 表格行（连续两行以上以 `|` 开头，围栏代码块里的不算）
 *    不进模型输入；表格里的字不抽概念、不补空位。
 * 2. **超长文章一段一段投**：正常按节切块仍超过 [MAX_EXTRACT_CHUNKS] 段时，改走「段落模式」——
 *    每个自然段一次调用，正文前重放它所在的完整标题链（`# 一 / ## 1.1`），
 *    这样再多内容也不会因为 8 段上限被截断。
 *
 * 每块的 [ExtractionChunk.text] 尽量是原文的连续片段；表格行会被剔除，
 * 所以提示词里的「所属章节」和槽位行号以 [ExtractionChunk.startLine]/[ExtractionChunk.endLine] 为准。
 */
internal data class ExtractionChunk(
    /** 原文片段（不含任何提示词前缀；表格行已剔除）。 */
    val text: String,
    /** 这一段所属的标题路径（从一级到当前节）；空表示正文开头或一级标题本身。 */
    val contextPath: List<String> = emptyList(),
    /** 是否从某一节的正文中间开始（上一条分块已经发过这一节的标题）。 */
    val continued: Boolean = false,
    /** 这一段第一行在整篇正文里的行号（0 开始）。 */
    val startLine: Int = 0,
    /** 这一段最后一行的行号（含）。 */
    val endLine: Int = 0,
    /** 笔记太长、超过段数上限，后面还有内容没送进来。 */
    val truncated: Boolean = false,
    /**
     * 段落模式重放的标题链（`# 一\n## 1.1`）。普通分块为空——正文里本来就带着标题行。
     * 单独放一个字段，是为了不改 [text]、让槽位行号仍然对得上原文。
     */
    val headingPrefix: String = "",
    /**
     * 这一块所属的完整章节路径（含当前节）。段落模式下每个自然段都带同一份路径，
     * 换段不换前缀；普通分块为空（一段里可能跨多节，交给调用方按行兜底）。
     */
    val sectionPath: List<String> = emptyList(),
    /** 是否来自「超长文章段落模式」（每段重放标题链）。 */
    val paragraph: Boolean = false,
)

/**
 * 把正文切成带标题路径的块。
 *
 * @param maxChars 单块上限，默认 [MAX_EXTRACT_BODY_CHARS]。
 * @param maxChunks 正常模式最多几块，默认 [MAX_EXTRACT_CHUNKS]；超了自动转段落模式。
 * @param maxParagraphChunks 段落模式最多几块，默认 [MAX_PARAGRAPH_CHUNKS]。
 */
internal fun extractionChunks(
    body: String,
    maxChars: Int = MAX_EXTRACT_BODY_CHARS,
    maxChunks: Int = MAX_EXTRACT_CHUNKS,
    maxParagraphChunks: Int = MAX_PARAGRAPH_CHUNKS,
): List<ExtractionChunk> {
    require(maxChars > 0) { "maxChars must be positive" }
    require(maxChunks > 0) { "maxChunks must be positive" }
    require(maxParagraphChunks > 0) { "maxParagraphChunks must be positive" }
    if (body.isEmpty()) return emptyList()
    val lines = body.split('\n')
    val lastLine = lines.lastIndex
    val tables = tableLineMask(lines)

    if (body.length <= maxChars) {
        val text = joinVisible(lines, tables, 0, lastLine)
        if (text.isBlank()) return emptyList()
        return listOf(ExtractionChunk(text = text, startLine = 0, endLine = lastLine))
    }

    val index = LineIndex(lines, tables)
    val headings = scanHeadingLines(body)
    val builder = SegmentBuilder(lines, tables, maxChars)
    if (headings.isEmpty()) {
        builder.range(0, lastLine, emptyList(), emptyList())
    } else {
        val nodes = buildSections(headings, lastLine)
        if (headings.first().lineIndex > 0) {
            builder.range(0, headings.first().lineIndex - 1, emptyList(), emptyList())
        }
        nodes.filter { it.parent == null }.forEach { emitSection(nodes, it.index, index, maxChars, builder) }
    }
    val chunks = builder.pack(maxChunks, lastLine)
    if (chunks.isEmpty()) return emptyList()
    if (chunks.last().truncated) {
        return paragraphChunks(body, lines, tables, headings, maxChars, maxParagraphChunks)
    }
    return chunks
}

/** 段落模式的上限：超长文章最多拆这么多段（再长就截断并在最后一块标明）。 */
internal const val MAX_PARAGRAPH_CHUNKS: Int = 1000

/**
 * 标记「表格行」：连续两行及以上、去掉缩进后以 `|` 开头；围栏代码块里的不算。
 * 单行以 `|` 开头多半是正文（或只有表头的残表），不剔除，避免误伤。
 */
internal fun tableLineMask(lines: List<String>): BooleanArray {
    val pipe = BooleanArray(lines.size)
    var inFence = false
    for (i in lines.indices) {
        val trimmed = lines[i].trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            continue
        }
        if (!inFence && trimmed.startsWith("|")) pipe[i] = true
    }
    val mask = BooleanArray(lines.size)
    var i = 0
    while (i < lines.size) {
        if (!pipe[i]) {
            i++
            continue
        }
        var j = i
        while (j < lines.size && pipe[j]) j++
        if (j - i >= 2) {
            for (k in i until j) mask[k] = true
        }
        i = j
    }
    return mask
}

private fun joinVisible(lines: List<String>, tables: BooleanArray, start: Int, end: Int): String {
    val text = StringBuilder()
    for (i in start..end) {
        if (i < 0 || i >= lines.size || tables[i]) continue
        if (text.isNotEmpty()) text.append('\n')
        text.append(lines[i])
    }
    return text.toString()
}



/** 标题行 + 它在文档里的完整路径（含自己）：给「按 mention 行号找所属章节」用。 */
internal data class SectionAnchor(val line: Int, val path: List<String>)

/** 把标题行压成路径锚点（跳级也归到最近祖先，和标题树一致）。 */
internal fun sectionAnchors(headings: List<HeadingLine>): List<SectionAnchor> {
    val stack = ArrayList<HeadingLine>()
    return headings.map { heading ->
        while (stack.isNotEmpty() && stack.last().level >= heading.level) {
            stack.removeAt(stack.lastIndex)
        }
        val path = stack.map { it.text } + heading.text
        stack += heading
        SectionAnchor(heading.lineIndex, path)
    }
}

/** 某一行所属的章节路径：它前面最近的一个标题（含自己）。 */
internal fun sectionAt(anchors: List<SectionAnchor>, line: Int): List<String> =
    anchors.lastOrNull { it.line <= line }?.path.orEmpty()

/**
 * 标题树 → 提示词里的缩进目录（`#` 数量表示层级）。
 *
 * 标题行很便宜，整棵树都给；只按行数/字符数兜底，避免病态笔记把正文挤掉。
 */
internal fun outlinePromptLines(
    roots: List<OutlineItem>,
    maxLines: Int = 80,
    maxChars: Int = 2000,
): List<String> {
    val result = ArrayList<String>()
    var chars = 0
    fun walk(nodes: List<OutlineItem>) {
        for (node in nodes) {
            if (result.size >= maxLines || chars >= maxChars) return
            val line = "  ".repeat((node.level - 1).coerceAtLeast(0)) +
                "#".repeat(node.level) + " " + node.text
            result += line
            chars += line.length + 1
            walk(node.children)
        }
    }
    walk(roots)
    return result
}

// ---------------------------------------------------------------------------
// 标题树
// ---------------------------------------------------------------------------

private class SectionNode(
    val index: Int,
    val level: Int,
    val title: String,
    val line: Int,
    val parent: Int?,
) {
    val children = mutableListOf<Int>()

    /** 自己这段正文的最后一行（下一个标题之前）。 */
    var ownEnd: Int = line

    /** 整棵子树的最后一行（下一个同级或更高级标题之前）。 */
    var end: Int = line

    /** 祖先标题（不含自己）。 */
    var path: List<String> = emptyList()

    /** 从一级到自己的完整标题链（Markdown 原文，例如 `# 一\n## 1.1`）。 */
    var headingPrefix: String = ""
}

private fun buildSections(headings: List<HeadingLine>, lastLine: Int): List<SectionNode> {
    val nodes = ArrayList<SectionNode>(headings.size)
    val stack = ArrayList<Int>()
    for (heading in headings) {
        while (stack.isNotEmpty() && nodes[stack.last()].level >= heading.level) {
            stack.removeAt(stack.lastIndex)
        }
        val parent = stack.lastOrNull()
        val node = SectionNode(nodes.size, heading.level, heading.text, heading.lineIndex, parent)
        nodes += node
        if (parent != null) nodes[parent].children += node.index
        stack += node.index
    }
    for (i in nodes.indices) {
        val node = nodes[i]
        node.ownEnd = if (i + 1 < nodes.size) nodes[i + 1].line - 1 else lastLine
        var next = i + 1
        while (next < nodes.size && nodes[next].level > node.level) next++
        node.end = if (next < nodes.size) nodes[next].line - 1 else lastLine
    }
    fun assignPath(index: Int, ancestors: List<String>, ancestorHeadings: String) {
        val node = nodes[index]
        node.path = ancestors
        val heading = "#".repeat(node.level) + " " + node.title
        node.headingPrefix = if (ancestorHeadings.isEmpty()) heading else ancestorHeadings + "\n" + heading
        val nextAncestors = ancestors + node.title
        node.children.forEach { assignPath(it, nextAncestors, node.headingPrefix) }
    }
    nodes.filter { it.parent == null }.forEach { assignPath(it.index, emptyList(), "") }
    return nodes
}

private fun emitSection(
    nodes: List<SectionNode>,
    index: Int,
    lines: LineIndex,
    maxChars: Int,
    builder: SegmentBuilder,
) {
    val node = nodes[index]
    if (lines.length(node.line, node.end) <= maxChars) {
        builder.range(node.line, node.end, node.path, node.path)
        return
    }
    // 整棵子树放不下：先发「标题 + 本节自己的正文」，再逐个子标题递归。
    // 只有标题、没有正文的中间节点不单独成段（否则会换来一条十几个字的空请求）：
    // 把它的标题行挂到下一段开头，既保住原文覆盖，又保住层级。
    if (node.ownEnd > node.line || node.children.isEmpty()) {
        builder.range(node.line, node.ownEnd, node.path, node.path + node.title)
    } else {
        builder.prependHeading(node.line, node.path)
    }
    node.children.forEach { emitSection(nodes, it, lines, maxChars, builder) }
}

// ---------------------------------------------------------------------------
// 段落模式（超长文章）
// ---------------------------------------------------------------------------

/**
 * 每个自然段一块，标题链重放在 [ExtractionChunk.headingPrefix] 里。
 *
 * 表格行直接跳过；空段不产生请求。超过 [maxChunks] 的部分不再送，最后一块标 truncated。
 */
private fun paragraphChunks(
    body: String,
    lines: List<String>,
    tables: BooleanArray,
    headings: List<HeadingLine>,
    maxChars: Int,
    maxChunks: Int,
): List<ExtractionChunk> {
    val chunks = ArrayList<ExtractionChunk>()
    var truncated = false
    val lastLine = lines.lastIndex

    fun add(text: String, start: Int, end: Int, prefix: String, path: List<String>) {
        val trimmed = text.trim('\n')
        if (trimmed.isBlank()) return
        // 分隔线（---）、空引用、只有标点的块不单独发请求。
        if (trimmed.count { it.isLetterOrDigit() } < 2) return
        if (chunks.size >= maxChunks) {
            truncated = true
            return
        }
        chunks += ExtractionChunk(
            text = trimmed,
            contextPath = path,
            continued = false,
            startLine = start,
            endLine = end,
            headingPrefix = prefix,
            sectionPath = path,
            paragraph = true,
        )
    }

    fun emitRange(start: Int, end: Int, prefix: String, path: List<String>) {
        if (start > end) return
        var blockStart = -1
        var blockEnd = -1
        fun flush() {
            if (blockStart < 0) return
            val text = joinVisible(lines, tables, blockStart, blockEnd)
            if (text.isBlank()) {
                blockStart = -1
                blockEnd = -1
                return
            }
            if (text.length <= maxChars) {
                add(text, blockStart, blockEnd, prefix, path)
            } else {
                splitTextBlocks(text, blockStart, blockEnd, prefix, path, maxChars, ::add)
            }
            blockStart = -1
            blockEnd = -1
        }
        for (i in start..end) {
            val blank = !tables[i] && lines[i].isBlank()
            if (blank) {
                flush()
                continue
            }
            if (tables[i]) continue
            if (blockStart < 0) blockStart = i
            blockEnd = i
        }
        flush()
    }

    if (headings.isEmpty()) {
        emitRange(0, lastLine, "", emptyList())
    } else {
        if (headings.first().lineIndex > 0) {
            emitRange(0, headings.first().lineIndex - 1, "", emptyList())
        }
        val nodes = buildSections(headings, lastLine)
        fun walk(index: Int) {
            val node = nodes[index]
            emitRange(node.line + 1, node.ownEnd, node.headingPrefix, node.path + node.title)
            node.children.forEach { walk(it) }
        }
        nodes.filter { it.parent == null }.forEach { walk(it.index) }
    }
    if (truncated && chunks.isNotEmpty()) {
        chunks[chunks.lastIndex] = chunks.last().copy(truncated = true)
    }
    return chunks
}

/** 单个自然段仍超过 [maxChars]：按行/字符切开，每块都带同一份标题链。 */
private fun splitTextBlocks(
    text: String,
    startLine: Int,
    endLine: Int,
    prefix: String,
    path: List<String>,
    maxChars: Int,
    add: (String, Int, Int, String, List<String>) -> Unit,
) {
    var cursor = 0
    var line = startLine
    while (cursor < text.length) {
        var end = (cursor + maxChars).coerceAtMost(text.length)
        if (end < text.length) {
            val newline = text.lastIndexOf('\n', end - 1)
            if (newline > cursor) end = newline
        }
        val piece = text.substring(cursor, end).trim('\n')
        val linesInPiece = text.substring(cursor, end).count { it == '\n' }
        if (piece.isNotBlank()) add(piece, line, line + linesInPiece, prefix, path)
        line += linesInPiece
        cursor = if (end < text.length && text[end] == '\n') end + 1 else end
    }
}

// ---------------------------------------------------------------------------
// 分段与打包
// ---------------------------------------------------------------------------

/** 覆盖整篇正文的连续片段；[newlineBefore] 表示和前一段之间要补一个换行（同一行的硬切不补）。 */
private class Segment(
    val text: String,
    val startLine: Int,
    val endLine: Int,
    val path: List<String>,
    val continued: Boolean,
    val newlineBefore: Boolean,
)

private class LineIndex(private val lines: List<String>, private val tables: BooleanArray) {
    private val prefix = IntArray(lines.size + 1)
    private val visible = IntArray(lines.size + 1)

    init {
        for (i in lines.indices) {
            val shown = !tables[i]
            prefix[i + 1] = prefix[i] + if (shown) lines[i].length + 1 else 0
            visible[i + 1] = visible[i] + if (shown) 1 else 0
        }
    }

    fun length(start: Int, end: Int): Int {
        if (end < start) return 0
        val safeEnd = end.coerceAtMost(lines.lastIndex)
        val count = visible[safeEnd + 1] - visible[start]
        if (count <= 0) return 0
        return prefix[safeEnd + 1] - prefix[start] - 1
    }
}

private class SegmentBuilder(
    private val lines: List<String>,
    private val tables: BooleanArray,
    private val maxChars: Int,
) {
    private val segments = ArrayList<Segment>()

    /** 只有标题、没有正文的中间节点：标题行先攒着，挂到下一段开头（不单独成段）。 */
    private data class PendingHeading(val text: String, val startLine: Int, val path: List<String>)

    private var pendingHeading: PendingHeading? = null

    fun prependHeading(lineIndex: Int, path: List<String>) {
        val existing = pendingHeading
        pendingHeading = if (existing == null) {
            PendingHeading(lines[lineIndex], lineIndex, path)
        } else {
            existing.copy(text = existing.text + "\n" + lines[lineIndex])
        }
    }

    private fun takePending(): PendingHeading? {
        val pending = pendingHeading
        pendingHeading = null
        return pending
    }

    private fun join(start: Int, end: Int): String = joinVisible(lines, tables, start, end)

    /**
     * 把 [start]..[end] 行切成不超过 [maxChars] 的片段。
     *
     * [firstPath] 给第一段用（通常是祖先标题）；[continuationPath] 给「同一节被切开」的后续段用。
     */
    fun range(start: Int, end: Int, firstPath: List<String>, continuationPath: List<String>) {
        var cursor = start
        var first = true
        val pending = takePending()
        while (cursor <= end) {
            if (tables[cursor]) {
                cursor++
                continue
            }
            val line = lines[cursor]
            if (line.length > maxChars) {
                var from = 0
                while (from < line.length) {
                    val to = (from + maxChars).coerceAtMost(line.length)
                    val withHeading = first && pending != null
                    val piece = line.substring(from, to)
                    segments += Segment(
                        text = if (withHeading) pending.text + "\n" + piece else piece,
                        startLine = if (withHeading) pending.startLine else cursor,
                        endLine = cursor,
                        path = when {
                            withHeading -> pending.path
                            first -> firstPath
                            else -> continuationPath
                        },
                        continued = !first && !withHeading,
                        newlineBefore = segments.isNotEmpty() && from == 0,
                    )
                    from = to
                    first = false
                }
                cursor++
                continue
            }
            var last = cursor
            var length = line.length
            var scan = cursor
            while (scan < end) {
                scan++
                if (tables[scan]) continue
                val next = length + 1 + lines[scan].length
                if (next > maxChars) break
                last = scan
                length = next
            }
            val withHeading = first && pending != null
            val piece = join(cursor, last)
            segments += Segment(
                text = if (withHeading) pending.text + "\n" + piece else piece,
                startLine = if (withHeading) pending.startLine else cursor,
                endLine = last,
                path = when {
                    withHeading -> pending.path
                    first -> firstPath
                    else -> continuationPath
                },
                continued = !first && !withHeading,
                newlineBefore = segments.isNotEmpty(),
            )
            cursor = last + 1
            first = false
        }
    }

    fun pack(maxChunks: Int, lastLine: Int): List<ExtractionChunk> {
        // 标题行攒到最后也没有正文可挂（整篇只有标题）：单独成段，别丢。
        takePending()?.let { pending ->
            segments += Segment(
                text = pending.text,
                startLine = pending.startLine,
                endLine = pending.startLine,
                path = pending.path,
                continued = false,
                newlineBefore = segments.isNotEmpty(),
            )
        }
        val chunks = ArrayList<ExtractionChunk>()
        var cursor = 0
        while (cursor < segments.size && chunks.size < maxChunks) {
            val first = segments[cursor]
            val text = StringBuilder()
            var length = 0
            var lastIndex = cursor
            var lastLineOfChunk = first.startLine
            while (lastIndex < segments.size) {
                val segment = segments[lastIndex]
                val connector = if (lastIndex == cursor) 0 else if (segment.newlineBefore) 1 else 0
                if (text.isNotEmpty() && length + connector + segment.text.length > maxChars) {
                    // 小段（只有标题、或标题 + 一小段说明）不单独占一次请求：
                    // 从下一段的行边界切一截过来，剩下的留给下一块（标记为续段）。
                    val remaining = maxChars - length - connector
                    if (length < SMALL_CHUNK_CHARS && remaining >= MIN_SPLIT_CHARS) {
                        val head = splitSegment(lastIndex, remaining)
                        if (head != null) {
                            if (connector == 1) text.append('\n')
                            text.append(head.text)
                            length += connector + head.text.length
                            lastLineOfChunk = head.endLine
                            continue
                        }
                    }
                    break
                }
                if (connector == 1) text.append('\n')
                text.append(segment.text)
                length += connector + segment.text.length
                lastLineOfChunk = segment.endLine
                lastIndex++
            }
            if (text.isNotBlank()) {
                chunks += ExtractionChunk(
                    text = text.toString(),
                    contextPath = first.path,
                    continued = first.continued,
                    startLine = first.startLine,
                    endLine = lastLineOfChunk,
                )
            }
            cursor = lastIndex
        }
        if (chunks.isNotEmpty() && cursor < segments.size) {
            chunks[chunks.lastIndex] = chunks.last().copy(truncated = true)
        }
        if (chunks.isNotEmpty() && chunks.last().endLine >= lastLine && cursor >= segments.size) {
            chunks[chunks.lastIndex] = chunks.last().copy(truncated = false)
        }
        return chunks
    }

    /**
     * 把 [index] 这一段从 [capacity] 附近切开：行边界优先，没有行边界就按字符切。
     * 切出来的前半段返回给当前块，后半段留在 [segments] 里当下一条（标记为续段）。
     */
    private fun splitSegment(index: Int, capacity: Int): Segment? {
        val segment = segments[index]
        if (capacity <= 0 || segment.text.length <= capacity) return null
        val limit = (capacity - 1).coerceAtMost(segment.text.lastIndex)
        val newline = segment.text.lastIndexOf('\n', limit)
        val atNewline = newline >= MIN_SPLIT_CHARS
        val cut = if (atNewline) newline else capacity
        if (cut <= 0 || cut >= segment.text.length) return null
        val headText = segment.text.substring(0, cut)
        val tailText = if (atNewline) segment.text.substring(cut + 1) else segment.text.substring(cut)
        if (tailText.isEmpty()) return null
        val headEndLine = segment.startLine + headText.count { it == '\n' }
        val tailStartLine = if (atNewline) headEndLine + 1 else headEndLine
        segments[index] = Segment(
            text = tailText,
            startLine = tailStartLine,
            endLine = segment.endLine,
            path = segment.path,
            continued = true,
            newlineBefore = false,
        )
        return Segment(
            text = headText,
            startLine = segment.startLine,
            endLine = headEndLine,
            path = segment.path,
            continued = segment.continued,
            newlineBefore = false,
        )
    }
}

/** 小于这个长度的块，宁可从下一段切一截过来，也不单独发一次请求。 */
private const val SMALL_CHUNK_CHARS: Int = 800

/** 往小块里补内容时，至少要有这么多剩余空间才切。 */
private const val MIN_SPLIT_CHARS: Int = 200
