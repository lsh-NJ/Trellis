package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.SlotCell
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 布局文件的读写。
 *
 * 这里钉的是「坏文件也要能起得来」和「写下去的格式读回来不会变」——
 * 布局坏掉不该让应用打不开，那比不排版严重得多。
 */
class LayoutStoreTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-layout-", "").let {
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
    fun rowsAreParsedWithSemicolonsAndPipes() {
        val rows = LayoutStore.parseRows("ai.mood-checkin|ai.reading-pages;  ai.pushups  ; ;")
        assertEquals(
            listOf(
                listOf(SlotCell(PluginId("ai.mood-checkin")), SlotCell(PluginId("ai.reading-pages"))),
                listOf(SlotCell(PluginId("ai.pushups"))),
            ),
            rows,
        )
    }

    @Test
    fun aSpanIsParsedAndClampedToTheGrid() {
        val rows = LayoutStore.parseRows("ai.pushups:8|ai.mood-checkin:4;ai.reading-pages:99;ai.x:0")
        assertEquals(8, rows[0][0].span)
        assertEquals(4, rows[0][1].span)
        assertEquals(12, rows[1][0].span, "超过 12 栏要收回 12")
        assertEquals(1, rows[2][0].span, "0 栏要收回 1")
    }

    @Test
    fun anEmptyTextMeansNoRows() {
        assertEquals(emptyList(), LayoutStore.parseRows("   "))
        assertEquals(emptyList(), LayoutStore.parseRows("; ;"))
    }

    @Test
    fun whatIsSavedComesBackUnchanged() {
        val home = tempHome()
        val store = LayoutStore(home)
        val layout = mapOf(
            Slots.HomeSection.id to listOf(
                listOf(SlotCell(PluginId("ai.pushups"), 8), SlotCell(PluginId("ai.mood-checkin"), 4)),
                listOf(SlotCell(PluginId("ai.reading-pages"))),
            ),
        )

        store.save(layout)

        assertTrue(File(home, "layout.json").isFile)
        assertEquals(layout, LayoutStore(home).load())
    }

    @Test
    fun aBrokenFileIsTreatedAsNoLayout() {
        val home = tempHome()
        File(home, "layout.json").writeText("{ 这不是 json")

        assertEquals(emptyMap(), LayoutStore(home).load())
    }

    @Test
    fun aMissingFileIsTreatedAsNoLayout() {
        assertEquals(emptyMap(), LayoutStore(tempHome()).load())
    }
}
