package dev.trellis.app.desktop

import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 结构化分块：标题 1 > 标题 2 > 本段正文；每块带自己的章节路径，超长时按层级降级。 */
class KnowledgeExtractionChunkTest {
    @Test
    fun shortNotesStayWhole() {
        val body = "第一段\n\n第二段，很短。"
        val chunks = extractionChunks(body)
        assertEquals(1, chunks.size)
        assertEquals(body, chunks.single().text)
        assertEquals(0, chunks.single().startLine)
        assertTrue(chunks.single().contextPath.isEmpty())
        assertTrue(!chunks.single().continued)
    }

    @Test
    fun longNotesSplitAtFirstLevelHeadings() {
        val body = buildString {
            repeat(6) { index ->
                append("# 第").append(index).append("节\n")
                append("内容".repeat(600)).append("\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= MAX_EXTRACT_BODY_CHARS })
        // 后续每一段都从一级标题开始，不把标题和正文拆散。
        chunks.drop(1).forEach {
            assertTrue(it.text.startsWith("# "), "chunk should start at a heading: " + it.text.take(12))
            assertTrue(it.contextPath.isEmpty())
        }
        assertEquals(body, reassemble(chunks))
    }

    @Test
    fun oversizedFirstLevelSectionFallsBackToSecondLevelWithPath() {
        val body = buildString {
            append("# Agent记忆开源框架-Mem0\n")
            append("总览：Mem0 是一个记忆框架。".repeat(60)).append("\n")
            repeat(4) { index ->
                append("## 小节").append(index).append("\n")
                append("内容".repeat(900)).append("\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= MAX_EXTRACT_BODY_CHARS })
        assertEquals(listOf("Agent记忆开源框架-Mem0"), chunks.drop(1).first().contextPath)
        chunks.drop(1).forEach {
            assertTrue(it.text.startsWith("## "), "chunk should start at an h2: " + it.text.take(12))
            assertEquals(listOf("Agent记忆开源框架-Mem0"), it.contextPath)
        }
        assertEquals(body, reassemble(chunks))
    }

    @Test
    fun continuationCarriesTheDeepestHeadingPath() {
        val body = buildString {
            append("# 大章节\n")
            append("## 唯一小节\n")
            repeat(200) { append("第").append(it).append("行：").append("内容".repeat(20)).append("\n") }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionChunks(body)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.length <= MAX_EXTRACT_BODY_CHARS })
        val tail = chunks.drop(1)
        assertTrue(tail.all { it.continued }, "tail chunks should be continuations")
        assertTrue(tail.all { it.contextPath == listOf("大章节", "唯一小节") })
        assertEquals(body, reassemble(chunks))
    }

    @Test
    fun skippedLevelsUseTheNearestAncestor() {
        val body = buildString {
            append("# 一级\n")
            append("### 三级\n")
            append("内容".repeat(4000)).append("\n")
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS)
        val chunks = extractionChunks(body)
        assertTrue(chunks.size > 1)
        val tail = chunks.drop(1)
        assertTrue(tail.all { it.contextPath == listOf("一级", "三级") })
        assertEquals(body, reassemble(chunks))
    }

    @Test
    fun giantSingleLineIsHardSplitAndStillCovered() {
        val body = "长".repeat(MAX_EXTRACT_BODY_CHARS * 2 + 100)
        val chunks = extractionChunks(body)
        assertEquals(3, chunks.size)
        assertEquals(
            listOf(MAX_EXTRACT_BODY_CHARS, MAX_EXTRACT_BODY_CHARS, 100),
            chunks.map { it.text.length },
        )
        assertTrue(chunks.first().text + chunks[1].text + chunks[2].text == body)
        assertTrue(chunks.drop(1).all { it.continued })
    }

    @Test
    fun paragraphModeIsCappedAndTailIsMarkedTruncated() {
        val body = buildString {
            repeat(1100) {
                append("第").append(it).append("段：").append("内容".repeat(30)).append("\n\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS * MAX_EXTRACT_CHUNKS)
        val chunks = extractionChunks(body)
        assertEquals(MAX_PARAGRAPH_CHUNKS, chunks.size)
        assertTrue(chunks.all { it.text.length <= MAX_EXTRACT_BODY_CHARS })
        assertTrue(chunks.last().truncated)
    }

    @Test
    fun longArticlesUseParagraphModeAndKeepOneSectionPath() {
        val body = buildString {
            append("# 记忆写入\n")
            repeat(200) {
                append("第").append(it).append("段：").append("记忆写入的细节。".repeat(20)).append("\n\n")
            }
            append("# 去重策略\n")
            repeat(200) {
                append("第").append(it).append("段：").append("去重的细节。".repeat(25)).append("\n\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS * MAX_EXTRACT_CHUNKS)
        val chunks = extractionChunks(body)
        assertTrue(chunks.size > MAX_EXTRACT_CHUNKS)
        assertTrue(chunks.all { it.sectionPath.isNotEmpty() })
        val groups = chunks.groupBy { it.sectionPath }
        assertEquals(2, groups.size)
        assertEquals(setOf(listOf("记忆写入"), listOf("去重策略")), groups.keys)
        groups.values.forEach { group ->
            // 同一节的每一段重放同一份标题链；换段不换前缀。
            assertEquals(1, group.map { it.headingPrefix }.distinct().size)
            assertTrue(group.first().headingPrefix.startsWith("# "))
            assertTrue(group.size > 1)
        }
    }

    @Test
    fun paragraphModeRepeatsTheWholeHeadingChain() {
        val body = buildString {
            append("# A\n## B\n### C\n")
            repeat(300) {
                append("第").append(it).append("段：").append("内容".repeat(100)).append("\n\n")
            }
        }
        assertTrue(body.length > MAX_EXTRACT_BODY_CHARS * MAX_EXTRACT_CHUNKS)
        val chunks = extractionChunks(body)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.sectionPath == listOf("A", "B", "C") })
        assertTrue(chunks.all { it.headingPrefix == "# A\n## B\n### C" })
    }

    @Test
    fun tablesAreNotSentToTheModel() {
        val table = buildString {
            repeat(800) { append("| 列").append(it).append(" | 值 |\n") }
        }
        val body = "# 标题\n正文一。\n\n" + table + "\n正文二。"
        val mask = tableLineMask(body.split('\n'))
        assertEquals(800, mask.count { it })
        val chunks = extractionChunks(body)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.none { it.text.contains("| 列") })
        assertTrue(chunks.any { it.text.contains("正文二") })
    }

    @Test
    fun aNoteThatIsOnlyTablesProducesNoChunks() {
        val table = "| 甲 | 乙 |\n|---|---|\n" + "| 1 | 2 |\n".repeat(800)
        assertTrue(extractionChunks(table).isEmpty())
        assertTrue(extractionChunks("| 甲 | 乙 |\n|---|---|").isEmpty())
    }

    @Test
    fun headingOnlyParentIsPrependedInsteadOfBecomingATinyChunk() {
        val body = "# 一级标题\n### 直接跳到三级\n" + "内容".repeat(3500) + "\n"
        val chunks = extractionChunks(body)
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].text.startsWith("# 一级标题\n### 直接跳到三级"))
        assertEquals(MAX_EXTRACT_BODY_CHARS, chunks[0].text.length)
        assertEquals(listOf("一级标题", "直接跳到三级"), chunks[1].contextPath)
        assertEquals(body, reassemble(chunks))
    }

    @Test
    fun sectionAnchorsResolveTheHeadingChainAtALine() {
        val body = "# A\n\n## B\n\n正文一\n\n## C\n\n正文二"
        val anchors = sectionAnchors(scanHeadingLines(body))
        assertEquals(listOf("A", "B"), sectionAt(anchors, 4))
        assertEquals(listOf("A", "C"), sectionAt(anchors, 8))
        assertEquals(emptyList(), sectionAt(anchors, -1))
    }

    @Test
    fun headingsInsideFencedCodeAreNotStructure() {
        val body = "# 真标题\n\n```\n# 假标题\n```\n\n正文"
        val headings = scanHeadingLines(body)
        assertEquals(listOf("真标题"), headings.map { it.text })
    }

    @Test
    fun promptSaysWhichChunkItsSectionAndWhetherItContinues() {
        val messages = buildKnowledgeExtractionMessages(
            title = "长笔记",
            body = "## 小节\n正文",
            existing = emptyList(),
            slots = emptyList(),
            chunkIndex = 1,
            chunkCount = 3,
            contextPath = listOf("大标题", "小节"),
            continued = true,
            truncated = true,
        )
        val user = messages.first { it.role == LlmRole.User }.content
        assertTrue(user.contains("第 2/3 段"))
        assertTrue(user.contains("本段所属章节：大标题 > 小节"))
        assertTrue(user.contains("接上一段"))
        assertTrue(user.contains("后面还有内容"))
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
        assertTrue(!user.contains("本段所属章节"))
    }

    /** 按行号把各块拼回原文：跨块在同一行（超长行硬切）时不补换行。 */
    private fun reassemble(chunks: List<ExtractionChunk>): String = buildString {
        chunks.forEachIndexed { index, chunk ->
            if (index > 0 && chunk.startLine > chunks[index - 1].endLine) append('\n')
            append(chunk.text)
        }
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
    fun sameWordInDifferentSectionsKeepsItsOwnPrefix() {
        val merged = mergeExtractionResults(
            listOf(
                ExtractionResult(
                    candidates = listOf(
                        ConceptCandidate(
                            "去重",
                            "按内容哈希去重",
                            mention = "去重",
                            sectionPath = listOf("Agent记忆整合", "去重策略"),
                        ),
                    ),
                ),
                ExtractionResult(
                    candidates = listOf(
                        ConceptCandidate(
                            "去重",
                            "按时间窗口去重",
                            mention = "去重",
                            sectionPath = listOf("数据清洗", "去重"),
                        ),
                    ),
                ),
                ExtractionResult(
                    candidates = listOf(
                        ConceptCandidate(
                            "去重",
                            "按内容哈希去重（补充）",
                            mention = "去重",
                            sectionPath = listOf("Agent记忆整合", "去重策略"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, merged.candidates.size)
        val agent = merged.candidates.first { it.sectionPath.first() == "Agent记忆整合" }
        assertEquals("按内容哈希去重（补充）", agent.definition)
        val data = merged.candidates.first { it.sectionPath.first() == "数据清洗" }
        assertEquals("按时间窗口去重", data.definition)
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