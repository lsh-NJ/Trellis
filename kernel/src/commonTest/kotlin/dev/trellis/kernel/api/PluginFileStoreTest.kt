package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.spi.PluginFileStore
import dev.trellis.kernel.storage.InMemoryStorageBackend
import dev.trellis.kernel.storage.StorageBackend
import dev.trellis.kernel.storage.storageNamespaceOf
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val WEEK = 7L * 24L * 60L * 60L * 1000L

/** 内核不碰文件系统，只负责说「该动了」。这个假仓库记下它到底说了什么。 */
private class RecordingFileStore : PluginFileStore {
    val moved = mutableListOf<String>()
    val restored = mutableListOf<String>()
    val purged = mutableListOf<String>()

    override suspend fun moveToTrash(id: PluginId) { moved += id.value }
    override suspend fun restoreFromTrash(id: PluginId) { restored += id.value }
    override suspend fun purgeTrash(id: PluginId) { purged += id.value }
}

/**
 * 「删除」必须真的动文件。
 *
 * 这条曾经是个半真半假的实现：界面上写着「删除」，`remove()` 却只把插件从运行列表里
 * 摘掉、再记一个数据回收期限，**插件目录原样留着**。用户点完删除，去目录里一看
 * 东西全在，自然要问「目前插件无法删除吗」。
 *
 * 内核不懂文件系统（领域中立、common 里没有 java.io），所以走一个端口：
 * 内核负责决定，平台层负责动手。这条测试钉的就是「内核到底有没有让它动手」。
 */
class PluginFileStoreTest {

    /** 存储要在多次启动之间共用  回收期限是落盘的，换个新后端就什么都没了。 */
    private suspend fun bootWith(
        files: PluginFileStore,
        clock: FakeClock = FakeClock(1_000L),
        storage: StorageBackend = InMemoryStorageBackend(),
    ): Kernel {
        val a = ContribPlugin(m2Manifest("dev.a"))
        val b = ContribPlugin(m2Manifest("dev.b"))
        return Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b"))), clock)
                .copy(storage = storage, pluginFiles = files),
        )
    }

    @Test
    fun removingMovesTheFilesIntoTheBin() = runTest {
        val files = RecordingFileStore()
        val kernel = bootWith(files)

        kernel.remove(PluginId("dev.b"))

        assertEquals(listOf("dev.b"), files.moved, "删除要把文件移进回收站，不能只从列表里摘掉")
        assertTrue(files.purged.isEmpty(), "刚删还不到期，不该真删")
        kernel.shutdown()
    }

    @Test
    fun disablingTouchesNoFilesAtAll() = runTest {
        val files = RecordingFileStore()
        val kernel = bootWith(files)

        kernel.disable(PluginId("dev.b"))

        assertTrue(files.moved.isEmpty(), "停用是「暂时不用」，文件一动不动")
        assertTrue(kernel.pendingPurges().isEmpty(), "停用也不该开始计时")
        kernel.shutdown()
    }

    @Test
    fun restoringBringsTheFilesBack() = runTest {
        val files = RecordingFileStore()
        val clock = FakeClock(1_000L)
        val kernel = bootWith(files, clock)

        kernel.remove(PluginId("dev.b"))
        val result = kernel.enable(PluginId("dev.b"))

        assertTrue(result is InstallResult.Installed, result.toString())
        assertEquals(listOf("dev.b"), files.restored, "还原要连文件一起还  只还数据不还文件不叫还原")
        assertTrue(
            kernel.pendingPurges().isEmpty(),
            "还原之后不该还留着回收期限，否则 7 天后它会被清掉",
        )
        kernel.shutdown()
    }

    @Test
    fun whenTheWindowPassesTheFilesArePurgedToo() = runTest {
        val files = RecordingFileStore()
        val clock = FakeClock(1_000L)
        val storage = InMemoryStorageBackend()

        val first = bootWith(files, clock, storage)
        first.remove(PluginId("dev.b"))
        first.shutdown()
        assertTrue(files.purged.isEmpty(), "7 天没到就清文件，等于删除没有后悔期")

        clock.advanceBy(WEEK + 1)
        val second = bootWith(files, clock, storage)

        assertEquals(listOf("dev.b"), files.purged, "过期之后连文件一起清")
        assertTrue(second.pendingPurges().isEmpty())
        second.shutdown()
    }
    /** 用户不想等 7 天。点了「彻底删除」，文件和数据当场就没，回收期限也一并抹掉。 */
    @Test
    fun purgingByHandDoesNotWaitForTheWeek() = runTest {
        val files = RecordingFileStore()
        val storage = InMemoryStorageBackend()
        val kernel = bootWith(files, FakeClock(1_000L), storage)
        val id = PluginId("dev.b")
        val ns = storageNamespaceOf(id)

        kernel.remove(id)
        storage.write(ns, "left-behind", "1")
        assertTrue(files.purged.isEmpty(), "刚删还在 7 天窗口里")

        assertTrue(kernel.purgeNow(id), "回收站里的插件应该能立刻清掉")

        assertEquals(listOf("dev.b"), files.purged, "点彻底删除要当场动文件，不能等 7 天")
        assertTrue(kernel.pendingPurges().isEmpty(), "清完了就不该还挂着回收期限")
        assertTrue(storage.keys(ns, "").isEmpty(), "数据要跟着文件一起没")
        kernel.shutdown()
    }

    /** 没删过的插件不给清  那会绕过「进回收站还能还原」这道缓冲。 */
    @Test
    fun purgingSomethingThatIsNotInTheBinIsRefused() = runTest {
        val files = RecordingFileStore()
        val storage = InMemoryStorageBackend()
        val kernel = bootWith(files, FakeClock(1_000L), storage)
        val ns = storageNamespaceOf(PluginId("dev.a"))

        storage.write(ns, "still-in-use", "1")
        assertFalse(kernel.purgeNow(PluginId("dev.a")), "在跑的插件不能一键清数据")

        assertTrue(files.purged.isEmpty(), "没进回收站就不该动文件")
        assertTrue(storage.keys(ns, "").isNotEmpty(), "数据和插件都该原地不动")
        kernel.shutdown()
    }

}
