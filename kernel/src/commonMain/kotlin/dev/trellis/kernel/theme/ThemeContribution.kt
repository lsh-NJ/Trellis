package dev.trellis.kernel.theme

import dev.trellis.kernel.extension.OrderedContribution

/**
 * 一个插件贡献出来的主题包（L2 扩展）。
 *
 * 主题不是「随便一串 CSS」，而是一组**语义 token**：主色、底色、正文色、
 * 边框色…… 宿主只认这张词表，值只收 `#RRGGBB`，并且会自己验对比度。
 *
 * 为什么不让插件直接写颜色方案的全部字段（inverseSurface、scrim 之类）：
 * 那些字段之间有关系，插件写不全就会出来一套「局部好看、局部瞎眼」的界面。
 * 词表由宿主定，插件只能在词表里取舍 —— 和 UI 节点是同一个思路：
 * 限制词汇表，换来「每个主题都不会坏掉」。
 *
 * [dark] 允许留空：宿主会按 [light] 的明度反相派生一套暗色，并照样验对比度；
 * 派生不合格时会拒绝应用并说明原因，而不是硬套上去。
 */
data class ThemeContribution(
    override val id: String,
    val name: String,
    val description: String = "",
    val light: Map<String, String>,
    val dark: Map<String, String> = emptyMap(),
    /**
     * 宿主外壳的形状参数（可选）：`cardRadius`、`controlRadius`，单位 dp，0..24。
     * 不给就沿用偏好里的值 —— 主题只该说「这套长相哪里不一样」，不必把每项都复述一遍。
     */
    val shape: Map<String, String> = emptyMap(),
    /**
     * 宿主外壳的字体参数（可选）：`labelFamily`、`titleFamily`（default/mono/serif）、
     * `scale`（0.85..1.2）。只开放「族 + 缩放」，不开放具体字体文件：
     * 字体文件由端上提供，插件指定文件名会在别的端上找不到、静默回退成默认字体。
     */
    val type: Map<String, String> = emptyMap(),
    /**
     * 颜色分工（可选）：`data`、`dataMuted`、`dataTrack`、`accentText`、
     * `danger`、`dangerContainer`、`warning`、`warningContainer`。
     *
     * 和 [light] 的区别：light 是「页面结构色」，这一组是「数据与状态色」。
     * 图表（环/条/方格/折线）只能从 data 系取色，逾期/失败只能从 danger 取色，
     * 强调色上的文字只能从 accentText 取色 —— 不让插件拿 primary 去画图表，
     * 否则「强调」和「数据」又会混成一个色，回到配色单调的老路。
     */
    val extra: Map<String, String> = emptyMap(),
    override val order: Int = 100,
) : OrderedContribution
