package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ui.LocalExtraColors

/**
 * 会话里的 Markdown 渲染。
 *
 * agent 的回答天然带结构（小标题、清单、代码块、表格），以前用 [Text] 原样输出，
 * 用户看到的是满屏 `**加粗**` 和 `- 列表`。这里只做「轻量 Markdown」：
 *
 *  - 支持：标题、无序/有序/任务列表、引用、围栏代码块、`行内代码`、**加粗**、*斜体*、
 *    ***粗斜体***、[链接](url)、![图片](url)、~~删除线~~、表格、分割线（`---`/`***`/`___`）；
 *  - 不支持：HTML、脚注、嵌套复杂语法 —— 聊天记录和笔记预览不需要，也不该为它引入一整套解析器；
 *  - 解析失败绝不抛异常：认不出的就当普通段落显示，**宁可少一点样式，也不要吞掉内容**。
 */
internal sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    /** [checked] 非空 = 任务清单：null 是普通列表项，true/false 是勾没勾。 */
    data class Bullet(val text: String, val depth: Int, val checked: Boolean? = null) : MdBlock
    data class Numbered(val number: String, val text: String, val depth: Int) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    /** `$$ … $$` 公式块：不解析里面的 Markdown，按公式样式排版。 */
    data class Math(val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    /** AI 写的块级内容（`:::ai` … `:::`）：预览渲染成绿色大引号，每行可点 × 删除。 */
    data class AiBlock(val items: List<String>) : MdBlock
    data object Rule : MdBlock
}

private val bulletPattern = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val numberedPattern = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
private val headingPattern = Regex("^(#{1,6})\\s+(.+)$")

/** 表格的对齐行：`---` / `:---` / `---:` / `:---:`，只用来标记「这是表头分隔」。 */
private val separatorCell = Regex("^:?-{2,}:?$")

/** 分割线：整行只有 3 个以上同样的 `-` / `*` / `_`（中间可以夹空格）。 */
private val rulePattern = Regex("^([-*_])(?:\\s*\\1){2,}$")

