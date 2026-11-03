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
        assertTrue(byConcept.notes.any { it.summary.title == "检索笔记" })

        val byBody = store.search("向量")
        assertTrue(byBody.notes.any { it.summary.title == "检索笔记" })
        assertTrue(store.search("不存在的词").concepts.isEmpty())
        assertTrue(store.search("不存在的词").notes.isEmpty())
    }

    @Test
    fun searchReportsTheFirstBodyHitSoTheEditorCanJumpThere() {
        val store = store(tempHome())
        store.save(
            store.create("检索笔记").copy(
                body = "第一段没命中。\n\n第二段有 BM25 这个关键词，后面还有 BM25。",
            ),
        )
        val hit = store.search("BM25").notes.single()
        assertEquals("第一段没命中。\n\n第二段有 ".length, hit.start)
        assertEquals("BM25".length, hit.end - hit.start)
        assertTrue(hit.snippet.orEmpty().contains("BM25"), "副标题要显示命中那一行的上下文")

        val titleOnly = store.search("检索笔记").notes.single()
        assertEquals(-1, titleOnly.start, "只有标题命中时不该给正文位置")
        assertNull(titleOnly.snippet)
    }

    @Test
    fun conceptDetailSeparatesAnnotatedFromPlainMentions() {
        val store = store(tempHome())
        nowMs += 60_000
        store.save(
            store.create("标注过的").copy(
                body = "这篇把 [[RAG]] 标成本文概念。",
                concepts = listOf("RAG"),
            ),
        )
        nowMs += 60_000
        store.save(store.create("只是提到").copy(body = "这篇只是提到 RAG，没有标注。"))

        val detail = assertNotNull(store.conceptDetail("RAG"))
        assertEquals(listOf("标注过的"), detail.mentions.map { it.noteTitle })
        assertEquals(listOf("只是提到"), detail.others.map { it.noteTitle })
        assertTrue(detail.others.single().snippet.contains("RAG"))
        assertTrue(detail.others.single().start >= 0)
    }

    @Test
    fun conceptDetailResolvesAliasQueriesAndMappedAnnotations() {
        val store = store(tempHome())
        store.save(
            store.create("检索笔记").copy(
                body = "先做 [[检索增强生成 => RAG]]，再让模型作答。",
                concepts = listOf("RAG"),
            ),
        )
        val identities = listOf(ConceptIdentity("RAG", listOf("检索增强生成", "检索增强")))

        val byAlias = assertNotNull(store.conceptDetail("检索增强", identities = identities))
        assertEquals("RAG", byAlias.summary.name)
        assertEquals(listOf("检索笔记"), byAlias.mentions.map { it.noteTitle })
        val mention = byAlias.mentions.single()
        assertTrue(mention.snippet.contains("检索增强生成"), "片段要落在正文原词上")
        assertTrue(
            mention.start == "先做 [[".length,
            "命中位置是标注里原文的开头，不该选中整段标记",
        )
    }

    @Test
    fun searchMatchesAliasesAndPutsAnnotatedNotesFirst() {
        val store = store(tempHome())
        nowMs += 60_000
        store.save(store.create("只是提到").copy(body = "这篇提到 检索增强 但没标注。"))
        nowMs += 60_000
        store.save(
            store.create("标注过的").copy(
                body = "这篇把 [[检索增强生成 => RAG]] 标了。",
                concepts = listOf("RAG"),
            ),
        )
        val identities = listOf(ConceptIdentity("RAG", listOf("检索增强生成", "检索增强")))

        val hits = store.search("检索增强", identities = identities)
        assertEquals(listOf("RAG"), hits.concepts.map { it.name }, "别名也要能搜到概念")
        assertEquals("标注过的", hits.notes.first().summary.title, "标注过命中概念的笔记排最前")
        assertTrue(hits.notes.any { it.summary.title == "只是提到" })
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
    fun displayTitleDropsInlineMarkdownButKeepsTheWords() {
        assertEquals("RAG 检索增强", displayTitle("**RAG** 检索增强"))
        assertEquals("向量检索", displayTitle("`向量检索`"))
        assertEquals("参考 文档 里的做法", displayTitle("参考 [文档](https://example.com) 里的做法"))
        assertEquals("无标题笔记", displayTitle(""))
        assertEquals("旧的 新的", stripInlineMarkdown("~~旧的~~ *新的*"))
    }

    @Test
    fun headingWithoutFrontmatterTitleIsStrippedWhenUsedAsFallback() {
        val note = parseNoteMarkdown(
            id = "n_demo",
            raw = "# **检索增强** 生成\n\n正文",
            zone = zone,
            fallbackMs = nowMs,
        )
        assertEquals("检索增强 生成", note.title)
    }

    @Test
    fun extractionPromptCarriesNoteBodyAndExistingConcepts() {
        val messages = buildConceptExtractionMessages("检索笔记", "BM25 与向量检索", listOf("RRF"))
        assertEquals(2, messages.size)
        assertTrue(messages[1].content.contains("BM25 与向量检索"))
        assertTrue(messages[1].content.contains("RRF"))
        assertTrue(messages[1].content.contains("JSON"))
    }

    @Test
    fun extractionPromptCarriesHeadingsForGraphBuilding() {
        // 笔记页「保存并整理」把标题结构一起送进模型：AI 用大标题给父分类拟名（可概括，不要求一字不差）。
        val messages = buildKnowledgeExtractionMessages(
            title = "RAG 综述",
            body = "# RAG 综述\n\n## 关键环节\n\n[[向量检索]] 负责召回。",
            existing = emptyList(),
            slots = emptyList(),
            headings = listOf("RAG 综述", "关键环节"),
        )
        val user = messages[1].content
        assertTrue(user.contains("标题结构"))
        assertTrue(user.contains("- 关键环节"))
        assertTrue(user.contains("大标题当父分类名"))
        // 不给标题列表时不出现「本笔记的标题结构」这一块（兼容旧调用）。
        val legacy = buildConceptExtractionMessages("标题", "正文内容", emptyList())
        assertTrue(!legacy[1].content.contains("本笔记的标题结构"))
    }

    // ---- 概念准入：索引合并空白变体、跳过短词 ----

    @Test
    fun conceptIndexMergesWhitespaceVariantsAndSkipsShortNames() {
        val home = tempHome()
        val store = store(home)
        store.save(
            KnowledgeNote(
                id = "n_gate",
                title = "门槛",
                body = "Matryoshka 表示学习 可以让向量前几维就能用。",
                createdAt = nowMs,
                updatedAt = nowMs,
                concepts = listOf("Matryoshka 表示学习", "Matryoshka表示学习", "M", "ef"),
            ),
        )
        val index = store.conceptIndex()
        assertEquals(1, index.size)
        assertEquals(conceptIdentityKey("Matryoshka表示学习"), conceptIdentityKey(index.single().name))
        assertEquals(1, index.single().noteCount)
    }
}
