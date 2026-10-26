package dev.trellis.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/**
 * 宿主打包的字体族。
 *
 * ui-kernel 是 commonMain，不能直接读桌面资源目录；所以字体由端上的
 * 启动代码装进 [LocalAppFonts]，界面层只问「窄体长什么样」而不问「字体文件在哪」。
 * 默认值退化成系统字体，缺字体时界面照常能看，只是不像样片。
 */
data class AppFontFamilies(
    val condensed: FontFamily = FontFamily.Default,
    val mono: FontFamily = FontFamily.Monospace,
    val serif: FontFamily = FontFamily.Serif,
)

val LocalAppFonts = staticCompositionLocalOf { AppFontFamilies() }
