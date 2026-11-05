package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ui.LocalExtraColors
import kotlin.math.roundToInt

/**
 * 知识网络画布（设计 v1.2：只有「节点 + 父子连线」）。
 *
 * - 分类画成「虚」节点：空心、虚线圆角框；
 * - 概念画成「实」节点：实心圆角框；
 * - 只画树边（父 → 子），其它关系全部收进右下的聚焦小浮窗；
 * - 点击节点选中并聚焦（其它节点淡出 + 选中节点轻微放大），双击分类折叠/展开；
 * - v1.3：把虚节点（分类）拖到任意节点上，被拖的虚节点会成为那个节点的父分类——
 *   用来把「没有上级的实节点」收进新建的大类。
 *
 * 相机、折叠状态都由调用方（[KnowledgeGraphView]）持有，本组件只负责画和发事件。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun KnowledgeGraphCanvas(
    graph: KnowledgeGraph,
    layout: GraphLayout,
    camera: Camera,
    selectedKey: String?,
    modifier: Modifier = Modifier,
    onCameraChange: (Camera) -> Unit = {},
    onCanvasSize: (GraphSize) -> Unit = {},
    onNodeClick: (String) -> Unit = {},
    onCategoryToggle: (String) -> Unit = {},
    onBackgroundClick: () -> Unit = {},
    onLinkDrop: (draggedKey: String, targetKey: String) -> Unit = { _, _ -> },
) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current.density
    val measurer = rememberTextMeasurer(cacheSize = 1024)
    var canvasSize by remember { mutableStateOf(GraphSize.Zero) }
    // 手势回调里不能闭包旧的 camera（一平移就过期）：用 rememberUpdatedState 取最新值。
    val currentCamera by rememberUpdatedState(camera)
    // 拖动虚节点：拖起来时记录被拖节点和指针屏幕坐标，松手时把落点节点变成它的子节点。
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragScreen by remember { mutableStateOf<Offset?>(null) }
    var dropTargetKey by remember { mutableStateOf<String?>(null) }

    val colors = remember(extra, scheme) {
        GraphCanvasColors(
            background = scheme.surface,
            dot = extra.dataTrack.copy(alpha = 0.55f),
            link = extra.dataTrack,
            linkFocused = extra.accentText.copy(alpha = 0.75f),
            categoryBorder = extra.dataMuted,
            categoryText = extra.data,
            conceptFill = scheme.surfaceVariant,
            conceptBorder = extra.dataTrack,
            conceptText = scheme.onSurface,
            unclassifiedBorder = extra.warning.copy(alpha = 0.6f),
            mutedText = scheme.onSurfaceVariant,
            accent = extra.accentText,
        )
    }

    // 聚焦：选中节点 + 树上直接相邻的节点保持不透明，其它淡出。
    val neighborKeys = remember(layout, selectedKey) {
        if (selectedKey == null) emptySet()
        else layout.links
            .filter { it.fromKey == selectedKey || it.toKey == selectedKey }
            .map { if (it.fromKey == selectedKey) it.toKey else it.fromKey }
            .toSet()
    }
    val selectedScale by animateFloatAsState(
        targetValue = if (selectedKey != null && selectedKey in layout.nodes) 1.06f else 1f,
        animationSpec = spring(stiffness = 520f, dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "graph-select",
    )

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // 画布内容会平移/缩放，必须 clip，否则节点会画到左栏上（v1.3 实测溢出）。
                .clipToBounds()
                .onSizeChanged { size ->
                    val next = GraphSize(size.width.toFloat(), size.height.toFloat())
                    canvasSize = next
                    onCanvasSize(next)
                }
                // 滚轮缩放。`Modifier.onPointerEvent` 只有桌面有，所以自己开一个事件循环：
                // 两端都能用，手机上手指捏合由外层的手势处理（见 GraphGestures）。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val delta = change.scrollDelta.y
                            if (delta == 0f) continue
                            val factor = if (delta < 0f) 1.1f else 0.9f
                            val anchor = GraphPoint(change.position.x, change.position.y)
                            onCameraChange(currentCamera.zoomedAt(anchor, factor))
                            change.consume()
                        }
                    }
                }
                .pointerInput(layout) {
                    detectTapGestures(
                        onTap = { offset ->
                            val world = currentCamera.screenToWorld(GraphPoint(offset.x, offset.y))
                            val hit = hitNode(layout, world)
                            if (hit == null) onBackgroundClick() else onNodeClick(hit)
                        },
                        onDoubleTap = { offset ->
                            val world = currentCamera.screenToWorld(GraphPoint(offset.x, offset.y))
                            val hit = hitNode(layout, world) ?: return@detectTapGestures
                            if (graph.nodes[hit]?.kind == GraphNodeKind.CATEGORY) {
                                onCategoryToggle(hit)
                            }
                        },
                    )
                }
                .pointerInput(layout) {
                    // 自己写手势循环：detectDragGestures 的 onDragStart 给的是「穿过 slop 之后」的位置，
                    // 贴着节点边缘起拖会判不到节点；这里改用按下点做命中，再做 slop 判定。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downWorld = currentCamera.screenToWorld(
                            GraphPoint(down.position.x, down.position.y),
                        )
                        val pressedCategory = hitNode(layout, downWorld)?.takeIf { key ->
                            graph.nodes[key]?.kind == GraphNodeKind.CATEGORY
                        }
                        var pointer = down.position
                        var dragging = false
                        var target: String? = null
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position - pointer
                            pointer = change.position
                            if (!dragging) {
                                if ((change.position - down.position).getDistance() >
                                    viewConfiguration.touchSlop
                                ) {
                                    dragging = true
                                    if (pressedCategory != null) {
                                        dragKey = pressedCategory
                                        dragScreen = change.position
                                    }
                                }
                            }
                            if (dragging) {
                                change.consume()
                                if (pressedCategory != null) {
                                    dragScreen = change.position
                                    val world = currentCamera.screenToWorld(
                                        GraphPoint(change.position.x, change.position.y),
                                    )
                                    target = hitNode(layout, world)?.takeIf { key ->
                                        canDrop(graph, pressedCategory, key)
                                    }
                                    dropTargetKey = target
                                } else {
                                    onCameraChange(currentCamera.panned(delta.x, delta.y))
                                }
                            }
                        }
                        val dragged = dragKey
                        val dropped = dropTargetKey
                        dragKey = null
                        dragScreen = null
                        dropTargetKey = null
                        if (dragged != null && dropped != null) onLinkDrop(dragged, dropped)
                    }
                },
        ) {
            drawRect(color = colors.background, size = size)
            drawDotGrid(colors.dot, camera)

            translate(camera.panX, camera.panY) {
                scale(camera.zoom, pivot = Offset.Zero) {
                    // 树边先画，节点压在上面。
                    layout.links.forEach { link ->
                        val from = layout.nodes[link.fromKey] ?: return@forEach
                        val to = layout.nodes[link.toKey] ?: return@forEach
                        val isFocused = selectedKey != null &&
                            (link.fromKey == selectedKey || link.toKey == selectedKey)
                        val color = if (isFocused) colors.linkFocused else colors.link
                        val alpha = if (selectedKey == null || isFocused) 1f else 0.35f
                        drawTreeLink(from, to, color.copy(alpha = color.alpha * alpha), isFocused)
                    }

                    layout.nodes.values.forEach { box ->
                        val node = graph.nodes[box.key]
                        val isSelected = box.key == selectedKey
                        val isNeighbor = box.key in neighborKeys
                        val baseAlpha = if (selectedKey == null) 1f
                        else if (isSelected || isNeighbor) 1f
                        else 0.28f
                        val alpha = if (box.key == dragKey) 0.3f else baseAlpha
                        if (isSelected) {
                            scale(selectedScale, pivot = Offset(box.bounds.centerX, box.bounds.centerY)) {
                                drawTreeNode(colors, node, box, measurer, density, alpha, true)
                            }
                        } else {
                            drawTreeNode(colors, node, box, measurer, density, alpha, false)
                        }
                    }

                    // 拖动虚节点：从被拖节点画一条虚线到指针；落点节点画高亮框。
                    val pointer = dragScreen
                    val dragged = dragKey
                    if (pointer != null && dragged != null) {
                        layout.nodes[dragged]?.let { draggedBox ->
                            val target = currentCamera.screenToWorld(GraphPoint(pointer.x, pointer.y))
                            drawLine(
                                color = colors.accent.copy(alpha = 0.8f),
                                start = Offset(draggedBox.bounds.centerX, draggedBox.bounds.centerY),
                                end = Offset(target.x, target.y),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f)),
                            )
                        }
                        dropTargetKey?.let { targetKey ->
                            layout.nodes[targetKey]?.let { box ->
                                val pad = 3.dp.toPx()
                                drawRoundRect(
                                    color = colors.accent,
                                    topLeft = Offset(box.bounds.left - pad, box.bounds.top - pad),
                                    size = Size(box.bounds.width + pad * 2f, box.bounds.height + pad * 2f),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx(), 9.dp.toPx()),
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 绘制辅助 ----

private data class GraphCanvasColors(
    val background: Color,
    val dot: Color,
    val link: Color,
    val linkFocused: Color,
    val categoryBorder: Color,
    val categoryText: Color,
    val conceptFill: Color,
    val conceptBorder: Color,
    val conceptText: Color,
    val unclassifiedBorder: Color,
    val mutedText: Color,
    val accent: Color,
)

private const val DOT_SPACING_DP = 24f

private fun DrawScope.drawDotGrid(dotColor: Color, camera: Camera) {
    val spacing = DOT_SPACING_DP * density * camera.zoom
    if (spacing < 6f) return
    val startX = camera.panX % spacing
    val startY = camera.panY % spacing
    val radius = 1.dp.toPx()
    var x = startX
    while (x < size.width) {
        var y = startY
        while (y < size.height) {
            drawCircle(dotColor, radius = radius, center = Offset(x, y))
            y += spacing
        }
        x += spacing
    }
}

private fun DrawScope.drawTreeLink(
    from: GraphNodeBox,
    to: GraphNodeBox,
    color: Color,
    focused: Boolean,
) {
    val start = Offset(from.bounds.centerX, from.bounds.bottom)
    val end = Offset(to.bounds.centerX, to.bounds.top)
    val midY = (start.y + end.y) / 2f
    val path = Path().apply {
        moveTo(start.x, start.y)
        cubicTo(start.x, midY, end.x, midY, end.x, end.y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = if (focused) 2.dp.toPx() else 1.2.dp.toPx()),
    )
}

private fun DrawScope.drawTreeNode(
    colors: GraphCanvasColors,
    node: GraphNode?,
    box: GraphNodeBox,
    measurer: androidx.compose.ui.text.TextMeasurer,
    density: Float,
    alpha: Float,
    selected: Boolean,
) {
    val rect = box.bounds
    val left = rect.left
    val top = rect.top
    val width = rect.width
    val height = rect.height
    val isCategory = box.key == GRAPH_UNCLASSIFIED_KEY || node?.kind == GraphNodeKind.CATEGORY
    val label = when {
        box.key == GRAPH_UNCLASSIFIED_KEY -> "未分类"
        else -> node?.name ?: box.key
    }
    val corner = 8.dp.toPx()
    val border = if (selected) colors.accent else if (isCategory) colors.categoryBorder else colors.conceptBorder
    val borderWidth = if (selected) 2.dp.toPx() else 1.dp.toPx()
    val borderAlpha = if (selected) 1f else if (isCategory) 0.75f else 0.55f

    if (isCategory) {
        // 虚节点：空心 + 虚线边。
        drawRoundRect(
            color = border.copy(alpha = border.alpha * borderAlpha * alpha),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(
                width = borderWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
            ),
        )
        if (selected) {
            drawRoundRect(
                color = colors.accent.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )
        }
    } else {
        // 实节点：实心 + 细边。
        drawRoundRect(
            color = colors.conceptFill.copy(alpha = colors.conceptFill.alpha * alpha),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        )
        if (selected) {
            drawRoundRect(
                color = colors.accent.copy(alpha = 0.16f),
                topLeft = Offset(left, top),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            )
        }
        drawRoundRect(
            color = border.copy(alpha = border.alpha * borderAlpha * alpha),
            topLeft = Offset(left, top),
            size = Size(width, height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
            style = Stroke(width = borderWidth),
        )
    }

    val textColor = if (isCategory) colors.categoryText else colors.conceptText
    val fontSize = if (isCategory) 12.5.sp else 12.sp
    val style = TextStyle(
        color = textColor.copy(alpha = textColor.alpha * alpha),
        fontSize = fontSize,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
    )
    // 节点宽度本身已经含两端留白（布局里 estimateLabelWidth + padding）；
    // 这里只留很小的内缩，避免中文名/英文缩写被椭到只剩一两个字。
    val maxWidthPx = ((width - 8f).coerceAtLeast(12f)).roundToInt()
    val text = measurer.measure(
        text = AnnotatedString(label),
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = maxWidthPx),
    )
    drawText(
        textLayoutResult = text,
        topLeft = Offset(
            left + (width - text.size.width) / 2f,
            top + (height - text.size.height) / 2f,
        ),
    )
}

/**
 * 世界坐标里命中一个节点（后画的在上；节点不重叠，取面积最小的即可）。
 */
