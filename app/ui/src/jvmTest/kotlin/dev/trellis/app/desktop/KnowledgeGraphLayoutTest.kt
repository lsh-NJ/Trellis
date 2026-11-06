package dev.trellis.app.desktop

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 知识网络 v1.2 森林式树布局的纯逻辑单测：不碰 Compose、不读文件、不用时钟。
 *
 * 图数据全部在用例里直接构造（分类 = [GraphNodeKind.CATEGORY]，概念 = [GraphNodeKind.CONCEPT]，
 * 概念 `parentKey` 指向所属分类，`null` 表示未分类；[graphOf] 会按 Build 的规则派生 `childrenOf`）。
 *
 * 覆盖：每类一棵树、父居中于子跨度、同层不重叠、森林换行、折叠剪枝、未分类最后、
 * 确定性、warm 根序、bounds、估宽与 LOD 工具函数。
 */
class KnowledgeGraphLayoutTest {

    // ------------------------------------------------------------------ 空图 / 单树

    @Test
    fun noNodesYieldsEmpty() {
        val layout = layoutOf(KnowledgeGraph.Empty)
        assertEquals(GraphLayout.Empty, layout)
        assertEquals(GraphRect.Zero, layout.bounds)
        assertTrue(layout.nodes.isEmpty())
        assertTrue(layout.roots.isEmpty())
        assertTrue(layout.links.isEmpty())
        assertTrue(layout.collapsed.isEmpty())
    }

    @Test
    fun singleConceptUnderRoot() {
        val graph = graphOf(
            category("c_root", "根"),
            concept("k_1", "概念", parentKey = "c_root"),
        )
        val layout = layoutOf(graph)

        assertEquals(listOf("c_root"), layout.roots)
        assertEquals(2, layout.nodes.size)
        val root = layout.nodes.getValue("c_root")
        val child = layout.nodes.getValue("k_1")
        assertEquals(0, root.depth)
        assertEquals(1, child.depth)
        assertEquals(GraphNodeKind.CATEGORY, root.kind)
        assertEquals(GraphNodeKind.CONCEPT, child.kind)
        assertEquals(root.bounds.centerX, child.bounds.centerX, 0.01f)
        assertEquals(rowStep, child.bounds.top - root.bounds.top, 0.01f)
        assertEquals(listOf(GraphLink("c_root", "k_1")), layout.links)
        assertTrue(layout.bounds.contains(root.bounds.topLeft))
        assertTrue(layout.bounds.contains(GraphPoint(child.bounds.right, child.bounds.bottom)))
    }

    @Test
    fun singleCategoryWithTwoConcepts() {
        val graph = graphOf(
            category("c_root", "根"),
            // 概念顺序按 (noteCount 降序, name, key)：显式给 noteCount，避免中文 codepoint 顺序影响断言。
            concept("k_a", "甲", parentKey = "c_root", noteCount = 5),
            concept("k_b", "乙", parentKey = "c_root", noteCount = 3),
        )
        val layout = layoutOf(graph)

        assertEquals(listOf(GraphLink("c_root", "k_a"), GraphLink("c_root", "k_b")), layout.links)
        val a = layout.nodes.getValue("k_a").bounds
        val b = layout.nodes.getValue("k_b").bounds
        assertTrue(a.right < b.left)
        assertEquals(18f, b.left - a.right, 0.01f) // 默认 nodeGapX
        assertEquals(a.top, b.top, 0.01f)
        val root = layout.nodes.getValue("c_root").bounds
        // 父节点水平居中于两个子节点的跨度
        assertEquals((a.centerX + b.centerX) / 2f, root.centerX, 0.01f)
        assertTrue(root.top < a.top)
    }

