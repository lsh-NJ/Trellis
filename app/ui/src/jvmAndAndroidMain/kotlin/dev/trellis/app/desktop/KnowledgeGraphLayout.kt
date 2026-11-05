package dev.trellis.app.desktop

import kotlin.math.ceil
import kotlin.math.max

/**
 * 知识网络 v1.2 的森林式树布局（纯函数、确定性、无 Compose / 无 IO）。
 *
 * 设计 v1.2：「像建一颗虚实结合的树，由实（已存在的概念点）和虚（把节点链接在一起的分类），
 * 然后构成一个大类一颗树」。所以画布上不再有嵌套卡片/芯片，只有节点 + 父 → 子连线：
 * - 每棵树的根是一个根分类（`parentKey == null`）；未分类概念合成一棵虚拟根树
 *   [GRAPH_UNCLASSIFIED_KEY]，永远排在最后；
 * - 分类节点画成空心（虚），概念节点画成实心（实）；只画父 → 子边，其他关系不进画布；
 * - 折叠只剪枝：`folded = true` 的分类的子树不进 [GraphLayout.nodes] / [GraphLayout.links]；
 * - 节点宽度只由名字估宽决定（[estimateLabelWidth]），高度按节点类型（概念/分类）。
 *
 * 算法是经典 tidy tree 的两遍式实现（[ForestLayout]）：
 * 1. 测量：自底向上算子树的宽（`max(自身宽, 子宽之和 + 间距)`）与树高；
 * 2. 摆放：父节点水平居中于自己的子树/子节点跨度，同一棵树按层 `y = 行顶 + depth * rowStep`；
 * 3. 森林：各棵树按根顺序从左往右摆，一行内容宽超过 [LayoutOptions.maxCanvasWidth] 就换行。
 *
 * 全部用 [LinkedHashMap]/[List] 保持顺序、不迭代 HashMap；每个节点只测一次、摆一次，O(N)。
 */

/**
 * 森林式树布局的参数。
 *
 * @property padding 内容到画布边缘的留白（[GraphLayout.bounds] 会再按它 inflate）。
 * @property nodeGapX 同一父节点下相邻子树的水平间距。
 * @property rowGapY 层间距（父行底到子行顶；行高按 `max(nodeHeight, categoryHeight)` 算）。
 * @property treeGapX 森林里相邻两棵树的水平间距；换行后的行间距也以它为基准。
 * @property minNodeWidth 节点最小宽度（短名字不会缩得太窄）。
 * @property maxNodeWidth 节点最大宽度（超长名字截断估宽）。
 * @property nodeHeight 概念节点高度。
 * @property categoryHeight 分类节点高度。
 * @property conceptFontPx 概念名估算字号（px）。
 * @property categoryFontPx 分类名估算字号（px）。
 * @property maxCanvasWidth 画布最大宽度；一行树宽超过它就换行。
 */
internal data class LayoutOptions(
    val padding: Float = 32f,
    val nodeGapX: Float = 18f,
    val rowGapY: Float = 44f,
    val treeGapX: Float = 72f,
    val minNodeWidth: Float = 64f,
    val maxNodeWidth: Float = 260f,
    val nodeHeight: Float = 30f,
    val categoryHeight: Float = 28f,
    val conceptFontPx: Float = 12f,
    val categoryFontPx: Float = 12.5f,
    val maxCanvasWidth: Float = 3000f,
)

/**
 * 估算一行标签的宽度（px）：CJK / 全角按 1.0em、ASCII 按 0.55em，向上取整后再留 6% 余量。
 * 空串（或字号非正）返回 0。只用于算节点宽度；绘制时 Canvas 会再用实测字体截断（设计 §4.5）。
 */
internal fun estimateLabelWidth(text: String, fontPx: Float): Float {
    if (text.isEmpty() || fontPx <= 0f) return 0f
    var width = 0.0
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        width += if (isWideCodePoint(codePoint)) fontPx.toDouble() else fontPx.toDouble() * ASCII_EM
        index += Character.charCount(codePoint)
    }
    return (ceil(width) * LABEL_WIDTH_MARGIN).toFloat()
}

