package dev.trellis.ai

/**
 * 「用哪个模型」的全部配置。
 *
 * 刻意只有三项：它必须能表达「OpenAI 兼容」这一整族服务 ——
 * DeepSeek、通义、Kimi、Ollama、vLLM 都是这个形状，换个 endpoint 就能跑。
 */
data class LlmConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
) {
    /** 打日志时用，**永远不要**把它换成带 key 的版本。 */
    fun describe(): String = model + " @ " + endpoint
}