private fun hitNode(layout: GraphLayout, world: GraphPoint): String? =
    layout.nodes.entries
        .filter { it.value.bounds.contains(world) }
        .minByOrNull { it.value.bounds.width * it.value.bounds.height }
        ?.key

/**
 * 能不能把 [targetKey] 收成 [draggedKey] 的子节点（v1.3 拖动链接）。
 *
 * - 只有分类（虚节点）能被拖；
 * - 不能拖到自己身上；
 * - 不能拖到自己的后代上（否则成环）。
 */
private fun canDrop(graph: KnowledgeGraph, draggedKey: String, targetKey: String): Boolean {
    if (draggedKey == targetKey) return false
    val dragged = graph.nodes[draggedKey] ?: return false
    if (dragged.kind != GraphNodeKind.CATEGORY) return false
    if (graph.nodes[targetKey] == null) return false
    // 不能拖到自己的后代上（成环），也不能拖到自己的祖先上（同样成环）。
    if (graph.ancestorsOf(targetKey).any { it.key == draggedKey }) return false
    if (graph.ancestorsOf(draggedKey).any { it.key == targetKey }) return false
    return true
}

// ---- 相机工具（Screen 在打开/刷新/点「适应窗口」时用） ----

/**
 * 把 [bounds] 装进 [viewport]：留 [padding] 边距，zoom 夹在 0.25..[maxZoom]，居中。
 */
