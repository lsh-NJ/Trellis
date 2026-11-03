package dev.trellis.app.desktop

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 知识网络 G0 的**确定性分区布局**（设计 §4.2 / §4.6 / §8.3）。
 *
 * 输入 [KnowledgeGraph]（分类树 + 概念节点）、折叠集合与可选的 warm 缓存，输出 [GraphLayout]：
 * 分类框（[GraphBox]，可嵌套）+ 概念芯片（[GraphChipItem]）的世界坐标（单位 dp）。
 * 本文件**不碰 Compose、不读文件、不看时钟**：同一输入两次调用逐位相等，可在 JVM 单测里直接跑。
 *
 * 排布规则（与设计 §4.2 一致，自底向上单遍测量）：
 * 1. 分类框 = 标题行（[LayoutOptions.headerHeight]）+ 直接概念芯片的流式网格
 *    + 子分类框的嵌套堆叠；框内四周留 [LayoutOptions.boxPadding]；
 * 2. 根框宽度 clamp 到 [LayoutOptions.minRootWidth]..[LayoutOptions.maxRootWidth]，
 *    非根框最小 200dp；父框内容宽放不下子框时把子框按更窄的可用宽重新换行（不越界、不重叠）；
 * 3. 根层 masonry 按 reading order 从左到右排，超过 [LayoutOptions.maxCanvasWidth] 换行；
 *    未分类虚拟框 [GRAPH_UNCLASSIFIED_KEY] 永远排最后；
 * 4. 折叠的分类只留标题行，内部芯片/子框不进 [GraphLayout.chipsByKey]/[GraphLayout.boxesByKey]；
 *    折叠是**视图状态**，数据本身不变，删除折叠后重算即恢复；
 * 5. warm 缓存命中（[LayoutCache.fingerprint] == [layoutFingerprint]）时只复用**根块顺序**
 *    （[LayoutCache.rootOrder]，[LayoutCache.anchors] 仅做同序 tie-break），坐标全部重新计算——
 *    复用旧坐标会在归属/折叠变化后叠框。
 *
 * 确定性：所有顺序都来自显式排序（分类 `(sortOrder, name, key)`、概念 `(noteCount 降序, name, key)`），
 * 不迭代 HashMap 的键顺序；测量是 O(N) 递归（深层框被父框压窄时会按最终宽度重排一次子树，
 * 深度 ≤ 5 时总量仍是常数倍），2000 概念在毫秒级。
 */

/**
 * 布局参数（设计 §4.6 的默认值）。
 *
 * @property padding 画布外扩：内容并集四周留白（边缘留白 32dp）。
 * @property categoryGap 分类框之间的间距（子分类框纵向堆叠时使用；根块行间距见 [rootGap]）。
 * @property chipGap 概念芯片间距（同行相邻、行与行之间都用它）。
 * @property maxCanvasWidth 根块 masonry 的最大行宽，超过换行。
 * @property headerHeight 分类标题行高（折叠框只剩这一行）。
 * @property chipHeight 概念芯片高度。
 * @property boxPadding 框内四周留白。
 * @property columnGap 根块 masonry 的列间距（同一行相邻根框的水平间距，设计 §4.6 列间距 32dp）。
 * @property minRootWidth 根框最小宽度（clamp 下限；非根框不受它约束）。
 * @property maxRootWidth 任意分类框宽度上限。
 * @property chipFontPx 芯片文字估算字号（px）。
 * @property headerFontPx 分类标题文字估算字号（px）。
 * @property maxChipColumns 每行芯片列数上限（窄框 4 / 宽框 8）。
 * @property rootGap 根块 masonry 的行间距（行与行之间的垂直间距，设计 §4.6 根框间距 24dp）。
 */
internal data class LayoutOptions(
    val padding: Float = 32f,
    val categoryGap: Float = 24f,
    val chipGap: Float = 8f,
    val maxCanvasWidth: Float = 2600f,
    val headerHeight: Float = 30f,
    val chipHeight: Float = 30f,
    val boxPadding: Float = 10f,
    val columnGap: Float = 32f,
    val minRootWidth: Float = 240f,
    val maxRootWidth: Float = 720f,
    val chipFontPx: Float = 12f,
    val headerFontPx: Float = 13f,
    val maxChipColumns: Int = 8,
    val rootGap: Float = 24f,
)

