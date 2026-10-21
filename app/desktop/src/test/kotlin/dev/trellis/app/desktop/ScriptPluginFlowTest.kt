package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 一个会跑代码的插件，从装到用走完整条路：
 *   装上 -> 卡片按当前数据画出来 -> 用户输入 -> 存进它自己的抽屉 -> 下一帧就多一条。
 *
 * 全程插件拿不到文件、拿不到网络、也看不见别的插件的抽屉。
 * 它唯一能做的事是「描述要画什么」和「说明要存什么」，动手的都是宿主。
 */
class ScriptPluginFlowTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-script-", "").let {
            it.delete(); it.mkdirs(); tempDirs += it; it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun installSample(home: File) {
        val dst = File(home, "plugins/dev.mistakes")
        dst.mkdirs()
        for (name in listOf("manifest.json", "main.js")) {
            File("src/test/resources/plugins/dev.mistakes", name).copyTo(File(dst, name), overwrite = true)
        }
    }

    private fun theNotebook(runtime: AppRuntime): DynamicSlot =
        runtime.contributionsOf(PluginId("dev.mistakes"))
            .mapNotNull { it.value as? DynamicSlot }
            .single()

    @Test
    fun theNotebookIsInstalledAndDrawsItself() = runTest {
        val home = tempHome()
        installSample(home)
        val runtime = AppRuntime.create(home)

        val names = runtime.kernel.catalog().map { it.id.value }
        assertTrue(names.contains("dev.mistakes"), "插件列表里应当有它：" + names)

        val slot = theNotebook(runtime)
        assertEquals("notebook", slot.id)

        val day = 86_400_000L
        val first = flatten(slot.build(DataContext(1_000L, 0L, day)))
        assertTrue(first.contains("错题本"), "卡片该有标题：" + first)
        assertTrue(first.contains("一共攒了 0 道"), "一开始该是空的：" + first)

        runtime.shutdown()
    }

    @Test
    fun typingOneInMakesItShowUpNextFrame() = runTest {
        val home = tempHome()
        installSample(home)
        val runtime = AppRuntime.create(home)

        val slot = theNotebook(runtime)
        val day = 86_400_000L

        val outcome = slot.perform("add", mapOf("value" to "三角函数化简"))
        assertTrue(outcome.ok, "应当记下来：" + outcome.message)
        assertEquals("记下了", outcome.message)

        val after = flatten(slot.build(DataContext(2_000L, 0L, day)))
        assertTrue(after.contains("一共攒了 1 道"), "存了就该多一条：" + after)
        assertTrue(after.contains("三角函数化简"), "存的内容要画出来：" + after)

        runtime.shutdown()
    }

    @Test
    fun deletingOneTakesItOutOfTheStore() = runTest {
        val home = tempHome()
        installSample(home)
        val runtime = AppRuntime.create(home)

        val slot = theNotebook(runtime)
        val day = 86_400_000L

        slot.perform("add", mapOf("value" to "立体几何第二问"))
        val one = flatten(slot.build(DataContext(3_000L, 0L, day)))
        assertTrue(one.contains("一共攒了 1 道"), one)

        // 真正的键是插件自己造的（m: 前缀 + 时间戳），宿主不该去猜。
        // 这里顺着它自己画出来的按钮拿：按钮 args 里的 key 就是那条的键。
        val drawn = slot.build(DataContext(3_000L, 0L, day)) as UiNode.Column
        val button = drawn.children
            .filterIsInstance<UiNode.Row>()
            .flatMap { it.children }
            .filterIsInstance<UiNode.Button>()
            .single()
        val tap = button.onTap as UiTap.Plugin
        assertEquals("drop", tap.handler)
        val key = tap.args.getValue("key")

        val dropped = slot.perform("drop", mapOf("key" to key))
        assertTrue(dropped.ok, "删不该报错：" + dropped.message)

        val after = flatten(slot.build(DataContext(4_000L, 0L, day)))
        assertTrue(after.contains("一共攒了 0 道"), "删掉就该回到 0：" + after)
        assertTrue(!after.contains("立体几何第二问"), "删掉的条目不该还画着：" + after)

        runtime.shutdown()
    }

    private fun flatten(node: UiNode): String = when (node) {
        is UiNode.Column -> node.children.joinToString("|") { flatten(it) }
        is UiNode.Row -> node.children.joinToString("|") { flatten(it) }
        is UiNode.Text -> node.text
        is UiNode.Notice -> node.text
        is UiNode.Button -> node.label
        is UiNode.Input -> node.hint
        UiNode.Divider -> ""
    }
}
