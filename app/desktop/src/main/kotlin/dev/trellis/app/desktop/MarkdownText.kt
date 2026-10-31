package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ui.LocalExtraColors

/**
 * 会话里的 Markdown 渲染。
 *
 * agent 的回答天然带结构（小标题、清单、代码块、表格），以前用 [Text] 原样输出，
 * 用户看到的是满屏 `**加粗**` 和 `- 列表`。这里只做「轻量 Markdown」：
 *
 *  - 支持：标题、无序/有序列表、引用、围栏代码块、`行内代码`、**加粗**、*斜体*、[链接](url)；
 *  - 不支持：HTML、图片、脚注、嵌套复杂语法 —— 聊天记录不需要，也不该为它引入一整套解析器；
 *  - 解析失败绝不抛异常：认不出的就当普通段落显示，**宁可少一点样式，也不要吞掉内容**。
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String, val depth: Int) : MdBlock
    data class Numbered(val number: String, val text: String, val depth: Int) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    /** AI 写的块级内容（`:::ai` … `:::`）：预览渲染成绿色大引号，每行可点 × 删除。 */
    data class AiBlock(val items: List<String>) : MdBlock
}

private val bulletPattern = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val numberedPattern = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val headingPattern = Regex("^(#{1,6})\\s+(.*)$")

/** 表格的对齐行：`---` / `:---` / `---:` / `:---:`，只用来标记「这是表头分隔」。 */
private val separatorCell = Regex("^:?-{2,}:?$")


/** 把整段 Markdown 切成块；行内样式留给 [inlineMarkdown]。 */
internal fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var codeLanguage = ""
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.setLength(0)
        }
    }

    val lines = source.replace("\r\n", "\n").split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index].trimEnd()

        // 表格：连续的 `| a | b |` 行。分隔行（|---|）只用于对齐，不画出来。
        if (!inCode && line.trimStart().startsWith("|") && line.count { it == '|' } >= 2) {
            flushParagraph()
            val tableLines = mutableListOf<String>()
            while (index < lines.size) {
                val next = lines[index].trimEnd()
                if (!next.trimStart().startsWith("|")) break
                tableLines += next
                index++
            }
            val parsed = tableLines
                .map { row -> row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
                .filter { row -> row.any { it.isNotEmpty() } }
            if (parsed.size >= 2 && parsed[1].all { cell -> separatorCell.matches(cell) }) {
                blocks += MdBlock.Table(parsed.first(), parsed.drop(2))
            } else if (parsed.isNotEmpty()) {
                blocks += MdBlock.Table(parsed.first(), parsed.drop(1))
            }
            continue
        }

        if (inCode) {
            if (line.trimStart().startsWith("```")) {
                blocks += MdBlock.Code(codeLanguage, code.toString().trimEnd())
                code.setLength(0)
                codeLanguage = ""
                inCode = false
            } else {
                code.appendLine(line)
            }
            index++
            continue
        }

        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            inCode = true
            codeLanguage = line.trim().removePrefix("```").trim()
            index++
            continue
        }

        // AI 写的块：`:::ai` 到 `:::` 之间的行原样收进来，不按 Markdown 再拆一遍。
        if (line.trim() == AI_BLOCK_START) {
            flushParagraph()
            val items = mutableListOf<String>()
            index++
            while (index < lines.size) {
                val next = lines[index].trimEnd()
                if (next.trim() == AI_BLOCK_END) {
                    index++
                    break
                }
                items += next.trim().removePrefix("- ").removePrefix("* ").trim()
                index++
            }
            blocks += MdBlock.AiBlock(items.filter { it.isNotEmpty() })
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            index++
            continue
        }

        val heading = headingPattern.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            index++
            continue
        }

        val bullet = bulletPattern.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            blocks += MdBlock.Bullet(bullet.groupValues[2].trim(), indentDepth(bullet.groupValues[1]))
            index++
            continue
        }

        val numbered = numberedPattern.matchEntire(line)
        if (numbered != null) {
            flushParagraph()
            blocks += MdBlock.Numbered(numbered.groupValues[2], numbered.groupValues[3].trim(), indentDepth(numbered.groupValues[1]))
            index++
            continue
        }

        if (line.trimStart().startsWith(">")) {
            flushParagraph()
            blocks += MdBlock.Quote(line.trimStart().removePrefix(">").trim())
            index++
            continue
        }

        // 普通段落：连续行合并成一段（Markdown 的软换行本来就是空格）。
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(line.trim())
        index++
    }

    // 围栏没闭合：把攒下的内容当普通段落，别把答案吞掉。
    if (inCode && code.isNotEmpty()) blocks += MdBlock.Code(codeLanguage, code.toString().trimEnd())
    flushParagraph()
    return blocks
}

private fun indentDepth(prefix: String): Int = (prefix.length / 2).coerceIn(0, 3)

