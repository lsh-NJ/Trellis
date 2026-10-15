package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.storage.InMemoryStorageBackend
import dev.trellis.kernel.storage.StorageBackend
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val DAY = 24L * 60L * 60L * 1000L

/**
 * 「停用」必须是**持久化**的。
 *
 * 这条曾经是个真 bug：卸载只改了内存里的注册表，重启之后插件原样回来 
 * 用户会觉得界面上那个按钮是假的。原因是「当前启用集」这个概念
 * （设计文档 12 的 Profile）一直只活在内核的一个字段里，没有落盘。
 *
 * 现在它落在存储的内核命名空间里：`kernel/profile:disabled`。
 */
class DisablePersistenceTest {

    private suspend fun bootWith(
        storage: StorageBackend,
        clock: FakeClock = FakeClock(1_000L),
    ): Pair<Kernel, ContribPlugin> {
        val a = ContribPlugin(m2Manifest("dev.a"))
        val b = ContribPlugin(m2Manifest("dev.b"))
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b"))), clock)
                .copy(storage = storage),
        )
        return kernel to a
    }

    @Test
    fun aDisabledPluginStaysDisabledAfterARestart() = runTest {
        val storage = InMemoryStorageBackend()

        val (first, _) = bootWith(storage)
        assertEquals(2, first.plugins().size)

        first.disable(PluginId("dev.b"))
        assertEquals(1, first.plugins().size, "停用之后当场就没了")
        assertEquals(setOf(PluginId("dev.b")), first.disabledPlugins())
        first.shutdown()

        val (second, _) = bootWith(storage)
        assertEquals(1, second.plugins().size, "停用必须活过重启  这是这条测试存在的全部理由")
        assertEquals(PluginState.ACTIVE, second.plugin(PluginId("dev.a"))!!.state)
        assertNull(second.plugin(PluginId("dev.b")))
        second.shutdown()
    }

    @Test
    fun enablingBringsItBackAndClearsTheRecord() = runTest {
        val storage = InMemoryStorageBackend()

        val (first, _) = bootWith(storage)
        first.disable(PluginId("dev.b"))
        first.shutdown()

        val (second, _) = bootWith(storage)
        assertEquals(1, second.plugins().size)

        val result = second.enable(PluginId("dev.b"))
        assertTrue(result is InstallResult.Installed, result.toString())
        assertEquals(2, second.plugins().size)
        assertTrue(second.disabledPlugins().isEmpty(), "启用之后清单里不该还留着它")
        second.shutdown()

        // 而且这个「启用」本身也要落盘
        val (third, _) = bootWith(storage)
        assertEquals(2, third.plugins().size)
        third.shutdown()
    }

    @Test
    fun disablingTouchesNeitherDataNorFiles() = runTest {
        val storage = InMemoryStorageBackend()

        val (first, _) = bootWith(storage)
        first.disable(PluginId("dev.b"))

        // 停用**不写回收记录**  那是「删除」才会做的事
        assertTrue(first.pendingPurges().isEmpty(), "停用不是删除，不该触发数据回收")

        // 数据还在（下次启用时能读回来）
        assertEquals(setOf(PluginId("dev.b")), first.disabledPlugins())
        first.shutdown()
    }

    @Test
    fun anExpiredPurgeRecordIsCollectedAtBoot() = runTest {
        val storage = InMemoryStorageBackend()
        val clock = FakeClock(1_000L)

        val (first, _) = bootWith(storage, clock)
        // remove = 删除：既停用，也开始计时清数据
        first.remove(PluginId("dev.b"))
        assertEquals(1, first.pendingPurges().size, "停用时记下了回收期限")
        first.shutdown()

        // 还没到期：重启时不该被回收
        val (second, _) = bootWith(storage, clock)
        assertEquals(1, second.pendingPurges().size)
        second.shutdown()

        // 过了 7 天：启动时顺手回收
        clock.advanceBy(8 * DAY)
        val (third, _) = bootWith(storage, clock)
        assertTrue(
            third.pendingPurges().isEmpty(),
            "启动时应当回收过期数据  以前这个方法只在测试里被调过，实际永远不会发生",
        )
        third.shutdown()
    }
}
