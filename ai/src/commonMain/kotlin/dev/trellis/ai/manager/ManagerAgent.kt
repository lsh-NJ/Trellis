package dev.trellis.ai.manager

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.DeepSeekMarkup
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
    val text: String,
    val transcript: List<String> = emptyList(),
    val refused: Boolean = false,
    val ok: Boolean = true,
)

/**
 * 插件管理员。
 *
 * 2026-11-07：意图门控删掉之后，它只有一种跑法 ——
 * **先按聊天跑（流式，用户马上能看到字），发现它在说「我先动手」再切到工具循环**。
 *
 * 为什么不一开始就跑工具循环：大多数请求就是聊天/答疑，工具循环的第一跳是
 * `llm.complete`（不流式），用户要等整段生成完才看到第一个字。先流式、再按需切，
 * 聊天体验和以前一样，动手的轮次多花一次「确认它想动手」的调用 —— 换取的是
 * **没有意图误判**：模型自己决定这一轮是聊还是干。
 *
 * 它自己不持有会话状态：历史由调用方保存并传进来，[ManagerPrompt] 负责把
 * 「静态前缀 / 动态状态 / 历史 / 这一句」按顺序拼好 —— 这样前缀缓存才稳。
 */
class ManagerAgent(
    private val llm: LlmClient,
    /**
     * 一套提示词 + 一套工具。不再按意图换：全量工具、同一份静态前缀，
     * 选哪只由模型在回复里自己决定。
     */
    private val router: ManagerRouter,
    /** `null` = 不设步数额度；给「只想让它看一眼」的场景留的硬闸。 */
    private val maxSteps: Int? = null,
    /**
     * 交活前的最后一道检查。返回一句话就是要它接着干，返回 null 就是放行。
     *
     * 现在不再按意图给：只有「这一轮碰过插件工具」才该被要求「试过、装上了」，
     * 这件事由 PluginTools 自己记账（见 AppRuntime 的接线）。
     */
    private val beforeFinishing: (String) -> String? = { null },
) {

    /** 老调用点：一份提示词 + 全套工具。 */
    constructor(
        llm: LlmClient,
        prompt: ManagerPrompt,
        tools: List<AgentTool>,
        maxSteps: Int? = null,
        beforeFinishing: (String) -> String? = { null },
    ) : this(llm, ManagerRouter(prompt, tools), maxSteps, beforeFinishing)

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
        onTrace: (dev.trellis.ai.tool.ToolTrace) -> Unit = {},
        /** 聊天这步模型的原生思考（DeepSeek `reasoning_content`）；没有就不调。 */
        onThinking: (String) -> Unit = {},
        /**
         * 思考过程的**增量**（流式分片）。给了这个就不再把整段交给 [onThinking]：
         * 界面可以拿它边想边刷新同一行，而不是等模型想完才突然出现一大段。
         */
        onThinkingDelta: ((String) -> Unit)? = null,
    ): ManagerResult {
        val messages = router.prompt.messages(state, request, history)

        // ① 先按聊天跑：流式吐字，用户不用等整段生成。
        val streamed = StringBuilder()
        val reasoning = StringBuilder()
        val text = try {
            llm.stream(
                messages,
                onDelta = { delta ->
                    streamed.append(delta)
                    onDelta(delta)
                },
                onReasoning = { piece ->
                    reasoning.append(piece)
                    if (piece.isNotEmpty()) onThinkingDelta?.invoke(piece)
                },
            )
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val reason = t.message ?: (t::class.simpleName ?: "未知错误")
            onLog("出错了：" + reason)
            return ManagerResult("模型没回上来：" + reason, ok = false)
        }

        // 模型自己的思考过程原样交给界面；不裁剪、不加工，没有就不显示。
        // 有增量回调时，界面已经边想边画了，这里不再重复发一遍整段。
        if (onThinkingDelta == null) {
            reasoning.toString().trim().takeIf { it.isNotEmpty() }?.let(onThinking)
        }

        // ② 它想动手：把同一份上下文交给工具循环真去干。
        // 这不是「它在瞎说」——静态前缀里列了全套工具，它照着协议点名工具是正常行为。
        // 宁可多花一次调用，也别让「我先去建计划」停在界面上没下文。
        if (!looksLikeToolCall(text)) return ManagerResult(unwrapReply(text))

        onLog("判断：它要动手，切到工具模式")
        val run = try {
            ToolAgent(
                llm = llm,
                tools = router.tools,
                maxSteps = maxSteps,
                beforeFinishing = beforeFinishing,
            ).run(messages, onLog, onTrace, onThinkingDelta)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            val reason = t.message ?: (t::class.simpleName ?: "未知错误")
            onLog("出错了：" + reason)
            return ManagerResult("模型没回上来：" + reason, ok = false)
        }

        return ManagerResult(
            text = unwrapReply(run.message),
            transcript = run.transcript,
            refused = run.refused,
            ok = run.ok,
        )
    }

    /**
     * 把包着协议外壳的答复拆出来。
     *
     * 静态前缀里有工具协议，模型有时会直接回 `{"done": true, "message": "…"}`，
     * 甚至只回一个 JSON 字符串字面量。这在按协议说话，不是坏输出 —— 但把 JSON
     * 原样摊到界面上，用户看到的是转义过的 \n 和一整行大括号。这里只剥壳，不改内容；
     * 剥不出来就原样返回。
     */
    private fun unwrapReply(raw: String): String {
        val cleaned = DeepSeekMarkup.strip(raw).ifBlank { raw }
        val body = cleaned.trim()
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
                cleaned
            } else if (body.startsWith("\"")) {
                val text = json.parseToJsonElement(body).jsonPrimitive.content
                if (text.isNotBlank()) text else cleaned
            } else {
                cleaned
            }
        }.getOrDefault(cleaned)
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
        DeepSeekMarkup.contains(text) || Regex("\"tool\"\\s*:").containsMatchIn(text)
}
