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
     * 边生成边把新增的文本交给 [onDelta]，最后返回完整文本。
     *
     * [onDelta] 在各个分片上都会被调用，调用方自己负责拼接/刷新界面；
     * 返回值和所有分片拼起来必须一致（实现方保证）。
     */
    suspend fun stream(messages: List<LlmMessage>, onDelta: suspend (String) -> Unit): String {
        val full = complete(messages)
        onDelta(full)
        return full
    }
}
