package dev.trellis.app.desktop

/**
 * 知识网络（分类树 + 聚焦关系）的数据模型与值类型。
 *
 * 设计见 `docs/knowledge-graph-design.md`（v1.1）。这一层只放**纯数据**：
 * - 不 import Compose、不碰文件、不碰时钟——几何用自有的 [GraphPoint]/[GraphSize]/[GraphRect]，
 *   这样布局/构建逻辑可以在普通 JVM 单元测试里跑（仓里没有 Compose UI test）；
 * - 概念节点的 key 沿用一期的「概念身份键」[conceptIdentityKey]（比 `conceptKey` 多忽略空白），
 *   分类节点的 id 是 `c_` + 8 位十六进制；两者在同一个 `nodes` 表里，用 [GraphNode.kind] 区分；
 * - 分类树是**父子归属**（`parentKey`），不是关系边；关系边只表达前置/应用/相关/共现，
 *   默认不画，只在聚焦某个概念时临时展开（见设计 §3.3/§4.4）。
 */

/**
 * 知识库页的视图：笔记 / 网络。
 *
 * v1.3 起「概念」tab 下线——网络视图承担概念浏览（搜索、浮窗、分类树），
 * 概念详情从网络节点的小浮窗看，不再单独维护一页。
 */
internal enum class KnowledgeTab { Notes, Graph }

/** 节点类型：概念（叶子）或分类（父节点/领域）。 */
internal enum class GraphNodeKind { CONCEPT, CATEGORY }

/**
 * 节点是谁建的。
 *
 * - [DERIVED]：从笔记现算出来的（概念节点默认都是它，不落盘）；
 * - [USER]：用户分类/确认过的分类；
 * - [AI]：AI 建议出来的分类（`confirmed=false` 时带 AI 徽标，确认后转成用户数据）。
 */
internal enum class NodeOrigin { DERIVED, USER, AI }

/** 关系边类型。分类归属不是边（存 `parentKey`），这里只有「概念之间」的关系。 */
internal enum class GraphEdgeKind { PREREQUISITE, APPLIES, RELATED, CO_OCCUR }

/**
 * 关系边的来源，同时代表可信度层级（合并同一条边时按 USRE > EXPLICIT > HEURISTIC > AI 取）。
 *
 * - [USER]：用户在 UI 里确认/手加的关系，写进 `graph.user.json`，权威；
 * - [EXPLICIT]：正文里两个 `[[…]]` 相邻这类强信号（一期 AI 会自动打标，所以仍算「派生候选」，见设计 §3.5）；
 * - [HEURISTIC]：同段共现/跨段相邻现算出来的；
 * - [AI]：模型建议，只在 `graph.cache.json`，未确认不参与分层/不算事实。
 */
internal enum class GraphEdgeSource { EXPLICIT, HEURISTIC, AI, USER }

/** 构建/校验时发现的问题；不崩，进右栏或诊断列表给人看。 */
internal enum class DiagnosticKind {
    /** 分类的 parentId 指向不存在的分类。 */
    ParentMissing,

    /** 归属成环（A 的父是 B、B 的父是 A）。 */
    Cycle,

    /** 深度超过上限（5 层）。 */
    TooDeep,

    /** 同一父分类下出现同名子分类。 */
    DuplicateSibling,

    /** 非法归属（父节点不是分类 / 分类 id 不存在等）。 */
    InvalidAssignment,

    /** 关系的某一端不是已知概念（AI 编的概念名）。 */
    UnknownEdgeEnd,

    /** `graph.user.json` 读坏，已留档 `.bad-<ms>`，当前用空分类。 */
    BadUserFile,

    /** `graph.cache.json` 读坏，当空缓存处理。 */
    BadCacheFile,

    /** AI 建议被丢弃（名字/证据校验没过）。 */
    DroppedSuggestion,
}

/** 视口层级：决定画到什么粒度（缩放/节点数联合判定，见设计 §4.3）。 */
internal enum class LodLevel {
    /** 边框 + 芯片 + 全部标签。 */
    Full,

    /** 概念芯片只画色块/首字，超长分类折叠成「名称 · N」。 */
    Compact,

    /** 只画根分类块；点块放大。 */
    Aggregate,

    /** 不渲染全图，提示改用搜索/概念列表。 */
    TooLarge,
}

