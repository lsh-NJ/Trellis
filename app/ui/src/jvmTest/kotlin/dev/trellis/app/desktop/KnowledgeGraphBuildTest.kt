package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 知识网络构建的纯函数单测（设计 §3.2–§3.6、§10.1）。
 *
 * 只构造 [KnowledgeNote] / [GraphUserData] / [ConceptMeta] 内存对象：
 * 不读文件、不读写 graph JSON、不依赖 Compose，因此可以在 JVM 单测里跑得很快。
 */
class KnowledgeGraphBuildTest {

    private val base: Long = 1_700_000_000_000L

    private fun note(
        id: String,
        body: String = "",
        concepts: List<String> = emptyList(),
        createdAt: Long = base,
        updatedAt: Long = base,
        title: String = id,
        removedConcepts: List<String> = emptyList(),
    ): KnowledgeNote = KnowledgeNote(
        id = id,
        title = title,
        body = body,
        createdAt = createdAt,
        updatedAt = updatedAt,
        concepts = concepts,
        removedConcepts = removedConcepts,
    )

    private fun meta(
        name: String,
        definition: String = "",
        aliases: List<String> = emptyList(),
    ): ConceptMeta = ConceptMeta(name = name, definition = definition, aliases = aliases)

    private fun category(
        id: String,
        name: String,
        parentId: String? = null,
        order: Int = 0,
    ): GraphCategory = GraphCategory(id = id, name = name, parentId = parentId, order = order)

    private fun build(
        notes: List<KnowledgeNote>,
        concepts: Map<String, ConceptMeta> = emptyMap(),
        user: GraphUserData = GraphUserData.Empty,
    ): KnowledgeGraph = buildKnowledgeGraph(notes, concepts, user, GraphAiCache.Empty)

    // ---- 节点 ----

    @Test
    fun emptyInputBuildsEmptyGraph() {
        assertEquals(KnowledgeGraph.Empty, build(emptyList()))
    }

    @Test
    fun singleConceptNodeHasCounts() {
        val source = note("n_1", body = "[[向量检索]] 是基础。", concepts = listOf("向量检索"))
        val graph = build(listOf(source))

        val node = graph.nodes.getValue("向量检索")
        assertEquals(GraphNodeKind.CONCEPT, node.kind)
        assertEquals("向量检索", node.name)
        assertEquals(1, node.noteCount)
        assertEquals(1, node.mentionCount)
        assertEquals(source.createdAt, node.firstSeenAt)
        assertEquals(source.updatedAt, node.lastSeenAt)
        assertFalse(node.frontmatterOnly)
        assertNull(node.parentKey)
        assertEquals(1, graph.stats.conceptCount)
        assertEquals(0, graph.stats.classifiedCount)
        assertEquals(1, graph.stats.unclassifiedCount)
    }

    @Test
    fun aggregatesNotesAndUsesLatestSpelling() {
        val older = note(
            "n_1",
            body = "向量 检索 也不错",
            concepts = listOf("向量检索"),
            createdAt = base + 100,
            updatedAt = base + 100,
        )
        val newer = note(
            "n_2",
            body = "向量 检索 很好，向量 检索 很常用",
            concepts = listOf("向量 检索"),
            createdAt = base + 200,
            updatedAt = base + 200,
        )
        val graph = build(listOf(newer, older))

        val node = graph.nodes.getValue("向量检索")
        assertEquals(2, node.noteCount)
        // 最近更新的笔记里的写法胜出
        assertEquals("向量 检索", node.name)
        assertEquals(3, node.mentionCount)
        assertEquals(base + 100, node.firstSeenAt)
        assertEquals(base + 200, node.lastSeenAt)
    }

    @Test
    fun definitionComesFromConceptsMap() {
        val source = note("n_1", body = "[[向量检索]]", concepts = listOf("向量检索"))
        val graph = build(
            listOf(source),
            mapOf("向量检索" to meta("向量检索", definition = "把文本映射成向量")),
        )
        assertEquals("把文本映射成向量", graph.nodes.getValue("向量检索").definition)
    }

