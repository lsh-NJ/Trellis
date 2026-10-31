package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * 列表侧栏的共用宽度。
 *
 * 对话页的「会话」和知识库页的「笔记 / 概念」是同一类东西（左侧竖列表），
 * 所以宽度也共用一份：在任一页拖出来的宽度，切到另一页还是这个宽度。
 * 上下限定死在 220–360dp：再窄标题就只剩省略号，再宽正文会被挤成一条。
 */
internal object ListSidebarState {
    val minWidth: Dp = 220.dp
    val maxWidth: Dp = 360.dp
    val defaultWidth: Dp = 264.dp

    var width: Dp by mutableStateOf(defaultWidth)

    fun resize(delta: Dp) {
        width = (width + delta).coerceIn(minWidth, maxWidth)
    }
}

/**
 * 两个页面共用的左侧列表栏骨架：标题 + 数量 + 一个轻量动作，下面是调用方自己的搜索框/分组/列表。
 *
 * 布局上它贴左边界（导航栏右侧），右侧挨着拖拽条，中间不再套页面留白——
 * 这是「左侧贴导航栏」那条需求的实际落点。
 */
@Composable
internal fun ListSidebar(
    title: String,
    count: Int,
    actionLabel: String,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actionEnabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .width(ListSidebarState.width)
            .fillMaxHeight()
            .padding(start = 14.dp, end = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = AppFonts.mono,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.weight(1f))
            if (onAction != null) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (actionEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    },
                    modifier = Modifier
                        .clip(ControlShape)
                        .clickable(enabled = actionEnabled, onClick = onAction)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(2.dp))
                trailing()
            }
        }
        content()
    }
}

/** 列表栏右侧的拖拽条：与目录侧栏的 OutlineSplitter 同一套手感。 */
@Composable
internal fun ListSidebarSplitter(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var active by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .width(8.dp)
            .fillMaxHeight()
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, drag ->
                    change.consume()
                    ListSidebarState.resize(with(density) { drag.x.toDp() })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (active) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(
                    if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    },
                ),
        )
    }
}

/**
 * 两种列表行共用的底：46dp 高、4dp 圆角、选中浅主色底、hover 淡底。
 * 选中态左侧的 2dp 主色竖条由 [ListRowSelectionBar] 画，行内布局各自保留。
 */
@Composable
internal fun Modifier.listRowSurface(selected: Boolean, hovered: Boolean): Modifier {
    val background = when {
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    return this
        .height(46.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(background)
}

/** 选中行左侧的 2dp 主色竖条（对话页会话行、知识库笔记/概念行共用）。 */
@Composable
internal fun ListRowSelectionBar(selected: Boolean) {
    if (selected) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
