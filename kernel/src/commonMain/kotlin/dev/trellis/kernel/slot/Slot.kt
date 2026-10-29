package dev.trellis.kernel.slot

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.Cardinality
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.OrderedContribution
import dev.trellis.kernel.rule.ActionCall

/**
 * UI 挂载点的贡献契约。
 *
 * 这里刻意**不含任何绘制逻辑**  M2 阶段内核层没有 Compose。
 * 契约只有「身份」：一个 id 和排序权重。
 * 未来接入 Compose 时新增一个 ComposeSlot 实现即可，契约本身不用改。
 */
interface SlotContribution : OrderedContribution

/**
 * 卡片零件的语气。
 *
 * 是**语义**不是像素：插件说「这是坏消息」，由宿主决定坏消息长什么样。
 * 深色模式、换主题、读屏软件都因此不用插件操心。
 */
enum class SlotTone { DEFAULT, MUTED, ACCENT, DANGER, SUCCESS }

/** 字号档位。只有三档 —— 再多就等于让插件自己排版。 */
enum class SlotTextSize { SMALL, NORMAL, LARGE }

/** 一个数值：大数字 + 单位 + 说明。首页那排统计就是这个零件。 */
data class SlotStat(
    val value: String,
    val unit: String = "",
    val label: String = "",
    val tone: SlotTone = SlotTone.DEFAULT,
)

/**
 * 一条进度。
 *
 * [value] / [max] 是**模板片段**（可以是 `{{ summary.done }}`），
 * 渲染时求值再转成数字。求不出来就画一条空进度，不抛异常。
 */
data class SlotProgress(
    val value: String = "0",
    val max: String = "100",
    val label: String = "",
)

/** 一个状态标签：逾期 / 今天 / 完成。 */
data class SlotBadge(
    val text: String,
    val tone: SlotTone = SlotTone.DEFAULT,
)

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
     * 卡片下面那几个按钮。空 = 只读卡片（老插件都是这样，不影响它们）。
     *
     * 声明式插件里没有代码，所以它没法「响应点击」  它能做的只有点名一个
     * **宿主已经定义好的原子动作**。点下去的时候，这次调用走的是和规则
     * 完全同一条流水线：查动作 -> 过审批门 -> 执行。插件依然一行代码都没跑。
     */
    val actions: List<SlotAction> = emptyList(),
    /**
     * 这张卡是谁提供的。
     *
     * 求值 `{{ self.xxx }}` 时要用它去定位**这个插件自己的**字段 ——
     * 没有它，十个插件的 `self` 就只能抢一个位置。
     */
    val owner: PluginId? = null,
    /** 标题的口吻与字号。默认就是普通标题。 */
    val titleTone: SlotTone = SlotTone.DEFAULT,
    val titleSize: SlotTextSize = SlotTextSize.NORMAL,
    /** 正文口吻。次要说明用 MUTED，坏消息用 DANGER。 */
    val bodyTone: SlotTone = SlotTone.DEFAULT,
    /**
     * 具名零件。
     *
     * 为什么要有它们：一张卡片想说「今天到期 3 项」，以前只能写成
     * 「还有 3 项到期」这句话  而首页自己的统计栏是有样式的数字。
     * 同一屏里两种语言，那才是真正的不统一。
     */
    val stat: SlotStat? = null,
    val progress: SlotProgress? = null,
    val badges: List<SlotBadge> = emptyList(),
) : SlotContribution

/**
 * 卡片上的一个按钮。
 *
 * [label] 是给人看的字，[call] 是给内核看的动作调用。
 * 两者分开，是因为「加一个番茄钟」和「addTask(title=番茄钟 25 分钟)」
 * 本来就不是一回事  前者是文案，后者是意图。
 */
data class SlotAction(
    val label: String,
    val call: ActionCall,
    /** 按钮口吻：primary 是主操作，danger 会额外要求二次确认（宿主统一加）。 */
    val tone: SlotTone = SlotTone.DEFAULT,
    /**
     * 为什么这个按钮不能按。空 = 能按。
     *
     * 不写原因只把按钮变灰，用户只会以为程序坏了。
     */
    val disabledReason: String = "",
)

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

    /**
     * 对话页的常驻侧条：贴在对话线程旁边、不随滚动消失的一块。
     *
     * 和 [EmptyState] 的区别是「有没有会话都在」：空状态只在没有内容时出现，
     * 这条是常驻的。用它放「当前目标 / 今天的进度」这类需要一直看得见的东西。
     */
    val ChatAside: ExtensionPoint<SlotContribution> =
        point("slot.chat.aside", "对话页侧条")

    val AppOverlay: ExtensionPoint<SlotContribution> =
        point("slot.app.overlay", "全局浮层")

    val all: List<ExtensionPoint<SlotContribution>> = listOf(
        HomeHeader, HomeSection, DetailAside, DetailTab, RowTrailing,
        SettingsSection, ReportChart, EmptyState, ChatAside, AppOverlay,
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