/**
 * 知识网络里的一个节点（概念或分类）。
 *
 * [key] 概念=概念身份键（一期 [conceptIdentityKey]），分类=`c_`+8hex；
 * [parentKey] 分类的父分类 id（根分类为 null），概念为所属分类 id（未分类为 null）；
 * [sortOrder] 同级排序种子（G0 全 0，按名字排；G1 支持上移/下移）。
 */
internal data class GraphNode(
    val key: String,
    val kind: GraphNodeKind,
    val name: String,
    val definition: String = "",
    val noteCount: Int = 0,
    val mentionCount: Int = 0,
    val firstSeenAt: Long = 0L,
    val lastSeenAt: Long = 0L,
    /** 只在一期 frontmatter 里登记过、正文里没出现（画虚线芯片）。 */
    val frontmatterOnly: Boolean = false,
    val parentKey: String? = null,
    val origin: NodeOrigin = NodeOrigin.DERIVED,
    val description: String? = null,
    /** AI 建的分类默认 false（带 AI 徽标），用户确认后 true。 */
    val confirmed: Boolean = true,
    val sortOrder: Int = 0,
)

/** 关系边的证据：点回原文时用（与一期定位链路的 `ConceptMention` 对齐）。 */
internal data class GraphEvidence(
    val noteId: String,
    val start: Int,
    val end: Int,
    val snippet: String,
)

/**
 * 概念之间的一条关系边。
 *
 * [fromKey]/[toKey] 都是概念节点的 key；[confidence] 0..1；
 * [createdAt] 用户关系/AI 建议才有，派生候选为 null（每次现算）。
 */
internal data class GraphEdge(
    val fromKey: String,
    val toKey: String,
    val kind: GraphEdgeKind,
    val source: GraphEdgeSource,
    val confidence: Float,
    val evidence: GraphEvidence? = null,
    val reason: String? = null,
    val createdAt: Long? = null,
)

/** 顶栏/左栏统计（设计 §3.6）。 */
internal data class GraphStats(
    val conceptCount: Int = 0,
    val categoryCount: Int = 0,
    val rootCategoryCount: Int = 0,
    val maxDepth: Int = 0,
    val classifiedCount: Int = 0,
    val unclassifiedCount: Int = 0,
    val relationEdgeCount: Int = 0,
    val aiSuggestionCount: Int = 0,
    val droppedAssignments: Int = 0,
) {
    companion object {
        val Empty = GraphStats()
    }
}

/** 一条构建/校验诊断；[subjectKey] 是出问题的节点 key（不知道就 null）。 */
internal data class GraphDiagnostic(
    val kind: DiagnosticKind,
    val subjectKey: String? = null,
    val detail: String,
)

/** 构建结果：树 + 边 + 统计 + 诊断。`childrenOf[null]` = 未分类概念（虚拟框）。 */
internal data class KnowledgeGraph(
    val nodes: Map<String, GraphNode> = emptyMap(),
    val childrenOf: Map<String?, List<String>> = emptyMap(),
    val edges: List<GraphEdge> = emptyList(),
    val stats: GraphStats = GraphStats.Empty,
    val diagnostics: List<GraphDiagnostic> = emptyList(),
) {
    fun node(key: String): GraphNode? = nodes[key]

    fun childrenOf(categoryKey: String?): List<GraphNode> =
        childrenOf[categoryKey].orEmpty().mapNotNull(nodes::get)

    /** 概念节点的所属分类 id（未分类 / 分类节点返回 null）。 */
    fun categoryOf(conceptKey: String): String? =
        nodes[conceptKey]?.takeIf { it.kind == GraphNodeKind.CONCEPT }?.parentKey

    /** 从根到该节点的祖先链（不含自己）；分类/概念都适用，断链就停。 */
    fun ancestorsOf(key: String): List<GraphNode> {
        val result = ArrayList<GraphNode>()
        var current = nodes[key]?.parentKey
        var guard = 0
        while (current != null && guard++ < 16) {
            val node = nodes[current] ?: break
            result.add(node)
            current = node.parentKey
        }
        return result.asReversed()
    }

    /** 该节点到根的展示路径（含自己）；找不到返回空。 */
    fun pathOf(key: String): List<GraphNode> {
        val node = nodes[key] ?: return emptyList()
        return ancestorsOf(key) + node
    }

    fun relationsOf(conceptKey: String): List<GraphEdge> =
        edges.filter { it.fromKey == conceptKey || it.toKey == conceptKey }

    companion object {
        /** 空图：还没建分类、也没有笔记时的状态。 */
        val Empty = KnowledgeGraph()
    }
}

