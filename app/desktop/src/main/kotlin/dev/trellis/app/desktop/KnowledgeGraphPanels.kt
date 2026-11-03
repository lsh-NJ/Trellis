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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalExtraColors

/**
 * 知识网络的辅助面板（设计 §5）：左栏筛选/图例/统计、右栏概念/分类详情、空态。
 *
 * 面板**不改数据**：所有写操作都通过回调交给页面（页面再调 Taxonomy/Store）。
 * 这样 G0 的界面可以先接上，G1 换 AI 建议面板时不用改数据层。
 */
@Composable
internal fun KnowledgeGraphLeftPanel(
    graph: KnowledgeGraph,
    aiBusy: Boolean,
    message: String?,
    onQueryChange: (String) -> Unit,
    onNodeClick: (String) -> Unit,
    onShowUnclassified: (Boolean) -> Unit,
    onCollapseByDefault: (Boolean) -> Unit,
    onExpandDepth: (Int) -> Unit,
    onShowRelationsOnFocus: (Boolean) -> Unit,
    onShowCoOccurrence: (Boolean) -> Unit,
    onNewCategory: () -> Unit,
    onAiSuggest: (() -> Unit)?,
) {
    val view = KnowledgeGraphViewState
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChromeTextField(
            value = view.query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索概念或分类…",
        )

        if (view.query.isNotBlank()) {
            val hits = remember(graph, view.query) {
                graph.nodes.values
                    .filter { it.name.contains(view.query, ignoreCase = true) }
                    .sortedWith(compareBy({ it.kind == GraphNodeKind.CATEGORY }, { it.name }))
                    .take(40)
            }
            if (hits.isEmpty()) {
                KnowledgeHint("没有匹配的节点")
            } else {
                GraphSectionLabel("搜索结果 ${hits.size}")
                for (node in hits) {
                    GraphHitRow(node = node, onClick = { onNodeClick(node.key) })
                }
            }
            Spacer(Modifier.height(2.dp))
        }

        GraphSectionLabel("筛选")
        GraphToggleRow("显示未分类", view.showUnclassified, onShowUnclassified)
        GraphToggleRow("分类默认折叠", view.collapseByDefault, onCollapseByDefault)
        GraphToggleRow("聚焦时显示关联", view.showRelationsOnFocus, onShowRelationsOnFocus)
        GraphToggleRow("显示共现（默认隐藏）", view.showCoOccurrence, onShowCoOccurrence)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("分类深度", style = MaterialTheme.typography.labelMedium, color = LocalExtraColors.current.dataMuted)
            Spacer(Modifier.width(8.dp))
            for (depth in 1..5) {
                DepthButton(
                    label = depth.toString(),
                    active = view.expandDepth == depth,
                    onClick = { onExpandDepth(depth) },
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        GraphSectionLabel("图例")
        GraphLegendRow(kind = GraphEdgeKind.PREREQUISITE, label = "前置（聚焦时）")
        GraphLegendRow(kind = GraphEdgeKind.APPLIES, label = "应用（聚焦时）")
        GraphLegendRow(kind = GraphEdgeKind.RELATED, label = "相关（聚焦时）")
        GraphLegendRow(kind = GraphEdgeKind.CO_OCCUR, label = "共现（默认）")
        GraphLegendNoteRow("分类框（可折叠）")
        GraphLegendNoteRow("概念芯片（大小=提及次数）")
        GraphLegendNoteRow("未分类（可拖入分类）")

        GraphSectionLabel("统计")
        KnowledgeGraphStatsCard(graph)

        Box(Modifier.height(2.dp))
        if (onAiSuggest != null) {
            ChromeButton2(
                label = if (aiBusy) "AI 正在整理…" else "✦ 补全分类建议",
                enabled = !aiBusy,
                onClick = onAiSuggest,
            )
            Text(
                "AI 只写建议，确认后才进分类树；手动触发、每次调用有成本。",
                style = MaterialTheme.typography.labelSmall,
                color = LocalExtraColors.current.dataMuted,
            )
        } else {
            Text(
                "G0 不做 AI 归类：先手动整理分类树，AI 建议在 G1 接入。",
                style = MaterialTheme.typography.labelSmall,
                color = LocalExtraColors.current.dataMuted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChromeButton2(label = "+ 新建分类", enabled = true, onClick = onNewCategory)
        }
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
internal fun KnowledgeGraphToolbar(
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onFit: () -> Unit,
    onNewCategory: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("分类树", style = MaterialTheme.typography.labelMedium, color = LocalExtraColors.current.dataMuted)
        Text("·", style = MaterialTheme.typography.labelMedium, color = LocalExtraColors.current.dataMuted)
        EditorChip("展开全部", active = false, onClick = onExpandAll)
        EditorChip("折叠全部", active = false, onClick = onCollapseAll)
        EditorChip("适应窗口", active = false, onClick = onFit)
        EditorChip("+ 新建分类", active = false, onClick = onNewCategory)
        Text(
            "体系视图（G2）",
            style = MaterialTheme.typography.labelSmall,
            color = LocalExtraColors.current.dataMuted.copy(alpha = 0.6f),
        )
    }
}

@Composable
internal fun KnowledgeGraphStatsCard(graph: KnowledgeGraph) {
    val stats = graph.stats
    val extra = LocalExtraColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GraphStatValue(label = "概念", value = stats.conceptCount.toString())
            GraphStatValue(label = "分类", value = stats.categoryCount.toString())
            GraphStatValue(label = "未分类", value = stats.unclassifiedCount.toString())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GraphStatValue(label = "关系", value = stats.relationEdgeCount.toString())
            GraphStatValue(
                label = "AI 建议",
                value = stats.aiSuggestionCount.toString(),
                highlight = stats.aiSuggestionCount > 0,
            )
        }
        if (stats.droppedAssignments > 0) {
            Text(
                "有 ${stats.droppedAssignments} 条归属因分类树非法被忽略（见诊断）",
                style = MaterialTheme.typography.labelSmall,
                color = extra.warning,
            )
        }
    }
}

@Composable
private fun GraphStatValue(label: String, value: String, highlight: Boolean = false) {
    val extra = LocalExtraColors.current
    Column {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) extra.accentText else MaterialTheme.colorScheme.onSurface,
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
    }
}