    @Test
    fun siblingsDoNotOverlap() {
        val nodes = arrayOf<GraphNode>(
            category("c_root", "根"),
            concept("k_0", "甲", parentKey = "c_root"),
            concept("k_1", "乙", parentKey = "c_root"),
            concept("k_2", "丙", parentKey = "c_root"),
            concept("k_3", "丁", parentKey = "c_root"),
            concept("k_4", "戊", parentKey = "c_root"),
        )
        val layout = layoutOf(graphOf(*nodes))

        val children = layout.links
            .filter { it.fromKey == "c_root" }
            .map { layout.nodes.getValue(it.toKey).bounds }
        assertEquals(5, children.size)
        for (i in children.indices) {
            for (j in i + 1 until children.size) assertNoOverlap(children[i], children[j])
        }
    }

    // ------------------------------------------------------------------ 顺序 / 折叠 / 未分类

    @Test
    fun categoriesAndConceptsOrdering() {
        val graph = KnowledgeGraph(
            nodes = linkedMapOf(
                "c_root" to category("c_root", "根"),
                "c_sub" to category("c_sub", "子类", parentKey = "c_root", sortOrder = 1),
                "k_1" to concept("k_1", "概念", parentKey = "c_root", noteCount = 9),
            ),
            childrenOf = mapOf<String?, List<String>>(
                null to listOf("c_root"),
                "c_root" to listOf("c_sub", "k_1"),
            ),
        )
        val layout = layoutOf(graph)

        // Build 契约：同一父节点下子分类在前、概念在后。
        assertEquals(listOf(GraphLink("c_root", "c_sub"), GraphLink("c_root", "k_1")), layout.links)
        assertEquals(1, layout.nodes.getValue("c_sub").depth)
        assertEquals(1, layout.nodes.getValue("k_1").depth)
        assertTrue(centerOf(layout, "c_sub").x < centerOf(layout, "k_1").x)
    }

    @Test
    fun collapsedCategoryHidesChildren() {
        val graph = graphOf(
            category("c_root", "根"),
            category("c_mid", "中类", parentKey = "c_root"),
            concept("k_1", "概念", parentKey = "c_mid"),
        )
        val expanded = layoutOf(graph)
        val collapsed = layoutOf(graph, collapsed = setOf("c_mid"))

        assertTrue(expanded.nodes.containsKey("k_1"))
        assertFalse(collapsed.nodes.containsKey("k_1"))
        assertEquals(2, collapsed.nodes.size)
        assertTrue(collapsed.nodes.getValue("c_mid").folded)
        assertFalse(collapsed.nodes.getValue("c_root").folded)
        assertEquals(setOf("c_mid"), collapsed.collapsed)
        assertEquals(listOf(GraphLink("c_root", "c_mid")), collapsed.links)
        assertTrue(collapsed.bounds.height < expanded.bounds.height)
    }

    @Test
    fun collapsingRootLeavesOneNode() {
        val graph = graphOf(
            category("c_root", "根"),
            category("c_mid", "中类", parentKey = "c_root"),
            concept("k_1", "概念", parentKey = "c_mid"),
        )
        val layout = layoutOf(graph, collapsed = setOf("c_root"))

        assertEquals(listOf("c_root"), layout.roots)
        assertEquals(1, layout.nodes.size)
        assertTrue(layout.nodes.getValue("c_root").folded)
        assertTrue(layout.links.isEmpty())
        assertEquals(setOf("c_root"), layout.collapsed)
        assertTrue(layout.bounds.contains(layout.nodes.getValue("c_root").bounds.topLeft))
    }

    @Test
    fun unclassifiedIsLastRoot() {
        val graph = graphOf(
            category("c_a", "甲类", sortOrder = 0),
            concept("k_a", "甲概念", parentKey = "c_a"),
            category("c_b", "乙类", sortOrder = 1),
            concept("k_b", "乙概念", parentKey = "c_b"),
            concept("k_loose1", "散一"),
            concept("k_loose2", "散二"),
        )
        val layout = layoutOf(graph)

        assertEquals(listOf("c_a", "c_b", GRAPH_UNCLASSIFIED_KEY), layout.roots)
        val unclassified = layout.nodes.getValue(GRAPH_UNCLASSIFIED_KEY)
        assertEquals(GraphNodeKind.CATEGORY, unclassified.kind)
        assertEquals(0, unclassified.depth)
        assertFalse(unclassified.folded)
        assertEquals(1, layout.nodes.getValue("k_loose1").depth)
        assertEquals(1, layout.nodes.getValue("k_loose2").depth)
        assertTrue(layout.links.contains(GraphLink(GRAPH_UNCLASSIFIED_KEY, "k_loose1")))
        assertTrue(layout.links.contains(GraphLink(GRAPH_UNCLASSIFIED_KEY, "k_loose2")))
        assertTrue(unclassified.bounds.left > layout.nodes.getValue("c_b").bounds.left)
    }