// ---- 几何（不 import Compose：纯数据，布局/命中测试都能在 JVM 单测里跑） ----

internal data class GraphPoint(val x: Float, val y: Float) {
    companion object {
        val Zero = GraphPoint(0f, 0f)
    }
}

internal data class GraphSize(val width: Float, val height: Float) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    companion object {
        val Zero = GraphSize(0f, 0f)
    }
}

internal data class GraphRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
    val center: GraphPoint get() = GraphPoint(centerX, centerY)
    val topLeft: GraphPoint get() = GraphPoint(left, top)

    fun contains(point: GraphPoint): Boolean =
        point.x >= left && point.x <= right && point.y >= top && point.y <= bottom

    fun inflate(delta: Float): GraphRect = GraphRect(left - delta, top - delta, right + delta, bottom + delta)

    fun union(other: GraphRect): GraphRect = GraphRect(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )

    fun intersect(other: GraphRect): GraphRect = GraphRect(
        maxOf(left, other.left),
        maxOf(top, other.top),
        minOf(right, other.right),
        minOf(bottom, other.bottom),
    ).let { if (it.right < it.left || it.bottom < it.top) Zero else it }

    companion object {
        val Zero = GraphRect(0f, 0f, 0f, 0f)

        fun between(left: Float, top: Float, right: Float, bottom: Float): GraphRect =
            GraphRect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
    }
}

/** 画布相机：`screen = world * zoom + pan`（设计 §4.6）。 */
internal data class Camera(
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    fun worldToScreen(point: GraphPoint): GraphPoint =
        GraphPoint(point.x * zoom + panX, point.y * zoom + panY)

    fun screenToWorld(point: GraphPoint): GraphPoint =
        GraphPoint((point.x - panX) / zoom, (point.y - panY) / zoom)

    fun panned(dx: Float, dy: Float): Camera = copy(panX = panX + dx, panY = panY + dy)

    /** 以屏幕上的 [anchor] 为不动点缩放 [factor] 倍，zoom 夹在 [minZoom]..[maxZoom]。 */
    fun zoomedAt(anchor: GraphPoint, factor: Float, minZoom: Float = 0.25f, maxZoom: Float = 2.5f): Camera {
        val newZoom = (zoom * factor).coerceIn(minZoom, maxZoom)
        if (newZoom == zoom) return this
        val world = screenToWorld(anchor)
        return Camera(newZoom, anchor.x - world.x * newZoom, anchor.y - world.y * newZoom)
    }

    companion object {
        val Default = Camera()
    }
}

// ---- 布局结果（纯几何；由 KnowledgeGraphLayout 产出，Canvas 只画这里的东西） ----

/** 未分类虚拟框的 key（不是真实分类 id；统计里也不算分类）。 */
internal const val GRAPH_UNCLASSIFIED_KEY: String = "__unclassified__"

/**
 * 树上的一个节点（分类或概念）。
 *
 * [key] 是分类 id 或概念身份键；[kind] 决定画成「虚」分类还是「实」概念；
 * [depth] 根为 0；[bounds] 是世界坐标；[folded] 表示该分类当前被折叠（子节点不出现）。
 */
internal data class GraphNodeBox(
    val key: String,
    val kind: GraphNodeKind,
    val depth: Int,
    val bounds: GraphRect,
    val folded: Boolean = false,
)

/** 树边：父 → 子。设计 v1.2 起画布只画这一种线，其余关系全部收进小浮窗。 */
internal data class GraphLink(val fromKey: String, val toKey: String)

/**
 * 一次布局的完整结果：森林。
 *
 * [roots] 是每棵树的根（大类分类，未分类虚拟框排最后）；[nodes] 只含当前可见节点；
 * [links] 只含可见父子边；[collapsed] 是当前折叠的分类集合。
 */
internal data class GraphLayout(
    val bounds: GraphRect = GraphRect.Zero,
    val roots: List<String> = emptyList(),
    val nodes: Map<String, GraphNodeBox> = emptyMap(),
    val links: List<GraphLink> = emptyList(),
    val collapsed: Set<String> = emptySet(),
) {
    companion object {
        val Empty = GraphLayout()
    }
}

/** 画布点击命中：一个节点。 */
internal sealed interface GraphHit {
    data class Node(val key: String) : GraphHit
}

// ---- 持久化 DTO（graph.user.json / graph.cache.json 的内存形状；JSON 读写见 KnowledgeGraphStore） ----

