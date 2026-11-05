package dev.trellis.app.desktop

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 笔记导入的测试：认后缀、标题去重、以及真的把外部文件读成笔记。
 *
 * 用临时目录 + 固定时钟，不碰用户真实数据（`~/.trellis`）。
 */
class MarkdownImportTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private val nowMs: Long = Instant.parse("2026-11-05T10:20:00Z").toEpochMilli() // 18:20 +08:00

    private fun clock(): TrellisClock = object : TrellisClock {
        override val zone: ZoneId = this@MarkdownImportTest.zone
        override fun nowMs(): Long = this@MarkdownImportTest.nowMs
        override fun monotonicMs(): Long = this@MarkdownImportTest.nowMs
        override fun today(): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        override fun isoWeek(): String = isoWeekOf(today())
        override fun simulatedDate(): LocalDate? = null
    }

    private fun tempHome(): File {
        val dir = File.createTempFile("trellis-import-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun importFile(home: File, name: String, text: String): File {
        val source = File(home, "incoming")
        source.mkdirs()
        return File(source, name).apply { writeText(text, Charsets.UTF_8) }
    }

    @Test
    fun acceptsMarkdownAndTextFileNames() {
        assertTrue(isMarkdownFileName("a.md"))
        assertTrue(isMarkdownFileName("A.MD"))
        assertTrue(isMarkdownFileName("a.markdown"))
        assertTrue(isMarkdownFileName("a.txt"))
        assertFalse(isMarkdownFileName("a.pdf"))
        assertFalse(isMarkdownFileName("README"))
    }

    @Test
    fun uniqueTitleAppendsSuffixOnlyWhenTaken() {
        assertEquals("读书笔记", uniqueNoteTitle("读书笔记", emptySet()))
        assertEquals("读书笔记 (2)", uniqueNoteTitle("读书笔记", setOf("读书笔记")))
        assertEquals(
            "读书笔记 (3)",
            uniqueNoteTitle("读书笔记", setOf("读书笔记", "读书笔记 (2)")),
        )
    }

    @Test
    fun blankTitleFallsBackToPlaceholder() {
        assertEquals("无标题笔记", uniqueNoteTitle("   ", emptySet()))
        assertEquals("无标题笔记 (2)", uniqueNoteTitle("", setOf("无标题笔记")))
    }

    @Test
    fun importPrefersFrontMatterTitleThenHeadingThenFileName() {
        val home = tempHome()
        val store = KnowledgeStore(home, clock())
        val withFrontMatter = importFile(
            home,
            "a.md",
            "---\ntitle: 前端标题\ncreated: 2026-01-01T00:00:00+08:00\n---\n\n正文 A\n",
        )
        val withHeading = importFile(home, "b.md", "# 正文标题\n\n正文 B\n")
        val plain = importFile(home, "c.md", "只有正文 C\n")

        val imported = store.importNotes(listOf(withFrontMatter, withHeading, plain))

        assertEquals(3, imported.size)
        assertEquals(listOf("前端标题", "正文标题", "c"), imported.map { it.title })
        assertEquals("正文 A", imported[0].body.trim())
        // 标题取自正文第一个 `# ` 时，标题行本身仍留在正文里（不替用户删内容）。
        assertTrue(imported[1].body.contains("正文 B"))
        assertTrue(imported[2].body.contains("只有正文 C"))
    }

    @Test
    fun importStripsBomAndKeepsBodyText() {
        val home = tempHome()
        val store = KnowledgeStore(home, clock())
        val bom = importFile(home, "bom.md", "\uFEFF# 带 BOM\n\n第一段\n")

        val imported = store.importNotes(listOf(bom))

        assertEquals(1, imported.size)
        val note = assertNotNull(store.read(imported.first().id))
        assertEquals("带 BOM", note.title)
        assertFalse(note.body.startsWith("\uFEFF"))
        assertTrue(note.body.contains("第一段"))
    }

    @Test
    fun importDedupesTitlesAndSkipsMissingFiles() {
        val home = tempHome()
        val store = KnowledgeStore(home, clock())
        val first = importFile(home, "same1.md", "# 同名\n\n第一篇\n")
        val second = importFile(home, "same2.md", "# 同名\n\n第二篇\n")
        val missing = File(home, "不存在.md")

        val imported = store.importNotes(listOf(first, second, missing))

        assertEquals(2, imported.size)
        assertEquals(listOf("同名", "同名 (2)"), imported.map { it.title })
    }
}
