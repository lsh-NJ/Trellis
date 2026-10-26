package dev.trellis.ai.manager

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolAgent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 管理员这一轮跑完的结论。
 *
 * [text] 是给用户看的那句话；[transcript] 是过程（调了哪个工具、返回什么），
 * 界面拿它画流水账；[refused] 表示它是**主动说不做**，不是没做成 —— 这两种
 * 在界面上该怎么说话不一样。
 */
data class ManagerResult(
    val intent: AgentIntent,
    val text: String,
    val transcript: List<String> = emptyList(),
    val refused: Boolean = false,
    val ok: Boolean = true,
)

/**
 * 插件管理员。
 *
 * 和 [dev.trellis.ai.PluginAgent]（一问一答一装）以及 [ToolAgent]（只管干活）的区别：
 * 它是**长期角色**，每轮先判断「这次到底该不该动手、动哪一类手」，再决定走哪条路：
 *
 * - 聊天：流式回答，不碰工具；
 * - 造/改插件、管插件、调布局：带着工具跑循环，边干边把过程交给界面。
 *
 * 它自己不持有会话状态：历史由调用方保存并传进来，[ManagerPrompt] 负责把
 * 「静态前缀 / 动态状态 / 历史 / 任务模块」按顺序拼好 —— 这样前缀缓存才稳。
 */
class ManagerAgent(
    private val llm: LlmClient,
    private val prompt: ManagerPrompt,
    private val tools: List<AgentTool>,
    /** `null` = 不设步数额度；给「只想让它看一眼」的场景留的硬闸。 */
    private val maxSteps: Int? = null,
    /**
     * 收尾前的最后一道检查。**按意图给**：只有「造/改插件」才该被要求
     * 「试过、装上了」；调布局有 arrange_cards，管理有 disable/remove，
     * 拿装插件的标准去卡它们，只会把已经做成的事判成没做。
     */
    private val beforeFinishing: (AgentIntent) -> String? = { null },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun respond(
        state: String,
        request: String,
        history: List<LlmMessage> = emptyList(),
        onDelta: suspend (String) -> Unit = {},
        onLog: (String) -> Unit = {},
    ): ManagerResult {
        val intent = prompt.intent(request)
        val messages = prompt.messages(state, request, history)

        if (intent == AgentIntent.Chat) {
            val streamed = StringBuilder()
            val text = try {
                llm.stream(messages, onDelta = { delta ->
                    streamed.append(delta)
                    onDelta(delta)
                })
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                val reason = t.message ?: (t::class.simpleName ?: "未知错误")
                onLog("出错了：" + reason)
                return ManagerResult(intent, "模型没回上来：" + reason, ok = false)
            }

            // 聊天分支没挂工具，但静态前缀里列了工具目录 —— 模型可能直接吐一个
            // 工具调用 JSON（"我先截个图" 后面跟 {"tool": ...}）。这不是它在瞎说，
            // 是它想动手：转给工具循环真去干，别让这句话停在界面上没下文。
            if (looksLikeToolCall(text)) {
                onLog("判断：它想动手，切到工具模式")
                val run = try {
                    ToolAgent(
                        llm = llm,
                        tools = tools,
                        maxSteps = maxSteps,
                        beforeFinishing = { beforeFinishing(intent) },
                    ).run(messages, onLog)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    val reason = t.message ?: (t::class.simpleName ?: "未知错误")
                    onLog("出错了：" + reason)
                    return ManagerResult(intent, "模型没回上来：" + reason, ok = false)
                }
                return ManagerResult(
                    intent = intent,
                    text = unwrapReply(run.message),
                    transcript = run.transcript,
                    refused = run.refused,
                    ok = run.ok,
                )
            }

            return ManagerResult(intent, unwrapReply(text))
        }

        onLog("判断：这次要动" + labelOf(intent))
        val run = try {
            ToolAgent(
                llm = llm,
                tools = tools,
                maxSteps = maxSteps,
                beforeFinishing = { beforeFinishing(intent) },
            ).run(messages, onLog)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val reason = t.message ?: (t::class.simpleName ?: "未知错误")
            onLog("出错了：" + reason)
            return ManagerResult(intent, "模型没回上来：" + reason, ok = false)
        }

        return ManagerResult(
            intent = intent,
            text = unwrapReply(run.message),
            transcript = run.transcript,
            refused = run.refused,
            ok = run.ok,
        )
    }

    /**
     * 把包着协议外壳的答复拆出来。
     *
     * 聊天分支没挂工具，但静态前缀里有工具协议，模型有时会直接回
     * `{"done": true, "message": "…"}`，甚至只回一个 JSON 字符串字面量。
     * 这在按协议说话，不是坏输出 —— 但把 JSON 原样摊到界面上，用户看到的是
     * 转义过的 \n 和一整行大括号。这里只剥壳，不改内容；剥不出来就原样返回。
     */
    private fun unwrapReply(raw: String): String {
        val body = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        if (body.isEmpty()) return raw
        return runCatching {
            if (body.startsWith("{")) {
                val obj = json.parseToJsonElement(body).jsonObject
                val refused = obj["cannotDo"]?.jsonPrimitive?.contentOrNull
                if (!refused.isNullOrBlank()) return refused
                val message = obj["message"]?.jsonPrimitive?.contentOrNull
                if (!message.isNullOrBlank() && obj.containsKey("done")) return message
                raw
            } else if (body.startsWith("\"")) {
                val text = json.parseToJsonElement(body).jsonPrimitive.content
                if (text.isNotBlank()) text else raw
            } else {
                raw
            }
        }.getOrDefault(raw)
    }

    /**
     * 流出来的文字里是不是藏着一个工具调用。
     *
     * 只认 `"tool":` 这个键：提示词要求它调工具时输出
     * {"tool": "名字", "arguments": {...}}，这个键不该出现在正常聊天里。
     * 宁可多切一次（工具循环第一轮看到的就是它自己那句话），也别让
     * 「我先截个图」就这么停在界面上。
     */
    private fun looksLikeToolCall(text: String): Boolean =
        Regex("\"tool\"\\s*:").containsMatchIn(text)

    private fun labelOf(intent: AgentIntent): String = when (intent) {
        AgentIntent.Chat -> "手"
        AgentIntent.CreatePlugin -> "插件（造或改）"
        AgentIntent.ManagePlugin -> "已装插件"
        AgentIntent.ArrangeUi -> "布局"
    }
}