/**
 * 估算一行标签的宽度（dp）：CJK / 全角按 1.0em、ASCII 按 0.55em，向上取整后再留 6% 余量。
 * 空串（或字号非正）返回 0。只用于算盒子宽度；绘制时 Canvas 会再用实测字体截断（设计 §4.5）。
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
 * [estimateLabelWidth] 的 `sp × density` 调用形式：Canvas 里字号常以 sp 记，这里折算成 px 再估宽。
 */
internal fun estimateLabelWidth(text: String, fontSizeSp: Float, density: Float): Float =
    estimateLabelWidth(text, fontSizeSp * density)

/**
 * 计算知识网络的分区布局。
 *
 * @param graph 结构化知识网络（分类 + 概念；不看 [KnowledgeGraph.childrenOf] 的顺序，自己排）。
 * @param collapsed 折叠的分类 key 集合（含「分类默认折叠」由 UI 算好传进来；未分类框用
 *   [GRAPH_UNCLASSIFIED_KEY] 表示）。不在图里的 key 会被忽略。
 * @param warm 上次布局缓存；只有 [LayoutCache.fingerprint] 与 [layoutFingerprint] 相同才采信根顺序，
 *   否则完全冷算（坐标永远重算，不复用旧坐标）。
 * @param options 布局参数。
 * @return [GraphLayout]；空图返回 [GraphLayout.Empty]（bounds = [GraphRect.Zero]）。
 */
internal fun layoutKnowledgeGraph(
    graph: KnowledgeGraph,
    collapsed: Set<String>,
    warm: LayoutCache?,
    options: LayoutOptions = LayoutOptions(),
): GraphLayout {
    if (graph.nodes.isEmpty()) return GraphLayout.Empty
    // warm 只认指纹：图变了（归属 / 排序 / 折叠结构变了）就整体冷算，避免旧顺序看起来「没刷新」。
    val hit = warm?.takeIf { it.fingerprint == layoutFingerprint(graph) }
    return PartitionLayout(graph, collapsed, hit, options).layout()
}

/**
 * 命中测试：芯片优先，其次分类标题行（由深到浅），最后分类框（由深到浅）；空白返回 null。
 *
 * 芯片区域也在框内部，所以顺序必须是芯片 → 标题 → 框，否则点芯片会先报成框。
 */
internal fun GraphLayout.hitTest(point: GraphPoint): GraphHit? {
    for (key in chipsByKey.keys.sorted()) {
        val chip = chipsByKey.getValue(key)
        if (chip.bounds.contains(point)) return GraphHit.Chip(key)
    }
    val deepFirst = boxesByKey.values.sortedWith(
        compareByDescending<GraphBox> { it.depth }.thenBy { it.categoryKey },
    )
    for (box in deepFirst) {
        if (box.headerBounds.contains(point)) return GraphHit.CategoryHeader(box.categoryKey)
    }
    for (box in deepFirst) {
        if (box.bounds.contains(point)) return GraphHit.CategoryBox(box.categoryKey)
    }
    return null
}

/** 取一个 key 的中心点：芯片优先，其次分类框；不存在返回 null（用于选中项的相机锚点）。 */
internal fun GraphLayout.centerOf(key: String): GraphPoint? =
    chipsByKey[key]?.bounds?.center ?: boxesByKey[key]?.bounds?.center

/**
 * zoom-to-fit：让 [bounds] 完整落进 [viewport]（四周留 [padding]），并把中心对齐到视口中心。
 *
 * `scale = min((vw - 2p) / bw, (vh - 2p) / bh, maxZoom)`，下限 [minZoom]；
 * bounds / 视口为空（宽或高 ≤ 0）返回 [Camera.Default]（换算不了，交给调用方保持原状）。
 */
