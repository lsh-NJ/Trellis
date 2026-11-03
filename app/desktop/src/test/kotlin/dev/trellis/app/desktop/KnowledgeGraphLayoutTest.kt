package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [KnowledgeGraphLayout] 的纯逻辑单测：不碰 Compose、不读文件、不用时钟。
 * 图数据全部在用例里直接构造（分类 = [GraphNodeKind.CATEGORY]，概念 = [GraphNodeKind.CONCEPT]，
 * 概念 `parentKey` 指向所属分类，`null` 表示未分类）。
 */
class KnowledgeGraphLayoutTest {

    // ------------------------------------------------------------------ 构造工具

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

    /** 用节点列表拼图；childrenOf 只是占位（布局层按 parentKey 建树，不依赖它的顺序）。 */
    private fun graphOf(vararg nodes: GraphNode): KnowledgeGraph {
        val nodesByKey = LinkedHashMap<String, GraphNode>()
        for (node in nodes) nodesByKey[node.key] = node
        val children = LinkedHashMap<String?, MutableList<String>>()
        for (node in nodes) children.getOrPut(node.parentKey) { ArrayList() }.add(node.key)
        return KnowledgeGraph(
            nodes = nodesByKey,
            childrenOf = children.mapValues { (_, value) -> value.toList() },
        )
    }

    /** 多宽度芯片 + 两层嵌套分类的样例图，供多数用例复用。 */
    private fun sampleGraph(): KnowledgeGraph = graphOf(
        category("c_retrieval", "检索", sortOrder = 0),
        category("c_chunking", "分块", parentKey = "c_retrieval", sortOrder = 0),
        category("c_embedding", "向量", sortOrder = 1),
        concept("k_chunk", "文本分块", parentKey = "c_chunking", noteCount = 3),
        concept("k_rerank", "重排序", parentKey = "c_retrieval", noteCount = 2),
        concept("k_recall", "召回率", parentKey = "c_retrieval", noteCount = 1),
        concept("k_vector", "向量数据库", parentKey = "c_embedding", noteCount = 4),
        concept("k_ann", "近似最近邻 ANN", parentKey = "c_embedding", noteCount = 1),
    )

    private fun assertRectContains(outer: GraphRect, inner: GraphRect) {
        assertTrue(outer.left < inner.left, "内框 left 应在外框内：$outer / $inner")
        assertTrue(outer.top < inner.top, "内框 top 应在外框内：$outer / $inner")
        assertTrue(outer.right > inner.right, "内框 right 应在外框内：$outer / $inner")
        assertTrue(outer.bottom > inner.bottom, "内框 bottom 应在外框内：$outer / $inner")
    }

    private fun assertNoOverlap(rects: List<GraphRect>) {
        for (first in rects.indices) {
            for (second in first + 1 until rects.size) {
                val overlap = rects[first].intersect(rects[second])
                assertTrue(overlap.isEmpty, "两个矩形重叠：${rects[first]} / ${rects[second]}")
            }
        }
    }

    private fun assertScreenRectInside(camera: Camera, rect: GraphRect, viewport: GraphSize) {
        val topLeft = camera.worldToScreen(rect.topLeft)
        val bottomRight = camera.worldToScreen(GraphPoint(rect.right, rect.bottom))
        assertTrue(topLeft.x >= -EPS && topLeft.y >= -EPS, "左上角越界：$topLeft")
        assertTrue(bottomRight.x <= viewport.width + EPS && bottomRight.y <= viewport.height + EPS, "右下角越界：$bottomRight")
    }

    // ------------------------------------------------------------------ 空图与基础测量

    @Test
    fun emptyGraphProducesEmptyLayout() {
        val layout = layoutKnowledgeGraph(KnowledgeGraph.Empty, emptySet(), null)
        assertEquals(GraphLayout.Empty, layout)
        assertEquals(GraphRect.Zero, layout.bounds)
        assertTrue(layout.roots.isEmpty())
        assertTrue(layout.boxesByKey.isEmpty())
        assertTrue(layout.chipsByKey.isEmpty())
    }