/** 任务清单项：`[ ] 待办` / `[x] 已办`。 */
private val taskPattern = Regex("^\\[([ xX])]\\s+(.*)$")


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

        // `$$` 公式块：单独成块。单行 `$$E=mc^2$$` 一次闭合，否则收到行尾带 `$$` 的那一行。
        if (line.trimStart().startsWith("$$")) {
            flushParagraph()
            val trimmed = line.trim()
            if (trimmed.length > 4 && trimmed.endsWith("$$") && !trimmed.removePrefix("$$").trimStart().startsWith("$$")) {
                blocks += MdBlock.Math(trimmed.removePrefix("$$").removeSuffix("$$").trim())
                index++
                continue
            }
            val math = StringBuilder(line.trimStart().removePrefix("$$").trim())
            index++
            while (index < lines.size) {
                val next = lines[index].trimEnd()
                if (next.trim().endsWith("$$")) {
                    val tail = next.trim().removeSuffix("$$").trim()
                    if (tail.isNotEmpty()) math.append(' ').append(tail)
                    index++
                    break
                }
                math.append('\n').append(next)
                index++
            }
            blocks += MdBlock.Math(math.toString().trim())
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

        // 分割线要排在列表前面：`- - -` 也能被列表正则认出来，先判横线才不会被吃成一项。
        if (rulePattern.matches(line.trim())) {
            flushParagraph()
            blocks += MdBlock.Rule
            index++
            continue
        }

        val bullet = bulletPattern.matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            val content = bullet.groupValues[2].trim()
            val task = taskPattern.matchEntire(content)
            if (task != null) {
                blocks += MdBlock.Bullet(
                    text = task.groupValues[2].trim(),
                    depth = indentDepth(bullet.groupValues[1]),
                    checked = task.groupValues[1].equals("x", ignoreCase = true),
                )
            } else {
                blocks += MdBlock.Bullet(content, indentDepth(bullet.groupValues[1]))
            }
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

/** 行内语法的唯一清单：渲染（本文件）与整篇编辑器的投影（MarkdownDocumentEdit.kt）共用。 */
internal val inlinePattern = Regex(
    // 用命名分组：加一条语法不会让所有 `groups[i]` 集体错位。
    // 强调符号必须贴着文字：`a * b * c` 里的星号是普通字符，不是斜体
    // （CommonMark 的「强调不能以空白开头/结尾」规则，简化版）。
    "\\*\\*\\*(?<bolditalic>(?!\\s).+?(?<!\\s))\\*\\*\\*" +      // ***粗斜体***
        "|\\*\\*(?<bold>(?!\\s).+?(?<!\\s))\\*\\*" +             // **粗体**
        "|`(?<code>[^`]+?)`" +                                  // `行内代码`
        "|\\*(?<italic>(?!\\s).+?(?<!\\s))\\*" +                 // *斜体*
        "|!\\[(?<imageAlt>.*?)]\\((?<imageUrl>.+?)\\)" +         // ![图片](url)
        "|\\[(?<linkLabel>.+?)]\\((?<linkUrl>.+?)\\)" +          // [文字](链接)
        "|\\[\\[(?<concept>[^\\[\\]\\n]+?)\\]\\]" +              // [[概念]]：显式标记的概念
        "|~~(?<strike>(?!\\s).+?(?<!\\s))~~" +                   // ~~删除线~~
        "|\\$(?!\$)(?<math>(?!\\s)[^$\\n]+?(?<!\\s))\\$(?!\$)", // $行内公式$
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

/**
 * 概念命中的样式：浅绿下划线，而不是把整段字染成绿色。
 *
 * Compose 的 [SpanStyle] 没有独立的下划线颜色，下划线会跟随文字颜色，
 * 所以这里用「浅一档的绿 + 下划线」：远看是划线在标记概念，近看字也不刺眼。
 */
private fun conceptColor(conceptGreen: Color): Color = conceptGreen.copy(alpha = 0.62f)

/** 标注过的概念（`[[…]]`）：浅绿 + 下划线，一眼看出「这里是本文概念」。 */
private fun conceptStyle(conceptGreen: Color): SpanStyle =
    SpanStyle(
        color = conceptColor(conceptGreen),
        textDecoration = TextDecoration.Underline,
    )

/**
 * 概念名/别名在正文里的普通出现：只染色、不加下划线。
 *
 * 和 [conceptStyle] 的差别就是「算不算本文概念」——普通出现只是提到，标注过的才算。
 */
private fun conceptPlainStyle(conceptGreen: Color): SpanStyle =
    SpanStyle(color = conceptColor(conceptGreen))

/** 行内代码的底色：半透明灰，暗色主题下提亮、亮色主题下压暗，两边都能和正文分开。 */
internal val MarkdownCodeBackground: Color = Color(0x2E9E9E9E)

/**
 * 中文粗体的视觉问题：macOS 的中文回退字体（PingFang SC）最粗只有 Semibold，
 * `FontWeight.Bold` 和 `FontWeight.Black` 会落到同一个字面，中文几乎看不出变粗。
 *
 * 解决方式是再叠一层「同色、只往右偏一丁点、不模糊」的阴影：相当于把每根笔画加宽约 1px，
 * 视觉上等价于真正的粗体，而且不依赖平台字体是否有对应的字重。
 * [textColor] 未知（[Color.Unspecified]）时不画阴影，保持纯函数旧的输出，单测不受影响。
 */
internal fun markdownBoldStyle(textColor: Color, italic: Boolean = false): SpanStyle = SpanStyle(
    fontWeight = FontWeight.Bold,
    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
    shadow = if (textColor == Color.Unspecified) {
        null
    } else {
        Shadow(color = textColor, offset = Offset(2.2f, 0f), blurRadius = 0f)
    },
)

/**
 * 行内代码的样式：只负责等宽。
 *
 * 底色不能走 [SpanStyle.background]：它画出来是方角、而且盒子高度跟着行盒走（下沿贴降部），
 * 和「圆角芯片、上下对称」的要求不符。底色统一由 [drawInlineCodeChips] 按文本布局自己画。
 */
internal fun markdownCodeStyle(): SpanStyle = SpanStyle(fontFamily = FontFamily.Monospace)

/** 行内渲染结果：显示文本 + 显示文本里行内代码的区间（给圆角芯片画底用）。 */
internal data class MarkdownInline(
    val text: AnnotatedString,
    val codeChips: List<IntRange> = emptyList(),
)

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
    textColor: Color = Color.Unspecified,
    showLinkUrl: Boolean = true,
    chips: MutableList<IntRange>? = null,
) {
    if (to <= from) return

    /** 粗体的阴影要和这段文字实际的颜色一致；概念高亮是绿色，阴影也必须绿色。 */
    fun boldShadowColor(a: Int, b: Int): Color {
        if (textColor == Color.Unspecified) return Color.Unspecified
        val overlapsConcept = conceptHits.any { it.first < b && it.last >= a }
        return if (overlapsConcept) conceptColor(conceptGreen) else textColor
    }
    var cursor = from
    fun appendPlain(a: Int, b: Int) {
        if (b <= a) return
        var position = a
        for (hit in conceptHits) {
            if (hit.last < position || hit.first >= b) continue
            val start = maxOf(hit.first, position)
            val end = minOf(hit.last + 1, b)
            if (start > position) append(text.substring(position, start))
            withStyle(conceptPlainStyle(conceptGreen)) { append(text.substring(start, end)) }
            position = end
        }
        if (b > position) append(text.substring(position, b))
    }
    val segment = text.substring(from, to)
    for (match in inlinePattern.findAll(segment)) {
        val matchStart = from + match.range.first
        val matchEnd = from + match.range.last + 1
        appendPlain(cursor, matchStart)
        val groups = match.groups
        val concept = groups["concept"]
        val boldItalic = groups["bolditalic"]
        val bold = groups["bold"]
        val code = groups["code"]
        val italic = groups["italic"]
        val imageAlt = groups["imageAlt"]
        val imageUrl = groups["imageUrl"]
        val linkLabel = groups["linkLabel"]
        val linkUrl = groups["linkUrl"]
        val strike = groups["strike"]
        val math = groups["math"]
        when {
            concept != null -> withStyle(conceptStyle(conceptGreen)) {
                // 只显示分隔符左边的原文：`[[检索增强生成 => RAG]]` 显示「检索增强生成」。
                append(conceptAnnotationDisplay(text.substring(from + concept.range.first, from + concept.range.last + 1)))
            }
            boldItalic != null -> withStyle(
                markdownBoldStyle(
                    boldShadowColor(from + boldItalic.range.first, from + boldItalic.range.last + 1),
                    italic = true,
                ),
            ) {
                appendPlain(from + boldItalic.range.first, from + boldItalic.range.last + 1)
            }
            bold != null -> withStyle(
                markdownBoldStyle(boldShadowColor(from + bold.range.first, from + bold.range.last + 1)),
            ) {
                appendPlain(from + bold.range.first, from + bold.range.last + 1)
            }
            code != null -> {
                val chipStart = length
                withStyle(markdownCodeStyle()) {
                    appendPlain(from + code.range.first, from + code.range.last + 1)
                }
                chips?.add(chipStart until length)
            }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendPlain(from + italic.range.first, from + italic.range.last + 1)
            }
            imageAlt != null && imageUrl != null -> {
                // 不联网取图（笔记是本地文本，聊天也可能在离线环境）；把说明和地址读出来就够用。
                withStyle(SpanStyle(color = ColorLink, fontWeight = FontWeight.Medium)) {
                    append("［图］" + imageAlt.value.ifBlank { "图片" })
                }
                append(" (" + imageUrl.value + ")")
            }
            linkLabel != null && linkUrl != null -> {
                withStyle(SpanStyle(color = ColorLink, fontWeight = FontWeight.Medium)) {
                    appendPlain(from + linkLabel.range.first, from + linkLabel.range.last + 1)
                }
                // marktext 只显示 label，点进编辑态才展开 `[label](url)`；
                // 聊天消息仍把地址带上，模型给的链接才复制得走。
                if (showLinkUrl) append(" (" + linkUrl.value + ")")
            }
            strike != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendPlain(from + strike.range.first, from + strike.range.last + 1)
            }
            math != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic),
            ) {
                appendPlain(from + math.range.first, from + math.range.last + 1)
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
    textColor: Color = Color.Unspecified,
    showLinkUrl: Boolean = true,
): AnnotatedString = buildInlineMarkdown(
    text, conceptNames, aiGreen, conceptGreen, textColor, showLinkUrl,
).text

