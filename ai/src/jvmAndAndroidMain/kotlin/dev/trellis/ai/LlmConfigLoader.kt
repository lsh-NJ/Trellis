package dev.trellis.ai

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    /**
     * 只读 `llm.json`，不看环境变量。
     *
     * 界面要区分「文件里存的是哪一份」和「现在生效的是哪一份」：两者不一致时
     * （比如用户设过 TRELLIS_LLM_KEY），导入新配置会「导入成功但不生效」——
     * 那是最难查的一类失败，所以必须能分别读到。
     */
    fun fromFile(home: File = defaultHome()): LlmConfig? {
        val fromFile = readFile(File(home, "llm.json")) ?: return null
        val key = fromFile.key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return LlmConfig(
            endpoint = fromFile.endpoint?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ENDPOINT,
            apiKey = key,
            model = fromFile.model?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL,
        )
    }

    /** 现在生效的那份配置是从哪来的。环境变量优先于文件。 */
    fun source(home: File = defaultHome()): String = when {
        !System.getenv(ENV_KEY).isNullOrBlank() -> "环境变量"
        File(home, "llm.json").isFile -> "llm.json"
        else -> "未配置"
    }

    /**
     * 把一份配置写进 `llm.json`。**不写 BOM**（写了 JSON 解析器会认不出，整份配置静默失效）。
     *
     * key 是密码：能设权限的平台把它设成「只有自己能读写」。
     */
    fun save(home: File, config: LlmConfig): File {
        val file = File(home, "llm.json")
        file.parentFile?.mkdirs()
        val root = buildJsonObject {
            put("endpoint", config.endpoint)
            put("apiKey", config.apiKey)
            put("model", config.model)
        }
        file.writeText(root.toString(), Charsets.UTF_8)
        runCatching {
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
        }
        return file
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
            // Windows 上用记事本 / Set-Content 存出来常常带 BOM。
            // Kotlin 的 readText 不会帮你剥掉，所以这里自己剥：
            // 不剥的话 Json 会以为第一个字符是垃圾，整个配置静默变成「没配」。
            val text = file.readText().removePrefix("\uFEFF")
            val root = Json.parseToJsonElement(text).jsonObject
            FromFile(
                endpoint = root["endpoint"]?.jsonPrimitive?.content,
                key = root["apiKey"]?.jsonPrimitive?.content,
                model = root["model"]?.jsonPrimitive?.content,
            )
        }.getOrNull()
    }
}
