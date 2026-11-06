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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.ui.LocalExtraColors

// ---------------------------------------------------------------------------
// 左栏：搜索 + 筛选 + 图例 + 统计 + 提示（建节点入口只在工具条，避免重复）
// ---------------------------------------------------------------------------

@Composable
internal fun KnowledgeGraphLeftPanel(
    graph: KnowledgeGraph,
    query: String,
    message: String?,
    showUnclassified: Boolean,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onNodeClick: (String) -> Unit,
    onShowUnclassified: (Boolean) -> Unit,
    aiEnabled: Boolean = false,
    aiBusy: Boolean = false,
    aiHint: String? = null,
    suggestionCount: Int = 0,
    onAiOrganize: () -> Unit = {},
    onOpenSuggestions: () -> Unit = {},
    onAdoptAll: () -> Unit = {},
    onCenterNode: (String) -> Unit = {},
) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    val results = remember(graph, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            emptyList()
        } else {
            graph.nodes.values
                .filter { it.name.contains(keyword, ignoreCase = true) }
                .sortedWith(compareBy({ if (it.kind == GraphNodeKind.CATEGORY) 0 else 1 }, { it.name }))
                .take(24)
        }
    }
    // 左栏下半部分：每棵树的根节点 + 「未分类」，点一下定位到那棵树。
    val roots = remember(graph) {
        graph.childrenOf(null).filter { it.kind == GraphNodeKind.CATEGORY }
    }
    val unclassified = remember(graph) {
        graph.childrenOf(null).filter { it.kind == GraphNodeKind.CONCEPT }
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (query.trim().isNotEmpty()) {
            if (results.isEmpty()) {
                KnowledgeHint("没有匹配的节点")
            } else {
                results.forEach { node ->
                    GraphHitRow(node = node, onClick = { onNodeClick(node.key) })
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示未分类", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(checked = showUnclassified, onCheckedChange = onShowUnclassified)
            }
            Text(
                "${graph.stats.conceptCount} 概念 · ${graph.stats.categoryCount} 分类 · ${graph.stats.unclassifiedCount} 未分类",
                style = tokens.type.caption,
                color = extra.dataMuted,
            )
            ChromeTextLink(
                if (aiBusy) "✦ AI 整理中…" else "✦ AI 整理分类",
                onClick = onAiOrganize,
                enabled = aiEnabled && !aiBusy,
            )
            if (suggestionCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ChromeTextLink(
                        "查看 AI 建议（$suggestionCount 条）",
                        onClick = onOpenSuggestions,
                    )
                    ChromeButton("一键采纳", onClick = onAdoptAll)
                }
            }
            if (!aiHint.isNullOrBlank()) {
                KnowledgeHint(aiHint)
            }
            if (roots.isNotEmpty() || unclassified.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text("分类树", style = tokens.type.meta, color = extra.dataMuted)
                roots.forEach { root ->
                    GraphHitRow(node = root, onClick = { onCenterNode(root.key) })
                }
                if (unclassified.isNotEmpty()) {
                    GraphHitRow(
                        node = GraphNode(
                            key = GRAPH_UNCLASSIFIED_KEY,
                            kind = GraphNodeKind.CATEGORY,
                            name = "未分类",
                            definition = "",
                            noteCount = unclassified.size,
                            mentionCount = unclassified.size,
                            firstSeenAt = 0L,
                            lastSeenAt = 0L,
                            frontmatterOnly = false,
                            parentKey = null,
                            origin = NodeOrigin.DERIVED,
                            description = null,
                            confirmed = true,
                            sortOrder = Int.MAX_VALUE,
                        ),
                        onClick = { onCenterNode(GRAPH_UNCLASSIFIED_KEY) },
                    )
                }
            }
        }

        if (!message.isNullOrBlank()) {
            Text(message, style = tokens.type.meta, color = extra.accentText)
        }
    }
}

