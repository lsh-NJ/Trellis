package dev.trellis.kernel.slot

import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.rule.ActionCall

/** 文字只有三档。多一档就有一个档位永远没人用。 */
enum class UiTextStyle { Title, Body, Caption }

/**
 * 提示的两种语气。
 *
 * `Hint` 是插件对用户说的话（「今天已经记过了」），不该吓人；
 * `Problem` 是宿主诊断出来的问题（认不出的节点、画不出来的卡片），要显眼。
 */
enum class UiNoticeTone { Hint, Problem }

/**
 * 内容的语气：普通、弱化、强调、警告、成功。
 *
 * 这是**语义**，不是颜色。插件说「这是一条警告」，
 * 具体画成什么颜色由宿主按主题决定 —— 换皮肤、暗色模式、无障碍对比度都不用改插件。
 */
enum class UiTone { Default, Muted, Accent, Danger, Success }

/** 一列或一行里的东西靠哪边。 */
enum class UiAlign { Start, Center, End }

/**
 * 字号只有四档。插件说「这行大一点」，宿主决定具体多少 sp ——
 * 换主题、做无障碍放大时，插件不用改。
 */
enum class UiTextSize { Small, Normal, Large, Huge }

/** 字重只有三档，够用且不会让每张卡片都长成海报。 */
enum class UiTextWeight { Normal, Medium, Bold }

/** 字体族：默认、等宽、衬线、窄体（数字和大标题用）。 */
enum class UiFont { Default, Mono, Serif, Condensed }

/** 柱状图里的一根柱子。 */
data class UiBar(val label: String, val value: Int)

/**
 * 一块界面。
 *
 * 这是**封闭词汇表**：插件只能从这几个形状里挑，不能自己画。
 * 理由一句话：宿主得先看懂，才谈得上审查、降级和主题一致。
 * 画什么由插件决定，怎么画由宿主决定。
 *
 * 词汇表按「像写网页一样」的目标扩过一轮：除了排字和按钮，
 * 现在还能分组（[Section]）、分栏（[Grid]）、摆数字（[Stat]）、
 * 贴标签（[Badge]）、画进度（[Progress]）、留白（[Spacer]）、调语气（[UiTone]）。
 * 但仍然**没有**像素坐标、没有自定义样式、没有任意绘制 ——
 * 因为那三样一旦放开，宿主就看不懂界面了，审查、降级、跨端一致性也一起没了。
 */
sealed interface UiNode {
    data class Column(
        val children: List<UiNode>,
        val gap: Int = 8,
        val align: UiAlign = UiAlign.Start,
    ) : UiNode

    data class Row(
        val children: List<UiNode>,
        val gap: Int = 8,
        val align: UiAlign = UiAlign.Start,
    ) : UiNode

    /** 带标题的一组内容。标题为空就是纯分组。 */
    data class Section(val title: String, val children: List<UiNode>) : UiNode

    /** 等宽分栏，`columns` 在 1..4 之间。子节点按行优先填格子。 */
    data class Grid(val columns: Int = 2, val children: List<UiNode>) : UiNode

    /**
     * 网格里「跨几栏」。
     *
     * 只在 [Grid] 里有意义（`span` 是这一格占的栏数）；放在别处就当成透明容器，
     * 直接画 [child] —— 这样插件不用为「换个位置」改结构。
     * 样片的不对称 bento（8 + 4、6 + 3 + 3）就是靠它表达的。
     */
    data class Cell(val span: Int, val child: UiNode) : UiNode

    data class Text(
        val text: String,
        val style: UiTextStyle = UiTextStyle.Body,
        val tone: UiTone = UiTone.Default,
        val size: UiTextSize = UiTextSize.Normal,
        val weight: UiTextWeight = UiTextWeight.Normal,
        val font: UiFont = UiFont.Default,
    ) : UiNode

    /**
     * 柱状图。插件只交「每根柱子的标签和数字」，柱宽、配色、间距由宿主画。
     * `max` 为空时取所有值里最大的那个。
     */
    data class Bars(
        val items: List<UiBar>,
        val max: Int? = null,
        val label: String = "",
    ) : UiNode

    /**
     * 折线/迷你走势图。`values` 是等间隔的采样点，
     * 宿主按 `min..max` 归一化画出折线。
     */
    data class Sparkline(
        val values: List<Int>,
        val min: Int = 0,
        val max: Int = 100,
        val label: String = "",
    ) : UiNode

    /**
     * 竖直柱子（柱状图）。插件只给数值与标签，柱子高度、基线、间距都由宿主画。
     * 和 `Bars`（横向长条）分工：分类比较用 Bars，时间序列（近七天出票）用 Columns。
     */
    data class Columns(
        val values: List<Int>,
        val labels: List<String> = emptyList(),
        val max: Int? = null,
        val label: String = "",
    ) : UiNode

