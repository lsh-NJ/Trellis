package dev.trellis.script

import dev.trellis.kernel.slot.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptSandboxTest {

    private val helloScript = """
        trellis.register({
          cards: [
            { id: "hello", point: "slot.home.section", order: 10, render: function (data) {
                return { type: "text", text: "你好 " + data.summary.total };
            } }
          ],
          on: {
            save: function (data, payload) {
              return { say: "记下了", save: { last: payload.value } };
            }
          }
        });
    """

    @Test
    fun aScriptCanRegisterACardAndRenderIt() {
        val sandbox = ScriptSandbox("dev.test", helloScript)
        sandbox.boot()

        assertEquals(1, sandbox.declaredCards().size)
        assertEquals("slot.home.section", sandbox.declaredCards().first().pointId)

        val raw = sandbox.render("hello", mapOf("summary" to mapOf("total" to 3)))
        val node = NodeBuilder.build(raw)
        assertEquals(UiNode.Text("你好 3"), node)
    }

    @Test
    fun aClickComesBackAsWhatToSave() {
        val sandbox = ScriptSandbox("dev.test", helloScript)
        sandbox.boot()

        val result = sandbox.runHandler("save", emptyMap(), mapOf("value" to "42"))
        assertEquals("记下了", result.say)
        assertEquals(mapOf("last" to "42"), result.save)
    }

    @Test
    fun javaIsNotReachable() {
        val sandbox = ScriptSandbox("dev.test", "var x = java.lang.System.exit(0);")
        val failure = assertFailsWith<ScriptFailure> { sandbox.boot() }
        assertTrue(failure.message!!.contains("java"), "报错该提到 java：${failure.message}")
    }

    @Test
    fun anEndlessLoopIsCutOff() {
        val script = """
            trellis.register({
              cards: [ { id: "spin", point: "slot.home.section", render: function () { while (true) {} } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script, budgetMs = 300)
        sandbox.boot()
        val failure = assertFailsWith<ScriptFailure> { sandbox.render("spin", emptyMap()) }
        assertTrue(failure.message!!.contains("跑太久"), "报错该说跑太久：${failure.message}")
    }

    @Test
    fun aNodeTheHostDoesNotKnowBecomesANotice() {
        val script = """
            trellis.register({
              cards: [ { id: "odd", point: "slot.home.section", render: function () { return { type: "iframe" }; } } ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        sandbox.boot()
        val node = NodeBuilder.build(sandbox.render("odd", emptyMap()))
        assertTrue(node is UiNode.Notice, "认不出的节点必须变成提示，实际是 $node")
    }

    @Test
    fun aCardThatForgotItsMountPointIsRefusedAtBoot() {
        val script = """
            trellis.register({ cards: [ { id: "x", render: function () { return "hi"; } } ] });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        val failure = assertFailsWith<ScriptFailure> { sandbox.boot() }
        assertTrue(failure.message!!.contains("point"), failure.message)
    }
}
