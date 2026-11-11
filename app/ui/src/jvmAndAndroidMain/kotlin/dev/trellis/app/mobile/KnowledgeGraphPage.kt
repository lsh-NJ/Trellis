package dev.trellis.app.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.Camera
import dev.trellis.app.desktop.ConceptMetaStore
import dev.trellis.app.desktop.GraphEdgeKind
import dev.trellis.app.desktop.GraphLayout
import dev.trellis.app.desktop.GraphNode
import dev.trellis.app.desktop.GraphNodeKind
import dev.trellis.app.desktop.GraphPoint
import dev.trellis.app.desktop.GraphSize
import dev.trellis.app.desktop.KnowledgeGraph
import dev.trellis.app.desktop.KnowledgeGraphStore
import dev.trellis.app.desktop.LayoutOptions
import dev.trellis.app.desktop.TaxonomyResult
import dev.trellis.app.desktop.buildKnowledgeGraph
import dev.trellis.app.desktop.assignConcept
import dev.trellis.app.desktop.cacheWithoutCategorySuggestions
import dev.trellis.app.desktop.categoryPathOf
import dev.trellis.app.desktop.categorySuggestionFingerprint
import dev.trellis.app.desktop.collectPendingSuggestions
import dev.trellis.app.desktop.confirmCategorySuggestions
import dev.trellis.app.desktop.createCategory
import dev.trellis.app.desktop.deleteCategory
import dev.trellis.app.desktop.globalCategoryBatches
import dev.trellis.app.desktop.ignoreCategorySuggestion
import dev.trellis.app.desktop.layoutKnowledgeGraph
import dev.trellis.app.desktop.moveCategory
import dev.trellis.app.desktop.noteBodyHash
import dev.trellis.app.desktop.renameCategory
import dev.trellis.app.desktop.runGlobalCategorySuggestions
import kotlinx.coroutines.launch

