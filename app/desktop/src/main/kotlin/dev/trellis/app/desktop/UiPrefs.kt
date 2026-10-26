package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 界面偏好：用户（或 agent）能改的**宿主样式**。
 *
 * 为什么是枚举和受限数值，不是任意十六进制颜色、任意像素：
 * 宿主得保证可读性、对比度、跨端一致；「随便改」换来的是一堆谁也看不懂、
 * 换台机器就崩的界面。所以这里只开放一组语义档位，宿主负责把它们翻译成
 * ColorScheme / 密度 / 圆角。
 *
 * 和插件契约的分工：插件决定卡片**里面**写什么；这里决定宿主**怎么画**卡片、
 * 怎么排间距、用什么主色。改的是宿主，不是插件。
 */
data class UiPrefs(
    val palette: String = "default",
    /** 自定义配色用：色相 0-359。预设配色的色相是写好的参数，这两个值只在 palette=custom 时生效。 */
    val paletteHue: Int = DEFAULT_HUE,
    /** 自定义配色用：彩度 0-100（0 是灰阶）。 */
    val paletteChroma: Int = DEFAULT_CHROMA,
    val density: String = "normal",
    val card: String = "bordered",
    val radius: Int = 12,
    val fontScale: Float = 1f,
    val nav: String = "top",
    val navTone: String = "default",
    val theme: String = "builtin",
    /** auto = 跟随系统；light / dark = 明确指定。一整页只认这一个开关。 */
    val appearance: String = "auto",
) {
    fun normalized(): UiPrefs = copy(
        palette = palette.takeIf { it in PALETTES } ?: "default",
        paletteHue = ((paletteHue % 360) + 360) % 360,
        paletteChroma = paletteChroma.coerceIn(0, 100),
        density = density.takeIf { it in DENSITIES } ?: "normal",
        card = card.takeIf { it in CARDS } ?: "bordered",
        radius = radius.coerceIn(0, 24),
        fontScale = fontScale.coerceIn(0.85f, 1.25f),
        nav = nav.takeIf { it in NAVS } ?: "top",
        navTone = navTone.takeIf { it in NAV_TONES } ?: "default",
        theme = theme.ifBlank { "builtin" },
        appearance = appearance.takeIf { it in APPEARANCES } ?: "auto",
    )

    fun describe(): String = listOf(
        "外观 " + appearanceLabel(appearance),
        "配色 " + palette + "（" + paletteLabel(palette) + "）",
        if (palette == "custom") "自定义色相 " + paletteHue + "° / 彩度 " + paletteChroma else "",
        "主题 " + themeLabel(theme),
        "密度 " + density + "（" + densityLabel(density) + "）",
        "卡片 " + card + "（" + cardLabel(card) + "）",
        "圆角 " + radius + "dp",
        "字号 " + (Math.round(fontScale * 100)) + "%",
        "导航 " + navLabel(nav) + "（" + navToneLabel(navTone) + "底）",
    ).filter { it.isNotBlank() }.joinToString("，")
}

internal val APPEARANCES = listOf("auto", "light", "dark")

internal fun appearanceLabel(appearance: String): String = when (appearance) {
    "light" -> "浅色"
    "dark" -> "深色"
    else -> "跟随系统"
}

internal val PALETTES = listOf("default", "ocean", "forest", "sunset", "mono", "custom")
internal val DENSITIES = listOf("compact", "normal", "relaxed")
internal val CARDS = listOf("bordered", "flat", "filled", "bento")
internal val NAVS = listOf("top", "left")
internal val NAV_TONES = listOf("default", "dark", "accent")

internal fun navLabel(nav: String): String = if (nav == "left") "左侧边栏" else "顶部标签栏"

internal fun navToneLabel(tone: String): String = when (tone) {
    "dark" -> "深色"
    "accent" -> "主色"
    else -> "跟随主题"
}

internal fun paletteLabel(palette: String): String = when (palette) {
    "ocean" -> "海蓝"
    "forest" -> "森林"
    "sunset" -> "晚霞"
    "mono" -> "灰阶"
    "custom" -> "自定义"
    else -> "默认紫"
}

internal fun themeLabel(theme: String): String =
    if (theme == "builtin" || theme.isBlank()) "跟随配色" else theme

internal fun densityLabel(density: String): String = when (density) {
    "compact" -> "紧凑"
    "relaxed" -> "宽松"
    else -> "标准"
}

internal fun cardLabel(card: String): String = when (card) {
    "flat" -> "扁平无边框"
    "filled" -> "浅底填充"
    "bento" -> "硬边贴纸（bento）"
    else -> "描边圆角"
}

/** 密度 → 显示比例的乘数。数字大 = 同样 dp 占更多像素 = 看起来更紧凑。 */
internal fun densityScale(density: String): Float = when (density) {
    "compact" -> 1.12f
    "relaxed" -> 0.9f
    else -> 1f
}

/** 落盘在宿主目录的 `ui.json`；坏文件当没改过，不阻断启动。 */
internal class UiPrefsStore(home: File) {
    private val file = File(home, "ui.json")

    fun load(): UiPrefs {
        // 有的编辑器/管道会写 UTF-8 BOM；JSON 解析器不认它，会让整份偏好悄悄退回默认值。
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF") ?: return UiPrefs()
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return UiPrefs()
        fun str(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull
        return UiPrefs(
            palette = str("palette") ?: "default",
            paletteHue = obj["paletteHue"]?.jsonPrimitive?.intOrNull ?: DEFAULT_HUE,
            paletteChroma = obj["paletteChroma"]?.jsonPrimitive?.intOrNull ?: DEFAULT_CHROMA,
            density = str("density") ?: "normal",
            card = str("card") ?: "bordered",
            radius = obj["radius"]?.jsonPrimitive?.intOrNull ?: 12,
            fontScale = obj["fontScale"]?.jsonPrimitive?.floatOrNull ?: 1f,
            nav = str("nav") ?: "top",
            navTone = str("navTone") ?: "default",
            theme = str("theme") ?: "builtin",
            appearance = str("appearance") ?: "auto",
        ).normalized()
    }

    fun save(prefs: UiPrefs) {
        val clean = prefs.normalized()
        val root = buildJsonObject {
            put("palette", clean.palette)
            put("paletteHue", clean.paletteHue)
            put("paletteChroma", clean.paletteChroma)
            put("density", clean.density)
            put("card", clean.card)
            put("radius", clean.radius)
            put("fontScale", clean.fontScale)
            put("nav", clean.nav)
            put("navTone", clean.navTone)
            put("theme", clean.theme)
            put("appearance", clean.appearance)
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }
}
