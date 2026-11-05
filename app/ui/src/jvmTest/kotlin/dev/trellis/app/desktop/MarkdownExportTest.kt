package dev.trellis.app.desktop

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「导出 Markdown」的纯函数测试：导出的文件要能直接给别的工具看。
 *
 * 三条约定（见 `docs/knowledge-base-draft.md` §12）：
 * 1. 不带 frontmatter；标题只出现一次（正文已有同名标题就不再加）；
 * 2. `{{…}}` 去掉花括号、`:::ai` 围栏行去掉，代码块里原样保留；
 * 3. 文件名从标题来，非法字符清掉。
 */
class MarkdownExportTest {

    private val nowMs: Long = Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()

    private fun note(title: String, body: String): KnowledgeNote = KnowledgeNote(
        id = "n_export",
        title = title,
        body = body,
        createdAt = nowMs,
        updatedAt = nowMs,
    )

    @Test
    fun exportStartsWithTheTitleAndHasNoFrontMatter() {
        val markdown = exportNoteMarkdown(note("RAG 检索增强", "RAG 的思路是先检索证据。"))
        assertEquals("# RAG 检索增强\n\nRAG 的思路是先检索证据。\n", markdown)
        assertFalse(markdown.startsWith("---"))
    }

    @Test
    fun exportDoesNotRepeatATitleThatIsAlreadyTheFirstHeading() {
        val markdown = exportNoteMarkdown(note("RAG 检索增强", "# **RAG** 检索增强\n\n正文"))
        assertEquals("# **RAG** 检索增强\n\n正文\n", markdown)
    }

    @Test
    fun exportKeepsTheTitleWhenBodyStartsWithAnotherHeading() {
        val markdown = exportNoteMarkdown(note("长笔记", "## 公式示例\n\n正文"))
        assertTrue(markdown.startsWith("# 长笔记\n\n## 公式示例"))
    }

    @Test
    fun exportUnwrapsInlineAiContent() {
        val markdown = exportNoteMarkdown(note("RAG", "RAG 的思路是{{先检索证据，再回答}}。"))
        assertEquals("# RAG\n\nRAG 的思路是先检索证据，再回答。\n", markdown)
    }

    @Test
    fun exportDropsAiBlockFencesButKeepsTheirContent() {
        val body = "前言\n\n:::ai\n- 检索增强生成：先找证据\n- 关键词检索：BM25\n:::\n\n后记"
        val markdown = exportNoteMarkdown(note("AI 演示", body))
        assertTrue(markdown.contains("- 检索增强生成：先找证据"))
        assertFalse(markdown.contains(":::ai"))
        assertFalse(markdown.contains("\n:::\n"))
    }

    @Test
    fun exportLeavesCodeFencesUntouched() {
        val body = "```kotlin\nval x = \"{{不处理}}\" // :::ai\n```\n\n正文 {{要处理}}"
        val markdown = exportNoteMarkdown(note("代码", body))
        assertTrue(markdown.contains("val x = \"{{不处理}}\" // :::ai"))
        assertTrue(markdown.contains("正文 要处理"))
    }

    @Test
    fun exportFileNameCleansUnsafeCharacters() {
        assertEquals("RAG 检索 增强.md", markdownExportFileName("RAG/检索:增强?"))
        assertEquals("无标题笔记.md", markdownExportFileName("   "))
        assertTrue(markdownExportFileName("a".repeat(200)).length <= 80 + ".md".length)
    }

    @Test
    fun exportBodyStaysMarkdown() {
        val body = "# 标题\n\n**加粗**、`代码`、[链接](https://example.com)、- 列表"
        val markdown = exportNoteMarkdown(note("标题", body))
        assertEquals("# 标题\n\n**加粗**、`代码`、[链接](https://example.com)、- 列表\n", markdown)
    }
}