private val inlinePattern = Regex(
    // 强调符号必须贴着文字：`a * b * c` 里的星号是普通字符，不是斜体
    // （CommonMark 的「强调不能以空白开头/结尾」规则，简化版）。
    "\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*" +   // **粗体**
        "|`([^`]+?)`" +                     // `行内代码`
        "|\\*(?!\\s)(.+?)(?<!\\s)\\*" +     // *斜体*
        "|\\[(.+?)]\\((.+?)\\)" +           // [文字](链接)
        "|~~(?!\\s)(.+?)(?<!\\s)~~",        // ~~删除线~~
)

/** 行内 AI 内容（`{{…}}`）在原文里的位置；[end] 是闭括号之后的偏移。 */
internal data class AiInlineSpan(val start: Int, val end: Int, val content: String)

/** 找出一段文本里所有 `{{…}}`；未闭合的 `{{` 当普通字符，不吞内容。 */
internal fun aiInlineSpans(text: String): List<AiInlineSpan> {
    val spans = mutableListOf<AiInlineSpan>()
    var cursor = 0
    while (cursor < text.length) {
        val open = text.indexOf(AI_INLINE_OPEN, cursor)
        if (open < 0) break
        val close = text.indexOf(AI_INLINE_CLOSE, open + AI_INLINE_OPEN.length)
        if (close < 0) break
        val content = text.substring(open + AI_INLINE_OPEN.length, close)
        if (content.isNotBlank()) {
            spans += AiInlineSpan(open, close + AI_INLINE_CLOSE.length, content)
            cursor = close + AI_INLINE_CLOSE.length
        } else {
            cursor = open + AI_INLINE_OPEN.length
        }
    }
    return spans
}

/** 预览用的 AI 绿色；和 `ExtraColors.accentText` 的默认值一致，调用时会被主题覆盖。 */
internal val MarkdownAiGreen: Color = Color(0xFF4F6A12)

/** AI 内容的大引号字号：正文约 15–16sp，这里按约定放大到约 2 倍（一定比普通引号大）。 */
private val AiQuoteFontSize = 26.sp

/** 概念命中的绿色强调样式。 */
private fun conceptStyle(conceptGreen: Color): SpanStyle =
    SpanStyle(color = conceptGreen, fontWeight = FontWeight.Medium)

/**
 * 在一段文本的 [from, to) 区间里应用行内样式与概念高亮。
 *
 * [conceptHits] 用整段文本的绝对偏移，所以这里切区间时命中样式不会错位。
 */
private fun AnnotatedString.Builder.appendStyledRange(
    text: String,
    from: Int,
    to: Int,
    conceptHits: List<ConceptHit>,
    conceptGreen: Color,
) {
    if (to <= from) return
    var cursor = from
    fun appendPlain(a: Int, b: Int) {
        if (b <= a) return
        var position = a
        for (hit in conceptHits) {
            if (hit.last < position || hit.first >= b) continue
            val start = maxOf(hit.first, position)
            val end = minOf(hit.last + 1, b)
            if (start > position) append(text.substring(position, start))
            withStyle(conceptStyle(conceptGreen)) { append(text.substring(start, end)) }
            position = end
        }
        if (b > position) append(text.substring(position, b))
    }
    val segment = text.substring(from, to)
    for (match in inlinePattern.findAll(segment)) {
        val matchStart = from + match.range.first
        val matchEnd = from + match.range.last + 1
        appendPlain(cursor, matchStart)
        val bold = match.groups[1]
        val code = match.groups[2]
        val italic = match.groups[3]
        val linkLabel = match.groups[4]
        val linkUrl = match.groups[5]
        val strike = match.groups[6]
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendPlain(from + bold.range.first, from + bold.range.last + 1)
            }
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                appendPlain(from + code.range.first, from + code.range.last + 1)
            }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendPlain(from + italic.range.first, from + italic.range.last + 1)
            }
            linkLabel != null && linkUrl != null -> {
                withStyle(SpanStyle(color = ColorLink, fontWeight = FontWeight.Medium)) {
                    appendPlain(from + linkLabel.range.first, from + linkLabel.range.last + 1)
                }
                append(" (" + linkUrl.value + ")")
            }
            strike != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendPlain(from + strike.range.first, from + strike.range.last + 1)
            }
        }
        cursor = matchEnd
    }
    appendPlain(cursor, to)
}

/**
 * 行内渲染：Markdown 行内样式 + 概念绿色高亮 + AI 内容的大绿引号（`{{…}}`）。
 *
 * 默认参数（聊天页用的那套）不改变旧行为：没有概念、没有 `{{…}}` 时输出与以前一致。
 */
