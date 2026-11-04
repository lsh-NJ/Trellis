package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 整篇正文投影的测试：标记该藏的时候藏、光标进去时还原，映射和边界退格不能跳。
 *
 * 这些用例是「一个文档一个编辑面」这条路径的契约：哪里显示、光标落在哪、
 * 折叠态按退格删的是什么，都钉在这里。
 */
class MarkdownDocumentEditTest {

    private fun project(body: String, caret: Int = 0) =
        projectDocument(body, TextRange(caret))

    private fun hiddenContains(projection: DocumentProjection, range: IntRange) =
        projection.hidden.any { it == range }

    @Test
    fun inlineMarkersAreHiddenWhenTheCaretIsElsewhere() {
        val projection = project("别礼**战技**起手 -", caret = 0)
        assertEquals("别礼战技起手 -", projection.text)
        assertTrue(hiddenContains(projection, 2..3))
        assertTrue(hiddenContains(projection, 6..7))
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.Bold && it.start == 2 && it.end == 4 })
    }

    @Test
    fun markersComeBackWhenTheCaretIsInsideTheToken() {
        val body = "别礼**战技**起手 -"
        val projection = project(body, caret = 4)
        assertEquals(body, projection.text)
        assertTrue(projection.hidden.isEmpty())
        assertTrue(projection.syntax.any { it == 2..3 })
        assertTrue(projection.syntax.any { it == 6..7 })
    }

    @Test
    fun markersComeBackWhenTheCaretSitsRightNextToTheToken() {
        val body = "别礼**战技**起手 -"
        assertEquals(body, project(body, caret = 1).text)
        assertEquals(body, project(body, caret = 8).text)
        assertEquals("别礼战技起手 -", project(body, caret = 9).text)
    }

    @Test
    fun headingPrefixIsHiddenOutsideItsLineAndTheLevelIsReported() {
        val projection = project("# 标题\n正文", caret = 6)
        assertEquals("标题\n正文", projection.text)
        assertTrue(hiddenContains(projection, 0..1))
        assertEquals(listOf(0..1 to 1), projection.headings)
    }

    @Test
    fun headingPrefixComesBackOnItsOwnLine() {
        val body = "# 标题\n正文"
        val projection = project(body, caret = 0)
        assertEquals(body, projection.text)
        assertTrue(projection.syntax.any { it == 0..1 })
        assertFalse(hiddenContains(projection, 0..1))
    }

    @Test
    fun quotePrefixIsHiddenOutsideItsLine() {
        val projection = project("> 引用\n正文", caret = 6)
        assertEquals("引用\n正文", projection.text)
        assertTrue(hiddenContains(projection, 0..1))
        assertEquals(listOf(0..1), projection.quotes)
    }

    @Test
    fun bulletBecomesADotWhenTheCaretIsElsewhere() {
        val projection = project("- 列表项\n正文", caret = 7)
        assertEquals("• 列表项\n正文", projection.text)
        assertTrue(projection.hidden.isEmpty())
        assertTrue(projection.marks.isEmpty())
    }

    @Test
    fun bulletStaysRawOnItsOwnLine() {
        val body = "- 列表项\n正文"
        val projection = project(body, caret = 2)
        assertEquals(body, projection.text)
        assertTrue(projection.syntax.any { it == 0..0 })
    }

    @Test
    fun taskListKeepsItsCheckboxMarker() {
        val body = "- [ ] 任务"
        val projection = project(body, caret = 7)
        assertEquals(body, projection.text)
        assertTrue(projection.syntax.isEmpty())
    }

    @Test
    fun codeFenceKeepsEveryMarkdownMarker() {
        val body = "```\n**not bold**\n```"
        val projection = project(body, caret = body.length - 1)
        assertEquals(body, projection.text)
        assertTrue(projection.tokens.isEmpty())
        assertTrue(projection.hidden.isEmpty())
    }

    @Test
    fun conceptMarkersAreHiddenAndTheNameIsStyled() {
        val projection = project("正文里 [[RAG]] 是检索", caret = 0)
        assertEquals("正文里 RAG 是检索", projection.text)
        assertTrue(hiddenContains(projection, 4..5))
        assertTrue(hiddenContains(projection, 9..10))
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.Concept && it.start == 4 && it.end == 7 })
    }

    @Test
    fun conceptAnnotationShowsOnlyTheOriginalText() {
        val body = "正文 [[检索增强生成=>RAG]] 对比"
        val projection = projectDocument(body, TextRange(0), listOf("RAG"))
        assertEquals("正文 检索增强生成 对比", projection.text)
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.Concept && it.start == 3 && it.end == 9 })
        assertTrue(projection.hidden.any { it.first == 11 && it.last == 17 })
    }

    @Test
    fun plainConceptOccurrenceIsColoredWithoutBeingAnnotated() {
        val body = "RAG 与 检索增强 都写一遍"
        val projection = projectDocument(body, TextRange(0), listOf("RAG"))
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.ConceptPlain && it.start == 0 && it.end == 3 })
        assertTrue(projection.marks.none { it.kind == InlineMarkKind.Concept })
    }

    @Test
    fun aliasOccurrenceIsHighlightedAsPlainConcept() {
        val body = "这里用检索增强生成做问答"
        val projection = projectDocument(body, TextRange(0), listOf("检索增强生成"))
        assertTrue(projection.marks.any {
            it.kind == InlineMarkKind.ConceptPlain && it.start == 3 && it.end == 9
        })
    }

    @Test
    fun caretInsideAConceptAnnotationShowsTheWholeMarker() {
        val body = "[[检索增强生成=>RAG]]"
        val projection = projectDocument(body, TextRange(5), listOf("RAG"))
        assertEquals(body, projection.text)
        assertTrue(projection.syntax.any { it.first <= 8 && it.last >= 8 })
    }

    // ---- `[[` 自动补全 ----

    private val completionNames = listOf("RAG", "检索增强生成", "GraphRAG")

    @Test
    fun completionWaitsUntilMoreThanOneCharacterIsTyped() {
        assertNull(conceptCompletionAt("[[检", 3, completionNames))
        assertNull(conceptCompletionAt("[[", 2, completionNames))
        val completion = conceptCompletionAt("[[检索", 4, completionNames)
        assertTrue(completion != null && completion.query == "检索")
    }

    @Test
    fun completionSuggestsNamesAndTheMappingSeparator() {
        val completion = conceptCompletionAt("[[检索", 4, completionNames) ?: error("要弹补全")
        assertEquals(2, completion.nameStart)
        assertFalse(completion.afterSeparator)
        assertTrue(completion.candidates.contains("检索增强生成"))
        assertEquals(CONCEPT_COMPLETION_SEPARATOR, completion.candidates.last())
    }

    @Test
    fun completionLeavesFullyMatchedNamesAlone() {
        assertNull(conceptCompletionAt("[[检索增强生成", 8, completionNames))
        assertNull(conceptCompletionAt("[[GraphRAG", 10, completionNames))
    }

    @Test
    fun afterTheSeparatorItCompletesConceptNames() {
        val text = "[[检索增强生成 => RA"
        val completion = conceptCompletionAt(text, text.length, completionNames) ?: error("要弹补全")
        assertTrue(completion.afterSeparator)
        assertEquals("RA", completion.query)
        assertEquals(
            listOf("RAG", "GraphRAG"),
            completion.candidates.filter { it != CONCEPT_COMPLETION_SEPARATOR },
        )
        assertFalse(completion.candidates.contains(CONCEPT_COMPLETION_SEPARATOR))
    }

    @Test
    fun pickingTheSeparatorKeepsTheOriginalText() {
        // 原文和概念名不一样时：先打原文，再从补全里选 `=>`，原文一个字符都不动。
        val text = "[[检索"
        val completion = conceptCompletionAt(text, text.length, completionNames) ?: error("要弹补全")
        val updated = applyConceptCompletion(
            TextFieldValue(text, TextRange(text.length)),
            completion,
            CONCEPT_COMPLETION_SEPARATOR,
        )
        assertEquals("[[检索 => ", updated.text)
        assertEquals(updated.text.length, updated.selection.min)
    }

    @Test
    fun pickingAConceptNameClosesTheBrackets() {
        val text = "[[检索增强生成 => RA"
        val completion = conceptCompletionAt(text, text.length, completionNames) ?: error("要弹补全")
        val updated = applyConceptCompletion(
            TextFieldValue(text, TextRange(text.length)),
            completion,
            "RAG",
        )
        assertEquals("[[检索增强生成 => RAG]]", updated.text)
        assertEquals(updated.text.length, updated.selection.min)
    }

    @Test
    fun completionDoesNotTouchTextOutsideTheMarker() {
        val text = "前面 [[RA]] 后面"
        val completion = conceptCompletionAt(text, 7, completionNames) ?: error("要弹补全")
        val updated = applyConceptCompletion(
            TextFieldValue(text, TextRange(7)),
            completion,
            "RAG",
        )
        assertEquals("前面 [[RAG]] 后面", updated.text)
    }

    @Test
    fun linkShowsOnlyItsLabelWhenTheCaretIsElsewhere() {
        val projection = project("看 [示例](https://a.b) 完", caret = 0)
        assertEquals("看 示例 完", projection.text)
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.Link && it.start == 2 && it.end == 4 })
    }

    @Test
    fun inlineCodeIsRecordedAsAChip() {
        val projection = project("跑 `val x = 1` 完", caret = 0)
        assertEquals("跑 val x = 1 完", projection.text)
        assertEquals(listOf(2..10), projection.chips)
    }

    @Test
    fun mappingKeepsTheCaretOutOfHiddenMarkers() {
        val projection = project("a **b** c", caret = 0)
        assertEquals("a b c", projection.text)
        assertEquals(2, projection.mapping.originalToTransformed(4))
        assertEquals(4, projection.mapping.transformedToOriginal(2))
        assertEquals(5, projection.mapping.originalToTransformed(9))
        assertEquals(5, projection.text.length)
    }

    @Test
    fun caretKindFollowsTheLineTheCaretSitsOn() {
        assertEquals(DocumentLineKind.Paragraph, project("正文\n- 列表", caret = 0).caretKind)
        assertEquals(DocumentLineKind.BulletItem, project("正文\n- 列表", caret = 5).caretKind)
        val fence = "正文\n```\ncode\n```"
        assertEquals(DocumentLineKind.CodeFence, project(fence, caret = fence.length - 1).caretKind)
        assertEquals(DocumentLineKind.CodeContent, project(fence, caret = 8).caretKind)
    }

    @Test
    fun orderedItemKeepsItsNumberAndIsReportedAsAList() {
        val body = "1. 一\n2. 二"
        val projection = project(body, caret = body.length - 1)
        assertEquals(body, projection.text)
        assertEquals(DocumentLineKind.OrderedItem, projection.caretKind)
        assertTrue(projection.syntax.isEmpty())
    }

    @Test
    fun markersOnLaterLinesUseAbsoluteOffsets() {
        // 回归：行首标记曾经按「行内相对下标」记，`replacements[0]` 会把正文第一个字符换成圆点。
        val body = "正文\n## 标题\n- 列表"
        val projection = project(body, caret = 0)
        assertEquals("正文\n标题\n• 列表", projection.text)
        assertTrue(hiddenContains(projection, 3..5))
        assertEquals(listOf(3..4 to 2), projection.headings)
        assertEquals(DocumentLineKind.Paragraph, projection.caretKind)
    }


    // ---- 回车行为（breakParagraphAt / splitListLineAt）：用户报过「行首回车没反应」「回车后字接在原文后面」 ----

    private fun typed(value: TextFieldValue, text: String): String {
        val caret = value.selection.min
        return value.text.substring(0, caret) + text + value.text.substring(caret)
    }

    @Test
    fun enterAtTheStartOfAParagraphCreatesAWholeEmptyParagraphAbove() {
        val body = "# 标题\n\n正文"
        val updated = breakParagraphAt(TextFieldValue(body, TextRange(6)))
        assertEquals("# 标题\n\n\n\n正文", updated.text)
        assertEquals(6, updated.selection.min)
        // 打一个字应该自成一整段，而不是和下段粘在一起（`\n` 单换行在 Markdown 里是同段软换行）。
        assertEquals("# 标题\n\n新\n\n正文", typed(updated, "新"))
    }

    @Test
    fun enterAtTheStartOfTheDocumentKeepsTheNewTextAsItsOwnParagraph() {
        val updated = breakParagraphAt(TextFieldValue("正文", TextRange(0)))
        assertEquals("\n\n正文", updated.text)
        assertEquals(0, updated.selection.min)
        assertEquals("新\n\n正文", typed(updated, "新"))
    }

    @Test
    fun enterAfterASoftBreakStillSeparatesParagraphs() {
        val updated = breakParagraphAt(TextFieldValue("上\n下", TextRange(2)))
        assertEquals("上\n\n\n\n下", updated.text)
        assertEquals("上\n\n新\n\n下", typed(updated, "新"))
    }

    @Test
    fun enterInTheMiddleOfALineSplitsIntoTwoParagraphs() {
        val updated = breakParagraphAt(TextFieldValue("前半后半", TextRange(2)))
        assertEquals("前半\n\n后半", updated.text)
        assertEquals(4, updated.selection.min)
    }

    @Test
    fun enterAtTheEndAppendsATrailingParagraph() {
        val updated = breakParagraphAt(TextFieldValue("正文", TextRange(2)))
        assertEquals("正文\n\n", updated.text)
        assertEquals(4, updated.selection.min)
    }

    @Test
    fun enterAtTheEndOfAListItemContinuesTheList() {
        val value = TextFieldValue("- 一", TextRange("- 一".length))
        val updated = splitListLineAt(value, scanDocumentLines(value.text))
        assertEquals("- 一\n- ", updated?.text)
        assertEquals(6, updated?.selection?.min)
    }

    @Test
    fun enterInTheMiddleOfAListItemSplitsIt() {
        val value = TextFieldValue("- 一二", TextRange(3))
        val updated = splitListLineAt(value, scanDocumentLines(value.text))
        assertEquals("- 一\n- 二", updated?.text)
    }

    @Test
    fun enterAtTheStartOfAListItemOpensAnItemAbove() {
        val value = TextFieldValue("- 一", TextRange(0))
        val updated = splitListLineAt(value, scanDocumentLines(value.text))
        assertEquals("- \n- 一", updated?.text)
        assertEquals(2, updated?.selection?.min)
    }

    @Test
    fun enterAtTheStartOfAnOrderedItemRenumbersTheCurrentOne() {
        val value = TextFieldValue("2. 二", TextRange(0))
        val updated = splitListLineAt(value, scanDocumentLines(value.text))
        assertEquals("2. \n3. 二", updated?.text)
        assertEquals(3, updated?.selection?.min)
    }

    @Test
    fun paragraphBreakLeavesPlainParagraphsAlone() {
        val value = TextFieldValue("正文", TextRange(1))
        assertNull(splitListLineAt(value, scanDocumentLines(value.text)))
    }

    @Test
    fun aiInlineFoldsTheBracesAndQuotesTheContent() {
        val projection = project("补充 {{先检索再回答}} 完。", caret = 0)
        assertEquals("补充 “先检索再回答” 完。", projection.text)
        assertTrue(hiddenContains(projection, 3..4))
        assertTrue(hiddenContains(projection, 11..12))
        assertTrue(projection.marks.any { it.kind == InlineMarkKind.AiInline })
        assertEquals(2, projection.marks.count { it.kind == InlineMarkKind.AiQuote })
    }

    @Test
    fun aiInlineShowsTheBracesWhenTheCaretIsInside() {
        val body = "补充 {{先检索再回答}} 完。"
        val projection = project(body, caret = 6)
        assertEquals(body, projection.text)
        assertTrue(projection.hidden.isEmpty())
        assertTrue(projection.syntax.any { it == 3..4 })
        assertTrue(projection.marks.none { it.kind == InlineMarkKind.AiQuote })
    }

    @Test
    fun paragraphsGroupAdjacentListsAndSeparateHeadings() {
        val projection = project("# 标题\n\n- 一\n- 二\n\n1. 甲\n1. 乙\n\n普通段落", caret = 0)
        assertEquals(listOf("H1", "•", "☰", "¶"), projection.paragraphs.map { it.marker })
        assertEquals("H1", projection.caretParagraph?.marker)
    }

    @Test
    fun caretInsideAListMarksTheWholeListAsOneParagraph() {
        val body = "- 一\n- 二\n- 三"
        val projection = project(body, caret = body.indexOf("二"))
        assertEquals("•", projection.caretParagraph?.marker)
    }

    @Test
    fun blankLinesStayBetweenParagraphs() {
        val projection = project("一\n\n二", caret = 0)
        assertEquals("一\n\n二", projection.text)
        assertTrue(projection.paragraphs.size == 2)
    }

    @Test
    fun newParagraphAtTheEndAddsTheBlankLineAndKeepsTheCaretOnANewLine() {
        val fromText = newParagraphAtEnd(TextFieldValue("正文", TextRange(2)))
        assertEquals("正文\n\n", fromText.text)
        assertEquals(4, fromText.selection.min)

        val fromSingle = newParagraphAtEnd(TextFieldValue("正文\n", TextRange(3)))
        assertEquals("正文\n\n", fromSingle.text)
        assertEquals(4, fromSingle.selection.min)

        val alreadyDivided = newParagraphAtEnd(TextFieldValue("正文\n\n", TextRange(4)))
        assertEquals("正文\n\n", alreadyDivided.text)
        assertEquals(4, alreadyDivided.selection.min)
    }

}
