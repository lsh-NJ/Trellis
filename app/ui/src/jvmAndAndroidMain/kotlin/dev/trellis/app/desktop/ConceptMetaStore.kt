package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 一个概念的补充元数据：定义 + 别名。
 *
 * [aliases] 只用于「正文里出现的别的写法 → 这个概念」的匹配（高亮、`[[…]]` 解析、检索），
 * 不参与显示；不会自动从 `[[原文=>概念名]]` 的原文里学习（防垃圾别名，见 docs/knowledge-base-draft.md §11）。
 */
internal data class ConceptMeta(
    val name: String,
    val definition: String,
    val aliases: List<String> = emptyList(),
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
            // 旧数据里可能有 M / ef / [] 这种「绿色满天飞」的垃圾概念，读的时候就挡掉。
            if (!isAcceptableConceptName(name)) continue
            val definition = (obj["definition"] as? JsonPrimitive)?.content.orEmpty().trim()
            val key = conceptIdentityKey(name)
            val aliases = (obj["aliases"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content }
                ?.map(::normalizeConceptName)
                ?.filter { isAcceptableConceptName(it) && conceptIdentityKey(it) != key }
                ?.distinctBy(::conceptIdentityKey)
                .orEmpty()
            // 同一个概念被 AI 抽成「Matryoshka 表示学习 / Matryoshka表示学习」两条时并成一条。
            val existing = result[key]
            result[key] = if (existing == null) {
                ConceptMeta(name, definition, aliases)
            } else {
                ConceptMeta(
                    existing.name,
                    existing.definition.ifBlank { definition },
                    (existing.aliases + aliases).distinctBy(::conceptIdentityKey),
                )
            }
        }
        result
    }.getOrElse { emptyMap() }

    /** 某条定义；没有/空定义返回 null。 */
    fun definition(name: String): String? = load()[conceptIdentityKey(name)]?.definition?.takeIf { it.isNotBlank() }

    /** 概念库里的全部身份（规范名 + 别名），用于把正文标注解析成规范名。 */
    fun identities(): List<ConceptIdentity> = load().values.map { ConceptIdentity(it.name, it.aliases) }

    /** 确保这些概念在库里有个条目（定义/别名可以为空）；已存在的不动。 */
    fun ensureConcepts(names: List<String>): Boolean {
        val targets = names.map(::normalizeConceptName)
            .filter(::isAcceptableConceptName)
            .distinctBy(::conceptIdentityKey)
        if (targets.isEmpty()) return true
        val current = load().toMutableMap()
        var changed = false
        for (name in targets) {
            val key = conceptIdentityKey(name)
            if (current.containsKey(key)) continue
            current[key] = ConceptMeta(normalizeConceptName(name), "")
            changed = true
        }
        return if (changed) write(current.values.toList()) else true
    }

    /** 写一条定义（空定义等价于删除）。返回是否真的写成功。 */
    fun saveDefinition(name: String, definition: String): Boolean {
        if (!isAcceptableConceptName(name)) return false
        val key = conceptIdentityKey(name)
        val current = load().toMutableMap()
        val text = definition.trim()
        if (text.isEmpty()) {
            current.remove(key)
        } else {
            current[key] = ConceptMeta(normalizeConceptName(name), text, current[key]?.aliases.orEmpty())
        }
        return write(current.values.toList())
    }

    /** 写一个概念的别名（空列表 = 清空）；概念不存在时先建条目。 */
    fun saveAliases(name: String, aliases: List<String>): Boolean {
        if (!isAcceptableConceptName(name)) return false
        val key = conceptIdentityKey(name)
        val current = load().toMutableMap()
        val existing = current[key]
        val normalized = aliases.map(::normalizeConceptName)
            .filter { isAcceptableConceptName(it) && conceptIdentityKey(it) != key }
            .distinctBy(::conceptIdentityKey)
        current[key] = ConceptMeta(
            existing?.name ?: normalizeConceptName(name),
            existing?.definition.orEmpty(),
            normalized,
        )
        return write(current.values.toList())
    }

    /** 合并别名：已有的保留，只追加新的（AI 抽出来的别名不能把用户删掉的又加回来）。 */
    fun mergeAliases(name: String, aliases: List<String>): Boolean {
        if (!isAcceptableConceptName(name) || aliases.isEmpty()) return false
        val key = conceptIdentityKey(name)
        val current = load().toMutableMap()
        val existing = current[key]
        val merged = (existing?.aliases.orEmpty() + aliases)
            .map(::normalizeConceptName)
            .filter { isAcceptableConceptName(it) && conceptIdentityKey(it) != key }
            .distinctBy(::conceptIdentityKey)
        current[key] = ConceptMeta(
            existing?.name ?: normalizeConceptName(name),
            existing?.definition.orEmpty(),
            merged,
        )
        return write(current.values.toList())
    }

    /** 批量补定义：只填还没有定义的，AI 不覆盖用户改过的文本。 */
    fun fillMissing(definitions: Map<String, String>): Boolean {
        if (definitions.isEmpty()) return true
        val current = load().toMutableMap()
        var changed = false
        for ((name, definition) in definitions) {
            if (!isAcceptableConceptName(name) || definition.isBlank()) continue
            val key = conceptIdentityKey(name)
            val existing = current[key]
            if (existing != null && existing.definition.isNotBlank()) continue
            current[key] = ConceptMeta(
                normalizeConceptName(name),
                definition.trim(),
                current[key]?.aliases.orEmpty(),
            )
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
                    for (item in items.sortedBy { conceptIdentityKey(it.name) }) {
                        add(
                            buildJsonObject {
                                put("name", item.name)
                                put("definition", item.definition)
                                if (item.aliases.isNotEmpty()) {
                                    put(
                                        "aliases",
                                        buildJsonArray { for (alias in item.aliases) add(JsonPrimitive(alias)) },
                                    )
                                }
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
