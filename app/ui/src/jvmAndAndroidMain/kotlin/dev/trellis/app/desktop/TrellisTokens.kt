package dev.trellis.app.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ui.ExtraColors

/**
 * 设计 token：宿主界面里**所有**间距、圆角、行高、字级、颜色角色都从这里取。
 *
 * 为什么要有这一层（2026-11-06 UI 评审的诊断）：
 * 以前颜色有三套来源（M3 ColorScheme / ExtraColors 岗位色 / 各处写死的 `Color(0x…)`），
 * 几何量则是 982 处 dp 字面量加 12 种圆角；主题包声明了圆角 3，控件却写死 6、
 * 插件卡用 12 —— 同一屏上三种圆角语言。这个文件把「能设计的东西」收成一等公民，
 * 组件只许读角色，不许再写数字。
 *
 * 和 M3 的关系：**不是替代，是收敛**。`MaterialTheme.colorScheme` 仍然由主题包
 * 决定（[TokenColors] 只是把它的语义重新命名成设计角色，再加两个它没有的：
 * 发丝线 [TokenColors.rule] 与必须过 3:1 的 [TokenColors.ruleStrong]）；
 * 字号角色是 M3 Typography 的目标值，批 2 会把 `Typography` 接到这张表上，
 * 现在先把表钉住，免得又出现「文档写 20/28、代码里 11sp」的漂移。
 *
 * 密度：**不缩放 [Density]**。以前 compact 是把 Density 乘 1.12，连文字一起放大
 * 12%，于是「紧凑」比「标准」还大。密度只该改行高和内边距（[TokenRows]），
 * 字号由 [UiPrefs.fontScale] 单独管。
 */
@Immutable
internal data class TrellisTokens(
    val colors: TokenColors,
    val type: TokenType,
    val space: TokenSpace,
    val radius: TokenRadius,
    val rows: TokenRows,
) {
    companion object {
        fun of(
            prefs: UiPrefs,
            chrome: ChromeStyle,
            scheme: ColorScheme,
            extra: ExtraColors,
        ): TrellisTokens = TrellisTokens(
            colors = tokenColors(scheme, extra),
            type = tokenType(chrome),
            space = TokenSpace(),
            radius = TokenRadius(
                control = chrome.controlRadius.dp,
                // 用户显式改过圆角就用他的；没改过（FOLLOW_THEME_RADIUS）跟主题走。
                card = if (prefs.radius < 0) chrome.cardRadius.dp else prefs.radius.dp,
            ),
            rows = TokenRows.of(prefs.density),
        )

        /** 只给预览/离屏测试用；真实界面一律由 [AppTheme] 注入。 */
        fun fallback(): TrellisTokens = of(UiPrefs(), ChromeStyle(), lightColorScheme(), ExtraColors())
    }
}

/** 颜色角色：一屏最多 3 个强调来源（accent / mark / danger-warn），其余交给 ink 阶梯。 */
@Immutable
internal data class TokenColors(
    /** 页面底。 */
    val bg: Color,
    /** 卡片/浮层表面。 */
    val surface: Color,
    /** 凹下去的底（分组底、轨道、输入框内底）。 */
    val sunken: Color,
    /** 正文墨色。 */
    val ink: Color,
    /** 次级文字。 */
    val ink2: Color,
    /** 元信息：时间、计数、注释；**不达 AA 的浅灰不再出现在文字上**。 */
    val ink3: Color,
    /** 装饰发丝线：卡片分隔，不承担「控件边界」职责，不要求 3:1。 */
    val rule: Color,
    /** 强线：控件边界、表格线、未选中单选框，保证对表面 ≥3:1。 */
    val ruleStrong: Color,
    /** 动作色（按钮、选中、链接）。 */
    val accent: Color,
    val onAccent: Color,
    /** 强调底（选中行底、AI 建议底）。 */
    val accentSoft: Color,
    val onAccentSoft: Color,
    /** 荧光笔：只用来标 AI 撰写与概念高亮，绝不当正文色。 */
    val mark: Color,
    val onMark: Color,
    val dayDone: Color,
    val onDayDone: Color,
    val danger: Color,
    val warn: Color,
    /** 完成/通过，保证对表面可读（主题给的是底色的场合退回 accent）。 */
    val ok: Color,
    /** 信息/中性数据，复用 tertiary。 */
    val info: Color,
)

/** 字号角色。行高与字号成对出现，避免「11sp 用了 22 行高」这种隐性放大。 */
@Immutable
internal data class TokenType(
    /** 11/16：最短的元信息。 */
    val caption: TextStyle,
    /** 12/18：标签、状态、列表副行。 */
    val meta: TextStyle,
    /** 13/20/500：控件文字。 */
    val control: TextStyle,
    /** 14/22：界面正文。 */
    val body: TextStyle,
    /** 16/24/600：卡片标题、区段标题。 */
    val title: TextStyle,
    /** 20/28/600：页标题。 */
    val page: TextStyle,
    /** 28/34/600 等宽数字：KPI、读数。 */
    val stat: TextStyle,
    /** 16/28：长文阅读（对话正文、笔记正文）。 */
    val read: TextStyle,
    /** 13/20：代码与时间戳。 */
    val code: TextStyle,
)

/** 间距阶梯：4 的倍数，只有这七档。 */
@Immutable
internal data class TokenSpace(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val huge: Dp = 48.dp,
)

