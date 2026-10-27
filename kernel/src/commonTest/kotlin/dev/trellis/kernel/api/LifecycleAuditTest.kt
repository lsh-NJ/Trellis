package dev.trellis.kernel.api

import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.audit.AuditOutcome
import dev.trellis.kernel.audit.InMemoryAuditLedger
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 生命周期审计：装、启用、停用、删除、彻底删除都要在内核的只追加账本里留一行。
 *
 * 为什么值得单独测：这些动作不需要任何能力，审批门那套覆盖不到它们。
 * 少记一条，用户问「谁把插件删了」时就只能回去看目录 mtime 猜。
 */
class LifecycleAuditTest {

    private suspend fun kernelWithLedger(): Pair<Kernel, InMemoryAuditLedger> {
        val ledger = InMemoryAuditLedger()
        val plugin = ContribPlugin(m2Manifest("dev.a"))
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a"))), FakeClock(1_000L))
                .copy(auditLedger = ledger),
        )
        return kernel to ledger
    }

    @Test
    fun disablingAndEnablingLeaveOneLineEach() = runTest {
        val (kernel, ledger) = kernelWithLedger()

        kernel.disable(PluginId("dev.a"), Actor.USER)
        kernel.enable(PluginId("dev.a"), Actor.AGENT)

        val actions = ledger.entries().map { it.action }
        assertEquals(listOf("disable", "enable"), actions)
        assertEquals(AuditOutcome.COMPLETED, ledger.entries()[0].outcome)
        assertEquals(Actor.USER, ledger.entries()[0].actor)
        assertEquals(Actor.AGENT, ledger.entries()[1].actor, "管理员装回来的要记成 AGENT")
        assertTrue(ledger.entries()[1].detail.contains("重新启用"))
    }

    @Test
    fun removingAndPurgingAreBothRecorded() = runTest {
        val (kernel, ledger) = kernelWithLedger()
        val id = PluginId("dev.a")

        kernel.remove(id)
        kernel.purgeNow(id)

        val entries = ledger.entries()
        assertEquals(listOf("remove", "purge"), entries.map { it.action })
        assertTrue(entries[0].detail.contains("回收站"), "删除这行要说清文件去了回收站")
        assertTrue(entries[1].detail.contains("不能恢复"), "彻底删除这行要写明不可恢复")
        assertEquals(listOf(id, id), entries.map { it.pluginId })
    }

    /** 失败也要记：否则用户点了「删除」没反应，账本上一片空白，比没有日志更误导。 */
    @Test
    fun failedRemovalIsRecordedAsFailed() = runTest {
        val (kernel, ledger) = kernelWithLedger()

        kernel.remove(PluginId("dev.ghost"))

        val entry = ledger.entries().single()
        assertEquals("remove", entry.action)
        assertEquals(AuditOutcome.FAILED, entry.outcome)
        assertTrue(entry.detail.contains("没找到"))
    }
}
