package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「清除数据」的边界：删干净该删的，配置一个都不能少。 */
class DataWipeTest {

    private val home: File = Files.createTempDirectory("trellis-wipe").toFile()

    @AfterTest
    fun cleanUp() {
        home.deleteRecursively()
    }

    private fun touch(relative: String, text: String = "x") {
        val file = File(home, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun exists(relative: String): Boolean = File(home, relative).exists()

    @Test
    fun plansScopeRemovesLibraryDocsAndBindingsButKeepsConfig() {
        touch("plans/plan.db")
        touch("plans/plan.db-wal")
        touch("plans/plan.db.bak-20261107")
        touch("plans/docs/pln_a.md")
        touch("plans/docs/pln_a/2026-W45.md")
        touch("plans/categories.json")
        touch("plans/sessions.json")
        touch("plans/auto-completions.json")
        touch("llm.json")
        touch("study.json")
        touch("knowledge/notes/n1.md")

        val result = DataWipe.wipe(home, WipeScope.Plans)

        assertTrue(result.ok, result.failed.joinToString())
        assertFalse(exists("plans/plan.db"))
        assertFalse(exists("plans/plan.db-wal"))
        assertFalse(exists("plans/plan.db.bak-20261107"))
        assertFalse(exists("plans/docs"))
        assertFalse(exists("plans/categories.json"))
        assertFalse(exists("plans/sessions.json"))
        assertFalse(exists("plans/auto-completions.json"))
        // 配置与别的功能数据不受影响
        assertTrue(exists("llm.json"))
        assertTrue(exists("study.json"))
        assertTrue(exists("knowledge/notes/n1.md"))
    }

    @Test
    fun chatsScopeRemovesSessionsAndStewardMemoryButKeepsSettings() {
        touch("chat/chat_a.json")
        touch("chat/chat_b.json")
        touch("steward/settings.json")
        touch("steward/checkpoints.json")
        touch("steward/memory.jsonl")
        touch("steward/digests/2026-11-08.md")

        val result = DataWipe.wipe(home, WipeScope.Chats)

        assertTrue(result.ok, result.failed.joinToString())
        assertFalse(exists("chat"))
        assertFalse(exists("steward/checkpoints.json"))
        assertFalse(exists("steward/memory.jsonl"))
        assertFalse(exists("steward/digests"))
        assertTrue(exists("steward/settings.json"))
    }

    @Test
    fun knowledgeScopeRemovesNotesConceptsAndGraph() {
        touch("knowledge/notes/n1.md")
        touch("knowledge/notes/n2.md")
        touch("knowledge/concepts.json")
        touch("knowledge/graph.user.json")
        touch("knowledge/graph.cache.json")
        touch("ui.json")

        val result = DataWipe.wipe(home, WipeScope.Knowledge)

        assertTrue(result.ok, result.failed.joinToString())
        assertFalse(exists("knowledge/notes"))
        assertFalse(exists("knowledge/concepts.json"))
        assertFalse(exists("knowledge/graph.user.json"))
        assertFalse(exists("knowledge/graph.cache.json"))
        assertTrue(exists("ui.json"))
    }

    @Test
    fun activityScopeRemovesDatabaseButKeepsClassification() {
        touch("activity/activity.db")
        touch("activity/activity.db-wal")
        touch("activity/taxonomy.json")
        touch("activity/settings.json")

        val result = DataWipe.wipe(home, WipeScope.Activity)

        assertTrue(result.ok, result.failed.joinToString())
        assertFalse(exists("activity/activity.db"))
        assertFalse(exists("activity/activity.db-wal"))
        assertTrue(exists("activity/taxonomy.json"))
        assertTrue(exists("activity/settings.json"))
    }

    @Test
    fun practiceScopeRemovesAttemptsButKeepsExternalBank() {
        touch("exams/attempts.jsonl")
        touch("exams/my-bank.jsonl")

        val result = DataWipe.wipe(home, WipeScope.Practice)

        assertTrue(result.ok, result.failed.joinToString())
        assertFalse(exists("exams/attempts.jsonl"))
        assertTrue(exists("exams/my-bank.jsonl"))
    }

    @Test
    fun allScopeLeavesOnlyTheConfigFiles() {
        touch("llm.json")
        touch("ui.json")
        touch("study.json")
        touch("steward/settings.json")
        touch("steward/memory.jsonl")
        touch("steward/checkpoints.json")
        touch("activity/taxonomy.json")
        touch("activity/settings.json")
        touch("dev.json")
        touch("activity/activity.db")
        touch("plans/plan.db")
        touch("plans/docs/pln_a.md")
        touch("chat/chat_a.json")
        touch("knowledge/notes/n1.md")
        touch("exams/attempts.jsonl")
        touch("plugins/demo/manifest.json")
        touch("store.json")
        touch("app.log")

        val result = DataWipe.wipe(home, WipeScope.All)

        assertTrue(result.ok, result.failed.joinToString())
        val left = home.listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(
            setOf("llm.json", "ui.json", "study.json", "dev.json", "steward", "activity"),
            left,
            "只剩配置文件",
        )
        // steward 里只留 settings.json
        assertEquals(setOf("settings.json"), File(home, "steward").list()?.toSet())
        // activity 里只留分类与同步设置
        assertEquals(setOf("taxonomy.json", "settings.json"), File(home, "activity").list()?.toSet())
    }
}