/** `graph.user.json` 的一个分类。[order] 同级排序种子，[origin]/[confirmed] 区分 AI 建议与用户确认。 */
internal data class GraphCategory(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val order: Int = 0,
    val origin: NodeOrigin = NodeOrigin.USER,
    val description: String? = null,
    val confirmed: Boolean = true,
)

/** 用户确认过/手加的关系（权威，写 `graph.user.json`）。 */
internal data class GraphUserRelation(
    val from: String,
    val to: String,
    val kind: GraphEdgeKind,
    val confidence: Float = 1f,
    val reason: String? = null,
    val createdAt: Long? = null,
)

internal data class RemovedRelation(val from: String, val to: String, val kind: GraphEdgeKind)

/** 用户去掉的归类：记下当时的分类路径，分类被删后墓碑仍可读。 */
internal data class RemovedAssignment(val concept: String, val categoryPath: List<String>)

internal data class RemovedCategory(val id: String, val path: List<String>, val at: Long)

/** 用户忽略的 AI 建议：同一概念 + 建议指纹不再提示。 */
internal data class RemovedSuggestion(val concept: String, val fingerprint: String)

/** 墓碑集合：删过的内容不再复活（设计 §6.7）。 */
internal data class GraphRemovals(
    val relations: List<RemovedRelation> = emptyList(),
    val assignments: List<RemovedAssignment> = emptyList(),
    val categories: List<RemovedCategory> = emptyList(),
    val suggestions: List<RemovedSuggestion> = emptyList(),
) {
    companion object {
        val Empty = GraphRemovals()
    }
}

/** `graph.user.json` 的完整内存形状；[version] 固定 2，未知字段由 Store 原样保留。 */
internal data class GraphUserData(
    val version: Int = 2,
    val updatedAt: Long = 0L,
    val categories: List<GraphCategory> = emptyList(),
    /** conceptKey -> categoryId（未分类不出现；分类删掉后由构建回退到未分类）。 */
    val assignments: Map<String, String> = emptyMap(),
    val relations: List<GraphUserRelation> = emptyList(),
    val removed: GraphRemovals = GraphRemovals.Empty,
    /** 合并分类：oldId -> newId；构建时按它把旧归属解析到新分类。 */
    val mergedInto: Map<String, String> = emptyMap(),
) {
    companion object {
        val Empty = GraphUserData()
    }
}

/** AI 给一篇笔记的归类建议（`name` 是概念/分类建议名，按设计 §6.1 的笔记内输出）。 */
internal data class AiCategorySuggestion(
    val name: String,
    val categoryPath: List<String> = emptyList(),
    val reason: String? = null,
    val createdAt: Long = 0L,
)

/** AI 给全图的一条归类建议（[concept] 是 conceptKey）。 */
internal data class AiGlobalCategorySuggestion(
    val concept: String,
    val categoryPath: List<String> = emptyList(),
    val reason: String? = null,
    val batch: Int = 0,
    val createdAt: Long = 0L,
)

/** AI 归类失败的条目（校验没过：名字不在输入集/evidence 不是正文子串等）。 */
internal data class AiFailedCategory(val concept: String, val reason: String)

/** AI 关系建议（笔记内与全图共用；[evidence] 是正文子串，可点回原文）。 */
internal data class AiRelationSuggestion(
    val from: String,
    val to: String,
    val kind: GraphEdgeKind,
    val confidence: Float,
    val reason: String? = null,
    val evidence: String? = null,
    val createdAt: Long = 0L,
)

/** 一篇笔记的 AI 建议分组。 */
internal data class AiNoteSuggestions(
    val bodyHash: String = "",
    val categories: List<AiCategorySuggestion> = emptyList(),
    val relations: List<AiRelationSuggestion> = emptyList(),
)

/** 全图归类建议分组（[conceptSetHash] 变了就整体失效）。 */
internal data class AiGlobalCategories(
    val conceptSetHash: String = "",
    val categories: List<AiGlobalCategorySuggestion> = emptyList(),
    val failed: List<AiFailedCategory> = emptyList(),
)

/** 全图关系建议分组（G2 才会真正用；G0 只保留结构与读写，避免以后 JSON 迁移）。 */
internal data class AiGlobalRelations(
    val conceptSetHash: String = "",
    val relations: List<AiRelationSuggestion> = emptyList(),
)

/** 布局缓存：指纹命中时直接复用根块顺序与坐标锚点（warm）。 */
internal data class LayoutCache(
    val fingerprint: Long = 0L,
    val rootOrder: List<String> = emptyList(),
    val anchors: Map<String, GraphPoint> = emptyMap(),
)

