package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 会话与预览里的 Markdown 渲染。
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
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
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

        if (line.isBlank()) {
            flushParagraph()
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

/**
 * 行内样式。用命名分组而不是下标：加一条语法不会再让所有 `groups[i]` 集体错位。
 *
 * 强调符号必须贴着文字：`a * b * c` 里的星号是普通字符，不是斜体
 * （CommonMark 的「强调不能以空白开头/结尾」规则，简化版）。
 */
private val inlinePattern = Regex(
    "\\*\\*\\*(?<bolditalic>(?!\\s).+?(?<!\\s))\\*\\*\\*" +      // ***粗斜体***
        "|\\*\\*(?<bold>(?!\\s).+?(?<!\\s))\\*\\*" +             // **粗体**
        "|`(?<code>[^`]+?)`" +                                  // `行内代码`
        "|\\*(?<italic>(?!\\s).+?(?<!\\s))\\*" +                 // *斜体*
        "|!\\[(?<imageAlt>.*?)]\\((?<imageUrl>.+?)\\)" +         // ![图片](url)
        "|\\[(?<linkLabel>.+?)]\\((?<linkUrl>.+?)\\)" +          // [文字](链接)
        "|~~(?<strike>(?!\\s).+?(?<!\\s))~~",                    // ~~删除线~~
)

/** 行内样式：粗体/粗斜体/斜体/行内代码/链接/图片/删除线；认不出的原样保留。 */
internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    fun appendPlain(from: Int, to: Int) {
        if (to > from) append(text.substring(from, to))
    }
    for (match in inlinePattern.findAll(text)) {
        appendPlain(cursor, match.range.first)
        val groups = match.groups
        val boldItalic = groups["bolditalic"]
        val bold = groups["bold"]
        val code = groups["code"]
        val italic = groups["italic"]
        val imageAlt = groups["imageAlt"]
        val imageUrl = groups["imageUrl"]
        val linkLabel = groups["linkLabel"]
        val linkUrl = groups["linkUrl"]
        val strike = groups["strike"]
        when {
            boldItalic != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                append(boldItalic.value)
            }
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold.value) }
            code != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code.value) }
            italic != null -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic.value) }
            imageAlt != null && imageUrl != null -> {
                // 不联网取图（笔记是本地文本，聊天也可能在离线环境）；把说明和地址读出来就够用。
                withStyle(SpanStyle(color = ColorLink, fontWeight = FontWeight.Medium)) {
                    append("［图］" + imageAlt.value.ifBlank { "图片" })
                }
                append(" (" + imageUrl.value + ")")
            }
            linkLabel != null && linkUrl != null -> {
                withStyle(SpanStyle(color = ColorLink, fontWeight = FontWeight.Medium)) { append(linkLabel.value) }
                append(" (" + linkUrl.value + ")")
            }
            strike != null -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(strike.value) }
        }
        cursor = match.range.last + 1
    }
    appendPlain(cursor, text.length)
}

/** 链接颜色在这里硬编码会绕开主题；用「主色文本」的近似值，暗色下也读得清。 */
private val ColorLink = androidx.compose.ui.graphics.Color(0xFF2E5AAC)

/**
 * 渲染一段 Markdown。
 *
 * [onBlockLayout] 给预览的目录跳转用：每块报告自己的 root 坐标 y（px）。
 * 传 null 时完全不装这个 Modifier，聊天消息那边零额外开销。
 */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    bodyStyle: TextStyle? = null,
    onBlockLayout: ((index: Int, y: Float) -> Unit)? = null,
) {
    val paragraphStyle = bodyStyle ?: MaterialTheme.typography.bodyLarge
    val blocks = parseMarkdown(text)
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
                MarkdownBlock(block, paragraphStyle)
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock, paragraphStyle: TextStyle) {
    when (block) {
        is MdBlock.Paragraph -> Text(
            inlineMarkdown(block.text),
            style = paragraphStyle,
        )
        is MdBlock.Heading -> Text(
            inlineMarkdown(block.text),
            style = markdownHeadingStyle(block.level, paragraphStyle),
            modifier = Modifier.padding(top = headingTopSpacing(block.level)),
        )
        is MdBlock.Bullet -> if (block.checked != null) {
            MarkdownTaskRow(block.depth, block.checked, block.text, paragraphStyle)
        } else {
            MarkdownBullet(block.depth, "•", block.text, paragraphStyle)
        }
        is MdBlock.Numbered -> MarkdownBullet(block.depth, block.number + ".", block.text, paragraphStyle)
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
                inlineMarkdown(block.text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is MdBlock.Table -> MarkdownTable(block)
        MdBlock.Rule -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 6.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
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
internal fun markdownHeadingStyle(level: Int, base: TextStyle): TextStyle {
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
private fun headingTopSpacing(level: Int) = when (level) {
    1 -> 14.dp
    2 -> 10.dp
    3 -> 8.dp
    else -> 6.dp
}

@Composable
private fun MarkdownTable(table: MdBlock.Table) {    // 列数取表头与数据行里最多的那个；超过 6 列就不再收（聊天里再宽也读不了）。
    val columns = maxOf(
        table.header.size,
        table.rows.maxOfOrNull { row -> row.size } ?: 0,
    ).coerceIn(1, 6)
    fun cells(row: List<String>): List<String> = List(columns) { column -> row.getOrElse(column) { "" } }

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        MarkdownTableRow(cells(table.header), header = true)
        for (row in table.rows) MarkdownTableRow(cells(row), header = false)
    }
}

@Composable
private fun MarkdownTableRow(cells: List<String>, header: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for (cell in cells) {
            Box(Modifier.weight(1f)) {
                Text(
                    inlineMarkdown(cell),
                    style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                    color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        thickness = 1.dp,
    )
}

@Composable
private fun MarkdownBullet(
    depth: Int,
    marker: String,
    text: String,
    bodyStyle: TextStyle,
) {
    Row(Modifier.padding(start = (depth * 14).dp)) {
        Text(
            marker,
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp),
        )
        Text(
            inlineMarkdown(text),
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
    bodyStyle: TextStyle,
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
                    if (checked) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
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
        Text(
            inlineMarkdown(text),
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