/**
 * 计算知识网络的森林式树布局。
 *
 * @param graph 结构化知识网络（分类 + 概念）。根分类按 [KnowledgeGraph.childrenOf] 的顺序（Build 已按
 *   `sortOrder → name → key` 排好；缺失时布局层兜底从 `nodes` 里筛根分类并按同一顺序补在后面）；未分类
 *   概念合成虚拟根 [GRAPH_UNCLASSIFIED_KEY] 排最后。
 * @param collapsed 折叠的分类 key 集合；只有存在可见子节点的分类会生效（`folded = true`），
 *   它的子树不进 `nodes` / `links`。不在图里或不是分类的 key 忽略。
 * @param warm 上次布局缓存；只有 [LayoutCache.fingerprint] 与 [layoutFingerprint] 相同才采信
 *   [LayoutCache.rootOrder]（重排根树顺序，新根接在后面，未分类永远最后），坐标全部重新计算。
 * @param options 布局参数。
 * @return [GraphLayout]；没有任何可见节点时返回 [GraphLayout.Empty]（bounds = [GraphRect.Zero]）。
 */
internal fun layoutKnowledgeGraph(
    graph: KnowledgeGraph,
    collapsed: Set<String> = emptySet(),
    warm: LayoutCache? = null,
    options: LayoutOptions = LayoutOptions(),
): GraphLayout = ForestLayout(graph, collapsed, warm, options).layout()

/**
 * 按「缩放 + 概念数 + 视口面积」选 LOD 档位（设计 §4.3）：
 * - `conceptCount > 5000` → [LodLevel.TooLarge]（先判最大数，避免大图还走精细路径）；
 * - `conceptCount > 2000` 或 `zoom < 0.3` → [LodLevel.Aggregate]；
 * - `perNodeArea = viewportArea * zoom² / max(1, conceptCount) < 180` 或 `zoom < 0.55` → [LodLevel.Compact]；
 * - 否则 [LodLevel.Full]。
 *
 * 判定顺序：TooLarge > Aggregate > Compact > Full。[zoom] 非有限（NaN/Infinity）按 0 处理。
 */
internal fun lodFor(zoom: Float, conceptCount: Int, viewportArea: Float): LodLevel {
    if (conceptCount > LOD_TOO_LARGE_CONCEPTS) return LodLevel.TooLarge
    val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom else 0f
    if (conceptCount > LOD_AGGREGATE_CONCEPTS || safeZoom < LOD_AGGREGATE_ZOOM) return LodLevel.Aggregate
    val count = max(1, conceptCount)
    val safeArea = if (viewportArea.isFinite() && viewportArea > 0f) viewportArea.toDouble() else 0.0
    val perNodeArea = safeArea * safeZoom.toDouble() * safeZoom.toDouble() / count.toDouble()
    if (perNodeArea < LOD_FULL_PER_NODE_AREA || safeZoom < LOD_COMPACT_ZOOM) return LodLevel.Compact
    return LodLevel.Full
}

// ---------------------------------------------------------------------------------------------
// 内部实现：测量 → 摆树 → 摆森林。全部 private，不污染同包其它文件。
// ---------------------------------------------------------------------------------------------

/**
 * 一次布局的构建器：先量出每棵树的骨架（[MeasuredTree]），再写世界坐标。
 *
 * 两遍式做法保证：父节点居中于子节点跨度、同层节点不重叠、每个节点只算一次（O(N)）。
 */
