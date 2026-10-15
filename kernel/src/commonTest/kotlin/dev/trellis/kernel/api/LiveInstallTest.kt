package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.slot.ConsoleSlotRenderer
import dev.trellis.kernel.slot.SlotHost
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TextSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LiveInstallTest {

    private fun card(id: String, order: Int, title: String) =
        TextSlot(id, order = order, title = title, body = "内容")

    /** M2 的验收标准：装一个插件界面长出来，卸掉界面缩回去  全程不重启进程。 */
    @Test
    fun installingAndUninstallingChangesWhatTheSlotRenders() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(Slots.HomeHeader)),
            registrations = listOf(Slots.HomeHeader to card("a-card", 100, "A")),
        )
        val b = ContribPlugin(
            m2Manifest("dev.b", declares = listOf(Slots.HomeHeader)),
            registrations = listOf(Slots.HomeHeader to card("b-card", 50, "B")),
        )
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), enabledIds = listOf(PluginId("dev.a")))),
        )
        val host = SlotHost(kernel.extensions, ConsoleSlotRenderer())

        assertEquals(1, host.render(Slots.HomeHeader).size)
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)

        assertTrue(kernel.install(b.manifest) is InstallResult.Installed)
        val afterInstall = host.render(Slots.HomeHeader)
        assertEquals(2, afterInstall.size)
        assertTrue(afterInstall[0].contains("[b-card]"), "order 50 应排在 order 100 前面")

        assertEquals(UninstallResult.Uninstalled(1), kernel.uninstall(PluginId("dev.b")))
        val afterUninstall = host.render(Slots.HomeHeader)
        assertEquals(1, afterUninstall.size, "卸载后界面必须立刻缩回去")
        assertTrue(afterUninstall[0].contains("[a-card]"))
    }

    /**
     * 不变量 I6 的关键一半：注销贡献点由内核按属主强制完成，
     * 完全不依赖插件的 dispose()。ContribPlugin 根本没实现 dispose  依然摘得干净。
     */
    @Test
    fun uninstallRemovesContributionsEvenThoughDisposeDoesNothing() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(Slots.HomeHeader, Slots.DetailAside)),
            registrations = listOf(
                Slots.HomeHeader to card("a1", 100, "A1"),
                Slots.DetailAside to card("a2", 100, "A2"),
            ),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))
        assertEquals(1, kernel.extensions.all(Slots.HomeHeader).size)
        assertEquals(1, kernel.extensions.all(Slots.DetailAside).size)

        assertEquals(UninstallResult.Uninstalled(2), kernel.uninstall(PluginId("dev.a")))

        assertTrue(kernel.extensions.all(Slots.HomeHeader).isEmpty())
        assertTrue(kernel.extensions.all(Slots.DetailAside).isEmpty())
        assertEquals(null, kernel.plugin(PluginId("dev.a")))
    }

    @Test
    fun requiredPluginsRefuseToBeUninstalled() = runTest {
        val core = ContribPlugin(
            m2Manifest("dev.core", required = true, declares = listOf(Slots.HomeHeader)),
            registrations = listOf(Slots.HomeHeader to card("core", 100, "核心")),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(core), listOf(PluginId("dev.core")))))

        val result = kernel.uninstall(PluginId("dev.core"))
        assertTrue(result is UninstallResult.Rejected, result.toString())
        assertEquals(1, kernel.extensions.all(Slots.HomeHeader).size, "被拒绝的卸载不能动到贡献点")
    }

    @Test
    fun installIsRejectedWhenTheDependencyIsNotRunning() = runTest {
        val b = ContribPlugin(m2Manifest("dev.b", dependsOn = listOf("dev.ghost")))
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(b), emptyList())))

        val result = kernel.install(b.manifest)
        assertTrue(result is InstallResult.Rejected, result.toString())
        assertEquals(ErrorCode.MISSING_DEPENDENCY, result.errors.first().code)
    }

    @Test
    fun installSucceedsOnceTheDependencyIsActive() = runTest {
        val a = ContribPlugin(m2Manifest("dev.a"))
        val b = ContribPlugin(m2Manifest("dev.b", dependsOn = listOf("dev.a")))
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), enabledIds = listOf(PluginId("dev.a")))),
        )

        assertTrue(kernel.install(b.manifest) is InstallResult.Installed)
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.b"))!!.state)
    }

    @Test
    fun installIsRejectedWhenSdkRangeDoesNotMatch() = runTest {
        val b = ContribPlugin(
            dev.trellis.kernel.plugin.PluginManifest(
                id = PluginId("dev.b"),
                version = dev.trellis.kernel.core.SemVer.parse("1.0.0"),
                name = "b",
                sdkRange = dev.trellis.kernel.core.VersionRange.parse(">=9.0.0"),
                loader = "catalog",
            ),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(b), emptyList())))

        val result = kernel.install(b.manifest)
        assertTrue(result is InstallResult.Rejected, result.toString())
        assertEquals(ErrorCode.SDK_VERSION_MISMATCH, result.errors.first().code)
    }

    @Test
    fun installingTheSamePluginTwiceIsRejected() = runTest {
        val a = ContribPlugin(m2Manifest("dev.a"))
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))

        val result = kernel.install(a.manifest)
        assertTrue(result is InstallResult.Rejected, result.toString())
        assertEquals(ErrorCode.MANIFEST_INVALID, result.errors.first().code)
    }

    @Test
    fun installingAPluginWithNoLoaderForThePlatformIsRejected() = runTest {
        val b = ContribPlugin(m2Manifest("dev.b", loader = "shadow"))
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(b), emptyList())))

        val result = kernel.install(b.manifest)
        assertTrue(result is InstallResult.Rejected, result.toString())
        assertEquals(ErrorCode.LOADER_UNAVAILABLE, result.errors.first().code)
    }
}