@Composable
internal fun KnowledgeGraphEmptyState(
    hasNotes: Boolean,
    hasConcepts: Boolean,
    message: String?,
) {
    val title = when {
        !hasNotes -> "还没有笔记"
        !hasConcepts -> "笔记里还没有概念"
        else -> "还没有分类"
    }
    val hint = when {
        !hasNotes -> "先去「笔记」写一篇，概念会自动点亮；网络视图从概念里长出来。"
        !hasConcepts -> "在笔记里写 `[[概念]]`，或让 AI 在焦点离开正文时整理；也可以去「概念」页手动加。"
        else -> "把概念拖进分类，或点「+ 新建分类」先搭一棵树；没有合适父节点的概念会留在「未分类」。"
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = 440.dp).padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.dataMuted)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 右栏：节点详情
// ---------------------------------------------------------------------------

@Composable
internal fun KnowledgeGraphDetailPanel(
    graph: KnowledgeGraph,
    selectedKey: String?,
    onClose: () -> Unit,
    onNodeClick: (String) -> Unit,
    onLocateNote: (String) -> Unit,
    onAssignCategory: (String, String?) -> Unit,
    onCreateCategory: (name: String, parentId: String?) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onMergeCategory: (String, String) -> Unit,
    onSaveDefinition: (conceptName: String, definition: String) -> Unit,
) {
    val node = selectedKey?.let { graph.nodes[it] }
    if (node == null) {
        KnowledgeGraphHintCard(
            "点节点看详情",
            "默认只画所属关系。点一个节点，右栏显示它的定义、所属分类与直接关联；其它关联只在聚焦时出现。",
        )
        return
    }
    val extra = LocalExtraColors.current
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (node.kind == GraphNodeKind.CATEGORY) "分类 · CATEGORY" else "概念 · CONCEPT",
                style = MaterialTheme.typography.labelSmall,
                color = extra.dataMuted,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Text(node.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "${node.noteCount} 篇笔记提到 · 共 ${node.mentionCount} 次" +
                if (node.frontmatterOnly) " · 只在 frontmatter 里" else "",
            style = MaterialTheme.typography.labelSmall,
            color = extra.dataMuted,
        )

        // 所属分类路径（面包屑 chips）。
        val path = graph.pathOf(node.key).dropLast(1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("所属分类", style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
            if (path.isEmpty()) {
                GraphPathChip("未分类", active = false, onClick = null)
            } else {
                for ((index, category) in path.withIndex()) {
                    if (index > 0) {
                        Text("/", style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
                    }
                    GraphPathChip(category.name, active = false, onClick = { onNodeClick(category.key) })
                }
            }
        }

        CategoryAssignmentRow(
            graph = graph,
            currentCategoryId = graph.categoryOf(node.key),
            onAssignCategory = onAssignCategory,
            onCreateCategory = { name -> onCreateCategory(name, node.parentKey) },
        )
        if (node.kind == GraphNodeKind.CATEGORY) {
            CategoryEditRow(
                graph = graph,
                category = node,
                onRename = { newName -> onRenameCategory(node.key, newName) },
                onDelete = { onDeleteCategory(node.key) },
                onMerge = { target -> onMergeCategory(node.key, target) },
                onCreateChild = { name -> onCreateCategory(name, node.key) },
            )
        }

        if (node.kind == GraphNodeKind.CONCEPT) {
            var definition by remember(node.key) { mutableStateOf(node.definition.orEmpty()) }
            GraphSectionLabel("定义")
            OutlinedTextField(
                value = definition,
                onValueChange = { definition = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("一句话定义（AI 只填空，不覆盖你写的）") },
                minLines = 2,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChromeButton2(
                    label = "保存定义",
                    enabled = definition.trim() != node.definition.orEmpty().trim(),
                    onClick = { onSaveDefinition(node.name, definition.trim()) },
                )
            }
        }

        // 直接关联（聚焦时在画布上显示；这里列出来兜底）。
        val relations = graph.relationsOf(node.key)
        GraphSectionLabel("直接关联（${relations.size}）")
        if (relations.isEmpty()) {
            KnowledgeHint("还没有关系。点节点时画布只显示直接相连的关系边；G2 才开放手动关系编辑。")
        } else {
            for (edge in relations.take(20)) {
                val otherKey = if (edge.fromKey == node.key) edge.toKey else edge.fromKey
                val other = graph.nodes[otherKey] ?: continue
                val direction = if (edge.fromKey == node.key) "→" else "←"
                GraphRelationRow(
                    kind = edge.kind,
                    source = edge.source,
                    label = "$direction ${other.name}",
                    detail = edge.evidence?.snippet?.take(50),
                    onClick = { onNodeClick(otherKey) },
                )
            }
        }

        if (node.kind == GraphNodeKind.CONCEPT) {
            ChromeButton2(
                label = "在笔记中定位",
                enabled = node.noteCount > 0,
                onClick = { onLocateNote(node.name) },
            )
            Text(
                "先看来源片段，再跳到原文。",
                style = MaterialTheme.typography.labelSmall,
                color = extra.dataMuted,
            )
            if (node.noteCount <= 1 || node.definition.isNullOrBlank()) {
                Text(
                    "薄弱：${if (node.noteCount <= 1) "只有 1 篇笔记提到" else ""}" +
                        if (node.definition.isNullOrBlank()) "${if (node.noteCount <= 1) "，" else ""}还没有定义" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.warning,
                )
            }
        }
        if (node.kind == GraphNodeKind.CATEGORY) {
            GraphSectionLabel("子分类与概念")
            val children = graph.childrenOf(node.key)
            if (children.isEmpty()) {
                KnowledgeHint("这个分类下还没有东西")
            } else {
                for (child in children) {
                    GraphHitRow(node = child, onClick = { onNodeClick(child.key) })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun CategoryAssignmentRow(
    graph: KnowledgeGraph,
    currentCategoryId: String?,
    onAssignCategory: (String, String?) -> Unit,
    onCreateCategory: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box {
            ChromeButton2(label = "移动到分类…", enabled = true, onClick = { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("未分类") },
                    onClick = {
                        expanded = false
                        onAssignCategory(GRAPH_UNCLASSIFIED_KEY, null)
                    },
                )
                val categories = graph.nodes.values
                    .filter { it.kind == GraphNodeKind.CATEGORY }
                    .sortedWith(compareBy({ graph.pathOf(it.key).size }, { it.name }))
                for (category in categories) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "  ".repeat((graph.pathOf(category.key).size - 1).coerceAtLeast(0)) + category.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            onAssignCategory(category.key, category.key)
                        },
                    )
                }
                if (currentCategoryId != null) {
                    DropdownMenuItem(
                        text = { Text("（当前已在分类里）", color = LocalExtraColors.current.dataMuted) },
                        onClick = { expanded = false },
                    )
                }
            }
        }
        ChromeButton2(label = "＋ 新建分类", enabled = true, onClick = { creating = true })
    }
    if (creating) {
        GraphTextInputDialog(
            title = "新建分类",
            label = "分类名（会建在当前分类下）",
            confirmLabel = "创建",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                onCreateCategory(name)
            },
        )
    }
}