/**
 * 知识网络：一棵「分类 → 概念」的树，外加 AI 建议与诊断。
 *
 * 手机上默认是**结构树**（缩进列表），不是画布：
 * 一张横向铺开的森林图塞进 390dp，只会得到一排看不清的截断标签。
 * 画布仍然保留（「总览」），用来一眼看规模与层级；要看内容和关系，用树。
 *
 * 数据是**现算**的：每次读盘 build 一遍（纯函数），不维护第二份真相。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphPage(runtime: AppRuntime, startMode: Int = 0, onOpenNote: (String) -> Unit) {
    val store = remember(runtime) { runtime.knowledgeStore }
    val metaStore = remember(runtime) { ConceptMetaStore(runtime.home) }
    val graphStore = remember(runtime) { KnowledgeGraphStore(store.home, runtime.clock) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var refreshToken by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(startMode.coerceIn(0, 1)) } // 0 结构 / 1 总览
    var selectedKey by remember { mutableStateOf<String?>(null) }
    var suggestionsOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }
    var organizing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    // 节点详情里的二级面板：0 概览 / 1 改归属 / 2 改定义 / 3 重命名 /
    // 4 新建子分类 / 5 新建上级分类 / 6 移动到分类 / 7 删除分类。
    // 不复用第二个 ModalBottomSheet —— 两个 sheet 叠起来时，返回手势先关掉下面那个，
    // 看起来像「点了一下什么都没发生」。
    var nodePanel by remember { mutableIntStateOf(0) }
    var definitionDraft by remember { mutableStateOf("") }
    var aliasDraft by remember { mutableStateOf("") }
    var renameDraft by remember { mutableStateOf("") }
    // 新建分类（根 / 子 / 上级）共用一份输入；每次打开面板时清空。
    var newCategoryDraft by remember { mutableStateOf("") }
    // 页头的「新建分类」是独立 sheet：此时没有选中节点，不能借用节点详情。
    var rootCreateOpen by remember { mutableStateOf(false) }

    val user = remember(refreshToken) { graphStore.loadUser() }
    val cache = remember(refreshToken) { graphStore.loadCache() }
    val graph: KnowledgeGraph = remember(refreshToken) {
        buildKnowledgeGraph(store.allNotes(), metaStore.load(), user, cache)
    }
    val columns = remember(graph) { boardColumns(graph) }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = Gap.l, end = Gap.l, bottom = Gap.l),
            verticalArrangement = Arrangement.spacedBy(Gap.xs),
        ) {
            item {
                CardBox(padding = Gap.m) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BigStat(graph.stats.conceptCount.toString(), "概念", Modifier.weight(1f))
                        BigStat(graph.stats.categoryCount.toString(), "分类", Modifier.weight(1f))
                        BigStat(graph.stats.relationEdgeCount.toString(), "关系", Modifier.weight(1f))
                        BigStat(
                            graph.stats.unclassifiedCount.toString(),
                            "未归类",
                            Modifier.weight(1f),
                            accent = if (graph.stats.unclassifiedCount > 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                null
                            },
                        )
                    }
                    Spacer(Modifier.height(Gap.m))
                    Segmented(items = listOf("结构", "总览"), selected = mode, onSelect = { mode = it })
                    Spacer(Modifier.height(Gap.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                        PhoneButton("新建分类", { newCategoryDraft = ""; rootCreateOpen = true }, filled = false)
                        PhoneButton("AI 建议", { suggestionsOpen = true }, filled = false)
                    }
                    Spacer(Modifier.height(Gap.s))
                    Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                        PhoneButton("诊断", { diagnosticsOpen = true }, filled = false)
                        PhoneButton("刷新", { refreshToken++ }, filled = false)
                    }
                }
            }

            if (graph.nodes.isEmpty()) {
                item {
                    EmptyState(
                        "还没有概念",
                        "在笔记里点一次「保存并整理」，概念就会长出来；也可以手动写 [[概念]] 标注。",
                    )
                }
            } else if (mode == 0) {
                // 结构：一棵树一行，树的叶子朝右发散。
                //
                // 之前是横向分栏（一列一类、左右滑），切一次只能看一类；
                // 现在竖着排树、每题往右展开，一屏能看到「有哪几棵树、每棵树挂着什么」，
                // 叶子再多就在这一行里左右滑，不会把别的树挤走。
                item(key = "forest") {
                    Column(verticalArrangement = Arrangement.spacedBy(Gap.m)) {
                        columns.forEach { column ->
                            TreeBranch(column = column, onOpen = { selectedKey = it })
                        }
                    }
                }
            } else {
                item {
                    CardBox(padding = Gap.s) {
                        GraphCanvas(graph, selectedKey) { selectedKey = it }
                    }
                }
            }

            if (graph.diagnostics.isNotEmpty()) {
                item {
                    CardBox(onClick = { diagnosticsOpen = true }) {
                        SectionLabel("诊断 " + graph.diagnostics.size, action = "看看" to { diagnosticsOpen = true })
                        Spacer(Modifier.height(Gap.xs))
                        Text(
                            graph.diagnostics.take(2).joinToString("；") { it.detail },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            message?.let {
                item {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    val selected = selectedKey?.let { graph.nodes[it] }
    if (selected != null) {
        val isCategory = selected.kind == GraphNodeKind.CATEGORY
        // 存一次就用的写回助手：报错进 message，成功就重算图并关面板。
        fun applyUser(result: TaxonomyResult) {
            if (result.error != null) {
                message = result.error
                return
            }
            graphStore.saveUser(result.user.copy(updatedAt = runtime.clock.nowMs()))
            refreshToken++
            message = result.changes.firstOrNull()
            nodePanel = 0
            selectedKey = null
        }

        // 当前分类 + 它的后代：移动目标里不能出现它们（会成环）。
        fun categorySubtreeKeys(rootKey: String): Set<String> {
            val seen = mutableSetOf<String>()
            fun walk(key: String) {
                if (!seen.add(key)) return
                graph.childrenOf(key)
                    .filter { it.kind == GraphNodeKind.CATEGORY }
                    .forEach { walk(it.key) }
            }
            walk(rootKey)
            return seen
        }

        ModalBottomSheet(
            onDismissRequest = {
                // 二级面板先回概览，再按一次才关掉。
                if (nodePanel != 0) nodePanel = 0 else selectedKey = null
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                when (nodePanel) {
                    1 -> {
                        PanelHeading("改归属", "归到哪个分类；没有合适的就选「未分类」，之后还能再改。")
                        Spacer(Modifier.height(Gap.m))
                        NodeChoice(
                            label = "未分类",
                            hint = "留在根上，不进任何分类",
                            selected = selected.parentKey == null,
                            onClick = { applyUser(assignConcept(user, selected.key, null)) },
                        )
                        user.categories
                            .sortedBy { categoryPathOf(user, it.id).joinToString("/") }
                            .forEach { category ->
                                val path = categoryPathOf(user, category.id).joinToString(" / ")
                                NodeChoice(
                                    label = path.ifBlank { category.name },
                                    hint = null,
                                    selected = selected.parentKey == category.id,
                                    onClick = { applyUser(assignConcept(user, selected.key, category.id)) },
                                )
                            }
                        Spacer(Modifier.height(Gap.m))
                        PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.fillMaxWidth(), filled = false)
                    }

                    2 -> {
                        PanelHeading(
                            title = if (isCategory) "分类说明" else "概念定义",
                            hint = if (isCategory) {
                                "一句话说明这个分类收什么，自己半年后也看得懂。"
                            } else {
                                "定义只有一份，所有提到它的笔记共用。写「是什么」，不写「在哪出现过」。"
                            },
                        )
                        Spacer(Modifier.height(Gap.m))
                        PhoneField(
                            value = definitionDraft,
                            onValueChange = { definitionDraft = it },
                            placeholder = if (isCategory) {
                                "例如：传输层与网络层之间的机制"
                            } else {
                                "例如：TCP 是面向连接的可靠传输协议。"
                            },
                            singleLine = false,
                            minLines = 4,
                        )
                        if (!isCategory) {
                            Spacer(Modifier.height(Gap.m))
                            Text(
                                "别名（逗号分隔，把同一概念的多种写法认成一条）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Gap.xs))
                            PhoneField(
                                value = aliasDraft,
                                onValueChange = { aliasDraft = it },
                                placeholder = "传输控制协议, Transmission Control Protocol",
                                singleLine = false,
                                minLines = 2,
                            )
                        }
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton("保存", {
                                if (isCategory) {
                                    graphStore.saveUser(
                                        user.copy(
                                            categories = user.categories.map {
                                                if (it.id == selected.key) {
                                                    it.copy(description = definitionDraft.ifBlank { null })
                                                } else {
                                                    it
                                                }
                                            },
                                            updatedAt = runtime.clock.nowMs(),
                                        ),
                                    )
                                    message = "已保存分类说明"
                                } else {
                                    val okDefinition = metaStore.saveDefinition(selected.name, definitionDraft)
                                    val aliases = aliasDraft.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }
                                    val okAliases = metaStore.saveAliases(selected.name, aliases)
                                    message = if (okDefinition && okAliases) {
                                        "已保存定义"
                                    } else {
                                        "保存失败：写不进 " + metaStore.filePath
                                    }
                                }
                                refreshToken++
                                nodePanel = 0
                                selectedKey = null
                            }, modifier = Modifier.weight(1f))
                            PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.weight(1f), filled = false)
                        }
                    }

                    3 -> {
                        PanelHeading("重命名分类", "改名会保留子分类与概念归属；同名分类不允许。")
                        Spacer(Modifier.height(Gap.m))
                        PhoneField(
                            value = renameDraft,
                            onValueChange = { renameDraft = it },
                            placeholder = "分类名",
                            singleLine = true,
                            minLines = 1,
                        )
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton("保存", {
                                applyUser(renameCategory(user, selected.key, renameDraft))
                            }, modifier = Modifier.weight(1f))
                            PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.weight(1f), filled = false)
                        }
                    }

                    4 -> {
                        PanelHeading(
                            "新建子分类",
                            "挂在「" + selected.name + "」下面；分类只影响网络结构，不改任何笔记。",
                        )
                        Spacer(Modifier.height(Gap.m))
                        PhoneField(
                            value = newCategoryDraft,
                            onValueChange = { newCategoryDraft = it },
                            placeholder = "子分类名（同一层不能重名）",
                            singleLine = true,
                            minLines = 1,
                        )
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton("创建", {
                                applyUser(
                                    createCategory(
                                        user,
                                        newCategoryDraft.trim(),
                                        selected.key,
                                        runtime.clock.nowMs(),
                                    ),
                                )
                            }, modifier = Modifier.weight(1f))
                            PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.weight(1f), filled = false)
                        }
                    }

                    5 -> {
                        PanelHeading(
                            "新建上级分类",
                            "新建一个分类，把「" + selected.name + "」和它的整棵子树挪到它下面。",
                        )
                        Spacer(Modifier.height(Gap.m))
                        PhoneField(
                            value = newCategoryDraft,
                            onValueChange = { newCategoryDraft = it },
                            placeholder = "上级分类名（同一层不能重名）",
                            singleLine = true,
                            minLines = 1,
                        )
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton("创建", {
                                val before = user.categories.map { it.id }.toSet()
                                val created = createCategory(
                                    user,
                                    newCategoryDraft.trim(),
                                    null,
                                    runtime.clock.nowMs(),
                                )
                                if (created.error != null) {
                                    message = created.error
                                } else {
                                    // 新分类的 id 就是「建之前不存在、现在存在」的那一个。
                                    val newId = created.user.categories
                                        .map { it.id }
                                        .firstOrNull { it !in before }
                                    if (newId == null) {
                                        applyUser(created)
                                    } else {
                                        val moved = moveCategory(created.user, selected.key, newId)
                                        if (moved.error != null) message = moved.error else applyUser(moved)
                                    }
                                }
                            }, modifier = Modifier.weight(1f))
                            PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.weight(1f), filled = false)
                        }
                    }

                    6 -> {
                        PanelHeading(
                            "移动到分类",
                            "换一个父分类；不能移到自己或自己的子分类下面。",
                        )
                        Spacer(Modifier.height(Gap.m))
                        NodeChoice(
                            label = "顶层（成为根分类）",
                            hint = "不放进任何分类",
                            selected = selected.parentKey == null,
                            onClick = { applyUser(moveCategory(user, selected.key, null)) },
                        )
                        val blocked = categorySubtreeKeys(selected.key)
                        user.categories
                            .filter { it.id !in blocked }
                            .sortedBy { categoryPathOf(user, it.id).joinToString("/") }
                            .forEach { category ->
                                val path = categoryPathOf(user, category.id).joinToString(" / ")
                                NodeChoice(
                                    label = path.ifBlank { category.name },
                                    hint = null,
                                    selected = selected.parentKey == category.id,
                                    onClick = { applyUser(moveCategory(user, selected.key, category.id)) },
                                )
                            }
                        Spacer(Modifier.height(Gap.m))
                        PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.fillMaxWidth(), filled = false)
                    }

                    7 -> {
                        PanelHeading(
                            "删除分类",
                            "「" + selected.name + "」会消失：子分类往上移一层，分类下的概念回到「未分类」。",
                        )
                        Spacer(Modifier.height(Gap.m))
                        Text(
                            "笔记正文不会被改；这个操作不影响任何笔记内容，只改网络结构。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton("删除", {
                                applyUser(deleteCategory(user, selected.key, runtime.clock.nowMs()))
                            }, modifier = Modifier.weight(1f), danger = true)
                            PhoneButton("返回", { nodePanel = 0 }, modifier = Modifier.weight(1f), filled = false)
                        }
                    }

                    else -> {
                        Text(
                            if (isCategory) "分类" else "概念",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(Gap.xs))
                        Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                        val path = graph.pathOf(selected.key).joinToString(" / ") { it.name }
                        if (path.isNotBlank()) {
                            Spacer(Modifier.height(Gap.xs))
                            Text(
                                path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected.definition.isNotBlank()) {
                            Spacer(Modifier.height(Gap.m))
                            Text(selected.definition, style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Spacer(Modifier.height(Gap.m))
                            Text(
                                if (isCategory) "还没有说明 —— 补一句，以后翻回来才认得出。" else "还没有定义 —— 补一句，AI 整理时也会更准。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.xxl)) {
                            BigStat(selected.noteCount.toString(), "相关笔记")
                            BigStat(selected.mentionCount.toString(), "正文提及")
                        }
                        val relations = graph.relationsOf(selected.key)
                        if (relations.isNotEmpty()) {
                            Spacer(Modifier.height(Gap.m))
                            SectionLabel("关系 " + relations.size)
                            Spacer(Modifier.height(Gap.xs))
                            relations.take(8).forEach { edge ->
                                val other = graph.nodes[if (edge.fromKey == selected.key) edge.toKey else edge.fromKey]
                                Text(
                                    "· " + edgeKindLabel(edge.kind) + " " + (other?.name ?: "?"),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Spacer(Modifier.height(Gap.l))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            if (isCategory) {
                                PhoneButton("重命名", {
                                    renameDraft = selected.name
                                    nodePanel = 3
                                }, modifier = Modifier.weight(1f))
                            } else {
                                PhoneButton("改归属", { nodePanel = 1 }, modifier = Modifier.weight(1f))
                            }
                            PhoneButton("改定义", {
                                definitionDraft = if (isCategory) {
                                    selected.definition
                                } else {
                                    metaStore.definition(selected.name).orEmpty()
                                }
                                aliasDraft = if (isCategory) {
                                    ""
                                } else {
                                    metaStore.load()[dev.trellis.app.desktop.conceptIdentityKey(selected.name)]
                                        ?.aliases.orEmpty().joinToString(", ")
                                }
                                nodePanel = 2
                            }, modifier = Modifier.weight(1f), filled = false)
                        }
                        if (isCategory) {
                            Spacer(Modifier.height(Gap.s))
                            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                                PhoneButton("新建子分类", {
                                    newCategoryDraft = ""
                                    nodePanel = 4
                                }, modifier = Modifier.weight(1f), filled = false)
                                PhoneButton("新建上级", {
                                    newCategoryDraft = ""
                                    nodePanel = 5
                                }, modifier = Modifier.weight(1f), filled = false)
                            }
                            Spacer(Modifier.height(Gap.s))
                            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                                PhoneButton("移动到分类", {
                                    nodePanel = 6
                                }, modifier = Modifier.weight(1f), filled = false)
                                PhoneButton("删除分类", {
                                    nodePanel = 7
                                }, modifier = Modifier.weight(1f), filled = false, danger = true)
                            }
                        }
                        Spacer(Modifier.height(Gap.s))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton("在笔记里找", {
                                // 跳到提到这个概念的第一篇笔记：手机上「看来源」比「看关系图」更有用。
                                val hit = store.allNotes().firstOrNull { note ->
                                    note.concepts.any { it.equals(selected.name, ignoreCase = true) }
                                }
                                if (hit != null) onOpenNote(hit.id) else message = "没有笔记提到它"
                                selectedKey = null
                            }, modifier = Modifier.weight(1f), filled = false)
                            PhoneButton("关闭", { selectedKey = null }, modifier = Modifier.weight(1f), filled = false)
                        }
                    }
                }
            }
        }
    }

    // 根分类的新建：此时没有选中节点，所以单独一个 sheet（不能挂在节点详情里）。
    if (rootCreateOpen) {
        val rootSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { rootCreateOpen = false },
            sheetState = rootSheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading(
                    "新建分类",
                    "顶层分类会出现在树的根部；建好之后也可以再移动到别的分类下面。",
                )
                Spacer(Modifier.height(Gap.m))
                PhoneField(
                    value = newCategoryDraft,
                    onValueChange = { newCategoryDraft = it },
                    placeholder = "分类名（同一层不能重名）",
                    singleLine = true,
                    minLines = 1,
                )
                Spacer(Modifier.height(Gap.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    PhoneButton("创建", {
                        val result = createCategory(
                            user,
                            newCategoryDraft.trim(),
                            null,
                            runtime.clock.nowMs(),
                        )
                        if (result.error != null) {
                            message = result.error
                        } else {
                            graphStore.saveUser(result.user.copy(updatedAt = runtime.clock.nowMs()))
                            refreshToken++
                            message = result.changes.firstOrNull()
                            newCategoryDraft = ""
                            rootCreateOpen = false
                        }
                    }, modifier = Modifier.weight(1f))
                    PhoneButton("取消", {
                        newCategoryDraft = ""
                        rootCreateOpen = false
                    }, modifier = Modifier.weight(1f), filled = false)
                }
            }
        }
    }

    if (suggestionsOpen) {
        ModalBottomSheet(
            onDismissRequest = { suggestionsOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            val notes = remember(refreshToken) { store.allNotes() }
            val hashes = remember(notes) { notes.associate { it.id to noteBodyHash(it) } }
            val pending = remember(refreshToken, notes) {
                collectPendingSuggestions(
                    cache = cache,
                    user = user,
                    conceptNames = graph.nodes.filterValues { it.kind == GraphNodeKind.CONCEPT }
                        .mapValues { it.value.name },
                    noteBodyHashes = hashes,
                    globalCategoriesValid = true,
                )
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading(
                    "AI 建议",
                    "都是建议：确认才写进分类树，忽略会记一条墓碑，以后不再提同一件事",
                )
                Spacer(Modifier.height(Gap.m))
                if (pending.isEmpty) {
                    Text(
                        "现在没有待确认的建议。整理几篇笔记后，这里会出现「这个概念该归到哪」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "待确认 " + pending.total + " 条（分类 " + pending.categories.size +
                            " · 关系 " + pending.relations.size + "）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(Gap.s))
                pending.categories.take(6).forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Gap.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.conceptName, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                item.categoryPath.joinToString(" / "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "确认",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    val next = confirmCategorySuggestions(user, listOf(item), runtime.clock.nowMs())
                                    if (next.error == null) {
                                        graphStore.saveUser(next.user)
                                        graphStore.saveCache(cacheWithoutCategorySuggestions(cache, listOf(item)))
                                        refreshToken++
                                        message = "已归到 " + item.categoryPath.joinToString(" / ")
                                    } else {
                                        message = next.error ?: "这条建议不合法，已跳过"
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                        Text(
                            "忽略",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    graphStore.saveUser(ignoreCategorySuggestion(user, item))
                                    graphStore.saveCache(cacheWithoutCategorySuggestions(cache, listOf(item)))
                                    refreshToken++
                                }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Gap.m))
                PhoneButton(
                    if (organizing) "AI 归类中…" else "让 AI 给未归类的概念提建议",
                    enabled = !organizing,
                    onClick = {
                        val client = runtime.llmClientOrNull()
                        if (client == null) {
                            message = "AI 没配置：先在「我的 → 模型」导入"
                            return@PhoneButton
                        }
                        val unassigned = graph.nodes.values
                            .filter { it.kind == GraphNodeKind.CONCEPT && it.parentKey == null }
                            .map { it.key }
                        if (unassigned.isEmpty()) {
                            message = "所有概念都已经归类"
                            return@PhoneButton
                        }
                        organizing = true
                        scope.launch {
                            val run = runCatching {
                                runGlobalCategorySuggestions(
                                    client = client,
                                    graph = graph,
                                    batches = globalCategoryBatches(unassigned),
                                    existingPaths = graph.nodes.values
                                        .filter { it.kind == GraphNodeKind.CATEGORY }
                                        .map { node -> graph.pathOf(node.key).joinToString("/") { it.name } },
                                    now = { runtime.clock.nowMs() },
                                    isIgnored = { key, path ->
                                        categorySuggestionFingerprint(key, path) in
                                            user.removed.suggestions.map { it.fingerprint }
                                    },
                                )
                            }.getOrNull()
                            organizing = false
                            if (run == null) {
                                message = "AI 归类失败，不影响现有分类"
                            } else {
                                graphStore.saveCache(cache.copy(globalCategories = run.categories))
                                refreshToken++
                                message = "AI 回了 " + run.answeredCount + " 个概念，确认后才会生效"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Gap.xs))
                Text(
                    "已归类的概念不会再被建议；分类树的结构在「诊断」里能看到问题项。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (diagnosticsOpen) {
        ModalBottomSheet(
            onDismissRequest = { diagnosticsOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading("网络诊断", "分类树/归属/关系里系统能自己发现的问题")
                Spacer(Modifier.height(Gap.m))
                if (graph.diagnostics.isEmpty()) {
                    Text("没有问题项。", style = MaterialTheme.typography.bodySmall)
                }
                graph.diagnostics.take(20).forEach { item ->
                    Text(
                        "· " + item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Gap.xs))
                }
                Spacer(Modifier.height(Gap.m))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.l)) {
                    BigStat(graph.stats.classifiedCount.toString(), "已归类")
                    BigStat(graph.stats.unclassifiedCount.toString(), "未归类")
                    BigStat(graph.stats.maxDepth.toString(), "最大层深")
                    BigStat(graph.stats.droppedAssignments.toString(), "失效归属")
                }
            }
        }
    }
}

// ----------------------------------------------------------------------------- 结构分栏

private data class BoardColumnModel(
    val key: String,
    val title: String,
    val count: String,
    val items: List<GraphNode>,
)

/**
 * 把森林切成「一列一类」。
 *
 * 顶层分类各占一列；没有子节点的散概念收进最后一列 —— 让每一列里都是同一种东西，
 * 比在缩进树里数「这个点是第几层」省力。列宽固定，右边露出下一列的一角，
 * 一眼就知道还能往右滑。
 */
