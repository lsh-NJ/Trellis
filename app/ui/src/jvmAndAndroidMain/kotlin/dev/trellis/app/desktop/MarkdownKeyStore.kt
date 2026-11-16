package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Markdown 快捷键的用户覆盖，落盘在 `<home>/markdown.json`。
 *
 * 为什么单独一个文件、不塞进 `ui.json`：`ui.json` 是「界面长什么样」的偏好，
 * 键位是「编辑器怎么用」的偏好，坏掉一份不该把另一份带走；而且键位只存**与默认不同**的项，
 * 文件小、好排查——用户嫌按键不对时打开就能看。
 *
 * 容错口径和宿主其它配置文件一致：文件不存在/坏 JSON/BOM/未知命令/非法键位，
 * 一律「当作没改过」，不挡启动、不影响其它项。键位是否与别的命令冲突不在这里判，
 * 交给 [effectiveMarkdownBindings]（冲突时忽略覆盖）和设置页（当场提示）。
 */
internal class MarkdownKeyStore(home: File) {
    private val file = File(home, "markdown.json")

    /** 读用户覆盖；commandId → 平台无关 label（空串 = 明确解绑）。 */
    fun load(): Map<String, String> {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF") ?: return emptyMap()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        val bindings = root["bindings"] as? JsonObject ?: return emptyMap()
        return bindings.entries.mapNotNull { (commandId, value) ->
            val label = value.jsonPrimitive.contentOrNull ?: return@mapNotNull null
            sanitize(commandId, label)
        }.toMap()
    }

    /** 写用户覆盖；写之前逐项过一遍 [sanitize]，坏项直接丢弃而不是让整份文件报废。 */
    fun save(bindings: Map<String, String>) {
        val clean = bindings.mapNotNull { (commandId, label) -> sanitize(commandId, label) }
        val root = buildJsonObject {
            put(
                "bindings",
                buildJsonObject {
                    for ((commandId, label) in clean) put(commandId, label)
                },
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun sanitize(commandId: String, raw: String): Pair<String, String>? {
        if (markdownCommand(commandId) == null) return null
        val label = raw.trim()
        if (label.isEmpty()) return commandId to ""
        val normalized = normalizeMarkdownShortcut(label) ?: return null
        return commandId to normalized
    }
}
