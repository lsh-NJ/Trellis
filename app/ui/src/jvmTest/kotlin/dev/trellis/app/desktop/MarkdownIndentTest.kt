package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tab / Shift+Tab 纯函数的测试。
 *
 * 缩进只碰行首：行中内容、列表编号、行数都不能变，选区要还盖住原来那几行。
 */
class MarkdownIndentTest {

    private fun indentAll(text: String, unit: String = LIST_INDENT) =
        indentMarkdownLines(text, TextRange(0, text.length), unit)

    private fun outdentAll(text: String, unit: String = LIST_INDENT) =
        outdentMarkdownLines(text, TextRange(0, text.length), unit)

    @Test
    fun indentCollapsedCursorAddsUnitAndMovesCursor() {
        val atStart = indentMarkdownLines("列表项", TextRange(0), LIST_INDENT)
        assertEquals("  列表项", atStart.text)
        assertEquals(2, atStart.selection.min)

        val inMiddle = indentMarkdownLines("abc", TextRange(2), LIST_INDENT)
        assertEquals("  abc", inMiddle.text)
        assertEquals(4, inMiddle.selection.min)
    }

    @Test
    fun indentCollapsedCursorOnEmptyLineDoesNothing() {
        val result = indentMarkdownLines("a\n\nb", TextRange(2), LIST_INDENT)
        assertEquals("a\n\nb", result.text)
        assertEquals(2, result.selection.min)

        val trail = indentMarkdownLines("a\n", TextRange(2), LIST_INDENT)
        assertEquals("a\n", trail.text)
        assertEquals(2, trail.selection.min)
    }

    @Test
    fun indentSelectionHandlesEveryLineAndSkipsEmptyOnes() {
        val text = "- 一\n\n- 二"
        val result = indentMarkdownLines(text, TextRange(0, text.length), LIST_INDENT)
        assertEquals("  - 一\n\n  - 二", result.text)
        assertEquals(3, result.text.split('\n').size)
        assertEquals(0, result.selection.min)
        assertEquals(result.text.length, result.selection.max)
    }

    @Test
    fun selectionEndingAtTheNextLineStartDoesNotTouchThatLine() {
        // Shift+↓ 选到第三行行首：只该缩进前两行，不能把第三行也带进去。
        val text = "aa\nbb\ncc"
        val indent = indentMarkdownLines(text, TextRange(0, 6), LIST_INDENT)
        assertEquals("  aa\n  bb\ncc", indent.text)

        val indented = "  aa\n  bb\ncc"
        val outdent = outdentMarkdownLines(indented, TextRange(0, 10), LIST_INDENT)
        assertEquals("aa\nbb\ncc", outdent.text)
    }

    @Test
    fun codeBlockEmptyLineGetsIndentOnlyWhenAsked() {
        val text = "```\n\n```"
        val skipped = indentMarkdownLines(text, TextRange(4), CODE_INDENT)
        assertEquals(text, skipped.text)
        val filled = indentMarkdownLines(text, TextRange(4), CODE_INDENT, indentEmptyLines = true)
        assertEquals("```\n    \n```", filled.text)
        assertEquals(8, filled.selection.min)
    }

    @Test
    fun indentSelectionCoversWholeLines() {
        val text = "aa\nbb\ncc"
        val result = indentMarkdownLines(text, TextRange(1, 5), LIST_INDENT)
        assertEquals("  aa\n  bb\ncc", result.text)
        assertEquals(0, result.selection.min)
        assertEquals("  aa\n  bb".length, result.selection.max)
    }

    @Test
    fun outdentRemovesOneUnitFromEachLine() {
        val result = outdentAll("  - 一\n  - 二")
        assertEquals("- 一\n- 二", result.text)
        assertEquals(0, result.selection.min)
        assertEquals(result.text.length, result.selection.max)
    }

    @Test
    fun outdentTreatsTabAsOneUnit() {
        val result = outdentMarkdownLines("\t- 一", TextRange(2), LIST_INDENT)
        assertEquals("- 一", result.text)
        assertEquals(1, result.selection.min)
    }

    @Test
    fun outdentRemovesSingleLeadingSpaceWhenShorterThanUnit() {
        val result = outdentMarkdownLines(" 行首一个空格", TextRange(0), LIST_INDENT)
        assertEquals("行首一个空格", result.text)
        assertEquals(0, result.selection.min)
    }

    @Test
    fun outdentNeverDeletesBodyText() {
        val plain = outdentAll("正文")
        assertEquals("正文", plain.text)
        assertEquals(0, plain.selection.min)

        // 行首空白不足一个单位：只删空白，正文一个字都不能少。
        val short = outdentMarkdownLines(" 正文", TextRange(0), CODE_INDENT)
        assertEquals("正文", short.text)
        assertEquals(0, short.selection.min)
    }

    @Test
    fun outdentCollapsedCursorDoesNotLandInsideRemovedIndent() {
        val inside = outdentMarkdownLines("    code", TextRange(2), CODE_INDENT)
        assertEquals("code", inside.text)
        assertEquals(0, inside.selection.min)

        val after = outdentMarkdownLines("    code", TextRange(6), CODE_INDENT)
        assertEquals("code", after.text)
        assertEquals(2, after.selection.min)

        val plain = outdentMarkdownLines("code", TextRange(2), CODE_INDENT)
        assertEquals("code", plain.text)
        assertEquals(2, plain.selection.min)
    }

    @Test
    fun outdentSkipsEmptyLinesAndKeepsLineCount() {
        val result = outdentAll("  a\n\n  b")
        assertEquals("a\n\nb", result.text)
        assertEquals(3, result.text.split('\n').size)
    }

    @Test
    fun indentKeepsListNumbersAndInlineContent() {
        val text = "1. 一\n2. 二"
        val indented = indentAll(text, CODE_INDENT)
        assertEquals("    1. 一\n    2. 二", indented.text)

        val restored = outdentMarkdownLines(indented.text, indented.selection, CODE_INDENT)
        assertEquals(text, restored.text)
    }

    @Test
    fun indentThenOutdentRoundTripsMultiLineSelection() {
        val text = "> 引用\n- 列表"
        val indented = indentAll(text)
        assertEquals("  > 引用\n  - 列表", indented.text)
        assertEquals(text, outdentMarkdownLines(indented.text, indented.selection, LIST_INDENT).text)
    }
}