internal fun zoomToFit(
    bounds: GraphRect,
    viewport: GraphSize,
    padding: Float = 32f,
    maxZoom: Float = 1.5f,
    minZoom: Float = 0.25f,
): Camera {
    if (bounds.isEmpty || viewport.isEmpty) return Camera.Default
    val availableWidth = viewport.width - padding * 2f
    val availableHeight = viewport.height - padding * 2f
    val scale = min(
        min(availableWidth / bounds.width, availableHeight / bounds.height),
        maxZoom,
    ).coerceAtLeast(minZoom)
    val panX = viewport.width / 2f - bounds.centerX * scale
    val panY = viewport.height / 2f - bounds.centerY * scale
    return Camera(zoom = scale, panX = panX, panY = panY)
}

/**
 * [zoomToFit] 的 `GraphLayout` 扩展形式：把画布上的一块世界矩形拟合成相机（Canvas/Panels 用）。
 */
internal fun GraphLayout.zoomToFit(
    bounds: GraphRect,
    canvas: GraphSize,
    padding: Float = 32f,
    maxZoom: Float = 1.5f,
): Camera = zoomToFit(bounds = bounds, viewport = canvas, padding = padding, maxZoom = maxZoom)

/**
 * 聚焦某个分类：把分类框拟合成相机；[categoryKey] 为 null（或 key 不存在）时拟合全图（设计 §4.4）。
 */
internal fun fitCategory(
    layout: GraphLayout,
    categoryKey: String?,
    viewport: GraphSize,
): Camera {
    val target = categoryKey?.let { layout.boxesByKey[it]?.bounds } ?: layout.bounds
    return zoomToFit(target, viewport)
}

/**
 * [fitCategory] 的 `GraphLayout` 扩展形式：key 不存在时返回 null（让调用方决定回退到全图）。
 */
internal fun GraphLayout.fitCategory(
    categoryKey: String,
    canvas: GraphSize,
    padding: Float = 32f,
    maxZoom: Float = 2f,
): Camera? {
    val box = boxesByKey[categoryKey] ?: return null
    return zoomToFit(box.bounds, canvas, padding, maxZoom)
}

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
    val perNodeArea = viewportArea.toDouble() * safeZoom.toDouble() * safeZoom.toDouble() / count.toDouble()
    if (perNodeArea < LOD_FULL_PER_NODE_AREA || safeZoom < LOD_COMPACT_ZOOM) return LodLevel.Compact
    return LodLevel.Full
}

// ---------------------------------------------------------------------------------------------
// 内部实现：测量 → 排布 → 写坐标。全部 private，不污染同包其它文件。
// ---------------------------------------------------------------------------------------------

/** 芯片左右内边距合计：芯片宽度 = 估宽 + 它（设计 §4.2）。 */
private const val CHIP_HORIZONTAL_PADDING = 20f
/** ASCII 字符的宽度系数（em）。 */
private const val ASCII_EM = 0.55
/** 估宽余量：取整后再 ×1.06（设计 §4.5 ±15% 的一半，宁可留宽）。 */
private const val LABEL_WIDTH_MARGIN = 1.06
/** 非根分类框的最小宽度（设计 §4.2 建议 max(200, 内容宽)）。 */
private const val MIN_NON_ROOT_WIDTH = 200f
/** 折叠框内容区最小宽度（设计 §4.2：max(160, headerW)）。 */
private const val MIN_COLLAPSED_CONTENT_WIDTH = 160f
/** 内容区宽度兜底，避免极端参数（父框被压得极窄）算出负宽。 */
private const val MIN_CONTENT_WIDTH = 40f
/** 未分类虚拟框标题。 */
private const val UNCLASSIFIED_LABEL = "未分类"
private const val LOD_TOO_LARGE_CONCEPTS = 5000
private const val LOD_AGGREGATE_CONCEPTS = 2000
private const val LOD_AGGREGATE_ZOOM = 0.3f
private const val LOD_COMPACT_ZOOM = 0.55f
private const val LOD_FULL_PER_NODE_AREA = 180.0

/** 分类排序：sortOrder 升序 → name → key（确定性，不迭代 HashMap）。 */
private val CATEGORY_ORDER: Comparator<GraphNode> =
    compareBy<GraphNode>({ it.sortOrder }, { it.name }, { it.key })

/** 概念排序：noteCount 降序 → name → key（笔记越多的概念越靠前）。 */
private val CONCEPT_ORDER: Comparator<GraphNode> =
    compareByDescending<GraphNode> { it.noteCount }.thenBy { it.name }.thenBy { it.key }