    // ------------------------------------------------------------------ 森林 / 换行 / 深树

    @Test
    fun multipleTreesAreSeparated() {
        val graph = graphOf(
            // 根顺序按 (sortOrder, name, key)：显式给 sortOrder，避免中文 codepoint 顺序影响断言。
            category("c_a", "甲类", sortOrder = 0),
            concept("k_a1", "甲一", parentKey = "c_a"),
            concept("k_a2", "甲二", parentKey = "c_a"),
            category("c_b", "乙类", sortOrder = 1),
            concept("k_b1", "乙一", parentKey = "c_b"),
            concept("k_b2", "乙二", parentKey = "c_b"),
        )
        val layout = layoutOf(graph)

        val firstTree = boundsUnder(layout, "c_a")
        val secondTree = boundsUnder(layout, "c_b")
        assertEquals(72f, secondTree.left - firstTree.right, 0.01f) // 默认 treeGapX
        assertEquals(firstTree.top, secondTree.top, 0.01f)
        assertTrue(firstTree.right < secondTree.left)
    }

    @Test
    fun deepChainTenLevels() {
        val nodes = ArrayList<GraphNode>()
        nodes.add(category("c_0", "第0层"))
        for (level in 1 until 10) {
            nodes.add(category("c_$level", "第${level}层", parentKey = "c_${level - 1}"))
        }
        nodes.add(concept("k_deep", "叶子", parentKey = "c_9"))
        val layout = layoutOf(graphOf(*nodes.toTypedArray()))

        assertEquals(11, layout.nodes.size)
        assertEquals(10, layout.links.size)
        for (level in 0 until 10) assertEquals(level, layout.nodes.getValue("c_$level").depth)
        assertEquals(10, layout.nodes.getValue("k_deep").depth)
        for (level in 1 until 10) {
            val parent = layout.nodes.getValue("c_${level - 1}").bounds
            val child = layout.nodes.getValue("c_$level").bounds
            assertTrue(child.top > parent.top)
            // 单链：每层父节点都居中于唯一的子节点
            assertEquals(parent.centerX, child.centerX, 0.01f)
        }
    }

    @Test
    fun wideForestWrapsRows() {
        val nodes = ArrayList<GraphNode>()
        for (index in 0 until 6) {
            nodes.add(category("c_$index", "a$index"))
            nodes.add(concept("k_$index", "b$index", parentKey = "c_$index"))
        }
        val options = LayoutOptions(maxCanvasWidth = 400f)
        val layout = layoutOf(graphOf(*nodes.toTypedArray()), options = options)

        assertEquals(6, layout.roots.size)
        val rootTops = layout.roots.map { layout.nodes.getValue(it).bounds.top }
        val rows = rootTops.distinct()
        assertEquals(2, rows.size) // 一行放 3 棵，共两行
        assertEquals(listOf(3, 3), rows.map { rowTop -> rootTops.count { abs(it - rowTop) < 0.01f } })
        assertEquals(32f, rows.first(), 0.01f) // 第一行从 padding 起
        assertTrue(rows.last() > rows.first())
    }

    // ------------------------------------------------------------------ 确定性 / warm

    @Test
    fun deterministic() {
        val graph = sampleGraph()
        val first = layoutOf(graph, collapsed = setOf("c_a_sub"))
        val second = layoutOf(graph, collapsed = setOf("c_a_sub"))

        assertEquals(first, second)
        assertEquals(first.nodes.keys.toList(), second.nodes.keys.toList())
        assertEquals(first.roots, second.roots)
        assertEquals(first.links, second.links)
        assertEquals(first.bounds, second.bounds)
        assertEquals(first.collapsed, second.collapsed)
    }

