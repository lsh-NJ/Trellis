package dev.trellis.app.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 界面偏好是「宿主样式的数据面」：归一化、落盘、坏文件兜底。
 *
 * 不碰真实主题（那要靠截图看），只钉住「喂进来什么、存下去什么」——
 * 这一层错了，agent 改样式就会时好时坏。
 */
class UiPrefsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-ui-prefs-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun unknownValuesFallBackInsteadOfBreakingTheUi() {
        val cleaned = UiPrefs(
            palette = "neon",
            density = "huge",
            card = "glass",
            radius = 999,
            fontScale = 3f,
        ).normalized()

        assertEquals("default", cleaned.palette)
        assertEquals("normal", cleaned.density)
        assertEquals("bordered", cleaned.card)
        assertEquals(24, cleaned.radius)
        assertEquals(1.25f, cleaned.fontScale)
    }

    @Test
    fun negativeRadiusIsClampedToZero() {
        assertEquals(0, UiPrefs(radius = -8).normalized().radius)
    }

    @Test
    fun navFallsBackToLeftAndIsDescribed() {
        assertEquals("left", UiPrefs(nav = "right").normalized().nav)
        assertTrue(UiPrefs().describe().contains("左侧边栏"))
        assertTrue(UiPrefs(nav = "top").describe().contains("顶部标签栏"))
    }

    @Test
    fun defaultsAreBentoThemeAndLeftNavigation() {
        val prefs = UiPrefs()

        assertEquals("dev.bento.shift/bento", prefs.theme)
        assertEquals(DEFAULT_THEME, prefs.theme)
        assertEquals("left", prefs.nav)
        assertTrue(prefs.describe().contains("Bento（默认）"), prefs.describe())
    }

    @Test
    fun missingFieldsGetTheNewDefaults() {
        val home = tempHome()
        File(home, "ui.json").writeText("""{"palette":"ocean"}""", Charsets.UTF_8)

        val loaded = UiPrefsStore(home).load()

        assertEquals(DEFAULT_THEME, loaded.theme)
        assertEquals("left", loaded.nav)
    }

    @Test
    fun explicitOldChoicesAreNotOverriddenByTheNewDefaults() {
        val home = tempHome()
        File(home, "ui.json").writeText("""{"theme":"builtin","nav":"top"}""", Charsets.UTF_8)

        val loaded = UiPrefsStore(home).load()

        assertEquals("builtin", loaded.theme)
        assertEquals("top", loaded.nav)
    }

    @Test
    fun navToneFallsBackToTheThemeDefault() {
        assertEquals("default", UiPrefs(navTone = "neon").normalized().navTone)
        assertTrue(UiPrefs(navTone = "dark").describe().contains("深色"))
    }

    @Test
    fun theBentoCardStyleIsAcceptedAndDescribed() {
        assertEquals("bento", UiPrefs(card = "bento").normalized().card)
        assertTrue(UiPrefs(card = "bento").describe().contains("bento"))
    }

    @Test
    fun prefsSurviveASaveAndReload() {
        val home = tempHome()
        val store = UiPrefsStore(home)
        val saved = UiPrefs(
            palette = "ocean",
            density = "compact",
            card = "filled",
            radius = 6,
            fontScale = 0.9f,
            nav = "left",
        )

        store.save(saved)

        assertEquals(saved, UiPrefsStore(home).load())
    }

    @Test
    fun aBrokenFileMeansDefaultsNotACrash() {
        val home = tempHome()
        File(home, "ui.json").writeText("{ 这不是 json", Charsets.UTF_8)

        assertEquals(UiPrefs(), UiPrefsStore(home).load())
    }

    @Test
    fun describeMentionsEveryDimension() {
        val text = UiPrefs(palette = "forest", density = "relaxed", card = "flat", radius = 18, fontScale = 1.1f)
            .describe()

        assertTrue(text.contains("森林"), text)
        assertTrue(text.contains("宽松"), text)
        assertTrue(text.contains("扁平"), text)
        assertTrue(text.contains("18dp"), text)
        assertTrue(text.contains("110%"), text)
    }

    @Test
    fun appearanceFallsBackToAutoAndIsDescribed() {
        assertEquals("auto", UiPrefs(appearance = "sepia").normalized().appearance)
        assertTrue(UiPrefs(appearance = "dark").describe().contains("深色"))
        assertTrue(UiPrefs().describe().contains("跟随系统"))
    }

    @Test
    fun appearanceSurvivesASaveAndReload() {
        val home = tempHome()
        UiPrefsStore(home).save(UiPrefs(appearance = "dark"))

        assertEquals("dark", UiPrefsStore(home).load().appearance)
    }

    @Test
    fun loadedPrefsAreAlwaysNormalized() {
        val home = tempHome()
        File(home, "ui.json").writeText("""{"palette":"ocean","radius":999}""", Charsets.UTF_8)

        val loaded = UiPrefsStore(home).load()

        assertEquals("ocean", loaded.palette)
        assertEquals(24, loaded.radius)
        assertFalse(loaded.describe().contains("999"))
    }
}
