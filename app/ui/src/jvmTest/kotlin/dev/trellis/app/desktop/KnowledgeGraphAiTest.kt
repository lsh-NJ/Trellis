package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G1 AI 建议的纯逻辑测试：解析 → 校验 → 过滤 → 确认/忽略。
 *
 * 不碰真模型（提示词/解析/mock e2e 另有测试），这里只钉住「模型胡说也不能污染图」的边界。
 */
class KnowledgeGraphAiTest {

    private val now = 1_700_000_000_000L

    private fun conceptNames(vararg names: String): Map<String, String> =
        names.associateBy { conceptIdentityKey(it) }

    private fun json(body: String): String = "```json\n$body\n```"

    // ------------------------------------------------------------------
    // 笔记内解析
    // ------------------------------------------------------------------

    @Test
    fun parsesValidCategoryAndRelationSuggestions() {
        val raw = json(
            """
            {"concepts":[],"fills":[],"categories":[
              {"concept":"向量检索","categoryPath":"检索/召回","reason":"讲的是召回"}
            ],"relations":[
              {"from":"向量检索","to":"RAG 检索增强生成","kind":"PREREQUISITE",
               "confidence":4,"reason":"检索是前置","evidence":"先做向量检索再生成"}
            ]}
            """.trimIndent(),
        )
        val result = parseNoteGraphSuggestions(
            raw = raw,
            noteConceptNames = listOf("向量检索", "RAG 检索增强生成"),
            body = "先做向量检索再生成答案。",
            now = now,
        )
        assertEquals(1, result.categories.size)
        assertEquals(listOf("检索", "召回"), result.categories.single().categoryPath)
        assertEquals("向量检索", result.categories.single().name)
        assertEquals(1, result.relations.size)
        val relation = result.relations.single()
        assertEquals(GraphEdgeKind.PREREQUISITE, relation.kind)
        assertEquals("先做向量检索再生成", relation.evidence)
        assertEquals(0.8f, relation.confidence)
        assertTrue(result.dropped.isEmpty(), result.dropped.toString())
    }

    @Test
    fun dropsCategoriesForConceptsNotInThisNote() {
        val raw = json(
            """{"categories":[{"concept":"别的笔记的概念","categoryPath":"甲/乙","reason":"x"}]}""",
        )
        val result = parseNoteGraphSuggestions(raw, listOf("向量检索"), "正文", now)
        assertTrue(result.categories.isEmpty())
        assertTrue(result.dropped.any { it.contains("不是本篇概念") }, result.dropped.toString())
    }

    @Test
    fun dropsInvalidCategoryPaths() {
        val raw = json(
            """
            {"categories":[
              {"concept":"向量检索","categoryPath":"甲/乙/丙"},
              {"concept":"向量检索","categoryPath":"未分类"},
              {"concept":"向量检索","categoryPath":"这是一个特别特别特别长的分类名字超过上限"},
              {"concept":"向量检索","categoryPath":"检索/召回"}
            ]}
            """.trimIndent(),
        )
        val result = parseNoteGraphSuggestions(raw, listOf("向量检索"), "正文", now)
        assertEquals(1, result.categories.size)
        assertEquals(listOf("检索", "召回"), result.categories.single().categoryPath)
        assertTrue(result.dropped.size >= 3, result.dropped.toString())
    }

    @Test
    fun requiresEvidenceInsideBodyForDirectedRelations() {
        val raw = json(
            """
            {"relations":[
              {"from":"向量检索","to":"RAG 检索增强生成","kind":"PREREQUISITE","confidence":4},
              {"from":"向量检索","to":"RAG 检索增强生成","kind":"APPLIES","confidence":3,
               "evidence":"这句话不在正文里"}
            ]}
            """.trimIndent(),
        )
        val result = parseNoteGraphSuggestions(raw, listOf("向量检索", "RAG 检索增强生成"), "先做向量检索再生成", now)
        assertTrue(result.relations.isEmpty())
        assertTrue(result.dropped.any { it.contains("缺少正文依据") })
        assertTrue(result.dropped.any { it.contains("不是正文原文") })
    }

