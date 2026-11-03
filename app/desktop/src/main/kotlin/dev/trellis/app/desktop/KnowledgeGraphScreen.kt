package dev.trellis.app.desktop

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.ui.LocalExtraColors

/**
 * 知识库「网络」视图（G0）。
 *
 * 数据链路（设计 §8.4）：每次进入/数据变更时现算——
 * `graph.user.json` + `graph.cache.json` + 全部笔记 + `concepts.json`
 * → [buildKnowledgeGraph] → [layoutKnowledgeGraph]（warm 读缓存的根顺序）→ Canvas。
 *
 * 这里只管「怎么看」：分类树默认只画所属关系，点节点聚焦时才在画布上临时画它的直接关联；
 * 右栏给定义、所属路径与直接关联，可改名/移动/合并/删除分类、可跳回笔记。
 *
 * 视图不改项目数据：分类/归属写 `graph.user.json`，概念定义写一期 `concepts.json`，
 * 笔记文件永远只读（跳转由 [onOpenNote] 交给知识库页处理）。
 */
@Composable
internal fun KnowledgeGraphView(
    runtime: AppRuntime,
    notesVersion: Int,
    modifier: Modifier = Modifier,
    onOpenNote: (noteId: String, start: Int, end: Int) -> Unit,
) {
    val store = runtime.knowledgeStore
    val metaStore = remember(runtime) { ConceptMetaStore(runtime.home) }
    val graphStore = remember(store) { KnowledgeGraphStore(store.home, runtime.clock) }
    val view = KnowledgeGraphViewState

    var user by remember { mutableStateOf(graphStore.loadUser()) }
    var cache by remember { mutableStateOf(graphStore.loadCache()) }
    var dataVersion by remember { mutableStateOf(0) }
    var canvasSize by remember { mutableStateOf<GraphSize?>(null) }
    var fitRequests by remember { mutableStateOf(0) }
    var fittedFingerprint by remember { mutableStateOf<Long?>(null) }
    var fittedFitRequests by remember { mutableStateOf(-1) }
    var newCategoryParent by remember { mutableStateOf<String?>(null) }
    var creatingRootCategory by remember { mutableStateOf(false) }

    // 注意：读正文用于派生关系，notesVersion 变化（新建/删除/保存）或 dataVersion 自增时重读。
    val notes = remember(notesVersion, dataVersion) { store.allNotes() }
    val meta = remember(notesVersion, dataVersion) { metaStore.load() }
    val identities = remember(notes, meta) { graphIdentities(meta, notes) }

    val graph = remember(notes, meta, user, cache, dataVersion) {
        buildKnowledgeGraph(notes, meta, user, cache)
    }
    val fingerprint = remember(graph) { layoutFingerprint(graph) }
    val effectiveCollapsed = remember(graph, view.collapseByDefault, view.expandDepth, view.collapsed, view.showUnclassified) {
        graphEffectiveCollapsed(graph, view.collapseByDefault, view.expandDepth, view.collapsed, view.showUnclassified)
    }
    val layout = remember(graph, effectiveCollapsed, cache) {
        layoutKnowledgeGraph(graph, effectiveCollapsed, cache?.layout)
    }

    // 相机自动 fit：首次拿到画布尺寸、或图的指纹变了（分类结构变化）时重新适应窗口。
    LaunchedEffect(layout, canvasSize, fitRequests) {
        val size = canvasSize ?: return@LaunchedEffect
        if (graph.nodes.isEmpty()) return@LaunchedEffect
        if (fingerprint != fittedFingerprint || fitRequests != fittedFitRequests) {
            view.camera = layout.zoomToFit(layout.bounds, size, padding = 40f, maxZoom = 1f)
            fittedFingerprint = fingerprint
            fittedFitRequests = fitRequests
        }
    }

    // 布局缓存（可删的加速信息，不是真相）：只复用根块顺序，坐标每次重算。
    LaunchedEffect(layout) {
        if (layout.roots.isEmpty()) return@LaunchedEffect
        val fresh = graphStore.loadCache()
        val saved = LayoutCache(
            fingerprint = fingerprint,
            rootOrder = layout.roots.map { it.categoryKey },
            anchors = layout.boxesByKey
                .filterValues { it.chips.isNotEmpty() }
                .mapValues { it.value.bounds.centerPoint() },
        )
        if (fresh.layout != saved) graphStore.saveCache(fresh.copy(layout = saved))
    }

    fun persist(result: TaxonomyResult) {
        val error = result.error
        if (error != null) {
            view.message = error
            return
        }
        user = result.user
        graphStore.saveUser(result.user)
        view.requestRefresh(result.changes.firstOrNull())
    }

    fun selectNode(key: String) {
        view.selectedKey = key
        view.message = null
    }

    fun locateNode(key: String) {
        val node = graph.nodes[key] ?: return
        if (node.kind != GraphNodeKind.CONCEPT) {
            view.message = "分类节点没有可定位的笔记；点它下面的概念。"
            return
        }
        val detail = store.conceptDetail(node.name, notes, identities)
        val mention = detail?.mentions?.firstOrNull() ?: detail?.others?.firstOrNull()
        if (mention == null) {
            view.message = "「" + node.name + "」正文里还没有标注，先去笔记页把它写进正文。"
            return
        }
        onOpenNote(mention.noteId, mention.start, mention.end)
    }

    fun saveDefinition(conceptName: String, definition: String) {
        metaStore.saveDefinition(conceptName, definition)
        dataVersion++
        view.message = if (definition.isBlank()) {
            "已清空「" + conceptName + "」的定义"
        } else {
            "已保存「" + conceptName + "」的定义"
        }
    }

    val extra = LocalExtraColors.current

    if (graph.stats.conceptCount == 0) {
        KnowledgeGraphEmptyState(
            hasNotes = notes.isNotEmpty(),
            hasConcepts = false,
            message = view.message,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        // 页头工具条（设计 §5.3）：分类树 / 展开折叠 / 适应窗口 / 新建分类 / 体系视图（G2）。
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 20.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KnowledgeGraphToolbar(
                onExpandAll = {
                    view.collapseByDefault = false
                    view.collapsed = emptySet()
                    view.expandDepth = 5
                },
                onCollapseAll = {
                    view.collapseByDefault = false
                    view.collapsed = graph.nodes.values
                        .filter { it.kind == GraphNodeKind.CATEGORY }
                        .mapTo(LinkedHashSet()) { it.key }
                },
                onFit = { fitRequests++ },
                onNewCategory = {
                    newCategoryParent = null
                    creatingRootCategory = true
                },
            )
            Spacer(Modifier.weight(1f))
            val message = view.message
            if (message != null) {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = extra.dataMuted,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // 左栏：筛选 / 图例 / 统计 / AI 入口（G0 不调模型）。可滚动，窄窗口不会挤爆。
            Column(
                Modifier.width(264.dp).fillMaxHeight().padding(start = 18.dp, top = 10.dp, end = 8.dp),
            ) {
                // 左栏面板自己带 verticalScroll：外面只能给有界高度，不能再套一层滚动。
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    KnowledgeGraphLeftPanel(
                        graph = graph,
                        aiBusy = false,
                        message = view.message,
                        onQueryChange = { view.query = it },
                        onNodeClick = { selectNode(it) },
                        onShowUnclassified = { view.showUnclassified = it },
                        onCollapseByDefault = { view.collapseByDefault = it },
                        onExpandDepth = { view.expandDepth = it },
                        onShowRelationsOnFocus = { view.showRelationsOnFocus = it },
                        onShowCoOccurrence = { view.showCoOccurrence = it },
                        onNewCategory = {
                            newCategoryParent = null
                            creatingRootCategory = true
                        },
                        onAiSuggest = null,
                    )
                }
                // 统计卡已由 KnowledgeGraphLeftPanel 内部渲染（滚动区里），这里不再重复。
            }
            Spacer(Modifier.width(8.dp))
            // 主画布。
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    KnowledgeGraphCanvas(
                        graph = graph,
                        layout = layout,
                        camera = view.camera,
                        selectedKey = view.selectedKey,
                        showCoOccurrence = view.showCoOccurrence,
                        modifier = Modifier.fillMaxSize(),
                        onCameraChange = { view.camera = it },
                        onCanvasSize = { canvasSize = it },
                        onChipClick = { selectNode(it) },
                        onCategoryClick = { key ->
                            if (view.selectedKey == key) {
                                // 再点一下分类标题 = 折叠/展开（画布标题行本身不画折叠箭头，G1 补）。
                                view.collapsed = if (key in view.collapsed) {
                                    view.collapsed - key
                                } else {
                                    view.collapsed + key
                                }
                                view.collapseByDefault = false
                            } else {
                                selectNode(key)
                            }
                        },
                        onBackgroundClick = { view.selectedKey = null },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            // 右栏：节点详情；没选中时给一句话引导。
            Column(
                Modifier.width(320.dp).fillMaxHeight().padding(end = 20.dp, top = 10.dp, bottom = 10.dp),
            ) {
                // 详情面板内部是 verticalScroll：必须给有界高度，否则 Column 会以无限高去量它。
                Box(Modifier.weight(1f).fillMaxWidth()) {
                KnowledgeGraphDetailPanel(
                    graph = graph,
                    selectedKey = view.selectedKey,
                    onClose = { view.selectedKey = null },
                    onNodeClick = { selectNode(it) },
                    onLocateNote = { locateNode(it) },
                    onAssignCategory = { conceptKey, categoryId ->
                        persist(assignConcept(user, conceptKey, categoryId))
                    },
                    onCreateCategory = { name, parentId ->
                        persist(
                            createCategory(
                                user = user,
                                name = name,
                                parentId = parentId,
                                now = runtime.clock.nowMs(),
                                id = graphStore.newCategoryId(),
                            ),
                        )
                        newCategoryParent = null
                        creatingRootCategory = false
                    },
                    onRenameCategory = { id, name -> persist(renameCategory(user, id, name)) },
                    onDeleteCategory = { id -> persist(deleteCategory(user, id, runtime.clock.nowMs())) },
                    onMergeCategory = { fromId, intoId ->
                        persist(mergeCategories(user, fromId, intoId, runtime.clock.nowMs()))
                    },
                    onSaveDefinition = { name, definition -> saveDefinition(name, definition) },
                )
                }
            }
        }
    }

    if (creatingRootCategory) {
        NewCategoryDialog(
            parent = null,
            onDismiss = { creatingRootCategory = false },
            onConfirm = { name ->
                creatingRootCategory = false
                persist(
                    createCategory(
                        user = user,
                        name = name,
                        parentId = newCategoryParent,
                        now = runtime.clock.nowMs(),
                        id = graphStore.newCategoryId(),
                    ),
                )
            },
        )
    }
}

/**
 * 分类树的有效折叠集合：分类默认折叠时按「只有前 N 层展开」算；
 * 关掉「显示未分类」时把未分类框也折叠起来（布局层会保留标题行，画布上仍可见可展开）。
 */
private fun graphEffectiveCollapsed(
    graph: KnowledgeGraph,
    collapseByDefault: Boolean,
    expandDepth: Int,
    collapsed: Set<String>,
    showUnclassified: Boolean,
): Set<String> {
    val result = LinkedHashSet<String>(collapsed)
    if (collapseByDefault) {
        val depth = expandDepth.coerceIn(1, 5)
        for (node in graph.nodes.values) {
            if (node.kind != GraphNodeKind.CATEGORY) continue
            if (graph.pathOf(node.key).size > depth) result.add(node.key)
        }
    }
    if (!showUnclassified) {
        for (child in graph.childrenOf(null)) {
            if (child.kind == GraphNodeKind.CONCEPT) {
                result.add(GRAPH_UNCLASSIFIED_KEY)
                break
            }
        }
    }
    if (graph.stats.conceptCount > 800) {
        for (node in graph.nodes.values) {
            if (node.kind == GraphNodeKind.CATEGORY && graph.pathOf(node.key).size > 1) {
                result.add(node.key)
            }
        }
    }
    return result
}

/** 概念身份表 + 一期索引里出现过的写法（正文标注 / 别名解析都用它）。 */
private fun graphIdentities(meta: Map<String, ConceptMeta>, notes: List<KnowledgeNote>): List<ConceptIdentity> {
    val known = LinkedHashMap<String, ConceptIdentity>()
    for (entry in meta.values.sortedBy { it.name }) {
        if (isAcceptableConceptName(entry.name)) {
            known.putIfAbsent(conceptIdentityKey(entry.name), ConceptIdentity(entry.name, entry.aliases))
        }
    }
    for (note in notes.sortedBy { it.id }) {
        for (name in note.concepts) {
            if (isAcceptableConceptName(name)) {
                known.putIfAbsent(conceptIdentityKey(name), ConceptIdentity(name))
            }
        }
    }
    return known.values.toList()
}

/** 新建根分类的小对话框（分类子节点的新建由右栏面板自己弹）。 */
@Composable
private fun NewCategoryDialog(
    parent: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (parent == null) "新建根分类" else "新建子分类") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("分类名") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
