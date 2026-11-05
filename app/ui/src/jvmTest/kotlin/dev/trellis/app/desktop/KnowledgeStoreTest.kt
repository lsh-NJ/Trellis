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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 笔记文件层的测试：文件格式、时间戳、排序、删除、导入兼容。
 *
 * 知识库的第一条硬规矩是「文件是真相」——所以这里测的就是「文件写出来长什么样、
 * 还能不能原样读回来」，而不是界面。时间用固定时钟，不碰系统时间。
 */
class KnowledgeStoreTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private var nowMs: Long = Instant.parse("2026-10-30T06:00:00Z").toEpochMilli() // 14:00 +08:00

    private fun clock(): TrellisClock = object : TrellisClock {
        override val zone: ZoneId = this@KnowledgeStoreTest.zone
        override fun nowMs(): Long = nowMs
        override fun monotonicMs(): Long = nowMs
        override fun today(): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        override fun isoWeek(): String = isoWeekOf(today())
        override fun simulatedDate(): LocalDate? = null
    }

    private fun tempHome(): File {
        val dir = File.createTempFile("trellis-knowledge-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    @Test
    fun createWritesMarkdownFileWithFrontMatter() {
        val store = KnowledgeStore(tempHome(), clock())
        val note = store.create()

        val file = File(store.notesDir, note.id + ".md")
        assertTrue(file.isFile, "笔记应该是 knowledge/notes/<id>.md")
        val raw = file.readText(Charsets.UTF_8)
        assertTrue(raw.startsWith("---\n"), "应带 frontmatter，实际：$raw")
        assertTrue(raw.contains("updated: 2026-10-30T14:00:00+08:00"))

        val read = assertNotNull(store.read(note.id))
        assertEquals("", read.title)
        assertEquals("", read.body)
        assertEquals(note.createdAt, read.createdAt)
    }

    @Test
    fun saveAndReadRoundTripPreservesText() {
        val store = KnowledgeStore(tempHome(), clock())
        val note = store.create()
        nowMs += 60_000

        val body = "# 检索\n\n- BM25\n- 向量\n\n```kotlin\nval a = 1\n```"
        val saved = note.copy(title = "RAG 笔记", body = body, updatedAt = nowMs)
        assertTrue(store.save(saved))

        val read = assertNotNull(store.read(note.id))
        assertEquals("RAG 笔记", read.title)
        assertEquals(body, read.body)
        assertEquals(nowMs, read.updatedAt)
        assertEquals(note.createdAt, read.createdAt)
    }

    @Test
    fun listSortsByUpdatedAtDescending() {
        val store = KnowledgeStore(tempHome(), clock())
        val older = store.create("旧的")
        nowMs += 1_000
        val newer = store.create("新的")

        val list = store.list()
        assertEquals(listOf(newer.id, older.id), list.map { it.id })
        assertEquals("新的", list.first().title)
    }

    @Test
    fun deleteRemovesFileAndReadReturnsNull() {
        val store = KnowledgeStore(tempHome(), clock())
        val note = store.create()
        assertTrue(store.delete(note.id))
        assertFalse(store.exists(note.id))
        assertNull(store.read(note.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun importedMarkdownWithoutFrontMatterIsReadable() {
        val store = KnowledgeStore(tempHome(), clock())
        File(store.notesDir, "n_import.md").writeText("# 我的旧笔记\n\n正文一\n", Charsets.UTF_8)

        val read = assertNotNull(store.read("n_import"))
        assertEquals("我的旧笔记", read.title)
        assertTrue(read.body.contains("正文一"))
    }

    @Test
    fun unknownFrontMatterKeysSurviveReadWriteRoundTrip() {
        val store = KnowledgeStore(tempHome(), clock())
        File(store.notesDir, "n_source.md").writeText(
            """
            ---
            title: 带来源的笔记
            created: 2026-10-30T14:00:00+08:00
            updated: 2026-10-30T14:00:00+08:00
            source: C:\notes\old.md
            ---

            正文
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val note = assertNotNull(store.read("n_source"))
        assertEquals("C:\\notes\\old.md", note.extras["source"])
        assertTrue(store.save(note.copy(title = "改过标题")))

        val again = assertNotNull(store.read("n_source"))
        assertEquals("改过标题", again.title)
        assertEquals("C:\\notes\\old.md", again.extras["source"], "以后的 concepts: 就靠这条不丢")
    }

    @Test
    fun invalidIdCannotEscapeNotesDirectory() {
        val store = KnowledgeStore(tempHome(), clock())
        assertNull(store.read("../store"))
        assertFalse(store.exists(".."))
        assertFalse(store.delete("../x"))
    }
}
