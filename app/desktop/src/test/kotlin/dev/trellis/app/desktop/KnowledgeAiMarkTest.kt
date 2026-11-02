package dev.trellis.app.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI 补全约定（`docs/knowledge-base-draft.md` §11）的纯函数测试。
 *
 * 约定三件事：
 *  - 用户写 `标签：。。。` 表示留了空位，只认行尾、最多 3 个；
 *  - 模型补出来的内容包成 `{{…}}` 写回正文，其它字不动、补完不重复；
 *  - 预览里 `{{…}}` 画成大绿引号，`:::ai` 块画成带 × 的 AI 内容，概念名绿色高亮。
 */
class KnowledgeAiMarkTest {

    // ---- 槽位识别 ----

    @Test
    fun slotRequiresLabelAndMarkerAtLineEnd() {
        assertEquals("RAG", aiFillSlotLabel("RAG：。。。"))
        assertEquals("向量检索", aiFillSlotLabel("- 向量检索：。。。"))
        assertEquals("GraphRAG", aiFillSlotLabel("GraphRAG: 。。。"))
        assertNull(aiFillSlotLabel("RAG：。。。 但是后面还有正文"))
        assertNull(aiFillSlotLabel("。。。"))
    }

    @Test
    fun slotsSkipCodeFenceAndAiBlock() {
        val body = """
            RAG：。。。

            ```
            BM25：。。。
            ```

            :::ai
            RRF：。。。
            :::
        """.trimIndent()
        val slots = findAiFillSlots(body)
        assertEquals(listOf("RAG"), slots.map { it.label })
    }

    @Test
    fun slotsDedupeAndCapAtThree() {
        val body = """
            RAG：。。。
            BM25：。。。
            向量检索：。。。
            RRF：。。。
            HYDE：。。。
        """.trimIndent()
        val slots = findAiFillSlots(body)
        assertEquals(3, slots.size)
        assertEquals(listOf("RAG", "BM25", "向量检索"), slots.map { it.label })
    }

    // ---- 写回正文 ----

    @Test
    fun applyFillsOnlyReplacesTheMarker() {
        val body = """
            # 检索
            先写一句自己的话。

            RAG：。。。
            结尾不动。
        """.trimIndent()
        val result = applyAiFills(body, mapOf("RAG" to "检索增强生成，先找证据再让模型回答"))
        assertEquals(listOf("RAG"), result.applied)
        assertTrue(result.body.contains("RAG：{{检索增强生成，先找证据再让模型回答}}"))
        assertTrue(result.body.contains("先写一句自己的话。"))
        assertTrue(result.body.endsWith("结尾不动。"))
    }

    @Test
    fun applyFillsIsIdempotentAndSkipsUnknownLabels() {
        val filled = "RAG：{{检索增强生成}}\nBM25：。。。"
        assertTrue(applyAiFills(filled, mapOf("RAG" to "别再补了")).applied.isEmpty())
        assertEquals(filled, applyAiFills(filled, mapOf("RAG" to "别再补了")).body)

        val untouched = "BM25：。。。"
        val skipped = applyAiFills(untouched, mapOf("RAG" to "不是这行"))
        assertTrue(skipped.applied.isEmpty())
        assertEquals(untouched, skipped.body)
    }

    @Test
    fun applyFillsTruncatesLongTextAndSkipsBlank() {
        val body = "RAG：。。。"
        val long = applyAiFills(body, mapOf("RAG" to "字".repeat(MAX_AI_FILL_CHARS + 20)))
        assertTrue(long.body.contains("{{" + "字".repeat(MAX_AI_FILL_CHARS) + "}}"))
        assertFalse(long.body.contains("字".repeat(MAX_AI_FILL_CHARS + 1)))

        assertTrue(applyAiFills(body, mapOf("RAG" to "   ")).applied.isEmpty())
    }

    // ---- 删除 AI 内容 ----

    @Test
    fun removeAiBlockLineDeletesOnlyInsideAiBlock() {
        val body = """
            正文第一行
            :::ai
            - 检索增强生成：先找证据
            - BM25：稀疏检索打分
            :::
            正文最后一行
        """.trimIndent()
        val next = removeAiBlockLine(body, "检索增强生成：先找证据")
        assertTrue(next != null)
        assertTrue(!next!!.contains("检索增强生成"))
        assertTrue(next.contains("BM25：稀疏检索打分"))
        assertTrue(next.contains(":::ai"))

        // 正文里有一样的一行，但不是 AI 块里的，不能删。
        val plain = "检索增强生成：先找证据"
        assertNull(removeAiBlockLine(plain, "检索增强生成：先找证据"))
    }