    /** 面积图：折线下面填一层淡色。用来画「累计」这类只涨不跌的曲线。 */
    data class Area(
        val values: List<Int>,
        val min: Int = 0,
        val max: Int = 100,
        val label: String = "",
    ) : UiNode

    /** 环形进度。比横条更适合「目标完成度」这种一眼要判断的场合。 */
    data class Ring(
        val value: Int,
        val max: Int = 100,
        val label: String = "",
    ) : UiNode

    /**
     * 点阵数字（5×7 方格拼字）。只认 0-9 和 `:` `-` 这几个字形，
     * 用来做看板上「20 天」那种倒计时/计数的大号装饰数字。
     * 字形表在宿主里，插件只给要显示的那几个字符。
     */
    data class DotMatrix(
        val value: String,
        val label: String = "",
    ) : UiNode

    /**
     * 一张小表格：表头 + 若干行。
     *
     * 每一格都只是文字：不画单元格边框、不合并、不允许塞节点。
     * 看板里「明细表」很常用，但宿主只承认「表格」这一种语义，
     * 而不是让插件自带 HTML —— 审查、降级、跨端渲染都靠这份封闭词表。
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val caption: String = "",
    ) : UiNode

    /** 一个数字配一个标签，比如「本周 12 页」。 */
    data class Stat(val label: String, val value: String) : UiNode

    /** 短标签，比如「连续 7 天」。 */
    data class Badge(val text: String, val tone: UiTone = UiTone.Default) : UiNode

    /** 进度条。`value` 会被夹到 `0..max`。 */
    data class Progress(val value: Int, val max: Int = 100, val label: String = "") : UiNode

    /**
     * 一张插图，用 emoji 当画面。
     *
     * 这是插件自带画面最轻的办法：不联网、不读文件、不占体积，跨端长得也差不多。
     * `glyph` 最多 4 个字符（一个 emoji 常占 2 个 UTF-16 单元）。
     * 真正的图片看 [Image]。
     */
    data class Icon(val glyph: String, val size: Int = 24) : UiNode

    /**
     * 图片，只能引用**插件自己目录里**的文件。
     *
     * `ref` 是插件目录内的相对路径（如 `art/hero.png`），宿主拒绝绝对路径、`..`、
     * 超过 1MB 的文件和非图片内容。没有网络图片：那会让「卡片长什么样」取决于
     * 别人的服务器，也绕过内容审查。
     */
    data class Image(
        val ref: String,
        val alt: String = "",
        val height: Int = 96,
    ) : UiNode

    /** 竖向留白，单位 dp，0..64。 */
    data class Spacer(val height: Int = 8) : UiNode

    /**
     * 一排小方格，像打卡纸：`values[i]` 是该格的强度，0 表示没记录。
     *
     * 用途：最近七天的心情、连续打卡的 28 格、近一周的完成情况。
     * 语义只到「强度」这一层，颜色与格子大小由宿主决定 —— 换主题、暗色都跟着走。
     */
    data class HeatStrip(
        val values: List<Int>,
        val max: Int = 5,
        val labels: List<String> = emptyList(),
    ) : UiNode

    data class Button(val label: String, val onTap: UiTap) : UiNode

    data class Input(val hint: String, val submitLabel: String, val onSubmit: UiTap) : UiNode

    /**
     * 计数步进器：`− 12 页 +`。
     *
     * 为什么要有它：计数型插件（今天做了几个、读了几页）用输入框是最差的做法 ——
     * 要打字、要提交、数字一多就懒得记。宿主画出加减两个按钮，插件只需要在
     * handler 里收 `delta`（"+1"/"-1"）并决定怎么存。
     */
    data class Stepper(
        val label: String,
        val value: Int,
        val unit: String = "",
        val min: Int = 0,
        val max: Int = 999,
        val step: Int = 1,
        val onStep: UiTap,
    ) : UiNode

    data class Notice(val text: String, val tone: UiNoticeTone = UiNoticeTone.Hint) : UiNode

    data object Divider : UiNode
}

/**
 * 点下去会发生什么。只有两种可能。
 *
 * Host 是点名宿主已有的原子动作，照样过审批门；
 * Plugin 是把值交回插件自己的处理函数，它只能返回「要存什么」，不能直接写。
 */
sealed interface UiTap {
    data class Host(val call: ActionCall) : UiTap
    data class Plugin(val handler: String, val args: Map<String, String> = emptyMap()) : UiTap
}

/**
 * 由**代码**生成的卡片。
 *
 * 和 TemplateSlot 的区别：模板卡片只会把占位符换成数字，
 * 这种卡片能看数据、能按条件决定画什么。代价是它要跑代码，
 * 所以宿主必须给它设预算，并且**永远不在渲染线程上跑它**。
 */
interface DynamicSlot : SlotContribution {
    suspend fun build(context: DataContext): UiNode
    suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome
}
