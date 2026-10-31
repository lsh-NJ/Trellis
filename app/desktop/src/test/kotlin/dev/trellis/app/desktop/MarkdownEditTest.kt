package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Markdown 编辑变换的测试：斜杠命令和快捷键都靠这几个纯函数。
 *
 * 重点钉两类事故：选区边界把字吃掉、光标位置在变换后飞到别处。
 */
class MarkdownEditTest {

    private fun collapsed(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    @Test
    fun boldWrapsSelection() {
        val result = toggleInlineMarkdown("hello world", TextRange(0, 5), "**", "粗体")
        assertEquals("**hello** world", result.text)
        assertEquals(2, result.selection.min)
        assertEquals(7, result.selection.max)
    }

    @Test
    fun boldUnwrapsWhenSelectionOutsideMarker() {
        val result = toggleInlineMarkdown("**hello** world", TextRange(2, 7), "**", "粗体")
        assertEquals("hello world", result.text)
        assertEquals(0, result.selection.min)
        assertEquals(5, result.selection.max)
    }

    @Test
    fun boldWithCollapsedCursorInsertsPlaceholderAndSelectsIt() {
        val result = toggleInlineMarkdown("", TextRange(0), "**", "粗体")
        assertEquals("**粗体**", result.text)
        assertEquals(2, result.selection.min)
        assertEquals(4, result.selection.max)
    }

    @Test
    fun bulletAppliesToEverySelectedLineAndTogglesOff() {
        val on = toggleLineMarkdown("a\nb", TextRange(0, 3), "- ")
        assertEquals("- a\n- b", on.text)

        val off = toggleLineMarkdown(on.text, on.selection, "- ")
        assertEquals("a\nb", off.text)
    }

    @Test
    fun numberedListRenumbersEachLine() {
        val result = toggleNumberedList("a\nb\nc", TextRange(0, 5))
        assertEquals("1. a\n2. b\n3. c", result.text)
    }

    @Test
    fun headingTogglesOnCurrentLine() {
        val on = setHeadingLevel("标题", TextRange(0, 2), 2)
        assertEquals("## 标题", on.text)

        val off = setHeadingLevel(on.text, on.selection, 2)
        assertEquals("标题", off.text)
    }

    @Test
    fun headingReplacesExistingLevel() {
        val result = setHeadingLevel("# 标题", TextRange(0, 4), 3)
        assertEquals("### 标题", result.text)
    }

    @Test
    fun linkWrapsSelectedTextAndSelectsUrl() {
        val result = insertMarkdownLink("RAG 是检索增强", TextRange(0, 3))
        assertEquals("[RAG](url) 是检索增强", result.text)
        assertEquals(6, result.selection.min)
        assertEquals(9, result.selection.max)
    }

    @Test
    fun codeBlockWrapsSelection() {
        val result = insertCodeBlock("val a = 1", TextRange(0, 9))
        assertEquals("```\nval a = 1\n```", result.text)
        assertEquals(4, result.selection.min)
        assertEquals(13, result.selection.max)
    }

    @Test
    fun tableInsertsTwoByTwoTemplate() {
        val result = insertMarkdownTable("", TextRange(0))
        assertEquals("| 列 1 | 列 2 |\n| --- | --- |\n|  |  |", result.text)
    }

    @Test
    fun enterContinuesBulletList() {
        val result = continueMarkdownList(collapsed("- item", 6))
        assertEquals("- item\n- ", result?.text)
        assertEquals(9, result?.selection?.min)
    }

    @Test
    fun enterOnEmptyBulletExitsList() {
        val result = continueMarkdownList(collapsed("- ", 2))
        assertEquals("", result?.text)
        assertEquals(0, result?.selection?.min)
    }

    @Test
    fun enterContinuesNumberedListWithNextNumber() {
        val result = continueMarkdownList(collapsed("1. 第一步", 6))
        assertEquals("1. 第一步\n2. ", result?.text)
    }

    @Test
    fun enterContinuesQuote() {
        val result = continueMarkdownList(collapsed("> 引用", 4))
        assertEquals("> 引用\n> ", result?.text)
    }

    @Test
    fun enterInMiddleOfLineIsNotIntercepted() {
        assertNull(continueMarkdownList(collapsed("abc", 1)))
        assertNull(continueMarkdownList(TextFieldValue("plain", TextRange(5))))
    }

    @Test
    fun horizontalRuleGetsItsOwnLine() {
        val afterText = insertHorizontalRule("上一段", TextRange(3))
        assertEquals("上一段\n---", afterText.text)
        assertEquals(7, afterText.selection.min)

        val atStart = insertHorizontalRule("下一段", TextRange(0))
        assertEquals("---\n下一段", atStart.text)
        assertEquals(3, atStart.selection.min)
    }

    @Test
    fun horizontalRuleDoesNotDoubleTheNewline() {
        val result = insertHorizontalRule("a\n", TextRange(2))
        assertEquals("a\n---", result.text)
    }

    @Test
    fun taskListAppliesAndTogglesOff() {
        val on = toggleTaskList("a\nb", TextRange(0, 3))
        assertEquals("- [ ] a\n- [ ] b", on.text)

        val off = toggleTaskList("- [ ] a\n- [x] b", TextRange(0, 15))
        assertEquals("a\nb", off.text)
    }

    @Test
    fun clearHeadingStripsOneToSixHashes() {
        assertEquals("标题", clearHeading("## 标题", TextRange(0, 4)).text)
        assertEquals("正文", clearHeading("###### 正文", TextRange(0, 4)).text)
        assertEquals("没有标题", clearHeading("没有标题", TextRange(0, 4)).text)
    }

    @Test
    fun imageWrapsSelectionWithAltText() {
        val result = insertMarkdownImage("架构图", TextRange(0, 3))
        assertEquals("![架构图](url)", result.text)
        assertEquals(7, result.selection.min)
        assertEquals(10, result.selection.max)
    }
}