    @Test
    fun allowsRelatedWithoutEvidenceButStillValidatesIt() {
        val raw = json(
            """
            {"relations":[
              {"from":"向量检索","to":"RAG 检索增强生成","kind":"RELATED"},
              {"from":"向量检索","to":"RAG 检索增强生成","kind":"RELATED","evidence":"先做向量检索"}
            ]}
            """.trimIndent(),
        )
        val result = parseNoteGraphSuggestions(raw, listOf("向量检索", "RAG 检索增强生成"), "先做向量检索再生成", now)
        // 两条都合法，但 (from,to,kind) 去重后只留第一条（无依据）。
        assertEquals(1, result.relations.size)
        assertNull(result.relations.single().evidence)
    }

    @Test
    fun dropsRelationWhenEndpointIsUnknownOrSelf() {
        val raw = json(
            """
            {"relations":[
              {"from":"向量检索","to":"不存在","kind":"RELATED"},
              {"from":"向量检索","to":"向量检索","kind":"RELATED"}
            ]}
            """.trimIndent(),
        )
        val result = parseNoteGraphSuggestions(raw, listOf("向量检索"), "正文", now)
        assertTrue(result.relations.isEmpty())
        assertTrue(result.dropped.any { it.contains("端点") })
    }

    @Test
    fun normalizesConfidenceFromBothScales() {
        assertEquals(0.5f, normalizeSuggestionConfidence(0.5, GraphEdgeKind.RELATED))
        assertEquals(0.8f, normalizeSuggestionConfidence(4.0, GraphEdgeKind.RELATED))
        assertEquals(0.9f, normalizeSuggestionConfidence(5.0, GraphEdgeKind.RELATED))
        assertEquals(0.4f, normalizeSuggestionConfidence(0.1, GraphEdgeKind.RELATED))
        // 没给就按类型给默认值（PREREQUISITE 更可信一点）。
        assertEquals(0.7f, normalizeSuggestionConfidence(null, GraphEdgeKind.PREREQUISITE))
    }

    @Test
    fun parsesCategoryPathSeparatorsAndTrims() {
        assertEquals(listOf("检索", "召回"), parseSuggestionCategoryPath(" 检索 / 召回 "))
        assertEquals(listOf("检索", "召回"), parseSuggestionCategoryPath("检索 > 召回"))
        assertEquals(listOf("检索", "召回"), parseSuggestionCategoryPath("检索｜召回"))
        assertEquals(listOf("检索"), parseSuggestionCategoryPath("检索"))
        assertNull(parseSuggestionCategoryPath(""))
        assertNull(parseSuggestionCategoryPath("甲/乙/丙"))
        assertNull(parseSuggestionCategoryPath("未分类"))
    }

    // ------------------------------------------------------------------
    // 全图分批 / 提示词 / 合并
    // ------------------------------------------------------------------

    @Test
    fun batchesConceptsWithoutExceedingLimits() {
        val keys = (1..95).map { "c$it" }
        val batches = globalCategoryBatches(keys)
        assertEquals(3, batches.size)
        assertEquals(40, batches[0].size)
        assertEquals(15, batches[2].size)
        assertTrue(globalCategoryBatches(emptyList()).isEmpty())

        val many = (1..(MAX_GLOBAL_BATCHES + 5) * MAX_GLOBAL_BATCH_CONCEPTS).map { "k$it" }
        assertEquals(MAX_GLOBAL_BATCHES, globalCategoryBatches(many).size)
        assertEquals(
            MAX_GLOBAL_BATCHES * MAX_GLOBAL_BATCH_CONCEPTS,
            globalCategoryCoveredCount(many.size),
        )
    }

    @Test
    fun globalPromptCarriesConceptsExistingPathsAndJsonContract() {
        val messages = buildGlobalCategoryMessages(
            concepts = listOf("conceptIdentityKey(向量检索)" to "向量检索"),
            existingPaths = listOf("检索/召回"),
            batchIndex = 0,
            batchCount = 1,
        )
        val text = messages.joinToString("\n") { it.content }
        assertTrue(text.contains("现有分类路径"))
        assertTrue(text.contains("检索/召回"))
        assertTrue(text.contains("conceptIdentityKey(向量检索)"))
        assertTrue(text.contains("assignments"))
        assertTrue(text.contains("只输出 JSON"))
    }

