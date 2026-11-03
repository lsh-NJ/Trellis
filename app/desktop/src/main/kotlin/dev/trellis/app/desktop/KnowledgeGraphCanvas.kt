package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalExtraColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

/**
 * 知识网络画布（设计 §4/§5）。
 *
 * 这里只做「把 [GraphLayout] 画出来 + 把指针坐标翻译回节点 key」：
 * 数据、分层、布局全在 [KnowledgeGraphLayout] / [KnowledgeGraphBuild] 里，
 * 画布不读文件、不改分类树、不知道 AI 建议从哪来。
 *
 * 画法约定（与 `docs/assets/knowledge-graph/page-mockup.svg` 对齐）：
 * - 分类框嵌套，标题行在最上面；概念是框里的圆角芯片；不画树边线。
 * - 未分类框最后画，虚线描边。
 * - 只有聚焦某个节点时才画它的一跳关系边（默认零关系线）。
 * - 世界坐标 = dp；相机负责缩放/平移；文字用 [TextMeasurer] 量出来后再画。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun KnowledgeGraphCanvas(
    graph: KnowledgeGraph,
    layout: GraphLayout,
    camera: Camera,
    selectedKey: String?,
    showCoOccurrence: Boolean,
    modifier: Modifier = Modifier,
    onCameraChange: (Camera) -> Unit,
    onCanvasSize: (GraphSize) -> Unit = {},
    onChipClick: (String) -> Unit,
    onCategoryClick: (String) -> Unit,
    onBackgroundClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extra: ExtraColors = LocalExtraColors.current
    val measurer = rememberTextMeasurer(cacheSize = 1024)
    val latestCamera by rememberUpdatedState(camera)
    val latestLayout by rememberUpdatedState(layout)
    val canvasSize = remember { androidx.compose.runtime.mutableStateOf<GraphSize?>(null) }
    val latestCanvasSize by rememberUpdatedState(canvasSize.value)

    val colors = remember(scheme, extra) {
        GraphCanvasColors(
            canvasBackground = scheme.background,
            dot = scheme.onSurfaceVariant.copy(alpha = 0.16f),
            boxFill = scheme.surface,
            boxBorder = extra.dataTrack,
            boxHeaderFill = scheme.surfaceVariant.copy(alpha = 0.55f),
            chipFill = Color(0xFFF1F4F9),
            chipBorder = extra.dataTrack,
            chipText = scheme.onSurface,
            categoryText = scheme.onSurface,
            mutedText = extra.dataMuted,
            accent = extra.data,
            conceptGreen = extra.accentText,
            suggestionFill = Color(0xFFFCF7E8),
            skeleton = extra.dataMuted,
            aiBadge = extra.warning,
            unclassifiedFill = Color(0xFFFBF6EC),
            relationPrerequisite = extra.data,
            relationApplies = Color(0xFF4E6FA8),
            relationRelated = extra.dataMuted,
            relationCoOccur = extra.dataTrack,
            danger = extra.danger,
        )
    }

    val focus = remember(graph, selectedKey) {
        if (selectedKey == null) null else FocusRing.of(graph, selectedKey)
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                val size = GraphSize(it.width.toFloat(), it.height.toFloat())
                canvasSize.value = size
                onCanvasSize(size)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { latestCamera.fitToBounds(latestLayout.bounds, latestCanvasSize)?.let(onCameraChange) },
                    onTap = { position ->
                        val world = latestCamera.screenToWorld(GraphPoint(position.x, position.y))
                        when (val hit = latestLayout.hitGraphAt(world)) {
                            is GraphHit.Chip -> onChipClick(hit.key)
                            is GraphHit.CategoryHeader -> onCategoryClick(hit.key)
                            is GraphHit.CategoryBox -> onCategoryClick(hit.key)
                            null -> onBackgroundClick()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val c = onCameraChange
                    c(latestCamera.panned(drag.x, drag.y))
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val change = event.changes.firstOrNull() ?: return@onPointerEvent
                val delta = change.scrollDelta.y
                if (abs(delta) < 0.01f) return@onPointerEvent
                val factor = if (delta > 0f) 0.9f else 1.1f
                onCameraChange(
                    latestCamera.zoomedAt(
                        anchor = GraphPoint(change.position.x, change.position.y),
                        factor = factor,
                    ),
                )
            },
    ) {
        drawRect(colors.canvasBackground)
        drawDotGrid(colors.dot, camera)
        translate(left = camera.panX, top = camera.panY) {
            scale(camera.zoom, camera.zoom, pivot = Offset.Zero) {
                drawGraph(
                    graph = graph,
                    layout = layout,
                    colors = colors,
                    measurer = measurer,
                    showCoOccurrence = showCoOccurrence,
                    focus = focus,
                )
            }
        }
    }
}

private data class GraphCanvasColors(
    val canvasBackground: Color,
    val dot: Color,
    val boxFill: Color,
    val boxBorder: Color,
    val boxHeaderFill: Color,
    val chipFill: Color,
    val chipBorder: Color,
    val chipText: Color,
    val categoryText: Color,
    val mutedText: Color,
    val accent: Color,
    val conceptGreen: Color,
    val suggestionFill: Color,
    val skeleton: Color,
    val aiBadge: Color,
    val unclassifiedFill: Color,
    val relationPrerequisite: Color,
    val relationApplies: Color,
    val relationRelated: Color,
    val relationCoOccur: Color,
    val danger: Color,
)

/** 聚焦时的一跳邻居集合；画边、淡化都只认它。 */
private data class FocusRing(
    val selected: String,
    val neighbors: Set<String>,
    val edges: List<GraphEdge>,
) {
    companion object {
        fun of(graph: KnowledgeGraph, selectedKey: String): FocusRing {
            val edges = graph.edges.filter { it.fromKey == selectedKey || it.toKey == selectedKey }
            val neighbors = edges.flatMap { listOf(it.fromKey, it.toKey) }.toSet() - selectedKey
            return FocusRing(selectedKey, neighbors, edges)
        }
    }
}

