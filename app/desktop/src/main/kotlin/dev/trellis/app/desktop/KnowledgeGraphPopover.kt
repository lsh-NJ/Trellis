package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalExtraColors
import kotlin.math.roundToInt

/**
 * 聚焦节点的小浮窗（设计 v1.2：节点信息不画在画布上，全部收进这里）。
 *
 * 内容：所属路径 / 定义（可编辑）/ 来源片段（可点回笔记）/ 直接关联（前置·应用·相关·共现）/
 * 操作（概念：移动到分类；分类：改名、新建子分类、删除）。
 * 位置贴着节点右上角并自动避让边界；宽度固定 300dp，内容超高时内部滚动。
 */
@Composable
internal fun KnowledgeGraphPopover(
    graph: KnowledgeGraph,
    node: GraphNode,
    anchor: GraphPoint,
    viewport: GraphSize,
    sources: List<ConceptMention>,
    categories: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onSelectNode: (String) -> Unit,
    onLocate: (noteId: String, start: Int, end: Int) -> Unit,
    onSaveDefinition: (name: String, definition: String) -> Unit,
    onMoveToCategory: (conceptKey: String, categoryId: String?) -> Unit,
    onNewSubcategory: (parentId: String) -> Unit,
    onRenameCategory: (categoryId: String) -> Unit,
    onDeleteCategory: (categoryId: String) -> Unit,
) {
    val extra = LocalExtraColors.current
    val scheme = MaterialTheme.colorScheme
    val isCategory = node.kind == GraphNodeKind.CATEGORY
    val pathNodes = remember(graph, node.key) { graph.ancestorsOf(node.key) }
    val relations = remember(graph, node.key) {
        graph.relationsOf(node.key)
            .sortedWith(compareBy({ relationKindOrder(it.kind) }, { -it.confidence }))
            .mapNotNull { edge ->
                val otherKey = if (edge.fromKey == node.key) edge.toKey else edge.fromKey
                val other = graph.nodes[otherKey] ?: return@mapNotNull null
                PopoverRelation(
                    edge = edge,
                    otherKey = otherKey,
                    otherName = other.name,
                    outgoing = edge.fromKey == node.key,
                )
            }
    }
    val children = remember(graph, node.key) { graph.childrenOf(node.key) }

    // 位置：节点右上角 + 14dp；右侧放不下就翻到左侧；纵向夹在视口内。
    val popoverWidth = 300f
    val margin = 8f
    val estimatedHeight = 420f
    val targetX = anchor.x + 14f
    val x = if (targetX + popoverWidth > viewport.width - margin) {
        (anchor.x - popoverWidth - 14f).coerceAtLeast(margin)
    } else {
        targetX
    }
    val y = (anchor.y - 8f).coerceIn(margin, (viewport.height - estimatedHeight).coerceAtLeast(margin))

    Column(
        modifier = modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .width(popoverWidth.dp)
            .heightIn(max = estimatedHeight.dp)
            .background(scheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, extra.dataTrack, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PopoverHeader(isCategory, node, extra, scheme, onClose)
        PopoverPath(isCategory, pathNodes, node, extra, scheme)

        if (relations.isNotEmpty()) {
            Text("直接关联", fontSize = 10.sp, color = extra.dataMuted)
            relations.forEach { relation ->
                RelationRow(relation, extra, scheme, onSelectNode)
            }
        }

        if (isCategory) {
            CategoryBody(node, children, extra, scheme, onSelectNode, onRenameCategory, onNewSubcategory, onDeleteCategory)
        } else {
            ConceptBody(
                graph = graph,
                node = node,
                sources = sources,
                categories = categories,
                extra = extra,
                scheme = scheme,
                onLocate = onLocate,
                onSaveDefinition = onSaveDefinition,
                onMoveToCategory = onMoveToCategory,
            )
        }

    }
}

@Composable
private fun PopoverHeader(
    isCategory: Boolean,
    node: GraphNode,
    extra: ExtraColors,
    scheme: androidx.compose.material3.ColorScheme,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (isCategory) "分类" else "概念",
            fontSize = 10.sp,
            color = extra.dataMuted,
            modifier = Modifier
                .background(extra.dataTrack, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = node.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClose) { Text("×", fontSize = 16.sp) }
    }
}

