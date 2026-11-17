package dev.trellis.app.desktop

import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 全角/半角符号一致性（2026-11-16 用户要求）：
 *
 * 「看起来一样的符号，中英文输入法下行为一样」——中文输入法打出的 ［［ 概念 ］］、｛｛AI 内容｝｝、
 * ＃ 标题、－ 列表、：：：ai 必须和半角写法效果一致；统一在载入时归一半角（写回文件也是半角），
 * 正文里单个全角标点不动。程序不主动加转义符（见 markdown.js 的 escapeMarkdownText）。
 */
class KnowledgeSymbolNormalizationTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private val shuxue = ConceptIdentity("线性代数", listOf("线代"))

    @Test
    fun fullwidthConceptBracketsBecomeAnnotations() {
        val normalized = normalizeSyntaxSymbols("先看 ［［线性代数］］，再看 ［［原文 ＝＞ 线性代数］］。")
        assertEquals("先看 [[线性代数]]，再看 [[原文 => 线性代数]]。", normalized)
        val annotations = parseConceptAnnotations(normalized)
        assertEquals(2, annotations.size)
        assertEquals("线性代数", annotations[0].display)
        assertEquals("原文", annotations[1].display)
        assertEquals("线性代数", annotations[1].target)
    }

    @Test
    fun mixedWidthBracketsAlsoCountAsSyntax() {
        assertEquals("[[线代]]", normalizeSyntaxSymbols("［[线代]］"))
    }

    @Test
    fun fullwidthAiInlineBracesNormalized() {
        assertEquals("内容 {{自动补全的内容}} 结束。", normalizeSyntaxSymbols("内容 ｛｛自动补全的内容｝｝ 结束。"))
    }

    @Test
    fun fullwidthAiBlockMarkerNormalized() {
        val body = "：：：ai\n这是 AI 块\n：：：\n\n正文"
        assertEquals(":::ai\n这是 AI 块\n:::\n\n正文", normalizeSyntaxSymbols(body))
    }

    @Test
    fun fullwidthBlockMarkersNormalized() {
        val body = "＃ 标题\n－ 一\n＊ 二\n＋ 三\n＞ 引用\n１２． 十二\n｀｀｀kotlin\nval x = 1\n｀｀｀"
        val expected = "# 标题\n- 一\n- 二\n- 三\n> 引用\n12. 十二\n```kotlin\nval x = 1\n```"
        assertEquals(expected, normalizeSyntaxSymbols(body))
    }

    @Test
    fun proseFullwidthPunctuationStaysUntouched() {
        val body = "（见［1］）以及 －、＊、＞ 这些标点在正文里原样保留。"
        assertEquals(body, normalizeSyntaxSymbols(body))
    }

    @Test
    fun fenceContentIsNeverRewritten() {
        val body = "```\n＃ 不是标题\n［［不是概念］］\n｛｛不是 AI｝｝\n```"
        assertEquals(body, normalizeSyntaxSymbols(body))
    }

    @Test
    fun inlineCodeContentIsNeverRewritten() {
        assertEquals("`［［x］］` 与 [[y]]", normalizeSyntaxSymbols("`［［x］］` 与 ［［y］］"))
    }

    @Test
    fun normalizationIsIdempotent() {
        val once = normalizeSyntaxSymbols("＃ 标题\n［［线代］］ 和 ｛｛内容｝｝\n－ 列表")
        assertEquals(once, normalizeSyntaxSymbols(once))
    }

    @Test
    fun parseNoteMarkdownNormalizesBodyAndTitle() {
        val raw = "---\ntitle: 我的笔记\n---\n\n＃ 全角标题\n\n［［线性代数］］ 和 ｛｛补全｝｝。\n"
        val note = parseNoteMarkdown("n_test", raw, zone, 0L)
        assertTrue(note.body.contains("# 全角标题"), note.body)
        assertTrue(note.body.contains("[[线性代数]]"), note.body)
        assertTrue(note.body.contains("{{补全}}"), note.body)
        val annotations = parseConceptAnnotations(note.body)
        assertEquals(1, annotations.size)
        assertEquals("线性代数", conceptAnnotationName(annotations[0], listOf(shuxue)))
    }
}
