package dev.trellis.app.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * agent 改样式的那条通路：list_ui_options / set_ui。
 *
 * 用真实的 [AppRuntime.create]（临时家目录），因为偏好要经过落盘和主题状态；
 * 这里只验「改没改成、越界有没有被收回来」，画面效果靠截图验。
 */
class UiStyleToolsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-ui-style-", "").let {
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
    fun settingAPaletteChangesTheRuntimePrefs() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("set_ui").call(
                buildJsonObject {
                    put("palette", "ocean")
                    put("radius", 4)
                },
            )

            assertFalse(result.isError, result.content)
            assertEquals("ocean", runtime.uiPrefs.palette)
            assertEquals(4, runtime.uiPrefs.radius)
            assertTrue(result.content.contains("海蓝"), result.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun anOutOfRangeRadiusIsClampedNotRejected() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            tools.getValue("set_ui").call(buildJsonObject { put("radius", 999) })

            assertEquals(24, runtime.uiPrefs.radius)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun listingShowsTheCurrentPrefsAndTheOptions() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("list_ui_options").call(buildJsonObject {})

            assertTrue(result.content.contains("当前界面偏好"), result.content)
            assertTrue(result.content.contains("海蓝"), result.content)
            assertTrue(result.content.contains("扁平无边框"), result.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun navigationCanMoveToTheLeft() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("set_ui").call(buildJsonObject { put("nav", "left") })

            assertFalse(result.isError, result.content)
            assertEquals("left", runtime.uiPrefs.nav)
            assertTrue(result.content.contains("左侧边栏"), result.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun theNavCanGetADarkerBackground() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("set_ui").call(buildJsonObject { put("nav_tone", "dark") })

            assertFalse(result.isError, result.content)
            assertEquals("dark", runtime.uiPrefs.navTone)
            assertTrue(result.content.contains("深色"), result.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun theWholePageCanFollowOrPinTheSystemAppearance() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("set_ui").call(buildJsonObject { put("appearance", "dark") })

            assertFalse(result.isError, result.content)
            assertEquals("dark", runtime.uiPrefs.appearance)
            assertTrue(runtime.uiPrefs.describe().contains("深色"), runtime.uiPrefs.describe())

            val listed = tools.getValue("list_ui_options").call(buildJsonObject { })
            assertTrue(listed.content.contains("跟随系统"), listed.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun theChoiceIsRememberedOnDisk() = runTest {        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            tools.getValue("set_ui").call(
                buildJsonObject {
                    put("palette", "sunset")
                    put("density", "compact")
                },
            )
        } finally {
            runtime.shutdown()
        }

        val reopened = AppRuntime.create(home)
        try {
            assertEquals("sunset", reopened.uiPrefs.palette)
            assertEquals("compact", reopened.uiPrefs.density)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    fun aSinglePluginCanGetItsOwnCardStyle() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val pluginId = runtime.pluginInfos.first().manifest.id
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("set_plugin_card").call(
                buildJsonObject {
                    put("plugin", pluginId.value)
                    put("card", "filled")
                    put("radius", 6)
                },
            )

            assertFalse(result.isError, result.content)
            val style = runtime.pluginCardStyles.getValue(pluginId)
            assertEquals("filled", style.card)
            assertEquals(6, style.radius)
            assertTrue(result.content.contains("filled"), result.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun anUnknownPluginIsRejectedAndTheListIsHandedBack() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            val result = tools.getValue("set_plugin_card").call(
                buildJsonObject {
                    put("plugin", "ai.nope")
                    put("card", "filled")
                },
            )

            assertTrue(result.isError, result.content)
            assertTrue(result.content.contains("没有这个插件"), result.content)
            assertTrue(runtime.pluginCardStyles.isEmpty())
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun listingShowsThePerPluginOverrides() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val pluginId = runtime.pluginInfos.first().manifest.id
            val tools = UiStyleTools(runtime).all.associateBy { it.name }
            tools.getValue("set_plugin_card").call(
                buildJsonObject {
                    put("plugin", pluginId.value)
                    put("card", "flat")
                },
            )

            val listing = tools.getValue("list_ui_options").call(buildJsonObject {})
            assertTrue(listing.content.contains("单个插件的卡片样式"), listing.content)
            assertTrue(listing.content.contains(pluginId.value), listing.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun thePerPluginStyleSurvivesARestart() = runTest {
        val home = tempHome()
        val pluginId = run {
            val runtime = AppRuntime.create(home)
            try {
                val id = runtime.pluginInfos.first().manifest.id
                UiStyleTools(runtime).all.associateBy { it.name }
                    .getValue("set_plugin_card")
                    .call(buildJsonObject { put("plugin", id.value); put("card", "filled") })
                id
            } finally {
                runtime.shutdown()
            }
        }

        val reopened = AppRuntime.create(home)
        try {
            assertEquals("filled", reopened.pluginCardStyles.getValue(pluginId).card)
        } finally {
            reopened.shutdown()
        }
    }

    @Test
    fun theThemeListAlwaysOffersBuiltin() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val listing = UiStyleTools(runtime).all.associateBy { it.name }
                .getValue("list_themes").call(buildJsonObject {})

            assertFalse(listing.isError, listing.content)
            assertTrue(listing.content.contains("当前主题"), listing.content)
            assertTrue(listing.content.contains("builtin"), listing.content)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun anUnknownThemeIsRejectedWithoutChangingThePreference() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val result = UiStyleTools(runtime).all.associateBy { it.name }
                .getValue("set_theme").call(buildJsonObject { put("key", "nope/missing") })

            assertTrue(result.isError, result.content)
            assertTrue(result.content.contains("没有这个主题"), result.content)
            assertEquals(DEFAULT_THEME, runtime.uiPrefs.theme)
        } finally {
            runtime.shutdown()
        }
    }
}
