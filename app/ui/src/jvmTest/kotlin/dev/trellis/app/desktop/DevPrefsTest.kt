package dev.trellis.app.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 调试模式开关的数据面：默认关、落盘、坏文件兜底。
 *
 * 开关本身在设置页 /「我的」页；这里只钉住「喂进来什么、存下去什么」，
 * 以及坏文件不能让人意外开着调试模式启动。
 */
class DevPrefsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-dev-prefs-", "").let {
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
    fun debugModeIsOffByDefaultAndRoundTrips() {
        val home = tempHome()
        val store = DevPrefsStore(home)

        assertFalse(store.load().debugMode, "没有 dev.json 时调试模式默认关")

        store.save(DevPrefs(debugMode = true))
        assertTrue(File(home, "dev.json").isFile)
        assertTrue(store.load().debugMode)

        store.save(DevPrefs(debugMode = false))
        assertFalse(store.load().debugMode)
    }

    @Test
    fun aBrokenFileFallsBackToOffInsteadOfFailingStartup() {
        val home = tempHome()
        File(home, "dev.json").writeText("{ not json", Charsets.UTF_8)
        assertFalse(DevPrefsStore(home).load().debugMode)

        // 编辑器写出的 BOM 也要认。
        File(home, "dev.json").writeText("\uFEFF{\"debugMode\":true}", Charsets.UTF_8)
        assertTrue(DevPrefsStore(home).load().debugMode)
    }

    @Test
    fun theSettingSwitchTurnsDevModeOnWithoutTheEnvironmentVariable() {
        val home = tempHome()
        val setup = createTrellisClock(home, env = emptyMap(), debugMode = true, defaultHome = File(home, "default"))

        assertTrue(setup.devMode, "设置页开关应能打开开发者模式")
        assertFalse(setup.devModeFromEnv, "这次不是环境变量开的")
        assertEquals(false, setup.simulated)
    }

    @Test
    fun theEnvironmentVariableStaysAForcedLaunchOverride() {
        val home = tempHome()
        val setup = createTrellisClock(
            home,
            env = mapOf("TRELLIS_DEV" to "1"),
            debugMode = false,
            defaultHome = File(home, "default"),
        )

        assertTrue(setup.devMode)
        assertTrue(setup.devModeFromEnv, "界面要能说清是环境变量在强制开启")
    }

    @Test
    fun theSettingSwitchAlsoRestoresThePersistedSimulatedDate() {
        val home = tempHome()
        DevClockStore(home).save(java.time.LocalDate.parse("2026-11-05"))

        val setup = createTrellisClock(home, env = emptyMap(), debugMode = true, defaultHome = File(home, "default"))

        assertTrue(setup.simulated, "设置页打开后，上次拨的模拟日期要和 TRELLIS_DEV=1 一样恢复")
        assertEquals(java.time.LocalDate.parse("2026-11-05"), setup.clock.simulatedDate())
    }
}