private fun DrawScope.drawDotGrid(dot: Color, camera: Camera) {
    val spacing = 24f * camera.zoom
    if (spacing < 6f) return // 缩得太小时点阵变噪点，直接不画
    val startX = camera.panX % spacing
    val startY = camera.panY % spacing
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(dot, radius = 1.1f * camera.zoom.coerceIn(0.5f, 1.6f), center = Offset(x, y))
            y += spacing
        }
        x += spacing
    }
}

/** 把整张图 fit 进画布（padding 32dp，最大 zoom 1.5）。 */
internal fun Camera.fitToBounds(bounds: GraphRect, canvas: GraphSize?): Camera? {
    val area = canvas ?: return null
    if (area.width <= 0f || area.height <= 0f) return null
    val contentWidth = bounds.width
    val contentHeight = bounds.height
    if (contentWidth <= 0f || contentHeight <= 0f) return null
    val pad = 32f
    val zoom = min(
        (area.width - pad * 2f) / contentWidth,
        (area.height - pad * 2f) / contentHeight,
    ).coerceIn(0.25f, 1.5f)
    return Camera(
        zoom = zoom,
        panX = (area.width - contentWidth * zoom) / 2f - bounds.left * zoom,
        panY = (area.height - contentHeight * zoom) / 2f - bounds.top * zoom,
    )
}

/** 画布自己的命中测试（不依赖布局模块的实现细节）：先芯片、再标题行、最后空白框。 */
private fun GraphLayout.hitGraphAt(point: GraphPoint): GraphHit? {
    chipsByKey.values.firstOrNull { it.bounds.contains(point) }?.let { return GraphHit.Chip(it.key) }
    val boxes = boxesByKey.values.sortedByDescending { it.depth }
    for (box in boxes) {
        if (box.headerBounds.contains(point)) {
            return GraphHit.CategoryHeader(box.categoryKey ?: GRAPH_UNCLASSIFIED_KEY)
        }
    }
    for (box in boxes) {
        if (box.bounds.contains(point)) {
            return GraphHit.CategoryBox(box.categoryKey ?: GRAPH_UNCLASSIFIED_KEY)
        }
    }
    return null
}

