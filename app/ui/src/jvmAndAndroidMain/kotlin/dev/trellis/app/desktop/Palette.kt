package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 配色生成：**一个色相 + 一个彩度**，推出一整套 Material 3 语义色。
 *
 * 为什么不再写死五组 RGB：写死等于把「能选的颜色」钉死在五个上，agent 想给用户
 * 「更冷静一点的蓝」时无路可走。改成参数化以后，preset 只是「几组起好名字的参数」，
 * 自定义色板和它们走同一条生成路径 —— 没有第二条实现，也就没有两套对比度行为。
 *
 * 为什么在 OKLCH 里生成而不是 HSL：HSL 的「同样亮度」在视觉上并不同亮
 * （黄色天生比蓝色亮得多），拿 HSL 调一组 Material 色会得到发灰的容器色和不齐的层级。
 * OKLab 的明度是感知均匀的，容器色/描边/正文的亮度关系不会随色相漂移。
 *
 * 对比度是**生成时**保证的，不是事后再调：每一对「字/底」都由 [pair] 反向求解，
 * 不满足 WCAG AA 就把底色往满足的方向推（正文 ≥7:1，其余 ≥4.5:1）。
 * 所以 agent 把色相/彩度拉到任意值，都不会得到看不清的界面。
 */
internal data class PaletteSeed(
    val hue: Double,
    /** OKLCH 彩度，0 是灰阶，0.18 已经很艳；再高就会出界。 */
    val chroma: Double,
)

internal const val DEFAULT_HUE = 285
internal const val DEFAULT_CHROMA = 55

/** 预设只是起好名字的参数；`custom` 读用户/agent 给的两个数。 */
internal fun paletteSeed(prefs: UiPrefs): PaletteSeed = when (prefs.palette) {
    "ocean" -> PaletteSeed(242.0, 0.130)
    "forest" -> PaletteSeed(148.0, 0.105)
    "sunset" -> PaletteSeed(32.0, 0.150)
    "mono" -> PaletteSeed(270.0, 0.0)
    "custom" -> PaletteSeed(
        hue = prefs.paletteHue.toDouble(),
        chroma = prefs.paletteChroma / 100.0 * 0.18,
    )
    else -> PaletteSeed(285.0, 0.130)
}

/**
 * OKLCH → sRGB。
 *
 * OKLab 的 a/b 在色相角上写成 `C·cos(h)` / `C·sin(h)`；超出 sRGB 色域时逐步收回彩度，
 * 而不是直接裁 RGB —— 直接裁会让高彩度颜色出现色相偏移（蓝会偏紫），
 * 收回彩度则保住色相、只损失一点饱和度。
 */
internal fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    val l = lightness.coerceIn(0.0, 1.0)
    val h = hue * PI / 180.0
    var c = chroma.coerceAtLeast(0.0)
    var rgb: DoubleArray
    while (true) {
        val a = c * cos(h)
        val b = c * sin(h)
        val lPrime = l + 0.3963377774 * a + 0.2158037573 * b
        val mPrime = l - 0.1055613458 * a - 0.0638541728 * b
        val sPrime = l - 0.0894841775 * a - 1.2914855480 * b
        val l3 = lPrime * lPrime * lPrime
        val m3 = mPrime * mPrime * mPrime
        val s3 = sPrime * sPrime * sPrime
        rgb = doubleArrayOf(
            4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
            -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
            -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
        )
        val inGamut = rgb.all { it >= -0.0005 && it <= 1.0005 }
        if (inGamut || c < 0.0005) break
        c *= 0.94
    }
    fun channel(value: Double): Int {
        val x = value.coerceIn(0.0, 1.0)
        val encoded = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1.0 / 2.4) - 0.055
        return (encoded * 255.0).roundToInt().coerceIn(0, 255)
    }
    return Color(channel(rgb[0]), channel(rgb[1]), channel(rgb[2]))
}

/** WCAG 相对亮度（输入已是 gamma 编码的 sRGB）。 */
internal fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val x = value.toDouble()
        return if (x <= 0.03928) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** WCAG 对比度，1.0（同色）到 21.0（黑白）。 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/**
 * 求一对「字/底」：底色从 [backgroundLightness] 出发，不满足 [minimum] 就往
 * 让对比度变大的方向推（字深则底变亮，字浅则底变暗）。
 *
 * 这是「生成时保证」的落点：调用方给的是设计意图（层级、彩度、起始明度），
 * 最终值由对比度决定。极端色相（黄、青）也不会生成不可读的组合。
 */
private fun pair(
    backgroundLightness: Double,
    backgroundChroma: Double,
    foregroundLightness: Double,
    foregroundChroma: Double,
    hue: Double,
    minimum: Double,
): Pair<Color, Color> {
    val foreground = oklch(foregroundLightness, foregroundChroma, hue)
    val lightenBackground = foregroundLightness < 0.5
    var backgroundL = backgroundLightness.coerceIn(0.0, 1.0)
    repeat(120) {
        val background = oklch(backgroundL, backgroundChroma, hue)
        if (contrastRatio(foreground, background) >= minimum) return foreground to background
        backgroundL = if (lightenBackground) {
            (backgroundL + 0.005).coerceAtMost(1.0)
        } else {
            (backgroundL - 0.005).coerceAtLeast(0.0)
        }
    }
    return foreground to oklch(backgroundL, backgroundChroma, hue)
}