    @Test
    fun inputOrderIrrelevant() {
        val nodes = arrayOf<GraphNode>(
            category("c_a", "甲", sortOrder = 0),
            concept("k_a", "甲概念", parentKey = "c_a"),
            category("c_b", "乙", sortOrder = 1),
            concept("k_b", "乙概念", parentKey = "c_b"),
            concept("k_loose", "散概念"),
        )
        val forward = layoutOf(graphOf(*nodes))
        val reversed = layoutOf(graphOf(*nodes.reversedArray()))

        assertEquals(forward.roots, reversed.roots)
        assertEquals(forward.links, reversed.links)
        assertEquals(forward.nodes, reversed.nodes)
        assertEquals(forward.bounds, reversed.bounds)
    }

    @Test
    fun warmRootOrderIsRespected() {
        val graph = graphOf(
            category("c_a", "甲", sortOrder = 0),
            category("c_b", "乙", sortOrder = 1),
            category("c_c", "丙", sortOrder = 2),
            concept("k_a", "甲概念", parentKey = "c_a"),
        )
        val cold = layoutOf(graph)
        assertEquals(listOf("c_a", "c_b", "c_c"), cold.roots)

        // 指纹命中：按缓存顺序重排根，未知 key 忽略，新根接在后面。
        val warm = LayoutCache(
            fingerprint = layoutFingerprint(graph),
            rootOrder = listOf("c_c", "c_a", "c_missing"),
        )
        val warmed = layoutOf(graph, warm = warm)
        assertEquals(listOf("c_c", "c_a", "c_b"), warmed.roots)
        assertEquals(cold.nodes.keys, warmed.nodes.keys)
        assertTrue(warmed.nodes.getValue("c_c").bounds.left < warmed.nodes.getValue("c_a").bounds.left)

        // 指纹不匹配：退回冷计算的确定性顺序（sortOrder → name → key）。
        val stale = LayoutCache(
            fingerprint = layoutFingerprint(graph) xor 1L,
            rootOrder = listOf("c_c", "c_a"),
        )
        assertEquals(listOf("c_a", "c_b", "c_c"), layoutOf(graph, warm = stale).roots)
    }

    @Test
    fun warmKeepsUnclassifiedLast() {
        val graph = graphOf(
            category("c_a", "甲", sortOrder = 0),
            category("c_b", "乙", sortOrder = 1),
            concept("k_loose", "散概念"),
        )
        val warm = LayoutCache(
            fingerprint = layoutFingerprint(graph),
            rootOrder = listOf(GRAPH_UNCLASSIFIED_KEY, "c_b"),
        )
        assertEquals(
            listOf("c_b", "c_a", GRAPH_UNCLASSIFIED_KEY),
            layoutOf(graph, warm = warm).roots,
        )
    }

    // ------------------------------------------------------------------ bounds / 可见性

    @Test
    fun boundsContainAllNodes() {
        val layout = layoutOf(sampleGraph())
        assertTrue(layout.nodes.isNotEmpty())

        for (box in layout.nodes.values) {
            assertTrue(layout.bounds.contains(box.bounds.topLeft), "bounds 应包含节点 ${box.key} 的左上角")
            assertTrue(
                layout.bounds.contains(GraphPoint(box.bounds.right, box.bounds.bottom)),
                "bounds 应包含节点 ${box.key} 的右下角",
            )
        }
        val minLeft = layout.nodes.values.minOf { it.bounds.left }
        val minTop = layout.nodes.values.minOf { it.bounds.top }
        val maxRight = layout.nodes.values.maxOf { it.bounds.right }
        val maxBottom = layout.nodes.values.maxOf { it.bounds.bottom }
        assertEquals(32f, minLeft - layout.bounds.left, 0.01f) // padding
        assertEquals(32f, minTop - layout.bounds.top, 0.01f)
        assertEquals(32f, layout.bounds.right - maxRight, 0.01f)
        assertEquals(32f, layout.bounds.bottom - maxBottom, 0.01f)
    }