/** 圆角：控件一档、卡片一档、浮层一档，胶囊单独一档。 */
@Immutable
internal data class TokenRadius(
    val control: Dp,
    val card: Dp,
    val overlay: Dp = 12.dp,
    val pill: Dp = 999.dp,
)

/**
 * 行高：密度的**唯一**落点。
 *
 * 这三档不是随便定的：32 是「一屏能扫完的任务表」，40 是「有目标的点击行」
 * （44 是触摸底线，桌面行不需要），48 是「宽松到能喘气」。控件高度这一轮不动，
 * 仍是 [ControlHeight] 的 44dp，等批 2 的统一组件再按场景取 32/36/40。
 */
@Immutable
internal data class TokenRows(
    val compact: Dp,
    val standard: Dp,
    val comfortable: Dp,
    /** 当前密度对应的行高，组件直接用这个。 */
    val active: Dp,
) {
    companion object {
        fun of(density: String): TokenRows = when (density) {
            "compact" -> TokenRows(32.dp, 40.dp, 48.dp, 32.dp)
            "relaxed" -> TokenRows(32.dp, 40.dp, 48.dp, 48.dp)
            else -> TokenRows(32.dp, 40.dp, 48.dp, 40.dp)
        }
    }
}

internal val LocalTrellisTokens = staticCompositionLocalOf { TrellisTokens.fallback() }

// ---------------------------------------------------------------------------
// 派生：主题/偏好 → 角色
// ---------------------------------------------------------------------------

private fun tokenColors(scheme: ColorScheme, extra: ExtraColors): TokenColors {
    // 强线必须真的看得见：主题给的 outline 不够 3:1 就退到 onSurfaceVariant。
    // 这不是「兜底配色」——目标主题都会自己把 outline 调到 3:1 以上，这里是防
    // 第三方主题包把边界线交成 2:1 的浅灰（评审里 Bento 的卡片线就是这么来的）。
    val ruleStrong = if (contrastRatio(scheme.outline, scheme.surface) >= 3.0) {
        scheme.outline
    } else {
        scheme.onSurfaceVariant
    }
    // 完成色：主题的 success 是「底」（青柠那种），不能直接当文字；当文字不达标就用动作色。
    val ok = if (contrastRatio(extra.success, scheme.surface) >= 4.5) extra.success else scheme.primary
    // 元信息色也是**文字**，必须过 4.5。暗色下 dataMuted 只被推到 3:1（它是岗位色，
    // 主要给图表），所以当文字不达标时退到 onSurfaceVariant —— 它是保证了正文级对比的。
    val ink3 = if (contrastRatio(extra.dataMuted, scheme.surface) >= 4.5) {
        extra.dataMuted
    } else {
        scheme.onSurfaceVariant
    }
    return TokenColors(
        bg = scheme.background,
        surface = scheme.surface,
        sunken = scheme.surfaceVariant,
        ink = scheme.onSurface,
        ink2 = scheme.onSurfaceVariant,
        ink3 = ink3,
        rule = scheme.outlineVariant,
        ruleStrong = ruleStrong,
        accent = scheme.primary,
        onAccent = scheme.onPrimary,
        accentSoft = scheme.primaryContainer,
        onAccentSoft = scheme.onPrimaryContainer,
        mark = extra.mark,
        onMark = extra.onMark,
        dayDone = extra.dayDone,
        onDayDone = extra.onDayDone,
        danger = extra.danger,
        warn = extra.warning,
        ok = ok,
        info = scheme.tertiary,
    )
}

/**
 * 字号角色。`chrome.scale` 是主题包给的整页缩放（0.85–1.2），乘在字号和行高上。
 *
 * 中文字面量一律 `FontFamily.Default`：包里那两个字体（Barlow Condensed、IBM Plex Mono）
 * 都没有 CJK 字形，拿它们排中文会掉回系统字体但数字/英文仍是等宽，混排看起来是碎的。
 * 只有 [TokenType.code] 用等宽（代码、时间戳，那里没有中文）。
 */
private fun tokenType(chrome: ChromeStyle): TokenType {
    val s = chrome.scale
    fun role(
        size: Int,
        line: Int,
        weight: FontWeight = FontWeight.Normal,
        family: FontFamily = FontFamily.Default,
        features: String? = null,
    ): TextStyle = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * s).sp,
        lineHeight = (line * s).sp,
        fontFeatureSettings = features,
    )
    return TokenType(
        caption = role(11, 16),
        meta = role(12, 18),
        control = role(13, 20, FontWeight.Medium),
        body = role(14, 22),
        title = role(16, 24, FontWeight.SemiBold),
        page = role(20, 28, FontWeight.SemiBold),
        // 等宽数字：读数变化时字宽不跳（KPI、进度、时长）。
        stat = role(28, 34, FontWeight.SemiBold, features = "tnum"),
        read = role(16, 28),
        code = role(13, 20, FontWeight.Normal, AppFonts.mono),
    )
}

/**
 * 三档断点（dp）。批 6 起不再到处写魔法数：<720 窄窗（手机/半屏）、720–1100 中窗、
 * >1100 宽窗。任务页两栏、知识库右栏、计划抽屉都按这三档决定形态。
 */
internal object TrellisBreakpoints {
    const val compact = 720f

    /**
     * 「宽窗」：对话页正文列的最大宽度（760dp）。
     * 能放下这么宽就已经够摆两栏了，不用等到 1100。
     */
    const val twoColumn = 760f

    const val medium = 1100f
}
