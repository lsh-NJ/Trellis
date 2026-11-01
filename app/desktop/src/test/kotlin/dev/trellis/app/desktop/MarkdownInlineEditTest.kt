package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 编辑态行内投影：标记该藏的时候藏、光标进去时还原，光标映射不能把标记删掉。
 *
 * 这些用例钉的是 marktext 那条交互：`**战技**` 在光标不在里面时显示成加粗的「战技」，
 * 光标进去才看到 `**`。
 */
class MarkdownInlineEditTest {

    private fun project(
        raw: String,
        caret: Int = raw.length,
        kind: BlockKind = BlockKind.Paragraph,
    ): InlineEditProjection = projectInlineEdit(raw, TextRange(caret), kind)

    @Test
    fun boldMarkersAreHiddenWhenTheCaretIsElsewhere() {
        val raw = "别礼**战技**起手 -"
        val projection = project(raw)

        assertEquals("别礼战技起手 -", projection.text)
        assertEquals(listOf(InlineMarkRange(2, 4, InlineMarkKind.Bold)), projection.marks)
        assertEquals(listOf(2..3, 6..7), projection.hidden)
        assertTrue(projection.syntax.isEmpty())
        assertEquals(1, projection.tokens.size)
        assertEquals(2, projection.tokens[0].start)
        assertEquals(8, projection.tokens[0].end)
    }

    @Test
    fun boldMarkersComeBackWhenTheCaretIsInsideTheToken() {
        val raw = "别礼**战技**起手 -"
        val projection = project(raw, caret = 4)

        assertEquals(raw, projection.text)
        assertTrue(projection.hidden.isEmpty())
        assertEquals(listOf(2..3, 6..7), projection.syntax.map { it })
        assertEquals(listOf(InlineMarkRange(4, 6, InlineMarkKind.Bold)), projection.marks)
    }

    @Test
    fun selectingTheWholeLineRevealsEveryMarker() {
        val raw = "别礼**战技**起手"
        val projection = projectInlineEdit(raw, TextRange(0, raw.length), BlockKind.Paragraph)

        assertEquals(raw, projection.text)
        assertTrue(projection.hidden.isEmpty())
    }

    @Test
    fun onlyTheTokenUnderTheCaretIsRevealed() {
        val raw = "**a** 和 **b**"
        val projection = project(raw, caret = 3)

        assertEquals(listOf(8..9, 11..12), projection.hidden)
        assertFalse(projection.hidden.any { 0 in it || 3 in it || 4 in it })
    }

    @Test
    fun markersComeBackWhenTheCaretSitsRightNextToTheToken() {
        val raw = "别礼**战技**起手"
        // 紧挨在开标记之前（token.start - 1）或闭标记之后（token.end）都算「在编辑这个 token」。
        assertEquals(raw, project(raw, caret = 1).text)
        assertEquals(raw, project(raw, caret = 8).text)
        assertEquals("别礼战技起手", project(raw, caret = 0).text)
        assertEquals("别礼战技起手", project(raw, caret = 9).text)
    }

    @Test
    fun everyInlineKindGetsAHiddenMarkerAndAStyle() {
        val raw = "斜体*italic*与 `code` 和 ~~gone~~ 与 [标签](https://x.y)"
        // 光标放到最前面（离每个 token 都超过一格），所有标记都应该收起。
        val projection = project(raw, caret = 0)

        assertEquals("斜体italic与 code 和 gone 与 标签", projection.text)
        assertEquals(
            setOf(InlineMarkKind.Italic, InlineMarkKind.Code, InlineMarkKind.Strike, InlineMarkKind.Link),
            projection.marks.map { it.kind }.toSet(),
        )
        assertFalse(projection.text.contains("https"))
    }

    @Test
    fun inlineMathHidesItsDollars() {
        val raw = "行内 \$x + y\$ 公式"
        val projection = project(raw)

        assertEquals("行内 x + y 公式", projection.text)
        assertEquals(listOf(InlineMarkKind.Math), projection.marks.map { it.kind })
    }

    @Test
    fun headingPrefixStaysVisibleAsSyntaxAndTheLevelIsReported() {
        val raw = "## 小节标题"
        val projection = project(raw, kind = BlockKind.Heading)

        // `#` 一直显示成暗色源码：编辑这一块时能看到它是几级标题（预览态仍渲染成大字）。
        assertEquals(raw, projection.text)
        assertEquals(2, projection.headingLevel)
        assertEquals(0..2, projection.prefix)
        assertEquals(emptyList<IntRange>(), projection.hidden)
        assertTrue(projection.syntax.contains(0..2))
    }