    @Test
    fun singleCategoryKeepsAllChipsInsideBox() {
        val graph = graphOf(
            category("c_a", "分类甲"),
            concept("k_1", "文本分块", parentKey = "c_a"),
            concept("k_2", "重排序", parentKey = "c_a"),
            concept("k_3", "RAG 检索增强", parentKey = "c_a"),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)
        assertEquals(1, layout.roots.size)
        val box = assertNotNull(layout.boxesByKey["c_a"])
        assertEquals(3, box.chips.size)
        assertEquals(3, layout.chipsByKey.size)
        assertFalse(box.collapsed)
        for (chipKey in box.chips) {
            val chip = assertNotNull(layout.chipsByKey[chipKey])
            assertTrue(box.bounds.contains(chip.bounds.topLeft), "芯片左上角应在框内：$chip")
            assertTrue(
                box.bounds.contains(GraphPoint(chip.bounds.right, chip.bounds.bottom)),
                "芯片右下角应在框内：$chip",
            )
            assertTrue(chip.bounds.left >= box.bounds.left + 10f - EPS, "芯片应在框内 padding 之后：$chip")
            assertTrue(chip.bounds.top >= box.headerBounds.bottom - EPS, "芯片应在标题行下方：$chip")
        }
        assertTrue(layout.bounds.contains(box.bounds.topLeft))
        assertTrue(layout.bounds.contains(GraphPoint(box.bounds.right, box.bounds.bottom)))
    }

    @Test
    fun nestedChildBoxStaysInsideParentBox() {
        val graph = graphOf(
            category("c_root", "领域"),
            category("c_child", "子领域", parentKey = "c_root"),
            concept("k_root", "根概念", parentKey = "c_root"),
            concept("k_child", "子概念", parentKey = "c_child"),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)
        val root = assertNotNull(layout.boxesByKey["c_root"])
        val child = assertNotNull(layout.boxesByKey["c_child"])
        assertEquals(0, root.depth)
        assertEquals(1, child.depth)
        assertEquals(listOf("c_child"), root.children.map { it.categoryKey })
        assertEquals(listOf("k_child"), child.chips)
        assertRectContains(root.bounds, child.bounds)
        assertRectContains(root.bounds, child.headerBounds)
        assertTrue(layout.bounds.contains(GraphPoint(child.bounds.right, child.bounds.bottom)))
    }

    @Test
    fun chipsDoNotOverlap() {
        val layout = layoutKnowledgeGraph(sampleGraph(), emptySet(), null)
        assertNoOverlap(layout.chipsByKey.values.map { it.bounds })
    }

    @Test
    fun siblingBoxesDoNotOverlap() {
        val graph = graphOf(
            category("c_1", "一", sortOrder = 0),
            category("c_2", "二", sortOrder = 1),
            category("c_11", "一甲", parentKey = "c_1", sortOrder = 0),
            category("c_12", "一乙", parentKey = "c_1", sortOrder = 1),
            category("c_21", "二甲", parentKey = "c_2", sortOrder = 0),
            concept("k_11", "概念一一", parentKey = "c_11"),
            concept("k_12", "概念一二", parentKey = "c_12"),
            concept("k_21", "概念二一", parentKey = "c_21"),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)
        assertNoOverlap(layout.roots.map { it.bounds })
        val firstRoot = assertNotNull(layout.boxesByKey["c_1"])
        assertEquals(2, firstRoot.children.size)
        assertNoOverlap(firstRoot.children.map { it.bounds })
        for (child in firstRoot.children) assertRectContains(firstRoot.bounds, child.bounds)
    }

    // ------------------------------------------------------------------ 确定性

    @Test
    fun layoutIsDeterministic() {
        val graph = sampleGraph()
        val collapsed = setOf("c_retrieval")
        val first = layoutKnowledgeGraph(graph, collapsed, null)
        val second = layoutKnowledgeGraph(graph, collapsed, null)
        assertEquals(first, second)
    }