private fun DrawScope.drawGraph(
    graph: KnowledgeGraph,
    layout: GraphLayout,
    colors: GraphCanvasColors,
    measurer: TextMeasurer,
    showCoOccurrence: Boolean,
    focus: FocusRing?,
) {
    val dimmed = focus != null
    for (root in layout.roots) {
        drawBox(
            graph = graph,
            layout = layout,
            box = root,
            colors = colors,
            measurer = measurer,
            focus = focus,
            showCoOccurrence = showCoOccurrence,
        )
    }
    if (dimmed && focus != null) {
        drawFocusEdges(graph, layout, focus, colors, measurer, showCoOccurrence)
    }
}

/**
 * 聚焦时的一跳关系边：只画选中节点直接相连的边，其它照旧不画（设计 §4.4）。
 * 折叠/聚合导致节点没有芯片盒时，退到它所在分类的标题行画。
 */
private fun DrawScope.drawFocusEdges(
    graph: KnowledgeGraph,
    layout: GraphLayout,
    focus: FocusRing,
    colors: GraphCanvasColors,
    measurer: TextMeasurer,
    showCoOccurrence: Boolean,
) {
    for (edge in focus.edges) {
        if (edge.kind == GraphEdgeKind.CO_OCCUR && !showCoOccurrence) continue
        val from = anchorOf(layout, edge.fromKey) ?: continue
        val to = anchorOf(layout, edge.toKey) ?: continue
        val color = when (edge.kind) {
            GraphEdgeKind.PREREQUISITE -> colors.relationPrerequisite
            GraphEdgeKind.APPLIES -> colors.relationApplies
            GraphEdgeKind.RELATED -> colors.relationRelated
            GraphEdgeKind.CO_OCCUR -> colors.relationCoOccur
        }
        val dashed = edge.kind != GraphEdgeKind.PREREQUISITE
        drawLine(
            color = color,
            start = Offset(from.x, from.y),
            end = Offset(to.x, to.y),
            strokeWidth = if (edge.kind == GraphEdgeKind.CO_OCCUR) 1f else 1.6f,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null,
        )
        if (edge.kind == GraphEdgeKind.PREREQUISITE || edge.kind == GraphEdgeKind.APPLIES) {
            drawArrowHead(color, from, to)
        }
        // 类型小标（不是每条都写 reason，reason 在右栏详情里看）。
        val label = when (edge.kind) {
            GraphEdgeKind.PREREQUISITE -> "前置"
            GraphEdgeKind.APPLIES -> "应用"
            GraphEdgeKind.RELATED -> "相关"
            GraphEdgeKind.CO_OCCUR -> "共现"
        }
        val midX = (from.x + to.x) / 2f
        val midY = (from.y + to.y) / 2f
        val text = measureLabel(measurer, label, 8.5f, FontWeight.Medium, color, 60f)
        drawText(text, topLeft = Offset(midX - text.size.width / 2f, midY - text.size.height - 2f))
    }
}

private fun DrawScope.drawArrowHead(color: Color, from: GraphPoint, to: GraphPoint) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.sqrt(dx * dx + dy * dy)
    if (length < 1f) return
    val ux = dx / length
    val uy = dy / length
    // 箭头落在目标芯片边缘附近，留 14dp 给标签/圆角。
    val tipX = to.x - ux * 16f
    val tipY = to.y - uy * 16f
    val size = 5f
    val px = -uy * size
    val py = ux * size
    val path = Path().apply {
        moveTo(tipX, tipY)
        lineTo(tipX - ux * size * 1.8f + px, tipY - uy * size * 1.8f + py)
        lineTo(tipX - ux * size * 1.8f - px, tipY - uy * size * 1.8f - py)
        close()
    }
    drawPath(path, color)
}