/**
 * 和 [inlineMarkdown] 同一套规则，但额外返回「显示文本里哪些区间是行内代码」，
 * 让渲染层能用 [drawInlineCodeChips] 画圆角芯片。
 */
internal fun buildInlineMarkdown(
    text: String,
    conceptNames: List<String> = emptyList(),
    aiGreen: Color = MarkdownAiGreen,
    conceptGreen: Color = MarkdownAiGreen,
    textColor: Color = Color.Unspecified,
    showLinkUrl: Boolean = true,
): MarkdownInline {
    val chips = mutableListOf<IntRange>()
    val annotated = buildAnnotatedString {
    val hits = conceptHits(text, conceptNames)
    var cursor = 0
    for (span in aiInlineSpans(text)) {
        appendStyledRange(text, cursor, span.start, hits, conceptGreen, textColor, showLinkUrl, chips)
        withStyle(SpanStyle(color = aiGreen, fontWeight = FontWeight.Bold, fontSize = AiQuoteFontSize)) {
            append("\u201C")
        }
        appendStyledRange(
            text,
            span.start + AI_INLINE_OPEN.length,
            span.end - AI_INLINE_CLOSE.length,
            hits,
            conceptGreen,
            textColor,
            showLinkUrl,
            chips,
        )
        withStyle(SpanStyle(color = aiGreen, fontWeight = FontWeight.Bold, fontSize = AiQuoteFontSize)) {
            append("\u201D")
        }
        cursor = span.end
    }
    appendStyledRange(text, cursor, text.length, hits, conceptGreen, textColor, showLinkUrl, chips)
    }
    return MarkdownInline(annotated, chips)
}