    @Test
    fun parsesGlobalSuggestionsAndDropsUnknownConceptKeys() {
        val allowed = mapOf(conceptIdentityKey("向量检索") to "向量检索")
        val raw = json(
            """
            {"assignments":[
              {"concept":"向量检索","categoryPath":"检索/召回","reason":"属于召回"},
              {"concept":"编造的概念","categoryPath":"检索","reason":"x"},
              {"concept":"向量检索","categoryPath":"甲/乙/丙","reason":"非法路径"}
            ]}
            """.trimIndent(),
        )
        val parsed = parseGlobalCategorySuggestions(raw, allowed, batchIndex = 0, now = now)
        assertEquals(1, parsed.size)
        assertEquals(conceptIdentityKey("向量检索"), parsed.single().concept)
        assertEquals(listOf("检索", "召回"), parsed.single().categoryPath)
    }

    @Test
    fun mergesGlobalSuggestionsAndResetsOnConceptSetChange() {
        val first = mergeGlobalCategorySuggestions(
            existing = AiGlobalCategories(),
            conceptSetHash = "hash-1",
            incoming = listOf(AiGlobalCategorySuggestion(concept = "a", categoryPath = listOf("甲"))),
            failed = listOf(AiFailedCategory("b", "没把握")),
            now = now,
        )
        assertEquals(1, first.categories.size)

        val merged = mergeGlobalCategorySuggestions(
            existing = first,
            conceptSetHash = "hash-1",
            incoming = listOf(
                AiGlobalCategorySuggestion(concept = "a", categoryPath = listOf("乙")),
                AiGlobalCategorySuggestion(concept = "c", categoryPath = listOf("丙")),
            ),
            failed = emptyList(),
            now = now,
        )
        assertEquals(2, merged.categories.size)
        assertEquals(listOf("甲"), merged.categories.first { it.concept == "a" }.categoryPath)
        assertEquals(1, merged.failed.size)

        val reset = mergeGlobalCategorySuggestions(
            existing = merged,
            conceptSetHash = "hash-2",
            incoming = listOf(AiGlobalCategorySuggestion(concept = "d", categoryPath = listOf("丁"))),
            failed = emptyList(),
            now = now,
        )
        assertEquals(1, reset.categories.size)
        assertEquals("d", reset.categories.single().concept)
    }

    // ------------------------------------------------------------------
    // 待确认建议的过滤
    // ------------------------------------------------------------------

    private fun noteSuggestions(bodyHash: String, categories: List<AiCategorySuggestion>, relations: List<AiRelationSuggestion> = emptyList()) =
        AiNoteSuggestions(bodyHash = bodyHash, categories = categories, relations = relations)

    @Test
    fun hidesStaleNoteSuggestionsWhoseBodyChanged() {
        val cache = GraphAiCache(
            noteSuggestions = mapOf(
                "n1" to noteSuggestions(
                    bodyHash = "fnv1a64:0000000000000001",
                    categories = listOf(AiCategorySuggestion("向量检索", listOf("检索"))),
                ),
            ),
        )
        val stale = collectPendingSuggestions(
            cache = cache,
            user = GraphUserData.Empty,
            conceptNames = conceptNames("向量检索"),
            noteBodyHashes = mapOf("n1" to "fnv1a64:0000000000000002"),
            globalCategoriesValid = false,
        )
        assertTrue(stale.isEmpty)

        val fresh = collectPendingSuggestions(
            cache = cache,
            user = GraphUserData.Empty,
            conceptNames = conceptNames("向量检索"),
            noteBodyHashes = mapOf("n1" to "fnv1a64:0000000000000001"),
            globalCategoriesValid = false,
        )
        assertEquals(1, fresh.categories.size)
        assertEquals("向量检索", fresh.categories.single().conceptName)
    }

    @Test
    fun globalSuggestionsSurviveConfirmingOneCategory() {
        // 回归：确认一条归类建议会新建分类；分类树变化不能让 conceptSetHash 变，
        // 否则其余全图建议会在下一次重组时全部消失（用户 2026-11-03 报的问题）。
        val before = graphOf("向量检索", "嵌入", "重排")
        val cache = GraphAiCache(
            globalCategories = AiGlobalCategories(
                conceptSetHash = knowledgeConceptSetHash(before),
                categories = listOf(
                    AiGlobalCategorySuggestion(
                        concept = conceptIdentityKey("向量检索"),
                        categoryPath = listOf("检索"),
                    ),
                    AiGlobalCategorySuggestion(
                        concept = conceptIdentityKey("嵌入"),
                        categoryPath = listOf("检索"),
                    ),
                ),
            ),
        )
        // 确认「向量检索」后：它有了归属，同时图里多出分类「检索」。
        val after = before.copy(
            nodes = before.nodes + (
                "c_retrieval" to GraphNode(
                    key = "c_retrieval",
                    kind = GraphNodeKind.CATEGORY,
                    name = "检索",
                )
                ),
        )
        assertEquals(knowledgeConceptSetHash(before), knowledgeConceptSetHash(after))

        val user = GraphUserData.Empty.copy(
            assignments = mapOf(conceptIdentityKey("向量检索") to "c_retrieval"),
        )
        val pending = collectPendingSuggestions(
            cache = cache,
            user = user,
            conceptNames = conceptNames("向量检索", "嵌入", "重排"),
            noteBodyHashes = emptyMap(),
            globalCategoriesValid = cache.globalCategories.conceptSetHash == knowledgeConceptSetHash(after),
        )
        assertEquals(1, pending.categories.size)
        assertEquals("嵌入", pending.categories.single().conceptName)
    }

