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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * 2026-11-06 之后多了 [TrellisTokens]：颜色/字号/间距/圆角/行高的**角色**
 * 在这里一次性算好，往下通过 [LocalTrellisTokens] 提供。组件该做的是读角色
 * （`tokens.radius.control`），不是再拍一个 `6.dp`。
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
    val tokens = TrellisTokens.of(prefs, chrome, scheme, extra)
    // 字号要**乘**系统的缩放，而不是替换它。
    // 以前的写法是 `Density(..., prefs.fontScale)`，等于把用户在系统设置里调大的字号
    // 一律按 100% 处理 —— 视力不好的人把系统字体调到最大，进这个应用还是小的。
    //
    // 密度**不**乘在这里：compact 原来乘 1.12，把整页连字号一起放大 12%，
    // 「紧凑」反而比「标准」还大。密度只改行高/内边距，见 TrellisTokens.rows。
    val density = Density(base.density, base.fontScale * prefs.fontScale)
    // 卡片圆角：用户没显式改过就跟主题走（FOLLOW_THEME_RADIUS），改过就用他的。
    val cardRadius = tokens.radius.card
    val cardStyle = when (prefs.card) {
        "flat" -> DynamicCardStyle(border = false, radius = cardRadius)
        "filled" -> DynamicCardStyle(border = false, filled = true, radius = cardRadius)
        // bento：小圆角 + 硬阴影。圆角上限压到 4dp，否则投影跟着大圆角会糊掉。
        "bento" -> DynamicCardStyle(
            border = true,
            radius = cardRadius.coerceAtMost(4.dp),
            hardShadow = true,
        )
        else -> DynamicCardStyle(border = true, radius = cardRadius)
    }
    // M3 组件的圆角也走同一份 token：以前 Shapes 直接读 chrome，和插件卡（UiPrefs.radius）
    // 各写各的，于是 M3 卡片 3dp、插件卡 12dp 同屏。
    val control = tokens.radius.control
    val card = tokens.radius.card
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
            LocalTrellisTokens provides tokens,
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
 * 主题带来的排版参数：字族一律是界面字体，主题只决定整体缩放。
 *
 * 2026-11-06 批 2 定的规矩：中文界面不跟着主题换字族。Bento 那套等宽/窄体样片在中文里
 * 必然回退到系统字体，屏幕于是变成「中文系统字体 + 数字英文等宽」的混排；把 label 换成
 * 等宽只在纯英文界面里成立。等宽从此只留给**本来就该等宽**的地方——代码块、过程行、
 * 时间戳、计时器数字（那些地方直接写 [AppFonts.mono]），不再通过主题间接传染。
 *
 * 不换具体字体文件 —— 字体是端上的资产，插件指定文件名在别的端上找不到，只会静默回退。
 * [ChromeStyle] 里的 label/title/display 字族字段保留给插件声明和后续组件套件，这里不消费。
 *
 * `labelSmall` 从 M3 的 11/16 提到 12/18：全应用 266 处挤在 11sp 是层级被压平的直接原因，
 * 11sp 留给真正的注解（`tokens.type.caption`）。
 */
private fun typographyFor(chrome: ChromeStyle): Typography {
    val s = chrome.scale
    fun TextStyle.ui(): TextStyle = copy(
        fontFamily = FontFamily.Default,
        fontSize = fontSize * s,
        lineHeight = lineHeight * s,
    )
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.ui(),
        displayMedium = base.displayMedium.ui(),
        displaySmall = base.displaySmall.ui(),
        headlineLarge = base.headlineLarge.ui(),
        headlineMedium = base.headlineMedium.ui(),
        headlineSmall = base.headlineSmall.ui(),
        titleLarge = base.titleLarge.ui(),
        titleMedium = base.titleMedium.ui(),
        titleSmall = base.titleSmall.ui(),
        bodyLarge = base.bodyLarge.ui(),
        bodyMedium = base.bodyMedium.ui(),
        bodySmall = base.bodySmall.ui(),
        labelLarge = base.labelLarge.copy(
            fontFamily = FontFamily.Default,
            fontSize = 13.sp * s,
            lineHeight = 20.sp * s,
            fontWeight = FontWeight.Medium,
        ),
        labelMedium = base.labelMedium.copy(
            fontFamily = FontFamily.Default,
            fontSize = 12.sp * s,
            lineHeight = 18.sp * s,
            fontWeight = FontWeight.Medium,
        ),
        labelSmall = base.labelSmall.copy(
            fontFamily = FontFamily.Default,
            fontSize = 12.sp * s,
            lineHeight = 18.sp * s,
            fontWeight = FontWeight.Medium,
        ),
    )
}