/** 节点在布局里的锚点：芯片中心优先；折叠/未出现在布局里时退到所属分类标题中心。 */
internal fun anchorOf(layout: GraphLayout, key: String): GraphPoint? {
    layout.chipsByKey[key]?.let { return it.bounds.centerPoint() }
    val boxes = layout.boxesByKey.values.sortedByDescending { it.depth }
    for (box in boxes) {
        if (boxContains(box, key)) return box.headerBounds.centerPoint()
    }
    return null
}

private fun boxContains(box: GraphBox, key: String): Boolean =
    box.chips.contains(key) || box.children.any { boxContains(it, key) }

private fun DrawScope.drawBox(
    graph: KnowledgeGraph,
    layout: GraphLayout,
    box: GraphBox,
    colors: GraphCanvasColors,
    measurer: TextMeasurer,
    focus: FocusRing?,
    showCoOccurrence: Boolean,
) {
    val bounds = box.bounds
    if (bounds.width <= 0f || bounds.height <= 0f) return
    val isUnclassified = box.categoryKey == GRAPH_UNCLASSIFIED_KEY
    val boxColor = if (isUnclassified) colors.unclassifiedFill else colors.boxFill
    val borderColor = if (isUnclassified) colors.boxBorder else colors.boxBorder

    drawRoundRect(
        color = boxColor,
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        cornerRadius = CornerRadius(10f),
    )
    drawRoundRect(
        color = borderColor,
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        cornerRadius = CornerRadius(10f),
        style = Stroke(
            width = 1f,
            pathEffect = if (isUnclassified) PathEffect.dashPathEffect(floatArrayOf(5f, 4f)) else null,
        ),
    )

    // 标题行：折叠箭头 + 名字 + 计数 + AI 徽标。
    val header = box.headerBounds
    val categoryNode = box.categoryKey?.let { graph.nodes[it] }
    val name = if (isUnclassified) "未分类" else categoryNode?.name ?: "分类"
    val descendantCount = countChips(layout, box)
    val arrow = if (box.collapsed) "▸" else "▾"
    val title = "$arrow  $name"
    val titleLayout = measureLabel(
        measurer = measurer,
        text = title,
        fontSizeSp = 13f,
        weight = FontWeight.SemiBold,
        color = colors.categoryText,
        maxWidth = max(80f, header.width - 48f),
    )
    drawText(
        textLayoutResult = titleLayout,
        topLeft = Offset(header.left + 10f, header.top + (header.height - titleLayout.size.height) / 2f),
    )
    val countText = "$descendantCount"
    val countLayout = measureLabel(measurer, countText, 10.5f, FontWeight.Normal, colors.mutedText)
    drawText(
        textLayoutResult = countLayout,
        topLeft = Offset(header.right - countLayout.size.width - 10f, header.top + (header.height - countLayout.size.height) / 2f),
    )
    if (categoryNode?.origin == NodeOrigin.AI && !categoryNode.confirmed) {
        val badge = measureLabel(measurer, "AI 建议", 9f, FontWeight.Medium, colors.aiBadge)
        drawText(
            textLayoutResult = badge,
            topLeft = Offset(header.right - countLayout.size.width - badge.size.width - 16f, header.top + (header.height - badge.size.height) / 2f),
        )
    }
    drawLine(
        color = colors.boxBorder,
        start = Offset(header.left + 8f, header.bottom),
        end = Offset(header.right - 8f, header.bottom),
        strokeWidth = 1f,
    )

    // 子分类递归。
    for (child in box.children) {
        drawBox(
            graph = graph,
            layout = layout,
            box = child,
            colors = colors,
            measurer = measurer,
            focus = focus,
            showCoOccurrence = showCoOccurrence,
        )
    }

    // 芯片。
    for (chipKey in box.chips) {
        val chip = layout.chipsByKey[chipKey] ?: continue
        drawChip(graph, chip, chipKey, colors, measurer, focus)
    }
}

