package dev.trellis.app.desktop

import androidx.compose.ui.graphics.Color
import dev.trellis.kernel.theme.ThemeContribution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 主题包的门槛：词表、hex、对比度，以及暗色派生之后还站不站得住。
 *
 * 这里不测「好不好看」——那是审美，测的是**不会坏**：一套主题不能把整页
 * 变成看不清的样子，也不能靠少写几个 token 混过去。
 */
class ThemePacksTest {

    private val warmPaper = mapOf(
        "primary" to "#8A5A2B", "onPrimary" to "#FFFFFF",
        "background" to "#FBF5EA", "onBackground" to "#221E18",
        "surface" to "#FFFDF8", "onSurface" to "#221E18",
        "surfaceVariant" to "#EFE6D8", "onSurfaceVariant" to "#4A443B",
    )

    private val warmPaperDark = mapOf(
        "primary" to "#E2B37E", "onPrimary" to "#3B2410",
        "background" to "#191713", "onBackground" to "#F2E9DC",
        "surface" to "#211E19", "onSurface" to "#F2E9DC",
        "surfaceVariant" to "#302B23", "onSurfaceVariant" to "#D6C8B5",
    )

    @Test
    fun aCompleteThemePassesAndCanBeResolved() {
        val problems = themeProblems(warmPaper, emptyMap())
        assertTrue(problems.isEmpty(), problems.toString())

        val theme = ThemeContribution(id = "warm", name = "暖纸", light = warmPaper)
        val scheme = themeSchemeOf(theme)
        assertNotNull(scheme)
        assertEquals(
            warmPaper.getValue("primary").uppercase(),
            hexOf(scheme.light.primary).uppercase(),
        )
    }

    @Test
    fun missingTokensAreNamed() {
        val problems = themeProblems(warmPaper - "onSurface", emptyMap())
        assertTrue(
            problems.any { it.contains("onSurface") && it.contains("缺") },
            problems.toString(),
        )
    }

    @Test
    fun aTokenOutsideTheVocabularyIsRejected() {
        val problems = themeProblems(warmPaper + ("accent" to "#FF0000"), emptyMap())
        assertTrue(
            problems.any { it.contains("accent") && it.contains("不认识") },
            problems.toString(),
        )
    }

    @Test
    fun aBadHexIsRejected() {
        val problems = themeProblems(warmPaper + ("primary" to "#GGGGGG"), emptyMap())
        assertTrue(problems.any { it.contains("#RRGGBB") }, problems.toString())
    }

    @Test
    fun lowContrastIsRejectedAndSaysWhichPair() {
        val problems = themeProblems(warmPaper + ("onSurface" to "#F0EDE6"), emptyMap())
        assertTrue(
            problems.any { it.contains("onSurface") && it.contains("对比度") },
            problems.toString(),
        )
    }

    @Test
    fun derivedDarkReallyGetsDarkAndKeepsContrast() {
        val theme = ThemeContribution(id = "warm", name = "暖纸", light = warmPaper)
        val scheme = themeSchemeOf(theme)
        assertNotNull(scheme)
        assertTrue(
            relativeLuminance(scheme.dark.background) < 0.1,
            "派生出来的暗色底应该真的很暗",
        )
        assertTrue(
            contrastRatio(scheme.dark.background, scheme.dark.onBackground) >= 7.0,
            "派生之后正文对比度仍要不低于 7:1",
        )
    }

    @Test
    fun aThemeWithItsOwnDarkSetIsValidatedToo() {
        val theme = ThemeContribution(
            id = "warm",
            name = "暖纸",
            light = warmPaper,
            dark = warmPaperDark,
        )
        assertTrue(themeProblems(theme.light, theme.dark).isEmpty(), themeProblems(theme.light, theme.dark).toString())
        val bad = ThemeContribution(
            id = "warm",
            name = "暖纸",
            light = warmPaper,
            dark = warmPaperDark + ("onBackground" to "#20201E"),
        )
        assertTrue(
            themeProblems(bad.light, bad.dark).any { it.contains("onBackground") && it.contains("对比度") },
            themeProblems(bad.light, bad.dark).toString(),
        )
    }

    @Test
    fun theShellShapeAndTypeAreParsedAndBounded() {
        val theme = ThemeContribution(
            id = "bento",
            name = "值班台",
            light = warmPaper,
            shape = mapOf("cardRadius" to "3", "controlRadius" to "3"),
            type = mapOf("labelFamily" to "mono", "titleFamily" to "serif", "scale" to "1.05"),
        )
        assertTrue(chromeProblems(theme).isEmpty(), chromeProblems(theme).toString())
        val chrome = chromeOf(theme)
        assertEquals(3, chrome.cardRadius)
        assertEquals(3, chrome.controlRadius)
        assertEquals("mono", chrome.labelFamily)
        assertEquals("serif", chrome.titleFamily)
        assertEquals(1.05f, chrome.scale)
    }

