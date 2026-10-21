package dev.trellis.script

import dev.trellis.dsl.JvmPluginFileSystem
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.slot.UiNode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 这份 main.js 和用户机器上的示例插件是同一份文件（测试资源里存了副本）。
// 目的：保证「能装上去」不是我在嘴里说的，而是每次构建都真的跑了一遍。
class SamplePluginTest {

    private val root = "src/test/resources/plugins"

    @Test
    fun theLoaderFindsItAndReadsItsManifest() {
        val loader = ScriptLoader(JvmPluginFileSystem())
        val result = runBlocking { loader.discover(root) }

        assertTrue(result.errors.isEmpty(), "不该有错误：" + result.errors)
        val found = result.plugins.single()
        assertEquals(PluginId("dev.mistakes"), found.manifest.id)
        assertEquals("错题本", found.manifest.name)
        assertEquals("script", found.manifest.loader)
        assertEquals("main.js", found.manifest.entry)
        assertTrue(found.manifest.contributes.isNotEmpty(), "要声明挂载点，否则注册会被拒")
    }
    @Test
    fun theNotebookRendersAColumnWithAnInput() {
        val sandbox = ScriptSandbox("dev.mistakes", source())
        sandbox.boot()
        val card = sandbox.declaredCards().single()
        assertEquals("slot.home.section", card.pointId)

        val raw = sandbox.render("notebook", mapOf("store" to mapOf<String, String>()))
        val node = NodeBuilder.build(raw)
        assertTrue(node is UiNode.Column, "顶层应该是一个 column，实际是 " + node)
        val texts = (node as UiNode.Column).children
            .filterIsInstance<UiNode.Text>()
            .joinToString("|") { it.text }
        assertTrue(texts.contains("错题本"), "卡片该有标题：" + texts)
        assertTrue(texts.contains("一共攒了 0 道"), "空的时候也要说清楚有 0 道：" + texts)
    }

    @Test
    fun addingAnEntryAsksTheHostToStoreIt() {
        val sandbox = ScriptSandbox("dev.mistakes", source())
        sandbox.boot()
        val result = sandbox.runHandler("add", emptyMap(), mapOf("value" to "三角函数化简"))
        assertEquals("记下了", result.say)
        assertEquals(1, result.save.size)
        assertTrue(result.save.keys.single().startsWith("m:"), "键要有前缀：" + result.save)
        assertEquals("三角函数化简", result.save.values.single())
    }
    @Test
    fun anEmptyEntryIsRefusedInsteadOfStored() {
        val sandbox = ScriptSandbox("dev.mistakes", source())
        sandbox.boot()
        val result = sandbox.runHandler("add", emptyMap(), mapOf("value" to "   "))
        assertEquals("先写点什么", result.say)
        assertTrue(result.save.isEmpty(), "空内容不该存进去：" + result.save)
    }

    @Test
    fun droppingAnEntryAsksTheHostToForgetThatKey() {
        val sandbox = ScriptSandbox("dev.mistakes", source())
        sandbox.boot()
        val result = sandbox.runHandler("drop", emptyMap(), mapOf("key" to "m:123"))
        assertEquals(listOf("m:123"), result.remove)
    }

    private fun source(): String = java.io.File(root, "dev.mistakes/main.js").readText()
}
