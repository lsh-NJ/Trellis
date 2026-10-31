package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * 编辑器的目录侧栏：从 Markdown 标题长出来的一棵树。
 *
 * 三件事分开做，谁也不绑定谁：
 *
 * - [parseOutline] 只认标题（`#` ~ `######`，围栏代码块里的不算），给出字符偏移；
 * - [flattenOutline] 把树按「哪些节点被折叠了」拍平成可见行，渲染只认这个列表；
 * - [MarkdownOutlinePanel] 只画行、把点击回调出去；跳转由编辑器自己决定（编辑态挪光标，预览态滚动）。
 *
 * 宽度/显隐放在 [MarkdownSidebarState]：切到别的页再回来，用户的宽度和折叠状态还在。
 */

internal data class OutlineItem(
    val level: Int,
    val text: String,
    /** 标题那一行在正文里的字符偏移，跳转靠它。 */
    val offset: Int,
    val lineIndex: Int,
    /** 第几个标题（0 开始，按文档顺序）；预览态用它换算成渲染块下标。 */
    val ordinal: Int,
    val children: List<OutlineItem> = emptyList(),
)

private data class FlatHeading(
    val level: Int,
    val text: String,
    val offset: Int,
    val lineIndex: Int,
    val ordinal: Int,
)

private val outlineHeadingPattern = Regex("^(#{1,6})\\s+(.+)$")

/**
 * 把正文解析成标题树。
 *
 * 只有 `# 标题`（`#` 后面必须有空格和非空文字）才算标题——和 [parseMarkdown] 保持同一套规则，
 * 否则目录里的第 N 条和预览里的第 N 个标题会对不上，跳转会错位。
 */
internal fun parseOutline(body: String): List<OutlineItem> {
    val flat = mutableListOf<FlatHeading>()
    var inFence = false
    var offset = 0
    var ordinal = 0
    val lines = body.split('\n')
    for (lineIndex in lines.indices) {
        val raw = lines[lineIndex]
        val line = raw.removeSuffix("\r")
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
        } else if (!inFence) {
            val match = outlineHeadingPattern.matchEntire(line.trimEnd())
            if (match != null) {
                val text = match.groupValues[2].trim()
                if (text.isNotEmpty()) {
                    flat += FlatHeading(
                        level = match.groupValues[1].length,
                        text = text,
                        offset = offset,
                        lineIndex = lineIndex,
                        ordinal = ordinal,
                    )
                    ordinal++
                }
            }
        }
        offset += raw.length + 1
    }

    // 按层级挂树：找最近的、层级更小的前驱当父节点；跳级（# 直接到 ###）也归到最近的上级。
    fun build(index: Int, parentLevel: Int): Pair<List<OutlineItem>, Int> {
        val nodes = mutableListOf<OutlineItem>()
        var cursor = index
        while (cursor < flat.size && flat[cursor].level > parentLevel) {
            val head = flat[cursor]
            val (children, next) = build(cursor + 1, head.level)
            nodes += OutlineItem(
                level = head.level,
                text = head.text,
                offset = head.offset,
                lineIndex = head.lineIndex,
                ordinal = head.ordinal,
                children = children,
            )
            cursor = next
        }
        return nodes to cursor
    }

    return build(0, 0).first
}

/** 拍平后的一行：深度决定缩进，[hasChildren] 决定有没有折叠箭头。 */
internal data class OutlineRow(
    val item: OutlineItem,
    val depth: Int,
    val hasChildren: Boolean,
    val expanded: Boolean,
)

internal fun flattenOutline(roots: List<OutlineItem>, collapsed: Set<Int>): List<OutlineRow> {
    val rows = mutableListOf<OutlineRow>()
    fun walk(nodes: List<OutlineItem>, depth: Int) {
        for (node in nodes) {
            val expanded = node.children.isEmpty() || node.offset !in collapsed
            rows += OutlineRow(
                item = node,
                depth = depth,
                hasChildren = node.children.isNotEmpty(),
                expanded = expanded,
            )
            if (expanded && node.children.isNotEmpty()) walk(node.children, depth + 1)
        }
    }
    walk(roots, 0)
    return rows
}

/**
 * 侧栏宽度与显隐。
 *
 * 做成模块级状态而不是 `remember`：`KnowledgeScreen` 切走时整个组合会被丢掉，
 * 每次都回到默认 240dp 会很烦。这里只记界面偏好，不涉及业务数据。
 */
internal object MarkdownSidebarState {
    val minWidth: Dp = 180.dp
    val maxWidth: Dp = 420.dp
    val defaultWidth: Dp = 240.dp

    var visible by mutableStateOf(true)
    var width: Dp by mutableStateOf(defaultWidth)

    /** 拖拽时调用；左右边界在这里兜住，外面不用再夹一次。 */
    fun resize(delta: Dp) {
        width = (width + delta).coerceIn(minWidth, maxWidth)
    }

    fun resetWidth() {
        width = defaultWidth
    }
}

/** 目录面板：标题行（折叠全部/展开全部/收起）+ 标题树。 */
@Composable
internal fun MarkdownOutlinePanel(
    roots: List<OutlineItem>,
    collapsed: Set<Int>,
    modifier: Modifier = Modifier,
    onToggle: (OutlineItem) -> Unit = {},
    onCollapseAll: () -> Unit = {},
    onExpandAll: () -> Unit = {},
    onJump: (OutlineItem) -> Unit = {},
    onHide: () -> Unit = {},
) {
    val rows = remember(roots, collapsed) { flattenOutline(roots, collapsed) }
    Column(modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "目录",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                rows.size.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Spacer(Modifier.weight(1f))
            OutlineIconAction("全部收起", onCollapseAll)
            OutlineIconAction("全部展开", onExpandAll)
            OutlineIconAction("×", onHide)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (roots.isEmpty()) {
            Text(
                "正文里还没有标题。\n用 # 写一个小标题，这里就会出现目录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 6.dp,
                    end = 6.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            ) {
                items(rows, key = { it.item.offset }) { row -> OutlineRowView(row, onToggle, onJump) }
            }
        }
    }
}

@Composable
private fun OutlineRowView(
    row: OutlineRow,
    onToggle: (OutlineItem) -> Unit,
    onJump: (OutlineItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .clickable { onJump(row.item) }
            .padding(start = (4 + row.depth * 12).dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(ControlShape)
                .clickable(enabled = row.hasChildren) { onToggle(row.item) },
            contentAlignment = Alignment.Center,
        ) {
            if (row.hasChildren) {
                Text(
                    if (row.expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            // 目录里也要把 `**加粗**` 这类行内标记渲染掉：标题本来就是 Markdown，
            // 目录是它的投影，不该再显示原始符号。
            inlineMarkdown(row.item.text),
            style = when (row.item.level) {
                1 -> MaterialTheme.typography.bodyMedium
                2 -> MaterialTheme.typography.bodySmall
                else -> MaterialTheme.typography.labelSmall
            },
            fontWeight = if (row.item.level == 1) FontWeight.SemiBold else FontWeight.Normal,
            color = if (row.item.level <= 2) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun OutlineIconAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(ControlShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    )
}

/** 编辑器与目录之间的拖拽条：左右拖改宽度，宽度上下限在 [MarkdownSidebarState] 里。 */
@Composable
internal fun OutlineSplitter(
    onDrag: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    var active by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(8.dp)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectDragGestures(
                    onDragStart = { active = true },
                    onDragEnd = { active = false },
                    onDragCancel = { active = false },
                ) { change, drag ->
                    change.consume()
                    onDrag(with(density) { drag.x.toDp() })
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
