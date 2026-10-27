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
    /** 它明确说不做，并给出理由。和「做完了」是两件事，界面上说的话也不同。 */
    data class Refuse(val reason: String) : AgentStep
}

/** 一整轮跑完的结果。 */
data class ToolRun(
    val ok: Boolean,
    val message: String,
    /** 每一步都在这：谁调了谁、返回了什么。给界面看，也给人查。 */
    val transcript: List<String>,
    /** 它是主动说不做（cannotDo），不是没做成。这两种在界面上说的话不一样。 */
    val refused: Boolean = false,
)

/**
 * 会自己动手的 agent。
 *
 * 它不是「问一句、答一段、装一次」的一次性生成器：
 * 它可以**先去看再动手** —— 查已装插件、查真实的事件和动作、把草稿丢进去试跑、
 * 看到报错再改。它不再是盲写。
 *
 * ### 为什么用文字协议而不是原生 function calling
 *
 * [LlmClient] 是纯文本接口，请求已经开了 JSON mode。既然模型无论如何都要吐 JSON，
 * 那就把工具调用也定义成 JSON：少一层协议、少一份适配代码，
 * 而且**假模型（ScriptedLlm）能完整测这个循环** —— 原生 function calling 做不到这一点。
 *
 * ### 两种入口
 *
 * - [run] (system, request)：老入口，自己拼两条消息；
 * - [run] (messages)：新入口，消息已经由调用方按「静态前缀 → 动态状态 → 历史 →
 *   任务模块 → 用户输入」拼好。管理员 agent 走这条，这样前缀缓存才不会被
 *   每轮的动态状态打掉。
 *
 * ### 步数
 *
 * **不设额度上限**：复杂任务要多少步就多少步（之前的 8/10 步上限会把「查五个插件、
 * 逐个试跑」这种正常任务腰斩，用户看到的是「它在 10 步里没做完」）。
 * 防跑飞不靠额度，靠三件事：
 *
 * - 同一个工具、同一组参数连续调 [repeatLimit] 次 → 判定卡住，停下来把查到的交出去；
 * - [safetyLimit] 是保险丝（200 步），不是额度，正常任务根本碰不到；
 * - 模型自己说 done / cannotDo 就结束。
 */
