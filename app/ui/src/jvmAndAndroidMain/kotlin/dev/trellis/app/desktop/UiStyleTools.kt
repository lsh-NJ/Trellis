package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 让 agent 能改宿主样式。
 *
 * 「优化一下任务界面的 UI」以前只能回 cannotDo：布局、配色、卡片外框、按钮位置都写死在
 * 宿主里，插件契约里根本没有这一层。这个工具补的是那条通路，不是新开一个例外 ——
 * 改的仍然是一份数据（[UiPrefs]），宿主照着它画，边界和权限都在宿主手里。
 */
class UiStyleTools(private val runtime: AppRuntime) {

    val guidance: String = listOf(
        "<how_to_style_the_ui>",
        "1. 用户说「界面不好看 / 优化一下 / 换个主题 / 字太小 / 导航放左边」时，先 list_ui_options 看现在什么样、能改什么，再 set_ui。",
        "2. 能改的有八样：外观 appearance（auto 跟随系统 / light 浅色 / dark 深色）、配色 palette（default/ocean/forest/sunset/mono/custom）、密度 density、卡片外观 card、圆角 radius（0-24）、字号 font_scale（0.85-1.25）、导航位置 nav（top 顶栏 / left 左侧栏）、导航底色 nav_tone（default 跟随主题 / dark 深色 / accent 主色）。",
        "2.0 用户问「为什么一半深色一半浅色 / 怎么不跟随系统」时：整页只认 appearance 这一个开关，先 list_ui_options 看它是 auto 还是被固定成了 light/dark；dark 卡片的文字用主题的 onSurface，别自己写颜色。",
        "2.1 想要「更冷/更暖/更安静」的配色时，把 palette 设成 custom，再用 hue（0-359，越大越偏暖紫→红→黄→绿→蓝→紫）和 chroma（0-100，0 是灰阶）调；先给一版，再按 describe_screen / take_screenshot 看效果微调，别一次跳到极端值。",
        "3. 不要一次改六样：先改一两样，再用 take_screenshot 或 describe_screen 核对，不满意再调。",
        "4. 改完要在回答里说清改了什么、为什么这样改；没有核对过的效果不要宣布成事实。",
        "5. 卡片**里面**长什么样是插件的（插件词汇表），卡片**外框/配色/密度/导航位置**是宿主的（这个工具）——两件事别混。",
        "6. 用户要「逐个插件调」时用 set_plugin_card：先 list_plugins（或 list_cards）拿到插件 id，只改点名的那几个，别的插件不动；没点名就保持全局样式。",
        "7. 主题包（L2）：list_themes 看当前主题和插件贡献的主题，set_theme 切换（builtin 回配色方案）。主题由插件贡献、宿主验 token 与对比度；装一个「主题插件」再 set_theme 就能整套换装。",
        "</how_to_style_the_ui>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(ListOptions(), SetUi(), SetPluginCard(), ListThemes(), SetTheme())

    private inner class ListOptions : AgentTool {
        override val name: String = "list_ui_options"
        override val description: String =
            "看当前界面偏好（配色/密度/卡片外观/圆角/字号），以及每样能选的档位。改样式之前先调这个。"
        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val prefs = runtime.uiPrefs
            val text = buildString {
                appendLine("当前界面偏好：" + prefs.describe())
                appendLine("外观：auto 跟随系统 / light 浅色 / dark 深色")
                appendLine("配色：default 默认紫 / ocean 海蓝 / forest 森林 / sunset 晚霞 / mono 灰阶 / custom 自定义（配 hue 色相 0-359 与 chroma 彩度 0-100）")
                appendLine("密度：compact 紧凑 / normal 标准 / relaxed 宽松")
                appendLine("卡片：bordered 描边圆角 / flat 扁平无边框 / filled 浅底填充")
                appendLine("圆角：0-24dp")
                appendLine("字号：0.85-1.25（85%-125%）")
                appendLine("导航：top 顶部标签栏 / left 左侧边栏（默认）")
                appendLine("导航底色：default 跟随主题 / dark 深色 / accent 主色")
                if (runtime.pluginCardStyles.isNotEmpty()) {
                    appendLine("单个插件的卡片样式（没列出的跟随全局）：")
                    runtime.pluginCardStyles.forEach { (id, style) ->
                        appendLine("- " + id.value + "：" + style.card +
                            (style.radius?.let { "，圆角 " + it + "dp" } ?: ""))
                    }
                }
            }
            return ToolResult(text)
        }
    }

