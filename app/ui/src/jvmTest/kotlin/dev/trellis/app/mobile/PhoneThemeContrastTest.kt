package dev.trellis.app.mobile

import androidx.compose.ui.graphics.Color
import dev.trellis.app.desktop.contrastRatio
import dev.trellis.ui.ExtraColors
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 手机端的「岗位色」必须跟着外观一起换。
 *
 * 这条测试是为一个真实缺陷加的：`PhoneTheme` 以前只提供 `LocalCardColor`，
 * 没提供 `LocalExtraColors`，于是插件贡献的卡片（今日盘点、待办清单、UiNode 图表）
 * 在暗色下拿到的是 `ExtraColors()` 的**浅色默认值** —— 靛蓝 #2C4A63 压在近黑底上，
 * 实测 1.6:1，截图里那行「连续 1 天」基本看不见。
 *
 * 为什么不断言具体色值：色值可以改，可读性不能退。这里钉的是关系。
 */
class PhoneThemeContrastTest {

    private val defaults = ExtraColors()

    private fun atLeast(minimum: Double, foreground: Color, background: Color, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            ratio >= minimum,
            what + " 的对比度只有 " + "%.2f".format(ratio) + "（要求 ≥ " + minimum + "）",
        )
    }

    private fun assertReadable(dark: Boolean) {
        val label = if (dark) "暗色" else "浅色"
        val extra = phoneExtraColors(dark)
        val surface = phoneScheme(dark).surface
        // 当文字用的三档：正文级 4.5:1，弱化档 3:1。
        atLeast(4.5, extra.data, surface, label + " data/surface")
        atLeast(4.5, extra.danger, surface, label + " danger/surface")
        atLeast(3.0, extra.dataMuted, surface, label + " dataMuted/surface")
        // 强调底上的字：Pill/角标都是这套搭配。
        atLeast(4.5, extra.accentText, phoneScheme(dark).primaryContainer, label + " accentText/primaryContainer")
    }

    @Test
    fun `浅色下岗位色当文字要看得清`() {
        assertReadable(dark = false)
    }

    @Test
    fun `暗色下岗位色当文字要看得清`() {
        assertReadable(dark = true)
    }

    @Test
    fun `岗位色不再沿用浅色默认值`() {
        // 浅色默认值恰好就是旧版行为；暗色若还等于它，说明又忘了按外观推。
        assertNotEquals(defaults.data, phoneExtraColors(dark = true).data)
        assertNotEquals(defaults.data, phoneExtraColors(dark = false).data)
    }

    @Test
    fun `切换外观会同时切换岗位色`() {
        val light = phoneExtraColors(dark = false)
        val dark = phoneExtraColors(dark = true)

        assertNotEquals(light.data, dark.data)
        assertNotEquals(light.danger, dark.danger)
    }
}
