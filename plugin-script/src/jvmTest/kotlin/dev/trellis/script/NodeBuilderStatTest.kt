package dev.trellis.script

import dev.trellis.kernel.slot.UiNode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 钉住 stat 的数字渲染。
 *
 * 真实 bug：插件写 `value: 1`，卡片上显示成「1.0」——脚本里所有数字都是
 * JS double，走 `raw.toString()` 就会把整数带上小数尾巴。
 */
class NodeBuilderStatTest {

    private fun statOf(valueLiteral: String): UiNode.Stat {
        val script = """
            trellis.register({
              cards: [
                { id: "reading", point: "slot.home.section", order: 10, render: function (data) {
                    return { type: "column", children: [
                      { type: "stat", label: "在读的书", value: $valueLiteral }
                    ] };
                } }
              ]
            });
        """
        val sandbox = ScriptSandbox("dev.test", script)
        try {
            sandbox.boot()
            val node = NodeBuilder.build(sandbox.render("reading", emptyMap()))
            return ((node as UiNode.Column).children.single() as UiNode.Stat)
        } finally {
            sandbox.close()
        }
    }

    @Test
    fun anIntegerValueDoesNotGainADecimalTail() {
        // 读书记录卡片上的「在读的书」就是这个形状。
        assertEquals("1", statOf("1").value)
    }

    @Test
    fun aWholeFloatIsStillRenderedAsAnInteger() {
        assertEquals("1", statOf("1.0").value)
    }

    @Test
    fun aFractionKeepsItsDecimals() {
        assertEquals("1.5", statOf("1.5").value)
    }

    @Test
    fun zeroIsRenderedAsZero() {
        assertEquals("0", statOf("0").value)
    }

    @Test
    fun aStringValueIsPassedThroughUnchanged() {
        assertEquals("3 本", statOf("\"3 本\"").value)
    }
}
