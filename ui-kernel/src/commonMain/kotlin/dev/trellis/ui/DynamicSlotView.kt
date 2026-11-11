package dev.trellis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.SlotAction
import dev.trellis.kernel.slot.UiAlign
import dev.trellis.kernel.slot.UiFont
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiNoticeTone
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextSize
import dev.trellis.kernel.slot.UiTextStyle
import dev.trellis.kernel.slot.UiTextWeight
import dev.trellis.kernel.slot.UiTone
import kotlinx.coroutines.launch

/**
 * 由端上提供的「插件图片解析器」：`(owner, ref) -> 图片`。
 *
 * ui-kernel 是 commonMain，读不了插件目录；桌面端在启动时把解析器装进来。
 * 默认全返回 null —— 没有图片能力的端会降级成 alt 文字，而不是显示破图。
 */
val LocalPluginImage = staticCompositionLocalOf<(PluginId?, String) -> ImageBitmap?> {
    { _, _ -> null }
}

/** 当前卡片属于哪个插件。图片解析、样式覆盖都要它。 */
private val LocalSlotOwner = staticCompositionLocalOf<PluginId?> { null }

/**
 * 画一张**由代码生成**的卡片。
 *
 * 这里有一个刻意的分工：插件只交出 [UiNode] 这棵**数据树**，
 * 真正画像素的是下面这些 Compose 代码。于是：
 *
 *  - 插件画不出宿主不认识的东西（它交上来的树会被逐节点校验）
 *  - 插件崩了只影响这张卡片（求值在协程里，异常落成一句提示）
 *  - 主题、字号、间距由宿主说了算，插件生态不会长成一片拼贴画
 */
