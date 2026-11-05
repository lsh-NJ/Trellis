package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.trellis.kernel.theme.ThemeContribution
import dev.trellis.ui.ExtraColors

/**
 * L2 主题包：把插件交来的语义 token 变成 Compose 配色。
 *
 * 三道闸，一道都不能省：
 * 1. **词表**：只认 [THEME_ALL_TOKENS]；插件多写的 token 会被点名拒掉，
 *    而不是悄悄忽略 —— 悄悄忽略等于「装了但没效果」，那种失望比报错更难查。
 * 2. **值**：只收 `#RRGGBB`，其余一律不认。
 * 3. **对比度**：正文/底色至少 7:1，其余前景/背景对至少 4.5:1。
 *    主题是给整页换装的，一套低对比度的主题会让**所有**插件一起变得难读。
 *
 * 暗色允许缺失，由宿主按明度反相派生；派生完会照样验对比度，
 * 不合格就整份拒绝并说清哪一对不够 —— 不硬套，也不静默降级。
 */
internal val THEME_REQUIRED_TOKENS: List<String> = listOf(
    "primary", "onPrimary",
    "background", "onBackground",
    "surface", "onSurface",
    "surfaceVariant", "onSurfaceVariant",
)

internal val THEME_OPTIONAL_TOKENS: List<String> = listOf(
    "primaryContainer", "onPrimaryContainer",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "outline", "outlineVariant",
    "error", "onError", "errorContainer", "onErrorContainer",
    "inverseSurface", "inverseOnSurface", "inversePrimary",
)

internal val THEME_EXTRA_TOKENS: List<String> = listOf(
    "data", "dataMuted", "dataTrack", "accentText",
    "danger", "dangerContainer", "warning", "warningContainer",
    "success", "successContainer",
)

internal val THEME_ALL_TOKENS: List<String> =
    THEME_REQUIRED_TOKENS + THEME_OPTIONAL_TOKENS + THEME_EXTRA_TOKENS

/** 必须对上的一对对色：前景要能从背景里读出来。 */
private val THEME_CONTRAST_PAIRS: List<Pair<String, String>> = listOf(
    "onPrimary" to "primary",
    "onSecondary" to "secondary",
    "onTertiary" to "tertiary",
    "onBackground" to "background",
    "onSurface" to "surface",
    "onSurfaceVariant" to "surfaceVariant",
    "onError" to "error",
)

/** 大段正文：标准比 4.5 更严一档。 */
private val THEME_BODY_PAIRS: Set<Pair<String, String>> = setOf(
    "onBackground" to "background",
    "onSurface" to "surface",
)

internal fun parseHexColor(raw: String): Color? {
    val text = raw.trim()
    val hex = if (text.startsWith("#")) text.substring(1) else text
    if (hex.length != 6) return null
    if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}

