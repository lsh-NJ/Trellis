package dev.trellis.app.desktop

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「AI 自动完成」账本的读写测试：往返一致、写前留 `.bak`、坏文件留档不崩、
 * 不认识的顶层字段不丢、撤销/屏蔽这些纯操作。
 */
class AutoCompletionStoreTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private var nowMs: Long = Instant.parse("2026-11-03T02:00:00Z").toEpochMilli() // 10:00 +08:00

    private fun clock(): TrellisClock = object : TrellisClock {
        override val zone: ZoneId = this@AutoCompletionStoreTest.zone
        override fun nowMs(): Long = nowMs
        override fun monotonicMs(): Long = nowMs
        override fun today(): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        override fun isoWeek(): String = isoWeekOf(today())
        override fun simulatedDate(): LocalDate? = null
    }

    private fun tempHome(): File {
        val dir = File.createTempFile("trellis-auto-completion-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun store(home: File = tempHome()): AutoCompletionStore = AutoCompletionStore(home, clock())

    private fun sampleEntry(id: String = "ac_00000001"): AutoCompletionEntry = AutoCompletionEntry(
        id = id,
        planId = "plan_1",
        occurrenceId = "occ_1",
        title = "HNSW 建图",
        chapter = "向量数据库",
        day = "2026-11-05",
        noteId = "n_1",
        noteTitle = "HNSW 笔记",
        evidence = "每个节点最多认识 M 个邻居",
        confidence = 0.91,
        at = 1_700_000_000_000L,
    )

    @Test
    fun ledgerRoundTripsThroughTheFile() {
        val store = store()
        val ledger = AutoCompletionLedger(
            entries = listOf(sampleEntry()),
            blocked = setOf("occ_9", "occ_8"),
        )
        assertTrue(store.save(ledger))
        assertTrue(store.file.isFile)
        val loaded = store.load()
        assertNull(store.loadError)
        assertEquals(1, loaded.entries.size)
        assertEquals("occ_1", loaded.entries.single().occurrenceId)
        assertEquals(0.91, loaded.entries.single().confidence)
        assertEquals(AutoCompletionStatus.Auto, loaded.entries.single().status)
        assertEquals(setOf("occ_9", "occ_8"), loaded.blocked)
    }

    @Test
    fun savingLeavesABackupOfThePreviousVersion() {
        val store = store()
        assertTrue(store.save(AutoCompletionLedger(entries = listOf(sampleEntry()))))
        assertTrue(store.save(AutoCompletionLedger(blocked = setOf("occ_2"))))
        val backup = File(store.file.parentFile, store.file.name + ".bak")
        assertTrue(backup.isFile, "第二次保存前应该留下 .bak")
        assertTrue(backup.readText(Charsets.UTF_8).contains("HNSW 建图"))
    }

    @Test
    fun corruptFileIsQuarantinedAndTheLedgerStartsEmpty() {
        val store = store()
        store.file.parentFile.mkdirs()
        store.file.writeText("{ 这不是 json", Charsets.UTF_8)
        val loaded = store.load()
        assertTrue(loaded.entries.isEmpty())
        assertTrue(loaded.blocked.isEmpty())
        assertNotNull(store.loadError)
        val quarantined = store.file.parentFile.listFiles()?.any { it.name.startsWith(store.file.name + ".bad-") }
        assertTrue(quarantined == true, "坏文件应该改名留档")
    }

    @Test
    fun unknownTopLevelFieldsSurviveARewrite() {
        val store = store()
        store.file.parentFile.mkdirs()
        store.file.writeText(
            "{\"version\":1,\"entries\":[],\"blocked\":[],\"future\":{\"a\":1}}",
            Charsets.UTF_8,
        )
        val loaded = store.load()
        assertTrue(store.save(loaded.copy(blocked = setOf("occ_7"))))
        val text = store.file.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"future\""), "不认识的字段要原样写回")
    }

    @Test
    fun newEntryIdsHaveTheExpectedShapeAndDoNotRepeat() {
        val store = store()
        val first = store.newEntryId()
        val second = store.newEntryId()
        assertTrue(first.startsWith("ac_"), "id 前缀是 ac_")
        assertEquals(11, first.length)
        assertTrue(first != second)
    }

    @Test
    fun undoneEntriesAreMarkedAndBlockedNodesStick() {
        val entry = sampleEntry()
        var ledger = AutoCompletionLedger().withCompletion(entry)
        assertEquals(entry.id, ledger.activeCompletionFor("occ_1")?.id)

        ledger = ledger.withUndone(entry.id, at = 1_700_000_100_000L)
        assertEquals(AutoCompletionStatus.Undone, ledger.entries.single().status)
        assertEquals(1_700_000_100_000L, ledger.entries.single().undoneAt)
        assertNull(ledger.activeCompletionFor("occ_1"))

        ledger = ledger.withBlocked("occ_1")
        assertTrue(ledger.isBlocked("occ_1"))
        ledger = ledger.withBlocked("occ_1")
        assertEquals(setOf("occ_1"), ledger.blocked, "重复屏蔽是幂等的")
        ledger = ledger.withUnblocked("occ_1")
        assertTrue(!ledger.isBlocked("occ_1"))
    }

    @Test
    fun recentEntriesComeNewestFirst() {
        val ledger = AutoCompletionLedger(
            entries = listOf(
                sampleEntry("ac_1").copy(at = 10L),
                sampleEntry("ac_2").copy(at = 30L),
                sampleEntry("ac_3").copy(at = 20L),
            ),
        )
        assertEquals(listOf("ac_2", "ac_3"), ledger.recentEntries(2).map { it.id })
    }
}
