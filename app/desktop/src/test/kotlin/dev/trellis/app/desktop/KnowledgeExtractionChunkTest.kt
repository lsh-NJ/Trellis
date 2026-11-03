package dev.trellis.app.desktop

import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 长笔记分段整理：切段、合并、提示词里说明「这是第几段」。 */
class KnowledgeExtractionChunkTest {
    @Test
    fun extractionBodyChunksKeepsShortNotesWhole() {
        val body = "第一段\n\n第二段，很短。"
        assertEquals(listOf(body), extractionBodyChunks(body))
    }

    @Test
    fun extractionBodyChunksSplitsLongNotesAtLineEnds() {
        val body = buildString {
            repeat(120) { line ->
                append("第").append(line).append("行：").append("内容".repeat(60)).append('\n')
            }
        }
        val chunks = extractionBodyChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= MAX_EXTRACT_BODY_CHARS })
        assertEquals(body, chunks.joinToString(""))
        // 除最后一段外都在行尾断开：末尾是换行，且不是半行。
        chunks.dropLast(1).forEach { assertTrue(it.endsWith("\n")) }
    }

    @Test
    fun extractionBodyChunksHardSplitsAGiantLine() {
        val body = "长".repeat(MAX_EXTRACT_BODY_CHARS * 2 + 100)
        val chunks = extractionBodyChunks(body)
        assertEquals(3, chunks.size)
        assertEquals(
            listOf(MAX_EXTRACT_BODY_CHARS, MAX_EXTRACT_BODY_CHARS, 100),
            chunks.map { it.length },
        )
        assertEquals(body, chunks.joinToString(""))
    }

    @Test
    fun extractionBodyChunksSplitsAtFirstLevelHeadings() {
        val body = buildString {
            repeat(6) { index ->
                append("# 第").append(index).append("节\n")
                append("内容".repeat(600)).append("\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionBodyChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= MAX_EXTRACT_BODY_CHARS })
        assertEquals(body, chunks.joinToString(""))
        // 每段都从一级标题开始：段过大时按标题切开，而不是从中间断。
        chunks.forEach { assertTrue(it.startsWith("# "), "chunk should start at a heading: " + it.take(12)) }
    }

    @Test
    fun extractionBodyChunksFallsBackToSecondLevelForBigSections() {
        val body = buildString {
            append("# 大章节\n")
            repeat(4) { index ->
                append("## 小节").append(index).append("\n")
                append("内容".repeat(900)).append("\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionBodyChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= MAX_EXTRACT_BODY_CHARS })
        assertEquals(body, chunks.joinToString(""))
        // 第一段可以从一级标题开始；后面的段都从二级标题开始（降级成功）。
        chunks.drop(1).forEach { assertTrue(it.startsWith("## "), "chunk should start at an h2: " + it.take(12)) }
    }

    @Test
    fun extractionBodyChunksSplitsAtLinesInsideAGiantSection() {
        val body = buildString {
            append("# 唯一的大章节\n")
            repeat(400) { index ->
                append("第").append(index).append("行：").append("内容".repeat(30)).append("\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionBodyChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= MAX_EXTRACT_BODY_CHARS })
        assertEquals(body, chunks.joinToString(""))
        assertTrue(chunks.first().startsWith("# 唯一的大章节"))
    }

    @Test
    fun extractionBodyChunksCapsTheNumberOfCalls() {
        val body = "行\n".repeat(30000)
        val chunks = extractionBodyChunks(body)
        assertEquals(MAX_EXTRACT_CHUNKS, chunks.size)
        assertTrue(chunks.all { it.length <= MAX_EXTRACT_BODY_CHARS })
    }

    @Test
    fun longNotePromptSaysWhichChunkItIs() {
        val messages = buildKnowledgeExtractionMessages(
            title = "长笔记",
            body = "这一段的内容",
            existing = emptyList(),
            slots = emptyList(),
            chunkIndex = 1,
            chunkCount = 3,
        )
        val user = messages.first { it.role == LlmRole.User }.content
        assertTrue(user.contains("第 2/3 段"))
    }

    @Test
    fun shortNotePromptDoesNotMentionChunks() {
        val messages = buildKnowledgeExtractionMessages(
            title = "短笔记",
            body = "短正文",
            existing = emptyList(),
            slots = emptyList(),
        )
        val user = messages.first { it.role == LlmRole.User }.content
        assertTrue(!user.contains("段，其余段落这里省略"))
    }

    @Test
    fun consolidationPromptAsksForOneMergedListWithoutInventing() {
        val messages = buildConceptConsolidationMessages(
            title = "长笔记",
            candidates = listOf(
                ConceptCandidate("RAG", "检索增强生成", mention = "检索增强生成"),
                ConceptCandidate("GraphRAG", "图版本", aliases = listOf("图检索")),
            ),
        )
        val user = messages.first { it.role == LlmRole.User }.content
        assertTrue(user.contains("RAG"))
        assertTrue(user.contains("GraphRAG"))
        assertTrue(user.contains("正文原词"))
        assertTrue(messages.first { it.role == LlmRole.System }.content.contains("不要新增"))
    }

    @Test
    fun consolidationMergesAliasesAndKeepsOriginalMentions() {
        val applied = applyConsolidatedCandidates(
            original = listOf(
                ConceptCandidate("RAG", "短定义", mention = "检索增强生成"),
                ConceptCandidate("检索生成", "另一段里的叫法", mention = "检索生成"),
                ConceptCandidate("BM25", "关键词检索", mention = "BM25"),
            ),
            consolidated = listOf(
                ConceptCandidate(
                    "RAG",
                    "先检索再让模型回答",
                    aliases = listOf("检索生成", "检索增强生成"),
                ),
            ),
        )
        assertEquals(2, applied.size)
        val rag = applied.first { conceptIdentityKey(it.name) == conceptIdentityKey("RAG") }
        assertEquals("先检索再让模型回答", rag.definition)
        // mention 必须用原始候选的正文原词，不能被模型改写。
        assertEquals("检索增强生成", rag.mention)
        assertTrue(rag.aliases.any { conceptIdentityKey(it) == conceptIdentityKey("检索生成") })
        // 模型没提 BM25：原样保留，不丢。
        assertTrue(applied.any { conceptIdentityKey(it.name) == conceptIdentityKey("BM25") })
    }

    @Test
    fun consolidationIgnoresConceptsThatWereNeverExtracted() {
        val applied = applyConsolidatedCandidates(
            original = listOf(ConceptCandidate("RAG", "检索增强生成", mention = "检索增强生成")),
            consolidated = listOf(
                ConceptCandidate("RAG", "更完整定义", mention = ""),
                ConceptCandidate("LLM 推理", "编造出来的新概念", mention = ""),
            ),
        )
        assertEquals(1, applied.size)
        assertEquals("更完整定义", applied.first().definition)
        assertTrue(applied.none { conceptIdentityKey(it.name) == conceptIdentityKey("LLM 推理") })
    }

    @Test
    fun mergeExtractionResultsDeduplicatesConceptsAndFills() {
        val merged = mergeExtractionResults(
            listOf(
                ExtractionResult(
                    candidates = listOf(
                        ConceptCandidate("RAG", "短定义", mention = "检索增强生成"),
                        ConceptCandidate("GraphRAG", "图版本"),
                    ),
                    fills = listOf(FillSuggestion("回答", "先检索再回答")),
                ),
                ExtractionResult(
                    candidates = listOf(
                        ConceptCandidate(
                            "rag",
                            "更完整的一句话定义",
                            mention = "",
                            aliases = listOf("检索增强"),
                        ),
                    ),
                    fills = listOf(
                        FillSuggestion("回答", "另一份答案"),
                        FillSuggestion("总结", "一段总结"),
                    ),
                ),
            ),
        )
        assertEquals(2, merged.candidates.size)
        val rag = merged.candidates.first { conceptIdentityKey(it.name) == conceptIdentityKey("RAG") }
        assertEquals("更完整的一句话定义", rag.definition)
        assertEquals("检索增强生成", rag.mention)
        assertEquals(listOf("检索增强"), rag.aliases)
        assertEquals(2, merged.fills.size)
        assertEquals("先检索再回答", merged.fills.first { it.label == "回答" }.text)
    }

    @Test
    fun mergeGraphSuggestionsDedupesAndPrefersHigherConfidence() {
        val merged = mergeNoteGraphSuggestions(
            listOf(
                NoteGraphSuggestions(
                    categories = listOf(
                        AiCategorySuggestion(name = "RAG", categoryPath = listOf("检索")),
                        AiCategorySuggestion(name = "GraphRAG", categoryPath = listOf("检索")),
                    ),
                    relations = listOf(
                        AiRelationSuggestion(
                            from = "RAG",
                            to = "GraphRAG",
                            kind = GraphEdgeKind.PREREQUISITE,
                            confidence = 0.5f,
                        ),
                        AiRelationSuggestion(
                            from = "RAG",
                            to = "GraphRAG",
                            kind = GraphEdgeKind.RELATED,
                            confidence = 0.4f,
                        ),
                    ),
                ),
                NoteGraphSuggestions(
                    categories = listOf(
                        AiCategorySuggestion(
                            name = "RAG",
                            categoryPath = listOf("检索"),
                            reason = "同属检索增强",
                        ),
                    ),
                    relations = listOf(
                        AiRelationSuggestion(
                            from = "RAG",
                            to = "GraphRAG",
                            kind = GraphEdgeKind.PREREQUISITE,
                            confidence = 0.8f,
                        ),
                        AiRelationSuggestion(
                            from = "GraphRAG",
                            to = "RAG",
                            kind = GraphEdgeKind.RELATED,
                            confidence = 0.6f,
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, merged.categories.size)
        assertEquals("同属检索增强", merged.categories.first().reason)
        assertEquals(2, merged.relations.size)
        val prerequisite = merged.relations.first { it.kind == GraphEdgeKind.PREREQUISITE }
        assertEquals(0.8f, prerequisite.confidence)
        // RELATED 是双向的：两段里方向相反也只留一条，取置信度高的 0.6。
        val related = merged.relations.filter { it.kind == GraphEdgeKind.RELATED }
        assertEquals(1, related.size)
        assertEquals(0.6f, related.first().confidence)
    }
}
