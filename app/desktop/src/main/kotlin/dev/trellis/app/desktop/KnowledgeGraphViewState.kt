package dev.trellis.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 知识网络视图的页内状态（设计 v1.2：只保留「树 + 聚焦浮窗」需要的东西）。
 *
 * 跟一期 [MarkdownSidebarState] 一个思路：放 object 里，切到别的 tab 再回来，
 * 视野、选中节点、折叠状态都还在。**不落盘**，重启回到默认。
 */
internal object KnowledgeGraphViewState {
    /** 知识页当前 tab（笔记 / 概念 / 网络）。 */
    var tab: KnowledgeTab by mutableStateOf(KnowledgeTab.Notes)

    /** 画布相机（缩放 + 平移）。 */
    var camera: Camera by mutableStateOf(Camera.Default)

    /** 当前聚焦节点（分类 id 或概念身份键）；null = 没聚焦。小浮窗跟随它。 */
    var selectedKey: String? by mutableStateOf(null)

    /** 手动折叠的分类 id 集合。 */
    var collapsed: Set<String> by mutableStateOf(emptySet())

    /** 是否显示「未分类」那类树。 */
    var showUnclassified: Boolean by mutableStateOf(true)

    /** 左栏是否收起（和笔记/概念页的列表栏同一套交互，收起后只剩一条窄边）。 */
    var leftCollapsed: Boolean by mutableStateOf(false)

    /** 左栏搜索框内容（搜索时左栏临时换成命中列表）。 */
    var query: String by mutableStateOf("")

    /** 递增后让页面重新读盘（笔记变了/手动刷新）。 */
    var refreshToken: Int by mutableStateOf(0)

    /** 一次性提示（新建分类成功、导入失败等）。 */
    var message: String? by mutableStateOf(null)

    /** 递增后请求「适应窗口」。 */
    var fitRequests: Int by mutableStateOf(0)

    /** AI 建议面板是否展开（G1；左侧「查看 AI 建议」打开）。 */
    var suggestionsOpen: Boolean by mutableStateOf(false)

    fun requestRefresh(message: String? = null) {
        refreshToken++
        if (message != null) this.message = message
    }

    fun clearFocus() {
        selectedKey = null
    }

    fun toggleFolded(categoryId: String) {
        collapsed = if (categoryId in collapsed) collapsed - categoryId else collapsed + categoryId
    }

    fun expandTo(key: String, graph: KnowledgeGraph) {
        val ancestors = graph.ancestorsOf(key).map { it.key }.filter { it != key }
        if (ancestors.isEmpty()) return
        collapsed = collapsed - ancestors.toSet()
    }

    fun fitReset() {
        camera = Camera.Default
        fitRequests++
    }
}