    @Test
    fun frontmatterOnlyConceptIsMarkedAndHasNoCoOccurrence() {
        val source = note(
            "n_1",
            body = "[[向量检索]] 和 [[文本分块]] 同段。",
            concepts = listOf("向量检索", "文本分块", "位置编码"),
        )
        val graph = build(listOf(source))

        val lonely = graph.nodes.getValue("位置编码")
        assertTrue(lonely.frontmatterOnly)
        assertEquals(0, lonely.mentionCount)
        assertTrue(
            graph.edges.none { it.fromKey == "位置编码" || it.toKey == "位置编码" },
            "frontmatter-only 概念不生成共现边",
        )
    }

    // ---- 分类树 / 归属 ----

    @Test
    fun categoryNodesAndConceptParentKeys() {
        val userData = GraphUserData(
            categories = listOf(
                category("c_root", "检索与生成"),
                category("c_child", "向量检索", "c_root"),
            ),
            assignments = mapOf("向量检索" to "c_child"),
        )
        val source = note("n_1", body = "[[向量检索]] 是基础。", concepts = listOf("向量检索"))
        val graph = build(listOf(source), user = userData)

        val categoryNode = graph.nodes.getValue("c_child")
        assertEquals(GraphNodeKind.CATEGORY, categoryNode.kind)
        assertEquals("c_root", categoryNode.parentKey)
        assertEquals(0, categoryNode.sortOrder)
        assertEquals("c_child", graph.nodes.getValue("向量检索").parentKey)
        assertEquals("c_root", graph.childrenOf.getValue(null).single())
        assertEquals(listOf("c_child"), graph.childrenOf("c_root").map { it.key })
        assertEquals(1, graph.stats.classifiedCount)
        assertEquals(0, graph.stats.unclassifiedCount)
    }

    @Test
    fun unclassifiedConceptsAreUnderNull() {
        val source = note(
            "n_1",
            body = "[[A概念]] [[B概念]]",
            concepts = listOf("A概念", "B概念"),
        )
        val graph = build(listOf(source))
        // 节点 key 是 conceptIdentityKey（会 lowercase）：A概念 → a概念
        assertEquals(listOf("a概念", "b概念"), graph.childrenOf[null])
        assertEquals(0, graph.stats.classifiedCount)
        assertEquals(2, graph.stats.unclassifiedCount)
    }

    @Test
    fun categoriesWithoutConceptsStillBuild() {
        val userData = GraphUserData(categories = listOf(category("c_a", "检索")))
        val graph = build(emptyList(), user = userData)
        assertEquals(1, graph.stats.categoryCount)
        assertEquals(0, graph.stats.conceptCount)
        assertEquals(listOf("c_a"), graph.childrenOf[null])
        assertEquals(0, graph.stats.unclassifiedCount)
    }

    @Test
    fun assignmentToMergedCategoryResolvesToTarget() {
        val userData = GraphUserData(
            categories = listOf(category("c_new", "检索")),
            assignments = mapOf("向量检索" to "c_old"),
            mergedInto = mapOf("c_old" to "c_new"),
        )
        val source = note("n_1", body = "[[向量检索]]", concepts = listOf("向量检索"))
        val graph = build(listOf(source), user = userData)
        assertEquals("c_new", graph.nodes.getValue("向量检索").parentKey)
        assertEquals(1, graph.stats.classifiedCount)
    }

    @Test
    fun childrenOrderIsDeterministic() {
        val userData = GraphUserData(
            categories = listOf(
                category("c_b", "乙", order = 1),
                category("c_a", "甲", order = 0),
            ),
        )
        val notes = listOf(
            note("n_1", body = "[[乙概念]]", concepts = listOf("乙概念")),
            note("n_2", body = "[[乙概念]]", concepts = listOf("乙概念")),
            note("n_3", body = "[[甲概念]]", concepts = listOf("甲概念")),
        )
        val graph = build(notes, user = userData)
        // 根分类按 sortOrder；未分类概念按 noteCount 降序
        assertEquals(listOf("c_a", "c_b"), graph.childrenOf[null]?.take(2))
        assertEquals(listOf("乙概念", "甲概念"), graph.childrenOf[null]?.drop(2))
    }

