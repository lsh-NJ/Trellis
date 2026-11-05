package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.trellis.ui.DynamicCardStyle
import dev.trellis.ui.ExtraColors
import dev.trellis.ui.LocalAppFonts
import dev.trellis.ui.LocalCardStyle
import dev.trellis.ui.LocalExtraColors

/**
 * 把用户/agent 改的界面偏好翻译成 Compose 主题。
 *
 * 只在这一层翻译：界面代码里继续用 MaterialTheme 的语义色、继续写 dp ——
 * 换配色不会让任何一处的对比度关系失效，改密度也不会让某个组件单独变形。
 *
 * [themeScheme] 来自 L2 主题包（插件贡献、宿主校验过）；为空时落回
 * [schemeFor] / [darkSchemeFor] 生成的配色方案。两条路最终都是同一个 ColorScheme，
 * 界面层不需要知道现在用的是哪一种。
 *
 * 根部的 [Surface] 是「整页只认一个主题」的兜底：底色、正文默认颜色都在这里刷一次。
 * 少了它，窗口会用系统默认的白底，而里面的卡片/导航却按暗色方案取色，
 * 就会出现「一部分跟随、一部分不跟随」。
 */
@Composable
internal fun AppTheme(
    prefs: UiPrefs,
    themeScheme: ColorScheme? = null,
    dark: Boolean = false,
    chrome: ChromeStyle = ChromeStyle(),
    extra: ExtraColors = ExtraColors(),
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    val scheme = themeScheme ?: if (dark) darkSchemeFor(prefs) else schemeFor(prefs)
    val density = Density(base.density * densityScale(prefs.density), prefs.fontScale)
    val cardStyle = when (prefs.card) {
        "flat" -> DynamicCardStyle(border = false, radius = prefs.radius.dp)
        "filled" -> DynamicCardStyle(border = false, filled = true, radius = prefs.radius.dp)
        // bento：小圆角 + 硬阴影。圆角上限压到 4dp，否则投影跟着大圆角会糊掉。
        "bento" -> DynamicCardStyle(
            border = true,
            radius = prefs.radius.coerceAtMost(4).dp,
            hardShadow = true,
        )
        else -> DynamicCardStyle(border = true, radius = prefs.radius.dp)
    }
    val control = chrome.controlRadius.dp
    val card = chrome.cardRadius.dp
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(control),
        small = RoundedCornerShape(control),
        medium = RoundedCornerShape(card),
        large = RoundedCornerShape(card),
        extraLarge = RoundedCornerShape(card),
    )
    MaterialTheme(colorScheme = scheme, shapes = shapes, typography = typographyFor(chrome)) {
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalCardStyle provides cardStyle,
            LocalAppFonts provides AppFonts.families,
            LocalExtraColors provides extra,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}

/**
 * 主题带的外壳字体参数：只换字族和整体缩放。
 *
 * 不换具体字体文件 —— 字体是端上的资产，插件指定文件名在别的端上找不到，只会静默回退，
 * 那比不换更糟。标签用 mono 是这套样片的「编辑感」（mono 小标），标题允许 mono/serif。
 */
private fun typographyFor(chrome: ChromeStyle): Typography {
    val labels = familyOf(chrome.labelFamily)
    val titles = familyOf(chrome.titleFamily)
    val displays = familyOf(chrome.displayFamily)
    val s = chrome.scale
    fun TextStyle.tuned(family: FontFamily): TextStyle = copy(
        fontFamily = family,
        fontSize = fontSize * s,
        lineHeight = lineHeight * s,
    )
    fun TextStyle.scaled(): TextStyle = copy(fontSize = fontSize * s, lineHeight = lineHeight * s)
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.tuned(displays),
        displayMedium = base.displayMedium.tuned(displays),
        displaySmall = base.displaySmall.tuned(displays),
        headlineLarge = base.headlineLarge.tuned(displays),
        headlineMedium = base.headlineMedium.tuned(displays),
        headlineSmall = base.headlineSmall.tuned(displays),
        titleLarge = base.titleLarge.tuned(titles),
        titleMedium = base.titleMedium.tuned(titles),
        titleSmall = base.titleSmall.tuned(titles),
        bodyLarge = base.bodyLarge.scaled(),
        bodyMedium = base.bodyMedium.scaled(),
        bodySmall = base.bodySmall.scaled(),
        labelLarge = base.labelLarge.tuned(labels),
        labelMedium = base.labelMedium.tuned(labels),
        labelSmall = base.labelSmall.tuned(labels),
    )
}

private fun familyOf(name: String): FontFamily = when (name) {
    "mono" -> AppFonts.mono
    "serif" -> FontFamily.Serif
    "condensed" -> AppFonts.condensed
    else -> FontFamily.Default
}
