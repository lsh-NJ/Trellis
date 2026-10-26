package dev.trellis.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.core.PluginId

/**
 * 卡片外框的样式。
 *
 * 这是**宿主**的样式，不是插件的：插件只能决定卡片里写什么，
 * 边框、圆角、填充由宿主画（见 `docs/agent-manager.md` §11）。
 * 用户或 agent 通过界面偏好改这套值——改的是宿主，不是插件契约。
 */
data class DynamicCardStyle(
    val border: Boolean = true,
    val filled: Boolean = false,
    val radius: Dp = 12.dp,
    /**
     * 硬阴影：右下角一块实心偏移色块，模拟印刷品的「贴纸」层次。
     *
     * 这是样片里最能定调的一招，但仍然只是宿主的画法：
     * 插件既不知道也不控制它，换主题/换端都在宿主这一层翻译。
     */
    val hardShadow: Boolean = false,
)

val LocalCardStyle = staticCompositionLocalOf { DynamicCardStyle() }

/**
 * 单个插件的卡片样式覆盖。
 *
 * 用户说「心情打卡用填充底、俯卧撑保持描边」时，需要按插件给样式，而不是整页一个值。
 * 返回 null 表示这个插件没单独设过，回到 [LocalCardStyle] 的全局值 ——
 * 和布局一样：没点名的卡片不会被改，也不会被藏起来。
 */
val LocalPluginCardStyle = staticCompositionLocalOf<(PluginId?) -> DynamicCardStyle?> { { null } }