private class ForestLayout(
    private val graph: KnowledgeGraph,
    private val collapsed: Set<String>,
    private val warm: LayoutCache?,
    private val options: LayoutOptions,
) {
    private val padding: Float = options.padding.coerceAtLeast(0f)
    private val nodeGapX: Float = options.nodeGapX.coerceAtLeast(0f)
    private val treeGapX: Float = options.treeGapX.coerceAtLeast(0f)
    private val rowGapY: Float = options.rowGapY.coerceAtLeast(0f)
    private val nodeHeight: Float = options.nodeHeight.coerceAtLeast(1f)
    private val categoryHeight: Float = options.categoryHeight.coerceAtLeast(1f)
    private val minNodeWidth: Float = options.minNodeWidth.coerceAtLeast(1f)
    private val maxNodeWidth: Float = options.maxNodeWidth.coerceAtLeast(minNodeWidth)

    /** 一层的纵向步长：`max(概念高, 分类高) + 层间距`（设计：`y = padding + depth * rowStep`）。 */
    private val rowStep: Float = max(nodeHeight, categoryHeight) + rowGapY

    /** 一行可用的内容宽（扣掉左右 edge padding）。 */
    private val maxContentWidth: Float = (options.maxCanvasWidth - padding * 2f).coerceAtLeast(0f)

    /** 防御 childrenOf 成环：当前递归链上的分类 key（正常 Build 不会给环）。 */
    private val visiting: MutableSet<String> = HashSet()

    fun layout(): GraphLayout {
        val roots = measureForest()
        if (roots.isEmpty()) return GraphLayout.Empty
        val orderedRoots = applyWarmRootOrder(roots)

        val nodes = LinkedHashMap<String, GraphNodeBox>()
        val links = ArrayList<GraphLink>()
        val foldedKeys = LinkedHashSet<String>()
        var penX = padding
        var rowTop = padding
        var rowBottom = padding
        for (root in orderedRoots) {
            // 一行总宽（含树间距）超过 maxCanvasWidth 就换行；一行至少放一棵树。
            if (penX > padding && (penX - padding) + root.subtreeWidth > maxContentWidth) {
                penX = padding
                rowTop = rowBottom + rowGapY
                rowBottom = rowTop
            }
            placeTree(root, subtreeLeft = penX, rowTop = rowTop, depth = 0, nodes = nodes, links = links, foldedKeys = foldedKeys)
            penX += root.subtreeWidth + treeGapX
            rowBottom = max(rowBottom, rowTop + root.treeHeight)
        }

        var bounds = nodes.values.first().bounds
        for (box in nodes.values) bounds = bounds.union(box.bounds)
        return GraphLayout(
            bounds = bounds.inflate(padding),
            roots = orderedRoots.map { it.key },
            nodes = nodes,
            links = links,
            collapsed = foldedKeys,
        )
    }

    // ---- 测量 ----

    /** 森林的骨架（根树列表，未分类虚拟根最后）。 */
    private fun measureForest(): List<MeasuredTree> {
        val rootKeys = ArrayList<String>()
        val listedRoots = HashSet<String>()
        for (node in graph.childrenOf(null)) {
            if (node.kind == GraphNodeKind.CATEGORY && listedRoots.add(node.key)) rootKeys.add(node.key)
        }
        // 兜底：childrenOf[null] 没列根分类时（手工构造的图 / Build 契约变化），从 nodes 里补齐并按确定性顺序排。
        for (node in graph.nodes.values.filter { it.kind == GraphNodeKind.CATEGORY && it.parentKey == null && it.key !in listedRoots }
            .sortedWith(CATEGORY_ORDER)) {
            rootKeys.add(node.key)
        }

        val looseConceptKeys = LinkedHashSet<String>()
        for (node in graph.childrenOf(null)) {
            if (node.kind == GraphNodeKind.CONCEPT) looseConceptKeys.add(node.key)
        }
        // 兜底：childrenOf[null] 没列散概念时，从 nodes 里筛 parentKey == null 的概念并确定性排序。
        for (node in graph.nodes.values.filter { it.kind == GraphNodeKind.CONCEPT && it.parentKey == null && it.key !in looseConceptKeys }
            .sortedWith(CONCEPT_ORDER)) {
            looseConceptKeys.add(node.key)
        }

        val trees = ArrayList<MeasuredTree>(rootKeys.size + 1)
        for (key in rootKeys) {
            val node = graph.nodes[key] ?: continue
            trees.add(measureCategory(node))
        }
        val looseConcepts = looseConceptKeys.mapNotNull(graph.nodes::get).filter { it.kind == GraphNodeKind.CONCEPT }
        if (looseConcepts.isNotEmpty()) {
            val folded = GRAPH_UNCLASSIFIED_KEY in collapsed
            val children = if (folded) emptyList() else looseConcepts.map(::measureConcept)
            trees.add(
                buildMeasured(
                    key = GRAPH_UNCLASSIFIED_KEY,
                    kind = GraphNodeKind.CATEGORY,
                    name = UNCLASSIFIED_LABEL,
                    folded = folded,
                    children = children,
                ),
            )
        }
        return trees
    }

    private fun measureCategory(node: GraphNode): MeasuredTree {
        visiting.add(node.key)
        try {
            val childNodes = graph.childrenOf(node.key)
            val folded = node.key in collapsed && childNodes.isNotEmpty()
            val children = ArrayList<MeasuredTree>(childNodes.size)
            if (!folded) {
                for (child in childNodes) {
                    when (child.kind) {
                        GraphNodeKind.CATEGORY -> {
                            // 成环防御：递归链上已有的分类不再展开，避免无限递归（Build 正常不会给环）。
                            if (child.key !in visiting) children.add(measureCategory(child))
                        }
                        GraphNodeKind.CONCEPT -> children.add(measureConcept(child))
                    }
                }
            }
            return buildMeasured(node.key, GraphNodeKind.CATEGORY, node.name, folded, children)
        } finally {
            visiting.remove(node.key)
        }
    }

    private fun measureConcept(node: GraphNode): MeasuredTree =
        buildMeasured(node.key, GraphNodeKind.CONCEPT, node.name, folded = false, children = emptyList())

    /** 自底向上算：节点宽高、子树宽（`max(自身, 子宽和 + 间距)`）、树高。 */
    private fun buildMeasured(
        key: String,
        kind: GraphNodeKind,
        name: String,
        folded: Boolean,
        children: List<MeasuredTree>,
    ): MeasuredTree {
        val fontPx = if (kind == GraphNodeKind.CONCEPT) options.conceptFontPx else options.categoryFontPx
        val height = if (kind == GraphNodeKind.CONCEPT) nodeHeight else categoryHeight
        val width = (estimateLabelWidth(name, fontPx) + NODE_HORIZONTAL_PADDING).coerceIn(minNodeWidth, maxNodeWidth)
        var childrenWidth = 0f
        children.forEachIndexed { index, child ->
            if (index > 0) childrenWidth += nodeGapX
            childrenWidth += child.subtreeWidth
        }
        var below = 0f
        for (child in children) below = max(below, rowStep + child.treeHeight)
        return MeasuredTree(
            key = key,
            kind = kind,
            folded = folded,
            width = width,
            height = height,
            children = children,
            subtreeWidth = max(width, childrenWidth),
            treeHeight = max(height, below),
        )
    }

    // ---- warm 根序 ----

    /** 指纹命中时按缓存里的根顺序重排（新根接在后面，未分类永远最后）；坐标照常全部重算。 */
    private fun applyWarmRootOrder(roots: List<MeasuredTree>): List<MeasuredTree> {
        val cached = warm?.takeIf { it.fingerprint == layoutFingerprint(graph) } ?: return roots
        if (cached.rootOrder.isEmpty()) return roots
        val byKey = roots.associateBy { it.key }
        val ordered = ArrayList<MeasuredTree>(roots.size)
        val used = HashSet<String>(roots.size)
        for (key in cached.rootOrder) {
            if (key == GRAPH_UNCLASSIFIED_KEY) continue
            val root = byKey[key] ?: continue
            if (used.add(key)) ordered.add(root)
        }
        for (root in roots) {
            if (root.key != GRAPH_UNCLASSIFIED_KEY && used.add(root.key)) ordered.add(root)
        }
        byKey[GRAPH_UNCLASSIFIED_KEY]?.let(ordered::add)
        return ordered
    }

    // ---- 摆放 ----

    /**
     * 递归摆一棵树：节点中心 = 子树中心（于是父节点居中于子节点跨度），子节点按顺序从左往右排。
     */
    private fun placeTree(
        tree: MeasuredTree,
        subtreeLeft: Float,
        rowTop: Float,
        depth: Int,
        nodes: MutableMap<String, GraphNodeBox>,
        links: MutableList<GraphLink>,
        foldedKeys: MutableSet<String>,
    ) {
        val centerX = subtreeLeft + tree.subtreeWidth / 2f
        val nodeLeft = centerX - tree.width / 2f
        val nodeTop = rowTop + depth * rowStep
        nodes[tree.key] = GraphNodeBox(
            key = tree.key,
            kind = tree.kind,
            depth = depth,
            bounds = GraphRect(nodeLeft, nodeTop, nodeLeft + tree.width, nodeTop + tree.height),
            folded = tree.folded,
        )
        if (tree.folded) foldedKeys.add(tree.key)
        if (tree.children.isEmpty()) return

        var childrenWidth = 0f
        tree.children.forEachIndexed { index, child ->
            if (index > 0) childrenWidth += nodeGapX
            childrenWidth += child.subtreeWidth
        }
        var childLeft = centerX - childrenWidth / 2f
        for (child in tree.children) {
            links.add(GraphLink(fromKey = tree.key, toKey = child.key))
            placeTree(child, subtreeLeft = childLeft, rowTop = rowTop, depth = depth + 1, nodes = nodes, links = links, foldedKeys = foldedKeys)
            childLeft += child.subtreeWidth + nodeGapX
        }
    }
}

