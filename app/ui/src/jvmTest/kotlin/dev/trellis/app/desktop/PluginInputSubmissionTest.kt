package dev.trellis.app.desktop

import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.ui.inputSubmitArgs
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 插件输入框的提交契约：用户打的字必须作为 `value` 交给插件的 handler。
 *
 * 这条曾经真的坏过：浮窗自己存了一份文字、卡片又存了一份，提交时读的是卡片那份（永远是空的），
 * 于是「输入的内容没办法实际进入」。这里钉住「谁提交、参数叫什么、插件 args 怎么合并」。
 */
class PluginInputSubmissionTest {

    @Test
    fun theTypedTextGoesInAsValueAndKeepsTheHandlerArgs() {
        val node = UiNode.Input(
            hint = "加一本书：书名",
            submitLabel = "加一本书",
            onSubmit = UiTap.Plugin("addBook", mapOf("row" to "3")),
        )

        assertEquals(mapOf("row" to "3", "value" to "三体"), inputSubmitArgs(node, "三体"))
    }

    @Test
    fun anEmptySubmissionStillCarriesAnEmptyValue() {
        val node = UiNode.Input("记页数", "记一页", UiTap.Plugin("setPage"))

        assertEquals(mapOf("value" to ""), inputSubmitArgs(node, ""))
    }

    @Test
    fun aHostActionInputNeverPretendsToCarryAPluginArg() {
        val node = UiNode.Input("书名", "打开", UiTap.Host(dev.trellis.kernel.rule.ActionCall("openKnowledgeNote", mapOf("title" to "x"))))

        // 宿主动作不是插件 handler，参数只留 value —— 别把宿主动作的 args 混进来冒充 payload。
        assertEquals(mapOf("value" to "三体"), inputSubmitArgs(node, "三体"))
    }
}
