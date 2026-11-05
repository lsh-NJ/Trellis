package dev.trellis.app.desktop

import androidx.compose.ui.text.font.FontFamily
import dev.trellis.ui.AppFontFamilies

/**
 * 打进安装包的字体（Barlow Condensed、IBM Plex Mono，都在 OFL 下，
 * 许可证在 `resources/fonts/OFL-*.txt`）。
 *
 * 为什么自己带：样片的「编辑感」一半来自窄体的大数字和等宽小标。系统字体在
 * Windows / macOS / Linux / Android 上各不相同，样式会随机器漂。
 *
 * 两个平台各自怎么加载：
 * - 桌面：classpath 资源（`resources/fonts` 下的 ttf），用 Compose Desktop 的 `Font(data=…)`；
 * - 安卓：`res/font` 下的 ttf，用 `Font(resId=…)` —— 安卓上没有 classpath 那一套。
 * 读不到时退回系统字体，界面照常能用，只是不像样片。
 */
expect object AppFonts {
    val condensed: FontFamily
    val mono: FontFamily
    val families: AppFontFamilies
}