internal fun hexOf(color: Color): String {
    fun channel(value: Float): Int = (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return "#" + "%02X%02X%02X".format(channel(color.red), channel(color.green), channel(color.blue))
}

/**
 * 暗色派生的第一版：每个 token 的明度反相，色相/饱和度保留。
 *
 * 单纯反相会把「前景/背景」这一对也一起翻过来，原来 7:1 的一对翻完可能只剩 4:1。
 * 所以反相之后还要**逐对把前景往远处推**，推到对比度够为止 —— 派生不是估计，
 * 是一条能算清楚、也能被测试钉住的规则。
 */
internal fun invertedTone(tokens: Map<String, String>): Map<String, String> {
    val inverted = LinkedHashMap<String, String>()
    for ((name, value) in tokens) {
        val color = parseHexColor(value) ?: continue
        val (h, s, l) = toHsl(color)
        inverted[name] = hexOf(fromHsl(h, s, 1f - l))
    }
    for ((foreground, background) in THEME_CONTRAST_PAIRS) {
        val fg = inverted[foreground]?.let { parseHexColor(it) } ?: continue
        val bg = inverted[background]?.let { parseHexColor(it) } ?: continue
        val need = if ((foreground to background) in THEME_BODY_PAIRS) 7.0 else 4.5
        inverted[foreground] = hexOf(pushApart(fg, bg, need))
    }
    return inverted
}

/** 只动前景的明度，把它推到与背景的对比度达标为止。推到头还不够就返回最远的那一版。 */
private fun pushApart(foreground: Color, background: Color, minimum: Double): Color {
    if (contrastRatio(foreground, background) >= minimum) return foreground
    val (_, _, bgLightness) = toHsl(background)
    var (hue, saturation, lightness) = toHsl(foreground)
    repeat(200) {
        val step = if (lightness >= bgLightness) 0.01f else -0.01f
        val next = (lightness + step).coerceIn(0f, 1f)
        if (next == lightness) return fromHsl(hue, saturation, lightness)
        lightness = next
        val candidate = fromHsl(hue, saturation, lightness)
        if (contrastRatio(candidate, background) >= minimum) return candidate
    }
    return fromHsl(hue, saturation, lightness)
}

/** 数据/状态色必须对上的背景：图表与标签不是正文，门槛按用途给。 */
private val THEME_EXTRA_PAIRS: List<Triple<String, String, Double>> = listOf(
    Triple("data", "surface", 4.5),
    Triple("dataMuted", "surface", 3.0),
    Triple("danger", "surface", 4.5),
    Triple("warning", "surface", 4.5),
    Triple("accentText", "primaryContainer", 4.5),
    Triple("danger", "dangerContainer", 3.0),
    Triple("warning", "warningContainer", 3.0),
)

/**
 * 数据 / 状态色的校验。和 [themeProblems] 分开，是因为这几项是**可选岗位**：
 * 不写就用宿主默认，写了就不能写坏 —— 指标颜色一旦低于 4.5:1，
 * 图表和「逾期」标签会在某些主题里直接读不出来。
 */
internal fun extraProblems(extra: Map<String, String>, tokens: Map<String, String>): List<String> {
    val problems = mutableListOf<String>()
    val unknown = extra.keys.filter { it !in THEME_EXTRA_TOKENS }
    if (unknown.isNotEmpty()) {
        problems += "extra 里有宿主不认识的颜色：" + unknown.joinToString("、") +
            "。能用的是：" + THEME_EXTRA_TOKENS.joinToString("、")
    }
    for ((name, value) in extra) {
        if (parseHexColor(value) == null) {
            problems += "extra." + name + "「" + value + "」不是 #RRGGBB 颜色"
        }
    }
    for ((foreground, background, need) in THEME_EXTRA_PAIRS) {
        val fg = extra[foreground]?.let { parseHexColor(it) } ?: continue
        val bg = extra[background]?.let { parseHexColor(it) }
            ?: tokens[background]?.let { parseHexColor(it) }
            ?: continue
        val ratio = contrastRatio(fg, bg)
        if (ratio < need) {
            problems += "extra 的 " + foreground + " / " + background + " 对比度只有 " +
                oneDecimal(ratio) + ":1，至少要 " + oneDecimal(need) + ":1"
        }
    }
    // 「容器色」（青柠、状态底）是拿来当背景的，校验方向反过来：
    // 墨字写在它上面要能看清，而不是它自己写在纸上要能看清。
    val ink = tokens["onSurface"]?.let { parseHexColor(it) } ?: tokens["onBackground"]?.let { parseHexColor(it) }
    if (ink != null) {
        for (name in listOf("success", "successContainer", "dangerContainer", "warningContainer")) {
            val bg = extra[name]?.let { parseHexColor(it) } ?: continue
            val ratio = contrastRatio(ink, bg)
            if (ratio < 4.5) {
                problems += "extra 的 " + name + " 上写正文只有 " + oneDecimal(ratio) +
                    ":1，至少要 4.5:1（容器色是当底用的，要能压住墨字）"
            }
        }
    }
    return problems
}

internal fun extraColorsOf(theme: ThemeContribution?, dark: Boolean, scheme: ColorScheme): ExtraColors {
    val base = ExtraColors(
        data = scheme.primary,
        dataMuted = scheme.onSurfaceVariant,
        dataTrack = scheme.outlineVariant,
        accentText = scheme.onPrimaryContainer,
        danger = scheme.error,
        dangerContainer = scheme.errorContainer,
        warning = scheme.tertiary,
        warningContainer = scheme.tertiaryContainer,
        success = scheme.primaryContainer,
        successContainer = scheme.primaryContainer.copy(alpha = 0.35f),
    )
    val extra = theme?.extra
    if (extra.isNullOrEmpty()) return base

    val resolved: Map<String, Color> = if (dark) {
        // 主题只给浅色版：暗色按明度反相，再把「当文字用」的那几档推到达标。
        val inverted = LinkedHashMap<String, Color>()
        for ((name, value) in extra) {
            val color = parseHexColor(value) ?: continue
            val (h, s, l) = toHsl(color)
            inverted[name] = fromHsl(h, s, 1f - l)
        }
        for ((name, background, need) in listOf(
            Triple("data", scheme.surface, 4.5),
            Triple("dataMuted", scheme.surface, 3.0),
            Triple("danger", scheme.surface, 4.5),
            Triple("warning", scheme.surface, 4.5),
            Triple("accentText", scheme.primaryContainer, 4.5),
        )) {
            val fg = inverted[name] ?: continue
            inverted[name] = pushApart(fg, background, need)
        }
        inverted
    } else {
        extra.mapNotNull { (name, value) -> parseHexColor(value)?.let { name to it } }.toMap()
    }

    return ExtraColors(
        data = resolved["data"] ?: base.data,
        dataMuted = resolved["dataMuted"] ?: base.dataMuted,
        dataTrack = resolved["dataTrack"] ?: base.dataTrack,
        accentText = resolved["accentText"] ?: base.accentText,
        danger = resolved["danger"] ?: base.danger,
        dangerContainer = resolved["dangerContainer"] ?: base.dangerContainer,
        warning = resolved["warning"] ?: base.warning,
        warningContainer = resolved["warningContainer"] ?: base.warningContainer,
        success = resolved["success"] ?: base.success,
        successContainer = resolved["successContainer"] ?: base.successContainer,
    )
}

/**
 * 主题包能不能用。返回的是「不能用的原因」，空列表才算通过。
 * 这里报的每条都会**原样**交给 agent / 直接显示给人，所以要能照着改。
 */
internal fun themeProblems(light: Map<String, String>, dark: Map<String, String>): List<String> {
    val problems = mutableListOf<String>()
    problems += checkTokens("light", light)
    val darkTokens = if (dark.isNotEmpty()) {
        problems += checkTokens("dark", dark)
        dark
    } else {
        invertedTone(light)
    }
    for ((label, tokens) in listOf("light" to light, "dark" to darkTokens)) {
        for ((foreground, background) in THEME_CONTRAST_PAIRS) {
            val fg = tokens[foreground]?.let { parseHexColor(it) } ?: continue
            val bg = tokens[background]?.let { parseHexColor(it) } ?: continue
            val ratio = contrastRatio(fg, bg)
            val need = if ((foreground to background) in THEME_BODY_PAIRS) 7.0 else 4.5
            if (ratio < need) {
                problems += label + " 的 " + foreground + " / " + background + " 对比度只有 " +
                    oneDecimal(ratio) + ":1，至少要 " + oneDecimal(need) + ":1"
            }
        }
    }
    return problems
}

private fun checkTokens(label: String, tokens: Map<String, String>): List<String> {
    val problems = mutableListOf<String>()
    val unknown = tokens.keys.filter { it !in THEME_ALL_TOKENS }
    if (unknown.isNotEmpty()) {
        problems += label + " 里有宿主不认识的 token：" + unknown.joinToString("、") +
            "。能用的是：" + THEME_ALL_TOKENS.joinToString("、")
    }
    for ((name, value) in tokens) {
        if (parseHexColor(value) == null) {
            problems += label + " 的 " + name + "「" + value + "」不是 #RRGGBB 颜色"
        }
    }
    val missing = THEME_REQUIRED_TOKENS.filter { it !in tokens }
    if (missing.isNotEmpty()) {
        problems += label + " 缺必需的 token：" + missing.joinToString("、")
    }
    return problems
}

/** 一套主题的两套配色。暗色可能是插件给的，也可能是宿主派生的。 */
internal data class ThemeScheme(val light: ColorScheme, val dark: ColorScheme)

internal fun themeSchemeOf(theme: ThemeContribution): ThemeScheme? {
    if (themeProblems(theme.light, theme.dark).isNotEmpty()) return null
    if (extraProblems(theme.extra, theme.light).isNotEmpty()) return null
    val darkTokens = theme.dark.ifEmpty { invertedTone(theme.light) }
    val light = colorSchemeFrom(theme.light, dark = false) ?: return null
    val dark = colorSchemeFrom(darkTokens, dark = true) ?: return null
    return ThemeScheme(light, dark)
}

internal fun colorSchemeFrom(tokens: Map<String, String>, dark: Boolean): ColorScheme? {
    fun color(name: String, fallback: Color): Color = tokens[name]?.let { parseHexColor(it) } ?: fallback

    val surface = color("surface", if (dark) Color(0xFF1C1B1F) else Color(0xFFFFFBFE))
    val onSurface = color("onSurface", if (dark) Color(0xFFE6E1E5) else Color(0xFF1C1B1F))
    val background = color("background", surface)
    val onBackground = color("onBackground", onSurface)
    val surfaceVariant = color("surfaceVariant", if (dark) Color(0xFF49454F) else Color(0xFFE7E0EC))
    val onSurfaceVariant = color("onSurfaceVariant", if (dark) Color(0xFFCAC4D0) else Color(0xFF49454F))
    val primary = color("primary", if (dark) Color(0xFFD0BCFF) else Color(0xFF6750A4))
    val onPrimary = color("onPrimary", if (dark) Color(0xFF381E72) else Color.White)
    val primaryContainer = color("primaryContainer", if (dark) Color(0xFF4F378B) else Color(0xFFEADDFF))
    val onPrimaryContainer = color("onPrimaryContainer", if (dark) Color(0xFFEADDFF) else Color(0xFF21005D))
    val secondary = color("secondary", primary)
    val onSecondary = color("onSecondary", onPrimary)
    val secondaryContainer = color("secondaryContainer", primaryContainer)
    val onSecondaryContainer = color("onSecondaryContainer", onPrimaryContainer)
    val tertiary = color("tertiary", secondary)
    val onTertiary = color("onTertiary", onSecondary)
    val tertiaryContainer = color("tertiaryContainer", secondaryContainer)
    val onTertiaryContainer = color("onTertiaryContainer", onSecondaryContainer)
    val error = color("error", if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E))
    val onError = color("onError", if (dark) Color(0xFF690005) else Color.White)
    val errorContainer = color("errorContainer", if (dark) Color(0xFF93000A) else Color(0xFFF9DEDC))
    val onErrorContainer = color("onErrorContainer", if (dark) Color(0xFFFFDAD6) else Color(0xFF410E0B))
    val outline = color("outline", if (dark) Color(0xFF938F99) else Color(0xFF79747E))
    val outlineVariant = color("outlineVariant", if (dark) Color(0xFF49454F) else Color(0xFFCAC4D0))
    val inverseSurface = color("inverseSurface", onSurface)
    val inverseOnSurface = color("inverseOnSurface", surface)
    val inversePrimary = color("inversePrimary", primary)

    return if (dark) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary,
            onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            error = error,
            onError = onError,
            errorContainer = errorContainer,
            onErrorContainer = onErrorContainer,
            background = background,
            onBackground = onBackground,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            outlineVariant = outlineVariant,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            inversePrimary = inversePrimary,
        )
    }
}