    private inner class SetUi : AgentTool {
        override val name: String = "set_ui"
        override val description: String =
            "改界面偏好：外观（跟随系统/浅色/深色）、配色、密度、卡片外观、圆角、字号。只传要改的字段，立即生效并落盘。"
        override val parameters: String =
            "appearance：auto 跟随系统 / light 浅色 / dark 深色；" +
                "palette：default/ocean/forest/sunset/mono/custom；hue：palette=custom 时的色相 0-359；" +
                "chroma：palette=custom 时的彩度 0-100（0 灰阶）；density：compact/normal/relaxed；" +
                "card：bordered/flat/filled；radius：0-24 的整数；font_scale：0.85-1.25 的小数；" +
                "nav：top（顶部标签栏）/ left（左侧边栏，默认）；nav_tone：default/dark/accent（导航底色）。全部可选。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val current = runtime.uiPrefs
            fun str(key: String): String? = arguments[key]?.jsonPrimitive?.contentOrNull
            val next = UiPrefs(
                palette = str("palette") ?: current.palette,
                paletteHue = arguments["hue"]?.jsonPrimitive?.intOrNull ?: current.paletteHue,
                paletteChroma = arguments["chroma"]?.jsonPrimitive?.intOrNull ?: current.paletteChroma,
                density = str("density") ?: current.density,
                card = str("card") ?: current.card,
                radius = arguments["radius"]?.jsonPrimitive?.intOrNull ?: current.radius,
                fontScale = arguments["font_scale"]?.jsonPrimitive?.floatOrNull ?: current.fontScale,
                nav = str("nav") ?: current.nav,
                navTone = str("nav_tone") ?: current.navTone,
                // 主题是另一条通路（L2）：set_ui 不认识 theme，但也不能把它重置回默认。
                theme = current.theme,
                appearance = str("appearance") ?: current.appearance,
            )
            val message = runtime.updateUiPrefs(next)
            return ToolResult(if (next == current) "界面偏好没变：" + runtime.uiPrefs.describe() else message)
        }
    }

    private inner class SetPluginCard : AgentTool {
        override val name: String = "set_plugin_card"
        override val description: String =
            "只改某一个插件的卡片外框样式（bordered/flat/filled，可带圆角）。别的插件不受影响；没点名的插件保持全局样式。"
        override val parameters: String =
            "plugin：插件 id（如 ai.pushups，先用 list_plugins 或 list_cards 查）；" +
                "card：bordered 描边圆角 / flat 扁平无边框 / filled 浅底填充；radius：0-24（可选）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val plugin = arguments["plugin"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (plugin.isBlank()) return ToolResult("plugin 不能为空：要改哪个插件？", isError = true)
            val card = arguments["card"]?.jsonPrimitive?.contentOrNull
            val radius = arguments["radius"]?.jsonPrimitive?.intOrNull
            val message = runtime.setPluginCardStyle(plugin, card, radius)
            val bad = message.startsWith("没有这个插件") || message.startsWith("卡片样式只认")
            return ToolResult(message, isError = bad)
        }
    }

    private inner class ListThemes : AgentTool {
        override val name: String = "list_themes"
        override val description: String =
            "看当前主题和插件贡献的主题包（key、名字、能不能用）。换主题前先调这个。"
        override val parameters: String = "不需要参数。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val current = runtime.uiPrefs.theme.ifBlank { "builtin" }
            val options = runtime.themeOptions()
            val currentLabel = when (current) {
                "builtin" -> "builtin（跟随配色方案）"
                DEFAULT_THEME -> current + "（默认）"
                else -> current
            }
            val text = buildString {
                appendLine("当前主题：" + currentLabel)
                appendLine("builtin：内置，跟随 palette / hue / chroma 生成的配色方案")
                if (options.isEmpty()) {
                    appendLine("现在没有插件贡献主题。")
                } else {
                    appendLine("插件贡献的主题：")
                    for ((key, name) in options) {
                        val theme = runtime.themeContribution(key)
                        val problems = if (theme == null) {
                            listOf("找不到这个主题")
                        } else {
                            themeProblems(theme.light, theme.dark)
                        }
                        appendLine(
                            "- " + name + "　key=" + key + "　" +
                                if (problems.isEmpty()) "可用" else "不可用：" + problems.joinToString("；"),
                        )
                    }
                }
                appendLine(
                    "主题 token（light 必须给全，dark 缺省时宿主按明度反相派生）：" +
                        THEME_REQUIRED_TOKENS.joinToString("、"),
                )
            }
            return ToolResult(text.trimEnd())
        }
    }

    private inner class SetTheme : AgentTool {
        override val name: String = "set_theme"
        override val description: String =
            "切换主题：传 builtin，或「插件id/主题id」。宿主先验 token 与对比度，不合格会拒绝并说明原因。"
        override val parameters: String =
            "key：builtin，或插件贡献的主题 key（先用 list_themes 查）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val key = arguments["key"]?.jsonPrimitive?.contentOrNull
                ?: arguments["theme"]?.jsonPrimitive?.contentOrNull
            if (key.isNullOrBlank()) {
                return ToolResult("key 不能为空：要换成哪个主题？先用 list_themes 看。", isError = true)
            }
            val message = runtime.applyTheme(key)
            val bad = message.startsWith("没有这个主题") || message.startsWith("主题「")
            return ToolResult(message, isError = bad)
        }
    }
}