@Composable
private fun LegendRow(solid: Boolean, label: String) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (solid) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (solid) extra.dataTrack else extra.dataMuted,
                    shape = RoundedCornerShape(3.dp),
                ),
        )
        Text(label, style = tokens.type.caption, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LegendLineRow(label: String) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(26.dp).height(1.5.dp).background(extra.dataTrack))
        Text(label, style = tokens.type.caption, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * 画布右上角的紧凑图例浮层：概念（实）/ 分类（虚）/ 父子连线。
 *
 * v1.3 起从「左栏一段竖排图例」移到这里——图例是看画布时才需要的说明，
 * 贴着画布右上角比塞在左栏更顺手，也把左栏让给搜索和统计。
 */
@Composable
internal fun KnowledgeGraphLegend(modifier: Modifier = Modifier) {
    val extra = LocalExtraColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
            .border(1.dp, extra.dataTrack.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LegendRow(solid = true, label = "概念")
        LegendRow(solid = false, label = "分类")
        LegendLineRow("归属")
    }
}

// ---------------------------------------------------------------------------
// 工具条
// ---------------------------------------------------------------------------

@Composable
internal fun KnowledgeGraphToolbar(
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onFit: () -> Unit,
    onNewVirtualNode: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        EditorChip(label = "展开全部", active = false, onClick = onExpandAll)
        EditorChip(label = "折叠全部", active = false, onClick = onCollapseAll)
        EditorChip(label = "适应窗口", active = false, onClick = onFit)
        EditorChip(label = "＋ 虚节点", active = false, onClick = onNewVirtualNode)
    }
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------

@Composable
internal fun KnowledgeGraphEmptyState(hasNotes: Boolean, hasConcepts: Boolean, message: String?) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("还没有知识网络", style = MaterialTheme.typography.titleMedium)
        Text(
            when {
                !hasNotes -> "先去「笔记」记一篇：正文里写下概念，AI 整理后会点亮知识点。"
                !hasConcepts -> "笔记里还没有被点亮的 concept：正文里写下概念名，或手动标注 [[概念]]。"
                else -> "概念已经就绪，点工具条「＋ 虚节点」搭出第一棵分类树，再把它拖到该在的位置。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!message.isNullOrBlank()) {
            Text(message, style = tokens.type.meta, color = extra.accentText)
        }
    }
}

// ---------------------------------------------------------------------------
// 小部件
// ---------------------------------------------------------------------------

@Composable
internal fun GraphSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalExtraColors.current.dataMuted,
    )
}

@Composable
internal fun GraphHitRow(node: GraphNode, onClick: () -> Unit) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (node.kind == GraphNodeKind.CATEGORY) extra.dataTrack else extra.accentText),
        )
        Text(
            node.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (node.origin == NodeOrigin.AI && !node.confirmed) {
            Text("AI", style = tokens.type.caption, color = extra.warning)
        }
    }
}

@Composable
internal fun KnowledgeHint(text: String) {
    Text(
        text,
        style = LocalTrellisTokens.current.type.meta,
        color = LocalExtraColors.current.dataMuted,
    )
}

@Composable
internal fun KnowledgeGraphHintCard(title: String, hint: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(hint, style = LocalTrellisTokens.current.type.meta, color = LocalExtraColors.current.dataMuted)
    }
}

/**
 * AI 建议面板（G1）：浮在画布右侧，逐条确认 / 忽略。
 *
 * 这是「建议」不是结果：面板上的操作才写 `graph.user.json`；忽略会写墓碑
 * （同一概念/路径或同一关系不再重复出现）。
 */
