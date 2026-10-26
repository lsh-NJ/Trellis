package dev.trellis.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 数据与状态色（「岗位色」）。
 *
 * 为什么不直接用 `MaterialTheme.colorScheme.primary` 画图表：
 * primary 是「动作色」，数据是「测量值」，两者混用会让页面回到「只有一个颜色在干活」。
 * 所以这里单独开一组岗位色：
 *
 * - [data] / [dataMuted] / [dataTrack]：量（环、条、柱、折线、面积、日历选中）
 * - [accentText]：强调色底上的**文字**（强调色本身对纸只有 1.16:1，当文字等于没写）
 * - [danger] / [dangerContainer]：逾期、删除、失败
 * - [warning] / [warningContainer]：进行中、待确认
 * - [success] / [successContainer]：完成、满格、选中（青柠只做底，不做线）
 *
 * 默认值来自 bento 主题的实测色板；主题包可以在 `extra` 里覆盖，
 * 主题没给就用这里默认 —— 宁可给一套能看的默认，也不让图表掉回单色。
 */
data class ExtraColors(
    val data: Color = Color(0xFF2C4A63),
    val dataMuted: Color = Color(0xFF5E7891),
    val dataTrack: Color = Color(0xFFE2DED2),
    val accentText: Color = Color(0xFF4F6A12),
    val danger: Color = Color(0xFFB23A2E),
    val dangerContainer: Color = Color(0xFFF7E4DF),
    val warning: Color = Color(0xFF8F5C12),
    val warningContainer: Color = Color(0xFFF6EBD6),
    val success: Color = Color(0xFFD6F24B),
    val successContainer: Color = Color(0xFFEEF7C4),
)

val LocalExtraColors = staticCompositionLocalOf { ExtraColors() }
