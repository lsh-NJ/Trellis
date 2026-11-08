package dev.trellis.ai

enum class LlmRole { System, User, Assistant }

/**
 * 一条对话消息。
 *
 * [images] 是 data URL（`data:image/png;base64,...`）。用字符串而不是文件路径，
 * 是因为 ai 模块是 commonMain，读不了文件；拼请求时也只需要这个字符串。
 * 有图片时请求里的 content 会变成 `[{type:"text"},{type:"image_url"}]` 数组
 * （OpenAI 兼容格式），没有图片时仍是纯字符串 —— 老接口行为一个字都没变。
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String,
    val images: List<String> = emptyList(),
) {
    fun toWire(): Map<String, String> = mapOf("role" to role.name.lowercase(), "content" to content)
}

/**
 * 模型的一次完整回复：正文 + 可选的推理过程。
 *
 * 开了思考的模型（DeepSeek 的 `reasoning_content`）会把思维链单独放在
 * `reasoning_content` / 流式 `delta.reasoning_content` 里，正文仍在 `content`。
 * 没有思考内容的实现把 [reasoning] 留 `null` 就行——界面上就不显示思考行。
 */
data class LlmReply(
    val content: String,
    val reasoning: String? = null,
)

/**
 * 一次「给我下一段话」的调用。
 *
 * 刻意做成最小的接口：没有原生工具调用、没有 token 计数。
 * 现在只需要「发一段对话，拿一段文本」；工具调用在 [dev.trellis.ai.tool.ToolAgent]
 * 里用纯 JSON 协议做，不依赖厂商函数调用。
 *
 * ### 流式
 *
 * [stream] 是后加的：对话界面要边生成边显示，不然用户盯着一个转圈等十几秒
 * 会以为卡死了。默认实现退化成「一次拿完再吐一次」，假模型和不需要流式的
 * 实现（测试里那些）一个字都不用改。
 */
interface LlmClient {
    suspend fun complete(messages: List<LlmMessage>): String

    /**
     * 同 [complete]，但把模型的推理过程（`reasoning_content`）一起带回来。
     *
     * 默认实现退化成「没有推理过程」，老实现一个字都不用改。
     */
    suspend fun completeWithReasoning(messages: List<LlmMessage>): LlmReply =
        LlmReply(complete(messages))

    /**
     * 边生成边把新增的文本交给 [onDelta]，最后返回完整文本。
     *
     * [onDelta] 在各个分片上都会被调用，调用方自己负责拼接/刷新界面；
     * 返回值和所有分片拼起来必须一致（实现方保证）。
     *
     * [onReasoning] 接模型的原生思考过程（流式 `delta.reasoning_content`）。
     * 没有思考内容的回复不会调它；默认实现先整段拿回来，再把 reasoning 和正文
     * 各交一次——假模型和不支持思考的实现因此不用改签名。
     */
    suspend fun stream(
        messages: List<LlmMessage>,
        onDelta: suspend (String) -> Unit,
        onReasoning: suspend (String) -> Unit = {},
        /** 工具循环要严格 JSON：流式下也开 `response_format: json_object`。对话页保持 false。 */
        jsonMode: Boolean = false,
    ): String {
        val reply = completeWithReasoning(messages)
        reply.reasoning?.takeIf { it.isNotBlank() }?.let { onReasoning(it) }
        onDelta(reply.content)
        return reply.content
    }
}