    @Test
    fun headingPrefixComesBackWhenTheCaretSitsInIt() {
        val raw = "## 小节标题"
        val projection = project(raw, caret = 0, kind = BlockKind.Heading)

        assertEquals(raw, projection.text)
        assertEquals(2, projection.headingLevel)
        assertEquals(listOf(0..2), projection.syntax)
    }

    @Test
    fun bulletBecomesADotWithoutChangingTheLength() {
        val raw = "- 列表项"
        val projection = project(raw, kind = BlockKind.ListItem)

        assertEquals("• 列表项", projection.text)
        assertEquals(raw.length, projection.text.length)
        assertTrue(projection.hidden.isEmpty())
    }

    @Test
    fun taskListKeepsItsCheckboxMarker() {
        val raw = "- [ ] 待办"
        val projection = project(raw, kind = BlockKind.ListItem)

        assertEquals(raw, projection.text)
    }

    @Test
    fun quotePrefixStaysVisibleAsSyntax() {
        val raw = "> 引用一行"
        val projection = project(raw, kind = BlockKind.Quote)

        // 和标题一样：编辑引用块时把 `>` 露出来当源码，预览态不显示。
        assertEquals(raw, projection.text)
        assertTrue(projection.isQuote)
        assertEquals(emptyList<IntRange>(), projection.hidden)
        assertTrue(projection.syntax.contains(0..1))
    }

    @Test
    fun specialBlocksAreProjectedAsIs() {
        val code = "val top = hybridSearch(query, k = 50)"
        assertEquals(code, projectInlineEdit(code, TextRange(3), BlockKind.Code).text)
        assertTrue(projectInlineEdit(code, TextRange(3), BlockKind.Code).marks.isEmpty())
        assertTrue(projectInlineEdit("| a | b |", TextRange(1), BlockKind.Table).hidden.isEmpty())
    }

    @Test
    fun unmatchedMarkersStayAsPlainText() {
        val raw = "a ** b 与一个孤立的 * "
        val projection = project(raw)

        assertEquals(raw, projection.text)
        assertTrue(projection.marks.isEmpty())
        assertTrue(projection.hidden.isEmpty())
    }

    @Test
    fun mappingKeepsTheCaretOutOfHiddenMarkers() {
        val raw = "别礼**战技**起手 -"
        val projection = project(raw)

        // 隐藏区内部的 offset 落到区段起点，保证单调、光标不会往回跳。
        assertEquals(2, projection.mapping.originalToTransformed(3))
        // 可见文本「战」前面（2）对应原文内容起点 4；「起」前面（4）跳过闭标记到原文 8。
        assertEquals(4, projection.mapping.transformedToOriginal(2))
        assertEquals(8, projection.mapping.transformedToOriginal(4))
        // 末尾光标落在可见文本末尾。
        assertEquals(projection.text.length, projection.mapping.originalToTransformed(raw.length))
    }

    @Test
    fun conceptMarkersAreHiddenAndTheNameIsStyled() {
        val raw = "正文里 [[RAG]] 是检索"
        val projection = project(raw)

        assertEquals("正文里 RAG 是检索", projection.text)
        assertTrue(projection.hidden.contains(4..5))
        assertTrue(projection.hidden.contains(9..10))
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.Concept && it.start == 4 && it.end == 7 })
    }

    @Test
    fun conceptMarkersComeBackWhenTheCaretIsInside() {
        val projection = project("正文里 [[RAG]] 是检索", caret = 7)

        assertEquals("正文里 [[RAG]] 是检索", projection.text)
        assertTrue(projection.syntax.contains(4..5))
    }

    @Test
    fun headingPrefixKeepsCaretOnThePrefixItself() {
        val raw = "## 标题"
        val projection = project(raw, kind = BlockKind.Heading)

        // 前缀不再被隐藏，光标可以直接落在 `#` 上（删前缀就把标题改回段落）。
        assertEquals(0, projection.mapping.originalToTransformed(0))
        assertEquals(0, projection.mapping.transformedToOriginal(0))
        assertEquals(3, projection.mapping.transformedToOriginal(3))
    }
}