/** 测量结果：一棵可见子树的骨架。宽高在构建时算好，摆放时只读。 */
private data class MeasuredTree(
    val key: String,
    val kind: GraphNodeKind,
    val folded: Boolean,
    val width: Float,
    val height: Float,
    val children: List<MeasuredTree>,
    val subtreeWidth: Float,
    val treeHeight: Float,
)

/** 分类兜底顺序：与 Build 契约一致（sortOrder → name → key）。 */
private val CATEGORY_ORDER: Comparator<GraphNode> =
    compareBy<GraphNode>({ it.sortOrder }, { it.name }, { it.key })

/** 散概念兜底顺序：与 Build 契约一致（noteCount 降序 → name → key）。 */
private val CONCEPT_ORDER: Comparator<GraphNode> =
    compareByDescending<GraphNode> { it.noteCount }.thenBy { it.name }.thenBy { it.key }

/** 节点左右内边距合计：宽度 = 估宽 + 它（节点画成圆角胶囊，名字两侧各留 10dp）。 */
private const val NODE_HORIZONTAL_PADDING = 20f

/** ASCII 字符的宽度系数（em）。 */
private const val ASCII_EM = 0.55

/** 估宽余量：向上取整后再 ×1.06（设计 §4.5 的 ±15% 误差内取宽）。 */
private const val LABEL_WIDTH_MARGIN = 1.06