/** `graph.cache.json` 的完整内存形状；丢掉不影响用户树，下次重算即可。 */
internal data class GraphAiCache(
    val version: Int = 2,
    val promptVersion: Int = 0,
    val model: String = "",
    val updatedAt: Long = 0L,
    val noteSuggestions: Map<String, AiNoteSuggestions> = emptyMap(),
    val globalCategories: AiGlobalCategories = AiGlobalCategories(),
    val globalRelations: AiGlobalRelations = AiGlobalRelations(),
    val layout: LayoutCache? = null,
) {
    companion object {
        val Empty = GraphAiCache()
    }
}

/** 一条归类建议（Taxonomy 的输入；来自 AI 缓存或 UI 手动）。 */
internal data class CategorySuggestion(
    val conceptKey: String,
    val categoryPath: List<String>,
    val reason: String? = null,
    val origin: NodeOrigin = NodeOrigin.AI,
)

// ---- 哈希（warm/cold 与缓存失效共用；纯函数、无 IO，放 Model 便于布局与 Store 共用） ----

/** FNV-1a 64 位哈希（UTF-8 字节）；同输入同值，跨平台可用。 */
internal fun fnv1a64(text: String): Long {
    var hash = -0x340D631B7BDDCD9L // 0xcbf29ce484222325
    for (byte in text.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFFL)
        hash *= 0x100000001B3L
    }
    return hash
}

/** 存进 json 的哈希文本：`fnv1a64:<16 位十六进制>`（固定宽度，方便比对）。 */
internal fun graphHashText(value: Long): String =
    "fnv1a64:" + value.toULong().toString(16).padStart(16, '0')

/** 解析 `fnv1a64:<hex>`（也接受纯 hex 或数字文本）；不合法返回 null。 */
internal fun graphHashFromText(raw: String): Long? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith(GRAPH_HASH_PREFIX)) {
        return text.removePrefix(GRAPH_HASH_PREFIX).toULongOrNull(16)?.toLong()
    }
    if (text.startsWith("0x")) return text.substring(2).toULongOrNull(16)?.toLong()
    // 没前缀时：含 a-f 的按十六进制，否则按十进制（存盘的哈希永远带前缀）。
    return if (text.any { it in 'a'..'f' || it in 'A'..'F' }) {
        text.toULongOrNull(16)?.toLong()
    } else {
        text.toULongOrNull()?.toLong()
    }
}

internal const val GRAPH_HASH_PREFIX: String = "fnv1a64:"

/**
 * 概念集的指纹：概念 key 集合 + 分类树结构（id、父子关系）。
 *
 * 用于「全图 AI 建议」是否过期（设计 §7.4）：概念增删/分类树结构变了才失效；
 * 分类改名、排序变化不算（它们不影响建议语义）。
 */
internal fun conceptSetHash(graph: KnowledgeGraph): Long {
    // 只取概念集合：这个哈希用来判断「全图归类建议是否还适用于当前概念集」。
    // 分类树结构 / 归属变化不影响——否则用户确认一条归类建议（会新建分类）就会让
    // 其余所有建议瞬间失效（2026-11-03 用户报「确认一条后其他建议全消失」）。
    val builder = StringBuilder()
    graph.nodes.values
        .asSequence()
        .filter { it.kind == GraphNodeKind.CONCEPT }
        .map { it.key }
        .sorted()
        .forEach { builder.append("c:").append(it).append('\n') }
    return fnv1a64(builder.toString())
}

/**
 * 布局指纹：分类 id+parentId+order、概念归属、概念 key 集（设计 §7.4）。
 *
 * 改名/只改展示信息不改变指纹；分类增删、归属移动、概念增删会变。
 */
internal fun layoutFingerprint(graph: KnowledgeGraph): Long {
    val builder = StringBuilder()
    graph.nodes.values
        .asSequence()
        .filter { it.kind == GraphNodeKind.CONCEPT }
        .sortedBy { it.key }
        .forEach { builder.append("c:").append(it.key).append('|').append(it.parentKey ?: "").append('\n') }
    graph.nodes.values
        .asSequence()
        .filter { it.kind == GraphNodeKind.CATEGORY }
        .sortedBy { it.key }
        .forEach {
            builder.append("g:").append(it.key).append('|').append(it.parentKey ?: "")
                .append('|').append(it.sortOrder).append('\n')
        }
    return fnv1a64(builder.toString())
}