    // ---- 用户关系 / 墓碑 ----

    @Test
    fun userRelationsBecomeUserEdges() {
        val source = note(
            "n_1",
            body = "[[向量检索]] [[RAG]]",
            concepts = listOf("向量检索", "RAG"),
        )
        val userData = GraphUserData(
            relations = listOf(
                GraphUserRelation(
                    from = "向量检索",
                    to = "RAG",
                    kind = GraphEdgeKind.PREREQUISITE,
                    confidence = 0.9f,
                    reason = "检索是 RAG 的前置",
                    createdAt = base + 5,
                ),
            ),
        )
        val graph = build(listOf(source), user = userData)

        // 概念 key 是 conceptIdentityKey：RAG → rag
        val edge = graph.edges.single { it.source == GraphEdgeSource.USER }
        assertEquals(GraphEdgeKind.PREREQUISITE, edge.kind)
        assertEquals("向量检索", edge.fromKey)
        assertEquals("rag", edge.toKey)
        assertEquals(0.9f, edge.confidence)
        assertEquals("检索是 RAG 的前置", edge.reason)
        assertEquals(base + 5, edge.createdAt)
        assertNull(edge.evidence, "用户关系没有正文证据")
    }

    @Test
    fun tombstonesFilterDerivedAndUserEdges() {
        val source = note(
            "n_1",
            body = "[[向量检索]] [[文本分块]]",
            concepts = listOf("向量检索", "文本分块"),
        )
        val userData = GraphUserData(
            relations = listOf(GraphUserRelation("向量检索", "文本分块", GraphEdgeKind.RELATED)),
            removed = GraphRemovals(
                relations = listOf(
                    RemovedRelation("向量检索", "文本分块", GraphEdgeKind.RELATED),
                    // 无向边反向墓碑也要挡住派生边
                    RemovedRelation("文本分块", "向量检索", GraphEdgeKind.CO_OCCUR),
                ),
            ),
        )
        val graph = build(listOf(source), user = userData)
        assertTrue(graph.edges.isEmpty(), "删过的关系不复活：${graph.edges}")
    }

    @Test
    fun unknownRelationEndIsDroppedWithDiagnostic() {
        val source = note("n_1", body = "[[向量检索]]", concepts = listOf("向量检索"))
        val userData = GraphUserData(
            relations = listOf(
                GraphUserRelation("向量检索", "不存在的概念", GraphEdgeKind.RELATED),
            ),
        )
        val graph = build(listOf(source), user = userData)
        assertTrue(graph.edges.none { it.source == GraphEdgeSource.USER })
        val diagnostic = graph.diagnostics.single { it.kind == DiagnosticKind.UnknownEdgeEnd }
        assertEquals("向量检索", diagnostic.subjectKey)
    }

    // ---- 派生关系 ----

    @Test
    fun sameParagraphProducesHalfConfidenceCoOccurrence() {
        val source = note(
            "n_1",
            body = "[[向量检索]] 和 [[文本分块]] 是同一段。",
            concepts = listOf("向量检索", "文本分块"),
        )
        val graph = build(listOf(source))

        val edge = graph.edges.single { it.kind == GraphEdgeKind.CO_OCCUR }
        assertEquals(GraphEdgeSource.HEURISTIC, edge.source)
        assertEquals(0.5f, edge.confidence)
        val evidence = assertNotNull(edge.evidence)
        assertEquals(source.id, evidence.noteId)
        assertEquals(0, evidence.start)
        assertEquals(8, evidence.end)
        assertEquals("[[向量检索]] 和 [[文本分块]] 是同一段。", evidence.snippet)
        assertNull(edge.createdAt)

        // 同段相邻标注同时产生 EXPLICIT RELATED
        val related = graph.edges.single { it.kind == GraphEdgeKind.RELATED }
        assertEquals(GraphEdgeSource.EXPLICIT, related.source)
        assertEquals(0.8f, related.confidence)
    }

