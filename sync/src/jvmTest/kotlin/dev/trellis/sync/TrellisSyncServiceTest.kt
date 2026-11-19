package dev.trellis.sync

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrellisSyncServiceTest {

    @Test
    fun configRoundTrip() {
        val home = Files.createTempDirectory("trellis-sync-config").toFile()
        val config = SyncConfig(
            enabled = true,
            remotePath = File(home, "remote").absolutePath,
            domains = setOf(SyncConfig.DOMAIN_NOTES),
        )
        SyncConfigStore.save(home, config)
        assertEquals(config, SyncConfigStore.load(home))
    }

    @Test
    fun configIgnoresUnknownDomains() {
        val home = Files.createTempDirectory("trellis-sync-config2").toFile()
        val file = SyncConfigStore.file(home)
        file.parentFile?.mkdirs()
        file.writeText("""{"schema":1,"enabled":true,"remotePath":"/tmp/x","domains":["notes","bogus"]}""")
        val loaded = SyncConfigStore.load(home)
        assertTrue(loaded.enabled)
        assertEquals(setOf(SyncConfig.DOMAIN_NOTES), loaded.domains)
        assertFalse(loaded.domains.contains("bogus"))
    }

    @Test
    fun notesAndPlanDocsSyncBetweenTwoHomes() = runBlocking {
        val root = Files.createTempDirectory("trellis-sync-service").toFile()
        val remote = File(root, "remote").absolutePath
        val homeA = File(root, "A").apply { mkdirs() }
        val homeB = File(root, "B").apply { mkdirs() }

        val notesA = File(File(homeA, "knowledge"), "notes").apply { mkdirs() }
        File(notesA, "n1.md").writeText("# 笔记一", Charsets.UTF_8)
        val docsA = File(File(homeA, "knowledge"), "plan-docs/pln_1").apply { mkdirs() }
        File(docsA, "总览.md").writeText("# 总览", Charsets.UTF_8)

        val serviceA = TrellisSyncService(homeA, SyncConfig(enabled = true, remotePath = remote), "test")
        val serviceB = TrellisSyncService(homeB, SyncConfig(enabled = true, remotePath = remote), "test")

        serviceA.sync()
        serviceB.sync()

        assertEquals("# 笔记一", File(File(File(homeB, "knowledge"), "notes"), "n1.md").readText())
        assertEquals("# 总览", File(File(homeB, "knowledge/plan-docs/pln_1"), "总览.md").readText())
    }
}