    @Test
    fun linksOnlyBetweenVisibleNodes() {
        val graph = sampleGraph()
        val layout = layoutOf(graph, collapsed = setOf("c_a_sub"))

        for (link in layout.links) {
            assertTrue(link.fromKey in layout.nodes, "link 起点应在可见节点里：$link")
            assertTrue(link.toKey in layout.nodes, "link 终点应在可见节点里：$link")
        }
        val incoming = layout.links.groupingBy { it.toKey }.eachCount()
        for (key in layout.roots) assertFalse(incoming.containsKey(key), "根节点不应有入边：$key")
        for (key in layout.nodes.keys) {
            if (key !in layout.roots) assertEquals(1, incoming[key], "非根节点应恰好一条入边：$key")
        }
    }

    @Test
    fun collapsedKeysOutsideGraphIgnored() {
        val graph = sampleGraph()
        val baseline = layoutOf(graph)
        val ignored = layoutOf(graph, collapsed = setOf("missing_category", "k_loose"))

        // 不存在的分类、概念 key 都不产生折叠（概念本来就没有子节点）。
        assertEquals(baseline, ignored)
        assertTrue(ignored.collapsed.isEmpty())
    }

    @Test
    fun emptyCategoryIsLeaf() {
        val layout = layoutOf(graphOf(category("c_empty", "空分类")))
        assertEquals(listOf("c_empty"), layout.roots)
        assertEquals(1, layout.nodes.size)
        assertTrue(layout.links.isEmpty())
        assertFalse(layout.nodes.getValue("c_empty").folded)
        assertEquals(0, layout.nodes.getValue("c_empty").depth)
    }

    // ------------------------------------------------------------------ 工具函数：估宽 / LOD / 节点宽

    @Test
    fun estimateLabelWidthCjkWiderThanAscii() {
        assertTrue(estimateLabelWidth("概念树", 12f) > estimateLabelWidth("abc", 12f))
        assertEquals(0f, estimateLabelWidth("", 12f))
        assertEquals(0f, estimateLabelWidth("概念", 0f))
        assertTrue(estimateLabelWidth("Ａ", 12f) > estimateLabelWidth("A", 12f)) // 全角 > 半角
        assertTrue(estimateLabelWidth("abc", 14f) > estimateLabelWidth("abc", 12f))
    }

    @Test
    fun estimateLabelWidthMonotonic() {
        val widths = listOf("概", "概念", "概念树", "概念树布局").map { estimateLabelWidth(it, 12f) }
        for (index in 1 until widths.size) {
            assertTrue(widths[index] > widths[index - 1], "更长的名字应估得更宽：$widths")
        }
        assertTrue(estimateLabelWidth("a", 12f) > 0f)
    }

    @Test
    fun lodForThresholds() {
        val viewportArea = 960_000f
        assertEquals(LodLevel.Full, lodFor(1f, 100, viewportArea))
        assertEquals(LodLevel.Compact, lodFor(0.54f, 100, viewportArea))
        assertEquals(LodLevel.Compact, lodFor(1f, 100, 1000f))
        assertEquals(LodLevel.Aggregate, lodFor(0.2f, 100, viewportArea))
        assertEquals(LodLevel.Aggregate, lodFor(1f, 2001, viewportArea))
        assertEquals(LodLevel.Aggregate, lodFor(1f, 5000, viewportArea))
        assertEquals(LodLevel.TooLarge, lodFor(1f, 5001, viewportArea))
        assertEquals(LodLevel.Aggregate, lodFor(Float.NaN, 100, viewportArea))
        assertEquals(LodLevel.Full, lodFor(1f, 100, 18_000f))
        assertEquals(LodLevel.Compact, lodFor(1f, 100, 17_999f))
    }

