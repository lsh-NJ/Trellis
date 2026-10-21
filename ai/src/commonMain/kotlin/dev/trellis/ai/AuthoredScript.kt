package dev.trellis.ai

import kotlinx.serialization.Serializable

/**
 * 一份「会跑的小应用」草稿。
 *
 * 和 [AuthoredPlugin]（声明式）的分工：能靠卡片+规则表达清楚的走声明式，
 * 需要列表、输入框、多步交互的走这里。两边的 id 规则、名字、说明是一致的。
 *
 * 刻意**不让模型写 manifest**：清单里的 contributes 由宿主把脚本跑一遍、
 * 数出真实注册了哪些卡片再生成。让模型手抄一份「我注册了什么」，
 * 迟早和脚本对不上，而那种错它自己看不见。
 */
@Serializable
data class AuthoredScript(
    val id: String,
    val name: String,
    val description: String = "",
    val usage: String = "",
    val limitation: String = "",
    val capabilities: List<String> = emptyList(),
    val script: String,
) {

    fun validate(): List<String> {
        val problems = mutableListOf<String>()
        if (!ID_PATTERN.matches(id)) {
            problems += "id「" + id + "」不合规：必须由小写字母、数字、点、横线组成，" +
                "至少两段，且以 ai. 开头，例如 ai.mistakes"
        }
        if (name.isBlank()) problems += "name 不能为空"
        if (name.length > 24) problems += "name 太长（" + name.length + " 个字），最多 24"
        if (script.isBlank()) problems += "script 不能为空"
        if (script.length > 20000) problems += "script 太长（" + script.length + " 个字符），最多 20000"
        if (script.isNotBlank() && !script.contains("trellis.register")) {
            problems += "script 里没有调用 trellis.register，这样的脚本什么也不会注册"
        }
        // Rhino 认得的 ES6 有限。这两样写下去一定跑不起来，早点说清楚。
        if (script.contains("=>")) problems += "script 里用了箭头函数（=>），沙箱只认 ES5，请写 function"
        if (script.contains("`")) problems += "script 里用了反引号模板字符串，沙箱只认 ES5，请用 + 拼"
        for (bad in FORBIDDEN) {
            if (script.contains(bad)) problems += "script 里出现了「" + bad + "」，沙箱不允许访问宿主"
        }
        val tooMuch = capabilities.filter { it !in AUTO_GRANTED }
        if (tooMuch.isNotEmpty()) {
            problems += "这些权限不能替用户点头：" + tooMuch.joinToString("、") +
                "。脚本插件只能声明：" + AUTO_GRANTED.joinToString("、")
        }
        return problems
    }

    /**
     * 生成两个文件。[cards] 是**脚本真的注册出来的**卡片，由调用方跑一遍拿到，
     * 不是模型声明的 —— 这是草稿和 manifest 之间唯一的真相来源。
     */
    fun toFiles(
        cards: List<ScriptCardDecl>,
        actions: List<ScriptActionDecl> = emptyList(),
    ): Map<String, String> {
        val slots = cards.filter { it.pointId.isNotBlank() }
            .groupBy { it.pointId }
            .entries
            .joinToString(", ") { (point, list) ->
                "\"" + point + "\": [" +
                    list.joinToString(", ") { "{\"id\": " + quote(it.id) + "}" } + "]"
            }
        val contributes = if (actions.isEmpty()) {
            slots
        } else {
            slots + (", \"kernel.action\": [" +
                actions.joinToString(", ") { "{\"id\": " + quote(it.id) + "}" } + "]")
        }

        val manifest = buildString {
            appendLine("{")
            appendLine("  \"id\": " + quote(id) + ",")
            appendLine("  \"version\": \"1.0.0\",")
            appendLine("  \"name\": " + quote(name) + ",")
            appendLine("  \"description\": " + quote(description) + ",")
            appendLine("  \"usage\": " + quote(usage) + ",")
            appendLine("  \"limitation\": " + quote(limitation) + ",")
            appendLine("  \"loader\": \"script\",")
            appendLine("  \"entry\": \"main.js\",")
            appendLine("  \"capabilities\": [" + capabilities.joinToString(", ") { quote(it) } + "],")
            appendLine("  \"contributes\": {" + contributes + "}")
            appendLine("}")
        }
        return linkedMapOf("manifest.json" to manifest, "main.js" to script)
    }

    private fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

private val ID_PATTERN = Regex("^ai\\.[a-z0-9][a-z0-9.-]*$")

/** 脚本插件允许声明的权限。其余的必须由用户到插件详情里亲手点。 */
private val AUTO_GRANTED = setOf("read_data", "write_data")

/** 沙箱里根本不该出现的东西。写出来就早点说清楚，比跑一遍报语法错有用。 */
private val FORBIDDEN = listOf("Java.type", "importPackage", "importClass", "Packages.", "require(")

/** 脚本跑一遍之后，它到底注册了哪张卡片挂在哪里。 */
data class ScriptCardDecl(val id: String, val pointId: String)

/** 脚本声明的一个动作。[id] 已经带上 script.<插件id>. 前缀，和内核里注册的完全一致。 */
data class ScriptActionDecl(val id: String, val label: String)
