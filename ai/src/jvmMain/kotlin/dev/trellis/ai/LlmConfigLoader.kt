package dev.trellis.ai

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 去哪儿找 API key。
 *
 * 优先级：环境变量 > `~/.trellis/llm.json`。
 *
 * **key 永远不进仓库**：它是你的密码，不是项目的配置。
 * 所以这里没有任何默认 key，没配就是「没配」，界面会给出配置指引。
 */
object LlmConfigLoader {

    const val ENV_ENDPOINT = "TRELLIS_LLM_ENDPOINT"
    const val ENV_KEY = "TRELLIS_LLM_KEY"
    const val ENV_MODEL = "TRELLIS_LLM_MODEL"

    /** DeepSeek 的 OpenAI 兼容地址：国内可直连、便宜、中文好。 */
    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
    const val DEFAULT_MODEL = "deepseek-chat"

    private data class FromFile(val endpoint: String?, val key: String?, val model: String?)

    /**
     * 返回 null 表示**还没配 key**。
     *
     * 这不是错误：用户可能只是还没用这个功能。界面该显示配置指引，而不是弹一个红叉。
     */
    fun load(home: File = defaultHome()): LlmConfig? {
        val fromFile = readFile(File(home, "llm.json"))

        val endpoint = System.getenv(ENV_ENDPOINT) ?: fromFile?.endpoint ?: DEFAULT_ENDPOINT
        val model = System.getenv(ENV_MODEL) ?: fromFile?.model ?: DEFAULT_MODEL
        val key = System.getenv(ENV_KEY) ?: fromFile?.key

        if (key.isNullOrBlank()) return null
        return LlmConfig(endpoint, key, model)
    }

    /** 界面上的「怎么配」提示，必须和 [load] 的实现保持同步。 */
    fun setupHint(home: File = defaultHome()): String =
        "还没有配置模型 API key。两种方式任选一种：\n" +
            "1. 设置环境变量 " + ENV_KEY + "\n" +
            "   （可选：" + ENV_MODEL + " 默认 " + DEFAULT_MODEL +
            "，" + ENV_ENDPOINT + " 默认 " + DEFAULT_ENDPOINT + "）\n" +
            "2. 新建文件 " + File(home, "llm.json").absolutePath + "\n" +
            "   内容：{\"endpoint\":\"" + DEFAULT_ENDPOINT + "\",\"apiKey\":\"sk-...\",\"model\":\"" +
            DEFAULT_MODEL + "\"}"

    private fun defaultHome(): File = File(System.getProperty("user.home"), ".trellis")

    private fun readFile(file: File): FromFile? {
        if (!file.isFile) return null
        return runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            FromFile(
                endpoint = root["endpoint"]?.jsonPrimitive?.content,
                key = root["apiKey"]?.jsonPrimitive?.content,
                model = root["model"]?.jsonPrimitive?.content,
            )
        }.getOrNull()
    }
}
