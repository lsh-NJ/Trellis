package dev.trellis.kernel.api

import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.spi.PluginFileStore
import dev.trellis.kernel.storage.InMemoryStorageBackend
import dev.trellis.kernel.storage.StorageBackend
import dev.trellis.kernel.security.InMemoryManifestLedger
import dev.trellis.kernel.security.ManifestLedger
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
        ledger: ManifestLedger = InMemoryManifestLedger(),
    ): Kernel {
        val a = ContribPlugin(m2Manifest("dev.a"))
        val b = ContribPlugin(m2Manifest("dev.b"))
        return Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b"))), clock)
                .copy(storage = storage, pluginFiles = files, manifestLedger = ledger),
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

    /** 彻底删除之后，目录里也要忘干净  否则「删了还能不能重做」就成了「不能」。 */
    @Test
    fun purgingForgetsThePluginEntirely() = runTest {
        val files = RecordingFileStore()
        val kernel = bootWith(files, FakeClock(1_000L), InMemoryStorageBackend())
        val id = PluginId("dev.b")

        kernel.remove(id)
        kernel.purgeNow(id)

        assertTrue(kernel.catalog().none { it.id == id }, "彻底删除了就不该还在已安装目录里")
        assertTrue(kernel.liveCatalog().none { it.id == id })
        kernel.shutdown()
    }

    /** 进了回收站就不算「还装着一个」 —— 用户已经说过不要它了，别拿它拦新插件。 */
    @Test
    fun aBinnedPluginIsNotCountedAsInstalled() = runTest {
        val files = RecordingFileStore()
        val kernel = bootWith(files, FakeClock(1_000L), InMemoryStorageBackend())
        val id = PluginId("dev.b")

        kernel.remove(id)

        assertTrue(kernel.liveCatalog().none { it.id == id }, "回收站里的插件不该算已安装")
        assertTrue(kernel.catalog().any { it.id == id }, "但目录里要留着  还原还要靠它")
        kernel.shutdown()
    }

    /** 停用只是暂时不用，它还该被算进「已安装」。 */
    @Test
    fun aDisabledPluginStillCountsAsInstalled() = runTest {
        val files = RecordingFileStore()
        val kernel = bootWith(files, FakeClock(1_000L), InMemoryStorageBackend())
        val id = PluginId("dev.b")

        kernel.disable(id)

        assertTrue(kernel.liveCatalog().any { it.id == id }, "停用的插件还在，下次可能就启用了")
        kernel.shutdown()
    }

    /**
     * 彻底删除要连指纹和授权一起收。
     *
     * 留着指纹：同一个 id 重做、能力清单变了，会被判成「插件被篡改」永久拒装。
     * 留着授权：新插件**继承上一次的批准**，用户根本没看过它这次要什么 —— 提权暗道。
     */
    @Test
    fun purgingAlsoDropsTheFingerprintAndTheGrants() = runTest {
        val files = RecordingFileStore()
        val ledger = InMemoryManifestLedger()
        val kernel = bootWith(files, FakeClock(1_000L), InMemoryStorageBackend(), ledger)
        val id = PluginId("dev.b")
        kernel.grants.grant(id, setOf(CapabilityId("write_data")))
        assertTrue(ledger.fingerprintOf(id) != null, "装上之后应该记过指纹")

        kernel.remove(id)
        kernel.purgeNow(id)

        assertEquals(null, ledger.fingerprintOf(id), "指纹要忘掉，否则重做会被判成篡改")
        assertTrue(kernel.grants.grantedTo(id).isEmpty(), "授权要收回，否则新插件白捡权限")
        kernel.shutdown()
    }

}