/** 量完的一颗芯片；[x]/[y] 是相对所属框**内容区原点**（框内 padding 之后）的偏移。 */
private class MeasuredChip(
    val key: String,
    val width: Float,
    val height: Float,
    val x: Float,
    val y: Float,
)

/** 量完的子框 + 相对父框内容区原点的偏移。 */
private class MeasuredChild(
    val box: MeasuredBox,
    val x: Float,
    val y: Float,
)

/** 量完的分类框（相对坐标系，尚未落到世界坐标）。 */
private class MeasuredBox(
    val categoryKey: String,
    val depth: Int,
    val collapsed: Boolean,
    val width: Float,
    val height: Float,
    val headerHeight: Float,
    val contentWidth: Float,
    val chips: List<MeasuredChip>,
    val children: List<MeasuredChild>,
)

/** 流式排布前的芯片草稿。 */
private class ChipDraft(
    val key: String,
    val width: Float,
)

/** 芯片流式网格（行优先，行高 = chipHeight）。 */
private class ChipFlow(
    val rows: List<List<ChipDraft>>,
    val width: Float,
    val height: Float,
)

/** 子框嵌套排布结果（[children] 里的 y 是相对子框块的偏移，[height] 是块高）。 */
private class PackedChildren(
    val children: List<MeasuredChild>,
    val width: Float,
    val height: Float,
)

/**
 * 一次布局计算的上下文：建树 → 测量 → 排布 → 写世界坐标。
 * 所有排序都在 [init] 里定死，[layout] 只做确定性遍历。
 */
