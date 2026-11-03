package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 知识网络视图（设计 v1.2：简单的「虚实树 + 聚焦浮窗」）。
 *
 * - 画布上只有节点和父子连线（分类虚节点、概念实节点），每棵根分类一棵树；
 * - 点节点：其它节点淡出、选中节点放大、旁边弹出小浮窗（定义 / 来源 / 直接关联 / 操作）；
 * - 分类深度不预制，由 AI 归类（G1）决定；G0 只做手动分类。
 */
@Composable
internal fun KnowledgeGraphView(
    runtime: AppRuntime,
    notesVersion: Int,
    modifier: Modifier = Modifier,
    onOpenNote: (noteId: String, start: Int, end: Int) -> Unit,
) {
    val home = runtime.knowledgeStore.home
    val knowledgeStore = remember { KnowledgeStore(home, runtime.clock) }
    val metaStore = remember { ConceptMetaStore(home) }
    val graphStore = remember { KnowledgeGraphStore(home, runtime.clock) }

    var user by remember { mutableStateOf(graphStore.loadUser()) }
    var cache by remember { mutableStateOf(graphStore.loadCache()) }
    var dataVersion by remember { mutableStateOf(0) }
    var canvasSize by remember { mutableStateOf(GraphSize.Zero) }
    // 新建虚节点后把它带到视野中央（否则新节点可能在视野外，用户找不到）。
    var centerKey by remember { mutableStateOf<String?>(null) }

    // 外部触发刷新（比如从笔记页回来、手动刷新）。
    LaunchedEffect(KnowledgeGraphViewState.refreshToken) {
        user = graphStore.loadUser()
        cache = graphStore.loadCache()
        dataVersion++
    }

    val notes = remember(notesVersion, dataVersion, KnowledgeGraphViewState.refreshToken) {
        knowledgeStore.allNotes()
    }
    val meta = remember(dataVersion) { metaStore.load() }
    val identities = remember(meta) { meta.values.map { ConceptIdentity(it.name, it.aliases) } }
    val graph = remember(notes, meta, user, cache) {
        buildKnowledgeGraph(notes, meta, user, cache)
    }
    val fingerprint = remember(graph) { layoutFingerprint(graph) }

    val collapsed = remember(KnowledgeGraphViewState.collapsed, KnowledgeGraphViewState.showUnclassified) {
        if (KnowledgeGraphViewState.showUnclassified) {
            KnowledgeGraphViewState.collapsed
        } else {
            KnowledgeGraphViewState.collapsed + GRAPH_UNCLASSIFIED_KEY
        }
    }
    // 画布世界坐标单位是 px；布局里的字号/尺寸按 density 放大，
    // 才能和 drawText 的 12sp（px = sp × density）对齐，否则中文会被截成一两个字。
    val density = LocalDensity.current.density
    val layoutOptions = remember(density) {
        LayoutOptions(
            padding = 32f * density,
            nodeGapX = 18f * density,
            rowGapY = 44f * density,
            treeGapX = 72f * density,
            minNodeWidth = 64f * density,
            maxNodeWidth = 260f * density,
            nodeHeight = 30f * density,
            categoryHeight = 28f * density,
            conceptFontPx = 12f * density,
            categoryFontPx = 12.5f * density,
            maxCanvasWidth = 3000f * density,
        )
    }
    val layout = remember(graph, collapsed, cache.layout, layoutOptions) {
        layoutKnowledgeGraph(graph, collapsed, cache.layout, layoutOptions)
    }

    // 图变了 / 点了「适应窗口」：重新 fit 相机。
    var lastFittedFingerprint by remember { mutableStateOf<Long?>(null) }
    var lastFitRequest by remember { mutableStateOf(-1) }
    LaunchedEffect(fingerprint, canvasSize, density, KnowledgeGraphViewState.fitRequests) {
        if (canvasSize.isEmpty) return@LaunchedEffect
        val request = KnowledgeGraphViewState.fitRequests
        if (lastFittedFingerprint != fingerprint || request != lastFitRequest) {
            KnowledgeGraphViewState.camera = fitCamera(layout.bounds, canvasSize, padding = 32f * density)
            lastFittedFingerprint = fingerprint
            lastFitRequest = request
        }
    }

    // 新建的虚节点：布局出来之后把相机挪到它身上。
    LaunchedEffect(centerKey, layout, canvasSize) {
        val key = centerKey ?: return@LaunchedEffect
        if (canvasSize.isEmpty) return@LaunchedEffect
        val box = layout.nodes[key] ?: return@LaunchedEffect
        val zoom = KnowledgeGraphViewState.camera.zoom.coerceIn(0.9f, 1.5f)
        val center = box.bounds.center
        KnowledgeGraphViewState.camera = Camera(
            zoom = zoom,
            panX = canvasSize.width / 2f - center.x * zoom,
            panY = canvasSize.height / 2f - center.y * zoom,
        )
        centerKey = null
    }

    // 布局稳定后把指纹/根序/锚点写进缓存（可删，下次打开直接 warm）。
    LaunchedEffect(layout, fingerprint) {
        if (layout.nodes.isEmpty()) return@LaunchedEffect
        if (cache.layout?.fingerprint == fingerprint) return@LaunchedEffect
        val next = cache.copy(
            layout = LayoutCache(
                fingerprint = fingerprint,
                rootOrder = layout.roots,
                anchors = layout.nodes.mapValues { (_, box) -> box.bounds.center },
            ),
        )
        cache = next
        graphStore.saveCache(next)
    }

    val selectedNode = KnowledgeGraphViewState.selectedKey?.let { graph.nodes[it] }
    val selectedKey = selectedNode?.key

    // 选中概念时准备浮窗里的来源片段（先预览、点一条再跳原文）。
    val sources = remember(selectedKey, dataVersion, notes) {
        val node = selectedKey?.let { graph.nodes[it] } ?: return@remember emptyList()
        if (node.kind != GraphNodeKind.CONCEPT) return@remember emptyList()
        val detail = knowledgeStore.conceptDetail(node.name, notes, identities)
            ?: return@remember emptyList()
        (detail.mentions + detail.others).distinctBy { "${it.noteId}:${it.start}:${it.end}" }
    }

    fun persist(result: TaxonomyResult) {
        if (result.error != null) {
            KnowledgeGraphViewState.message = result.error
            return
        }
        user = result.user
        graphStore.saveUser(user)
        KnowledgeGraphViewState.message = result.changes.firstOrNull()
        dataVersion++
    }

    // ---- G1：AI 建议（归类 + 关系） ----
    // 建议来自两处：①笔记整理时搭便车写进 graph.cache.json 的 noteSuggestions；
    // ②用户点「AI 整理分类」后分批对未归类概念跑出来的 globalCategories。
    // 确认前不碰分类树；忽略会写墓碑，AI 不会再提同一条。
    val scope = rememberCoroutineScope()
    var aiBusy by remember { mutableStateOf(false) }
    var aiProgress by remember { mutableStateOf<String?>(null) }
    var aiConfirmBatches by remember { mutableStateOf<List<List<String>>?>(null) }

    val noteBodyHashes = remember(notes) { notes.associate { it.id to noteBodyHash(it) } }
    val conceptNamesByKey = remember(graph) {
        graph.nodes.values
            .filter { it.kind == GraphNodeKind.CONCEPT }
            .associate { it.key to it.name }
    }
    val globalSuggestionsValid = remember(graph, cache) {
        val hash = cache.globalCategories.conceptSetHash
        hash.isNotEmpty() && hash == knowledgeConceptSetHash(graph)
    }
    val pendingSuggestions = remember(cache, user, conceptNamesByKey, noteBodyHashes, globalSuggestionsValid) {
        collectPendingSuggestions(
            cache = cache,
            user = user,
            conceptNames = conceptNamesByKey,
            noteBodyHashes = noteBodyHashes,
            globalCategoriesValid = globalSuggestionsValid,
        )
    }
    val unclassifiedConcepts = remember(graph) {
        graph.childrenOf(null).filter { it.kind == GraphNodeKind.CONCEPT }
    }
    val llmReady = remember(runtime) { runtime.llmClientOrNull() != null }
    val aiHint = when {
        aiBusy -> aiProgress
        !llmReady -> "AI 没配置：先去设置页导入模型；也可以手动拖节点整理。"
        unclassifiedConcepts.isEmpty() && pendingSuggestions.isEmpty ->
            "没有未归类的概念。整理笔记时会顺带产生归类 / 关系建议，回笔记页写几句再回来。"
        else -> null
    }
    val aiEnabled = llmReady && unclassifiedConcepts.isNotEmpty() && !aiBusy

    fun saveCacheAndRefresh(next: GraphAiCache) {
        cache = next
        graphStore.saveCache(next)
        dataVersion++
    }

    fun confirmCategories(items: List<PendingCategorySuggestion>) {
        if (items.isEmpty()) return
        val result = confirmCategorySuggestions(user, items, runtime.clock.nowMs())
        if (result.error != null) {
            KnowledgeGraphViewState.message = result.error
            return
        }
        user = result.user
        graphStore.saveUser(user)
        saveCacheAndRefresh(cacheWithoutCategorySuggestions(cache, items))
        KnowledgeGraphViewState.message = result.changes.firstOrNull() ?: "已确认 ${items.size} 条归类建议"
    }

    fun ignoreCategories(items: List<PendingCategorySuggestion>) {
        if (items.isEmpty()) return
        var next = user
        items.forEach { next = ignoreCategorySuggestion(next, it) }
        user = next
        graphStore.saveUser(user)
        saveCacheAndRefresh(cacheWithoutCategorySuggestions(cache, items))
        KnowledgeGraphViewState.message = "已忽略 ${items.size} 条归类建议（同类建议不会再出现）"
    }

    fun confirmRelations(items: List<PendingRelationSuggestion>) {
        if (items.isEmpty()) return
        user = confirmRelationSuggestions(user, items, runtime.clock.nowMs())
        graphStore.saveUser(user)
        saveCacheAndRefresh(cacheWithoutRelationSuggestions(cache, items))
        KnowledgeGraphViewState.message = "已确认 ${items.size} 条关系建议"
    }

    fun ignoreRelations(items: List<PendingRelationSuggestion>) {
        if (items.isEmpty()) return
        var next = user
        items.forEach { next = ignoreRelationSuggestion(next, it) }
        user = next
        graphStore.saveUser(user)
        saveCacheAndRefresh(cacheWithoutRelationSuggestions(cache, items))
        KnowledgeGraphViewState.message = "已忽略 ${items.size} 条关系建议（同类建议不会再出现）"
    }

    fun reverseRelation(item: PendingRelationSuggestion) {
        val reversed = item.copy(
            fromKey = item.toKey,
            toKey = item.fromKey,
            fromName = item.toName,
            toName = item.fromName,
            fingerprint = relationSuggestionFingerprint(item.toKey, item.fromKey, item.kind),
        )
        user = confirmRelationSuggestions(user, listOf(reversed), runtime.clock.nowMs())
        graphStore.saveUser(user)
        saveCacheAndRefresh(cacheWithoutRelationSuggestions(cache, listOf(item)))
        KnowledgeGraphViewState.message = "已反向确认一条关系建议"
    }

    fun startAiOrganize(batches: List<List<String>>) {
        val client = runtime.llmClientOrNull()
        if (client == null) {
            KnowledgeGraphViewState.message = "AI 没配置：先去设置页导入模型。"
            return
        }
        val existingPaths = graph.nodes.values
            .filter { it.kind == GraphNodeKind.CATEGORY }
            .map { category -> graph.pathOf(category.key).joinToString(" / ") { it.name } }
            .distinct()
            .sorted()
        aiBusy = true
        aiProgress = "准备中…"
        scope.launch {
            try {
                val run = runGlobalCategorySuggestions(
                    client = client,
                    graph = graph,
                    batches = batches,
                    existingPaths = existingPaths,
                    now = { runtime.clock.nowMs() },
                    isIgnored = { conceptKey, path ->
                        isCategorySuggestionIgnored(user.removed.suggestions, conceptKey, path)
                    },
                    onProgress = { aiProgress = it },
                )
                val next = cache.copy(
                    globalCategories = run.categories,
                    promptVersion = GRAPH_PROMPT_VERSION,
                    updatedAt = runtime.clock.nowMs(),
                )
                saveCacheAndRefresh(next)
                KnowledgeGraphViewState.suggestionsOpen = true
                val failedNote = if (run.failedCount > 0) "，${run.failedCount} 个概念没给建议" else ""
                KnowledgeGraphViewState.message =
                    "AI 建议已生成：${run.answeredCount} 条（共 ${run.callCount} 批$failedNote；你确认后才写入分类树）"
            } finally {
                aiBusy = false
                aiProgress = null
                aiConfirmBatches = null
            }
        }
    }

    fun requestAiOrganize() {
        if (!llmReady) {
            KnowledgeGraphViewState.message = "AI 没配置：先去设置页导入模型。"
            return
        }
        val keys = unclassifiedConcepts.map { it.key }
        if (keys.isEmpty()) {
            KnowledgeGraphViewState.message = "没有未归类的概念；AI 建议会在整理笔记时自动出现。"
            return
        }
        aiConfirmBatches = globalCategoryBatches(keys)
    }

    // v1.3：新建一个「虚节点」——先造一个空分类，再拖到某个节点上把它收为子节点。
    fun addVirtualNode() {
        val used = user.categories.map { it.name }.toSet()
        var name = "新分类"
        var index = 2
        while (name in used) {
            name = "新分类 $index"
            index++
        }
        val before = user.categories.map { it.id }.toSet()
        val result = createCategory(user, name, null, runtime.clock.nowMs())
        persist(result)
        if (result.error != null) return
        val created = result.user.categories.firstOrNull { it.id !in before } ?: return
        KnowledgeGraphViewState.selectedKey = created.id
        centerKey = created.id
        KnowledgeGraphViewState.message =
            "已添加虚节点「${created.name}」：把它拖到某个节点上，那个节点就会收进来；浮窗里可改名。"
    }

    // 新建 / 重命名 / 删除分类的对话框状态。
    var createParent by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<GraphNode?>(null) }
    var deleteTarget by remember { mutableStateOf<GraphNode?>(null) }
    // 「新建上级分类」：先创建根分类，再把当前分类挂进去。
    var newParentTarget by remember { mutableStateOf<GraphNode?>(null) }

    val categories: List<Pair<String, String>> = remember(graph) {
        graph.nodes.values
            .filter { it.kind == GraphNodeKind.CATEGORY }
            .sortedWith(compareBy({ graph.pathOf(it.key).size }, { it.name }))
            .map { category ->
                val path = graph.pathOf(category.key).joinToString(" / ") { it.name }
                category.key to path
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        KnowledgeGraphToolbar(
            onExpandAll = { KnowledgeGraphViewState.collapsed = emptySet() },
            onCollapseAll = {
                KnowledgeGraphViewState.collapsed = graph.childrenOf(null)
                    .filter { it.kind == GraphNodeKind.CATEGORY }
                    .map { it.key }
                    .toSet()
            },
            onFit = { KnowledgeGraphViewState.fitReset() },
            onNewVirtualNode = { addVirtualNode() },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(268.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp)) {
                KnowledgeGraphLeftPanel(
                    graph = graph,
                    query = KnowledgeGraphViewState.query,
                    message = KnowledgeGraphViewState.message,
                    showUnclassified = KnowledgeGraphViewState.showUnclassified,
                    onQueryChange = { KnowledgeGraphViewState.query = it },
                    onNodeClick = { key ->
                        KnowledgeGraphViewState.expandTo(key, graph)
                        KnowledgeGraphViewState.selectedKey = key
                    },
                    onShowUnclassified = { KnowledgeGraphViewState.showUnclassified = it },
                    aiEnabled = aiEnabled,
                    aiBusy = aiBusy,
                    aiHint = aiHint,
                    suggestionCount = pendingSuggestions.total,
                    onAiOrganize = { requestAiOrganize() },
                    onOpenSuggestions = { KnowledgeGraphViewState.suggestionsOpen = true },
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (graph.nodes.isEmpty()) {
                    KnowledgeGraphEmptyState(
                        hasNotes = notes.isNotEmpty(),
                        hasConcepts = graph.nodes.values.any { it.kind == GraphNodeKind.CONCEPT },
                        message = KnowledgeGraphViewState.message,
                    )
                } else {
                    KnowledgeGraphCanvas(
                        graph = graph,
                        layout = layout,
                        camera = KnowledgeGraphViewState.camera,
                        selectedKey = selectedKey,
                        modifier = Modifier.fillMaxSize(),
                        onCameraChange = { KnowledgeGraphViewState.camera = it },
                        onCanvasSize = { canvasSize = it },
                        onNodeClick = { key ->
                            KnowledgeGraphViewState.expandTo(key, graph)
                            KnowledgeGraphViewState.selectedKey = key
                        },
                        onCategoryToggle = { key -> KnowledgeGraphViewState.toggleFolded(key) },
                        onBackgroundClick = { KnowledgeGraphViewState.clearFocus() },
                        onLinkDrop = { draggedKey, targetKey ->
                            val dragged = graph.nodes[draggedKey]
                            val target = graph.nodes[targetKey]
                            if (dragged != null && target != null && dragged.kind == GraphNodeKind.CATEGORY) {
                                val result = if (target.kind == GraphNodeKind.CONCEPT) {
                                    assignConcept(user, target.key, dragged.key)
                                } else {
                                    moveCategory(user, target.key, dragged.key)
                                }
                                persist(result)
                                if (result.error == null) {
                                    KnowledgeGraphViewState.selectedKey = dragged.key
                                    KnowledgeGraphViewState.message =
                                        "已把「${target.name}」收进「${dragged.name}」"
                                }
                            }
                        },
                    )

                    val anchor = selectedKey?.let { key ->
                        popoverAnchor(layout, key, KnowledgeGraphViewState.camera)
                    }
                    if (selectedNode != null && anchor != null) {
                        KnowledgeGraphPopover(
                            graph = graph,
                            node = selectedNode,
                            anchor = anchor,
                            viewport = canvasSize,
                            sources = sources,
                            categories = categories,
                            onClose = { KnowledgeGraphViewState.clearFocus() },
                            onSelectNode = { key ->
                                KnowledgeGraphViewState.expandTo(key, graph)
                                KnowledgeGraphViewState.selectedKey = key
                            },
                            onLocate = { noteId, start, end -> onOpenNote(noteId, start, end) },
                            onSaveDefinition = { name, definition ->
                                metaStore.saveDefinition(name, definition)
                                dataVersion++
                                KnowledgeGraphViewState.message = "已保存「$name」的定义"
                            },
                            onMoveToCategory = { conceptKey, categoryId ->
                                persist(assignConcept(user, conceptKey, categoryId))
                            },
                            onNewSubcategory = { parentId -> createParent = parentId },
                            onNewParentCategory = { categoryId -> newParentTarget = graph.nodes[categoryId] },
                            onMoveCategory = { categoryId, newParentId ->
                                persist(moveCategory(user, categoryId, newParentId))
                            },
                            onRenameCategory = { categoryId -> renameTarget = graph.nodes[categoryId] },
                            onDeleteCategory = { categoryId -> deleteTarget = graph.nodes[categoryId] },
                        )
                    }

                    if (KnowledgeGraphViewState.suggestionsOpen) {
                        KnowledgeGraphSuggestionPanel(
                            pending = pendingSuggestions,
                            busy = aiBusy,
                            message = aiProgress,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 52.dp, end = 12.dp),
                            onClose = { KnowledgeGraphViewState.suggestionsOpen = false },
                            onConfirmCategory = { confirmCategories(listOf(it)) },
                            onIgnoreCategory = { ignoreCategories(listOf(it)) },
                            onConfirmRelation = { confirmRelations(listOf(it)) },
                            onReverseRelation = { reverseRelation(it) },
                            onIgnoreRelation = { ignoreRelations(listOf(it)) },
                            onConfirmAll = { confirmCategories(pendingSuggestions.categories) },
                            onIgnoreAll = {
                                ignoreCategories(pendingSuggestions.categories)
                                ignoreRelations(pendingSuggestions.relations)
                            },
                        )
                    }

                    // 图例贴在画布右上角（v1.3：从左栏挪过来，左栏留给搜索和统计）。
                    KnowledgeGraphLegend(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    )
                }
            }
        }
    }

    // ---- 对话框 ----
    val confirmBatches = aiConfirmBatches
    if (confirmBatches != null) {
        val total = unclassifiedConcepts.size
        AlertDialog(
            onDismissRequest = { aiConfirmBatches = null },
            title = { Text("AI 整理分类？") },
            text = {
                Text(
                    "将把 $total 个未归类概念分 ${confirmBatches.size} 批交给模型" +
                        "（每批最多 $MAX_GLOBAL_BATCH_CONCEPTS 个）。只会生成建议，不会直接改分类树；" +
                        "你逐条确认后才写入。每次调用有成本。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val batches = confirmBatches
                    aiConfirmBatches = null
                    startAiOrganize(batches)
                }) { Text("开始") }
            },
            dismissButton = { TextButton(onClick = { aiConfirmBatches = null }) { Text("取消") } },
        )
    }

    val createTarget = createParent
    if (createTarget != null) {
        val parentId = createTarget
        GraphTextInputDialog(
            title = "新建子分类",
            label = "分类名",
            confirmLabel = "创建",
            initial = "",
            onDismiss = { createParent = null },
            onConfirm = { name ->
                createParent = null
                persist(createCategory(user, name, parentId, runtime.clock.nowMs()))
            },
        )
    }
    val newParent = newParentTarget
    if (newParent != null) {
        GraphTextInputDialog(
            title = "新建上级分类",
            label = "分类名（例如「RAG 系统」）",
            confirmLabel = "创建并移入",
            initial = "",
            onDismiss = { newParentTarget = null },
            onConfirm = { name ->
                newParentTarget = null
                val before = user.categories.map { it.id }.toSet()
                val created = createCategory(user, name, null, runtime.clock.nowMs())
                if (created.error != null) {
                    persist(created)
                } else {
                    val newId = created.user.categories.firstOrNull { it.id !in before }?.id
                    if (newId == null) {
                        persist(created)
                    } else {
                        persist(moveCategory(created.user, newParent.key, newId))
                    }
                }
            },
        )
    }
    val rename = renameTarget
    if (rename != null) {
        GraphTextInputDialog(
            title = "重命名分类",
            label = "新名字",
            confirmLabel = "保存",
            initial = rename.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                persist(renameCategory(user, rename.key, name))
            },
        )
    }
    val delete = deleteTarget
    if (delete != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除分类「${delete.name}」？") },
            text = { Text("子分类会往上移一层，分类下的概念回到「未分类」；笔记正文不会被改。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    persist(deleteCategory(user, delete.key, runtime.clock.nowMs()))
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}
