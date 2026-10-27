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
 * Bento 主题（BENTO SHIFT），作为**内置插件**随应用一起发。
 *
 * 为什么主题要当插件、而不是宿主里写死一套颜色：
 * 主题的来历、版本、卸载清理、能力审查，插件体系已经全兜住了；宿主再写死的
 * 话，「换掉这套配色」就退化成改代码重新发版，违背「界面也是插件」这条线。
 *
 * 为什么又要**内置**（而不是留在 `~/.trellis/plugins/` 里）：
 * 它是默认主题包的提供者，属于「装好就有」的东西。放在插件目录里，一次误停用
 * 就整页回落到默认紫，而且插件列表里一旦被删掉就找不回来 —— 默认能力不该依赖
 * 用户目录里的一份脚本。内置插件仍然出现在插件列表里，可以停用、可以看贡献，
 * 和 `core.plan` 是同一类东西。
 *
 * 这个类的 id 与取值沿用最初的外部样片插件（`dev.bento.shift` / `bento`），
 * 这样已经存在 `ui.json` 里的 `"theme": "dev.bento.shift/bento"` 不用迁移。
 * 原来那份脚本插件（`~/.trellis/plugins/a-shift-bento`）可以删掉，内容已搬到这里。
 */
internal class BentoThemePlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("dev.bento.shift"),
        version = SemVer.parse("1.0.0"),
        name = "Bento 主题",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        description = "纸感米白 / 荧光青柠 / 墨黑的主题包；内置，可在插件页停用",
        usage = "装上（默认就有）之后，在界面偏好里选「Bento」；卡片样式建议用 bento",
        limitation = "只给 token（配色 + 圆角 + 字族），字体文件和排布仍由宿主决定",
        contributes = mapOf(
            KernelPoints.Theme.id to listOf(ContributionDecl(THEME_ID)),
        ),
    )

    /**
     * 主题本体。抽成属性而不是写在 [init] 里，测试才能在不启内核的情况下
     * 直接拿它过一遍校验（词表、色值、对比度）。
     */
    internal val theme: ThemeContribution = ThemeContribution(
        id = THEME_ID,
        name = "Bento",
        description = "纸感米白 / 荧光青柠 / 墨黑；编辑风、硬边小圆角",
        light = mapOf(
            "primary" to "#141414",
            "onPrimary" to "#FFFDF7",
            "primaryContainer" to "#D6F24B",
            "onPrimaryContainer" to "#141414",
            "secondary" to "#3A5BE0",
            "onSecondary" to "#FFFFFF",
            "secondaryContainer" to "#E8E4D6",
            "onSecondaryContainer" to "#141414",
            "tertiary" to "#4B6B00",
            "onTertiary" to "#FFFFFF",
            "background" to "#F7F5EE",
            "onBackground" to "#141414",
            "surface" to "#FFFDF7",
            "onSurface" to "#141414",
            "surfaceVariant" to "#EFEBDD",
            "onSurfaceVariant" to "#4B473C",
            "outline" to "#B9B3A2",
            "outlineVariant" to "#E2DED2",
            "error" to "#B3261E",
            "onError" to "#FFFFFF",
        ),
        dark = mapOf(
            "primary" to "#D6F24B",
            "onPrimary" to "#141414",
            "primaryContainer" to "#2A2E12",
            "onPrimaryContainer" to "#EAF7B8",
            "secondary" to "#A9B8FF",
            "onSecondary" to "#141414",
            "secondaryContainer" to "#23252E",
            "onSecondaryContainer" to "#DCE1FF",
            "tertiary" to "#D6F24B",
            "onTertiary" to "#141414",
            "background" to "#141414",
            "onBackground" to "#F7F5EE",
            "surface" to "#1C1B19",
            "onSurface" to "#F7F5EE",
            "surfaceVariant" to "#262521",
            "onSurfaceVariant" to "#C9C4B4",
            "outline" to "#6C685D",
            "outlineVariant" to "#3A3832",
            "error" to "#F2B8B5",
            "onError" to "#2A1110",
        ),
        // 外壳基因：样片是 3px 小圆角、等宽小标、编辑风。宿主有词表和范围校验。
        shape = mapOf(
            "cardRadius" to "3",
            "controlRadius" to "3",
        ),
        type = mapOf(
            "labelFamily" to "mono",
            "titleFamily" to "default",
            "displayFamily" to "condensed",
            "scale" to "1",
        ),
        // 岗位色：绘图只许取 data 系，逾期只用 danger，强调底上的字只用 accentText。
        extra = mapOf(
            "data" to "#2C4A63",
            "dataMuted" to "#5E7891",
            "dataTrack" to "#E2DED2",
            "accentText" to "#4F6A12",
            "danger" to "#B23A2E",
            "dangerContainer" to "#F7E4DF",
            "warning" to "#8F5C12",
            "warningContainer" to "#F6EBD6",
            "success" to "#D6F24B",
            "successContainer" to "#EEF7C4",
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        ctx.extensions.register(KernelPoints.Theme, theme)
    }

    private companion object {
        const val THEME_ID = "bento"
    }
}