private fun boardColumns(graph: KnowledgeGraph): List<BoardColumnModel> {
    val columns = ArrayList<BoardColumnModel>()
    val loose = ArrayList<GraphNode>()

    graph.childrenOf(null).forEach { root ->
        val children = graph.childrenOf(root.key)
        if (children.isEmpty() && root.kind != GraphNodeKind.CATEGORY) {
            loose += root
        } else {
            columns += BoardColumnModel(
                key = root.key,
                title = root.name,
                count = graphCountLabel(root),
                items = children,
            )
        }
    }
    if (loose.isNotEmpty()) {
        columns += BoardColumnModel(
            key = "__loose",
            title = "散落概念",
            count = loose.size.toString() + " 个",
            items = loose,
        )
    }
    return columns
}

/** 一棵树：上面是根（分类），下面一行是它的叶子，朝右发散、叶子多就横滑。 */
@Composable
private fun TreeBranch(column: BoardColumnModel, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                column.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = column.key != "__loose") { onOpen(column.key) }
                    .padding(horizontal = Gap.xs, vertical = 2.dp),
            )
            Text(
                column.count,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Gap.s),
            )
        }
        Spacer(Modifier.height(Gap.xs))
        if (column.items.isEmpty()) {
            Text(
                "这一类还没有概念",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Gap.xs, vertical = 4.dp),
            )
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Gap.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                column.items.forEach { node -> LeafChip(node, onOpen) }
            }
        }
    }
}