/**
 * 把整套语义色从 seed 推出来。
 *
 * 色相关系：主色用 seed 色相；次色同色相、低彩度；强调色转 +55° ——
 * 这是 Material 的「同族 + 一处偏离」做法，整页不会出现两种互不相干的颜色。
 */
internal fun schemeFor(prefs: UiPrefs): ColorScheme {
    val seed = paletteSeed(prefs)
    val hue = seed.hue
    val chroma = seed.chroma
    val accentHue = (hue + 55.0) % 360.0

    val (onPrimary, primary) = pair(0.475, chroma, 0.995, chroma * 0.05, hue, 4.5)
    val (onPrimaryContainer, primaryContainer) =
        pair(0.925, chroma * 0.34, 0.235, chroma * 0.55, hue, 4.5)
    val (onSecondary, secondary) = pair(0.545, chroma * 0.46, 0.995, chroma * 0.05, hue, 4.5)
    val (onSecondaryContainer, secondaryContainer) =
        pair(0.940, chroma * 0.22, 0.265, chroma * 0.30, hue, 4.5)
    val (onTertiary, tertiary) = pair(0.505, chroma * 0.62, 0.995, chroma * 0.05, accentHue, 4.5)
    val (onTertiaryContainer, tertiaryContainer) =
        pair(0.930, chroma * 0.24, 0.250, chroma * 0.42, accentHue, 4.5)
    val (onError, error) = pair(0.500, 0.165, 0.995, 0.02, 27.0, 4.5)
    val (onErrorContainer, errorContainer) = pair(0.925, 0.055, 0.265, 0.115, 27.0, 4.5)
    val (onSurface, surface) = pair(0.985, chroma * 0.045, 0.205, chroma * 0.14, hue, 7.0)
    val (onSurfaceVariant, surfaceVariant) =
        pair(0.935, chroma * 0.10, 0.415, chroma * 0.17, hue, 4.5)
    val background = surface
    val onBackground = onSurface
    val outline = oklch(0.615, chroma * 0.18, hue)
    val outlineVariant = oklch(0.845, chroma * 0.10, hue)
    val inverseSurface = oklch(0.290, chroma * 0.14, hue)
    val inverseOnSurface = oklch(0.955, chroma * 0.04, hue)
    val inversePrimary = oklch(0.790, chroma * 0.75, hue)

    return lightColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color(0xFF000000),
    )
}

/**
 * 暗色方案由同一批语义位**派生**：明度对调，色相/彩度不动。
 *
 * 只允许「一套 token、两种明暗」——插件或用户只写浅色时，暗色是算出来的，
 * 不是另外维护一份（两份迟早会漂移）。
 */
internal fun darkSchemeFor(prefs: UiPrefs): ColorScheme {
    val seed = paletteSeed(prefs)
    val hue = seed.hue
    val chroma = seed.chroma
    val accentHue = (hue + 55.0) % 360.0

    val (onPrimary, primary) = pair(0.795, chroma * 0.80, 0.235, chroma * 0.55, hue, 4.5)
    val (onPrimaryContainer, primaryContainer) =
        pair(0.360, chroma * 0.48, 0.905, chroma * 0.30, hue, 4.5)
    val (onSecondary, secondary) = pair(0.815, chroma * 0.40, 0.245, chroma * 0.30, hue, 4.5)
    val (onSecondaryContainer, secondaryContainer) =
        pair(0.360, chroma * 0.28, 0.900, chroma * 0.18, hue, 4.5)
    val (onTertiary, tertiary) = pair(0.810, chroma * 0.55, 0.240, chroma * 0.40, accentHue, 4.5)
    val (onTertiaryContainer, tertiaryContainer) =
        pair(0.370, chroma * 0.34, 0.900, chroma * 0.24, accentHue, 4.5)
    val (onError, error) = pair(0.760, 0.130, 0.235, 0.070, 27.0, 4.5)
    val (onErrorContainer, errorContainer) = pair(0.360, 0.120, 0.900, 0.060, 27.0, 4.5)
    val (onSurface, surface) = pair(0.175, chroma * 0.10, 0.905, chroma * 0.06, hue, 7.0)
    val (onSurfaceVariant, surfaceVariant) =
        pair(0.280, chroma * 0.12, 0.780, chroma * 0.12, hue, 4.5)
    val background = surface
    val onBackground = onSurface
    val outline = oklch(0.640, chroma * 0.14, hue)
    val outlineVariant = oklch(0.360, chroma * 0.10, hue)
    val inverseSurface = oklch(0.950, chroma * 0.03, hue)
    val inverseOnSurface = oklch(0.220, chroma * 0.10, hue)
    val inversePrimary = oklch(0.475, chroma, hue)

    // 必须用 darkColorScheme：没显式赋值的 token（surfaceContainer* 系列等）
    // 会继承「深色默认值」。如果这里用 lightColorScheme，Card 默认的
    // surfaceContainerLow 就会是浅色 —— 页面暗、卡片亮、字看不清，正是「一半跟随」。
    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = inversePrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceTint = primary,
        inverseSurface = inverseSurface,
        inverseOnSurface = inverseOnSurface,
        error = error,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
        outline = outline,
        outlineVariant = outlineVariant,
        scrim = Color(0xFF000000),
    )
}
