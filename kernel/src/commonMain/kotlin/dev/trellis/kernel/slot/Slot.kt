package dev.trellis.kernel.slot

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.Cardinality
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.OrderedContribution

/**
 * UI 挂载点的贡献契约。
 *
 * 这里刻意**不含任何绘制逻辑**  M2 阶段内核层没有 Compose。
 * 契约只有「身份」：一个 id 和排序权重。
 * 未来接入 Compose 时新增一个 ComposeSlot 实现即可，契约本身不用改。
 */
interface SlotContribution : OrderedContribution

/**
 * 内容由**模板**生成的卡片，例如：
 *
 * ```
 * { "id": "due", "order": 10,
 *   "title": "今日待办",
 *   "body": "{{ summary.todo }} 项待办，{{ summary.overdue }} 项逾期" }
 * ```
 *
 * 求值**不在内核里做**  内核只提供 `renderTemplate()`。
 * 谁来调、什么时候重新求值，归渲染层管：控制台渲染器画之前求一次，
 * Compose 渲染器按帧/按数据变化求。内核不假装知道这些。
 */
data class TemplateSlot(
    override val id: String,
    override val order: Int = 100,
    val title: String,
    val body: String,
    /**
     * 这张卡是谁提供的。
     *
     * 求值 `{{ self.xxx }}` 时要用它去定位**这个插件自己的**字段 ——
     * 没有它，十个插件的 `self` 就只能抢一个位置。
     */
    val owner: PluginId? = null,
) : SlotContribution

/** 纯文本贡献：控制台假渲染器用。用来验证装卸逻辑，不负责好看。 */
data class TextSlot(
    override val id: String,
    override val order: Int = 100,
    val title: String,
    val body: String,
) : SlotContribution

/**
 * 内核提供的挂载点。注意它们是**通用 UI 位置**，不是业务概念 
 * 「详情页侧栏」是 UI 概念，「学习计划详情页」是业务概念。
 * 后者由插件自己决定要往哪个通用位置里放东西。
 */
object Slots {
    val HomeHeader: ExtensionPoint<SlotContribution> =
        point("slot.home.header", "首页头部")

    val HomeSection: ExtensionPoint<SlotContribution> =
        point("slot.home.section", "首页区块")

    val DetailAside: ExtensionPoint<SlotContribution> =
        point("slot.detail.aside", "详情页侧栏")

    val DetailTab: ExtensionPoint<SlotContribution> =
        point("slot.detail.tab", "详情页标签页")

    val RowTrailing: ExtensionPoint<SlotContribution> =
        point("slot.row.trailing", "列表行尾")

    val SettingsSection: ExtensionPoint<SlotContribution> =
        point("slot.settings.section", "设置页分组")

    val ReportChart: ExtensionPoint<SlotContribution> =
        point("slot.report.chart", "报表图表")

    val EmptyState: ExtensionPoint<SlotContribution> =
        point("slot.empty.state", "空状态")

    val AppOverlay: ExtensionPoint<SlotContribution> =
        point("slot.app.overlay", "全局浮层")

    val all: List<ExtensionPoint<SlotContribution>> = listOf(
        HomeHeader, HomeSection, DetailAside, DetailTab, RowTrailing,
        SettingsSection, ReportChart, EmptyState, AppOverlay,
    )

    private fun point(id: String, label: String) =
        ExtensionPoint<SlotContribution>(ExtensionPointId(id), Cardinality.MANY, label)

    private val byId: Map<ExtensionPointId, ExtensionPoint<SlotContribution>> =
        all.associateBy { it.id }

    /**
     * 按 id 反查挂载点。声明式插件（轨道 A）只有 id 字符串，没有 Kotlin 对象，
     * 这是它唯一能落到实际挂载点上的途径。
     * 返回 null 表示这个 id **根本不是内核认识的扩展点**  拼错的 id 必须被拒绝，
     * 不能静默变成一个永远不生效的贡献。
     */
    fun byId(id: ExtensionPointId): ExtensionPoint<SlotContribution>? = byId[id]

    fun isKnown(id: ExtensionPointId): Boolean = id in byId

    val knownIds: Set<ExtensionPointId> get() = byId.keys
}