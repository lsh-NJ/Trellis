package dev.trellis.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 知识库「网络」视图的页内状态（设计 §5.1）。
 *
 * 放成 object 而不是 `remember` 的理由与一期 `MarkdownSidebarState` 一致：
 * 切到别的导航页再回来，视野、选中、折叠状态还在；这些只是**视图状态**，从不落盘
 * （唯一例外是布局指纹缓存，属于 `graph.cache.json` 的可删缓存，不属于这里）。
 *
 * 注意：这里不存「图」本身。图每次从笔记 + 两个 json 现算（设计 §3.1），
 * 视图状态只记「怎么看」。
 */
internal object KnowledgeGraphViewState {
    /** 当前知识库 tab；跨页保留。 */
    var tab: KnowledgeTab by mutableStateOf(KnowledgeTab.Notes)

    /** 相机（缩放 + 平移），世界坐标见 [Camera]。 */
    var camera: Camera by mutableStateOf(Camera.Default)

    /** 当前聚焦的概念/分类 key；null = 没聚焦（默认视图只画所属关系）。 */
    var selectedKey: String? by mutableStateOf(null)

    /** 用户手动折叠的分类 id 集合（分类默认展开；「分类默认折叠」筛选打开时这里存展开的反面）。 */
    var collapsed: Set<String> by mutableStateOf(emptySet())

    /** 「分类默认折叠」筛选开关（左栏）。 */
    var collapseByDefault: Boolean by mutableStateOf(false)

    /** 默认展开深度（1..5）；分类默认折叠时此值不生效。 */
    var expandDepth: Int by mutableStateOf(2)

    /** 左栏筛选：是否显示「未分类」虚拟框。 */
    var showUnclassified: Boolean by mutableStateOf(true)

    /** 左栏筛选：聚焦时是否显示其它关系（前置/应用/相关/共现）。 */
    var showRelationsOnFocus: Boolean by mutableStateOf(true)

    /** 左栏筛选：聚焦时是否连共现边也画（设计 Q5：默认隐藏）。 */
    var showCoOccurrence: Boolean by mutableStateOf(false)

    /** 左栏筛选：只看某个根分类（null = 全部领域）。 */
    var domainFilter: String? by mutableStateOf(null)

    /** 左栏搜索词；非空时左栏切成结果列表。 */
    var query: String by mutableStateOf("")

    /** 数据变更后自增，用来让画布重新计算构建 + 布局（分类增删改、确认 AI 建议等）。 */
    var refreshToken: Int by mutableStateOf(0)

    /** 一次性操作提示（例如「已新建分类」「AI 建议需要配置模型」）。 */
    var message: String? by mutableStateOf(null)

    fun requestRefresh(message: String? = null) {
        this.message = message
        refreshToken++
    }

    fun clearFocus() {
        selectedKey = null
        query = ""
    }

    fun fitReset() {
        camera = Camera.Default
    }
}
