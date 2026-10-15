package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.plugin.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 「发现」与「启用」必须是两个维度。
 *
 * 混在一起的后果不是抽象的：`install(id)` 靠重新扫描目录来找插件，
 * 如果扫描结果已经被启用集过滤过，**没启用的插件就永远装不上** 
 * 而「装一个还没启用的插件」恰恰是 install 最主要的用途。
 */
class EnabledPluginsTest {

    private suspend fun kernelWithEnabled(enabled: Set<PluginId>, required: Boolean = false): Triple<Kernel, ContribPlugin, ContribPlugin> {
        val a = ContribPlugin(m2Manifest("dev.a", required = required).copy(builtin = required))
        val b = ContribPlugin(m2Manifest("dev.b"))
        val loader = CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b")))
        return Triple(Kernel.boot(m2Config(loader).copy(enabledPlugins = enabled)), a, b)
    }

    @Test
    fun onlyEnabledPluginsBoot() = runTest {
        val (kernel, _, _) = kernelWithEnabled(setOf(PluginId("dev.a")))

        assertEquals(1, kernel.plugins().size, kernel.plugins().map { it.manifest.id.value }.toString())
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
        assertEquals(null, kernel.plugin(PluginId("dev.b")))
        kernel.shutdown()
    }

    @Test
    fun aDisabledButInstalledPluginCanStillBeInstalledById() = runTest {
        val (kernel, _, _) = kernelWithEnabled(setOf(PluginId("dev.a")))

        val result = kernel.install(PluginId("dev.b"))

        assertTrue(result is InstallResult.Installed, result.toString())
        assertEquals(2, kernel.plugins().size)
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.b"))!!.state)
        kernel.shutdown()
    }

    @Test
    fun requiredBuiltinsIgnoreTheEnabledList() = runTest {
        // 启用集里只有 dev.b；但 dev.a 是必需内置插件，它必须起来
        val (kernel, _, _) = kernelWithEnabled(setOf(PluginId("dev.b")), required = true)

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.b"))!!.state)
        kernel.shutdown()
    }

    @Test
    fun aNullEnabledListMeansEverythingBoots() = runTest {
        val a = ContribPlugin(m2Manifest("dev.a"))
        val b = ContribPlugin(m2Manifest("dev.b"))
        val loader = CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b")))
        val kernel = Kernel.boot(m2Config(loader))

        assertEquals(2, kernel.plugins().size, "null 表示不限制，而不是表示一个都不启用")
        kernel.shutdown()
    }
}
