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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.activity.ActivityTaxonomy
import dev.trellis.activity.CategoryNode
import dev.trellis.activity.CategoryRule
import dev.trellis.activity.RuleField
import dev.trellis.activity.Segment
import kotlinx.coroutines.launch

/**
 * 活动分类规则页：在程序里维护「大类-小类」树和正则规则，不用再手改 `taxonomy.json`。
 *
 * 交互按「点一行改一行」设计，没有全局编辑态：
 * - 点分类行 → 编辑器（名字 / 颜色 / 父分类 / 规则列表）；
 * - 「加子类」在当前分类下建一个空分类，「新建大类」建根分类；
 * - 保存时先跑 [ActivityTaxonomy.validate] + 正则编译检查，有问题就整页提示、不落盘。
 *
 * 保存成功后通过 [onChanged] 通知活动页重新取数：分类器已经换成新树，
 * 日视图 / 范围总览下一次请求就是新规则算出来的结果。
 */
@Composable
internal fun ActivityRulesView(
    runtime: AppRuntime,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var taxonomy by remember { mutableStateOf<ActivityTaxonomy?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<CategoryNode?>(null) }
    var editingNew by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CategoryNode?>(null) }
    var saving by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(Unit) {
        taxonomy = runtime.activityTaxonomySnapshot()
        if (taxonomy == null) loadError = runtime.activityMessage ?: "分类规则读不出来"
    }

    fun apply(next: ActivityTaxonomy) {
        scope.launch {
            saving = true
            problems = runtime.saveActivityTaxonomy(next)
            saving = false
            if (problems.isEmpty()) {
                taxonomy = next
                onChanged()
            }
        }
    }

    val tree = taxonomy
    if (tree == null) {
        Text(
            loadError ?: "正在读取分类规则…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("分类规则", style = MaterialTheme.typography.titleSmall)
                Text(
                    "大类-小类树 + 正则规则；保存后立即按新规则重新分类。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ChromeButton(
                label = "新建大类",
                onClick = {
                    editingNew = true
                    editing = CategoryNode(id = tree.nextCategoryId(), name = "")
                },
                filled = true,
            )
        }

        if (problems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("保存失败，规则没有改动：", style = MaterialTheme.typography.labelSmall)
                problems.take(6).forEach { problem ->
                    Text("· " + problem, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(flattenTree(tree)) { row ->
                val node = row.first
                val depth = row.second
                CategoryListRow(
                    tree = tree,
                    node = node,
                    depth = depth,
                    onClick = {
                        editingNew = false
                        editing = node
                    },
                    onAddChild = {
                        editingNew = true
                        editing = CategoryNode(id = tree.nextCategoryId(), name = "", parentId = node.id)
                    },
                    onDelete = if (node.id == ActivityTaxonomy.UNCATEGORIZED_ID) {
                        null
                    } else {
                        { pendingDelete = node }
                    },
                )
            }
        }
    }

    editing?.let { node ->
        CategoryEditorDialog(
            tree = tree,
            node = node,
            isNew = editingNew,
            saving = saving,
            problems = problems,
            onDismiss = {
                editing = null
                problems = emptyList()
            },
            onSave = { updated ->
                editing = null
                problems = emptyList()
                apply(tree.withCategory(updated))
            },
        )
    }

    pendingDelete?.let { node ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除分类") },
            text = {
                Text(
                    "删除「" + node.name.ifBlank { node.id } + "」？它的子类会挂到上一级，它自己的规则会一起删掉。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    apply(tree.withoutCategory(node.id))
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

// ------------------------------------------------------------------ 分类列表

@Composable
private fun CategoryListRow(
    tree: ActivityTaxonomy,
    node: CategoryNode,
    depth: Int,
    onClick: () -> Unit,
    onAddChild: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width((depth * 16).dp))
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(ruleColorOf(node.color)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name.ifBlank { "（未命名）" } +
                    if (node.id == ActivityTaxonomy.UNCATEGORIZED_ID) "（保留）" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                tree.pathNames(node.id).joinToString(" / ") + " · " + node.rules.size + " 条规则",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (node.id != ActivityTaxonomy.UNCATEGORIZED_ID) {
            TextButton(onClick = onAddChild) { Text("加子类") }
            if (onDelete != null) {
                TextButton(onClick = onDelete) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 分类编辑

@Composable
private fun CategoryEditorDialog(
    tree: ActivityTaxonomy,
    node: CategoryNode,
    isNew: Boolean,
    saving: Boolean,
    problems: List<String>,
    onDismiss: () -> Unit,
    onSave: (CategoryNode) -> Unit,
) {
    var name by remember(node) { mutableStateOf(node.name) }
    var color by remember(node) { mutableStateOf(node.color) }
    var parentId by remember(node) { mutableStateOf(node.parentId) }
    var rules by remember(node) { mutableStateOf(node.rules) }
    var parentMenu by remember { mutableStateOf(false) }
    var ruleEditorIndex by remember { mutableStateOf<Int?>(null) }
    var showRuleEditor by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    // 可选父分类：不能选自己，也不能选自己的后代（否则成环）。
    val candidates = remember(tree, node.id) {
        tree.categories
            .filter {
                it.id != node.id &&
                    it.id != ActivityTaxonomy.UNCATEGORIZED_ID &&
                    !tree.isDescendantOf(it.id, node.id)
            }
            .sortedBy { tree.pathNames(it.id).joinToString(" / ") }
    }
    val parentLabel = parentId
        ?.let { id -> tree.node(id)?.let { tree.pathNames(it.id).joinToString(" / ") } }
        ?: "无（大类）"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建分类" else "编辑分类") },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isEmpty()) {
                        localError = "名字不能为空"
                        return@TextButton
                    }
                    val finalColor = color.trim().uppercase()
                        .takeIf { ActivityTaxonomy.isHexColor(it) }
                        ?: ActivityTaxonomy.paletteColor(node.id)
                    onSave(
                        node.copy(
                            name = trimmed,
                            color = finalColor,
                            parentId = parentId,
                            rules = rules,
                        ),
                    )
                },
            ) { Text(if (saving) "保存中…" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        localError = null
                    },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = color,
                    onValueChange = { color = it },
                    label = { Text("颜色 #RRGGBB（留空自动取色板）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                PaletteSwatches(selected = color.trim().uppercase()) { picked -> color = picked }

                Box {
                    ChromeButton(
                        label = "父分类：" + parentLabel,
                        onClick = { parentMenu = true },
                        filled = false,
                    )
                    DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("无（大类）") },
                            onClick = {
                                parentId = null
                                parentMenu = false
                            },
                        )
                        candidates.forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(tree.pathNames(candidate.id).joinToString(" / ")) },
                                onClick = {
                                    parentId = candidate.id
                                    parentMenu = false
                                },
                            )
                        }
                    }
                }

                Text("规则（" + rules.size + " 条）", style = MaterialTheme.typography.titleSmall)
                if (node.id == ActivityTaxonomy.UNCATEGORIZED_ID) {
                    Text(
                        "未分类是保留分类：没被任何规则命中的片段会落到这里，规则加在这里没有意义。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rules.forEachIndexed { index, rule ->
                    RuleListRow(
                        rule = rule,
                        onEdit = {
                            ruleEditorIndex = index
                            showRuleEditor = true
                        },
                        onDelete = {
                            rules = rules.toMutableList().also { it.removeAt(index) }
                        },
                    )
                }
                ChromeButton(
                    label = "+ 添加规则",
                    onClick = {
                        ruleEditorIndex = null
                        showRuleEditor = true
                    },
                    filled = false,
                )

                (localError ?: problems.firstOrNull())?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                problems.drop(1).take(4).forEach {
                    Text("· " + it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )

    if (showRuleEditor) {
        val editingRule = ruleEditorIndex?.let { index -> rules.getOrNull(index) }
        RuleEditorDialog(
            initial = editingRule,
            onDismiss = { showRuleEditor = false },
            onSave = { savedRule ->
                val index = ruleEditorIndex
                rules = if (index != null && index in rules.indices) {
                    rules.toMutableList().also { it[index] = savedRule }
                } else {
                    rules + savedRule
                }
                showRuleEditor = false
            },
        )
    }
}

@Composable
private fun RuleListRow(rule: CategoryRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                rule.pattern,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                ruleFieldsLabel(rule) + " · " + (rule.priority?.let { "优先级 $it" } ?: "优先级按层级"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onEdit) { Text("编辑") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun PaletteSwatches(selected: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ActivityTaxonomy.PALETTE.chunked(8).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowColors.forEach { hex ->
                    val isSelected = hex.equals(selected, ignoreCase = true)
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(ruleColorOf(hex))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = RoundedCornerShape(4.dp),
                            )
                            .clickable { onPick(hex) },
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 规则编辑

@Composable
private fun RuleEditorDialog(
    initial: CategoryRule?,
    onDismiss: () -> Unit,
    onSave: (CategoryRule) -> Unit,
) {
    var pattern by remember(initial) { mutableStateOf(initial?.pattern ?: "") }
    var fields by remember(initial) { mutableStateOf(initial?.fields ?: setOf(RuleField.APP)) }
    var ignoreCase by remember(initial) { mutableStateOf(initial?.ignoreCase ?: true) }
    var priority by remember(initial) { mutableStateOf(initial?.priority?.toString() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加规则" else "编辑规则") },
        confirmButton = {
            TextButton(onClick = {
                val text = pattern.trim()
                if (text.isEmpty()) {
                    error = "正则不能为空（空正则会匹配一切）"
                    return@TextButton
                }
                val compileError = runCatching {
                    Regex(text, ActivityTaxonomy.regexOptions(CategoryRule(pattern = text, fields = fields, ignoreCase = ignoreCase)))
                }.exceptionOrNull()
                if (compileError != null) {
                    error = "正则编译不过：" + (compileError.message ?: "语法错误")
                    return@TextButton
                }
                val rawPriority = priority.trim()
                val parsedPriority = if (rawPriority.isEmpty()) {
                    null
                } else {
                    rawPriority.toIntOrNull() ?: run {
                        error = "优先级要写整数，留空表示按层级计算"
                        return@TextButton
                    }
                }
                onSave(
                    CategoryRule(
                        pattern = text,
                        fields = fields,
                        ignoreCase = ignoreCase,
                        priority = parsedPriority,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        error = null
                    },
                    label = { Text("正则（作用于下面选中的字段）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("匹配字段", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleField.entries.forEach { field ->
                        FieldToggle(
                            field = field,
                            selected = field in fields,
                            onClick = {
                                fields = if (field in fields) fields - field else fields + field
                            },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ignoreCase, onCheckedChange = { ignoreCase = it })
                    Text("忽略大小写", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    value = priority,
                    onValueChange = {
                        priority = it
                        error = null
                    },
                    label = { Text("优先级（留空 = 按层级 depth × 10）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/** 字段开关：APP / TITLE / URL / DOMAIN，多选是「或」关系。 */
@Composable
private fun FieldToggle(field: RuleField, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(fieldName(field), style = MaterialTheme.typography.labelSmall, color = content)
    }
}

// ------------------------------------------------------------------ 从片段建规则

/**
 * 「把这段归到某个分类」：从片段里的进程名/域名生成一条规则，选个分类就能保存。
 *
 * 这是未分类片段最顺手的出口 —— 不用先想正则，程序先给出能匹配这条片段的字面量，
 * 用户只挑分类；要更精细的规则再进「分类规则」页改。
 */
@Composable
internal fun QuickClassifyDialog(
    runtime: AppRuntime,
    segment: Segment,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var taxonomy by remember { mutableStateOf<ActivityTaxonomy?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var pattern by remember(segment) { mutableStateOf(defaultRulePattern(segment)) }
    var fields by remember(segment) { mutableStateOf(defaultRuleFields(segment)) }
    var menuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        taxonomy = runtime.activityTaxonomySnapshot()
        if (taxonomy == null) error = runtime.activityMessage ?: "分类规则读不出来"
    }

    val tree = taxonomy
    val selectedNode = selectedId?.let { id -> tree?.node(id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归到分类") },
        confirmButton = {
            TextButton(
                enabled = tree != null && selectedNode != null && pattern.isNotBlank() && !saving,
                onClick = {
                    val current = tree ?: return@TextButton
                    val node = selectedNode ?: return@TextButton
                    scope.launch {
                        saving = true
                        val rule = CategoryRule(
                            pattern = pattern.trim(),
                            fields = fields,
                            ignoreCase = true,
                        )
                        val problems = runtime.saveActivityTaxonomy(
                            current.withCategory(node.copy(rules = node.rules + rule)),
                        )
                        saving = false
                        if (problems.isEmpty()) onSaved() else error = problems.joinToString("；")
                    }
                },
            ) { Text(if (saving) "保存中…" else "保存并重新分类") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    (segment.app ?: "（未知）") + " · " + rulesDuration(segment.durationMs),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!segment.url.isNullOrBlank()) {
                    Text(
                        "网址：" + segment.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = {
                        pattern = it
                        error = null
                    },
                    label = { Text("正则（已按片段填好，可改）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RuleField.entries.forEach { field ->
                        FieldToggle(
                            field = field,
                            selected = field in fields,
                            onClick = {
                                fields = if (field in fields) fields - field else fields + field
                            },
                        )
                    }
                }
                Box {
                    ChromeButton(
                        label = "分类：" + (
                            selectedNode?.let { tree?.pathNames(it.id)?.joinToString(" / ") } ?: "请选择"
                            ),
                        onClick = { menuOpen = true },
                        filled = false,
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (tree != null) {
                            flattenTree(tree).forEach { row ->
                                val node = row.first
                                val depth = row.second
                                DropdownMenuItem(
                                    text = { Text("  ".repeat(depth) + node.name.ifBlank { node.id }) },
                                    onClick = {
                                        selectedId = node.id
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                Text(
                    "保存后会给这个分类加一条规则，按上面的正则重新分类所有记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

// ------------------------------------------------------------------ 小工具

/** 分类树展开成「节点 + 缩进层级」，顺序就是 [ActivityTaxonomy.childrenOf] 的顺序。 */
private fun flattenTree(tree: ActivityTaxonomy): List<Pair<CategoryNode, Int>> {
    val rows = ArrayList<Pair<CategoryNode, Int>>()
    fun walk(parentId: String?, depth: Int) {
        tree.childrenOf(parentId).forEach { node ->
            rows += node to depth
            walk(node.id, depth + 1)
        }
    }
    walk(null, 0)
    return rows
}

private fun ruleFieldsLabel(rule: CategoryRule): String =
    rule.fields.joinToString("+") { fieldName(it) }.ifBlank { "APP" }

private fun fieldName(field: RuleField): String = when (field) {
    RuleField.APP -> "进程名"
    RuleField.TITLE -> "窗口标题"
    RuleField.URL -> "完整 URL"
    RuleField.DOMAIN -> "域名"
}

/** 从片段猜一条「最少惊讶」的规则：有域名用域名，否则用进程名，再不行用标题。 */
private fun defaultRulePattern(segment: Segment): String {
    val domain = ActivityTaxonomy.domainOf(segment.url)
    val raw = domain
        ?: segment.app?.takeIf { it.isNotBlank() }
        ?: segment.title?.takeIf { it.isNotBlank() }
        ?: ""
    return if (raw.isBlank()) "" else Regex.escape(raw)
}

private fun defaultRuleFields(segment: Segment): Set<RuleField> = when {
    ActivityTaxonomy.domainOf(segment.url) != null -> setOf(RuleField.DOMAIN)
    !segment.app.isNullOrBlank() -> setOf(RuleField.APP)
    else -> setOf(RuleField.TITLE)
}

private fun rulesDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes <= 0 -> "不到 1 分钟"
        minutes < 60 -> "$minutes 分钟"
        else -> (minutes / 60).toString() + " 小时 " + (minutes % 60) + " 分"
    }
}

/** `#RRGGBB` → Color；写坏的颜色退回未分类灰（和页面的 tintOf 同一套规则）。 */
private fun ruleColorOf(hex: String): Color {
    val clean = hex.trim().removePrefix("#")
    if (clean.length != 6) return Color(0xFF9CA3AF)
    val value = clean.toLongOrNull(16) ?: return Color(0xFF9CA3AF)
    return Color(0xFF000000L or value)
}