@Composable
fun DynamicSlotView(
    slot: DynamicSlot,
    refreshKey: Any?,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
    owner: PluginId? = null,
) {
    var root by remember(slot.id) { mutableStateOf<UiNode?>(null) }
    var failure by remember(slot.id) { mutableStateOf("") }

    LaunchedEffect(slot.id, refreshKey) {
        try {
            root = slot.build(currentDataContext())
            failure = ""
        } catch (t: Throwable) {
            failure = t.message ?: "未知原因"
        }
    }

    val current = root
    when {
        failure.isNotEmpty() -> Text(
            "这张卡片画不出来：" + failure,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        current == null -> Text("正在准备…", style = MaterialTheme.typography.bodySmall)
        else -> CompositionLocalProvider(LocalSlotOwner provides owner) {
            UiNodeView(current, slot, onAction)
        }
    }
}

@Composable
private fun UiNodeView(
    node: UiNode,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    when (node) {
        is UiNode.Column -> Column(
            verticalArrangement = Arrangement.spacedBy(snapGap(node.gap)),
            horizontalAlignment = when (node.align) {
                UiAlign.Start -> Alignment.Start
                UiAlign.Center -> Alignment.CenterHorizontally
                UiAlign.End -> Alignment.End
            },
        ) {
            for (child in node.children) UiNodeView(child, slot, onAction)
        }

        is UiNode.Row -> Row(
            horizontalArrangement = Arrangement.spacedBy(snapGap(node.gap)),
            verticalAlignment = when (node.align) {
                UiAlign.Start -> Alignment.Top
                UiAlign.Center -> Alignment.CenterVertically
                UiAlign.End -> Alignment.Bottom
            },
        ) {
            for (child in node.children) UiNodeView(child, slot, onAction)
        }

        is UiNode.Section -> {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (node.title.isNotEmpty()) {
                    Text(
                        node.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (child in node.children) UiNodeView(child, slot, onAction)
            }
        }

        is UiNode.Grid -> GridView(node, slot, onAction)

        // Cell 只在 Grid 里有意义；跑到别处就当透明容器。
        is UiNode.Cell -> UiNodeView(node.child, slot, onAction)

        is UiNode.Text -> TextNode(node)

        is UiNode.Divider -> Box(
            Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )

        is UiNode.Notice -> Text(
            node.text,
            style = MaterialTheme.typography.bodySmall,
            color = when (node.tone) {
                UiNoticeTone.Hint -> MaterialTheme.colorScheme.onSurfaceVariant
                UiNoticeTone.Warning -> LocalExtraColors.current.warning
                UiNoticeTone.Problem -> MaterialTheme.colorScheme.error
            },
        )

        is UiNode.Stat -> StatView(node)
        is UiNode.Badge -> BadgeView(node)
        is UiNode.Progress -> ProgressView(node)
        is UiNode.Bars -> BarsView(node)
        is UiNode.Sparkline -> SparklineView(node)
        is UiNode.Columns -> ColumnsView(node)
        is UiNode.Area -> AreaView(node)
        is UiNode.Ring -> RingView(node)
        is UiNode.DotMatrix -> DotMatrixView(node)
        is UiNode.Table -> TableView(node)
        is UiNode.HeatStrip -> HeatStripView(node)
        is UiNode.Icon -> Text(node.glyph, fontSize = node.size.sp)
        is UiNode.Image -> ImageView(node)
        is UiNode.Spacer -> Spacer(Modifier.height(node.height.dp))
        is UiNode.Button -> DynamicButton(node, slot, onAction)
        is UiNode.Input -> DynamicInput(node, slot)
        is UiNode.Stepper -> DynamicStepper(node, slot)
        is UiNode.ListRow -> DynamicListRow(node, slot, onAction)
        is UiNode.Switch -> DynamicSwitch(node, slot, onAction)
        is UiNode.Chip -> DynamicChip(node, slot, onAction)
    }
}

/** 一个网格项：真正要画的节点 + 占几栏。 */
private data class GridItem(val node: UiNode, val span: Int)

/**
 * 把网格子节点排成行。
 *
 * 子节点既可以包一层 [UiNode.Cell] 指定 span，也可以直接给（默认占一栏）。
 * 一行放不下就先收行 —— 不做「拉伸补洞」，因为补洞会让作者算不清实际长什么样。
 */
private fun gridRows(children: List<UiNode>, columns: Int): List<List<GridItem>> {
    val cols = if (columns <= 0) 1 else columns
    val items = children.map { child ->
        if (child is UiNode.Cell) {
            GridItem(child.child, child.span.coerceIn(1, cols))
        } else {
            GridItem(child, 1)
        }
    }
    val rows = mutableListOf<List<GridItem>>()
    var row = mutableListOf<GridItem>()
    var used = 0
    for (item in items) {
        if (used + item.span > cols && row.isNotEmpty()) {
            rows += row
            row = mutableListOf()
            used = 0
        }
        row += item
        used += item.span
    }
    if (row.isNotEmpty()) rows += row
    return rows
}

@Composable
private fun GridView(
    node: UiNode.Grid,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in gridRows(node.children, node.columns)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                for (item in row) {
                    Box(Modifier.weight(item.span.toFloat())) {
                        UiNodeView(item.node, slot, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextNode(node: UiNode.Text) {
    val fonts = LocalAppFonts.current
    val typography = MaterialTheme.typography
    val base = when (node.style) {
        UiTextStyle.Title -> typography.titleMedium
        UiTextStyle.Body -> typography.bodyMedium
        UiTextStyle.Caption -> typography.bodySmall
    }
    val sized = when (node.size) {
        UiTextSize.Small -> typography.bodySmall
        UiTextSize.Normal -> base
        UiTextSize.Large -> typography.titleMedium
        UiTextSize.Huge -> typography.headlineSmall
    }
    Text(
        node.text,
        style = sized,
        color = toneColor(node.tone),
        fontWeight = when (node.weight) {
            UiTextWeight.Normal -> null
            UiTextWeight.Medium -> FontWeight.Medium
            UiTextWeight.Bold -> FontWeight.Bold
        },
        fontFamily = when (node.font) {
            UiFont.Default -> null
            UiFont.Mono -> fonts.mono
            UiFont.Serif -> fonts.serif
            UiFont.Condensed -> fonts.condensed
        },
        maxLines = node.maxLines ?: Int.MAX_VALUE,
        overflow = if (node.maxLines != null) TextOverflow.Ellipsis else TextOverflow.Clip,
    )
}

/** 大数字 + 小标签：看板里最常用的一格。 */
@Composable
private fun StatView(node: UiNode.Stat) {
    val fonts = LocalAppFonts.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            node.label,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = fonts.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            node.value,
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = fonts.condensed,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BadgeView(node: UiNode.Badge) {
    val color = toneColor(node.tone)
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(node.text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun ProgressView(node: UiNode.Progress) {
    val max = if (node.max <= 0) 1 else node.max
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinearProgressIndicator(
            progress = { (node.value.toFloat() / max).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BarsView(node: UiNode.Bars) {
    val fonts = LocalAppFonts.current
    val extra = LocalExtraColors.current
    val max = (node.max ?: node.items.maxOfOrNull { it.value })?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (item in node.items) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(72.dp),
                )
                val ratio = (item.value.toFloat() / max).coerceIn(0f, 1f)
                Box(Modifier.weight(1f).height(8.dp), contentAlignment = Alignment.CenterStart) {
                    if (item.value <= 0) {
                        // 0 值不铺满灰轨道：一条满宽底会被读成「满格」，这是最常见的图表误读。
                        Box(
                            Modifier
                                .width(10.dp)
                                .height(2.dp)
                                .background(extra.dataTrack, RoundedCornerShape(1.dp)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(extra.dataTrack),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(ratio)
                                    .fillMaxHeight()
                                    // 满格 = 完成：用青柠底（success）而不是数据靛，颜色本身就是结论。
                                    .background(if (ratio >= 1f) extra.success else extra.data, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
                Text(
                    item.value.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = fonts.mono,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
    }
}

@Composable
private fun SparklineView(node: UiNode.Sparkline) {
    val line = LocalExtraColors.current.data
    val fonts = LocalAppFonts.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val values = node.values
            if (values.size < 2) return@Canvas
            val low = node.min
            val high = if (node.max > low) node.max else low + 1
            val stepX = size.width / (values.size - 1).toFloat()
            fun yOf(value: Int): Float {
                val ratio = ((value - low).toFloat() / (high - low)).coerceIn(0f, 1f)
                return size.height - ratio * size.height
            }
            var previous = Offset(0f, yOf(values[0]))
            for (index in 1 until values.size) {
                val next = Offset(stepX * index, yOf(values[index]))
                drawLine(line, previous, next, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                previous = next
            }
            drawCircle(line, radius = 2.5.dp.toPx(), center = previous)
        }
    }
}

@Composable
private fun ColumnsView(node: UiNode.Columns) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val fonts = LocalAppFonts.current
    val max = (node.max ?: node.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fonts.mono,
                color = colors.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (value in node.values) {
                val ratio = (value.toFloat() / max).coerceIn(0f, 1f)
                Box(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .fillMaxHeight(ratio.coerceAtLeast(0.02f))
                            .clip(RoundedCornerShape(topStart = 1.dp, topEnd = 1.dp))
                            .background(extra.data),
                    )
                }
            }
        }
        if (node.labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (label in node.labels) {
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun AreaView(node: UiNode.Area) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val fonts = LocalAppFonts.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fonts.mono,
                color = colors.onSurfaceVariant,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val values = node.values
            if (values.size < 2) return@Canvas
            val low = node.min
            val high = if (node.max > low) node.max else low + 1
            val stepX = size.width / (values.size - 1).toFloat()
            fun yOf(value: Int): Float {
                val ratio = ((value - low).toFloat() / (high - low)).coerceIn(0f, 1f)
                return size.height - 2f - ratio * (size.height - 6f)
            }

            val fill = Path().apply {
                moveTo(0f, size.height)
                values.forEachIndexed { index, value -> lineTo(stepX * index, yOf(value)) }
                lineTo(size.width, size.height)
                close()
            }
            drawPath(fill, color = extra.data.copy(alpha = 0.10f))

            val line = Path()
            values.forEachIndexed { index, value ->
                if (index == 0) line.moveTo(0f, yOf(value)) else line.lineTo(stepX * index, yOf(value))
            }
            drawPath(line, color = extra.data, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            drawLine(
                color = extra.dataTrack,
                start = Offset(0f, size.height - 1f),
                end = Offset(size.width, size.height - 1f),
                strokeWidth = 1.dp.toPx(),
            )
        }
    }
}

@Composable
private fun DotMatrixView(node: UiNode.DotMatrix) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val fonts = LocalAppFonts.current
    val glyphs = node.value.filter { it.isDigit() || it == ':' || it == '-' || it == ' ' }.take(6)
    if (glyphs.isEmpty()) return
    val cell = 5.dp
    val gap = 2.dp
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fonts.mono,
                color = colors.onSurfaceVariant,
            )
        }
        for (row in 0 until 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (glyph in glyphs) {
                    val bits = dotGlyph(glyph)[row]
                    for (column in 0 until 5) {
                        val on = bits[column] == '1'
                        Canvas(Modifier.size(cell)) {
                            drawCircle(
                                color = if (on) extra.data else extra.dataTrack.copy(alpha = 0.5f),
                                radius = if (on) size.minDimension / 2f else size.minDimension / 2.6f,
                            )
                        }
                    }
                    Spacer(Modifier.width(gap))
                }
            }
        }
    }
}

/**
 * 5×7 点阵字形。只保留看板需要的字符：数字、冒号、连字符、空格。
 * 别的字符会变成空格 —— 宁可空着，也不拿宿主字体「凑一个图形」。
 */
private fun dotGlyph(glyph: Char): List<String> = when (glyph) {
    '0' -> listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110")
    '1' -> listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110")
    '2' -> listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111")
    '3' -> listOf("11111", "00010", "00100", "00010", "00001", "10001", "01110")
    '4' -> listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010")
    '5' -> listOf("11111", "10000", "11110", "00001", "00001", "10001", "01110")
    '6' -> listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110")
    '7' -> listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000")
    '8' -> listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110")
    '9' -> listOf("01110", "10001", "10001", "01111", "00001", "00010", "01100")
    ':' -> listOf("00000", "00100", "00100", "00000", "00100", "00100", "00000")
    '-' -> listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000")
    else -> listOf("00000", "00000", "00000", "00000", "00000", "00000", "00000")
}

@Composable
private fun RingView(node: UiNode.Ring) {
    val max = if (node.max <= 0) 1 else node.max
    val fraction = (node.value.toFloat() / max).coerceIn(0f, 1f)
    val extra = LocalExtraColors.current
    val track = extra.dataTrack
    // 满环 = 完成：用青柠（success）；没满才是数据靛，颜色本身说明状态。
    val line = if (fraction >= 0.999f) extra.success else extra.data
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(56.dp)) {
                val stroke = 6.dp.toPx()
                val inset = stroke / 2
                drawArc(
                    color = track,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = line,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Text(
                node.value.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (node.label.isNotEmpty()) {
            Text(
                node.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 明细表：只画文字和分隔线，不画单元格边框 —— 边界由宿主统一决定。 */
@Composable
private fun TableView(node: UiNode.Table) {
    val colors = MaterialTheme.colorScheme
    val fonts = LocalAppFonts.current

    fun numericCell(cell: String): Boolean =
        cell.matches(Regex("^[0-9][0-9.,:/%+\\-]*$"))

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (node.caption.isNotEmpty()) {
            Text(
                node.caption,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = fonts.mono,
                letterSpacing = 0.6.sp,
                color = colors.onSurfaceVariant,
            )
        }
        if (node.headers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for (header in node.headers) {
                    Text(
                        header,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = fonts.mono,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant))
        }
        for ((index, row) in node.rows.withIndex()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (index % 2 == 1) colors.surfaceVariant.copy(alpha = 0.45f) else Color.Transparent,
                    )
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                for ((cellIndex, cell) in row.withIndex()) {
                    val numeric = numericCell(cell)
                    Text(
                        cell,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (numeric) fonts.mono else FontFamily.Default,
                        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                        color = if (cellIndex == 0) colors.onSurface else colors.onSurfaceVariant,
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant.copy(alpha = 0.4f)))
        }
    }
}

/**
 * 打卡纸式的一排方格：0 是空格（1dp 虚线感的描边），其余按 max 比例加深。
 *
 * 颜色只用主题色（primary + alpha），所以换主题、暗色都自动跟随；
 * 格子尺寸也由宿主定，插件只交「每天多强」这一层数据。
 */
@Composable
private fun HeatStripView(node: UiNode.HeatStrip) {
    val colors = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val max = node.max.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (value in node.values) {
                val ratio = (value.toFloat() / max).coerceIn(0f, 1f)
                val base = Modifier.size(14.dp).clip(RoundedCornerShape(2.dp))
                Box(
                    if (value <= 0) {
                        base.border(1.dp, extra.dataTrack, RoundedCornerShape(2.dp))
                    } else {
                        base.background(extra.data.copy(alpha = 0.25f + 0.75f * ratio))
                    },
                )
            }
        }
        if (node.labels.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (label in node.labels) {
                    Text(
                        label,
                        modifier = Modifier.width(14.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageView(node: UiNode.Image) {
    val owner = LocalSlotOwner.current
    val bitmap = LocalPluginImage.current(owner, node.ref)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = node.alt.ifBlank { null },
            modifier = Modifier.fillMaxWidth().height(node.height.dp),
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            node.alt.ifBlank { "（图片加载不出来）" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DynamicButton(
    node: UiNode.Button,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    val scope = rememberCoroutineScope()
    val owner = LocalSlotOwner.current
    var feedback by remember(slot.id, node.label) { mutableStateOf("") }
    var running by remember(slot.id, node.label) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            enabled = !running,
            onClick = {
                running = true
                feedback = ""
                scope.launch {
                    feedback = try {
                        when (val tap = node.onTap) {
                            is UiTap.Plugin -> slot.perform(tap.handler, tap.args).message
                            is UiTap.Host -> {
                                val action = SlotAction(label = node.label, call = tap.call)
                                onAction?.invoke(owner, action)?.message
                                    ?: "这个按钮现在点不了"
                            }
                        }
                    } catch (t: Throwable) {
                        t.message ?: "点击失败"
                    } finally {
                        running = false
                    }
                }
            },
        ) {
            Text(node.label)
        }
        if (feedback.isNotEmpty()) {
            Text(feedback, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 浮窗/常驻两种输入框提交时交给插件处理器的参数。
 *
 * 用户打的字永远放在 `value` 里；插件在按钮上写的 `args` 一起带上（用来点名「哪一行」）。
 * 抽成函数是为了只有一条提交路径：两种形态都走它，谁也别再自己拼一遍参数。
 */
fun inputSubmitArgs(node: UiNode.Input, value: String): Map<String, String> {
    val extra = when (val tap = node.onSubmit) {
        is UiTap.Plugin -> tap.args
        is UiTap.Host -> emptyMap()
    }
    return extra + mapOf("value" to value)
}

@Composable
private fun DynamicInput(node: UiNode.Input, slot: DynamicSlot) {
    if (node.popup) DynamicPopupInput(node, slot) else DynamicInlineInput(node, slot)
}

/**
 * 浮窗版输入框（默认形态）：卡片里只有一行可点的字。
 *
 * 为什么要这样：卡片是看数据的地方，一行行输入框会把卡片撑成表单，
 * 提示语还容易和标题重复。收成一个入口，点开才在浮窗里填，提交后浮窗关掉。
 */
@Composable
private fun DynamicPopupInput(node: UiNode.Input, slot: DynamicSlot) {
    val scope = rememberCoroutineScope()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var open by remember(slot.id, node.hint) { mutableStateOf(false) }
    var feedback by remember(slot.id, node.hint) { mutableStateOf("") }
    var running by remember(slot.id, node.hint) { mutableStateOf(false) }
    val submitLabel = node.submitLabel.ifBlank { "提交" }

    // 注意：输入的文字在浮窗里，由浮窗作为参数交进来（[PluginInputDialog] 的 onSubmit）。
    // 不要在这里另存一份 text —— 两份状态必然不同步，用户打的字就会「进不去」。
    fun submit(value: String) {
        if (running) return
        running = true
        scope.launch {
            feedback = try {
                when (val tap = node.onSubmit) {
                    is UiTap.Plugin -> slot.perform(tap.handler, inputSubmitArgs(node, value)).message
                    is UiTap.Host -> "这个输入框只能交给插件处理"
                }
            } catch (t: Throwable) {
                t.message ?: "提交失败"
            } finally {
                running = false
            }
            open = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            submitLabel,
            style = MaterialTheme.typography.labelLarge,
            color = if (running) muted.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = !running) { open = true },
        )
        if (feedback.isNotEmpty()) {
            Text(feedback, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }

    if (open) {
        PluginInputDialog(
            hint = node.hint,
            submitLabel = submitLabel,
            running = running,
            onDismissRequest = { open = false },
            onSubmit = { value -> submit(value) },
        )
    }
}

/** 常驻版输入框（`popup:false`）：一行文字 + 底线 + 右侧「↵ 提交」。 */
@Composable
private fun DynamicInlineInput(node: UiNode.Input, slot: DynamicSlot) {
    val scope = rememberCoroutineScope()
    var field by remember(slot.id, node.hint) { mutableStateOf(TextFieldValue("")) }
    var feedback by remember(slot.id, node.hint) { mutableStateOf("") }
    var running by remember(slot.id, node.hint) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // 占位文字只做提示，不是正文：压到 0.65 的次级墨色，别再抢视线。
    val placeholder = muted.copy(alpha = 0.65f)
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    val submitLabel = node.submitLabel.ifBlank { "提交" }

    fun submit() {
        if (running) return
        running = true
        feedback = ""
        val value = field.text
        scope.launch {
            feedback = try {
                when (val tap = node.onSubmit) {
                    is UiTap.Plugin -> slot.perform(tap.handler, inputSubmitArgs(node, value)).message
                    is UiTap.Host -> "这个输入框只能交给插件处理"
                }
            } catch (t: Throwable) {
                t.message ?: "提交失败"
            } finally {
                running = false
            }
            field = TextFieldValue("")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { event ->
                        // 输入法组合中的 Enter（选候选）不算提交；组合结束才认。
                        val composing = field.composition != null
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !composing) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) {
                        if (field.text.isEmpty()) {
                            Text(
                                node.hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = placeholder,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "↵ $submitLabel",
                style = MaterialTheme.typography.labelMedium,
                color = if (running) muted.copy(alpha = 0.4f) else muted,
                modifier = Modifier.clickable(enabled = !running) { submit() },
            )
        }
        HorizontalDivider(color = line)
        if (feedback.isNotEmpty()) {
            Text(feedback, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

@Composable
private fun DynamicStepper(node: UiNode.Stepper, slot: DynamicSlot) {
    val scope = rememberCoroutineScope()
    var feedback by remember(slot.id, node.label) { mutableStateOf("") }
    var busy by remember(slot.id, node.label) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    fun step(delta: Int) {
        if (busy) return
        busy = true
        scope.launch {
            feedback = try {
                when (val tap = node.onStep) {
                    is UiTap.Plugin ->
                        slot.perform(tap.handler, tap.args + mapOf("delta" to delta.toString())).message
                    is UiTap.Host -> "计步器只能交给插件处理"
                }
            } catch (t: Throwable) {
                t.message ?: "没记上"
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (node.label.isNotEmpty()) {
                Text(node.label, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.weight(1f))
            StepGlyph("−", enabled = !busy) { step(-node.step) }
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    node.value.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = LocalAppFonts.current.condensed,
                    fontWeight = FontWeight.Bold,
                )
                if (node.unit.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(node.unit, style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            StepGlyph("+", enabled = !busy) { step(node.step) }
        }
        if (feedback.isNotEmpty()) {
            Text(feedback, style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/** 计步器的加减按钮：28dp 方块，1dp 边，不用 Material 的圆形 IconButton。 */
@Composable
private fun StepGlyph(glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 语义语气 -> 主题色。换主题、暗色模式都跟着变，插件不用改。 */
@Composable
private fun toneColor(tone: UiTone): Color = when (tone) {
    UiTone.Default -> MaterialTheme.colorScheme.onSurface
    UiTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    UiTone.Accent -> MaterialTheme.colorScheme.primary
    UiTone.Danger -> MaterialTheme.colorScheme.error
    UiTone.Success -> MaterialTheme.colorScheme.tertiary
}

/**
 * 把插件写的任意 `gap` 取整到宿主 token 的间距档（4/8/12/16/24）。
 *
 * 词表以前写的是「gap 0..32」，结果每张卡都长出自己的间距节奏；宿主只认自己的刻度，
 * 旧插件里的 6/10/18 落到最近一档，视觉上是轻微变化，不会破版。
 */
private fun snapGap(gap: Int): Dp = when {
    gap <= 4 -> 4.dp
    gap <= 8 -> 8.dp
    gap <= 12 -> 12.dp
    gap <= 16 -> 16.dp
    else -> 24.dp
}

/**
 * 列表行（批 6）：标题/副标题/右值三段，行高、内边距与宿主自营清单一致。
 * 有 `onTap` 时整行可点。
 */
@Composable
private fun DynamicListRow(
    node: UiNode.ListRow,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    val scope = rememberCoroutineScope()
    val owner = LocalSlotOwner.current
    val tap = node.onTap
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (tap != null) {
                    Modifier.clickable { scope.launch { runTap(tap, node.title, slot, onAction, owner) } }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(node.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = node.subtitle
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val trailing = node.trailing
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 开关（批 6）：与宿主设置页同一个 M3 开关，不自己画。 */
@Composable
private fun DynamicSwitch(
    node: UiNode.Switch,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    val scope = rememberCoroutineScope()
    val owner = LocalSlotOwner.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(node.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = node.checked,
            onCheckedChange = { scope.launch { runTap(node.onTap, node.label, slot, onAction, owner) } },
        )
    }
}

/** 选择片（批 6）：选中用主色浅底，未选中描边，和宿主 chip 同一套。 */
@Composable
private fun DynamicChip(
    node: UiNode.Chip,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    val scope = rememberCoroutineScope()
    val owner = LocalSlotOwner.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (node.selected) colors.primaryContainer else Color.Transparent)
            .border(1.dp, if (node.selected) Color.Transparent else colors.outlineVariant, shape)
            .then(
                if (node.onTap != null) {
                    Modifier.clickable {
                        node.onTap?.let { tap -> scope.launch { runTap(tap, node.label, slot, onAction, owner) } }
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            node.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (node.selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 把一次点击交回插件/宿主，和按钮共用同一套返回值处理。 */
private suspend fun runTap(
    tap: UiTap,
    label: String,
    slot: DynamicSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
    owner: PluginId?,
): String = try {
    when (tap) {
        is UiTap.Plugin -> slot.perform(tap.handler, tap.args).message
        is UiTap.Host -> onAction?.invoke(owner, SlotAction(label = label, call = tap.call))?.message
            ?: "这个操作现在点不了"
    }
} catch (t: Throwable) {
    t.message ?: "操作失败"
}