    @Test
    fun removeAiBlockLineDropsEmptyShell() {
        val body = "前\n:::ai\n只剩这一条\n:::\n后"
        assertEquals("前\n后", removeAiBlockLine(body, "只剩这一条"))
    }

    @Test
    fun removeAiInlineDeletesTheWholeWrappedSpan() {
        assertEquals("RAG：", removeAiInline("RAG：{{先找证据}}", "先找证据"))
        assertNull(removeAiInline("RAG：先找证据", "先找证据"))
    }

    // ---- 解析模型输出 ----

    @Test
    fun parseExtractionResultReadsConceptsAndFills() {
        val raw = """
            ```json
            {"concepts":[{"name":"RAG","definition":"检索增强生成"},{"name":"BM25","definition":""}],
             "fills":[{"label":"向量检索","text":"把文本变成向量再找相似"}]}
            ```
        """.trimIndent()
        val result = parseExtractionResult(raw)
        assertEquals(listOf("RAG", "BM25"), result.candidates.map { it.name })
        assertEquals(1, result.fills.size)
        assertEquals("向量检索", result.fills[0].label)
        assertEquals("把文本变成向量再找相似", result.fills[0].text)
    }

    @Test
    fun parseExtractionResultFallsBackToOldArrayFormat() {
        val result = parseExtractionResult("""[{"name":"RRF","definition":"融合排序"}]""")
        assertEquals(listOf("RRF"), result.candidates.map { it.name })
        assertTrue(result.fills.isEmpty())
    }

    @Test
    fun parseExtractionResultToleratesBrokenOutput() {
        assertTrue(parseExtractionResult("模型今天不想输出 JSON").candidates.isEmpty())
        assertTrue(parseExtractionResult("{坏对象").candidates.isEmpty())
    }

    @Test
    fun extractionPromptMentionsSlotsAndFillsFormat() {
        val messages = buildKnowledgeExtractionMessages("检索笔记", "RAG：。。。", emptyList(), listOf("RAG"))
        assertEquals(2, messages.size)
        assertEquals(LlmRole.System, messages[0].role)
        assertTrue(messages[0].content.contains(AI_SLOT_MARKER))
        assertTrue(messages[1].content.contains("RAG"))
        assertTrue(messages[1].content.contains("fills"))
        assertTrue(messages[1].content.contains("\"concepts\""))
    }

    @Test
    fun oldConceptsOnlyPromptStillWorks() {
        val messages = buildConceptExtractionMessages("标题", "正文", emptyList())
        assertFalse(messages[1].content.contains("空位（整行是"))
    }

    // ---- 渲染 ----

    @Test
    fun parseMarkdownTurnsAiBlockIntoTypedBlock() {
        val blocks = parseMarkdown(":::ai\n- 第一条\n- 第二条\n:::")
        assertEquals(1, blocks.size)
        val block = blocks[0] as MdBlock.AiBlock
        assertEquals(listOf("第一条", "第二条"), block.items)
    }

    @Test
    fun unclosedAiBlockStillKeepsContent() {
        val blocks = parseMarkdown(":::ai\n只有一行\n")
        assertEquals(listOf("只有一行"), (blocks[0] as MdBlock.AiBlock).items)
    }

    @Test
    fun aiMarkerInsideCodeFenceIsNotABlock() {
        val blocks = parseMarkdown("```\n:::ai\n```")
        assertTrue(blocks.none { it is MdBlock.AiBlock })
    }

    @Test
    fun inlineAiContentRendersAsLargeGreenQuotes() {
        val rendered = inlineMarkdown("RAG：{{检索增强生成}}")
        assertFalse(rendered.text.contains("{{"))
        assertFalse(rendered.text.contains("}}"))
        assertTrue(rendered.text.contains("\u201C检索增强生成\u201D"))
        assertTrue(rendered.spanStyles.any { it.item.fontSize.value > 20f })
    }

    @Test
    fun inlineSpansFindMultipleAndTolerateUnclosed() {
        val spans = aiInlineSpans("a{{一}}b{{二}}c")
        assertEquals(listOf("一", "二"), spans.map { it.content })
        assertTrue(aiInlineSpans("未闭合 {{ 就到这里").isEmpty())
        assertTrue(aiInlineSpans("{{}}").isEmpty())
    }

