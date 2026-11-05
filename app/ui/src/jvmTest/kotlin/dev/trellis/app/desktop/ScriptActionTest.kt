package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.UiNode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val MANIFEST = """
{
  "id": "dev.counter",
  "version": "1.0.0",
  "name": "计数器",
  "description": "数一下",
  "usage": "首页看",
  "limitation": "",
  "loader": "script",
  "entry": "main.js",
  "capabilities": [],
  "contributes": {
    "slot.home.section": [{"id": "count"}],
    "kernel.action": [{"id": "script.dev.counter.addOne"}]
  }
}
"""

private val COUNTER = """
function render(data) {
  var n = data.store["n"] || "0";
  return { type: "column", children: [{ type: "text", text: "现在 " + n }] };
}
trellis.register({
  cards: [{ id: "count", point: "slot.home.section", render: render }],
  actions: [{ id: "addOne", label: "加一个", params: ["text"] }],
  on: {
    addOne: function (payload) {
      return { say: "加了一个", save: { n: "1" } };
    }
  }
});
"""

/**
 * 插件从「只能显示」变成「能被别的东西调用」。
 *
 * 这条路是自扩展的地基：脚本除了画卡片，还能声明一个具名动作，
 * 于是规则可以点名它，模型也能把它当工具用。
 */
class ScriptActionTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File = File.createTempFile("trellis-action-", "").let {
        it.delete()
        it.mkdirs()
        tempDirs += it
        it
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun install(home: File) {
        val dir = File(home, "plugins/dev.counter")
        dir.mkdirs()
        File(dir, "manifest.json").writeText(MANIFEST.trim(), Charsets.UTF_8)
        File(dir, "main.js").writeText(COUNTER.trim(), Charsets.UTF_8)
    }

    private fun shown(runtime: AppRuntime): String {
        val slots = runtime.contributionsOf(PluginId("dev.counter"))
            .mapNotNull { it.value as? DynamicSlot }
        if (slots.isEmpty()) {
            val errors = runtime.kernelErrors.joinToString(" | ") { it.detail }
            return "（没有卡片；内核报错：" + errors + "）"
        }
        val slot = slots.single()
        val now = System.currentTimeMillis()
        val node = kotlinx.coroutines.runBlocking { slot.build(DataContext(now, now, now)) }
        return textOf(node)
    }

    private fun textOf(node: UiNode): String = when (node) {
        is UiNode.Text -> node.text
        is UiNode.Column -> node.children.joinToString("") { textOf(it) }
        is UiNode.Row -> node.children.joinToString("") { textOf(it) }
        else -> ""
    }

    @Test
    fun aScriptPluginCanOfferAnActionOthersCanCall() = runTest {
        val home = tempHome()
        install(home)
        val runtime = AppRuntime.create(home)

        assertTrue(shown(runtime).contains("现在 0"), "一开始应该是 0：" + shown(runtime))

        val outcome = runtime.kernel.performAction(
            PluginId("dev.counter"),
            ActionCall("script.dev.counter.addOne", mapOf("text" to "hello")),
        )
        assertTrue(outcome.ok, "动作应该执行成功：" + outcome.message)
        assertTrue(shown(runtime).contains("现在 1"), "执行之后应该是 1：" + shown(runtime))
    }
}