/**
 * 渲染态是否把链接地址一起显示。
 *
 * 知识库正文只显示 label（点进块编辑才展开 `[label](url)`，marktext 的做法）；
 * 聊天消息保持显示地址，模型给的链接才复制得走。
 */
private val LocalMarkdownShowLinkUrl = compositionLocalOf { true }

/**
 * 渲染态用的行内渲染：按当前 [MarkdownText] 的链接显示方式调纯函数 [inlineMarkdown]。
 *
 * 纯函数保持默认行为（显示地址），方便单测直接调。
 */
@Composable
private fun markdownInline(
    text: String,
    conceptNames: List<String>,
    aiGreen: Color,
    conceptGreen: Color,
    textColor: Color,
): MarkdownInline = buildInlineMarkdown(
    text = text,
    conceptNames = conceptNames,
    aiGreen = aiGreen,
    conceptGreen = conceptGreen,
    textColor = textColor,
    showLinkUrl = LocalMarkdownShowLinkUrl.current,
)

/**
 * 行内代码芯片：圆角矩形，上下按字号对称留白。
 *
 * 不用 [SpanStyle.background] 的原因：它画出来是方角，而且盒子跟着行盒走——下沿贴着降部，
 * 和「上边界对齐大写字母、下边界对称」的要求差一截。这里按 [TextLayoutResult] 的基线
 * 自己算盒子：上沿 = 基线 - 0.80 倍字号，下沿 = 基线 + 0.24 倍字号。
 */
