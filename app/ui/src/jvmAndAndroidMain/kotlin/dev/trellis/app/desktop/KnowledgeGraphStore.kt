package dev.trellis.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 知识网络的两个持久化文件（设计 §7）：
 *
 * - `<home>/knowledge/graph.user.json`：用户策展（分类树、归属、确认过的关系、墓碑），**权威**；
 * - `<home>/knowledge/graph.cache.json`：AI 建议与布局缓存，**可删**，丢了下次重算。
 *
 * 写入策略沿用一期 `ConceptMetaStore`：去 BOM 读、临时文件 + rename 写、写前留 `.bak`；
 * 文件读坏时改名成 `.bad-<ms>` 留档、程序按空数据继续（设计 §7.2）。
 * 不认识的顶层字段原样保留，避免新版写回旧版时抹掉别人加的东西。
 *
 * 这一层不做校验、不建树——只负责「文件 ⇄ 内存 DTO」。建树/校验在 `KnowledgeGraphBuild`，
 * 分类增删改在 `KnowledgeGraphTaxonomy`。
 */
internal class KnowledgeGraphStore(
    private val home: File,
    private val clock: TrellisClock,
) {
    private val knowledgeDir: File = File(home, "knowledge")

    val userFile: File = File(knowledgeDir, "graph.user.json")
    val cacheFile: File = File(knowledgeDir, "graph.cache.json")

    /** 上次 [loadUser] 的失败说明（没失败=null）；UI 可拿去显示诊断。 */
    var userLoadError: String? = null
        private set

    /** 上次 [loadCache] 的失败说明；缓存坏不阻塞使用。 */
    var cacheLoadError: String? = null
        private set

    private val json = Json { prettyPrint = true }

    /** 上次读到的未知顶层字段；保存时补回，保证新版不吞其它版本写的字段。 */
    private var userExtras: JsonObject = JsonObject(emptyMap())
    private var cacheExtras: JsonObject = JsonObject(emptyMap())

    private var categorySeq = 0

    // ---- user.json ----

    fun loadUser(): GraphUserData {
        userLoadError = null
        userExtras = JsonObject(emptyMap())
        val text = readTextOrNull(userFile) ?: return GraphUserData.Empty
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            userExtras = JsonObject(root.filterKeys { it !in USER_TOP_LEVEL_KEYS })
            GraphUserData(
                version = root.intValue("version") ?: 2,
                updatedAt = root.longValue("updatedAt") ?: 0L,
                categories = parseCategories(root),
                assignments = parseStringMap(root.objectValue("assignments")),
                relations = parseUserRelations(root),
                removed = parseRemovals(root.objectValue("removed")),
                mergedInto = parseStringMap(root.objectValue("mergedInto")),
            )
        } catch (error: Exception) {
            userLoadError = quarantine(userFile, error)
            GraphUserData.Empty
        }
    }

    fun saveUser(data: GraphUserData): Boolean {
        val encoded = encodeUser(data)
        return writeAtomic(userFile, encodeToJson(mergeExtras(encoded, userExtras)))
    }

    // ---- cache.json ----

    fun loadCache(): GraphAiCache {
        cacheLoadError = null
        cacheExtras = JsonObject(emptyMap())
        val text = readTextOrNull(cacheFile) ?: return GraphAiCache.Empty
        return try {
            val root = json.parseToJsonElement(text).jsonObject
            cacheExtras = JsonObject(root.filterKeys { it !in CACHE_TOP_LEVEL_KEYS })
            GraphAiCache(
                version = root.intValue("version") ?: 2,
                promptVersion = root.intValue("promptVersion") ?: 0,
                model = root.stringValue("model").orEmpty(),
                updatedAt = root.longValue("updatedAt") ?: 0L,
                noteSuggestions = parseNoteSuggestions(root.objectValue("noteSuggestions")),
                globalCategories = parseGlobalCategories(root.objectValue("globalCategories")),
                globalRelations = parseGlobalRelations(root.objectValue("globalRelations")),
                layout = parseLayout(root.objectValue("layout")),
            )
        } catch (error: Exception) {
            cacheLoadError = quarantine(cacheFile, error)
            GraphAiCache.Empty
        }
    }

    fun saveCache(cache: GraphAiCache): Boolean {
        val encoded = encodeCache(cache)
        return writeAtomic(cacheFile, encodeToJson(mergeExtras(encoded, cacheExtras)))
    }

    /** 只读布局缓存；缓存文件不存在/坏 → null（布局会冷算一遍）。 */
    fun loadLayout(): LayoutCache? = loadCache().layout

    /** 只替换缓存文件里的 layout 段，不动 AI 建议。 */
    fun saveLayout(layout: LayoutCache): Boolean {
        val cache = loadCache()
        return saveCache(cache.copy(layout = layout))
    }

    /**
     * 生成一个新的分类 id（`c_` + 8 位十六进制，设计 §7.5）。
     *
     * 只保证「形状」，不查重——建分类时 [KnowledgeGraphTaxonomy.createCategory] 会重试到不冲突；
     * 这里用时钟 + 自增计数，测试里用固定时钟也能得到稳定值。
     */
    fun newCategoryId(): String {
        categorySeq++
        val seed = clock.nowMs() * 31L + categorySeq
        val hex = graphHashText(fnv1a64("category:$seed:$categorySeq")).removePrefix(GRAPH_HASH_PREFIX)
        return "c_" + hex.take(8)
    }

    // ---- 编码 ----

    private fun encodeUser(data: GraphUserData): JsonObject = buildJsonObject {
        put("version", data.version)
        put("updatedAt", data.updatedAt)
        put("categories", buildJsonArray { data.categories.forEach { add(encodeCategory(it)) } })
        put("assignments", buildJsonObject {
            data.assignments.toSortedMap().forEach { (concept, categoryId) -> put(concept, categoryId) }
        })
        put("relations", buildJsonArray { data.relations.forEach { add(encodeUserRelation(it)) } })
        put("removed", encodeRemovals(data.removed))
        put("mergedInto", buildJsonObject {
            data.mergedInto.toSortedMap().forEach { (from, to) -> put(from, to) }
        })
    }

    private fun encodeCategory(category: GraphCategory): JsonObject = buildJsonObject {
        put("id", category.id)
        put("name", category.name)
        putNullable("parentId", category.parentId)
        put("order", category.order)
        put("origin", category.origin.name)
        putNullable("description", category.description)
        put("confirmed", category.confirmed)
    }

    private fun encodeUserRelation(relation: GraphUserRelation): JsonObject = buildJsonObject {
        put("from", relation.from)
        put("to", relation.to)
        put("kind", relation.kind.name)
        put("confidence", relation.confidence)
        putNullable("reason", relation.reason)
        relation.createdAt?.let { put("createdAt", it) }
    }

    private fun encodeRemovals(removed: GraphRemovals): JsonObject = buildJsonObject {
        put("relations", buildJsonArray {
            removed.relations.forEach { tombstone ->
                add(buildJsonObject {
                    put("from", tombstone.from)
                    put("to", tombstone.to)
                    put("kind", tombstone.kind.name)
                })
            }
        })
        put("assignments", buildJsonArray {
            removed.assignments.forEach { tombstone ->
                add(buildJsonObject {
                    put("concept", tombstone.concept)
                    put("categoryPath", buildJsonArray { tombstone.categoryPath.forEach { add(it) } })
                })
            }
        })
        put("categories", buildJsonArray {
            removed.categories.forEach { tombstone ->
                add(buildJsonObject {
                    put("id", tombstone.id)
                    put("path", buildJsonArray { tombstone.path.forEach { add(it) } })
                    put("at", tombstone.at)
                })
            }
        })
        put("suggestions", buildJsonArray {
            removed.suggestions.forEach { tombstone ->
                add(buildJsonObject {
                    put("concept", tombstone.concept)
                    put("fingerprint", tombstone.fingerprint)
                })
            }
        })
    }

    private fun encodeCache(cache: GraphAiCache): JsonObject = buildJsonObject {
        put("version", cache.version)
        put("promptVersion", cache.promptVersion)
        put("model", cache.model)
        put("updatedAt", cache.updatedAt)
        put("noteSuggestions", buildJsonObject {
            cache.noteSuggestions.toSortedMap().forEach { (noteId, suggestions) ->
                put(noteId, encodeNoteSuggestions(suggestions))
            }
        })
        put("globalCategories", encodeGlobalCategories(cache.globalCategories))
        put("globalRelations", encodeGlobalRelations(cache.globalRelations))
        cache.layout?.let { put("layout", encodeLayout(it)) }
    }

    private fun encodeNoteSuggestions(suggestions: AiNoteSuggestions): JsonObject = buildJsonObject {
        put("bodyHash", suggestions.bodyHash)
        put("categories", buildJsonArray {
            suggestions.categories.forEach { add(encodeCategorySuggestion(it)) }
        })
        put("relations", buildJsonArray {
            suggestions.relations.forEach { add(encodeRelationSuggestion(it)) }
        })
    }

    private fun encodeCategorySuggestion(suggestion: AiCategorySuggestion): JsonObject = buildJsonObject {
        put("name", suggestion.name)
        put("categoryPath", buildJsonArray { suggestion.categoryPath.forEach { add(it) } })
        putNullable("reason", suggestion.reason)
        put("createdAt", suggestion.createdAt)
    }

    private fun encodeGlobalCategories(categories: AiGlobalCategories): JsonObject = buildJsonObject {
        put("conceptSetHash", categories.conceptSetHash)
        put("categories", buildJsonArray {
            categories.categories.forEach { suggestion ->
                add(buildJsonObject {
                    put("concept", suggestion.concept)
                    put("categoryPath", buildJsonArray { suggestion.categoryPath.forEach { add(it) } })
                    putNullable("reason", suggestion.reason)
                    put("batch", suggestion.batch)
                    put("createdAt", suggestion.createdAt)
                })
            }
        })
        put("failed", buildJsonArray {
            categories.failed.forEach { failure ->
                add(buildJsonObject {
                    put("concept", failure.concept)
                    put("reason", failure.reason)
                })
            }
        })
    }

    private fun encodeGlobalRelations(relations: AiGlobalRelations): JsonObject = buildJsonObject {
        put("conceptSetHash", relations.conceptSetHash)
        put("relations", buildJsonArray { relations.relations.forEach { add(encodeRelationSuggestion(it)) } })
    }

    private fun encodeRelationSuggestion(suggestion: AiRelationSuggestion): JsonObject = buildJsonObject {
        put("from", suggestion.from)
        put("to", suggestion.to)
        put("kind", suggestion.kind.name)
        put("confidence", suggestion.confidence)
        putNullable("reason", suggestion.reason)
        putNullable("evidence", suggestion.evidence)
        put("createdAt", suggestion.createdAt)
    }

    private fun encodeLayout(layout: LayoutCache): JsonObject = buildJsonObject {
        put("fingerprint", graphHashText(layout.fingerprint))
        put("rootOrder", buildJsonArray { layout.rootOrder.forEach { add(it) } })
        put("anchors", buildJsonObject {
            layout.anchors.toSortedMap().forEach { (key, point) ->
                put(key, buildJsonObject {
                    put("x", point.x)
                    put("y", point.y)
                })
            }
        })
    }

    // ---- 解码 ----

    private fun parseCategories(root: JsonObject): List<GraphCategory> =
        root.arrayValue("categories").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.stringValue("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = entry.stringValue("name")?.takeIf { it.isNotBlank() } ?: id
            GraphCategory(
                id = id,
                name = name,
                parentId = entry.stringValue("parentId")?.takeIf { it.isNotBlank() },
                order = entry.intValue("order") ?: 0,
                origin = enumValue(entry.stringValue("origin"), NodeOrigin.USER),
                description = entry.stringValue("description")?.takeIf { it.isNotBlank() },
                confirmed = entry.boolValue("confirmed") ?: true,
            )
        }

    private fun parseUserRelations(root: JsonObject): List<GraphUserRelation> =
        root.arrayValue("relations").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val from = entry.stringValue("from")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val to = entry.stringValue("to")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            GraphUserRelation(
                from = from,
                to = to,
                kind = enumValue(entry.stringValue("kind"), GraphEdgeKind.RELATED),
                confidence = entry.floatValue("confidence") ?: 1f,
                reason = entry.stringValue("reason")?.takeIf { it.isNotBlank() },
                createdAt = entry.longValue("createdAt"),
            )
        }

    private fun parseRemovals(root: JsonObject?): GraphRemovals {
        if (root == null) return GraphRemovals.Empty
        val relations = root.arrayValue("relations").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val from = entry.stringValue("from") ?: return@mapNotNull null
            val to = entry.stringValue("to") ?: return@mapNotNull null
            RemovedRelation(from, to, enumValue(entry.stringValue("kind"), GraphEdgeKind.RELATED))
        }
        val assignments = root.arrayValue("assignments").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val concept = entry.stringValue("concept") ?: return@mapNotNull null
            RemovedAssignment(concept, entry.stringList("categoryPath"))
        }
        val categories = root.arrayValue("categories").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val id = entry.stringValue("id") ?: return@mapNotNull null
            RemovedCategory(id, entry.stringList("path"), entry.longValue("at") ?: 0L)
        }
        val suggestions = root.arrayValue("suggestions").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val concept = entry.stringValue("concept") ?: return@mapNotNull null
            RemovedSuggestion(concept, entry.stringValue("fingerprint").orEmpty())
        }
        return GraphRemovals(relations, assignments, categories, suggestions)
    }

    private fun parseNoteSuggestions(root: JsonObject?): Map<String, AiNoteSuggestions> {
        if (root == null) return emptyMap()
        return root.mapNotNull { (noteId, element) ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            noteId to AiNoteSuggestions(
                bodyHash = entry.stringValue("bodyHash").orEmpty(),
                categories = entry.arrayValue("categories").orEmpty().mapNotNull { item ->
                    val category = item as? JsonObject ?: return@mapNotNull null
                    AiCategorySuggestion(
                        name = category.stringValue("name")?.takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null,
                        categoryPath = category.stringList("categoryPath"),
                        reason = category.stringValue("reason")?.takeIf { it.isNotBlank() },
                        createdAt = category.longValue("createdAt") ?: 0L,
                    )
                },
                relations = parseRelationSuggestions(entry.arrayValue("relations")),
            )
        }.toMap()
    }

    private fun parseRelationSuggestions(array: JsonArray?): List<AiRelationSuggestion> =
        array.orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val from = entry.stringValue("from") ?: return@mapNotNull null
            val to = entry.stringValue("to") ?: return@mapNotNull null
            AiRelationSuggestion(
                from = from,
                to = to,
                kind = enumValue(entry.stringValue("kind"), GraphEdgeKind.RELATED),
                confidence = entry.floatValue("confidence") ?: 0.5f,
                reason = entry.stringValue("reason")?.takeIf { it.isNotBlank() },
                evidence = entry.stringValue("evidence")?.takeIf { it.isNotBlank() },
                createdAt = entry.longValue("createdAt") ?: 0L,
            )
        }

    private fun parseGlobalCategories(root: JsonObject?): AiGlobalCategories {
        if (root == null) return AiGlobalCategories()
        return AiGlobalCategories(
            conceptSetHash = root.stringValue("conceptSetHash").orEmpty(),
            categories = root.arrayValue("categories").orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val concept = entry.stringValue("concept")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AiGlobalCategorySuggestion(
                    concept = concept,
                    categoryPath = entry.stringList("categoryPath"),
                    reason = entry.stringValue("reason")?.takeIf { it.isNotBlank() },
                    batch = entry.intValue("batch") ?: 0,
                    createdAt = entry.longValue("createdAt") ?: 0L,
                )
            },
            failed = root.arrayValue("failed").orEmpty().mapNotNull { element ->
                val entry = element as? JsonObject ?: return@mapNotNull null
                val concept = entry.stringValue("concept") ?: return@mapNotNull null
                AiFailedCategory(concept, entry.stringValue("reason").orEmpty())
            },
        )
    }

    private fun parseGlobalRelations(root: JsonObject?): AiGlobalRelations {
        if (root == null) return AiGlobalRelations()
        return AiGlobalRelations(
            conceptSetHash = root.stringValue("conceptSetHash").orEmpty(),
            relations = parseRelationSuggestions(root.arrayValue("relations")),
        )
    }

    private fun parseLayout(root: JsonObject?): LayoutCache? {
        if (root == null) return null
        val fingerprint = graphHashFromText(root.stringValue("fingerprint").orEmpty()) ?: return null
        val anchors = root.objectValue("anchors").orEmpty().mapNotNull { (key, element) ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val x = entry.floatValue("x") ?: return@mapNotNull null
            val y = entry.floatValue("y") ?: return@mapNotNull null
            key to GraphPoint(x, y)
        }.toMap()
        return LayoutCache(fingerprint, root.stringList("rootOrder"), anchors)
    }

    // ---- 读写基建 ----

    private fun readTextOrNull(file: File): String? {
        if (!file.isFile) return null
        return try {
            file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        } catch (_: Exception) {
            null
        }
    }

    private fun writeAtomic(file: File, text: String): Boolean {
        val parent = file.parentFile ?: return false
        if (!parent.isDirectory && !parent.mkdirs()) return false
        return try {
            if (file.isFile && file.length() > 0L) {
                try {
                    file.copyTo(File(parent, file.name + ".bak"), overwrite = true)
                } catch (_: Exception) {
                    // 备份失败不阻塞主写入。
                }
            }
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(text, Charsets.UTF_8)
            if (file.exists() && !file.delete()) return false
            if (tmp.renameTo(file)) return true
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 坏文件改名留档；返回给人看的说明（设计 §7.2：不覆盖原文件）。 */
    private fun quarantine(file: File, error: Exception): String {
        val target = File(file.parentFile, file.name + ".bad-" + clock.nowMs())
        val moved = try {
            file.renameTo(target)
        } catch (_: Exception) {
            false
        }
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
        return if (moved) {
            "「${file.name}」解析失败（$reason），已留档为「${target.name}」，当前按空数据处理"
        } else {
            "「${file.name}」解析失败（$reason），当前按空数据处理"
        }
    }

    private fun encodeToJson(element: JsonObject): String = json.encodeToString(JsonElement.serializer(), element)

    private fun mergeExtras(encoded: JsonObject, extras: JsonObject): JsonObject {
        if (extras.isEmpty()) return encoded
        val merged = LinkedHashMap<String, JsonElement>(encoded)
        extras.forEach { (key, value) -> if (!merged.containsKey(key)) merged[key] = value }
        return JsonObject(merged)
    }

    private fun parseStringMap(root: JsonObject?): Map<String, String> {
        if (root == null) return emptyMap()
        return root.mapNotNull { (key, element) ->
            if (key.isBlank()) return@mapNotNull null
            val value = (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private companion object {
        val USER_TOP_LEVEL_KEYS = setOf(
            "version", "updatedAt", "categories", "assignments", "relations", "removed", "mergedInto",
        )
        val CACHE_TOP_LEVEL_KEYS = setOf(
            "version", "promptVersion", "model", "updatedAt", "noteSuggestions",
            "globalCategories", "globalRelations", "layout",
        )
    }
}

/** 一篇笔记的正文指纹（标题 + 正文）；正文一变，这篇的 AI 建议失效（设计 §7.4）。 */
internal fun noteBodyHash(note: KnowledgeNote): String =
    graphHashText(fnv1a64(note.title + "\n" + note.body))

// ---- JsonElement 小工具（与一期 ConceptMetaStore 的手工读写风格一致） ----

private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, JsonPrimitive(value))
}

private fun JsonObject?.stringValue(key: String): String? {
    val element = this?.get(key) as? JsonPrimitive ?: return null
    return element.contentOrNull
}

private fun JsonObject?.intValue(key: String): Int? = stringValue(key)?.toIntOrNull()

private fun JsonObject?.longValue(key: String): Long? = stringValue(key)?.toLongOrNull()

private fun JsonObject?.floatValue(key: String): Float? = stringValue(key)?.toFloatOrNull()

private fun JsonObject?.boolValue(key: String): Boolean? = stringValue(key)?.toBooleanStrictOrNull()

private fun JsonObject?.objectValue(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.arrayValue(key: String): JsonArray? = this?.get(key) as? JsonArray

private fun JsonObject.stringList(key: String): List<String> =
    arrayValue(key).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { text -> text.isNotBlank() } }

private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return fallback
    return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: fallback
}