    @Test
    fun adjacentParagraphsProduceQuarterConfidence() {
        val source = note(
            "n_1",
            body = "[[向量检索]] 第一段。\n\n[[文本分块]] 第二段。",
            concepts = listOf("向量检索", "文本分块"),
        )
        val graph = build(listOf(source))
        val edge = graph.edges.single { it.kind == GraphEdgeKind.CO_OCCUR }
        assertEquals(0.25f, edge.confidence)
        assertEquals(GraphEdgeSource.HEURISTIC, edge.source)
    }

    @Test
    fun adjacentAnnotationsInSameParagraphAreRelatedExplicit() {
        val source = note(
            "n_1",
            body = "[[向量检索]] [[文本分块]] 这个概念对。",
            concepts = listOf("向量检索", "文本分块"),
        )
        val graph = build(listOf(source))
        val related = graph.edges.single { it.kind == GraphEdgeKind.RELATED }
        assertEquals(GraphEdgeSource.EXPLICIT, related.source)
        assertEquals(0.8f, related.confidence)
        assertNotNull(related.evidence)
    }

    @Test
    fun derivedEdgesPerNoteAreCapped() {
        val concepts = (1..20).map { "概念" + it.toString().padStart(2, '0') }
        val body = concepts.joinToString(" ") { "[[$it]]" } + " 同段。"
        val source = note("n_1", body = body, concepts = concepts)
        val graph = build(listOf(source))

        // 上限 = min(24, max(8, 20 * 2)) = 24；EXPLICIT 的 19 条 RELATED 优先
        assertEquals(24, graph.edges.size)
        assertEquals(19, graph.edges.count { it.kind == GraphEdgeKind.RELATED })
        assertEquals(5, graph.edges.count { it.kind == GraphEdgeKind.CO_OCCUR })
        assertTrue(graph.edges.all { it.evidence?.noteId == "n_1" })
    }

    @Test
    fun codeFenceAnnotationsDoNotProduceEdges() {
        val body = "```\n[[向量检索]] [[文本分块]]\n```\n\n[[向量检索]]\n\n[[RAG]]"
        val source = note(
            "n_1",
            body = body,
            concepts = listOf("向量检索", "文本分块", "RAG"),
        )
        val graph = build(listOf(source))
        assertTrue(
            graph.edges.none { it.fromKey == "文本分块" || it.toKey == "文本分块" },
            "代码围栏里的标注不参与派生关系",
        )
    }

    @Test
    fun aiBlockAnnotationsDoNotProduceEdges() {
        val body = ":::ai\n[[向量检索]] [[文本分块]]\n:::\n\n[[向量检索]]\n\n[[RAG]]"
        val source = note(
            "n_1",
            body = body,
            concepts = listOf("向量检索", "文本分块", "RAG"),
        )
        val graph = build(listOf(source))
        assertTrue(
            graph.edges.none { it.fromKey == "文本分块" || it.toKey == "文本分块" },
            ":::ai 块里的标注不参与派生关系",
        )
    }

    @Test
    fun annotationTargetMapsThroughAliases() {
        val concepts = mapOf("RAG" to meta("RAG", aliases = listOf("检索增强生成")))
        val source = note(
            "n_1",
            body = "[[检索增强生成 => RAG]] [[向量检索]] 一起用。",
            concepts = listOf("RAG", "向量检索"),
        )
        val graph = build(listOf(source), concepts)

        val related = graph.edges.single { it.kind == GraphEdgeKind.RELATED }
        assertEquals(GraphEdgeSource.EXPLICIT, related.source)
        assertEquals("rag", listOf(related.fromKey, related.toKey).single { it == "rag" })
        assertTrue(
            "向量检索" in listOf(related.fromKey, related.toKey),
            "别名/映射目标要解析到规范概念身份键",
        )
    }

    // ---- 合并优先级 / 统计 / 诊断 ----

