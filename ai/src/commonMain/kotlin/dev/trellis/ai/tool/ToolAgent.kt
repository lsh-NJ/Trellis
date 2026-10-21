package dev.trellis.ai.tool

import dev.trellis.ai.JsonExtraction
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 模型这一步想干什么。 */
sealed interface AgentStep {
    data class UseTool(val name: String, val arguments: JsonObject) : AgentStep
    data class Reply(val text: String) : AgentStep
    data class Malformed(val reason: String) : AgentStep
}

/** 一整轮跑完的结果。 */
data class ToolRun(
    val ok: Boolean,
    val message: String,
    /** 每一步都在这：谁调了谁、返回了什么。给界面看，也给人查。 */
    val transcript: List<String>,
)

/**
 * 会自己动手的 agent。
 *
 * 和 [dev.trellis.ai.PluginAgent] 的区别：那个是「问一句、答一段、装一次」，
 * 这个可以**先去看再动手** —— 查已装插件、查真实的事件和动作、把草稿丢进去试跑、
 * 看到报错再改。它不再是盲写。
 *
 * ### 为什么用文字协议而不是原生 function calling
 *
 * [LlmClient] 是纯文本接口，请求已经开了 JSON mode。既然模型无论如何都要吐 JSON，
 * 那就把工具调用也定义成 JSON：少一层协议、少一份适配代码，
 * 而且**假模型（ScriptedLlm）能完整测这个循环** —— 原生 function calling 做不到这一点。
 *
 * ### 步数上限
 *
 * 模型可能一直调工具不收敛。上限是硬闸：到了就停，把已经查到的东西交出去，
 * 比无限烧用户的 token 强。
 */
class ToolAgent(
    private val llm: LlmClient,
    private val tools: List<AgentTool>,
    private val maxSteps: Int = 8,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 工具清单，拼进系统提示词。 */
    fun catalogue(): String = buildString {
        appendLine("<tools>")
        appendLine("你有这些工具可以调。每一步只做一件事，然后等结果。")
        appendLine()
        for (tool in tools) {
            appendLine("- " + tool.name + "：" + tool.description)
            if (tool.parameters.isNotBlank()) appendLine("  参数：" + tool.parameters)
        }
        appendLine()
        appendLine("调用一个工具，就输出：")
        appendLine("""{"tool": "工具名", "arguments": {...}}""")
        appendLine("全部做完了（或者你判断做不了），就输出：")
        appendLine("""{"done": true, "message": "一句给用户看的话"}""")
        appendLine("除了这两种 JSON，不要输出任何别的东西。")
        appendLine("</tools>")
    }

    suspend fun run(
        system: String,
        request: String,
        onLog: (String) -> Unit = {},
    ): ToolRun {
        val messages = mutableListOf(
            LlmMessage(LlmRole.System, system + "\n\n" + catalogue()),
            LlmMessage(LlmRole.User, request),
        )
        val transcript = mutableListOf<String>()

        fun note(line: String) {
            transcript += line
            onLog(line)
        }

        repeat(maxSteps) { index ->
            val step = index + 1
            val reply = try {
                llm.complete(messages)
            } catch (t: Throwable) {
                note("第 " + step + " 步：模型没回上来（" + (t.message ?: "未知错误") + "）")
                return ToolRun(false, "模型没回上来：" + (t.message ?: "未知错误"), transcript)
            }
            messages += LlmMessage(LlmRole.Assistant, reply)

            when (val parsed = parse(reply)) {
                is AgentStep.Reply -> {
                    note("第 " + step + " 步：它说完了")
                    return ToolRun(true, parsed.text, transcript)
                }

                is AgentStep.Malformed -> {
                    note("第 " + step + " 步：格式不对（" + parsed.reason + "），让它重说")
                    messages += LlmMessage(
                        LlmRole.User,
                        "你的输出不是我能认的格式：" + parsed.reason +
                            "。只能输出两种 JSON：调用工具，或 done。",
                    )
                }

                is AgentStep.UseTool -> {
                    val tool = tools.firstOrNull { it.name == parsed.name }
                    if (tool == null) {
                        note("第 " + step + " 步：它想调不存在的工具 " + parsed.name)
                        messages += LlmMessage(
                            LlmRole.User,
                            "没有叫 " + parsed.name + " 的工具。能用的只有：" +
                                tools.joinToString("、") { it.name },
                        )
                    } else {
                        note("第 " + step + " 步：" + parsed.name)
                        val result = try {
                            tool.call(parsed.arguments)
                        } catch (t: Throwable) {
                            ToolResult("工具自己出错了：" + (t.message ?: "未知错误"), isError = true)
                        }
                        note("　→ " + result.content.lineSequence().first())
                        if (result.done) {
                            return ToolRun(!result.isError, result.content, transcript)
                        }
                        messages += LlmMessage(
                            LlmRole.User,
                            "工具 " + parsed.name + " 返回：\n" + result.content,
                        )
                    }
                }
            }
        }

        note("步数用完了（" + maxSteps + " 步）")
        return ToolRun(false, "它在 " + maxSteps + " 步里没做完。可以再点一次，或者把要求说小一点。", transcript)
    }

    private fun parse(reply: String): AgentStep {
        val raw = JsonExtraction.extractFirstObject(reply)
            ?: return AgentStep.Malformed("里面没有 JSON")
        val obj = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (t: Throwable) {
            return AgentStep.Malformed("JSON 语法不对")
        }

        val done = obj["done"]?.jsonPrimitive?.booleanOrNull == true
        if (done) {
            val message = obj["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "做完了"
            return AgentStep.Reply(message)
        }

        val name = obj["tool"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return AgentStep.Malformed("既没有 tool 也没有 done")
        val args = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return AgentStep.UseTool(name, args)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this.isString) this.content else this.content
}