internal fun DrawScope.drawInlineCodeChips(
    layout: TextLayoutResult,
    ranges: List<IntRange>,
    style: TextStyle,
    background: Color = MarkdownCodeBackground,
    radius: Dp = 4.dp,
    horizontalPadding: Dp = 3.dp,
) {
    if (ranges.isEmpty()) return
    val fontSizeSp = style.fontSize.value
    if (fontSizeSp.isNaN() || fontSizeSp <= 0f) return
    val fontSize = fontSizeSp.sp.toPx()
    val radiusPx = radius.toPx()
    val padPx = horizontalPadding.toPx()
    val textLength = layout.layoutInput.text.length
    for (range in ranges) {
        if (range.isEmpty()) continue
        val start = range.first.coerceIn(0, textLength)
        val end = (range.last + 1).coerceIn(start, textLength)
        if (end <= start) continue
        val firstLine = layout.getLineForOffset(start)
        val lastLine = layout.getLineForOffset(end - 1)
        for (line in firstLine..lastLine) {
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line, visibleEnd = true)
            val segStart = maxOf(start, lineStart)
            val segEnd = minOf(end, lineEnd)
            if (segEnd <= segStart) continue
            val left = layout.getHorizontalPosition(segStart, usePrimaryDirection = true) - padPx
            val right = if (segEnd >= lineEnd) {
                layout.getLineRight(line) + padPx
            } else {
                layout.getHorizontalPosition(segEnd, usePrimaryDirection = true) + padPx
            }
            val baseline = layout.getLineBaseline(line)
            val top = baseline - fontSize * 0.80f
            val bottom = baseline + fontSize * 0.24f
            drawRoundRect(
                color = background,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                cornerRadius = CornerRadius(radiusPx, radiusPx),
            )
        }
    }
}

/**
 * 带行内代码芯片的 Text：布局出来后在文字底下补圆角底色，其余样式仍走 [Text]。
 */
@Composable
private fun MarkdownInlineText(
    markdown: MarkdownInline,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    textDecoration: TextDecoration? = null,
) {
    var layout by remember(markdown) { mutableStateOf<TextLayoutResult?>(null) }
    Box(
        modifier.drawBehind {
            val result = layout ?: return@drawBehind
            drawInlineCodeChips(result, markdown.codeChips, style)
        },
    ) {
        Text(
            text = markdown.text,
            style = style,
            color = color,
            fontWeight = fontWeight,
            textDecoration = textDecoration,
            onTextLayout = { layout = it },
        )
    }
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
    /**
     * 预览的目录跳转用：每块报告自己在窗口里的 y（px）。
     * 传 null 时完全不装这个 Modifier，聊天消息那边零额外开销。
     */
    onBlockLayout: ((index: Int, y: Float) -> Unit)? = null,
    /** 链接是否把地址一起显示；知识库预览传 false（只显示 label）。 */
    showLinkUrl: Boolean = true,
) {
    CompositionLocalProvider(LocalMarkdownShowLinkUrl provides showLinkUrl) {
        MarkdownBody(text, modifier, bodyStyle, conceptNames, onDeleteAiBlockLine, onBlockLayout)
    }
}