    @Test
    fun hidesAssignedAndTombstonedSuggestions() {
        val path = listOf("检索")
        val tombstone = RemovedSuggestion(
            concept = conceptIdentityKey("向量检索"),
            fingerprint = categorySuggestionFingerprint(conceptIdentityKey("向量检索"), path),
        )
        val cache = GraphAiCache(
            noteSuggestions = mapOf(
                "n1" to noteSuggestions(
                    bodyHash = "h",
                    categories = listOf(
                        AiCategorySuggestion("向量检索", path),
                        AiCategorySuggestion("嵌入", path),
                        AiCategorySuggestion("重排", path),
                    ),
                ),
            ),
        )
        val user = GraphUserData.Empty.copy(
            assignments = mapOf(conceptIdentityKey("嵌入") to "c_fake"),
            removed = GraphRemovals(suggestions = listOf(tombstone)),
        )
        val pending = collectPendingSuggestions(
            cache = cache,
            user = user,
            conceptNames = conceptNames("向量检索", "嵌入", "重排"),
            noteBodyHashes = mapOf("n1" to "h"),
            globalCategoriesValid = false,
        )
        assertEquals(1, pending.categories.size)
        assertEquals("重排", pending.categories.single().conceptName)
    }

    @Test
    fun skipsGlobalSuggestionsWhenConceptSetChanged() {
        val cache = GraphAiCache(
            globalCategories = AiGlobalCategories(
                conceptSetHash = "old",
                categories = listOf(AiGlobalCategorySuggestion(concept = conceptIdentityKey("向量检索"), categoryPath = listOf("检索"))),
            ),
        )
        val stale = collectPendingSuggestions(
            cache, GraphUserData.Empty, conceptNames("向量检索"), emptyMap(), globalCategoriesValid = false,
        )
        assertTrue(stale.categories.isEmpty())
        val fresh = collectPendingSuggestions(
            cache, GraphUserData.Empty, conceptNames("向量检索"), emptyMap(), globalCategoriesValid = true,
        )
        assertEquals(1, fresh.categories.size)
        assertEquals(SuggestionOrigin.GLOBAL, fresh.categories.single().origin)
    }

    // ------------------------------------------------------------------
    // 确认 / 忽略 / 清缓存
    // ------------------------------------------------------------------

    @Test
    fun confirmingCategorySuggestionCreatesPathAndAssigns() {
        val pending = PendingCategorySuggestion(
            conceptKey = conceptIdentityKey("向量检索"),
            conceptName = "向量检索",
            categoryPath = listOf("检索", "召回"),
            reason = "属于召回",
            origin = SuggestionOrigin.NOTE,
            fingerprint = "fp",
            noteId = "n1",
        )
        val result = confirmCategorySuggestions(GraphUserData.Empty, listOf(pending), now)
        assertNull(result.error)
        assertEquals(2, result.user.categories.size)
        assertEquals("检索", result.user.categories.first { it.parentId == null }.name)
        val assigned = result.user.assignments[conceptIdentityKey("向量检索")]
        assertNotNull(assigned)
        assertTrue(result.user.categories.any { it.id == assigned && it.name == "召回" })
        // AI 建出来的分类先标成「未确认」，用户可以在浮窗里确认/改名。
        assertTrue(result.user.categories.all { it.origin == NodeOrigin.DERIVED || it.origin == NodeOrigin.AI })
    }