/** 未分类虚拟根树的标题（真名由 Canvas 兜底；布局只拿它估宽）。 */
private const val UNCLASSIFIED_LABEL = "未分类"

private const val LOD_TOO_LARGE_CONCEPTS = 5000
private const val LOD_AGGREGATE_CONCEPTS = 2000
private const val LOD_AGGREGATE_ZOOM = 0.3f
private const val LOD_COMPACT_ZOOM = 0.55f
private const val LOD_FULL_PER_NODE_AREA = 180.0

/**
 * 判断一个 code point 是否按「全角 / 1em」估算（CJK、假名、Hangul、全角标点、emoji 等）。
 * 覆盖范围与设计 §4.5 的估宽口径一致；宁可估宽不可估窄。
 */
private fun isWideCodePoint(codePoint: Int): Boolean = when (codePoint) {
    // Hangul Jamo 初声 / CJK 部首·标点·假名·注音 / 假名·CJK 兼容·带圈字符
    in 0x1100..0x115F, in 0x2E80..0x303E, in 0x3041..0x33FF,
    // CJK 扩展 A / CJK 统一表意文字 / 彝文 / Hangul 音节 / CJK 兼容表意文字 / CJK 兼容形式
    in 0x3400..0x4DBF, in 0x4E00..0x9FFF, in 0xA000..0xA4CF, in 0xAC00..0xD7A3,
    in 0xF900..0xFAFF, in 0xFE30..0xFE4F,
    // 全角 ASCII·标点 / 全角符号
    in 0xFF00..0xFF60, in 0xFFE0..0xFFE6,
    // emoji 与 CJK 扩展 B+（按 1em 估，宁可宽不可窄）
    in 0x1F300..0x1F64F, in 0x1F900..0x1F9FF, in 0x20000..0x3FFFD -> true
    else -> false
}