    @Test
    fun shellTokensOutsideTheVocabularyAreNamedAndRejected() {
        val theme = ThemeContribution(
            id = "bento",
            name = "值班台",
            light = warmPaper,
            shape = mapOf("shadowDepth" to "4"),
            type = mapOf("labelFamily" to "comic", "scale" to "9"),
        )
        val problems = chromeProblems(theme)
        assertTrue(problems.any { it.contains("shadowDepth") && it.contains("不认识") }, problems.toString())
        assertTrue(problems.any { it.contains("comic") && it.contains("mono") }, problems.toString())
        assertTrue(problems.any { it.contains("scale") && it.contains("0.85") }, problems.toString())
    }

    @Test
    fun theDisplayFamilyIsTheOneTheSampleNeeds() {
        val theme = ThemeContribution(
            id = "bento",
            name = "值班台",
            light = warmPaper,
            type = mapOf("displayFamily" to "condensed", "labelFamily" to "mono"),
        )
        assertTrue(chromeProblems(theme).isEmpty(), chromeProblems(theme).toString())
        assertEquals("condensed", chromeOf(theme).displayFamily)

        val bad = ThemeContribution(
            id = "loud",
            name = "大声",
            light = warmPaper,
            type = mapOf("displayFamily" to "comic"),
        )
        assertTrue(
            chromeProblems(bad).any { it.contains("displayFamily") && it.contains("condensed") },
            chromeProblems(bad).toString(),
        )
    }

    @Test
    fun noThemeMeansTheDefaultShell() {
        val chrome = chromeOf(null)
        assertEquals(12, chrome.cardRadius)
        assertEquals(8, chrome.controlRadius)
        assertEquals("default", chrome.labelFamily)
        assertEquals(1f, chrome.scale)
    }

    @Test
    fun dataAndStateColorsAreValidatedAsAGroup() {
        val theme = ThemeContribution(
            id = "bento",
            name = "值班台",
            light = warmPaper,
            extra = mapOf(
                "data" to "#2C4A63", "dataMuted" to "#4F6880", "dataTrack" to "#E2DED2",
                "accentText" to "#4F6A12", "danger" to "#B23A2E", "dangerContainer" to "#F7E4DF",
            ),
        )
        val problems = extraProblems(theme.extra, theme.light)
        assertTrue(problems.isEmpty(), problems.toString())

        val colors = extraColorsOf(theme, dark = false, scheme = schemeFor(UiPrefs()))
        assertEquals(parseHexColor("#2C4A63"), colors.data)
        assertEquals(parseHexColor("#4F6880"), colors.dataMuted)
        assertEquals(parseHexColor("#4F6A12"), colors.accentText)
        assertNotNull(colors.warning, "没写的岗位回落到配色方案，不能是透明")
    }

    @Test
    fun aBadDataColorIsNamedAndRejected() {
        val theme = ThemeContribution(
            id = "loud",
            name = "大声",
            light = warmPaper,
            extra = mapOf("data" to "#EEEEEE", "danger" to "#FFFFFE"),
        )
        val problems = extraProblems(theme.extra, theme.light)
        assertTrue(problems.any { it.contains("data") && it.contains("对比度") }, problems.toString())
        assertTrue(problems.any { it.contains("danger") && it.contains("对比度") }, problems.toString())

        val unknown = ThemeContribution(
            id = "weird",
            name = "怪",
            light = warmPaper,
            extra = mapOf("chartColor" to "#2C4A63"),
        )
        assertTrue(
            extraProblems(unknown.extra, unknown.light).any { it.contains("chartColor") && it.contains("不认识") },
            extraProblems(unknown.extra, unknown.light).toString(),
        )
    }

    @Test
    fun theRealBentoSampleStillValidates() {
        // 仓库里的样片主题如果自己都过不了校验，那「配色指导」就只是纸面文章。
        val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "examples/plugins/a-shift-bento/main.js") }
            .firstOrNull { it.exists() } ?: return
        val sandbox = dev.trellis.script.ScriptSandbox("dev.bento.shift", file.readText())
        sandbox.boot()
        val d = sandbox.declaredThemes().single()
        val theme = ThemeContribution(
            id = d.id, name = d.name, description = d.description,
            light = d.light, dark = d.dark, shape = d.shape, type = d.type, extra = d.extra,
        )
        assertTrue(themeProblems(theme.light, theme.dark).isEmpty(), themeProblems(theme.light, theme.dark).toString())
        assertTrue(chromeProblems(theme).isEmpty(), chromeProblems(theme).toString())
        assertTrue(extraProblems(theme.extra, theme.light).isEmpty(), extraProblems(theme.extra, theme.light).toString())
    }
}