@Composable
private fun MarkdownBody(
    text: String,
    modifier: Modifier,
    bodyStyle: androidx.compose.ui.text.TextStyle?,
    conceptNames: List<String>,
    onDeleteAiBlockLine: ((String) -> Unit)?,
    onBlockLayout: ((index: Int, y: Float) -> Unit)?,
) {
    val paragraphStyle = bodyStyle ?: MaterialTheme.typography.bodyLarge
    val extra = LocalExtraColors.current
    val aiGreen = extra.accentText
    val blocks = remember(text) { parseMarkdown(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEachIndexed { index, block ->
            val measured = if (onBlockLayout == null) {
                Modifier
            } else {
                Modifier.onGloballyPositioned { coordinates ->
                    onBlockLayout(index, coordinates.positionInRoot().y)
                }
            }
            Box(measured) {
                when (block) {
                    is MdBlock.Paragraph -> MarkdownInlineText(
                        markdown = markdownInline(
                            block.text, conceptNames, aiGreen, aiGreen, MaterialTheme.colorScheme.onSurface,
                        ),
                        style = paragraphStyle,
                    )
                    is MdBlock.Heading -> MarkdownInlineText(
                        markdown = markdownInline(
                            block.text, conceptNames, aiGreen, aiGreen, MaterialTheme.colorScheme.onSurface,
                        ),
                        style = markdownHeadingStyle(block.level, paragraphStyle),
                        modifier = Modifier.padding(top = headingTopSpacing(block.level)),
                    )
                    is MdBlock.Bullet -> if (block.checked != null) {
                        MarkdownTaskRow(
                            depth = block.depth,
                            checked = block.checked,
                            text = block.text,
                            bodyStyle = paragraphStyle,
                            conceptNames = conceptNames,
                            aiGreen = aiGreen,
                        )
                    } else {
                        MarkdownBullet(
                            block.depth,
                            "•",
                            block.text,
                            paragraphStyle,
                            conceptNames,
                            aiGreen,
                        )
                    }
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
                    is MdBlock.Math -> Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            block.code,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
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
                        MarkdownInlineText(
                            markdown = markdownInline(
                                block.text,
                                conceptNames,
                                aiGreen,
                                aiGreen,
                                MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
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
                    MdBlock.Rule -> androidx.compose.material3.HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = 1.dp,
                    )
                }
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
                    MarkdownInlineText(
                        markdown = markdownInline(
                            item, conceptNames, aiGreen, aiGreen, MaterialTheme.colorScheme.onSurface,
                        ),
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

/**
 * 标题字号：按正文字号算一档**独立的阶梯**，不直接借 MaterialTheme 的 `title*` / `label*`。
 *
 * 默认主题里 `titleMedium` 是 16sp、`bodyLarge` 也是 16sp，`titleSmall` 只有 14sp ——
 * 照搬的结果是一级标题和正文一样大、二级标题比正文还小，「加粗的正文」反而压过标题
 * （2026-10-31 用户反馈：加粗比标题还大）。这里保证三件事：
 *
 * 1. 每一级严格递减（`#` > `##` > `###` …）；
 * 2. 任何一级标题都不小于正文——否则「这是标题」根本看不出来；
 * 3. 加粗和正文同字号，所以它必然小于任何标题。
 *
 * 系数乘在传入的正文样式上，所以主题的整体缩放（`ChromeStyle.scale`）和用户字号偏好
 * （`UiPrefs.fontScale`）都会自动跟着走，不用在这里再算一遍。
 */
internal fun markdownHeadingStyle(level: Int, base: androidx.compose.ui.text.TextStyle): androidx.compose.ui.text.TextStyle {
    // TextUnit.Unspecified 的 value 是 NaN：没有字号时退回 16sp（和 bodyLarge 一致）。
    val rawSize = base.fontSize.value
    val baseSize = if (rawSize.isNaN() || rawSize <= 0f) 16.sp else rawSize.sp
    val scale = when (level) {
        1 -> 1.50f
        2 -> 1.31f
        3 -> 1.16f
        4 -> 1.10f
        5 -> 1.04f
        else -> 1.00f
    }
    val size = baseSize * scale
    return base.copy(
        fontSize = size,
        lineHeight = size * 1.32f,
        fontWeight = FontWeight.Bold,
    )
}

/** 标题上方留白：级别越高离上一段越远，读起来才知道「新的一节开始了」。 */
internal fun headingTopSpacing(level: Int) = when (level) {
    1 -> 14.dp
    2 -> 10.dp
    3 -> 8.dp
    else -> 6.dp
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
                MarkdownInlineText(
                    markdown = markdownInline(
                        cell,
                        conceptNames,
                        aiGreen,
                        aiGreen,
                        if (header) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
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
        MarkdownInlineText(
            markdown = markdownInline(text, conceptNames, aiGreen, aiGreen, MaterialTheme.colorScheme.onSurface),
            style = bodyStyle,
        )
    }
}

/**
 * 任务清单一行：方框自己画，不用 `☑`/`☐` 字符。
 *
 * 那两个字符在 Windows 上会落到彩色 emoji 字体（预览里显示成紫色方块），
 * 和周围的单色文字不搭；自己画的方框还能顺手给「已完成」加一条贯穿线。
 */
@Composable
private fun MarkdownTaskRow(
    depth: Int,
    checked: Boolean,
    text: String,
    bodyStyle: androidx.compose.ui.text.TextStyle,
    conceptNames: List<String>,
    aiGreen: Color,
) {
    Row(
        modifier = Modifier.padding(start = (depth * 14).dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(13.dp)
                .clip(RoundedCornerShape(3.dp))
                .border(
                    width = 1.dp,
                    color = if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    },
                    shape = RoundedCornerShape(3.dp),
                )
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(7.dp))
        MarkdownInlineText(
            markdown = markdownInline(
                text,
                conceptNames,
                aiGreen,
                aiGreen,
                if (checked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
            style = bodyStyle,
            color = if (checked) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (checked) TextDecoration.LineThrough else null,
        )
    }
}