class ToolAgent(
    private val llm: LlmClient,
    private val tools: List<AgentTool>,
    /**
     * 调用方明确要「这一轮最多几步」时才给的硬闸；`null`（默认）= 不设额度。
     * 留这个参数是为了测试和「只准看一眼」的场景，不是给正常对话用的。
     */
    private val maxSteps: Int? = null,
    /** 保险丝，只防跑飞，不是额度。 */
    private val safetyLimit: Int = 200,
    /** 同一个工具 + 同一组参数连续这么多次，就当卡住了。 */
    private val repeatLimit: Int = 3,
    /**
     * 它说「做完了」之前的最后一道检查。返回一句话就是要它接着干，返回 null 就是放行。
     *
     * 存在的理由：模型会理直气壮地说「已经装好了，不用再动」，而其实这一轮它一个文件都没写。
     * 这种话不能只靠提示词防 —— 得在它交活的时候真的去核对。
     */
    private val beforeFinishing: () -> String? = { null },
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 工具清单，拼进系统提示词。 */
    fun catalogue(): String = catalogueOf(tools)

    suspend fun run(
        system: String,
        request: String,
        onLog: (String) -> Unit = {},
    ): ToolRun = run(
        messages = listOf(
            LlmMessage(LlmRole.System, system + "\n\n" + catalogue()),
            LlmMessage(LlmRole.User, request),
        ),
        onLog = onLog,
    )

    /**
     * 跑一轮工具循环，消息原样接收。
     *
     * 注意：这里**不会**再往 system 后面拼工具清单 —— 调用方要自己拼好
     * （见 [catalogueOf]），否则同一个清单会出现两遍，把稳定前缀顶掉。
     */
    suspend fun run(
        messages: List<LlmMessage>,
        onLog: (String) -> Unit = {},
    ): ToolRun {
        val conversation = messages.toMutableList()
        val transcript = mutableListOf<String>()
        // 这一轮工具结果里带上来的截图，下一次请求时交给模型看。
        val pendingImages = mutableListOf<String>()

        fun note(line: String) {
            transcript += line
            onLog(line)
        }

        var step = 0
        var lastSignature: String? = null
        var repeats = 0

        while (true) {
            step += 1
            if (maxSteps != null && step > maxSteps) {
                note("到这一轮说好的步数上限了（" + maxSteps + " 步）")
                return ToolRun(false, "这一轮说好最多 " + maxSteps + " 步，还没做完。", transcript)
            }
            if (step > safetyLimit) {
                note("保险丝断了（" + safetyLimit + " 步）")
                return ToolRun(false, "它在 " + safetyLimit + " 步里没收敛，已经停下。", transcript)
            }
            val reply = try {
                llm.complete(conversation)
            } catch (t: Throwable) {
                note("第 " + step + " 步：模型没回上来（" + (t.message ?: "未知错误") + "）")
                return ToolRun(false, "模型没回上来：" + (t.message ?: "未知错误"), transcript)
            }
            conversation += LlmMessage(LlmRole.Assistant, reply)

            when (val parsed = parse(reply)) {
                is AgentStep.Reply -> {
                    val complaint = beforeFinishing()
                    if (complaint != null) {
                        note("第 " + step + " 步：它说完了，但 " + complaint)
                        conversation += LlmMessage(LlmRole.User, complaint)
                    } else {
                        note("第 " + step + " 步：它说完了")
                        return ToolRun(true, parsed.text, transcript)
                    }
                }

                is AgentStep.Refuse -> {
                    note("第 " + step + " 步：它说不做 —— " + parsed.reason)
                    return ToolRun(false, parsed.reason, transcript, refused = true)
                }

                is AgentStep.Malformed -> {
                    note("第 " + step + " 步：格式不对（" + parsed.reason + "），让它重说")
                    conversation += LlmMessage(
                        LlmRole.User,
                        "你的输出不是我能认的格式：" + parsed.reason +
                            "。只能输出两种 JSON：调用工具，或 done。",
                    )
                }

                is AgentStep.UseTool -> {
                    val tool = tools.firstOrNull { it.name == parsed.name }
                    if (tool == null) {
                        note("第 " + step + " 步：它想调不存在的工具 " + parsed.name)
                        conversation += LlmMessage(
                            LlmRole.User,
                            "没有叫 " + parsed.name + " 的工具。能用的只有：" +
                                tools.joinToString("、") { it.name },
                        )
                    } else {
                        note("第 " + step + " 步：" + parsed.name)
                        val signature = parsed.name + "|" + parsed.arguments
                        if (signature == lastSignature) {
                            repeats += 1
                        } else {
                            lastSignature = signature
                            repeats = 1
                        }
                        if (repeats >= repeatLimit) {
                            note("　→ 同一个工具、同一组参数连续调了 " + repeats + " 次，判定卡住")
                            return ToolRun(
                                false,
                                "它连续 " + repeats + " 次调同一个工具、传同一组参数，像是卡住了。" +
                                    "把要求说具体一点，或者先让它说说打算怎么做。",
                                transcript,
                            )
                        }
                        val result = try {
                            tool.call(parsed.arguments)
                        } catch (t: Throwable) {
                            ToolResult("工具自己出错了：" + (t.message ?: "未知错误"), isError = true)
                        }
                        note("　→ " + result.content.lineSequence().first())
                        if (result.image != null) {
                            pendingImages += result.image
                            note("　→ 附了一张截图（模型能看见）")
                        }
                        if (result.done) {
                            return ToolRun(!result.isError, result.content, transcript)
                        }
                        conversation += LlmMessage(
                            LlmRole.User,
                            "工具 " + parsed.name + " 返回：\n" + result.content,
                            images = pendingImages.toList(),
                        )
                        pendingImages.clear()
                    }
                }
            }
        }
    }

    private fun parse(reply: String): AgentStep {
        // 有些模型不吐约定的 JSON，而是吐自己训练里的标记语言（见 DeepSeekMarkup）。
        // 认出来就按工具调用执行 —— 否则界面上是一屏乱码，而且工具一次都不跑。
        DeepSeekMarkup.firstInvocation(reply)?.let { invocation ->
            val obj = try {
                json.parseToJsonElement(invocation.arguments.ifBlank { "{}" }).jsonObject
            } catch (t: Throwable) {
                return AgentStep.Malformed("工具参数不是 JSON")
            }
            return AgentStep.UseTool(invocation.name, obj)
        }

        val raw = JsonExtraction.extractFirstObject(reply)
            ?: return AgentStep.Malformed("里面没有 JSON")
        val obj = try {
            json.parseToJsonElement(raw).jsonObject
        } catch (t: Throwable) {
            return AgentStep.Malformed("JSON 语法不对")
        }

        val cannotDo = obj["cannotDo"]?.jsonPrimitive?.contentOrNullSafe()
        if (cannotDo != null && cannotDo.isNotBlank()) return AgentStep.Refuse(cannotDo)

        val done = obj["done"]?.jsonPrimitive?.booleanOrNull == true
        if (done) {
            val message = obj["message"]?.jsonPrimitive?.contentOrNullSafe() ?: "做完了"
            return AgentStep.Reply(message)
        }

        val name = obj["tool"]?.jsonPrimitive?.contentOrNullSafe()
            ?: return AgentStep.Malformed("既没有 tool，也没有 done，也没有 cannotDo")
        val args = obj["arguments"] as? JsonObject ?: JsonObject(emptyMap())
        return AgentStep.UseTool(name, args)
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this.isString) this.content else this.content

    companion object {
        /** 工具清单的文本。放进静态前缀里，整场会话不变。 */
        fun catalogueOf(tools: List<AgentTool>): String = buildString {
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
            appendLine("如果你判断这件事做不了、或者不该做，就输出：")
            appendLine("""{"cannotDo": "一句说清为什么的话"}""")
            appendLine("只有真的做了事（装上了、停用了、删掉了）才能说 done；只是「我觉得它应该已经有了」不算。")
            appendLine("除了这三种 JSON，不要输出任何别的东西。")
            appendLine("</tools>")
        }
    }
}
