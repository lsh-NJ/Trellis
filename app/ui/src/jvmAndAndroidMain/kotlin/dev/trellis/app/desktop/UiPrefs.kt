package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
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

/**
 * 默认主题：内置的「书桌」（`dev.trellis.desk/desk`）。
 *
 * 2026-11-06 用户拍板（评审决策 1）：Bento 和书桌两套都装好、并排看过截图之后，
 * **书桌当默认**，Bento 退为可选。两套都随应用编译进来（[DeskThemePlugin] /
 * [BentoThemePlugin]），不依赖 `~/.trellis/plugins/` 里的文件；旧机器上已经明确
 * 选过主题的仍按 `ui.json` 走，不会被默认值悄悄换掉。
 * 想回到「跟随 palette 的默认紫」，把 `ui.json` 的 theme 写成 `builtin` 即可。
 */
internal const val DEFAULT_THEME: String = DESK_THEME_KEY

/** 默认导航：左侧边栏。宽屏上比顶栏省纵向空间，任务页首屏能多放一张卡。 */
internal const val DEFAULT_NAV: String = "left"

/**
 * 「圆角跟随主题」的哨兵值。
 *
 * 圆角是**主题的壳基因**（Bento 3/3、书桌 8/4），不该由偏好写死一个数字；
 * 用户手动改过才覆盖。以前的默认值 12 是宿主自己拍的，结果是同一屏上
 * 「主题说 3、宿主画 12、控件画 6」三套圆角。
 */
internal const val FOLLOW_THEME_RADIUS: Int = -1

/** 设置页「圆角」那组里给用户看的档位：跟随主题 + 0/4/8/12/16。 */
internal val RADIUS_CHOICES: List<Int> = listOf(FOLLOW_THEME_RADIUS, 0, 4, 8, 12, 16)

/** 设置页「字号」那组的档位；和 [UiPrefs.fontScale] 的夹取范围一致。 */
internal val FONT_SCALE_CHOICES: List<Float> = listOf(0.85f, 1f, 1.15f)

data class UiPrefs(
    val palette: String = "default",
    /** 自定义配色用：色相 0-359。预设配色的色相是写好的参数，这两个值只在 palette=custom 时生效。 */
    val paletteHue: Int = DEFAULT_HUE,
    /** 自定义配色用：彩度 0-100（0 是灰阶）。 */
    val paletteChroma: Int = DEFAULT_CHROMA,
    val density: String = "normal",
    val card: String = "bordered",
    /** 卡片圆角（dp）；[FOLLOW_THEME_RADIUS] = 跟主题走（推荐）。 */
    val radius: Int = FOLLOW_THEME_RADIUS,
    val fontScale: Float = 1f,
    val nav: String = DEFAULT_NAV,
    val navTone: String = "default",
    val theme: String = DEFAULT_THEME,
    /** auto = 跟随系统；light / dark = 明确指定。一整页只认这一个开关。 */
    val appearance: String = "auto",
    /** 笔记自动完成计划节点时要不要弹桌面通知（默认关，设置页可开）。 */
    val notifyAutoCompletion: Boolean = false,
) {
    fun normalized(): UiPrefs = copy(
        palette = palette.takeIf { it in PALETTES } ?: "default",
        paletteHue = ((paletteHue % 360) + 360) % 360,
        paletteChroma = paletteChroma.coerceIn(0, 100),
        density = density.takeIf { it in DENSITIES } ?: "normal",
        card = card.takeIf { it in CARDS } ?: "bordered",
        radius = if (radius < 0) FOLLOW_THEME_RADIUS else radius.coerceIn(0, 24),
        fontScale = fontScale.coerceIn(0.85f, 1.25f),
        nav = nav.takeIf { it in NAVS } ?: DEFAULT_NAV,
        navTone = navTone.takeIf { it in NAV_TONES } ?: "default",
        theme = theme.ifBlank { DEFAULT_THEME },
        appearance = appearance.takeIf { it in APPEARANCES } ?: "auto",
    )

    fun describe(): String = listOf(
        "外观 " + appearanceLabel(appearance),
        "配色 " + palette + "（" + paletteLabel(palette) + "）",
        if (palette == "custom") "自定义色相 " + paletteHue + "° / 彩度 " + paletteChroma else "",
        "主题 " + themeLabel(theme),
        "密度 " + density + "（" + densityLabel(density) + "）",
        "卡片 " + card + "（" + cardLabel(card) + "）",
        "圆角 " + if (radius < 0) "跟随主题" else (radius.toString() + "dp"),
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

internal fun themeLabel(theme: String): String = when (theme) {
    "", "builtin" -> "跟随配色"
    DESK_THEME_KEY -> "书桌（默认）"
    "dev.bento.shift/bento" -> "Bento"
    else -> theme
}

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

/**
 * 密度**不再**缩放 [androidx.compose.ui.unit.Density]。
 *
 * 旧实现给 compact 乘 1.12、relaxed 乘 0.9，注释还写着「数字大 = 更紧凑」——
 * 但乘在 Density 上会把字号、行高、间距一起放大，于是「紧凑」比「标准」还大 12%，
 * 这是评审里「密度方向反了」的根因。现在密度只改行高与内边距，
 * 落在 [TokenRows]（32 / 40 / 48），字号由 [UiPrefs.fontScale] 单独管。
 */

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
            radius = obj["radius"]?.jsonPrimitive?.intOrNull ?: FOLLOW_THEME_RADIUS,
            fontScale = obj["fontScale"]?.jsonPrimitive?.floatOrNull ?: 1f,
            nav = str("nav") ?: DEFAULT_NAV,
            navTone = str("navTone") ?: "default",
            theme = str("theme") ?: DEFAULT_THEME,
            appearance = str("appearance") ?: "auto",
            notifyAutoCompletion = obj["notifyAutoCompletion"]?.jsonPrimitive?.booleanOrNull ?: false,
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
            put("notifyAutoCompletion", clean.notifyAutoCompletion)
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }
}
