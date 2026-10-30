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
    val intent: AgentIntent,
    val text: String,
    val transcript: List<String> = emptyList(),
    val refused: Boolean = false,
    val ok: Boolean = true,
    /** 有歧义时摆给用户的选项（点了就等于把那句话发出去）。 */
    val choices: List<String> = emptyList(),
    /**
     * 这次是「意图有歧义、先反问」的结果（**不是**模型调 `ask_choices` 问出来的）。
     *
     * 两者的选项来源不同：歧义选项是意图名（点了会重新分类），模型问的对象是任务参数
     * （「每天几分钟」「落成待办/排成计划」），下一句必须**沿用上一轮的意图和工具**。
     * AppRuntime 靠这个标记决定要不要把意图留给下一轮。
     */
    val clarified: Boolean = false,
)

/**
 * 插件管理员。
 *
 * 和 [ToolAgent]（只管干活、不管该不该干）的区别：
 * 它是**长期角色**，每轮先判断「这次到底该不该动手、动哪一类手」，再决定走哪条路：
 *
 * - 聊天：流式回答，不碰工具；
 * - 造/改插件、管插件、调布局：带着工具跑循环，边干边把过程交给界面。
 *
 * 它自己不持有会话状态：历史由调用方保存并传进来，[ManagerPrompt] 负责把
 * 「静态前缀 / 动态状态 / 历史 / 任务模块」按顺序拼好 —— 这样前缀缓存才稳。
 *
 * 稳定工具集实验（P1）打开时，每轮都拿到全量工具，「聊天」只是流式优先：
 * 它若在聊天里点名工具，直接切工具循环，不再按工具名换意图
 * （换意图那条补丁是意图门控时代的兜底，P3 随旧路径一起删）。
 */
class ManagerAgent(
    private val llm: LlmClient,
    /**
     * 意图 → 工具 → 提示词，三件事在这里一次定完。
     * 不再把「整套工具 + 整段静态前缀」直接塞给模型：那样它总会挑一个
     * 看起来像在干活的工具，越界就这么来的。
     */
    private val router: ManagerRouter,
    /** `null` = 不设步数额度；给「只想让它看一眼」的场景留的硬闸。 */
    private val maxSteps: Int? = null,
    /**
     * 收尾前的最后一道检查。**按意图给**：只有「造/改插件」才该被要求
     * 「试过、装上了」；调布局有 arrange_cards，管理有 disable/remove，
     * 拿装插件的标准去卡它们，只会把已经做成的事判成没做。
     */
    private val beforeFinishing: (AgentIntent) -> String? = { null },
) {

    /** 老调用点：一份提示词 + 全套工具，工具仍按意图裁，但提示词不重建。 */
    constructor(
        llm: LlmClient,
        prompt: ManagerPrompt,
        tools: List<AgentTool>,
        maxSteps: Int? = null,
        beforeFinishing: (AgentIntent) -> String? = { null },
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
        /**
         * 上一轮留下的意图（它问完问题、等用户点选项的那一轮）。
         *
         * 用户点的选项往往是「每天 60 分钟」这种**没有意图关键词的短句**，
         * 重新分类会落到 Chat（零工具），于是模型想调 `create_learning_plan` 却调不到。
         * 这里只在「这句话自己判成 Chat、又不像寒暄」时沿用上一轮意图；
         * 用户换了话题（句子有新意图关键词）时照常重新分类。
         */
        forcedIntent: AgentIntent? = null,
    ): ManagerResult {
        // ① 意图（单选）② 歧义就停下先问 ③ 选工具 ④ 按这轮的意图拼提示词 ⑤ 发模型
        var decision = router.decide(request)
        if (forcedIntent != null && decision.intent == AgentIntent.Chat && !decision.ambiguous &&
            request.trim().length <= 30
        ) {
            onLog("判断：这是在接着上一轮说，继续按「" + labelOf(forcedIntent) + "」处理")
            decision = IntentDecision(forcedIntent)
        }
        if (decision.ambiguous) {
            onLog("判断：这句话有歧义，先问清楚")
            return ManagerResult(
                intent = decision.intent,
                text = decision.question,
                choices = decision.choices,
                clarified = true,
            )
        }
        val intent = decision.intent
        val tools = router.tools(intent)
        if (router.stableTools) {
            // 实验开关的状态要在过程里看得见：跑 A/B 时不用去翻环境变量。
            onLog("工具集：稳定全量（" + STABLE_TOOLS_ENV + "=1），" + tools.size + " 只")
        }
        val messages = router.prompt(intent).messages(state, request, history, intent)

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
                // 关键：Chat 意图手里一只工具都没有。如果它点名要的工具有明确的归属意图
                // （比如 create_learning_plan 属于「找学习资源」），就**换到那条路**再跑，
                // 而不是拿空工具箱让它连撞几次「没有这个工具」。
                // 真实事故：用户点选项「每天 60 分钟」被判成 Chat，模型想建计划却调不到，
                // 最后回一句「我这边工具里没有建学习计划的那只」。
                val wanted = wantedToolName(text)
                // 稳定工具集实验里 Chat 本来就带着全量工具，不存在「手里没工具」的问题：
                // 不按工具名换意图（那正是 P3 要删掉的补丁），直接在同一轮里跑。
                val fallback = if (router.stableTools) {
                    null
                } else {
                    wanted?.let { router.intentForTool(it) }?.takeIf { it != intent }
                }
                val runIntent = fallback ?: intent
                val runTools = if (fallback != null) router.tools(fallback) else tools
                val runMessages = if (fallback != null) {
                    router.prompt(fallback).messages(state, request, history, fallback)
                } else {
                    messages
                }
                if (fallback != null) {
                    onLog("判断：它想用 " + wanted + "，那是「" + labelOf(fallback) + "」的工具；换到那条路继续")
                } else {
                    onLog("判断：它想动手，切到工具模式")
                }
                val run = try {
                    ToolAgent(
                        llm = llm,
                        tools = runTools,
                        maxSteps = maxSteps,
                        beforeFinishing = { beforeFinishing(runIntent) },
                    ).run(runMessages, onLog)
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    val reason = t.message ?: (t::class.simpleName ?: "未知错误")
                    onLog("出错了：" + reason)
                    return ManagerResult(runIntent, "模型没回上来：" + reason, ok = false)
                }
                return ManagerResult(
                    intent = runIntent,
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

    /**
     * 模型这句话里想调用的工具名（JSON 协议或 DSML 方言都认）。
     *
     * 用途只有一个：Chat 意图下手无寸铁，但模型往往会「自己想起来」某个真实工具；
     * 拿到工具名就能找到它真正的归属意图，换到那条路去执行。
     */
    private fun wantedToolName(text: String): String? =
        DeepSeekMarkup.firstInvocation(text)?.name
            ?: toolNameInJson.find(text)?.groupValues?.get(1)

    private fun labelOf(intent: AgentIntent): String = when (intent) {
        AgentIntent.Chat -> "手"
        AgentIntent.CreatePlugin -> "插件（造或改）"
        AgentIntent.ManagePlugin -> "已装插件"
        AgentIntent.ArrangeUi -> "布局"
        AgentIntent.FindResource -> "学习资源"
        AgentIntent.PlanManage -> "学习计划"
    }

    private companion object {
        /** `{"tool": "create_learning_plan", ...}` 里的工具名。 */
        val toolNameInJson = Regex("\"tool\"\\s*:\\s*\"([a-z_]+)\"")
    }
}