    @Test
    fun mergePrefersUserOverDerived() {
        val source = note(
            "n_1",
            body = "[[向量检索]] [[文本分块]] 同段。",
            concepts = listOf("向量检索", "文本分块"),
        )
        val userData = GraphUserData(
            relations = listOf(
                GraphUserRelation(
                    from = "文本分块",
                    to = "向量检索",
                    kind = GraphEdgeKind.CO_OCCUR,
                    confidence = 0.7f,
                    reason = "用户指定",
                ),
            ),
        )
        val graph = build(listOf(source), user = userData)

        val edge = graph.edges.single { it.kind == GraphEdgeKind.CO_OCCUR }
        assertEquals(GraphEdgeSource.USER, edge.source)
        assertEquals(0.7f, edge.confidence, "confidence 取高者")
        assertEquals("用户指定", edge.reason)
        assertNotNull(edge.evidence, "派生边有证据时合并结果要保留证据")
    }

    @Test
    fun invalidAssignmentDropsToUnclassifiedWithStats() {
        val userData = GraphUserData(
            categories = listOf(category("c_a", "检索")),
            assignments = mapOf("向量检索" to "c_missing"),
        )
        val source = note("n_1", body = "[[向量检索]]", concepts = listOf("向量检索"))
        val graph = build(listOf(source), user = userData)

        assertNull(graph.nodes.getValue("向量检索").parentKey)
        assertEquals(1, graph.stats.droppedAssignments)
        assertTrue(graph.diagnostics.any { it.kind == DiagnosticKind.InvalidAssignment })
    }

    @Test
    fun taxonomyDiagnosticsArePropagated() {
        val userData = GraphUserData(
            categories = listOf(category("c_a", "A", parentId = "c_missing")),
        )
        val graph = build(emptyList(), user = userData)
        assertTrue(graph.diagnostics.any { it.kind == DiagnosticKind.ParentMissing })
        assertNull(graph.nodes.getValue("c_a").parentKey)
    }

    @Test
    fun statsMatchCounts() {
        val userData = GraphUserData(
            categories = listOf(
                category("c_root", "检索与生成"),
                category("c_child", "向量检索", "c_root"),
            ),
            assignments = mapOf("向量检索" to "c_child"),
        )
        val notes = listOf(
            note("n_1", body = "[[向量检索]] [[文本分块]]", concepts = listOf("向量检索", "文本分块")),
            note("n_2", body = "[[RAG]]", concepts = listOf("RAG")),
        )
        val graph = build(notes, user = userData)

        assertEquals(3, graph.stats.conceptCount)
        assertEquals(2, graph.stats.categoryCount)
        assertEquals(1, graph.stats.rootCategoryCount)
        assertEquals(2, graph.stats.maxDepth)
        assertEquals(1, graph.stats.classifiedCount)
        assertEquals(2, graph.stats.unclassifiedCount)
        assertEquals(graph.edges.size, graph.stats.relationEdgeCount)
        assertEquals(0, graph.stats.aiSuggestionCount)
        assertEquals(0, graph.stats.droppedAssignments)
    }

    @Test
    fun buildIsDeterministic() {
        val userData = GraphUserData(
            categories = listOf(
                category("c_root", "检索与生成"),
                category("c_child", "向量检索", "c_root"),
            ),
            assignments = mapOf("向量检索" to "c_child"),
            relations = listOf(GraphUserRelation("rag", "向量检索", GraphEdgeKind.APPLIES)),
        )
        val notes = listOf(
            note(
                "n_2",
                body = "[[文本分块]] [[RAG]] 同段。\n\n[[向量检索]]",
                concepts = listOf("文本分块", "RAG", "向量检索"),
            ),
            note(
                "n_1",
                body = "[[向量检索]] [[RAG]] 同段。",
                concepts = listOf("向量检索", "RAG"),
            ),
        )
        val first = build(notes, user = userData)
        val second = build(notes.reversed(), user = userData)
        assertEquals(first, second, "同输入（顺序无关）必须输出逐位相等")
    }
}