@Composable
private fun CategoryEditRow(
    graph: KnowledgeGraph,
    category: GraphNode,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onMerge: (String) -> Unit,
    onCreateChild: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChromeButton2("改名…", true) { renaming = true }
        ChromeButton2("新建子分类…", true) { creating = true }
        ChromeButton2("合并到…", true) { merging = true }
        ChromeButton2("删除…", true) { deleting = true }
    }
    if (renaming) {
        GraphTextInputDialog(
            title = "重命名分类",
            label = "新名字",
            confirmLabel = "保存",
            initial = category.name,
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                onRename(name)
            },
        )
    }
    if (creating) {
        GraphTextInputDialog(
            title = "新建子分类",
            label = "分类名",
            confirmLabel = "创建",
            initial = "",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                onCreateChild(name)
            },
        )
    }
    if (merging) {
        val candidates = graph.nodes.values
            .filter { it.kind == GraphNodeKind.CATEGORY && it.key != category.key }
            .sortedBy { it.name }
        GraphChoiceDialog(
            title = "合并「${category.name}」到…",
            choices = candidates.map { it.key to it.name },
            emptyText = "没有别的分类可合并",
            onDismiss = { merging = false },
            onChoose = { key ->
                merging = false
                onMerge(key)
            },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除分类「${category.name}」？") },
            text = { Text("子分会往上移一层，概念回到「未分类」；正文笔记不会被改。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } },
        )
    }
}

// ---------------------------------------------------------------------------
// 小部件
// ---------------------------------------------------------------------------

@Composable
private fun GraphSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = LocalExtraColors.current.dataMuted,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun GraphToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DepthButton(label: String, active: Boolean, onClick: () -> Unit) {
    val extra = LocalExtraColors.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (active) extra.accentText.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (active) extra.accentText else extra.dataTrack, RoundedCornerShape(5.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) extra.accentText else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun GraphLegendRow(kind: GraphEdgeKind, label: String) {
    val extra = LocalExtraColors.current
    val color = when (kind) {
        GraphEdgeKind.PREREQUISITE -> extra.data
        GraphEdgeKind.APPLIES -> Color(0xFF4E6FA8)
        GraphEdgeKind.RELATED -> extra.dataMuted
        GraphEdgeKind.CO_OCCUR -> extra.dataTrack
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .width(26.dp)
                .height(2.dp)
                .background(color),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun GraphLegendNoteRow(label: String) {
    val extra = LocalExtraColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .border(1.dp, extra.dataTrack, RoundedCornerShape(3.dp)),
        )
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
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
        Text("${node.noteCount}", style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
    }
}

@Composable
private fun GraphPathChip(label: String, active: Boolean, onClick: (() -> Unit)?) {
    val extra = LocalExtraColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) extra.accentText.copy(alpha = 0.15f) else extra.dataTrack.copy(alpha = 0.35f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
private fun KnowledgeGraphHintCard(title: String, hint: String) {
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
private fun GraphRelationRow(
    kind: GraphEdgeKind,
    source: GraphEdgeSource,
    label: String,
    detail: String?,
    onClick: () -> Unit,
) {
    val extra = LocalExtraColors.current
    val kindText = when (kind) {
        GraphEdgeKind.PREREQUISITE -> "前置"
        GraphEdgeKind.APPLIES -> "应用"
        GraphEdgeKind.RELATED -> "相关"
        GraphEdgeKind.CO_OCCUR -> "共现"
    }
    val sourceText = when (source) {
        GraphEdgeSource.USER -> "你确认"
        GraphEdgeSource.EXPLICIT -> "标注"
        GraphEdgeSource.HEURISTIC -> "自动"
        GraphEdgeSource.AI -> "AI 建议"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(kindText, style = MaterialTheme.typography.labelSmall, color = extra.accentText)
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sourceText, style = MaterialTheme.typography.labelSmall, color = extra.dataMuted)
        }
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = extra.dataMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ChromeButton2(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun GraphTextInputDialog(
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
private fun GraphChoiceDialog(
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
