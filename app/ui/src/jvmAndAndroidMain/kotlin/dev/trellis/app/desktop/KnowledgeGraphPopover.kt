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
 * 操作（概念：移动到分类；分类：改名、新建子分类、新建上级分类、移动到分类、删除）。
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
    onNewParentCategory: (categoryId: String) -> Unit,
    onMoveCategory: (categoryId: String, newParentId: String?) -> Unit,
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
            Text("直接关联", style = LocalTrellisTokens.current.type.caption, color = extra.dataMuted)
            relations.forEach { relation ->
                RelationRow(relation, extra, scheme, onSelectNode)
            }
        }

        if (isCategory) {
            CategoryBody(
                graph = graph,
                node = node,
                children = children,
                categories = categories,
                extra = extra,
                scheme = scheme,
                onSelectNode = onSelectNode,
                onRenameCategory = onRenameCategory,
                onNewSubcategory = onNewSubcategory,
                onNewParentCategory = onNewParentCategory,
                onMoveCategory = onMoveCategory,
                onDeleteCategory = onDeleteCategory,
            )
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
            style = LocalTrellisTokens.current.type.caption,
            color = extra.dataMuted,
            modifier = Modifier
                .background(extra.dataTrack, RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = node.name,
            style = LocalTrellisTokens.current.type.title,
            fontWeight = FontWeight.SemiBold,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        ChromeTextLink("×", onClick = onClose)
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
    Text(if (isCategory) "所属路径" else "所属分类", style = LocalTrellisTokens.current.type.caption, color = extra.dataMuted)
    Text(
        text = when {
            isCategory -> (pathNodes + node).joinToString(" / ") { it.name }
            pathNodes.isEmpty() -> "未分类（点「移动到分类…」归档）"
            else -> pathNodes.joinToString(" / ") { it.name }
        },
        style = LocalTrellisTokens.current.type.meta,
        color = scheme.onSurfaceVariant,
    )
}

@Composable
private fun CategoryBody(
    graph: KnowledgeGraph,
    node: GraphNode,
    children: List<GraphNode>,
    categories: List<Pair<String, String>>,
    extra: ExtraColors,
    scheme: androidx.compose.material3.ColorScheme,
    onSelectNode: (String) -> Unit,
    onRenameCategory: (String) -> Unit,
    onNewSubcategory: (String) -> Unit,
    onNewParentCategory: (String) -> Unit,
    onMoveCategory: (String, String?) -> Unit,
    onDeleteCategory: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ChromeTextLink("改名", onClick = { onRenameCategory(node.key) })
        ChromeTextLink("新建子分类", onClick = { onNewSubcategory(node.key) })
        ChromeTextLink("删除", onClick = { onDeleteCategory(node.key) }, danger = true)
    }
    val forbidden = remember(graph, node.key) { collectCategoryDescendants(graph, node.key) }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        ChromeTextLink("新建上级分类", onClick = { onNewParentCategory(node.key) })
        var moveMenu by remember(node.key) { mutableStateOf(false) }
        Box {
            ChromeTextLink("移动到分类…", onClick = { moveMenu = true })
            DropdownMenu(expanded = moveMenu, onDismissRequest = { moveMenu = false }) {
                DropdownMenuItem(
                    text = { Text("顶层（成为根分类）", fontSize = 12.sp) },
                    onClick = {
                        moveMenu = false
                        onMoveCategory(node.key, null)
                    },
                )
                // 不能挂到自己或自己的后代下（会成环）。
                categories
                    .filter { (id, _) -> id !in forbidden }
                    .forEach { (id, path) ->
                        DropdownMenuItem(
                            text = { Text(path, fontSize = 12.sp) },
                            onClick = {
                                moveMenu = false
                                onMoveCategory(node.key, id)
                            },
                        )
                    }
            }
        }
    }
    if (children.isNotEmpty()) {
        Text("包含", style = LocalTrellisTokens.current.type.caption, color = extra.dataMuted)
        children.take(10).forEach { child ->
            Text(
                text = (if (child.kind == GraphNodeKind.CATEGORY) "▸ " else "· ") + child.name,
                style = LocalTrellisTokens.current.type.meta,
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
        Text("定义", style = LocalTrellisTokens.current.type.caption, color = extra.dataMuted, modifier = Modifier.weight(1f))
        if (!editing) {
            ChromeTextLink("编辑", onClick = {
                draft = graph.nodes[node.key]?.definition ?: ""
                editing = true
            })
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
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            ChromeButton("取消", onClick = { editing = false }, filled = false)
            ChromeButton("保存", onClick = {
                editing = false
                onSaveDefinition(node.name, draft.trim())
            })
        }
    } else {
        Text(
            text = node.definition.ifBlank { "（还没有定义，AI 整理或手动补一句）" },
            style = LocalTrellisTokens.current.type.meta,
            color = if (node.definition.isBlank()) extra.dataMuted else scheme.onSurface,
        )
    }

    Text("来源", style = LocalTrellisTokens.current.type.caption, color = extra.dataMuted)
    if (sources.isEmpty()) {
        Text("（笔记里还没有可定位的片段）", style = LocalTrellisTokens.current.type.meta, color = extra.dataMuted)
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
                    style = LocalTrellisTokens.current.type.meta,
                    fontWeight = FontWeight.Medium,
                    color = extra.accentText,
                )
                val snippet = cleanSnippet(mention.snippet)
                if (snippet.isNotEmpty()) {
                    Text(
                        text = if (snippet.length > 40) "${snippet.take(40)}…" else snippet,
                        style = LocalTrellisTokens.current.type.caption,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    var moveMenu by remember(node.key) { mutableStateOf(false) }
    Box {
        ChromeTextLink("移动到分类…", onClick = { moveMenu = true })
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
            style = LocalTrellisTokens.current.type.caption,
            color = relationKindColor(relation.edge.kind, extra),
            modifier = Modifier.width(52.dp),
        )
        Text(
            text = relation.otherName,
            style = LocalTrellisTokens.current.type.meta,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = relationSourceLabel(relation.edge.source),
            style = LocalTrellisTokens.current.type.caption,
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

/** 分类自己 + 所有分类后代；用于「移动到分类…」时排除会成环的目标。 */
private fun collectCategoryDescendants(graph: KnowledgeGraph, key: String): Set<String> {
    val result = LinkedHashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(key)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        if (!result.add(current)) continue
        graph.childrenOf(current).forEach { child ->
            if (child.kind == GraphNodeKind.CATEGORY) queue.add(child.key)
        }
    }
    return result
}