    @Test
    fun layoutIgnoresInputMapOrder() {
        val nodes = listOf(
            category("c_a", "甲"),
            category("c_b", "乙", sortOrder = 1),
            category("c_a1", "甲一", parentKey = "c_a"),
            concept("k_1", "概念一", parentKey = "c_a", noteCount = 3),
            concept("k_2", "概念二", parentKey = "c_a1", noteCount = 1),
            concept("k_3", "概念三", parentKey = "c_b", noteCount = 2),
        )
        val forward = layoutKnowledgeGraph(graphOf(*nodes.toTypedArray()), emptySet(), null)
        val backward = layoutKnowledgeGraph(graphOf(*nodes.reversed().toTypedArray()), emptySet(), null)
        assertEquals(forward, backward)
    }

    // ------------------------------------------------------------------ 折叠

    @Test
    fun collapsingCategoryHidesChipsAndShrinksBox() {
        val graph = graphOf(
            category("c_a", "分类甲"),
            category("c_a1", "子类", parentKey = "c_a"),
            category("c_b", "分类乙"),
            concept("k_1", "概念一", parentKey = "c_a"),
            concept("k_2", "概念二", parentKey = "c_a"),
            concept("k_3", "概念三", parentKey = "c_a1"),
            concept("k_4", "概念四", parentKey = "c_b"),
        )
        val expanded = layoutKnowledgeGraph(graph, emptySet(), null)
        val folded = layoutKnowledgeGraph(graph, setOf("c_a"), null)

        assertEquals(setOf("c_a"), folded.collapsed)
        assertEquals(3, expanded.boxesByKey.size)
        assertEquals(2, folded.boxesByKey.size)
        assertNull(folded.boxesByKey["c_a1"])

        val foldedBox = assertNotNull(folded.boxesByKey["c_a"])
        assertTrue(foldedBox.collapsed)
        assertTrue(foldedBox.chips.isEmpty())
        assertTrue(foldedBox.children.isEmpty())
        assertEquals(30f + 2f * 10f, foldedBox.bounds.height, EPS)

        val expandedBox = assertNotNull(expanded.boxesByKey["c_a"])
        assertFalse(expandedBox.collapsed)
        assertTrue(foldedBox.bounds.height < expandedBox.bounds.height)
        // 被折叠的子树里的芯片不出现；没折叠的兄弟分类芯片保留。
        assertEquals(setOf("k_4"), folded.chipsByKey.keys)
        assertTrue(expanded.chipsByKey.keys.containsAll(setOf("k_1", "k_2", "k_3", "k_4")))
    }

    @Test
    fun expandingAgainRestoresLayout() {
        val graph = sampleGraph()
        val expanded = layoutKnowledgeGraph(graph, emptySet(), null)
        layoutKnowledgeGraph(graph, setOf("c_chunking"), null)
        assertEquals(expanded, layoutKnowledgeGraph(graph, emptySet(), null))
    }

    // ------------------------------------------------------------------ warm 缓存

    @Test
    fun warmCacheKeepsRootOrderWhenFingerprintMatches() {
        val graph = graphOf(
            category("c_a", "甲", sortOrder = 0),
            category("c_b", "乙", sortOrder = 1),
            category("c_c", "丙", sortOrder = 2),
            concept("k_1", "概念", parentKey = "c_a"),
        )
        val cold = layoutKnowledgeGraph(graph, emptySet(), null)
        assertEquals(listOf("c_a", "c_b", "c_c"), cold.roots.map { it.categoryKey })

        val warm = LayoutCache(
            fingerprint = layoutFingerprint(graph),
            rootOrder = listOf("c_c", "c_a"),
        )
        val warmed = layoutKnowledgeGraph(graph, emptySet(), warm)
        // rootOrder 里仍存在的 key 按缓存顺序；新出现的 key（c_b）按自然顺序追加。
        assertEquals(listOf("c_c", "c_a", "c_b"), warmed.roots.map { it.categoryKey })
        assertEquals(cold.boxesByKey.keys, warmed.boxesByKey.keys)
        // 坐标必须重新算（不能复用旧坐标导致重叠）。
        assertNoOverlap(warmed.roots.map { it.bounds })
        assertEquals(setOf("c_a", "c_b", "c_c"), warmed.boxesByKey.keys)
    }

