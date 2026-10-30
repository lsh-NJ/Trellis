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
 * 概念层的测试：点亮（frontmatter 读写）、索引/详情/搜索、定义文件、抽取解析。
 *
 * 这些都不碰界面也不碰网络：抽取的「模型输出 → 候选列表」按纯函数测，
 * 索引则从真实写出来的 .md 文件现算——和页面用的是同一条路径。
 */
class KnowledgeConceptTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private var nowMs: Long = Instant.parse("2026-10-30T06:00:00Z").toEpochMilli() // 14:00 +08:00

    private fun clock(): TrellisClock = object : TrellisClock {
        override val zone: ZoneId = this@KnowledgeConceptTest.zone
        override fun nowMs(): Long = nowMs
        override fun monotonicMs(): Long = nowMs
        override fun today(): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        override fun isoWeek(): String = isoWeekOf(today())
        override fun simulatedDate(): LocalDate? = null
    }

    private fun tempHome(): File {
        val dir = File.createTempFile("trellis-knowledge-concepts-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun store(home: File): KnowledgeStore = KnowledgeStore(home, clock())

    // ---- 概念名与 frontmatter ----

    @Test
    fun conceptNamesAreNormalizedAndKeyedCaseInsensitively() {
        assertEquals("RAG 检索", normalizeConceptName("  RAG   检索 "))
        assertEquals("BM25", normalizeConceptName("BM25,"))
        assertEquals("rag 检索", conceptKey("RAG 检索"))
        assertEquals(conceptKey("vector"), conceptKey(" Vector "))
    }

    @Test
    fun splitConceptListAcceptsCommasAndDropsDuplicates() {
        val names = splitConceptList("RAG, 向量检索，BM25; rrf；RAG")
        assertEquals(listOf("RAG", "向量检索", "BM25", "rrf"), names)
        assertEquals("RAG, 向量检索", joinConceptList(listOf("RAG", "向量检索", "rag")))
    }

    @Test
    fun conceptsRoundTripThroughFrontMatter() {
        val home = tempHome()
        val store = store(home)
        val note = store.create("检索笔记").copy(
            body = "BM25 是稀疏检索，向量检索找语义相近。",
            concepts = listOf("BM25", "向量检索"),
            extras = mapOf("source" to "课堂"),
        )
        assertTrue(store.save(note))

        val raw = File(store.notesDir, note.id + ".md").readText()
        assertTrue(raw.contains("concepts: BM25, 向量检索"))
        assertTrue(raw.contains("source: 课堂"))

        val loaded = assertNotNull(store.read(note.id))
        assertEquals(listOf("BM25", "向量检索"), loaded.concepts)
        assertEquals("课堂", loaded.extras["source"])
    }

    @Test
    fun noteWithoutConceptsHasEmptyList() {
        val parsed = parseNoteMarkdown("n_1", "# 标题\n\n正文", zone, 1_000L)
        assertEquals(emptyList(), parsed.concepts)
        // 没有 frontmatter 的导入文件也能读，并在写回时补上字段。
        val text = formatNoteMarkdown(parsed, zone)
        assertFalse(text.contains("concepts:"))
    }

    // ---- 索引 / 详情 / 搜索 ----

    @Test
    fun conceptIndexCountsNotesAndMentions() {
        val store = store(tempHome())
        store.save(store.create("笔记一").copy(body = "BM25 是稀疏检索；BM25 常用。", concepts = listOf("BM25", "RRF")))
        nowMs += 60_000
        store.save(store.create("笔记二").copy(body = "向量检索找语义相近的结果。", concepts = listOf("向量检索")))

        val index = store.conceptIndex()
        assertEquals(setOf("BM25", "RRF", "向量检索"), index.map { it.name }.toSet())
        val bm25 = assertNotNull(index.firstOrNull { it.name == "BM25" })
        assertEquals(1, bm25.noteCount)
        assertEquals(2, bm25.mentionCount)
    }

    @Test
    fun conceptDetailTracesBackToNotesAndRelatedConcepts() {
        val store = store(tempHome())
        val first = store.create("检索笔记").copy(
            body = "先看 BM25，再看 RRF 融合排序。",
            concepts = listOf("BM25", "RRF"),
        )
        store.save(first)
        nowMs += 60_000
        store.save(store.create("第二篇").copy(body = "BM25 也算关键词命中。", concepts = listOf("BM25")))

        val detail = assertNotNull(store.conceptDetail("bm25"))
        assertEquals("BM25", detail.summary.name)
        assertEquals(2, detail.summary.noteCount)
        assertEquals("第二篇", detail.mentions.first().noteTitle, "引用按最近更新排")
        val older = detail.mentions.first { it.noteId == first.id }
        assertTrue(older.start >= 0)
        assertTrue(older.snippet.contains("BM25"))
        assertEquals(listOf("RRF"), detail.related.map { it.name })
    }

    @Test
    fun latinConceptsRespectWordBoundaries() {
        val store = store(tempHome())
        // RAG 不该把 GraphRAG 里那一段也算成自己的命中。
        store.save(store.create("图检索").copy(body = "GraphRAG 全栈在这里，RAG 只做检索。", concepts = listOf("RAG")))
        val detail = assertNotNull(store.conceptDetail("RAG"))
        val mention = detail.mentions.first()
        assertEquals("GraphRAG 全栈在这里，".length, mention.start, "应该命中正文里的 RAG，而不是 GraphRAG 里的片段")
        assertTrue(mention.snippet.contains("RAG 只做检索"))
        assertEquals(1, detail.summary.mentionCount)
    }

    @Test
    fun cjkConceptsStillMatchInsideLongerWords() {
        val store = store(tempHome())
        store.save(store.create("检索笔记").copy(body = "向量检索找语义相近的结果。", concepts = listOf("向量")))
        val detail = assertNotNull(store.conceptDetail("向量"))
        assertEquals(0, detail.mentions.first().start)
        assertEquals(1, detail.summary.mentionCount)
    }

    @Test
    fun searchFindsConceptsAndNoteText() {
        val store = store(tempHome())
        store.save(store.create("检索笔记").copy(body = "BM25 与向量检索", concepts = listOf("BM25")))

        val byConcept = store.search("BM")
        assertEquals(listOf("BM25"), byConcept.concepts.map { it.name })
        assertTrue(byConcept.notes.any { it.title == "检索笔记" })

        val byBody = store.search("向量")
        assertTrue(byBody.notes.any { it.title == "检索笔记" })
        assertTrue(store.search("不存在的词").concepts.isEmpty())
        assertTrue(store.search("不存在的词").notes.isEmpty())
    }

    // ---- 定义文件 ----

    @Test
    fun conceptDefinitionsPersistAndDoNotOverwriteUserText() {
        val home = tempHome()
        val meta = ConceptMetaStore(home)

        assertTrue(meta.saveDefinition("BM25", "稀疏检索的经典打分。"))
        assertEquals("稀疏检索的经典打分。", meta.definition("bm25"))

        // AI 补全只填空的，不覆盖用户已经写好的。
        meta.fillMissing(mapOf("BM25" to "AI 的定义", "RRF" to "融合排序"))
        assertEquals("稀疏检索的经典打分。", meta.definition("BM25"))
        assertEquals("融合排序", meta.definition("RRF"))

        // 空定义等价于删除。
        assertTrue(meta.saveDefinition("RRF", "   "))
        assertNull(meta.definition("RRF"))
    }

    @Test
    fun brokenDefinitionFileIsTolerated() {
        val home = tempHome()
        File(File(home, "knowledge"), "concepts.json").apply {
            parentFile.mkdirs()
            writeText("{ 这不是 JSON")
        }
        val meta = ConceptMetaStore(home)
        assertEquals(emptyMap(), meta.load())
        assertTrue(meta.saveDefinition("RAG", "检索增强生成"))
    }

    // ---- 抽取（提示词 + 解析） ----

    @Test
    fun parseCandidatesAcceptsFencedAndChattyOutput() {
        val raw = """
            好的，下面是整理结果：
            ```json
            [
              {"name": "BM25", "definition": "稀疏检索的打分方法"},
              {"name": "向量检索", "definition": "把文本编码成向量再找相似"},
              {"name": " bm25 ", "definition": "重复项"}
            ]
            ```
        """.trimIndent()
        val candidates = parseConceptCandidates(raw)
        assertEquals(listOf("BM25", "向量检索"), candidates.map { it.name })
        assertEquals("稀疏检索的打分方法", candidates.first().definition)
    }

    @Test
    fun parseCandidatesReturnsEmptyOnBrokenOutput() {
        assertTrue(parseConceptCandidates("模型今天不想输出 JSON").isEmpty())
        assertTrue(parseConceptCandidates("[{").isEmpty())
        assertTrue(parseConceptCandidates("").isEmpty())
    }

    @Test
    fun mergeKeepsExistingOrderAndDropsDuplicateKeys() {
        val merged = mergeConceptNames(
            existing = listOf("BM25", "向量检索"),
            candidates = listOf(ConceptCandidate("bm25", ""), ConceptCandidate("RRF", "融合排序")),
        )
        assertEquals(listOf("BM25", "向量检索", "RRF"), merged)
        assertEquals(mapOf("RRF" to "融合排序"), candidateDefinitions(listOf(ConceptCandidate("RRF", "融合排序"))))
    }

    @Test
    fun extractionPromptCarriesNoteBodyAndExistingConcepts() {
        val messages = buildConceptExtractionMessages("检索笔记", "BM25 与向量检索", listOf("RRF"))
        assertEquals(2, messages.size)
        assertTrue(messages[1].content.contains("BM25 与向量检索"))
        assertTrue(messages[1].content.contains("RRF"))
        assertTrue(messages[1].content.contains("JSON"))
    }
}
