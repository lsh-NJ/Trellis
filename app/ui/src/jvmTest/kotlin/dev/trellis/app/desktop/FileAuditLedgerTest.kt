package dev.trellis.app.desktop

import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.audit.AuditEntry
import dev.trellis.kernel.audit.AuditOutcome
import dev.trellis.kernel.core.PluginId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 审计日志落盘：只追加、重启后能读回、超过上限从最老的开始丢。
 *
 * 这里钉的是「能不能事后查」，不是格式细节 —— 所以断言的是条目内容，
 * 而不是 JSON 的字段顺序。
 */
class FileAuditLedgerTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-audit-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun entry(
        action: String,
        id: String? = "ai.pushups",
        detail: String = "细节",
        atMs: Long = 1_000L,
        actor: Actor = Actor.USER,
        outcome: AuditOutcome = AuditOutcome.COMPLETED,
    ): AuditEntry = AuditEntry(
        atMs = atMs,
        actor = actor,
        action = action,
        pluginId = id?.let { PluginId(it) },
        capability = null,
        detail = detail,
        outcome = outcome,
    )

    @Test
    fun entriesSurviveARestart() {
        val home = tempHome()
        val file = File(home, "audit.log")

        FileAuditLedger(file).append(entry("remove", detail = "文件进了回收站"))
        FileAuditLedger(file).append(entry("purge", detail = "不能恢复", atMs = 2_000L))

        val reloaded = FileAuditLedger(file).entries()
        assertEquals(listOf("remove", "purge"), reloaded.map { it.action })
        assertEquals("文件进了回收站", reloaded[0].detail)
        assertEquals(2_000L, reloaded[1].atMs)
        assertEquals(PluginId("ai.pushups"), reloaded[1].pluginId)
    }

    @Test
    fun oldestEntriesAreDroppedWhenOverCapacity() {
        val home = tempHome()
        val file = File(home, "audit.log")
        val ledger = FileAuditLedger(file, capacity = 2)

        ledger.append(entry("install", atMs = 1L))
        ledger.append(entry("disable", atMs = 2L))
        ledger.append(entry("remove", atMs = 3L))

        assertEquals(listOf("disable", "remove"), ledger.entries().map { it.action })
        assertEquals(2, file.readLines().size, "文件里也只该留 2 行")
        assertEquals(listOf("disable", "remove"), FileAuditLedger(file, capacity = 2).entries().map { it.action })
    }

    /** 记账不许反过来搞坏主流程：坏行跳过，不让整个日志读不出来。 */
    @Test
    fun brokenLinesAreSkippedInsteadOfFailingTheWholeLog() {
        val home = tempHome()
        val file = File(home, "audit.log")
        file.writeText("这不是 JSON\n", Charsets.UTF_8)
        FileAuditLedger(file).append(entry("remove"))

        val loaded = FileAuditLedger(file).entries()
        assertEquals(1, loaded.size)
        assertEquals("remove", loaded.single().action)
        assertTrue(file.readLines().any { it.contains("\"remove\"") })
    }
}