internal fun inlineMarkdown(
    text: String,
    conceptNames: List<String> = emptyList(),
    aiGreen: Color = MarkdownAiGreen,
    conceptGreen: Color = MarkdownAiGreen,
): AnnotatedString = buildAnnotatedString {
    val hits = conceptHits(text, conceptNames)
    var cursor = 0
    for (span in aiInlineSpans(text)) {
        appendStyledRange(text, cursor, span.start, hits, conceptGreen)
        withStyle(SpanStyle(color = aiGreen, fontWeight = FontWeight.Bold, fontSize = AiQuoteFontSize)) {
            append("\u201C")
        }
        appendStyledRange(
            text,
            span.start + AI_INLINE_OPEN.length,
            span.end - AI_INLINE_CLOSE.length,
            hits,
            conceptGreen,
        )
        withStyle(SpanStyle(color = aiGreen, fontWeight = FontWeight.Bold, fontSize = AiQuoteFontSize)) {
            append("\u201D")
        }
        cursor = span.end
    }
    appendStyledRange(text, cursor, text.length, hits, conceptGreen)
}

/** 链接颜色在这里硬编码会绕开主题；用「主色文本」的近似值，暗色下也读得清。 */
private val ColorLink = Color(0xFF2E5AAC)

@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    bodyStyle: androidx.compose.ui.text.TextStyle? = null,
    conceptNames: List<String> = emptyList(),
    onDeleteAiBlockLine: ((String) -> Unit)? = null,
) {
    val paragraphStyle = bodyStyle ?: MaterialTheme.typography.bodyLarge
    val extra = LocalExtraColors.current
    val aiGreen = extra.accentText
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    inlineMarkdown(block.text, conceptNames, aiGreen, aiGreen),
                    style = paragraphStyle,
                )
                is MdBlock.Heading -> Text(
                    inlineMarkdown(block.text, conceptNames, aiGreen, aiGreen),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Bullet -> MarkdownBullet(
                    block.depth,
                    "•",
                    block.text,
                    paragraphStyle,
                    conceptNames,
                    aiGreen,
                )
                is MdBlock.Numbered -> MarkdownBullet(
                    block.depth,
                    block.number + ".",
                    block.text,
                    paragraphStyle,
                    conceptNames,
                    aiGreen,
                )
                is MdBlock.Code -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        block.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                is MdBlock.Quote -> Row(
                    Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        inlineMarkdown(block.text, conceptNames, aiGreen, aiGreen),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MdBlock.Table -> MarkdownTable(block, conceptNames, aiGreen)
                is MdBlock.AiBlock -> AiBlockView(
                    items = block.items,
                    bodyStyle = paragraphStyle,
                    aiGreen = aiGreen,
                    containerColor = extra.successContainer,
                    conceptNames = conceptNames,
                    onDeleteLine = onDeleteAiBlockLine,
                )
            }
        }
    }
}

/**
 * AI 写的块：一对左上/右下的大绿引号框住内容，和正文里普通的“引号”在颜色、字号上都不同；
 * 每条内容右边一个 ×，由调用方决定删掉后怎么写回正文。
 */
@Composable
private fun AiBlockView(
    items: List<String>,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    aiGreen: Color,
    containerColor: Color,
    conceptNames: List<String>,
    onDeleteLine: ((String) -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(containerColor.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            "\u201C",
            style = bodyStyle.copy(fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
            color = aiGreen,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            "\u201D",
            style = bodyStyle.copy(fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
            color = aiGreen,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        Column(
            Modifier.padding(start = 26.dp, end = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("AI 补充", style = MaterialTheme.typography.labelSmall, color = aiGreen)
            for (item in items) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        inlineMarkdown(item, conceptNames, aiGreen, aiGreen),
                        style = bodyStyle,
                        modifier = Modifier.weight(1f),
                    )
                    if (onDeleteLine != null) {
                        Text(
                            "×",
                            style = bodyStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .clickable { onDeleteLine(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTable(table: MdBlock.Table, conceptNames: List<String>, aiGreen: Color) {
    // 列数取表头与数据行里最多的那个；超过 6 列就不再收（聊天里再宽也读不了）。
    val columns = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { row -> row.size } ?: 0,
    ).coerceIn(1, 6)
    fun cells(row: List<String>): List<String> = List(columns) { column -> row.getOrElse(column) { "" } }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        MarkdownTableRow(cells(table.header), header = true, conceptNames, aiGreen)
        for (row in table.rows) MarkdownTableRow(cells(row), header = false, conceptNames, aiGreen)
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, header: Boolean, conceptNames: List<String>, aiGreen: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for (cell in cells) {
            Box(Modifier.weight(1f)) {
                Text(
                    inlineMarkdown(cell, conceptNames, aiGreen, aiGreen),
                    style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                    color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        thickness = 1.dp,
    )
}

@Composable
private fun MarkdownBullet(
    depth: Int,
    marker: String,
    text: String,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    conceptNames: List<String>,
    aiGreen: Color,
) {
    Row(Modifier.padding(start = (depth * 14).dp)) {
        Text(
            marker,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp),
        )
        Text(
            inlineMarkdown(text, conceptNames, aiGreen, aiGreen),
            style = bodyStyle,
        )
    }
}
