package dev.trellis.app.desktop

import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.UiNode
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 一句剧本念到底的假模型。 */
private class ScriptLlm(private val reply: String) : LlmClient {
    var calls = 0
        private set

    override suspend fun complete(messages: List<LlmMessage>): String {
        calls++
        return reply
    }
}

/**
 * 模型写的一段脚本。刻意只用 ES5：`var` + `function` + 方括号取值，
 * 沙箱里没有箭头函数和模板字符串。
 */
private val COUNTER_JS = """
function render(data) {
  var cups = 0;
  for (var key in data.store) { if (key.indexOf("c") === 0) { cups = cups + 1; } }
  return { type: "column", children: [
    { type: "text", text: "今天喝了 " + cups + " 杯" },
    { type: "button", label: "再来一杯", on: "drink" }
  ] };
}
trellis.register({
  cards: [{ id: "cups", point: "slot.home.section", render: render }],
  on: {
    drink: function () {
      var saved = {};
      saved["c" + Date.now()] = "1";
      return { say: "记下了", save: saved };
    }
  }
});
"""

private fun jsonScript(): String {
    val escaped = COUNTER_JS.trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    return "{\"id\":\"ai.cups\",\"name\":\"喝水打卡\",\"description\":\"记今天喝了几杯\"," +
        "\"usage\":\"在首页按一下\",\"capabilities\":[],\"script\":\"" + escaped + "\"}"
}

private fun deployCall(): String =
    "{\"tool\":\"deploy\",\"arguments\":{\"plugin\":" + jsonScript() + "}}"

private fun textOf(node: UiNode): String = when (node) {
    is UiNode.Text -> node.text + "\n"
    is UiNode.Column -> node.children.joinToString("") { textOf(it) }
    is UiNode.Row -> node.children.joinToString("") { textOf(it) }
    else -> ""
}

/**
 * 一句话 -> 模型写脚本 -> 宿主跑一遍数出卡片 -> 自带的自检 -> 装上 -> 能画能按。
 *
 * 这条测试守的是「小应用」这条路真的通了，而不是「模型说它装好了」。
 */
class ScriptAgentFlowTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File = File.createTempFile("trellis-agent-script-", "").let {
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
    fun theAgentCanShipAScriptPlugin() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        runtime.llmOverride = ScriptLlm(deployCall())
        runtime.researchFirst = true

        val outcome = runtime.generatePlugin("我要一个喝水打卡的小卡片，按一下记一杯")
        assertIs<AgentOutcome.Installed>(
            outcome,
            "应该装上，实际日志：\n" + runtime.agentLog.joinToString("\n"),
        )
        assertEquals("ai.cups", outcome.pluginId)

        val slot = runtime.contributionsOf(PluginId("ai.cups"))
            .mapNotNull { it.value as? DynamicSlot }
            .single()
        val now = System.currentTimeMillis()
        val open = DataContext(now, now - 1000, now + 1000)

        assertTrue(textOf(slot.build(open)).contains("今天喝了 0 杯"), "第一屏：" + textOf(slot.build(open)))

        val answer = slot.perform("drink", emptyMap())
        assertTrue(answer.ok, "按一下应该成功：" + answer.message)
        assertEquals("记下了", answer.message)

        assertTrue(textOf(slot.build(open)).contains("今天喝了 1 杯"), "第二屏：" + textOf(slot.build(open)))
    }
}
