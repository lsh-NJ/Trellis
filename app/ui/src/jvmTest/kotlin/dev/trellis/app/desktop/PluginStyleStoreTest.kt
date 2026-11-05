package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 逐插件样式的落盘：写坏的文件不能把界面带崩，只能回退。 */
class PluginStyleStoreTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-plugin-style-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun aBrokenFileFallsBackToNothing() {
        val home = tempHome()
        File(home, "plugin-style.json").writeText("{ 这不是 JSON", Charsets.UTF_8)

        assertTrue(PluginStyleStore(home).load().isEmpty())
    }

    @Test
    fun whatIsSavedCanBeLoadedBack() {
        val home = tempHome()
        val store = PluginStyleStore(home)
        store.save(
            mapOf(
                PluginId("ai.pushups") to PluginCardStyle(card = "filled", radius = 6),
                PluginId("ai.mood-checkin") to PluginCardStyle(card = "flat"),
            ),
        )

        val loaded = store.load()
        assertEquals("filled", loaded.getValue(PluginId("ai.pushups")).card)
        assertEquals(6, loaded.getValue(PluginId("ai.pushups")).radius)
        assertEquals("flat", loaded.getValue(PluginId("ai.mood-checkin")).card)
    }

    @Test
    fun anUnknownCardWordIsNormalisedToTheDefault() {
        val home = tempHome()
        File(home, "plugin-style.json").writeText(
            """{"ai.pushups":{"card":"彩虹色","radius":999}}""",
            Charsets.UTF_8,
        )

        val style = PluginStyleStore(home).load().getValue(PluginId("ai.pushups"))
        assertEquals("bordered", style.card)
        assertEquals(24, style.radius)
    }
}
