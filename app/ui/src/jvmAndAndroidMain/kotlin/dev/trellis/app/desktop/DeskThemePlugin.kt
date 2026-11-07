package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.theme.ThemeContribution

/**
 * 书桌主题（「一张安静的书桌 + 一支荧光笔」），第二个**内置**主题。
 *
 * 来历：2026-11-06 的 UI 评审给出的不是一个换皮，而是一套目标气质——冷灰纸底、
 * 墨色正文、低饱和黑板绿当唯一品牌色、荧光黄只用来标 AI 与概念。评审把它写成
 * 方案 B。第一轮先把 Bento 和书桌都装好、并排看截图，2026-11-06 用户定了：
 * **书桌当默认**（`DEFAULT_THEME`），Bento 退为可选。
 *
 * 它和 Bento 的差别不是颜色列表不同，而是**壳的基因**不同：
 * - 圆角：控件 4、卡片 8（Bento 是 3/3、插件卡 12）——桌面软件该有的软硬；
 * - 字族：三族全部 default，没有等宽小标、没有窄体数字。中文界面用窄体做数字
 *   只会让「12 分钟」和「12分钟」看起来像两种字体；
 * - 青柠退场：mark 换成荧光黄 #F3D35B，且只允许当底（`onMark` 是墨色）。
 *
 * 取值全部来自评审文档 §8.1，且都过了 ThemePacks 的双重门槛：文字对 ≥4.5
 * （正文 7.0）、强线 ≥3:1、mark 上的字 ≥4.5。改色之前先读 `ThemePacksTest`。
 */
/** 书桌主题在 `ui.json` 里的 key；设置页、测试、描述都从这里取，别再各写一遍字符串。 */
internal const val DESK_THEME_KEY: String = "dev.trellis.desk/desk"

internal class DeskThemePlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("dev.trellis.desk"),
        version = SemVer.parse("1.0.0"),
        name = "书桌主题",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        description = "冷灰纸 / 墨色字 / 黑板绿 + 荧光笔；安静、克制、长读友好",
        usage = "想要「读书写字」而不是「看板」的气质就切到它；和 Bento 并存，可随时换回",
        limitation = "只给 token（配色 + 圆角 + 字族），字体文件和排布仍由宿主决定",
        contributes = mapOf(
            KernelPoints.Theme.id to listOf(ContributionDecl(THEME_ID)),
        ),
    )

    internal val theme: ThemeContribution = ThemeContribution(
        id = THEME_ID,
        name = "书桌",
        description = "冷灰纸 / 墨色字 / 黑板绿；荧光黄只标 AI 与概念",
        light = mapOf(
            "primary" to "#2D6A55",
            "onPrimary" to "#FFFFFF",
            "primaryContainer" to "#D8E9E0",
            "onPrimaryContainer" to "#163A2D",
            "secondary" to "#3E5A6E",
            "onSecondary" to "#FFFFFF",
            "secondaryContainer" to "#DDE6EE",
            "onSecondaryContainer" to "#16324A",
            // tertiary 就是 token 里的 info 角色（蓝）。
            "tertiary" to "#2D5B8A",
            "onTertiary" to "#FFFFFF",
            "tertiaryContainer" to "#DCE8F4",
            "onTertiaryContainer" to "#16324A",
            "background" to "#F2F3F0",
            "onBackground" to "#1C2124",
            "surface" to "#FBFBF9",
            "onSurface" to "#1C2124",
            "surfaceVariant" to "#E9EBE6",
            "onSurfaceVariant" to "#454D52",
            "outline" to "#868F89",
            "outlineVariant" to "#D3D8D2",
            "error" to "#B3362B",
            "onError" to "#FFFFFF",
            "errorContainer" to "#F7E4DF",
            "onErrorContainer" to "#6E1A12",
        ),
        dark = mapOf(
            "primary" to "#7DB89F",
            "onPrimary" to "#0E1F18",
            "primaryContainer" to "#203A30",
            "onPrimaryContainer" to "#CFE6DA",
            "secondary" to "#9FB3C6",
            "onSecondary" to "#0E181F",
            "secondaryContainer" to "#24313C",
            "onSecondaryContainer" to "#D5E1EA",
            "tertiary" to "#8DB8E6",
            "onTertiary" to "#0A2036",
            "tertiaryContainer" to "#1D3348",
            "onTertiaryContainer" to "#D3E4F5",
            "background" to "#141719",
            "onBackground" to "#E5E8E6",
            "surface" to "#1B1F22",
            "onSurface" to "#E5E8E6",
            "surfaceVariant" to "#101214",
            "onSurfaceVariant" to "#B3BAB7",
            "outline" to "#677069",
            "outlineVariant" to "#2B3134",
            "error" to "#F0857A",
            "onError" to "#2A1110",
            "errorContainer" to "#5A1F18",
            "onErrorContainer" to "#FBDDD6",
        ),
        // 桌面软件该有的圆角：控件 4、卡片 8，不是 3，也不是 12。
        shape = mapOf(
            "cardRadius" to "8",
            "controlRadius" to "4",
        ),
        // 三族全部 default：没有等宽小标、没有窄体数字，中文和数字同一条基线。
        type = mapOf(
            "labelFamily" to "default",
            "titleFamily" to "default",
            "displayFamily" to "default",
            "scale" to "1",
        ),
        extra = mapOf(
            "data" to "#2D6A55",
            "dataMuted" to "#868F89",
            "dataTrack" to "#D3D8D2",
            "accentText" to "#163A2D",
            "danger" to "#B3362B",
            "dangerContainer" to "#F7E4DF",
            "warning" to "#8A5A00",
            "warningContainer" to "#F6EBD6",
            // success 在 ExtraColors 里是「底」的语义，所以给浅绿底而不是 #2E6B3A ——
            // 那个绿是文字色，放在 success 位上会有墨色压深绿、通不过容器对比度检查。
            "success" to "#D8E9E0",
            "successContainer" to "#EEF4E8",
            "mark" to "#F3D35B",
            "onMark" to "#1C2124",
            // 天格「这天收工了」的印章：书桌用粉。
            "dayDone" to "#F4CBD5",
            "onDayDone" to "#4A1420",
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        ctx.extensions.register(KernelPoints.Theme, theme)
    }

    private companion object {
        const val THEME_ID = "desk"
    }
}