    @Test
    fun conceptsAreHighlightedWithWordBoundary() {
        val highlighted = inlineMarkdown("先说 RAG，再提 GraphRAG。", listOf("RAG"))
        // 普通出现只染色、不加下划线；标注过的 `[[…]]` 才带下划线（用例见 MarkdownTextTest）。
        val ranges = highlighted.spanStyles.filter { it.item.color != Color.Unspecified }
        assertEquals(1, ranges.size)
        assertEquals("RAG", highlighted.text.substring(ranges[0].start, ranges[0].end))
        assertTrue(highlighted.spanStyles.none { it.item.textDecoration == TextDecoration.Underline })
    }

    // ---- 概念标记 [[…]] ----

    @Test
    fun markConceptMentionsWrapsTheFirstPlainMention() {
        val body = "RAG 是检索增强。后面又提到 RAG。"
        assertEquals("[[RAG]] 是检索增强。后面又提到 RAG。", markConceptMentions(body, listOf("RAG")))
    }

    @Test
    fun markConceptMentionsSkipsCodeAiBlocksAndMissingNames() {
        val body = "普通 RAG 段落\n```\nRAG\n```\n:::ai\nRAG\n:::"
        val marked = markConceptMentions(body, listOf("RAG", "BM25"))
        assertEquals("普通 [[RAG]] 段落\n```\nRAG\n```\n:::ai\nRAG\n:::", marked)
    }

    @Test
    fun markConceptMentionsDoesNotNestOrRepeat() {
        assertEquals("[[RAG]] 已标过", markConceptMentions("[[RAG]] 已标过", listOf("RAG")))
        assertEquals("{{RAG}} 是补全", markConceptMentions("{{RAG}} 是补全", listOf("RAG")))
    }

    @Test
    fun conceptSyntaxRendersOnlyTheName() {
        val rendered = inlineMarkdown("用 [[RAG]] 做检索", conceptGreen = MarkdownAiGreen)
        assertEquals("用 RAG 做检索", rendered.text)
        val underline = rendered.spanStyles.filter { it.item.textDecoration == TextDecoration.Underline }
        assertEquals(1, underline.size)
        assertEquals("RAG", rendered.text.substring(underline[0].start, underline[0].end))
    }

    @Test
    fun extractionPromptRaisesTheBarForConcepts() {
        val messages = buildKnowledgeExtractionMessages("检索笔记", "RAG 是检索增强生成。", emptyList(), emptyList())
        val prompt = messages[1].content
        assertTrue(prompt.contains("正文依据"))
        assertTrue(prompt.contains("逐字出现在正文里"))
        assertTrue(prompt.contains("数量不设上限"))
    }

    @Test
    fun extractionDoesNotCapTheNumberOfConcepts() {
        val concepts = (1..20).joinToString(",") { "{\"name\":\"概念$it\",\"definition\":\"定义$it\"}" }
        val result = parseExtractionResult("{\"concepts\":[$concepts],\"fills\":[]}")
        assertEquals(20, result.candidates.size)
    }

    // ---- 焦点离开时「正文有没有真的变过」 ----

    @Test
    fun bodyForAiComparisonIgnoresConceptMarkers() {
        assertEquals(
            bodyForAiComparison("用 RAG 做检索"),
            bodyForAiComparison("用 [[RAG]] 做检索"),
        )
    }

    @Test
    fun bodyForAiComparisonIgnoresAiFillsAndAiBlocks() {
        val plain = "标题\n\n答案："
        assertEquals(bodyForAiComparison(plain), bodyForAiComparison("标题\n\n答案：{{一句 AI 补全}}"))
        val withBlock = "标题\n\n:::ai\nAI 写的内容\n第二行\n:::\n\n答案："
        assertEquals(bodyForAiComparison(plain), bodyForAiComparison(withBlock))
    }

    @Test
    fun bodyForAiComparisonSeesRealContentEdits() {
        assertNotEquals(
            bodyForAiComparison("RAG 是检索增强"),
            bodyForAiComparison("RAG 是检索增强生成"),
        )
    }

    @Test
    fun normalizeFillTextKeepsItOneLine() {
        assertEquals("一句话 另一句", normalizeAiFillText(" 一句话\n另一句 "))
        assertEquals("别套娃", normalizeAiFillText("{{别套娃}}"))
        assertEquals(MAX_AI_FILL_CHARS, normalizeAiFillText("很长".repeat(200)).length)
    }
}
