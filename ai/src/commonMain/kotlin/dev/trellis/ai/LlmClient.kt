package dev.trellis.ai

enum class LlmRole { System, User, Assistant }

data class LlmMessage(val role: LlmRole, val content: String) {
    fun toWire(): Map<String, String> = mapOf("role" to role.name.lowercase(), "content" to content)
}

/**
 * 一次「给我下一段话」的调用。
 *
 * 刻意做成最小的接口：没有流式、没有工具调用、没有 token 计数。
 * 现在只需要「发一段对话，拿一段文本」。等真的需要更多了再加 ——
 * 那时候才知道该加什么形状。
 */
interface LlmClient {
    suspend fun complete(messages: List<LlmMessage>): String
}