/** 叶子：一颗小药丸，点开概念详情。 */
@Composable
private fun LeafChip(node: GraphNode, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(LocalCardColor.current)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
            .clickable { onOpen(node.key) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val label = graphCountLabel(node)
        if (label.isNotEmpty()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

private fun graphCountLabel(node: GraphNode): String = when {
    node.noteCount > 0 -> node.noteCount.toString() + " 篇"
    node.mentionCount > 0 -> node.mentionCount.toString() + " 次"
    else -> ""
}

// ----------------------------------------------------------------------------- 总览画布

/**
 * 总览：整棵树缩到一屏，只看规模与层级。
 *
 * 手机上的默认缩放给出「能看清轮廓」的下限 0.3；再小就只剩色块。
 * 想看某个节点叫什么，回结构树。
 */
@Composable
private fun GraphCanvas(graph: KnowledgeGraph, selectedKey: String?, onSelect: (String?) -> Unit) {
    var camera by remember { mutableStateOf(Camera.Default) }
    var canvasSize by remember { mutableStateOf(GraphSize(0f, 0f)) }
    val layout: GraphLayout = remember(graph) {
        layoutKnowledgeGraph(
            graph = graph,
            collapsed = emptySet(),
            warm = null,
            options = LayoutOptions(
                padding = 24f,
                nodeGapX = 16f,
                rowGapY = 44f,
                treeGapX = 64f,
                minNodeWidth = 64f,
                maxNodeWidth = 220f,
                nodeHeight = 34f,
                categoryHeight = 32f,
                conceptFontPx = 12f,
                categoryFontPx = 12.5f,
            ),
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val monoStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = PhoneMono, fontWeight = FontWeight.Medium)
    val linkColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val categoryFill = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    val nodeFill = MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val conceptText = MaterialTheme.colorScheme.onSurface

    // 用**可见节点**的实际外框，而不是 layout.bounds：
    // bounds 会带上布局留下的空行（虚拟根、换行余量），拿它居中会整体偏上。
    val contentBounds = remember(layout) {
        val boxes = layout.nodes.values.map { it.bounds }
        if (boxes.isEmpty()) {
            layout.bounds
        } else {
            dev.trellis.app.desktop.GraphRect(
                left = boxes.minOf { it.left },
                top = boxes.minOf { it.top },
                right = boxes.maxOf { it.right },
                bottom = boxes.maxOf { it.bottom },
            )
        }
    }

    /**
     * 把可见节点装进画布：等比缩放（0.3–1.1）并居中。
     *
     * 用 [size] 而不是外部状态 `canvasSize`：`onSizeChanged` 里刚写入的新尺寸
     * 要等下一次重组才读得到，直接用状态会拿上一帧（甚至 0）来算。
     */
    /**
     * 把可见节点装进画布：等比缩放（0.3–1.1）并居中。
     *
     * 用 [size] 而不是外部状态 `canvasSize`：`onSizeChanged` 里刚写入的新尺寸
     * 要等下一次重组才读得到，直接用状态会拿上一帧（甚至 0）来算。
     */
    fun fitFor(size: GraphSize): Camera {
        if (size.isEmpty || contentBounds.isEmpty) return camera
        val pad = 12f
        val scale = minOf(
            (size.width - pad * 2) / contentBounds.width,
            (size.height - pad * 2) / contentBounds.height,
        ).coerceIn(0.3f, 1.1f)
        val drawnWidth = contentBounds.width * scale
        val drawnHeight = contentBounds.height * scale
        val panX = if (drawnWidth < size.width) {
            (size.width - drawnWidth) / 2f - contentBounds.left * scale
        } else {
            pad - contentBounds.left * scale
        }
        val panY = if (drawnHeight < size.height) {
            (size.height - drawnHeight) / 2f - contentBounds.top * scale
        } else {
            pad - contentBounds.top * scale
        }
        return Camera(zoom = scale, panX = panX, panY = panY)
    }

    // 在 onSizeChanged 里算，而不是 LaunchedEffect：
    // 离屏渲染（ImageComposeScene）只跑一帧，LaunchedEffect 可能根本轮不到执行，
    // 那样相机就停在 zoom=1、pan=0 —— 内容贴在左上角，正是第一版截图的样子。
    // onSizeChanged 属于布局阶段，一定会在这一帧里发生。

    Box(
        Modifier
            .fillMaxWidth()
            .height(380.dp)
            .clipToBounds()
            .onSizeChanged { size ->
                val next = GraphSize(size.width.toFloat(), size.height.toFloat())
                canvasSize = next
                camera = fitFor(next)
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .pointerInput(layout) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = camera.panned(pan.x, pan.y)
                    camera = if (zoom != 1f) {
                        next.zoomedAt(
                            GraphPoint(size.width / 2f, size.height / 2f),
                            zoom,
                            minZoom = 0.2f,
                            maxZoom = 2.4f,
                        )
                    } else {
                        next
                    }
                }
            }
            .pointerInput(layout) {
                detectTapGestures { offset ->
                    val world = camera.screenToWorld(GraphPoint(offset.x, offset.y))
                    onSelect(hitTest(layout, world))
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            translate(camera.panX, camera.panY) {
                scale(camera.zoom, camera.zoom, pivot = Offset.Zero) {
                    layout.links.forEach { edge ->
                        val from = layout.nodes[edge.fromKey]?.bounds ?: return@forEach
                        val to = layout.nodes[edge.toKey]?.bounds ?: return@forEach
                        drawLine(
                            color = linkColor,
                            start = Offset(from.centerX, from.bottom),
                            end = Offset(to.centerX, to.top),
                            strokeWidth = 1.2f,
                        )
                    }
                    layout.nodes.forEach { (key, box) ->
                        val node = graph.nodes[key] ?: return@forEach
                        val isCategory = box.kind == GraphNodeKind.CATEGORY
                        val selected = key == selectedKey
                        drawRoundRect(
                            color = if (isCategory) categoryFill else nodeFill,
                            topLeft = Offset(box.bounds.left, box.bounds.top),
                            size = Size(box.bounds.width, box.bounds.height),
                            cornerRadius = CornerRadius(7f),
                        )
                        drawRoundRect(
                            color = if (selected) accent
                            else outlineColor.copy(alpha = if (isCategory) 0.75f else 0.5f),
                            topLeft = Offset(box.bounds.left, box.bounds.top),
                            size = Size(box.bounds.width, box.bounds.height),
                            cornerRadius = CornerRadius(7f),
                            style = Stroke(width = if (selected) 2f else 1f),
                        )
                        // 缩到名字读不出来时不画字：一堆 6px 的标签只是噪点。
                        // 放大到 0.75 以上再显示；这条阈值同时也是「总览只看轮廓」的实现。
                        if (camera.zoom >= 0.75f) {
                            drawLabel(
                                text = node.name,
                                left = box.bounds.left,
                                top = box.bounds.top,
                                width = box.bounds.width,
                                height = box.bounds.height,
                                style = monoStyle.copy(color = if (isCategory) accent else conceptText),
                                fontSizePx = if (isCategory) 12.5f else 12f,
                                measurer = textMeasurer,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Gap.xs))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "单指拖动 · 双指缩放 · 点节点看详情",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        PhoneButton("适应", { camera = fitFor(canvasSize) }, filled = false)
    }
    Spacer(Modifier.height(Gap.xs))
    Text(
        "总览只看轮廓；节点名被截断时，回「结构」看全文。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 世界坐标里命中一个节点（节点不重叠，取面积最小的即可）。 */
private fun hitTest(layout: GraphLayout, world: GraphPoint): String? = layout.nodes.values
    .filter { it.bounds.contains(world) }
    .minByOrNull { it.bounds.width * it.bounds.height }
    ?.key

private fun edgeKindLabel(kind: GraphEdgeKind): String = when (kind) {
    GraphEdgeKind.PREREQUISITE -> "前置"
    GraphEdgeKind.APPLIES -> "应用于"
    GraphEdgeKind.RELATED -> "相关"
    GraphEdgeKind.CO_OCCUR -> "共现"
}

/** 画一个居中的单行标签，超宽就省略。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLabel(
    text: String,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    style: TextStyle,
    fontSizePx: Float,
    measurer: TextMeasurer,
) {
    // 关键：layout 的字体估算用的是 **px**，绘制时如果直接写 12.sp，
    // 在 density=2/3 的手机上会变成 24/36px —— 同样的框里只放得下两个字，
    // 于是所有节点名都成了「拥…」。这里把 px 换算回 sp。
    val measured = measurer.measure(
        text = AnnotatedString(text),
        style = style.copy(fontSize = (fontSizePx / density).sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        constraints = Constraints(maxWidth = (width - 10f).toInt().coerceAtLeast(1)),
    )
    drawText(
        textLayoutResult = measured,
        topLeft = Offset(
            left + (width - measured.size.width) / 2f,
            top + (height - measured.size.height) / 2f,
        ),
    )
}

@Composable
private fun PanelHeading(title: String, hint: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Gap.xs))
    Text(
        hint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 二级面板里的一行「可选目标」。
 *
 * 不用 RadioButton：那一列在手机上会占掉半屏宽度，而且点整行比点小圆点容易得多。
 * 选中态只用左侧色条 + 底色表示，和列表其它地方一致。
 */
@Composable
private fun NodeChoice(
    label: String,
    hint: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = Gap.m, vertical = Gap.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) {
            Text(
                "当前",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        } else if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