@Composable
internal fun KnowledgeGraphSuggestionPanel(
    pending: PendingGraphSuggestions,
    busy: Boolean,
    message: String?,
    modifier: Modifier = Modifier,
    // 停靠模式：贴在画布右侧一条通高栏（320dp），不再是个浮动小窗、也不再和聚焦浮窗抢位置。
    dock: Boolean = false,
    onClose: () -> Unit,
    onConfirmCategory: (PendingCategorySuggestion) -> Unit,
    onIgnoreCategory: (PendingCategorySuggestion) -> Unit,
    onConfirmRelation: (PendingRelationSuggestion) -> Unit,
    onReverseRelation: (PendingRelationSuggestion) -> Unit,
    onIgnoreRelation: (PendingRelationSuggestion) -> Unit,
    onConfirmAll: () -> Unit,
    onIgnoreAll: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(if (dock) 0.dp else 10.dp)
    Column(
        modifier = modifier
            .then(if (dock) Modifier.width(320.dp) else Modifier.width(392.dp).heightIn(max = 560.dp))
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, extra.dataTrack, shape)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "AI 建议 · ${pending.total} 条",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            ChromeTextLink("收起", onClick = onClose)
        }
        if (!message.isNullOrBlank()) {
            Text(message, style = tokens.type.meta, color = extra.warning)
        }
        if (pending.isEmpty) {
            KnowledgeHint("暂时没有待确认建议。AI 建议会在整理笔记或点「AI 整理分类」后出现；确认前不会改分类树。")
        } else {
            if (pending.categories.isNotEmpty()) {
                GraphSectionLabel("归类建议（${pending.categories.size}）")
                pending.categories.forEach { item ->
                    SuggestionCategoryRow(
                        item = item,
                        busy = busy,
                        onConfirm = { onConfirmCategory(item) },
                        onIgnore = { onIgnoreCategory(item) },
                    )
                }
            }
            if (pending.relations.isNotEmpty()) {
                GraphSectionLabel("关系建议（${pending.relations.size}）")
                pending.relations.forEach { item ->
                    SuggestionRelationRow(
                        item = item,
                        busy = busy,
                        onConfirm = { onConfirmRelation(item) },
                        onReverse = { onReverseRelation(item) },
                        onIgnore = { onIgnoreRelation(item) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ChromeButton("全部确认", onClick = onConfirmAll, enabled = !busy)
                ChromeButton("全部忽略", onClick = onIgnoreAll, enabled = !busy, filled = false)
            }
        }
    }
}

@Composable
private fun SuggestionCategoryRow(
    item: PendingCategorySuggestion,
    busy: Boolean,
    onConfirm: () -> Unit,
    onIgnore: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.conceptName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text("→", style = tokens.type.caption, color = extra.dataMuted)
            Text(item.categoryPath.joinToString(" / "), style = MaterialTheme.typography.labelMedium, color = extra.accentText)
            Spacer(Modifier.weight(1f))
            Text(item.origin.label, style = tokens.type.meta, color = extra.dataMuted)
        }
        if (!item.reason.isNullOrBlank()) {
            Text(item.reason, style = tokens.type.meta, color = extra.dataMuted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChromeTextLink("确认", onClick = onConfirm, enabled = !busy)
            ChromeTextLink("忽略", onClick = onIgnore, enabled = !busy)
        }
    }
}

@Composable
private fun SuggestionRelationRow(
    item: PendingRelationSuggestion,
    busy: Boolean,
    onConfirm: () -> Unit,
    onReverse: () -> Unit,
    onIgnore: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.fromName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(relationLabel(item.kind), style = tokens.type.meta, color = extra.dataMuted)
            Text(item.toName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(item.origin.label, style = tokens.type.meta, color = extra.dataMuted)
        }
        if (!item.reason.isNullOrBlank()) {
            Text(item.reason, style = tokens.type.meta, color = extra.dataMuted)
        }
        if (!item.evidence.isNullOrBlank()) {
            Text("依据：${item.evidence}", style = tokens.type.meta, color = extra.dataMuted, maxLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChromeTextLink("确认", onClick = onConfirm, enabled = !busy)
            ChromeTextLink("反向", onClick = onReverse, enabled = !busy)
            ChromeTextLink("忽略", onClick = onIgnore, enabled = !busy)
        }
    }
}

private fun relationLabel(kind: GraphEdgeKind): String = when (kind) {
    GraphEdgeKind.PREREQUISITE -> "是的前置"
    GraphEdgeKind.APPLIES -> "用于"
    GraphEdgeKind.RELATED -> "相关"
    GraphEdgeKind.CO_OCCUR -> "共现"
}

@Composable
internal fun GraphTextInputDialog(
    title: String,
    label: String,
    confirmLabel: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            ChromeButton(
                label = confirmLabel,
                onClick = { if (value.trim().isNotEmpty()) onConfirm(value.trim()) },
                enabled = value.trim().isNotEmpty(),
            )
        },
        dismissButton = { ChromeButton("取消", onClick = onDismiss, filled = false) },
    )
}

@Composable
internal fun GraphChoiceDialog(
    title: String,
    choices: List<Pair<String, String>>,
    emptyText: String,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (choices.isEmpty()) {
                Text(emptyText)
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    for ((key, label) in choices) {
                        DropdownMenuItem(text = { Text(label) }, onClick = { onChoose(key) })
                    }
                }
            }
        },
        confirmButton = { ChromeButton("取消", onClick = onDismiss, filled = false) },
    )
}