    @Test
    fun ignoringCategorySuggestionWritesTombstoneAndThenHidesIt() {
        val pending = PendingCategorySuggestion(
            conceptKey = conceptIdentityKey("向量检索"),
            conceptName = "向量检索",
            categoryPath = listOf("检索"),
            reason = null,
            origin = SuggestionOrigin.NOTE,
            fingerprint = categorySuggestionFingerprint(conceptIdentityKey("向量检索"), listOf("检索")),
            noteId = "n1",
        )
        val user = ignoreCategorySuggestion(GraphUserData.Empty, pending)
        assertEquals(1, user.removed.suggestions.size)
        assertEquals(pending.fingerprint, user.removed.suggestions.single().fingerprint)

        val cache = GraphAiCache(
            noteSuggestions = mapOf(
                "n1" to noteSuggestions("h", listOf(AiCategorySuggestion("向量检索", listOf("检索")))),
            ),
        )
        val pendingAfter = collectPendingSuggestions(
            cache, user, conceptNames("向量检索"), mapOf("n1" to "h"), globalCategoriesValid = false,
        )
        assertTrue(pendingAfter.categories.isEmpty())
    }

    @Test
    fun confirmingRelationSuggestionAddsUserRelationOnce() {
        val pending = PendingRelationSuggestion(
            fromKey = conceptIdentityKey("向量检索"),
            toKey = conceptIdentityKey("RAG 检索增强生成"),
            fromName = "向量检索",
            toName = "RAG 检索增强生成",
            kind = GraphEdgeKind.PREREQUISITE,
            confidence = 0.7f,
            reason = "检索是前置",
            evidence = "先做向量检索再生成",
            origin = SuggestionOrigin.NOTE,
            fingerprint = "fp",
            noteId = "n1",
        )
        val user = confirmRelationSuggestions(GraphUserData.Empty, listOf(pending), now)
        assertEquals(1, user.relations.size)
        assertEquals(now, user.relations.single().createdAt)
        val again = confirmRelationSuggestions(user, listOf(pending), now + 1)
        assertEquals(1, again.relations.size)
    }

    @Test
    fun ignoringRelationSuggestionWritesRemovedRelation() {
        val pending = PendingRelationSuggestion(
            fromKey = conceptIdentityKey("向量检索"),
            toKey = conceptIdentityKey("RAG 检索增强生成"),
            fromName = "向量检索",
            toName = "RAG 检索增强生成",
            kind = GraphEdgeKind.PREREQUISITE,
            confidence = 0.7f,
            reason = null,
            evidence = "先做向量检索再生成",
            origin = SuggestionOrigin.NOTE,
            fingerprint = "fp",
            noteId = "n1",
        )
        val user = ignoreRelationSuggestion(GraphUserData.Empty, pending)
        assertEquals(1, user.removed.relations.size)
        assertTrue(
            isRelationSuggestionIgnored(
                user.removed.relations,
                pending.fromKey,
                pending.toKey,
                pending.kind,
            ),
        )
    }

    @Test
    fun cacheDropsHandledSuggestions() {
        val category = PendingCategorySuggestion(
            conceptKey = conceptIdentityKey("向量检索"),
            conceptName = "向量检索",
            categoryPath = listOf("检索"),
            reason = null,
            origin = SuggestionOrigin.NOTE,
            fingerprint = categorySuggestionFingerprint(conceptIdentityKey("向量检索"), listOf("检索")),
            noteId = "n1",
        )
        val relation = PendingRelationSuggestion(
            fromKey = "a", toKey = "b", fromName = "A", toName = "B",
            kind = GraphEdgeKind.RELATED, confidence = 0.5f, reason = null, evidence = null,
            origin = SuggestionOrigin.NOTE,
            fingerprint = relationSuggestionFingerprint("A", "B", GraphEdgeKind.RELATED),
            noteId = "n1",
        )
        val cache = GraphAiCache(
            noteSuggestions = mapOf(
                "n1" to AiNoteSuggestions(
                    bodyHash = "h",
                    categories = listOf(AiCategorySuggestion("向量检索", listOf("检索"))),
                    relations = listOf(
                        AiRelationSuggestion("A", "B", GraphEdgeKind.RELATED, 0.5f),
                    ),
                ),
            ),
            globalCategories = AiGlobalCategories(
                conceptSetHash = "x",
                categories = listOf(AiGlobalCategorySuggestion(concept = "z", categoryPath = listOf("甲"))),
            ),
        )
        val cleaned = cacheWithoutRelationSuggestions(cacheWithoutCategorySuggestions(cache, listOf(category)), listOf(relation))
        assertTrue(cleaned.noteSuggestions.getValue("n1").categories.isEmpty())
        assertTrue(cleaned.noteSuggestions.getValue("n1").relations.isEmpty())
        // 全图建议没被这条笔记建议清掉。
        assertEquals(1, cleaned.globalCategories.categories.size)
    }