    @Test
    fun nodeWidthClamped() {
        val longName = "超长名字".repeat(40)
        val graph = graphOf(
            category("c_root", "根"),
            concept("k_long", longName, parentKey = "c_root"),
            concept("k_short", "a", parentKey = "c_root"),
        )
        val layout = layoutOf(graph)
        assertEquals(260f, layout.nodes.getValue("k_long").bounds.width, 0.01f) // 默认 maxNodeWidth
        assertEquals(64f, layout.nodes.getValue("k_short").bounds.width, 0.01f) // 默认 minNodeWidth

        val custom = layoutOf(graph, options = LayoutOptions(minNodeWidth = 40f, maxNodeWidth = 100f))
        assertEquals(100f, custom.nodes.getValue("k_long").bounds.width, 0.01f)
        assertEquals(40f, custom.nodes.getValue("k_short").bounds.width, 0.01f)
    }

    /**
     * 未分类虚拟根下的散概念折成 4 行一列的网格。
     *
     * 33 个未分类概念单行排开会把画布拉成一条线（评审 §3.7），所以按列优先排：
     * 一列最多 4 行，列内水平居中，列与列之间留 nodeGapX。
     */
    @Test
    fun unclassifiedConceptsWrapIntoColumns() {
        val graph = graphOf(*(1..9).map { concept("k_$it", "概念$it") }.toTypedArray())
        val layout = layoutOf(graph)
        val root = layout.nodes.getValue(GRAPH_UNCLASSIFIED_KEY)
        val children = (1..9).map { layout.nodes.getValue("k_$it") }
        // 一列 4 行：9 个概念铺成 3 列（4 + 4 + 1 行）。
        val rows = children.map { it.bounds.top }.distinct().sorted()
        assertEquals(4, rows.size, "应折成 4 行：" + rows)
        val columns = children.map { it.bounds.left }.distinct().sorted()
        assertEquals(3, columns.size, "应排成 3 列：" + columns)
        // 第一列第 1 行在虚拟根正下方一层；第 4 行比第 1 行低 3 个（概念高 + 网格行距）。
        assertEquals(rowStep, children[0].bounds.top - root.bounds.top, 0.01f)
        assertEquals(3 * (LayoutOptions().nodeHeight + 10f), children[3].bounds.top - children[0].bounds.top, 0.01f)
        // 第二列从第一列右边一个 nodeGapX 开始。
        assertEquals(LayoutOptions().nodeGapX, columns[1] - columns[0] - children[0].bounds.width, 0.01f)
    }

    // ------------------------------------------------------------------ 构造工具

    /** 一层的纵向步长（与实现同口径：`max(概念高, 分类高) + 层间距`）。 */
    private val rowStep: Float = maxOf(LayoutOptions().nodeHeight, LayoutOptions().categoryHeight) + LayoutOptions().rowGapY

    private fun layoutOf(
        graph: KnowledgeGraph,
        collapsed: Set<String> = emptySet(),
        warm: LayoutCache? = null,
        options: LayoutOptions = LayoutOptions(),
    ): GraphLayout = layoutKnowledgeGraph(graph, collapsed, warm, options)

    private fun category(
        key: String,
        name: String,
        parentKey: String? = null,
        sortOrder: Int = 0,
    ): GraphNode = GraphNode(
        key = key,
        kind = GraphNodeKind.CATEGORY,
        name = name,
        parentKey = parentKey,
        sortOrder = sortOrder,
        origin = NodeOrigin.USER,
    )

    private fun concept(
        key: String,
        name: String,
        parentKey: String? = null,
        noteCount: Int = 0,
    ): GraphNode = GraphNode(
        key = key,
        kind = GraphNodeKind.CONCEPT,
        name = name,
        parentKey = parentKey,
        noteCount = noteCount,
    )

