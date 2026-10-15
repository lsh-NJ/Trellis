package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.plugin.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest

/**
 * 不变量 I3：所有注册表变更串行执行。
 *
 * 这些测试的价值完全取决于**去掉锁之后它们会不会红**。
 * 所以每条都刻意让插件在 init 里挂起（initDelayMs），
 * 把「检查过 id 还没登记」和「真正写进注册表」之间的窗口撑开 
 * 那正是竞态发生的地方。
 */
class ConcurrentMutationTest {

    @Test
    fun concurrentInstallsOfTheSamePluginAdmitExactlyOne() = runTest {
        val b = ContribPlugin(m2Manifest("dev.b"), initDelayMs = 5)
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(b), emptyList())))

        val results = List(8) { async { kernel.install(b.manifest) } }.awaitAll()

        assertEquals(
            1, results.count { it is InstallResult.Installed },
            "并发安装同一个插件必须有且只有一个成功，实际成功 " +
                results.count { it is InstallResult.Installed } + " 次",
        )
        assertEquals(
            1, kernel.plugins().count { it.manifest.id == PluginId("dev.b") },
            "注册表里出现了重复条目：" + kernel.plugins().map { it.manifest.id.value },
        )
    }

    @Test
    fun concurrentUninstallOfTheSamePluginSucceedsExactlyOnce() = runTest {
        val a = ContribPlugin(m2Manifest("dev.a"), stopDelayMs = 5)
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))

        val results = List(6) { async { kernel.uninstall(PluginId("dev.a")) } }.awaitAll()

        assertEquals(
            1, results.count { it is UninstallResult.Uninstalled },
            "同一个插件只能被成功卸载一次，实际 " + results.count { it is UninstallResult.Uninstalled } + " 次",
        )
        assertEquals(null, kernel.plugin(PluginId("dev.a")))
    }

    @Test
    fun concurrentInstallsOfDifferentPluginsAllSucceed() = runTest {
        val plugins = (1..8).map { ContribPlugin(m2Manifest("dev.p" + it), initDelayMs = 3) }
        val kernel = Kernel.boot(m2Config(CatalogLoader(plugins, emptyList())))

        val results = plugins.map { async { kernel.install(it.manifest) } }.awaitAll()

        assertEquals(8, results.count { it is InstallResult.Installed }, results.toString())
        assertEquals(8, kernel.plugins().size)
        val ids = kernel.plugins().map { it.manifest.id.value }
        assertEquals(ids.size, ids.toSet().size, "id 重复：" + ids)
    }

    @Test
    fun concurrentInstallAndUninstallLeaveConsistentState() = runTest {
        val plugins = (1..6).map { ContribPlugin(m2Manifest("dev.p" + it), initDelayMs = 2) }
        val kernel = Kernel.boot(m2Config(CatalogLoader(plugins, emptyList())))

        val jobs = plugins.map { async { kernel.install(it.manifest) } } +
            listOf(async { kernel.uninstall(PluginId("dev.p1")) })
        jobs.awaitAll()

        val ids = kernel.plugins().map { it.manifest.id.value }
        assertEquals(ids.size, ids.toSet().size, "注册表里出现了重复 id：" + ids)
        assertTrue(
            kernel.plugins().all { it.state == PluginState.ACTIVE },
            "卸载与安装交错后状态不一致：" + kernel.plugins().map { it.manifest.id.value + "=" + it.state },
        )
    }

    @Test
    fun concurrentInstallsDoNotLoseContributions() = runTest {
        val plugins = (1..6).map {
            ContribPlugin(
                m2Manifest("dev.p" + it, declares = listOf(dev.trellis.kernel.slot.Slots.HomeHeader)),
                registrations = listOf(
                    dev.trellis.kernel.slot.Slots.HomeHeader to
                        dev.trellis.kernel.slot.TextSlot("card" + it, order = it, title = "卡片" + it, body = ""),
                ),
                initDelayMs = 2,
            )
        }
        val kernel = Kernel.boot(m2Config(CatalogLoader(plugins, emptyList())))

        plugins.map { async { kernel.install(it.manifest) } }.awaitAll()

        assertEquals(
            6, kernel.extensions.all(dev.trellis.kernel.slot.Slots.HomeHeader).size,
            "并发安装丢了贡献点：" +
                kernel.extensions.all(dev.trellis.kernel.slot.Slots.HomeHeader).map { it.id },
        )
    }
}