    @Test
    fun fingerprintsAreStableAndCaseInsensitive() {
        val a = categorySuggestionFingerprint("GraphRAG", listOf("检索"))
        val b = categorySuggestionFingerprint("graphrag", listOf("检索"))
        assertEquals(a, b)
        assertFalse(a == categorySuggestionFingerprint("GraphRAG", listOf("生成")))

        val r1 = relationSuggestionFingerprint("A", "B", GraphEdgeKind.PREREQUISITE)
        val r2 = relationSuggestionFingerprint("A", "B", GraphEdgeKind.PREREQUISITE)
        assertEquals(r1, r2)
    }

    // ------------------------------------------------------------------
    // 全图整理：假 LLM 端到端（分批、失败跳过、不写 user）
    // ------------------------------------------------------------------

    private fun graphOf(vararg names: String): KnowledgeGraph {
        val nodes = names.associate { name ->
            val key = conceptIdentityKey(name)
            key to GraphNode(key = key, kind = GraphNodeKind.CONCEPT, name = name)
        }
        return KnowledgeGraph(
            nodes = nodes,
            childrenOf = mapOf(null to nodes.keys.toList()),
        )
    }

    private class FakeLlm(private val answers: MutableList<() -> String>) : LlmClient {
        var calls = 0
            private set
        var lastPrompt: String = ""
            private set
        override suspend fun complete(messages: List<LlmMessage>): String {
            calls++
            lastPrompt = messages.joinToString("\n") { it.content }
            return answers.removeAt(0).invoke()
        }
        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
            onReasoning: suspend (String) -> Unit,
        ): String = complete(messages).also { onDelta(it) }
    }

    @Test
    fun globalRunBatchesAndSkipsFailedBatches() {
        val graph = graphOf("甲", "乙", "丙")
        val client = FakeLlm(
            mutableListOf(
                { """{"assignments":[{"concept":"${conceptIdentityKey("甲")}","categoryPath":"检索/召回","reason":"甲"}]}""" },
                { throw IllegalStateException("接口 500") },
            ),
        )
        val run = runBlocking {
            runGlobalCategorySuggestions(
                client = client,
                graph = graph,
                batches = listOf(
                    listOf(conceptIdentityKey("甲"), conceptIdentityKey("乙")),
                    listOf(conceptIdentityKey("丙")),
                ),
                existingPaths = emptyList(),
                now = { now },
            )
        }
        assertEquals(2, run.callCount)
        assertEquals(1, run.answeredCount)
        assertEquals(1, run.categories.categories.size)
        assertEquals(conceptIdentityKey("甲"), run.categories.categories.single().concept)
        // 甲已答；乙「这批没给建议」；丙那批请求失败；一共 2 个失败。
        assertEquals(2, run.failedCount)
        assertTrue(run.categories.failed.any { it.reason == "这批没给建议" })
        assertTrue(run.categories.failed.any { it.reason == "接口 500" })
    }

    @Test
    fun globalRunFiltersTombstonedSuggestions() {
        val graph = graphOf("甲", "乙")
        val client = FakeLlm(
            mutableListOf(
                { """{"assignments":[{"concept":"${conceptIdentityKey("甲")}","categoryPath":"检索"},""" +
                    """{"concept":"${conceptIdentityKey("乙")}","categoryPath":"生成"}]}""" },
            ),
        )
        val run = runBlocking {
            runGlobalCategorySuggestions(
                client = client,
                graph = graph,
                batches = listOf(listOf(conceptIdentityKey("甲"), conceptIdentityKey("乙"))),
                existingPaths = emptyList(),
                now = { now },
                isIgnored = { conceptKey, _ -> conceptKey == conceptIdentityKey("乙") },
            )
        }
        // 甲被保留；乙被墓碑过滤掉，算「没给建议」。
        assertEquals(1, run.answeredCount)
        assertEquals(1, run.failedCount)
        assertEquals(listOf("甲"), run.categories.categories.map { it.concept })
        assertEquals("这批没给建议", run.categories.failed.single().reason)
    }
}
