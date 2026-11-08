package dev.trellis.ai

/**
 * 「用哪个模型」的全部配置。
 *
 * 它要能表达「OpenAI 兼容」这一整族服务 ——
 * DeepSeek、通义、Kimi、Ollama、vLLM 都是这个形状，换个 endpoint 就能跑。
 */
data class LlmConfig(
    val endpoint: String,
    val apiKey: String,
    val model: String,
    /**
     * 是否在请求体里打开模型的思考（DeepSeek 的 `thinking={"type":"enabled"}`）。
     *
     * `null` = 自动：DeepSeek 的地址默认打开，其它兼容服务默认不发这个字段
     * （不认识的字段可能被它们当成 400）。`llm.json` 里可以显式写 true/false 覆盖。
     */
    val thinking: Boolean? = null,
) {
    /** 打日志时用，**永远不要**把它换成带 key 的版本。 */
    fun describe(): String = model + " @ " + endpoint
}
