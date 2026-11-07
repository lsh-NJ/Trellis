package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 计划 ↔ 会话绑定：`<home>/plans/sessions.json`。 */
class PlanSessionStoreTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-plan-session").toFile()

    @Test
    fun missingFileMeansNoBindings() {
        val store = PlanSessionStore(tempHome())
        assertNull(store.sessionFor("pln_a"))
        assertEquals(emptyMap(), store.bindings())
    }

    @Test
    fun aBindingIsPersisted() {
        val home = tempHome()
        PlanSessionStore(home).bind("pln_a", "chat_1")

        // 换实例读：绑定要能落盘。
        assertEquals("chat_1", PlanSessionStore(home).sessionFor("pln_a"))
    }

    @Test
    fun rebindingOverwritesTheOldSession() {
        val home = tempHome()
        PlanSessionStore(home).bind("pln_a", "chat_1")
        PlanSessionStore(home).bind("pln_a", "chat_2")
        assertEquals("chat_2", PlanSessionStore(home).sessionFor("pln_a"))
    }

    @Test
    fun separatePlansKeepSeparateSessions() {
        val home = tempHome()
        val store = PlanSessionStore(home)
        store.bind("pln_a", "chat_1")
        store.bind("pln_b", "chat_2")
        assertEquals("chat_1", PlanSessionStore(home).sessionFor("pln_a"))
        assertEquals("chat_2", PlanSessionStore(home).sessionFor("pln_b"))
    }

    @Test
    fun blankIdsAreIgnored() {
        val home = tempHome()
        PlanSessionStore(home).bind("", "chat_1")
        PlanSessionStore(home).bind("pln_a", "  ")
        assertEquals(emptyMap(), PlanSessionStore(home).bindings())
    }
}
