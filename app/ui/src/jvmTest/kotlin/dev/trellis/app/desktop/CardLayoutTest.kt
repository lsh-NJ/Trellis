package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.SlotCell
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 「布局即数据」接到真内核上的这一层。
 *
 * 用真实的 [AppRuntime.create]（临时家目录）：卡片是谁贡献的、挂载点在不在屏上，
 * 这些答案只有真内核知道，假对象测不出来。
 */
class CardLayoutTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-card-layout-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun cells(ids: List<String>): List<SlotCell> = ids.map { SlotCell(PluginId(it)) }

    @Test
    fun theCardsOnTheHomeSectionCanBePutIntoOneRow() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val point = Slots.HomeSection.id
            val cards = runtime.cardsOn(point)
            assertTrue(cards.isNotEmpty(), "测试前提：首页区块上应该有内置插件贡献的卡片")
            val ids = cards.map { it.first }.distinct().map { it.value }
            assertTrue(cards.all { it.second.isNotBlank() }, "每张卡片都该有个标题，好让 agent 认出来")

            val message = runtime.arrangeCards(point.value, listOf(cells(ids)))

            assertTrue(message.startsWith("排好了"), message)
            assertEquals(listOf(ids), runtime.layoutFor(point).map { row -> row.map { it.pluginId.value } })
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun spansAreKeptSoTheGridCanBeTwelveColumnsWide() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val point = Slots.HomeSection.id
            val ids = runtime.cardsOn(point).map { it.first }.distinct().map { it.value }
            assertTrue(ids.isNotEmpty(), "测试前提：首页区块至少要有一张插件的卡片")

            val message = runtime.arrangeCards(
                point.value,
                listOf(listOf(SlotCell(PluginId(ids[0]), 8))),
            )

            assertTrue(message.startsWith("排好了"), message)
            val row = runtime.layoutFor(point).single()
            assertEquals(8, row.single().span)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun aPluginThatDidNotContributeThereIsRejectedAndNothingChanges() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val point = Slots.HomeSection.id
            val message = runtime.arrangeCards(point.value, listOf(cells(listOf("ai.not-a-real-plugin"))))

            assertTrue(message.startsWith("这些插件没往"), message)
            assertTrue(
                message.contains("这个点上现在有卡片的插件是"),
                "拒绝时要告诉 agent 现在有哪些可用：$message",
            )
            assertTrue(runtime.layoutFor(point).isEmpty())
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun aPointThatIsNotOnScreenIsRejected() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val message = runtime.arrangeCards(Slots.DetailAside.id.value, listOf(cells(listOf("core.plan"))))
            assertTrue(message.startsWith("这个挂载点现在没上屏"), message)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun emptyRowsClearTheLayout() = runTest {
        val runtime = AppRuntime.create(tempHome())
        try {
            val point = Slots.HomeSection.id
            val ids = runtime.cardsOn(point).map { it.first }.distinct().map { it.value }
            runtime.arrangeCards(point.value, listOf(cells(ids)))
            assertEquals(1, runtime.layoutFor(point).size)

            val message = runtime.arrangeCards(point.value, emptyList())

            assertTrue(message.startsWith("清空了"), message)
            assertTrue(runtime.layoutFor(point).isEmpty())
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun arrangingIsRememberedOnDisk() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            val point = Slots.HomeSection.id
            val ids = runtime.cardsOn(point).map { it.first }.distinct().map { it.value }
            runtime.arrangeCards(point.value, listOf(cells(ids)))
        } finally {
            runtime.shutdown()
        }

        val reopened = AppRuntime.create(home)
        try {
            val ids = reopened.cardsOn(Slots.HomeSection.id).map { it.first }.distinct().map { it.value }
            assertEquals(
                listOf(ids),
                reopened.layoutFor(Slots.HomeSection.id).map { row -> row.map { it.pluginId.value } },
            )
        } finally {
            reopened.shutdown()
        }
    }
}
