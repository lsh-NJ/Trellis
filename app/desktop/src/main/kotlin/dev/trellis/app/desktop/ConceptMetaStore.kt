package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 一个概念的补充元数据：目前只有定义（别名、合并留到 P1）。 */
internal data class ConceptMeta(
    val name: String,
    val definition: String,
)

/**
 * 概念定义的文件存储：`<home>/knowledge/concepts.json`。
 *
 * 为什么定义不写进笔记 frontmatter：同一个概念散在多篇笔记里，定义只有一份；
 * 而且定义是用户可改的补充信息，笔记文件仍是纯 Markdown 的文本真相。
 * 这个文件丢了也不影响点亮与追溯——点亮只靠笔记 frontmatter 里的 `concepts:`。
 */
internal class ConceptMetaStore(home: File) {

    private val file = File(File(home, "knowledge"), "concepts.json")
    private val json = Json { prettyPrint = true }

    /** 写失败时给用户看的具体路径。 */
    val filePath: String get() = file.absolutePath

    /** 读取全部定义；文件坏了当没有（定义丢了不致命，界面照常能用）。 */
    fun load(): Map<String, ConceptMeta> = runCatching {
        if (!file.isFile) return emptyMap()
        val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        val array = json.parseToJsonElement(text).jsonObject["concepts"]?.jsonArray ?: return emptyMap()
        val result = LinkedHashMap<String, ConceptMeta>()
        for (element in array) {
            val obj = element.jsonObject
            val name = normalizeConceptName((obj["name"] as? JsonPrimitive)?.content.orEmpty())
            if (name.isEmpty()) continue
            val definition = (obj["definition"] as? JsonPrimitive)?.content.orEmpty().trim()
            result[conceptKey(name)] = ConceptMeta(name, definition)
        }
        result
    }.getOrElse { emptyMap() }

    /** 某条定义；没有/空定义返回 null。 */
    fun definition(name: String): String? = load()[conceptKey(name)]?.definition?.takeIf { it.isNotBlank() }

    /** 写一条定义（空定义等价于删除）。返回是否真的写成功。 */
    fun saveDefinition(name: String, definition: String): Boolean {
        val key = conceptKey(name)
        if (key.isEmpty()) return false
        val current = load().toMutableMap()
        val text = definition.trim()
        if (text.isEmpty()) {
            current.remove(key)
        } else {
            current[key] = ConceptMeta(normalizeConceptName(name), text)
        }
        return write(current.values.toList())
    }

    /** 批量补定义：只填还没有定义的，AI 不覆盖用户改过的文本。 */
    fun fillMissing(definitions: Map<String, String>): Boolean {
        if (definitions.isEmpty()) return true
        val current = load().toMutableMap()
        var changed = false
        for ((name, definition) in definitions) {
            val key = conceptKey(name)
            if (key.isEmpty() || definition.isBlank()) continue
            val existing = current[key]
            if (existing != null && existing.definition.isNotBlank()) continue
            current[key] = ConceptMeta(normalizeConceptName(name), definition.trim())
            changed = true
        }
        return if (changed) write(current.values.toList()) else true
    }

    private fun write(items: List<ConceptMeta>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val root = buildJsonObject {
            put(
                "concepts",
                buildJsonArray {
                    for (item in items.sortedBy { conceptKey(it.name) }) {
                        add(
                            buildJsonObject {
                                put("name", item.name)
                                put("definition", item.definition)
                            },
                        )
                    }
                },
            )
        }
        val text = root.toString()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text, Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.delete()
            file.writeText(text, Charsets.UTF_8)
        }
        true
    }.getOrDefault(false)
}
