package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 配色是**算出来的**，不是挑出来的：这里钉住三件事。
 *
 * 1. 可读性是生成的一部分 —— 每套预设、每个自定义色相/彩度，字与底都过 WCAG AA；
 * 2. 同一个 seed 永远给同一套颜色（可复现，不靠随机或主题缓存）；
 * 3. 参数真的起作用 —— 色相变了主色跟着变，彩度 0 就是灰阶，不会「调了没反应」。
 */
class PaletteTest {

    private val presets = listOf("default", "ocean", "forest", "sunset", "mono")

    private val customs = listOf(0, 60, 120, 180, 240, 300, 359).flatMap { hue ->
        listOf(0, 35, 60, 100).map { chroma ->
            UiPrefs(palette = "custom", paletteHue = hue, paletteChroma = chroma)
        }
    }

    private fun assertReadable(scheme: ColorScheme, label: String, bodyMinimum: Double = 7.0) {
        fun atLeast(minimum: Double, foreground: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, what: String) {
            val ratio = contrastRatio(foreground, background)
            assertTrue(
                ratio >= minimum,
                "$label 的 $what 对比度只有 " + "%.2f".format(ratio) + "（要求 ≥ " + minimum + "）",
            )
        }
        atLeast(4.5, scheme.onPrimary, scheme.primary, "onPrimary/primary")
        atLeast(4.5, scheme.onPrimaryContainer, scheme.primaryContainer, "onPrimaryContainer/primaryContainer")
        atLeast(4.5, scheme.onSecondary, scheme.secondary, "onSecondary/secondary")
        atLeast(4.5, scheme.onSecondaryContainer, scheme.secondaryContainer, "onSecondaryContainer/secondaryContainer")
        atLeast(4.5, scheme.onTertiary, scheme.tertiary, "onTertiary/tertiary")
        atLeast(4.5, scheme.onTertiaryContainer, scheme.tertiaryContainer, "onTertiaryContainer/tertiaryContainer")
        atLeast(4.5, scheme.onError, scheme.error, "onError/error")
        atLeast(4.5, scheme.onErrorContainer, scheme.errorContainer, "onErrorContainer/errorContainer")
        atLeast(4.5, scheme.onSurfaceVariant, scheme.surfaceVariant, "onSurfaceVariant/surfaceVariant")
        atLeast(bodyMinimum, scheme.onSurface, scheme.surface, "onSurface/surface")
        atLeast(bodyMinimum, scheme.onBackground, scheme.background, "onBackground/background")
    }

    @Test
    fun everyPresetKeepsTextReadable() {
        presets.forEach { preset -> assertReadable(schemeFor(UiPrefs(palette = preset)), preset) }
    }

    @Test
    fun everyCustomSeedKeepsTextReadable() {
        customs.forEachIndexed { index, prefs ->
            assertReadable(schemeFor(prefs), "自定义 #" + index + "（hue=" + prefs.paletteHue + ", chroma=" + prefs.paletteChroma + "）")
        }
    }

    @Test
    fun theDarkDerivationAlsoKeepsTextReadable() {
        (presets.map { UiPrefs(palette = it) } + customs.take(8)).forEach { prefs ->
            assertReadable(darkSchemeFor(prefs), "暗色 " + prefs.describe())
        }
    }

    @Test
    fun theSameSeedAlwaysGivesTheSameScheme() {
        val first = schemeFor(UiPrefs(palette = "custom", paletteHue = 137, paletteChroma = 61))
        val second = schemeFor(UiPrefs(palette = "custom", paletteHue = 137, paletteChroma = 61))

        assertEquals(first.primary, second.primary)
        assertEquals(first.surface, second.surface)
        assertEquals(first.onSurfaceVariant, second.onSurfaceVariant)
    }

    @Test
    fun changingTheHueChangesThePrimary() {
        val warm = schemeFor(UiPrefs(palette = "custom", paletteHue = 30))
        val cool = schemeFor(UiPrefs(palette = "custom", paletteHue = 230))

        assertNotEquals(warm.primary, cool.primary)
    }

    @Test
    fun zeroChromaIsGreyNotAWeirdColour() {
        val mono = schemeFor(UiPrefs(palette = "mono"))
        val custom = schemeFor(UiPrefs(palette = "custom", paletteHue = 210, paletteChroma = 0))

        listOf(mono, custom).forEach { scheme ->
            val p = scheme.primary
            assertTrue(abs(p.red - p.green) < 0.02f, "主色应接近灰阶：" + p)
            assertTrue(abs(p.green - p.blue) < 0.02f, "主色应接近灰阶：" + p)
        }
    }

    @Test
    fun theDarkDerivationNeverLeavesLightSurfacesBehind() {
        // M3 的 Card 默认用 surfaceContainerLow。派生暗色时若只拿 lightColorScheme 建方案，
        // 没显式赋值的 token 会保持浅色默认值 —— 表现就是「页面暗、卡片亮、字看不见」。
        val dark = darkSchemeFor(UiPrefs())
        listOf(
            "surfaceContainerLowest" to dark.surfaceContainerLowest,
            "surfaceContainerLow" to dark.surfaceContainerLow,
            "surfaceContainer" to dark.surfaceContainer,
            "surfaceContainerHigh" to dark.surfaceContainerHigh,
            "surfaceContainerHighest" to dark.surfaceContainerHighest,
        ).forEach { (name, color) ->
            assertTrue(relativeLuminance(color) < 0.3, "暗色方案里的 $name 仍是浅色：" + color)
        }
    }

    @Test
    fun aPresetIsJustANamedSeed() {
        // 预设不另走一条实现：ocean 的主色应等于「同样 hue/chroma 的 custom」。
        val ocean = schemeFor(UiPrefs(palette = "ocean"))
        val equivalentCustom = schemeFor(
            UiPrefs(
                palette = "custom",
                paletteHue = 242,
                paletteChroma = (0.130 / 0.18 * 100).toInt(),
            ),
        )

        assertEquals(ocean.primary, equivalentCustom.primary)
    }
}
