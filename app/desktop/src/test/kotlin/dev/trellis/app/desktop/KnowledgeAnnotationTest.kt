package dev.trellis.app.desktop

import java.io.File
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 概念标注（`[[正文原文=>概念名]]`）的纯函数测试。
 *
 * 约定见 `docs/knowledge-base-draft.md` §11：标记是「本文概念」的唯一来源，
 * frontmatter 的 `concepts:` 只是按标注重算出来的缓存，删掉的概念进 `removed-concepts:` 墓碑。
 */
class KnowledgeAnnotationTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)

    private val rag = ConceptIdentity("RAG", listOf("检索增强生成", "检索增强"))
    private val graphRag = ConceptIdentity("GraphRAG", listOf("图检索"))
    private val library = listOf(rag, graphRag)

    private fun note(body: String, concepts: List<String> = emptyList(), removed: List<String> = emptyList()) =
        KnowledgeNote(
            id = "n_test",
            title = "测试",
            body = body,
            createdAt = 0L,
            updatedAt = 0L,
            concepts = concepts,
            removedConcepts = removed,
        )

    // ---- 解析 ----

    @Test
    fun parsesPlainAndMappedAnnotationsWithOffsets() {
        val body = "先看 [[RAG]]，再看 [[检索增强生成=>RAG]]。"
        val annotations = parseConceptAnnotations(body)
        assertEquals(2, annotations.size)
        assertEquals("RAG", annotations[0].display)
        assertNull(annotations[0].target)
        assertEquals(body.indexOf("[[RAG]]"), annotations[0].start)
        assertEquals(body.indexOf("[[RAG]]") + "[[RAG]]".length, annotations[0].end)

        assertEquals("检索增强生成", annotations[1].display)
        assertEquals("RAG", annotations[1].target)
        assertEquals(body.indexOf("[[检索"), annotations[1].start)
    }

    @Test
    fun acceptsArrowSeparatorToo() {
        val annotations = parseConceptAnnotations("[[图检索→GraphRAG]]")
        assertEquals(1, annotations.size)
        assertEquals("图检索", annotations[0].display)
        assertEquals("GraphRAG", annotations[0].target)
    }

    @Test
    fun ignoresAnnotationsInsideCodeAiFillsAndMalformedOnes() {
        val body = """
            正文 [[RAG]]
            ```
            [[代码里的]]
            ```
            :::ai
            [[AI 块里的]]
            :::
            行内 {{[[AI 补全里的]]}} 不算
            [[]] 空的不算 [[ ]] 空白的也不算
        """.trimIndent()
        val annotations = parseConceptAnnotations(body)
        assertEquals(1, annotations.size)
        assertEquals("RAG", annotations[0].display)
    }

    // ---- 别名解析 ----

    @Test
    fun resolvesCanonicalNamesAliasesAndNewConcepts() {
        assertEquals("RAG", resolveConceptName("rag", library))
        assertEquals("RAG", resolveConceptName("检索增强生成", library))
        assertEquals("GraphRAG", resolveConceptName("图检索", library))
        assertEquals("BM25", resolveConceptName(" BM25 ", library))
        assertEquals("", resolveConceptName("   ", library))
    }

    @Test
    fun annotatedNamesResolveThroughAliasesAndKeepOrder() {
        val body = "[[检索增强生成]] 与 [[图检索=>GraphRAG]]，又提到 [[RAG]]。"
        assertEquals(listOf("RAG", "GraphRAG"), annotatedConceptNames(body, library))
    }

    // ---- 归属同步（concepts / 墓碑） ----

    @Test
    fun ownershipComesFromAnnotationsAndOldConceptsBecomeTombstones() {
        val before = note(
            body = "[[RAG]] 是检索增强生成。",
            concepts = listOf("RAG", "BM25"),
        )
        val after = synchronizeConceptMembership(before, library)
        assertEquals(listOf("RAG"), after.concepts)
        assertEquals(listOf("BM25"), after.removedConcepts)
    }

    @Test
    fun remarkingAConceptRemovesItsTombstone() {
        val before = note(body = "[[检索增强生成=>RAG]]", concepts = emptyList(), removed = listOf("RAG"))
        val after = synchronizeConceptMembership(before, library)
        assertEquals(listOf("RAG"), after.concepts)
        assertTrue(after.removedConcepts.isEmpty())
    }

    @Test
    fun deletingViaThePanelSeedsATombstoneThatSticks() {
        // 右栏 × 会先把标记降级成纯文本，再把概念写进墓碑；之后再保存不会把墓碑弄丢。
        val deleted = note(body = "这里没有标记了", concepts = emptyList(), removed = listOf("RAG"))
        val after = synchronizeConceptMembership(deleted, library)
        assertTrue(after.concepts.isEmpty())
        assertEquals(listOf("RAG"), after.removedConcepts)
    }

    @Test
    fun legacyNoteWithoutMarkersKeepsItsFrontmatterConcepts() {
        val before = note(body = "一篇老笔记，只有普通正文。", concepts = listOf("RAG", "BM25"))
        val after = synchronizeConceptMembership(before, library)
        assertEquals(before, after)
    }

    // ---- 删除 / 添加 ----

    @Test
    fun deletingAConceptDowngradesItsMarkersToPlainText() {
        val body = "[[检索增强生成=>RAG]] 与 [[GraphRAG]] 都是检索路线。"
        val stripped = stripConceptAnnotations(body, "RAG", library)
        assertEquals("检索增强生成 与 [[GraphRAG]] 都是检索路线。", stripped)
        assertEquals(body, stripConceptAnnotations(body, "BM25", library))
    }

    @Test
    fun addingAConceptWrapsTheFirstPlainMention() {
        val added = addConceptAnnotation("RAG 是检索增强生成。", "RAG", library)
        assertEquals("[[RAG]] 是检索增强生成。", added)
    }

    @Test
    fun addingAConceptThroughAnAliasWritesTheMappedForm() {
        val added = addConceptAnnotation("先做检索增强生成，再回答。", "RAG", library)
        assertEquals("先做[[检索增强生成 => RAG]]，再回答。", added)
    }

    @Test
    fun addingAConceptAppendsWhenTheBodyNeverMentionsIt() {
        assertEquals("[[RAG]]", addConceptAnnotation("", "RAG", library))
        assertEquals("只有别的内容。\n\n[[RAG]]\n", addConceptAnnotation("只有别的内容。", "RAG", library))
    }

    @Test
    fun addingAnAlreadyAnnotatedConceptIsANoOp() {
        val body = "[[RAG]] 已经标过了。"
        assertEquals(body, addConceptAnnotation(body, "rag", library))
    }

    @Test
    fun exportedBodyDropsAllAnnotations() {
        val body = "[[检索增强生成=>RAG]] 与 [[GraphRAG]] 都是路线。"
        assertEquals("检索增强生成 与 GraphRAG 都是路线。", stripAllConceptAnnotations(body))
        assertEquals(
            "# 标题\n\n检索增强生成 与 GraphRAG 都是路线。",
            exportNoteMarkdown(note(body).copy(title = "标题", body = "# 标题\n\n$body")).trim(),
        )
    }

    // ---- frontmatter 往返 ----

    @Test
    fun removedConceptsSurviveARoundTripButStayOutOfExtras() {
        val raw = """
            ---
            title: 测试
            created: 2026-11-01T23:00:00+08:00
            updated: 2026-11-01T23:30:00+08:00
            concepts: RAG
            removed-concepts: BM25, 向量检索
            ---

            正文 [[RAG]]
        """.trimIndent()
        val parsed = parseNoteMarkdown("n_test", raw, zone, 0L)
        assertEquals(listOf("RAG"), parsed.concepts)
        assertEquals(listOf("BM25", "向量检索"), parsed.removedConcepts)
        assertTrue(parsed.extras.isEmpty())

        val formatted = formatNoteMarkdown(parsed.copy(body = "正文 [[RAG]]"), zone)
        assertTrue(formatted.contains("concepts: RAG"))
        assertTrue(formatted.contains("removed-concepts: BM25, 向量检索"))
        val again = parseNoteMarkdown("n_test", formatted, zone, 0L)
        assertEquals(parsed.concepts, again.concepts)
        assertEquals(parsed.removedConcepts, again.removedConcepts)
    }

    // ---- AI 抽取的 mention/aliases：想总结时用 `[[原文 => 规范名]]` 映射，不改原词 ----

    @Test
    fun extractionParsesMentionAndAliases() {
        val raw = """{"concepts":[{"name":"RAG","mention":"检索增强生成","aliases":["检索增强","rag"],"definition":"先检索再回答"}],"fills":[]}"""
        val candidate = parseExtractionResult(raw).candidates.single()
        assertEquals("RAG", candidate.name)
        assertEquals("检索增强生成", candidate.mention)
        assertEquals(listOf("检索增强", "rag"), candidate.aliases)
    }

    @Test
    fun markConceptAnnotationsMapsTheMentionToTheCanonicalName() {
        assertEquals(
            "先做 [[检索增强生成 => RAG]]，再回答。",
            markConceptAnnotations(
                "先做 检索增强生成，再回答。",
                listOf(ConceptCandidate("RAG", "先检索再回答", mention = "检索增强生成")),
            ),
        )
    }

    @Test
    fun markConceptAnnotationsFallsBackToTheNameWhenMentionIsMissing() {
        assertEquals(
            "先做 [[RAG]]，再回答。",
            markConceptAnnotations("先做 RAG，再回答。", listOf(ConceptCandidate("RAG", "解释"))),
        )
    }

    @Test
    fun markConceptAnnotationsDropsCandidatesThatAreNotInTheBody() {
        val body = "先做 RAG，再回答。"
        assertEquals(body, markConceptAnnotations(body, listOf(ConceptCandidate("BM25", "解释", mention = "BM25"))))
    }

    @Test
    fun markConceptAnnotationsDoesNotRepeatAnExistingAnnotation() {
        val body = "先做 [[检索增强生成 => RAG]]。"
        assertEquals(body, markConceptAnnotations(body, listOf(ConceptCandidate("RAG", "解释", mention = "检索增强生成"))))
    }

    @Test
    fun onlyCandidatesThatLandedInTheBodyAreKept() {
        val body = markConceptAnnotations(
            "先做 检索增强生成，再回答。",
            listOf(
                ConceptCandidate("RAG", "先检索再回答", mention = "检索增强生成", aliases = listOf("检索增强")),
                ConceptCandidate("BM25", "稀疏检索", mention = "不存在的词"),
            ),
        )
        assertEquals("先做 [[检索增强生成 => RAG]]，再回答。", body)
        val kept = retainedCandidates(body, listOf(
            ConceptCandidate("RAG", "先检索再回答", mention = "检索增强生成", aliases = listOf("检索增强")),
            ConceptCandidate("BM25", "稀疏检索", mention = "不存在的词"),
        ), listOf(ConceptIdentity("RAG", listOf("检索增强"))))
        assertEquals(listOf("RAG"), kept.map { it.name })
    }

    @Test
    fun candidateAliasesOnlyCarriesCandidatesThatHaveSome() {
        val aliases = candidateAliases(
            listOf(
                ConceptCandidate("RAG", "解释", aliases = listOf("检索增强")),
                ConceptCandidate("BM25", "解释"),
            ),
        )
        assertEquals(mapOf("RAG" to listOf("检索增强")), aliases)
    }

    @Test
    fun mergingAliasesKeepsTheOnesTheUserAlreadyHad() {
        val home = File.createTempFile("trellis-annotation-merge-", "").apply {
            delete()
            mkdirs()
        }
        val meta = ConceptMetaStore(home)
        assertTrue(meta.saveAliases("RAG", listOf("检索增强")))
        assertTrue(meta.mergeAliases("RAG", listOf("检索增强", "rag", "RAG 检索")))
        val stored = meta.load()[conceptKey("RAG")]
        assertNotNull(stored)
        assertEquals(listOf("检索增强", "rag", "RAG 检索"), stored.aliases)
    }

    // ---- 概念库别名 ----

    @Test
    fun conceptAliasesPersistAndFeedTheIdentityTable() {
        val home = File.createTempFile("trellis-annotation-", "").apply {
            delete()
            mkdirs()
        }
        val meta = ConceptMetaStore(home)
        assertTrue(meta.ensureConcepts(listOf("RAG")))
        assertTrue(meta.saveAliases("RAG", listOf("检索增强生成", "rag", "检索增强生成")))

        val stored = meta.load()[conceptKey("RAG")]
        assertNotNull(stored)
        assertEquals("RAG", stored.name)
        assertEquals(listOf("检索增强生成", "rag"), stored.aliases)

        val identity = meta.identities().firstOrNull { conceptKey(it.name) == conceptKey("RAG") }
        assertNotNull(identity)
        assertEquals("RAG", resolveConceptName("检索增强生成", listOf(identity)))

        // 写定义不能把别名冲掉
        assertTrue(meta.saveDefinition("RAG", "先检索再让模型作答"))
        assertEquals(listOf("检索增强生成", "rag"), meta.load()[conceptKey("RAG")]?.aliases)
        assertEquals("先检索再让模型作答", meta.definition("RAG"))
    }

    @Test
    fun brokenAliasesAreIgnoredInsteadOfBreakingTheFile() {
        val home = File.createTempFile("trellis-annotation-bad-", "").apply {
            delete()
            mkdirs()
        }
        File(File(home, "knowledge"), "concepts.json").apply {
            parentFile.mkdirs()
            writeText(
                """
                {"concepts":[{"name":"RAG","definition":"检索增强","aliases":["  ", "检索增强生成", 42]}]}
                """.trimIndent(),
            )
        }
        val meta = ConceptMetaStore(home)
        assertEquals(listOf("检索增强生成"), meta.load()[conceptKey("RAG")]?.aliases)
    }
}
