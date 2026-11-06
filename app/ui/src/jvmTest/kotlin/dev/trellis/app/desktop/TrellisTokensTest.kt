package dev.trellis.app.desktop

import androidx.compose.ui.unit.dp
import dev.trellis.kernel.theme.ThemeContribution
import dev.trellis.ui.ExtraColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * token 层的门槛：角色对不对、表有没有漂、两套内置主题在明暗两种模式下是否还站得住。
 *
 * 这里**不测颜色好不好看**——那是审美。测的是「不会坏」：
 * 强弱线的对比、四个状态色能不能当文字、荧光笔上的字读不读得出来、
 * 以及密度/圆角/字号这三张表有没有按文档兑现。
 *
 * 和 [ThemePacksTest] 的分工：那边管「主题包能不能装」，这边管
 * 「装好之后界面拿到的角色值是否安全」。第三方主题能过那边的门槛，
 * 这边就是最后一道兜底。
 */
class TrellisTokensTest {

    private val shipped: List<Pair<String, ThemeContribution>> = listOf(
        "bento" to BentoThemePlugin().theme,
        "desk" to DeskThemePlugin().theme,
    )

    @Test
    fun bothShippedThemesPassEveryGate() {
        shipped.forEach { (name, theme) ->
            assertEquals(emptyList(), themeProblems(theme.light, theme.dark), name)
            assertEquals(emptyList(), extraProblems(theme.extra, theme.light), name)
            assertEquals(emptyList(), chromeProblems(theme), name)
            assertTrue(themeSchemeOf(theme) != null, "$name 解析不出配色方案")
        }
    }

    @Test
    fun densityOnlyChangesRowHeight() {
        // 密度的唯一落点是行高：32 / 40 / 48。以前 compact 是把 Density 乘 1.12，
        // 文字跟着放大 12%，于是「紧凑」比「标准」还大。
        assertEquals(32.dp, TokenRows.of("compact").active)
        assertEquals(40.dp, TokenRows.of("normal").active)
        assertEquals(48.dp, TokenRows.of("relaxed").active)

        // 三档在表里都要在：组件按场景取（清单 / 点击行 / 空态），不是只取 active
        val rows = TokenRows.of("compact")
        assertEquals(32.dp, rows.compact)
        assertEquals(40.dp, rows.standard)
        assertEquals(48.dp, rows.comfortable)
    }

    @Test
    fun radiusFollowsTheThemeUnlessTheUserOverridesIt() {
        val shell = TrellisTokens.of(UiPrefs(), ChromeStyle(), schemeFor(UiPrefs()), ExtraColors())
        assertEquals(4.dp, shell.radius.control)
        assertEquals(8.dp, shell.radius.card)

        // 主题声明的圆角真的生效：Bento 是 3，不是用户默认的 12 或宿主默认的 8
        val bento = TrellisTokens.of(
            UiPrefs(),
            chromeOf(BentoThemePlugin().theme),
            schemeFor(UiPrefs()),
            ExtraColors(),
        )
        assertEquals(3.dp, bento.radius.card)

        // 用户显式选过就听用户的
        val own = TrellisTokens.of(UiPrefs(radius = 12), ChromeStyle(), schemeFor(UiPrefs()), ExtraColors())
        assertEquals(12.dp, own.radius.card)
    }

    @Test
    fun typeRolesMatchTheDocumentedScale() {
        val type = TrellisTokens.of(UiPrefs(), ChromeStyle(), schemeFor(UiPrefs()), ExtraColors()).type
        val expected = listOf(
            Triple(type.caption, 11f, 16f),
            Triple(type.meta, 12f, 18f),
            Triple(type.control, 13f, 20f),
            Triple(type.body, 14f, 22f),
            Triple(type.title, 16f, 24f),
            Triple(type.page, 20f, 28f),
            Triple(type.stat, 28f, 34f),
            Triple(type.read, 16f, 28f),
            Triple(type.code, 13f, 20f),
        )
        expected.forEach { (style, size, line) ->
            assertEquals(size, style.fontSize.value, 0.01f, "$style 的字号")
            assertEquals(line, style.lineHeight.value, 0.01f, "$style 的行高")
        }
    }

    @Test
    fun scaledTypeRolesFollowTheThemeScale() {
        val tokens = TrellisTokens.of(
            UiPrefs(),
            ChromeStyle(scale = 1.2f),
            schemeFor(UiPrefs()),
            ExtraColors(),
        )
        assertEquals(13.2f, tokens.type.caption.fontSize.value, 0.01f)
        assertEquals(19.2f, tokens.type.caption.lineHeight.value, 0.01f)
    }

    @Test
    fun everyShippedThemeKeepsTheLinesVisible() {
        shipped.forEach { (name, theme) ->
            listOf(false, true).forEach { dark ->
                val colors = tokensFor(theme, dark).colors
                val strong = contrastRatio(colors.ruleStrong, colors.surface)
                assertTrue(strong >= 3.0, "$name dark=$dark 强线只有 $strong:1")
                // 发丝线是装饰，但至少不能和表面同色
                assertTrue(
                    contrastRatio(colors.rule, colors.surface) > 1.1,
                    "$name dark=$dark 发丝线几乎看不见",
                )
            }
        }
    }

    @Test
    fun everyShippedThemeKeepsTextReadable() {
        shipped.forEach { (name, theme) ->
            listOf(false, true).forEach { dark ->
                val colors = tokensFor(theme, dark).colors
                val where = "$name dark=$dark"
                assertTrue(contrastRatio(colors.ink, colors.surface) >= 7.0, "$where 正文不足 7:1")
                listOf(
                    "ink2" to colors.ink2,
                    "ink3" to colors.ink3,
                    "danger" to colors.danger,
                    "warn" to colors.warn,
                    "ok" to colors.ok,
                    "info" to colors.info,
                ).forEach { (label, color) ->
                    val ratio = contrastRatio(color, colors.surface)
                    assertTrue(ratio >= 4.5, "$where 的 $label 只有 $ratio:1")
                }
                assertTrue(
                    contrastRatio(colors.onMark, colors.mark) >= 4.5,
                    "$where 荧光笔上的字读不出来",
                )
                assertTrue(
                    contrastRatio(colors.onAccentSoft, colors.accentSoft) >= 4.5,
                    "$where 强调底上的字读不出来",
                )
                assertTrue(
                    contrastRatio(colors.onAccent, colors.accent) >= 4.5,
                    "$where 动作色上的字读不出来",
                )
            }
        }
    }

    /** 把一套主题按明暗解析成 token，顺便把主题校验失败的信息带进断言消息。 */
    private fun tokensFor(theme: ThemeContribution, dark: Boolean): TrellisTokens {
        val scheme = themeSchemeOf(theme) ?: error(
            "主题解析失败：" + (themeProblems(theme.light, theme.dark) +
                extraProblems(theme.extra, theme.light) + chromeProblems(theme)).joinToString("；"),
        )
        val colors = if (dark) scheme.dark else scheme.light
        return TrellisTokens.of(UiPrefs(), chromeOf(theme), colors, extraColorsOf(theme, dark, colors))
    }
}