internal fun fitCamera(
    bounds: GraphRect,
    viewport: GraphSize,
    padding: Float = 32f,
    maxZoom: Float = 1.5f,
): Camera {
    if (viewport.isEmpty || bounds.width <= 0f || bounds.height <= 0f) return Camera.Default
    val availableW = (viewport.width - padding * 2f).coerceAtLeast(1f)
    val availableH = (viewport.height - padding * 2f).coerceAtLeast(1f)
    val zoom = minOf(availableW / bounds.width, availableH / bounds.height).coerceIn(0.25f, maxZoom)
    val panX = (viewport.width - bounds.width * zoom) / 2f - bounds.left * zoom
    val panY = (viewport.height - bounds.height * zoom) / 2f - bounds.top * zoom
    return Camera(zoom = zoom, panX = panX, panY = panY)
}

/**
 * 小浮窗的锚点：节点在屏幕上的右上角（画布坐标）。节点不可见时退回 null。
 */
internal fun popoverAnchor(
    layout: GraphLayout,
    key: String,
    camera: Camera,
): GraphPoint? {
    val box = layout.nodes[key] ?: return null
    val corner = GraphPoint(box.bounds.right, box.bounds.top)
    return camera.worldToScreen(corner)
}

/** 画布尺寸为零时（还没布局完）浮窗位置兜底用的估算。 */
internal fun GraphRect.centerScreen(camera: Camera): GraphPoint =
    camera.worldToScreen(center)
