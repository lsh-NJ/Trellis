package dev.trellis.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「粘贴一段东西，换回一份能用的模型配置」。
 *
 * 为什么需要它：手动建 `llm.json` 要记住三个字段名、路径、还不能带 BOM ——
 * 这是给作者用的流程，不是给用户用的。用户手里通常只有一串 key
 * （或者从服务商控制台复制的一小段 JSON），所以这里认三种形态：
 *
 * 1. 裸 key：`sk-...` → 配上默认的 DeepSeek 地址与模型；
 * 2. 一整段 JSON：`{"endpoint": "...", "apiKey": "...", "model": "..."}`
 *    （也认 `baseUrl` / `key` / `token` 这些别名，服务商控制台各写各的）；
 * 3. 一行三段：`https://... sk-... model-name`。
 *
 * 解析失败必须说清「差什么」，因为这段文字会原样显示在导入对话框里。
 */
data class LlmImportResult(
    val config: LlmConfig? = null,
    val error: String? = null,
) {
    val ok: Boolean get() = config != null
}

object LlmImport {

    /** 各家都给 OpenAI 兼容地址，所以「换 endpoint」就是换服务商。 */
    val PRESETS: List<LlmPreset> = listOf(
        LlmPreset("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat"),
        LlmPreset("OpenAI", "https://api.openai.com/v1/chat/completions", "gpt-4o-mini"),
        LlmPreset("Kimi", "https://api.moonshot.cn/v1/chat/completions", "moonshot-v1-8k"),
        LlmPreset("本地 Ollama", "http://localhost:11434/v1/chat/completions", "qwen2.5:7b"),
    )

    /**
     * [endpoint] / [model] 是「只粘了 key」时用的默认值（界面上选中的那个服务商）。
     */
    fun parse(
        raw: String,
        endpoint: String = LlmConfigLoader.DEFAULT_ENDPOINT,
        model: String = LlmConfigLoader.DEFAULT_MODEL,
    ): LlmImportResult {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) return fail("先粘贴 API key，或者一整段 JSON 配置。")

        if (text.startsWith("{")) return fromJson(text, endpoint, model)

        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.first()
        if (head.startsWith("http://") || head.startsWith("https://")) {
            return fromLine(lines.joinToString(" "), endpoint, model)
        }
        return fromKey(text, endpoint, model)
    }

    // ---------------------------------------------------------------- 三种形态

    private fun fromJson(text: String, fallbackEndpoint: String, fallbackModel: String): LlmImportResult {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return fail("这段不是合法的 JSON：检查一下引号和逗号。")
        fun pick(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
            root[name]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
        }
        val key = pick("apiKey", "api_key", "key", "token")
            ?: return fail("JSON 里没有 apiKey 字段。")
        val endpoint = pick("endpoint", "baseUrl", "base_url", "url") ?: fallbackEndpoint
        val model = pick("model") ?: fallbackModel
        return build(endpoint, key, model)
    }

    /** `https://... sk-xxx [model]`：从服务商文档里复制的那一行。 */
    private fun fromLine(line: String, fallbackEndpoint: String, fallbackModel: String): LlmImportResult {
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        val endpoint = parts.getOrNull(0) ?: fallbackEndpoint
        val key = parts.getOrNull(1)
            ?: return fail("只有地址、没有 key：把 key 一起粘进来（地址 和 key 之间留个空格）。")
        val model = parts.getOrNull(2) ?: fallbackModel
        return build(endpoint, key, model)
    }

    private fun fromKey(key: String, endpoint: String, model: String): LlmImportResult =
        build(endpoint, key.trim(), model)

    // ---------------------------------------------------------------- 校验

    private fun build(endpoint: String, key: String, model: String): LlmImportResult {
        val normalized = normalizeEndpoint(endpoint)
            ?: return fail("地址要以 http:// 或 https:// 开头，现在拿到的是「" + endpoint + "」。")
        if (key.any { it.isWhitespace() }) {
            return fail("key 里不能有空格或换行 —— 复制的时候可能多带了一段。")
        }
        if (key.length < 8) {
            return fail("key 太短了（" + key.length + " 个字符），看起来不像一个完整的 key。")
        }
        if (model.isBlank()) return fail("model 不能为空。")
        return LlmImportResult(LlmConfig(normalized, key, model.trim()))
    }

    /**
     * 把用户粘的地址补成能直接 POST 的完整地址。
     *
     * 服务商文档里三种写法都有：带 `/chat/completions`、只到 `/v1`、只有一个域名。
     * 这里统一补齐，免得「导入成功但每次请求都 404」——那种失败最难查。
     */
    internal fun normalizeEndpoint(raw: String): String? {
        var endpoint = raw.trim().trimEnd('/')
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) return null
        if (endpoint.endsWith("/chat/completions")) return endpoint
        val afterScheme = endpoint.substringAfter("://")
        val hasPath = afterScheme.contains('/')
        return if (hasPath) endpoint + "/chat/completions" else endpoint + "/v1/chat/completions"
    }

    private fun fail(message: String): LlmImportResult = LlmImportResult(error = message)

    private val Json = Json { ignoreUnknownKeys = true }
}

/** 一个可选的服务商：界面上点一下就把地址和模型填好，用户只需要给 key。 */
data class LlmPreset(val name: String, val endpoint: String, val model: String)