private fun oneDecimal(value: Double): String = ((value * 10).toInt() / 10.0).toString()

private fun toHsl(color: Color): Triple<Float, Float, Float> {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    if (max == min) return Triple(0f, 0f, lightness)
    val delta = max - min
    val saturation = if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val rawHue = when (max) {
        r -> (g - b) / delta + (if (g < b) 6f else 0f)
        g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    }
    return Triple(rawHue / 6f, saturation, lightness)
}

private fun fromHsl(hue: Float, saturation: Float, lightness: Float): Color {
    if (saturation <= 0f) return Color(lightness, lightness, lightness)
    val q = if (lightness < 0.5f) {
        lightness * (1f + saturation)
    } else {
        lightness + saturation - lightness * saturation
    }
    val p = 2f * lightness - q
    fun channel(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(channel(hue + 1f / 3f), channel(hue), channel(hue - 1f / 3f))
}

/**
 * 主题还能带一点「外壳基因」：圆角多大、标签/标题用什么字族、整体缩放多少。
 *
 * 只开放这三类，并且每类都是**枚举或窄范围**：
 * 圆角 0..24，字族只认 default/mono/serif，缩放 0.85..1.2。原因和 UI 节点一样 ——
 * 外壳是宿主的，宿主得保证换端、暗色、无障碍之后这些值仍然成立；
 * 一旦允许插件写「任意字体名 / 任意 px」，就会在别的端上静默回退成默认值。
 */
internal val THEME_SHAPE_TOKENS: List<String> = listOf("cardRadius", "controlRadius")
internal val THEME_TYPE_TOKENS: List<String> = listOf("labelFamily", "titleFamily", "displayFamily", "scale")
private val THEME_FAMILIES: Set<String> = setOf("default", "mono", "serif", "condensed")

/** 外壳参数：解析后的值，全部带默认，宿主直接拿去建 Shapes/Typography。 */
internal data class ChromeStyle(
    val cardRadius: Int = 12,
    val controlRadius: Int = 8,
    val labelFamily: String = "default",
    val titleFamily: String = "default",
    /** 大数字（Stat、KPI、图表读数）用什么字族；样片这里用 condensed。 */
    val displayFamily: String = "default",
    val scale: Float = 1f,
)

internal fun chromeProblems(theme: ThemeContribution): List<String> {
    val problems = mutableListOf<String>()
    for ((name, value) in theme.shape) {
        if (name !in THEME_SHAPE_TOKENS) {
            problems += "shape 里有宿主不认识的 token：" + name +
                "。能用的是：" + THEME_SHAPE_TOKENS.joinToString("、")
            continue
        }
        val number = value.trim().toIntOrNull()
        if (number == null || number !in 0..24) {
            problems += "shape 的 " + name + "「" + value + "」要 0 到 24 之间的整数（单位 dp）"
        }
    }
    for ((name, value) in theme.type) {
        when (name) {
            "labelFamily", "titleFamily", "displayFamily" -> if (value.trim().lowercase() !in THEME_FAMILIES) {
                problems += "type 的 " + name + "「" + value + "」只认 default / mono / serif / condensed"
            }
            "scale" -> {
                val scale = value.trim().toFloatOrNull()
                if (scale == null || scale !in 0.85f..1.2f) {
                    problems += "type 的 scale「" + value + "」要 0.85 到 1.2 之间的小数"
                }
            }
            else -> problems += "type 里有宿主不认识的 token：" + name +
                "。能用的是：" + THEME_TYPE_TOKENS.joinToString("、")
        }
    }
    return problems
}

internal fun chromeOf(theme: ThemeContribution?): ChromeStyle {
    if (theme == null) return ChromeStyle()
    fun number(name: String, fallback: Int): Int =
        theme.shape[name]?.trim()?.toIntOrNull()?.takeIf { it in 0..24 } ?: fallback
    fun family(name: String): String =
        theme.type[name]?.trim()?.lowercase()?.takeIf { it in THEME_FAMILIES } ?: "default"
    val scale = theme.type["scale"]?.trim()?.toFloatOrNull()?.takeIf { it in 0.85f..1.2f } ?: 1f
    return ChromeStyle(
        cardRadius = number("cardRadius", 12),
        controlRadius = number("controlRadius", 8),
        labelFamily = family("labelFamily"),
        titleFamily = family("titleFamily"),
        displayFamily = family("displayFamily"),
        scale = scale,
    )
}