@Composable
private fun PopoverPath(
    isCategory: Boolean,
    pathNodes: List<GraphNode>,
    node: GraphNode,
    extra: ExtraColors,
    scheme: androidx.compose.material3.ColorScheme,
) {
    Text(if (isCategory) "所属路径" else "所属分类", fontSize = 10.sp, color = extra.dataMuted)
    Text(
        text = when {
            isCategory -> (pathNodes + node).joinToString(" / ") { it.name }
            pathNodes.isEmpty() -> "未分类（点「移动到分类…」归档）"
            else -> pathNodes.joinToString(" / ") { it.name }
        },
        fontSize = 12.sp,
        color = scheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryBody(
    node: GraphNode,
    children: List<GraphNode>,
    extra: ExtraColors,
    scheme: androidx.compose.material3.ColorScheme,
    onSelectNode: (String) -> Unit,
    onRenameCategory: (String) -> Unit,
    onNewSubcategory: (String) -> Unit,
    onDeleteCategory: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        TextButton(onClick = { onRenameCategory(node.key) }) { Text("改名", fontSize = 12.sp) }
        TextButton(onClick = { onNewSubcategory(node.key) }) { Text("新建子分类", fontSize = 12.sp) }
        TextButton(onClick = { onDeleteCategory(node.key) }) { Text("删除", fontSize = 12.sp) }
    }
    if (children.isNotEmpty()) {
        Text("包含", fontSize = 10.sp, color = extra.dataMuted)
        children.take(10).forEach { child ->
            Text(
                text = (if (child.kind == GraphNodeKind.CATEGORY) "▸ " else "· ") + child.name,
                fontSize = 12.sp,
                color = scheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectNode(child.key) }
                    .padding(vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun ConceptBody(
    graph: KnowledgeGraph,
    node: GraphNode,
    sources: List<ConceptMention>,
    categories: List<Pair<String, String>>,
    extra: ExtraColors,
    scheme: androidx.compose.material3.ColorScheme,
    onLocate: (noteId: String, start: Int, end: Int) -> Unit,
    onSaveDefinition: (name: String, definition: String) -> Unit,
    onMoveToCategory: (conceptKey: String, categoryId: String?) -> Unit,
) {
    var editing by remember(node.key) { mutableStateOf(false) }
    var draft by remember(node.key, node.definition) { mutableStateOf(node.definition) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("定义", fontSize = 10.sp, color = extra.dataMuted, modifier = Modifier.weight(1f))
        if (!editing) {
            TextButton(onClick = {
                draft = graph.nodes[node.key]?.definition ?: ""
                editing = true
            }) { Text("编辑", fontSize = 11.sp) }
        }
    }
    if (editing) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { editing = false }) { Text("取消", fontSize = 11.sp) }
            TextButton(onClick = {
                editing = false
                onSaveDefinition(node.name, draft.trim())
            }) { Text("保存", fontSize = 11.sp) }
        }
    } else {
        Text(
            text = node.definition.ifBlank { "（还没有定义，AI 整理或手动补一句）" },
            fontSize = 12.sp,
            color = if (node.definition.isBlank()) extra.dataMuted else scheme.onSurface,
        )
    }

    Text("来源", fontSize = 10.sp, color = extra.dataMuted)
    if (sources.isEmpty()) {
        Text("（笔记里还没有可定位的片段）", fontSize = 12.sp, color = extra.dataMuted)
    } else {
        sources.take(3).forEach { mention ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLocate(mention.noteId, mention.start, mention.end) }
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    text = mention.noteTitle.ifBlank { mention.noteId },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = extra.accentText,
                )
                val snippet = cleanSnippet(mention.snippet)
                if (snippet.isNotEmpty()) {
                    Text(
                        text = if (snippet.length > 40) "${snippet.take(40)}…" else snippet,
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    var moveMenu by remember(node.key) { mutableStateOf(false) }
    Box {
        TextButton(onClick = { moveMenu = true }) { Text("移动到分类…", fontSize = 12.sp) }
        DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
            DropdownMenuItem(
                text = { Text("未分类", fontSize = 12.sp) },
                onClick = {
                    moveMenu = false
                    onMoveToCategory(node.key, null)
                },
            )
            categories.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 12.sp) },
                    onClick = {
                        moveMenu = false
                        onMoveToCategory(node.key, id)
                    },
                )
            }
        }
    }
}

/** 把正文片段里的 `[[…]]` / `[[原文 => 概念名]]` 标记去掉，浮窗里只显示人能直接读的文字。 */
private fun cleanSnippet(raw: String): String =
    raw.trim()
        .removePrefix("[[").removeSuffix("]]")
        .replace("[[", "").replace("]]", "")
        .replace(" => ", " → ")
        .trim()

@Composable
private fun RelationRow(
    relation: PopoverRelation,
    extra: ExtraColors,
    scheme: androidx.compose.material3.ColorScheme,
    onSelectNode: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectNode(relation.otherKey) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = relationKindLabel(relation.edge.kind, relation.outgoing),
            fontSize = 10.sp,
            color = relationKindColor(relation.edge.kind, extra),
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = relation.otherName,
            fontSize = 12.sp,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relationSourceLabel(relation.edge.source),
            fontSize = 9.sp,
            color = extra.dataMuted,
        )
    }
}

private data class PopoverRelation(
    val edge: GraphEdge,
    val otherKey: String,
    val otherName: String,
    val outgoing: Boolean,
)

private fun relationKindOrder(kind: GraphEdgeKind): Int = when (kind) {
    GraphEdgeKind.PREREQUISITE -> 0
    GraphEdgeKind.APPLIES -> 1
    GraphEdgeKind.RELATED -> 2
    GraphEdgeKind.CO_OCCUR -> 3
}

private fun relationKindLabel(kind: GraphEdgeKind, outgoing: Boolean): String = when (kind) {
    GraphEdgeKind.PREREQUISITE -> if (outgoing) "后置 →" else "← 前置"
    GraphEdgeKind.APPLIES -> if (outgoing) "用于 →" else "← 用于"
    GraphEdgeKind.RELATED -> "相关"
    GraphEdgeKind.CO_OCCUR -> "共现"
}

private fun relationKindColor(kind: GraphEdgeKind, extra: ExtraColors): Color = when (kind) {
    GraphEdgeKind.PREREQUISITE -> extra.data
    GraphEdgeKind.APPLIES -> extra.accentText
    GraphEdgeKind.RELATED -> extra.dataMuted
    GraphEdgeKind.CO_OCCUR -> extra.dataTrack
}

private fun relationSourceLabel(source: GraphEdgeSource): String = when (source) {
    GraphEdgeSource.USER -> "手动"
    GraphEdgeSource.EXPLICIT -> "标注"
    GraphEdgeSource.HEURISTIC -> "自动"
    GraphEdgeSource.AI -> "AI"
}