private class PartitionLayout(
    private val graph: KnowledgeGraph,
    private val collapsed: Set<String>,
    private val warm: LayoutCache?,
    private val options: LayoutOptions,
) {
    /** 分类 → 直接子分类（已排序；键为父分类 key，null = 根）。 */
    private val categoryChildren: Map<String?, List<GraphNode>>

    /** 概念 → 所属分类（已排序；键为分类 key，[GRAPH_UNCLASSIFIED_KEY] = 未分类）。 */
    private val conceptChildren: Map<String, List<GraphNode>>

    /** 实际用到的折叠 key（图里存在的分类 + 有内容的未分类框）。 */
    private val usedCollapsed: Set<String>

    init {
        val nodesByKey = graph.nodes
        val categoryNodes = nodesByKey.values
            .filter { it.kind == GraphNodeKind.CATEGORY }
            .sortedBy { it.key }

        // 有效父分类：父节点存在、是分类、且不是自己；否则回退成根（防御数据异常）。
        val effectiveParent = HashMap<String, String?>(categoryNodes.size)
        for (node in categoryNodes) {
            effectiveParent[node.key] = node.parentKey?.takeIf { parentKey ->
                parentKey != node.key && nodesByKey[parentKey]?.kind == GraphNodeKind.CATEGORY
            }
        }
        // 断环：父链上再次遇到同一节点就把它提到根，保证递归一定收敛（Build 已保证深度 ≤5，这里兜底）。
        for (node in categoryNodes) {
            val seen = HashSet<String>()
            var current: String? = node.key
            while (current != null) {
                if (!seen.add(current)) {
                    effectiveParent[current] = null
                    break
                }
                current = effectiveParent[current]
            }
        }

        val categories = HashMap<String?, MutableList<GraphNode>>(categoryNodes.size)
        val concepts = HashMap<String, MutableList<GraphNode>>(categoryNodes.size)
        for (node in nodesByKey.values.sortedBy { it.key }) {
            when (node.kind) {
                GraphNodeKind.CATEGORY -> {
                    categories.getOrPut(effectiveParent[node.key]) { ArrayList() }.add(node)
                }
                GraphNodeKind.CONCEPT -> {
                    // 概念父节点必须是存在的分类，否则一律进未分类虚拟框。
                    val parentKey = node.parentKey?.takeIf { nodesByKey[it]?.kind == GraphNodeKind.CATEGORY }
                        ?: GRAPH_UNCLASSIFIED_KEY
                    concepts.getOrPut(parentKey) { ArrayList() }.add(node)
                }
            }
        }
        categoryChildren = categories.mapValues { (_, list) -> list.sortedWith(CATEGORY_ORDER) }
        conceptChildren = concepts.mapValues { (_, list) -> list.sortedWith(CONCEPT_ORDER) }
        usedCollapsed = collapsed.filter { key ->
            if (key == GRAPH_UNCLASSIFIED_KEY) {
                conceptChildren[GRAPH_UNCLASSIFIED_KEY].orEmpty().isNotEmpty()
            } else {
                nodesByKey[key]?.kind == GraphNodeKind.CATEGORY
            }
        }.toSet()
    }

    /** 计算完整布局：根块 masonry + 递归落坐标 + 算 bounds。 */
    fun layout(): GraphLayout {
        val rootKeys = orderedRootKeys()
        if (rootKeys.isEmpty()) return GraphLayout.Empty
        val measuredRoots = rootKeys.map { key -> measure(key, labelOf(key), depth = 0, maxOuterWidth = options.maxRootWidth) }

        val boxes = LinkedHashMap<String, GraphBox>(measuredRoots.size)
        val chips = LinkedHashMap<String, GraphChipItem>()
        val roots = ArrayList<GraphBox>(measuredRoots.size)
        var union: GraphRect? = null
        var x = 0f
        var y = 0f
        var rowHeight = 0f
        for (root in measuredRoots) {
            if (x > 0f && x + root.width > options.maxCanvasWidth) {
                y += rowHeight + options.rootGap
                x = 0f
                rowHeight = 0f
            }
            val box = place(root, options.padding + x, options.padding + y, boxes, chips)
            roots.add(box)
            union = union?.union(box.bounds) ?: box.bounds
            x += root.width + options.columnGap
            rowHeight = max(rowHeight, root.height)
        }
        val bounds = (union ?: GraphRect.Zero).inflate(options.padding)
        return GraphLayout(
            bounds = bounds,
            roots = roots,
            boxesByKey = boxes,
            chipsByKey = chips,
            collapsed = usedCollapsed,
        )
    }

    /** 根块顺序：用户分类自然序；warm 命中时按 rootOrder 复原（新出现的接后面）；未分类永远最后。 */
    private fun orderedRootKeys(): List<String> {
        val roots = categoryChildren[null].orEmpty()
        val result = ArrayList<String>(roots.size + 1)
        val cached = warm
        if (cached != null) {
            val rootKeys = roots.map { it.key }.toHashSet()
            val used = HashSet<String>()
            for (key in cached.rootOrder) {
                if (key in rootKeys && used.add(key)) result.add(key)
            }
            val fallback = roots
                .filter { it.key !in used }
                .sortedWith(warmFallbackOrder(cached))
            for (node in fallback) result.add(node.key)
        } else {
            for (node in roots) result.add(node.key)
        }
        if (conceptChildren[GRAPH_UNCLASSIFIED_KEY].orEmpty().isNotEmpty()) {
            result.add(GRAPH_UNCLASSIFIED_KEY)
        }
        return result
    }

    /** warm 命中后新根块的排序：仍按 (sortOrder, name)，旧坐标只做同序 tie-break（不用于摆放）。 */
    private fun warmFallbackOrder(cached: LayoutCache): Comparator<GraphNode> =
        compareBy<GraphNode> { it.sortOrder }
            .thenBy { it.name }
            .thenBy { node -> cached.anchors[node.key]?.y ?: Float.POSITIVE_INFINITY }
            .thenBy { node -> cached.anchors[node.key]?.x ?: Float.POSITIVE_INFINITY }
            .thenBy { it.key }

    private fun labelOf(key: String): String =
        if (key == GRAPH_UNCLASSIFIED_KEY) UNCLASSIFIED_LABEL else graph.nodes[key]?.name ?: key

    /**
     * 测量一个分类框（[maxOuterWidth] 是父容器允许给它的最大外宽）。
     * 先按「可能的最大内容宽」量一遍得到自然宽，再按 clamp 后的最终内容宽重排被压窄的芯片/子框。
     */
    private fun measure(key: String, name: String, depth: Int, maxOuterWidth: Float): MeasuredBox {
        val padding = options.boxPadding
        val maxContentWidth = max(MIN_CONTENT_WIDTH, maxOuterWidth - padding * 2f)

        if (key in usedCollapsed) {
            val headerWidth = estimateLabelWidth(name, options.headerFontPx)
            val contentWidth = max(MIN_COLLAPSED_CONTENT_WIDTH, headerWidth).coerceAtMost(maxContentWidth)
            val width = (contentWidth + padding * 2f).coerceAtMost(maxOuterWidth)
            return MeasuredBox(
                categoryKey = key,
                depth = depth,
                collapsed = true,
                width = width,
                height = options.headerHeight + padding * 2f,
                headerHeight = options.headerHeight,
                contentWidth = width - padding * 2f,
                chips = emptyList(),
                children = emptyList(),
            )
        }

        val chipNodes = conceptChildren[key].orEmpty()
        val childNodes = categoryChildren[key].orEmpty()
        val headerWidth = estimateLabelWidth(name, options.headerFontPx)

        // 第一遍：按本框内容宽上界测量（子框宽 ≤ 本框内容宽上界）。
        val childrenFirstPass = childNodes.map { child ->
            measure(child.key, labelOf(child.key), depth + 1, maxContentWidth)
        }
        val chipsFirstPass = flowChips(chipNodes, maxContentWidth)
        val widestChild = childrenFirstPass.maxOfOrNull { it.width } ?: 0f
        val rawContentWidth = max(max(headerWidth, chipsFirstPass.width), widestChild)
        val minWidth = if (depth == 0) options.minRootWidth else MIN_NON_ROOT_WIDTH
        var width = (rawContentWidth + padding * 2f)
            .coerceAtLeast(minWidth)
            .coerceAtMost(options.maxRootWidth)
            .coerceAtMost(maxOuterWidth)
        var contentWidth = width - padding * 2f
        if (contentWidth < MIN_CONTENT_WIDTH) {
            contentWidth = MIN_CONTENT_WIDTH
            width = contentWidth + padding * 2f
        }

        // 第二遍：框被 maxRootWidth / 父框内容宽压窄时，按最终内容宽重新换行 / 重量子框。
        val chips = if (chipsFirstPass.width > contentWidth) flowChips(chipNodes, contentWidth) else chipsFirstPass
        val children = if (widestChild > contentWidth) {
            childrenFirstPass.mapIndexed { index, child ->
                if (child.width > contentWidth) {
                    measure(childNodes[index].key, labelOf(childNodes[index].key), depth + 1, contentWidth)
                } else {
                    child
                }
            }
        } else {
            childrenFirstPass
        }

        // 纵向布局：header → (chipGap + 芯片网格) → (categoryGap + 子框块) → padding。
        val chipBlockHeight = if (chips.rows.isEmpty()) 0f else options.chipGap + chips.height
        val packed = packChildren(children, contentWidth, options.categoryGap)
        val childrenBlockHeight = if (children.isEmpty()) 0f else options.categoryGap + packed.height
        val height = padding * 2f + options.headerHeight + chipBlockHeight + childrenBlockHeight

        // 芯片相对内容区原点的坐标（内容区原点 = 框内 padding 之后）。
        val measuredChips = ArrayList<MeasuredChip>(chipNodes.size)
        var chipY = options.headerHeight + options.chipGap
        for (row in chips.rows) {
            var chipX = 0f
            for (draft in row) {
                measuredChips.add(
                    MeasuredChip(
                        key = draft.key,
                        width = draft.width,
                        height = options.chipHeight,
                        x = chipX,
                        y = chipY,
                    ),
                )
                chipX += draft.width + options.chipGap
            }
            chipY += options.chipHeight + options.chipGap
        }

        // 子框相对内容区原点的坐标：芯片块之后，隔 categoryGap。
        val childrenTop = options.headerHeight + chipBlockHeight
        val measuredChildren = packed.children.map { child ->
            MeasuredChild(
                box = child.box,
                x = child.x,
                y = childrenTop + options.categoryGap + child.y,
            )
        }

        return MeasuredBox(
            categoryKey = key,
            depth = depth,
            collapsed = false,
            width = width,
            height = height,
            headerHeight = options.headerHeight,
            contentWidth = contentWidth,
            chips = measuredChips,
            children = measuredChildren,
        )
    }

    /** 芯片流式换行：每行最多 [LayoutOptions.maxChipColumns] 列，且行宽不超过 [maxWidth]。 */
    private fun flowChips(nodes: List<GraphNode>, maxWidth: Float): ChipFlow {
        if (nodes.isEmpty()) return ChipFlow(emptyList(), 0f, 0f)
        val maxColumns = options.maxChipColumns.coerceAtLeast(1)
        val rows = ArrayList<List<ChipDraft>>()
        var row = ArrayList<ChipDraft>()
        var rowWidth = 0f
        for (node in nodes) {
            var chipWidth = estimateLabelWidth(node.name, options.chipFontPx) + CHIP_HORIZONTAL_PADDING
            if (chipWidth > maxWidth) chipWidth = maxWidth
            if (row.isNotEmpty()) {
                val nextWidth = rowWidth + options.chipGap + chipWidth
                if (row.size >= maxColumns || nextWidth > maxWidth) {
                    rows.add(row)
                    row = ArrayList()
                    rowWidth = 0f
                }
            }
            row.add(ChipDraft(node.key, chipWidth))
            rowWidth = if (row.size == 1) chipWidth else rowWidth + options.chipGap + chipWidth
        }
        if (row.isNotEmpty()) rows.add(row)
        val flowWidth = rows.maxOfOrNull { singleRow ->
            singleRow.fold(0f) { acc, chip -> acc + chip.width } + options.chipGap * (singleRow.size - 1)
        } ?: 0f
        val flowHeight = rows.size * options.chipHeight + (rows.size - 1) * options.chipGap
        return ChipFlow(rows, flowWidth, flowHeight)
    }

    /** 子框嵌套排布：按顺序放到父框内容宽内，放不下换行（行高 = 该行最高子框）。 */
    private fun packChildren(children: List<MeasuredBox>, maxWidth: Float, gap: Float): PackedChildren {
        if (children.isEmpty()) return PackedChildren(emptyList(), 0f, 0f)
        val placed = ArrayList<MeasuredChild>(children.size)
        var x = 0f
        var y = 0f
        var rowHeight = 0f
        var blockWidth = 0f
        for (child in children) {
            if (x > 0f && x + child.width > maxWidth) {
                y += rowHeight + gap
                x = 0f
                rowHeight = 0f
            }
            placed.add(MeasuredChild(child, x, y))
            x += child.width + gap
            rowHeight = max(rowHeight, child.height)
            blockWidth = max(blockWidth, x - gap)
        }
        return PackedChildren(placed, blockWidth, y + rowHeight)
    }

    /** 递归落世界坐标：父框坐标 + 相对偏移，并顺手登记 boxesByKey / chipsByKey。 */
    private fun place(
        measured: MeasuredBox,
        left: Float,
        top: Float,
        boxes: MutableMap<String, GraphBox>,
        chips: MutableMap<String, GraphChipItem>,
    ): GraphBox {
        val padding = options.boxPadding
        val contentLeft = left + padding
        val contentTop = top + padding

        val childBoxes = ArrayList<GraphBox>(measured.children.size)
        for (child in measured.children) {
            childBoxes.add(place(child.box, contentLeft + child.x, contentTop + child.y, boxes, chips))
        }

        val chipKeys = ArrayList<String>(measured.chips.size)
        for (chip in measured.chips) {
            chips[chip.key] = GraphChipItem(
                key = chip.key,
                bounds = GraphRect(
                    left = contentLeft + chip.x,
                    top = contentTop + chip.y,
                    right = contentLeft + chip.x + chip.width,
                    bottom = contentTop + chip.y + chip.height,
                ),
            )
            chipKeys.add(chip.key)
        }

        val box = GraphBox(
            categoryKey = measured.categoryKey,
            depth = measured.depth,
            collapsed = measured.collapsed,
            bounds = GraphRect(left, top, left + measured.width, top + measured.height),
            headerBounds = GraphRect(
                left = contentLeft,
                top = contentTop,
                right = left + measured.width - padding,
                bottom = contentTop + measured.headerHeight,
            ),
            chips = chipKeys,
            children = childBoxes,
        )
        boxes[measured.categoryKey] = box
        return box
    }
}

/** 判断一个 code point 是否按「全角 / 1em」估算（CJK、假名、Hangul、全角标点、emoji 等）。 */
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