    @Test
    fun warmCacheWithStaleFingerprintFallsBackToColdOrder() {
        val graph = graphOf(
            category("c_a", "甲", sortOrder = 2),
            category("c_b", "乙", sortOrder = 1),
            category("c_c", "丙", sortOrder = 3),
        )
        val stale = LayoutCache(
            fingerprint = layoutFingerprint(graph) + 1L,
            rootOrder = listOf("c_c", "c_b", "c_a"),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), stale)
        // 指纹不匹配 → 忽略 rootOrder，按 (sortOrder, name, key) 冷算。
        assertEquals(listOf("c_b", "c_a", "c_c"), layout.roots.map { it.categoryKey })
        assertEquals(layoutKnowledgeGraph(graph, emptySet(), null), layout)
    }

    // ------------------------------------------------------------------ bounds

    @Test
    fun boundsContainEveryBoxAndChip() {
        val layout = layoutKnowledgeGraph(sampleGraph(), setOf("c_chunking"), null)
        assertFalse(layout.bounds.isEmpty)
        // 内容并集再外扩 padding：最左/最上从 0 开始。
        assertEquals(0f, layout.bounds.left, EPS)
        assertEquals(0f, layout.bounds.top, EPS)
        for (box in layout.boxesByKey.values) {
            assertTrue(layout.bounds.contains(box.bounds.topLeft), "bounds 应包含框：$box")
            assertTrue(layout.bounds.contains(GraphPoint(box.bounds.right, box.bounds.bottom)), "bounds 应包含框：$box")
        }
        for (chip in layout.chipsByKey.values) {
            assertTrue(layout.bounds.contains(chip.bounds.topLeft), "bounds 应包含芯片：$chip")
            assertTrue(layout.bounds.contains(GraphPoint(chip.bounds.right, chip.bounds.bottom)), "bounds 应包含芯片：$chip")
        }
    }

    // ------------------------------------------------------------------ 命中测试

    @Test
    fun hitTestFindsChipHeaderBoxAndEmptySpace() {
        val graph = graphOf(
            category("c_a", "分类甲"),
            category("c_a1", "子类", parentKey = "c_a"),
            concept("k_1", "概念一", parentKey = "c_a1"),
            concept("k_2", "概念二", parentKey = "c_a"),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)

        val chip = assertNotNull(layout.chipsByKey["k_1"])
        assertEquals(GraphHit.Chip("k_1"), layout.hitTest(chip.bounds.center))

        val child = assertNotNull(layout.boxesByKey["c_a1"])
        // 子框标题比父框深：先报子框标题。
        assertEquals(GraphHit.CategoryHeader("c_a1"), layout.hitTest(child.headerBounds.center))

        val root = assertNotNull(layout.boxesByKey["c_a"])
        assertEquals(GraphHit.CategoryHeader("c_a"), layout.hitTest(root.headerBounds.center))

        // 框内 padding 区域（左下角留白）→ 命中父框本身。
        assertEquals(
            GraphHit.CategoryBox("c_a"),
            layout.hitTest(GraphPoint(root.bounds.left + 4f, root.bounds.bottom - 4f)),
        )

        // 空白区域。
        assertNull(layout.hitTest(GraphPoint(layout.bounds.right + 100f, layout.bounds.bottom + 100f)))

        // 折叠后只剩标题行，标题仍可命中。
        val folded = layoutKnowledgeGraph(graph, setOf("c_a"), null)
        val foldedRoot = assertNotNull(folded.boxesByKey["c_a"])
        assertEquals(GraphHit.CategoryHeader("c_a"), folded.hitTest(foldedRoot.headerBounds.center))
    }

    // ------------------------------------------------------------------ 相机

    @Test
    fun zoomToFitFitsBoundsIntoViewport() {
        val layout = layoutKnowledgeGraph(sampleGraph(), emptySet(), null)
        val viewport = GraphSize(1200f, 800f)
        val camera = zoomToFit(layout.bounds, viewport)

        assertTrue(camera.zoom >= 0.25f - EPS && camera.zoom <= 1.5f + EPS, "zoom 应被 clamp：${camera.zoom}")
        assertScreenRectInside(camera, layout.bounds, viewport)
        assertEquals(viewport.width / 2f, camera.worldToScreen(layout.bounds.center).x, EPS)
        assertEquals(viewport.height / 2f, camera.worldToScreen(layout.bounds.center).y, EPS)
        // GraphLayout 扩展形式与顶层函数等价。
        assertEquals(camera, layout.zoomToFit(layout.bounds, viewport))
    }

    @Test
    fun zoomToFitEmptyInputsReturnDefaultCamera() {
        assertEquals(Camera.Default, zoomToFit(GraphRect.Zero, GraphSize(1200f, 800f)))
        assertEquals(Camera.Default, zoomToFit(GraphRect(10f, 10f, 110f, 60f), GraphSize.Zero))
    }

    @Test
    fun fitCategoryFocusesCategoryBoxAndFallsBackToWholeGraph() {
        val graph = graphOf(
            category("c_root", "根", sortOrder = 0),
            category("c_child", "子", parentKey = "c_root", sortOrder = 0),
            category("c_other", "另", sortOrder = 1),
            concept("k_1", "概念一", parentKey = "c_child"),
            concept("k_2", "概念二", parentKey = "c_child"),
            concept("k_3", "概念三", parentKey = "c_other"),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)
        val viewport = GraphSize(1000f, 700f)

        val child = assertNotNull(layout.boxesByKey["c_child"])
        val camera = fitCategory(layout, "c_child", viewport)
        assertScreenRectInside(camera, child.bounds, viewport)
        assertEquals(viewport.width / 2f, camera.worldToScreen(child.bounds.center).x, EPS)

        // null / 不存在的 key → 拟合全图。
        assertEquals(zoomToFit(layout.bounds, viewport), fitCategory(layout, null, viewport))
        assertEquals(zoomToFit(layout.bounds, viewport), fitCategory(layout, "missing", viewport))

        // GraphLayout 扩展形式：存在返回相机，不存在返回 null。
        assertEquals(camera, layout.fitCategory("c_child", viewport, maxZoom = 1.5f))
        assertNull(layout.fitCategory("missing", viewport))
    }

    // ------------------------------------------------------------------ LOD

    @Test
    fun lodForDistinguishesFourLevels() {
        val area = 1200f * 800f
        assertEquals(LodLevel.Full, lodFor(zoom = 1f, conceptCount = 100, viewportArea = area))
        assertEquals(LodLevel.Full, lodFor(zoom = 0.55f, conceptCount = 10, viewportArea = area))
        assertEquals(LodLevel.Compact, lodFor(zoom = 0.54f, conceptCount = 10, viewportArea = area))
        assertEquals(LodLevel.Compact, lodFor(zoom = 1f, conceptCount = 100, viewportArea = 1000f))
        assertEquals(LodLevel.Aggregate, lodFor(zoom = 0.2f, conceptCount = 100, viewportArea = area))
        assertEquals(LodLevel.Full, lodFor(zoom = 1f, conceptCount = 2000, viewportArea = area))
        assertEquals(LodLevel.Aggregate, lodFor(zoom = 1f, conceptCount = 2001, viewportArea = area))
        assertEquals(LodLevel.Aggregate, lodFor(zoom = 1f, conceptCount = 5000, viewportArea = area))
        assertEquals(LodLevel.TooLarge, lodFor(zoom = 1f, conceptCount = 5001, viewportArea = area))
        assertEquals(LodLevel.Aggregate, lodFor(zoom = Float.NaN, conceptCount = 10, viewportArea = area))
    }

    // ------------------------------------------------------------------ 估宽

    @Test
    fun estimateLabelWidthUnderstandsWideCharacters() {
        val cjk = estimateLabelWidth("知识", 12f)
        val ascii = estimateLabelWidth("ab", 12f)
        assertTrue(cjk > ascii, "同字符数 CJK 应比 ASCII 宽：$cjk vs $ascii")
        assertEquals(0f, estimateLabelWidth("", 12f))
        assertEquals(0f, estimateLabelWidth("知识", 0f))
        assertTrue(estimateLabelWidth("，", 12f) > estimateLabelWidth(",", 12f), "全角标点应比半角宽")
        assertTrue(estimateLabelWidth("知识网络", 12f) > estimateLabelWidth("知识", 12f))
        // sp × density 的调用形式与 px 形式等价。
        assertEquals(estimateLabelWidth("知识", 12f), estimateLabelWidth("知识", 6f, 2f))
    }

    // ------------------------------------------------------------------ 未分类

    @Test
    fun unclassifiedBoxComesLastAndHoldsLooseConcepts() {
        val graph = graphOf(
            category("c_a", "分类甲"),
            category("c_b", "分类乙"),
            concept("k_1", "概念一", parentKey = "c_a"),
            concept("k_free_2", "散落二", noteCount = 1),
            concept("k_free_5", "散落五", noteCount = 5),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)
        assertEquals(3, layout.roots.size)
        assertEquals(GRAPH_UNCLASSIFIED_KEY, layout.roots.last().categoryKey)

        val unclassified = assertNotNull(layout.boxesByKey[GRAPH_UNCLASSIFIED_KEY])
        // 芯片按 noteCount 降序。
        assertEquals(listOf("k_free_5", "k_free_2"), unclassified.chips)

        // 折叠未分类：只留标题，只有普通分类的芯片还在。
        val folded = layoutKnowledgeGraph(graph, setOf(GRAPH_UNCLASSIFIED_KEY), null)
        val foldedBox = assertNotNull(folded.boxesByKey[GRAPH_UNCLASSIFIED_KEY])
        assertTrue(foldedBox.collapsed)
        assertTrue(foldedBox.chips.isEmpty())
        assertEquals(setOf("k_1"), folded.chipsByKey.keys)
    }

    @Test
    fun graphWithOnlyLooseConceptsHasSingleUnclassifiedRoot() {
        val graph = graphOf(
            concept("k_1", "概念一", noteCount = 2),
            concept("k_2", "概念二", noteCount = 1),
        )
        val layout = layoutKnowledgeGraph(graph, emptySet(), null)
        assertEquals(1, layout.roots.size)
        val root = layout.roots.single()
        assertEquals(GRAPH_UNCLASSIFIED_KEY, root.categoryKey)
        assertEquals(listOf("k_1", "k_2"), root.chips)
        assertEquals(2, layout.chipsByKey.size)
        assertFalse(layout.bounds.isEmpty)
    }

    // ------------------------------------------------------------------ 深层嵌套

    @Test
    fun fiveLevelTreeNestsStrictly() {
        val nodes = ArrayList<GraphNode>()
        var parentKey: String? = null
        for (level in 0 until 5) {
            val key = "c_$level"
            nodes.add(category(key, "第${level}层分类", parentKey = parentKey, sortOrder = 0))
            parentKey = key
        }
        nodes.add(concept("k_0", "概念零", parentKey = "c_0"))
        nodes.add(concept("k_4a", "概念四甲", parentKey = "c_4"))
        nodes.add(concept("k_4b", "概念四乙", parentKey = "c_4"))

        val layout = layoutKnowledgeGraph(graphOf(*nodes.toTypedArray()), emptySet(), null)
        assertEquals(5, layout.boxesByKey.size)
        for (level in 0 until 5) {
            val box = assertNotNull(layout.boxesByKey["c_$level"])
            assertEquals(level, box.depth)
        }
        // 相邻两层严格嵌套（每层都有 padding，不会贴边）。
        for (level in 0 until 4) {
            val outer = assertNotNull(layout.boxesByKey["c_$level"])
            val inner = assertNotNull(layout.boxesByKey["c_${level + 1}"])
            assertRectContains(outer.bounds, inner.bounds)
            assertRectContains(outer.bounds, inner.headerBounds)
        }
        val innermost = assertNotNull(layout.boxesByKey["c_4"])
        assertEquals(2, innermost.chips.size)
        for (chipKey in innermost.chips) {
            val chip = assertNotNull(layout.chipsByKey[chipKey])
            assertTrue(innermost.bounds.contains(chip.bounds.topLeft))
        }
    }

    // ------------------------------------------------------------------ masonry / 流式换行

    @Test
    fun rootMasonryWrapsWhenCanvasWidthExceeded() {
        val nodes = ArrayList<GraphNode>()
        for (index in 0 until 4) nodes.add(category("c_$index", "分类$index", sortOrder = index))
        val options = LayoutOptions(maxCanvasWidth = 400f)
        val layout = layoutKnowledgeGraph(graphOf(*nodes.toTypedArray()), emptySet(), null, options)

        assertEquals(4, layout.roots.size)
        val lefts = layout.roots.map { it.bounds.left }.distinct()
        assertEquals(1, lefts.size)
        assertEquals(options.padding, lefts.single(), EPS)
        // 一个 240 宽的框都放不下第二个 → 每个根框各占一行。
        for (index in 1 until layout.roots.size) {
            assertTrue(
                layout.roots[index].bounds.top > layout.roots[index - 1].bounds.top,
                "换行后 top 应递增",
            )
        }
        // bounds = 内容并集外扩 padding。
        assertEquals(0f, layout.bounds.left, EPS)
        assertEquals(options.padding + options.minRootWidth + options.padding, layout.bounds.right, EPS)
    }

    @Test
    fun chipsWrapWithinContentWidthAndColumnLimit() {
        val nodes = ArrayList<GraphNode>()
        nodes.add(category("c_a", "分类甲"))
        for (index in 0 until 18) {
            nodes.add(concept("k_$index", "概念名称第${index}号", parentKey = "c_a"))
        }
        val options = LayoutOptions(maxChipColumns = 4)
        val layout = layoutKnowledgeGraph(graphOf(*nodes.toTypedArray()), emptySet(), null, options)

        val box = assertNotNull(layout.boxesByKey["c_a"])
        assertEquals(18, box.chips.size)
        val chips = box.chips.map { layout.chipsByKey.getValue(it) }
        val contentLeft = box.bounds.left + options.boxPadding
        val contentRight = box.bounds.right - options.boxPadding
        for (chip in chips) {
            assertTrue(chip.bounds.left >= contentLeft - EPS, "芯片应在内容区内：$chip")
            assertTrue(chip.bounds.right <= contentRight + EPS, "芯片应在内容区内：$chip")
        }
        val rows = chips.groupBy { it.bounds.top }
        assertEquals(5, rows.size)
        for (row in rows.values) {
            assertTrue(row.size <= 4, "每行最多 4 列：$row")
            assertNoOverlap(row.map { it.bounds })
        }
        assertNoOverlap(chips.map { it.bounds })
    }

    // ------------------------------------------------------------------ 规模

    @Test
    fun largeGraphLaysOutEveryConcept() {
        // 40 个分类 × 50 个概念 = 2000 概念；结构正确即说明没有 O(N²) 的嵌套遍历（测试不做计时断言）。
        val nodes = ArrayList<GraphNode>()
        for (categoryIndex in 0 until 40) {
            val categoryKey = "c_$categoryIndex"
            nodes.add(category(categoryKey, "分类$categoryIndex", sortOrder = categoryIndex))
            for (conceptIndex in 0 until 50) {
                nodes.add(
                    concept(
                        key = "k_${categoryIndex}_$conceptIndex",
                        name = "概念$categoryIndex-$conceptIndex",
                        parentKey = categoryKey,
                        noteCount = conceptIndex,
                    ),
                )
            }
        }
        val layout = layoutKnowledgeGraph(graphOf(*nodes.toTypedArray()), emptySet(), null)
        assertEquals(2000, layout.chipsByKey.size)
        assertEquals(40, layout.roots.size)
        for (chip in layout.chipsByKey.values) {
            assertTrue(layout.bounds.contains(chip.bounds.topLeft), "bounds 应包含芯片：$chip")
            assertTrue(layout.bounds.contains(GraphPoint(chip.bounds.right, chip.bounds.bottom)))
        }
    }

    // ------------------------------------------------------------------ 中心点

    @Test
    fun centerOfResolvesChipsAndBoxes() {
        val layout = layoutKnowledgeGraph(
            graphOf(category("c_a", "分类甲"), concept("k_1", "概念一", parentKey = "c_a")),
            emptySet(),
            null,
        )
        val chip = assertNotNull(layout.chipsByKey["k_1"])
        assertEquals(chip.bounds.center, layout.centerOf("k_1"))
        val box = assertNotNull(layout.boxesByKey["c_a"])
        assertEquals(box.bounds.center, layout.centerOf("c_a"))
        assertNull(layout.centerOf("missing"))
    }

    private companion object {
        private const val EPS = 0.01f
    }
}
