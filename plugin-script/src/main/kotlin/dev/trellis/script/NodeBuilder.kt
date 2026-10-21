package dev.trellis.script

import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextStyle
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * 把插件交回来的 JS 对象翻译成 [UiNode]。
 *
 * 这一段是**唯一的信任边界**：从这里出去的东西，宿主一律当数据画。
 * 所以这里认不出的东西一律变成 [UiNode.Notice]，绝不「尽量猜一猜」。
 */
internal object NodeBuilder {

    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 200
    private const val MAX_TEXT = 400
    private const val MAX_LABEL = 12

    fun build(value: Any?): UiNode = convert(value, 0, intArrayOf(MAX_NODES))

    private fun convert(value: Any?, depth: Int, budget: IntArray): UiNode {
        if (budget[0] <= 0) return UiNode.Notice("这张卡片画的东西太多了（超过 " + MAX_NODES + " 个）")
        budget[0] -= 1

        if (depth > MAX_DEPTH) return UiNode.Notice("卡片嵌套太深（超过 " + MAX_DEPTH + " 层）")
        if (value == null || value is Undefined) return UiNode.Notice("插件这一步什么都没返回")
        if (value is String) return UiNode.Text(clip(value))
        if (value is Number || value is Boolean) return UiNode.Text(value.toString())
        if (value !is Scriptable) return UiNode.Notice("插件返回了宿主看不懂的东西")

        val type = string(value, "type") ?: return UiNode.Notice("卡片里有一个节点没写 type")
        return when (type) {
            "column" -> UiNode.Column(children(value, depth, budget), gap(value))
            "row" -> UiNode.Row(children(value, depth, budget), gap(value))
            "text" -> UiNode.Text(clip(string(value, "text") ?: string(value, "content") ?: ""), style(value))
            "divider" -> UiNode.Divider
            "button" -> button(value)
            "input" -> input(value)
            else -> UiNode.Notice("插件用了宿主不认识的界面元素：" + type)
        }
    }

    private fun children(value: Scriptable, depth: Int, budget: IntArray): List<UiNode> {
        val raw = ScriptableObject.getProperty(value, "children")
        if (raw !is NativeArray) return emptyList()
        val out = mutableListOf<UiNode>()
        for (i in 0 until raw.length.toInt()) out += convert(raw.get(i, raw), depth + 1, budget)
        return out
    }

    private fun button(value: Scriptable): UiNode {
        val label = clip(string(value, "label") ?: "按钮", MAX_LABEL)
        val on = string(value, "on")
        if (on != null) return UiNode.Button(label, UiTap.Plugin(on, args(value)))
        val action = string(value, "action")
        if (action != null) return UiNode.Button(label, UiTap.Host(ActionCall(action, args(value))))
        return UiNode.Notice("按钮「" + label + "」没说要干什么")
    }

    private fun input(value: Scriptable): UiNode {
        val on = string(value, "on") ?: return UiNode.Notice("输入框没写 on（提交之后交给谁）")
        return UiNode.Input(
            hint = clip(string(value, "hint") ?: "", MAX_TEXT),
            submitLabel = clip(string(value, "submitLabel") ?: "记下", MAX_LABEL),
            onSubmit = UiTap.Plugin(on),
        )
    }

    private fun args(value: Scriptable): Map<String, String> {
        val raw = ScriptableObject.getProperty(value, "args")
        if (raw !is Scriptable) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in raw.ids) {
            val name = key.toString()
            out[name] = plain(ScriptableObject.getProperty(raw, name))
        }
        return out
    }

    private fun plain(value: Any?): String = when (value) {
        null, is Undefined -> ""
        is Number -> {
            val d = value.toDouble()
            if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
        }
        else -> value.toString()
    }

    private fun style(value: Scriptable): UiTextStyle = when (string(value, "style")) {
        "title" -> UiTextStyle.Title
        "caption" -> UiTextStyle.Caption
        else -> UiTextStyle.Body
    }

    private fun gap(value: Scriptable): Int =
        ((ScriptableObject.getProperty(value, "gap") as? Number)?.toInt() ?: 8).coerceIn(0, 32)

    private fun string(value: Scriptable, name: String): String? {
        val raw = ScriptableObject.getProperty(value, name)
        if (raw == null || raw is Undefined || raw === Scriptable.NOT_FOUND) return null
        if (raw is CharSequence) return raw.toString()
        return null
    }

    private fun clip(text: String, limit: Int = MAX_TEXT): String =
        if (text.length <= limit) text else text.substring(0, limit) + "…"
}