private fun DrawScope.drawChip(
    graph: KnowledgeGraph,
    chip: GraphChipItem,
    chipKey: String,
    colors: GraphCanvasColors,
    measurer: TextMeasurer,
    focus: FocusRing?,
) {
    val node = graph.nodes[chipKey] ?: return
    val bounds = chip.bounds
    val isSelected = focus?.selected == chipKey
    val isNeighbor = focus?.neighbors?.contains(chipKey) == true
    val dim = focus != null && !isSelected && !isNeighbor

    val fill = when {
        isSelected -> colors.conceptGreen.copy(alpha = 0.22f)
        node.frontmatterOnly -> colors.chipFill.copy(alpha = 0.55f)
        else -> colors.chipFill
    }
    val alpha = if (dim) 0.18f else 1f
    val fillColor = fill.copy(alpha = fill.alpha * alpha)
    val border = when {
        isSelected -> colors.conceptGreen
        isNeighbor -> colors.accent.copy(alpha = 0.75f * alpha)
        else -> colors.chipBorder.copy(alpha = alpha)
    }
    drawRoundRect(
        color = fillColor,
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        cornerRadius = CornerRadius(7f),
    )
    drawRoundRect(
        color = border,
        topLeft = Offset(bounds.left, bounds.top),
        size = Size(bounds.width, bounds.height),
        cornerRadius = CornerRadius(7f),
        style = Stroke(
            width = if (isSelected) 1.8f else 1f,
            pathEffect = if (node.frontmatterOnly) PathEffect.dashPathEffect(floatArrayOf(4f, 3f)) else null,
        ),
    )
    val label = if (node.noteCount > 1) "${node.name}  ${node.noteCount}篇" else node.name
    val text = measureLabel(
        measurer = measurer,
        text = label,
        fontSizeSp = 11f,
        weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        color = colors.chipText.copy(alpha = if (dim) 0.25f else 1f),
        maxWidth = max(40f, bounds.width - 14f),
    )
    drawText(
        textLayoutResult = text,
        topLeft = Offset(bounds.left + 7f, bounds.top + (bounds.height - text.size.height) / 2f),
    )
}

/** 量一行标签；超宽用省略号（TextMeasurer 受 maxWidth 约束）。 */
private fun measureLabel(
    measurer: TextMeasurer,
    text: String,
    fontSizeSp: Float,
    weight: FontWeight,
    color: Color,
    maxWidth: Float = Float.POSITIVE_INFINITY,
): TextLayoutResult =
    measurer.measure(
        text = text,
        style = TextStyle(
            fontSize = fontSizeSp.sp,
            fontWeight = weight,
            color = color,
            lineHeight = fontSizeSp.sp * 1.25f,
        ),
        maxLines = 1,
        softWrap = false,
        constraints = androidx.compose.ui.unit.Constraints(
            minWidth = 0,
            maxWidth = if (maxWidth.isFinite()) max(1, maxWidth.toInt()) else androidx.compose.ui.unit.Constraints.Infinity,
        ),
    )

internal fun countChips(layout: GraphLayout, box: GraphBox): Int {
    var count = box.chips.size
    for (child in box.children) count += countChips(layout, child)
    return count
}

/** 分类框折叠/展开的命中区域是否是「箭头」；画布点击标题行整行都当切换折叠。 */
internal fun GraphBox.headerHeight(): Float = headerBounds.height

/** 世界坐标里两个节点的连线中点（没有布局时说哪都算 0）。 */
internal fun relationAnchor(layout: GraphLayout, key: String): GraphPoint? =
    layout.chipsByKey[key]?.bounds?.centerPoint()

internal fun GraphRect.centerPoint(): GraphPoint = GraphPoint((left + right) / 2f, (top + bottom) / 2f)

internal fun GraphRect.toComposeRect(): Rect = Rect(left, top, right, bottom)