    /**
     * 按 Build 的 `childrenOf` 规则建图：根分类在前、未分类概念在后；同一父节点下子分类在前、概念在后。
     * （[layoutKnowledgeGraph] 也做了兜底，但测试里保持与生产构建同形，避免测的路径不一致。）
     */
    private fun graphOf(vararg nodes: GraphNode): KnowledgeGraph {
        val nodesByKey = LinkedHashMap<String, GraphNode>(nodes.size)
        for (node in nodes) nodesByKey[node.key] = node
        val categoriesAt = LinkedHashMap<String?, MutableList<GraphNode>>()
        val conceptsAt = LinkedHashMap<String?, MutableList<GraphNode>>()
        for (node in nodesByKey.values) {
            when (node.kind) {
                GraphNodeKind.CATEGORY -> categoriesAt.getOrPut(node.parentKey) { ArrayList() }.add(node)
                GraphNodeKind.CONCEPT -> conceptsAt.getOrPut(node.parentKey) { ArrayList() }.add(node)
            }
        }
        val childrenOf = LinkedHashMap<String?, List<String>>()
        val rootCategories = categoriesAt[null].orEmpty().sortedWith(CATEGORY_ORDER)
        val rootConcepts = conceptsAt[null].orEmpty().sortedWith(CONCEPT_ORDER)
        if (rootCategories.isNotEmpty() || rootConcepts.isNotEmpty()) {
            childrenOf[null] = rootCategories.map { it.key } + rootConcepts.map { it.key }
        }
        for (categoryKey in nodesByKey.values.filter { it.kind == GraphNodeKind.CATEGORY }.map { it.key }) {
            val subCategories = categoriesAt[categoryKey].orEmpty().sortedWith(CATEGORY_ORDER)
            val concepts = conceptsAt[categoryKey].orEmpty().sortedWith(CONCEPT_ORDER)
            if (subCategories.isEmpty() && concepts.isEmpty()) continue
            childrenOf[categoryKey] = subCategories.map { it.key } + concepts.map { it.key }
        }
        return KnowledgeGraph(nodes = nodesByKey, childrenOf = childrenOf)
    }

    /** 一棵稍微复杂的样图：两个根分类、一层子分类、一个未分类散概念。 */
    private fun sampleGraph(): KnowledgeGraph = graphOf(
        category("c_a", "甲类", sortOrder = 0),
        category("c_a_sub", "甲子类", parentKey = "c_a", sortOrder = 0),
        concept("k_a1", "甲一", parentKey = "c_a"),
        concept("k_a2", "甲二", parentKey = "c_a_sub"),
        category("c_b", "乙类", sortOrder = 1),
        concept("k_b1", "乙一", parentKey = "c_b"),
        concept("k_loose", "散概念"),
    )

    private fun centerOf(layout: GraphLayout, key: String): GraphPoint =
        layout.nodes.getValue(key).bounds.center

    /** 从根沿 links 做 BFS，收集该树里的所有 key。 */
    private fun keysUnder(layout: GraphLayout, rootKey: String): Set<String> {
        val children = layout.links.groupBy({ it.fromKey }, { it.toKey })
        val visited = LinkedHashSet<String>()
        val pending = ArrayDeque<String>()
        pending.add(rootKey)
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (!visited.add(key)) continue
            children[key]?.forEach { pending.add(it) }
        }
        return visited
    }

    private fun boundsUnder(layout: GraphLayout, rootKey: String): GraphRect {
        var bounds = layout.nodes.getValue(rootKey).bounds
        for (key in keysUnder(layout, rootKey)) bounds = bounds.union(layout.nodes.getValue(key).bounds)
        return bounds
    }

    private fun assertNoOverlap(first: GraphRect, second: GraphRect) {
        val overlap = first.left < second.right && second.left < first.right &&
            first.top < second.bottom && second.top < first.bottom
        assertFalse(overlap, "节点不应重叠：$first / $second")
    }
}

/** 分类排序（Build 契约：sortOrder → name → key）。 */
private val CATEGORY_ORDER: Comparator<GraphNode> =
    compareBy<GraphNode>({ it.sortOrder }, { it.name }, { it.key })

/** 概念排序（Build 契约：noteCount 降序 → name → key）。 */
private val CONCEPT_ORDER: Comparator<GraphNode> =
    compareByDescending<GraphNode> { it.noteCount }.thenBy { it.name }.thenBy { it.key }
