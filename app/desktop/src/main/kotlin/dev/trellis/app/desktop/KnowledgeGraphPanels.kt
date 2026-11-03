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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    onQueryChange: (String) -> Unit,
    onNodeClick: (String) -> Unit,
    onShowUnclassified: (Boolean) -> Unit,
) {
    val extra = LocalExtraColors.current
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
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索概念或分类…", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (query.trim().isNotEmpty()) {
            Text("搜索结果 ${results.size}", style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
            if (results.isEmpty()) {
                KnowledgeHint("没有匹配的节点。试试完整概念名，或先去笔记里记录这个知识点。")
            } else {
                results.forEach { node ->
                    GraphHitRow(node = node, onClick = { onNodeClick(node.key) })
                }
            }
        } else {
            GraphSectionLabel("筛选")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("显示未分类", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Switch(checked = showUnclassified, onCheckedChange = onShowUnclassified)
            }
            KnowledgeHint("未分类 = 还没挂到任何分类下的概念；点浮窗里的「移动到分类…」归档。")

            GraphSectionLabel("图例")
            LegendRow(solid = true, label = "概念（实）")
            LegendRow(solid = false, label = "分类（虚）")
            LegendLineRow("父子连线（所属关系）")
            KnowledgeHint("点节点：其它节点淡出 + 弹出小浮窗；双击分类：折叠/展开；拖动虚节点到其它节点上：把那个节点收为子节点。")

            GraphSectionLabel("统计")
            KnowledgeGraphStatsCard(graph)
        }

        HorizontalDivider(color = extra.dataTrack.copy(alpha = 0.6f))

        KnowledgeHint("新建分类节点用工具条「＋ 虚节点」；把它拖到某个节点上，那个节点就收进来。")
        TextButton(onClick = {}, enabled = false) {
            Text("✦ AI 整理分类（G1 接入）", style = MaterialTheme.typography.labelMedium)
        }
        KnowledgeHint("G1：AI 读笔记建议分类路径 / 关系，你确认后才写入分类树。")

        if (!message.isNullOrBlank()) {
            Text(message, style = MaterialTheme.typography.labelSmall, color = extra.accentText)
        }
    }
}

@Composable
private fun LegendRow(solid: Boolean, label: String) {
    val extra = LocalExtraColors.current
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
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LegendLineRow(label: String) {
    val extra = LocalExtraColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.width(26.dp).height(1.5.dp).background(extra.dataTrack))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
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
    val extra = LocalExtraColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("分类树", style = MaterialTheme.typography.labelMedium, color = extra.dataMuted)
        TextButton(onClick = onExpandAll) { Text("展开全部", style = MaterialTheme.typography.labelMedium) }
        TextButton(onClick = onCollapseAll) { Text("折叠全部", style = MaterialTheme.typography.labelMedium) }
        TextButton(onClick = onFit) { Text("适应窗口", style = MaterialTheme.typography.labelMedium) }
        TextButton(onClick = onNewVirtualNode) { Text("＋ 虚节点", style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.weight(1f))
        Text("点击看浮窗 · 双击折叠 · 拖动虚节点收子节点", style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
    }
}

// ---------------------------------------------------------------------------
// 统计 / 空态
// ---------------------------------------------------------------------------

@Composable
internal fun KnowledgeGraphStatsCard(graph: KnowledgeGraph) {
    val extra = LocalExtraColors.current
    val stats = graph.stats
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatValue(stats.conceptCount, "概念")
            StatValue(stats.categoryCount, "分类")
            StatValue(stats.unclassifiedCount, "未分类")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StatValue(stats.relationEdgeCount, "关系")
            StatValue(stats.aiSuggestionCount, "AI 建议")
        }
        if (stats.droppedAssignments > 0) {
            Text(
                "有 ${stats.droppedAssignments} 条归属不合法，已丢进未分类",
                style = MaterialTheme.typography.labelSmall,
                color = extra.warning,
            )
        }
    }
}

@Composable
private fun StatValue(value: Int, label: String) {
    val extra = LocalExtraColors.current
    Column {
        Text("$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
    }
}

@Composable
internal fun KnowledgeGraphEmptyState(hasNotes: Boolean, hasConcepts: Boolean, message: String?) {
    val extra = LocalExtraColors.current
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
            Text(message, style = MaterialTheme.typography.labelSmall, color = extra.accentText)
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
            Text("AI", style = MaterialTheme.typography.labelSmall, color = extra.warning)
        }
    }
}

@Composable
internal fun KnowledgeHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
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
        Text(hint, style = MaterialTheme.typography.labelSmall, color = LocalExtraColors.current.dataMuted)
    }
}

@Composable
internal fun ChromeButton2(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
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
            TextButton(
                onClick = { if (value.trim().isNotEmpty()) onConfirm(value.trim()) },
                enabled = value.trim().isNotEmpty(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
