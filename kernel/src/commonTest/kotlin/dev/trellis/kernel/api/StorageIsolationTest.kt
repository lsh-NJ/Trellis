package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.storage.InMemoryStorageBackend
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.storage.StorageQuota
import dev.trellis.kernel.storage.StorageQuotaExceededException
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal class StorageProbe(
    override val manifest: PluginManifest,
    private val onInit: suspend (KvStore) -> Unit = {},
) : Plugin {

    var kv: KvStore? = null
        private set

    override suspend fun init(ctx: PluginContext) {
        kv = ctx.kv
        onInit(ctx.kv)
    }
}

private suspend fun twoPluginKernel(
    backend: InMemoryStorageBackend = InMemoryStorageBackend(),
    quota: StorageQuota = StorageQuota(),
    clock: FakeClock = FakeClock(1_000L),
): Triple<Kernel, StorageProbe, StorageProbe> {
    val a = StorageProbe(m2Manifest("dev.a"))
    val b = StorageProbe(m2Manifest("dev.b"))
    val kernel = Kernel.boot(
        m2Config(CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b"))), clock)
            .copy(storage = backend, storageQuota = quota),
    )
    return Triple(kernel, a, b)
}

class StorageIsolationTest {

    private val day = 24L * 60L * 60L * 1000L

    @Test
    fun eachPluginOnlySeesItsOwnKeys() = runTest {
        val (_, a, b) = twoPluginKernel()

        a.kv!!.put("draft", "A 的草稿")
        b.kv!!.put("draft", "B 的草稿")

        assertEquals("A 的草稿", a.kv!!.get("draft"))
        assertEquals("B 的草稿", b.kv!!.get("draft"))

        assertEquals(listOf("draft"), a.kv!!.keys())
        assertEquals(listOf("draft"), b.kv!!.keys())

        a.kv!!.delete("draft")
        assertEquals(null, a.kv!!.get("draft"))
        assertEquals("B 的草稿", b.kv!!.get("draft"), "删自己的不该动到别人")
    }

    /**
     * 这条是整个 7 的核心：**隔离做进了类型，不是做在字符串前缀上。**
     *
     * 插件 A 无论把键写成什么样  带斜杠的「路径」、带冒号的「命名空间前缀」、
     * 内核自己的记账键  都只会落在它自己的那一格里。
     */
    @Test
    fun noKeyShapeCanReachAnotherNamespace() = runTest {
        val (_, a, b) = twoPluginKernel()
        b.kv!!.put("secret", "B 的秘密")

        val crafted = listOf(
            "secret",
            "dev.b:secret",
            "plugin/dev.b/secret",
            "../dev.b/secret",
            "/plugin/dev.b/secret",
            "kernel:purge_deadline:dev.b",
            "__kernel__/secret",
        )
        for (key in crafted) {
            assertEquals(null, a.kv!!.get(key), "键「" + key + "」不该读到 dev.b 的数据")
        }

        // 而且它们不是被静默丢弃  确实成了 A 自己命名空间里的普通键
        a.kv!!.put("dev.b:secret", "这是 A 自己的值")
        assertEquals("这是 A 自己的值", a.kv!!.get("dev.b:secret"))
        assertEquals("B 的秘密", b.kv!!.get("secret"), "B 那边一点没变")
    }

    @Test
    fun keysWithAPrefixStillGoThroughIsolation() = runTest {
        val (_, a, b) = twoPluginKernel()
        a.kv!!.put("note:1", "1")
        a.kv!!.put("note:2", "2")
        b.kv!!.put("note:9", "9")

        assertEquals(listOf("note:1", "note:2"), a.kv!!.keys("note:"))
        assertEquals(listOf("note:9"), b.kv!!.keys("note:"))
    }

    @Test
    fun clearingOnePluginLeavesTheOtherUntouched() = runTest {
        val (_, a, b) = twoPluginKernel()
        a.kv!!.put("x", "1")
        b.kv!!.put("x", "2")

        a.kv!!.clear()

        assertEquals(null, a.kv!!.get("x"))
        assertEquals("2", b.kv!!.get("x"))
    }

    // ---- 卸载与数据保留（设计文档 7 规则 5）----

    @Test
    fun removingRetainsDataSoTheNextInstallFindsItAgain() = runTest {
        val backend = InMemoryStorageBackend()
        val clock = FakeClock(1_000L)
        val a = StorageProbe(m2Manifest("dev.a"))
        val loader = CatalogLoader(listOf(a), listOf(PluginId("dev.a")))
        val kernel = Kernel.boot(
            m2Config(loader, clock).copy(storage = backend),
        )

        a.kv!!.put("draft", "未完成的草稿")
        assertEquals(UninstallResult.Uninstalled(0, false), kernel.remove(PluginId("dev.a")))
        assertEquals(1, kernel.pendingPurges().size, "删除应当记下一个回收期限")
        assertTrue(kernel.dataNamespaces().isNotEmpty(), "数据此时还在")

        val installed = kernel.install(PluginId("dev.a"))
        assertTrue(installed is InstallResult.Installed, installed.toString())
        assertEquals("未完成的草稿", a.kv!!.get("draft"), "卸载重装不该丢数据")
        assertTrue(kernel.pendingPurges().isEmpty(), "重装成功后回收计划应当被取消")
    }

    @Test
    fun uninstallingWithPurgeRemovesDataImmediately() = runTest {
        val (kernel, a, _) = twoPluginKernel()
        a.kv!!.put("draft", "x")

        assertEquals(UninstallResult.Uninstalled(0, true), kernel.uninstall(PluginId("dev.a"), purgeData = true))
        assertTrue(kernel.pendingPurges().isEmpty(), "立刻清除就不该留回收计划")
        assertEquals(emptyList(), kernel.dataNamespaces())
    }

    @Test
    fun retainedDataIsCollectedOnceTheWindowPasses() = runTest {
        val backend = InMemoryStorageBackend()
        val clock = FakeClock(1_000L)
        val a = StorageProbe(m2Manifest("dev.a"))
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a"))), clock).copy(storage = backend),
        )
        a.kv!!.put("draft", "x")
        kernel.remove(PluginId("dev.a"))

        assertEquals(emptyList(), kernel.purgeExpiredData(), "保留期内不该被回收")
        assertTrue(kernel.dataNamespaces().isNotEmpty())

        clock.advanceBy(7 * day + 1)
        assertEquals(listOf(PluginId("dev.a")), kernel.purgeExpiredData())
        assertEquals(emptyList(), kernel.dataNamespaces())
        assertTrue(kernel.pendingPurges().isEmpty())
    }

    // ---- 配额（设计文档 6.3）----

    @Test
    fun aValueOverThePerValueQuotaFailsThePluginWithK010() = runTest {
        val big = StorageProbe(m2Manifest("dev.a")) { kv -> kv.put("payload", "x".repeat(500)) }
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(big), listOf(PluginId("dev.a"))))
                .copy(storageQuota = StorageQuota(maxValueBytes = 100)),
        )

        val info = kernel.plugin(PluginId("dev.a"))!!
        assertEquals(PluginState.FAILED, info.state)
        assertEquals(ErrorCode.QUOTA_EXCEEDED, info.errors.first().code)
        assertTrue(info.errors.first().detail.contains("超过上限"), info.errors.first().detail)
    }

    @Test
    fun theNamespaceTotalQuotaIsEnforced() = runTest {
        val (_, a, _) = twoPluginKernel(
            quota = StorageQuota(maxValueBytes = 1_000, maxNamespaceBytes = 300L),
        )
        val payload = "x".repeat(200)
        a.kv!!.put("k1", payload)

        assertFailsWith<StorageQuotaExceededException> { a.kv!!.put("k2", payload) }
        assertEquals(payload, a.kv!!.get("k1"), "超限的写入必须整体失败，不能留下半个状态")
    }

    @Test
    fun overwritingTheSameKeyDoesNotFalselyTriggerTheTotalQuota() = runTest {
        val (_, a, _) = twoPluginKernel(
            quota = StorageQuota(maxValueBytes = 1_000, maxNamespaceBytes = 300L),
        )
        val payload = "x".repeat(200)

        repeat(5) { a.kv!!.put("k", payload) }

        assertEquals(payload, a.kv!!.get("k"))
        assertEquals(listOf("k"), a.kv!!.keys())
    }

    @Test
    fun anEmptyOrOverlongKeyIsRejected() = runTest {
        val (_, a, _) = twoPluginKernel(quota = StorageQuota(maxKeyLength = 8))
        assertFailsWith<IllegalArgumentException> { a.kv!!.put("", "x") }
        assertFailsWith<IllegalArgumentException> { a.kv!!.put("0123456789", "x") }
    }
}
