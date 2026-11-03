package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * 知识网络的 AI 建议（G1）：把模型输出变成**可确认的建议**，不是在图里直接改数据。
 *
 * 三条底线（设计 §6 / §7.2）：
 * 1. 建议只写 `graph.cache.json`；用户点「确认」才写 `graph.user.json`。
 * 2. 名字必须是输入集里的概念——模型编出来的名字一律丢，防止图里凭空长出节点。
 * 3. 用户忽略过的建议（墓碑）不再出现；正文/概念集变了，旧建议自然失效。
 */

/** 提示词版本：改提示词或输出契约时 +1，旧缓存会当作过期（设计 §7.3）。 */
internal const val GRAPH_PROMPT_VERSION: Int = 1

/** 建议的分类路径层数上限。UI 不再预制整体深度，但**单条建议**先收窄，避免模型写一长串。 */
internal const val MAX_SUGGESTION_PATH_DEPTH: Int = 2

/** 分类路径里每层的最长字数（超过就当模型跑偏，丢弃）。 */
internal const val MAX_SUGGESTION_SEGMENT_CHARS: Int = 12

internal const val MAX_NOTE_CATEGORY_SUGGESTIONS: Int = 12
internal const val MAX_NOTE_RELATION_SUGGESTIONS: Int = 6
internal const val MAX_GLOBAL_BATCH_CONCEPTS: Int = 40
internal const val MAX_GLOBAL_BATCHES: Int = 20
internal const val MAX_PENDING_CATEGORY_SUGGESTIONS: Int = 80
internal const val MAX_PENDING_RELATION_SUGGESTIONS: Int = 30
private const val MAX_REASON_CHARS = 60
private const val MAX_EVIDENCE_CHARS = 80

// ---------------------------------------------------------------------------
// 笔记内建议（搭 organizeNote 的便车，同一次模型调用）
// ---------------------------------------------------------------------------

/** 一篇笔记里解析出来的图建议；[dropped] 只用于测试与调试，不写进缓存。 */
internal data class NoteGraphSuggestions(
    val categories: List<AiCategorySuggestion> = emptyList(),
    val relations: List<AiRelationSuggestion> = emptyList(),
    val dropped: List<String> = emptyList(),
)

/**
 * 从整理笔记的那次模型输出里，顺手解析 `categories` / `relations` 两段。
 *
 * 只有 `concepts`/`fills` 的旧格式会得到空建议——这不是错误，说明模型没给图建议。
 */
internal fun parseNoteGraphSuggestions(
    raw: String,
    noteConceptNames: List<String>,
    body: String,
    now: Long,
): NoteGraphSuggestions {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return NoteGraphSuggestions()
    val root = runCatching {
        Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
    }.getOrNull() ?: return NoteGraphSuggestions()

    val names = noteConceptNames
        .map(::normalizeConceptName)
        .filter(::isAcceptableConceptName)
    val canonical = names.associateBy(::conceptIdentityKey)
    val dropped = ArrayList<String>()

    val categories = ArrayList<AiCategorySuggestion>()
    val seenCategories = HashSet<String>()
    for (item in root.arrayOrEmpty("categories")) {
        val obj = item as? JsonObject ?: continue
        val conceptName = obj.stringOrNull("concept").orEmpty()
        val key = conceptIdentityKey(conceptName)
        val display = canonical[key]
        if (display == null) {
            dropped += "分类：「$conceptName」不是本篇概念"
            continue
        }
        val path = parseSuggestionCategoryPath(obj.stringOrNull("categoryPath"))
        if (path == null) {
            dropped += "分类：「$display」路径不合法"
            continue
        }
        val fingerprint = conceptIdentityKey(display) + "|" + path.joinToString("/")
        if (!seenCategories.add(fingerprint)) continue
        categories += AiCategorySuggestion(
            name = display,
            categoryPath = path,
            reason = obj.stringOrNull("reason")?.trim()?.take(MAX_REASON_CHARS),
            createdAt = now,
        )
        if (categories.size >= MAX_NOTE_CATEGORY_SUGGESTIONS) break
    }

    val relations = ArrayList<AiRelationSuggestion>()
    val seenRelations = HashSet<String>()
    for (item in root.arrayOrEmpty("relations")) {
        val obj = item as? JsonObject ?: continue
        val from = canonical[conceptIdentityKey(obj.stringOrNull("from").orEmpty())]
        val to = canonical[conceptIdentityKey(obj.stringOrNull("to").orEmpty())]
        if (from == null || to == null) {
            dropped += "关系：端点不在本篇概念里"
            continue
        }
        if (conceptIdentityKey(from) == conceptIdentityKey(to)) {
            dropped += "关系：「$from」指向自己"
            continue
        }
        val kind = parseRelationKind(obj.stringOrNull("kind"))
        if (kind == null) {
            dropped += "关系：类型不认识"
            continue
        }
        val evidence = obj.stringOrNull("evidence")?.replace("\n", " ")?.trim()?.take(MAX_EVIDENCE_CHARS)
        if (!kind.isSymmetric()) {
            if (evidence.isNullOrEmpty()) {
                dropped += "关系：「$from → $to」缺少正文依据"
                continue
            }
            if (!body.contains(evidence)) {
                dropped += "关系：「$from → $to」依据不是正文原文"
                continue
            }
        } else if (!evidence.isNullOrEmpty() && !body.contains(evidence)) {
            // 相关关系可以有也可以没有依据；给了但与原文字面不符就当幻觉丢掉。
            dropped += "关系：「$from ↔ $to」依据不是正文原文"
            continue
        }
        val dedupe = conceptIdentityKey(from) + ">" + conceptIdentityKey(to) + ">" + kind.name
        if (!seenRelations.add(dedupe)) continue
        relations += AiRelationSuggestion(
            from = from,
            to = to,
            kind = kind,
            confidence = normalizeSuggestionConfidence(obj.numberOrNull("confidence"), kind),
            reason = obj.stringOrNull("reason")?.trim()?.take(MAX_REASON_CHARS),
            evidence = evidence?.takeIf { it.isNotEmpty() },
            createdAt = now,
        )
        if (relations.size >= MAX_NOTE_RELATION_SUGGESTIONS) break
    }

    return NoteGraphSuggestions(categories = categories, relations = relations, dropped = dropped)
}

/** 分类路径：接受 `大类/小类`、`大类 > 小类`、`大类｜小类`；最多 [MAX_SUGGESTION_PATH_DEPTH] 层。 */
internal fun parseSuggestionCategoryPath(raw: String?): List<String>? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    val segments = text
        .split('/', '>', '＞', '\\', '｜', '|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (segments.isEmpty() || segments.size > MAX_SUGGESTION_PATH_DEPTH) return null
    if (segments.any { it.length > MAX_SUGGESTION_SEGMENT_CHARS }) return null
    if (segments.any { it == "未分类" || it == "未归类" }) return null
    val normalized = segments.map(::normalizeCategoryName).filter { it.isNotEmpty() }
    return normalized.takeIf { it.isNotEmpty() }
}

private fun parseRelationKind(raw: String?): GraphEdgeKind? =
    when (raw?.trim()?.uppercase()) {
        "PREREQUISITE", "前置", "PREREQ" -> GraphEdgeKind.PREREQUISITE
        "APPLIES", "应用", "APPLY" -> GraphEdgeKind.APPLIES
        "RELATED", "相关" -> GraphEdgeKind.RELATED
        else -> null
    }

private fun GraphEdgeKind.isSymmetric(): Boolean = this == GraphEdgeKind.RELATED

/**
 * 置信度归一：模型可能给 1–5 或 0–1；统一到 0.4–0.9（建议不是事实，不追求高置信）。
 */
internal fun normalizeSuggestionConfidence(raw: Double?, kind: GraphEdgeKind): Float {
    val fallback = when (kind) {
        GraphEdgeKind.PREREQUISITE -> 0.7f
        GraphEdgeKind.APPLIES -> 0.6f
        GraphEdgeKind.RELATED -> 0.5f
        GraphEdgeKind.CO_OCCUR -> 0.5f
    }
    if (raw == null) return fallback
    val value = if (raw > 1.0) raw / 5.0 else raw
    return value.coerceIn(0.4, 0.9).toFloat()
}

// ---------------------------------------------------------------------------
// 全图归类（手动触发，按批要建议）
// ---------------------------------------------------------------------------

/** 全图归类的待归类概念分批；最多 [MAX_GLOBAL_BATCHES] 批。 */
internal fun globalCategoryBatches(
    conceptKeys: List<String>,
    batchSize: Int = MAX_GLOBAL_BATCH_CONCEPTS,
): List<List<String>> {
    if (conceptKeys.isEmpty()) return emptyList()
    val size = batchSize.coerceAtLeast(1)
    return conceptKeys
        .distinct()
        .chunked(size)
        .take(MAX_GLOBAL_BATCHES)
}

/** 不分批上限的总概念数（UI 提示用户「还有 N 个没归类」）。 */
internal fun globalCategoryCoveredCount(conceptCount: Int): Int =
    minOf(conceptCount, MAX_GLOBAL_BATCHES * MAX_GLOBAL_BATCH_CONCEPTS)

internal fun buildGlobalCategoryMessages(
    concepts: List<Pair<String, String>>,
    existingPaths: List<String>,
    batchIndex: Int,
    batchCount: Int,
): List<LlmMessage> {
    val system = "你是个人知识库的分类整理助手。用户会给你一批还没归类的概念（key 与名字）" +
        "和现有分类路径；请给每个概念挑一个最合适的分类，只输出 JSON 对象。" +
        "规则：①优先挂到「现有分类」里；确实没有合适的，可以给一条新路径（最多 " +
        MAX_SUGGESTION_PATH_DEPTH + " 层，每层不超过 " + MAX_SUGGESTION_SEGMENT_CHARS + " 个字），" +
        "不要用「未分类」；②concept 必须逐字照抄输入的 key，不许改、不许编；" +
        "③每个 key 最多一条建议；④reason 一句话（不超过 " + MAX_REASON_CHARS + " 字）；" +
        "⑤不要抽新概念、不要改概念名字，只做归类。"
    val user = buildString {
        append("第 ").append(batchIndex + 1).append(" / ").append(batchCount).append(" 批：\n\n")
        if (existingPaths.isNotEmpty()) {
            append("现有分类路径（可直接用）：\n")
            existingPaths.take(80).forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("还没归类的概念（key｜名字）：\n")
        concepts.forEach { (key, name) -> append("- ").append(key).append("｜").append(name).append('\n') }
        append('\n')
        append("输出严格 JSON 对象：\n")
        append("{\"assignments\":[{\"concept\":\"<输入的 key>\",\"categoryPath\":\"大类/小类\",")
        append("\"reason\":\"一句话理由\"}]}\n")
        append("没有把握的概念可以不给建议；不要输出 JSON 以外的任何文字。")
    }
    return listOf(LlmMessage(LlmRole.System, system), LlmMessage(LlmRole.User, user))
}

internal fun parseGlobalCategorySuggestions(
    raw: String,
    allowedConcepts: Map<String, String>,
    batchIndex: Int,
    now: Long,
): List<AiGlobalCategorySuggestion> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    val root = runCatching {
        Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject
    }.getOrNull() ?: return emptyList()
    return root.arrayOrEmpty("assignments").mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val key = allowedConcepts[conceptIdentityKey(obj.stringOrNull("concept").orEmpty())]
            ?: return@mapNotNull null
        val path = parseSuggestionCategoryPath(obj.stringOrNull("categoryPath")) ?: return@mapNotNull null
        AiGlobalCategorySuggestion(
            concept = key,
            categoryPath = path,
            reason = obj.stringOrNull("reason")?.trim()?.take(MAX_REASON_CHARS),
            batch = batchIndex,
            createdAt = now,
        )
    }.distinctBy { it.concept }.take(MAX_GLOBAL_BATCH_CONCEPTS)
}

/** 把一批批结果合并进缓存：conceptSetHash 变了就整体重来，同一概念只留第一条。 */
internal fun mergeGlobalCategorySuggestions(
    existing: AiGlobalCategories,
    conceptSetHash: String,
    incoming: List<AiGlobalCategorySuggestion>,
    failed: List<AiFailedCategory>,
    now: Long,
): AiGlobalCategories {
    val sameSet = existing.conceptSetHash == conceptSetHash
    val base = if (sameSet) existing else AiGlobalCategories(conceptSetHash = conceptSetHash)
    val categories = (base.categories + incoming)
        .distinctBy { it.concept }
        .take(MAX_GLOBAL_BATCHES * MAX_GLOBAL_BATCH_CONCEPTS)
    val failures = (base.failed + failed).distinctBy { it.concept }.take(MAX_GLOBAL_BATCH_CONCEPTS)
    return AiGlobalCategories(
        conceptSetHash = conceptSetHash,
        categories = categories,
        failed = failures,
    )
}

/** 生成唯一的概念集哈希（字符串形式，同 `conceptSetHash(graph)` 的 16 进制写法）。 */
internal fun knowledgeConceptSetHash(graph: KnowledgeGraph): String =
    graphHashText(conceptSetHash(graph))

/** 全图整理的一轮结果（纯逻辑，便于用假 LLM 做端到端测试）。 */
internal data class GlobalSuggestionsRun(
    val categories: AiGlobalCategories,
    val answeredCount: Int,
    val callCount: Int,
) {
    val failedCount: Int get() = categories.failed.size
}

/**
 * 跑一轮「AI 整理分类」：分批调模型 → 解析 → 合并进（内存里的）缓存。
 *
 * - 串行、每批失败只记一条 failed、不自动重试；
 * - 不碰 `user`/分类树：确认是 UI 的第二步；
 * - `isIgnored` 用来过滤用户已经忽略过的建议（墓碑）。
 */
internal suspend fun runGlobalCategorySuggestions(
    client: LlmClient,
    graph: KnowledgeGraph,
    batches: List<List<String>>,
    existingPaths: List<String>,
    now: () -> Long,
    isIgnored: (conceptKey: String, path: List<String>) -> Boolean = { _, _ -> false },
    onProgress: (String) -> Unit = {},
): GlobalSuggestionsRun {
    val hash = knowledgeConceptSetHash(graph)
    var merged = AiGlobalCategories(conceptSetHash = hash)
    val failed = ArrayList<AiFailedCategory>()
    var answered = 0
    var calls = 0
    for ((index, keys) in batches.withIndex()) {
        onProgress("AI 整理中：第 ${index + 1}/${batches.size} 批（${keys.size} 个概念）")
        val concepts = keys.mapNotNull { key ->
            val node = graph.nodes[key] ?: return@mapNotNull null
            node.key to node.name
        }
        if (concepts.isEmpty()) continue
        val allowed = concepts.toMap()
        val messages = buildGlobalCategoryMessages(concepts, existingPaths, index, batches.size)
        calls++
        val raw = try {
            client.complete(messages)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            allowed.keys.forEach { failed += AiFailedCategory(it, error.message ?: "请求失败") }
            continue
        }
        val parsed = parseGlobalCategorySuggestions(raw, allowed, index, now())
            .filterNot { isIgnored(it.concept, it.categoryPath) }
        val answeredKeys = parsed.map { it.concept }.toSet()
        allowed.keys.filterNot { it in answeredKeys }
            .forEach { failed += AiFailedCategory(it, "这批没给建议") }
        answered += parsed.size
        merged = mergeGlobalCategorySuggestions(
            existing = merged,
            conceptSetHash = hash,
            incoming = parsed,
            failed = emptyList(),
            now = now(),
        )
    }
    merged = mergeGlobalCategorySuggestions(
        existing = merged,
        conceptSetHash = hash,
        incoming = emptyList(),
        failed = failed,
        now = now(),
    )
    return GlobalSuggestionsRun(categories = merged, answeredCount = answered, callCount = calls)
}

// ---------------------------------------------------------------------------
// 待确认建议（UI 列表的数据源；过滤墓碑 / 已归属 / 过期正文）
// ---------------------------------------------------------------------------

/** 建议来源，用于 UI 上一行来源标签。 */
internal enum class SuggestionOrigin(val label: String) {
    NOTE("笔记内"),
    GLOBAL("全图"),
}

internal data class PendingCategorySuggestion(
    val conceptKey: String,
    val conceptName: String,
    val categoryPath: List<String>,
    val reason: String?,
    val origin: SuggestionOrigin,
    val fingerprint: String,
    /** 来自哪篇笔记（全图建议为 null）；确认/忽略时用来清理缓存。 */
    val noteId: String? = null,
)

internal data class PendingRelationSuggestion(
    val fromKey: String,
    val toKey: String,
    val fromName: String,
    val toName: String,
    val kind: GraphEdgeKind,
    val confidence: Float,
    val reason: String?,
    val evidence: String?,
    val origin: SuggestionOrigin,
    val fingerprint: String,
    val noteId: String,
)

internal data class PendingGraphSuggestions(
    val categories: List<PendingCategorySuggestion> = emptyList(),
    val relations: List<PendingRelationSuggestion> = emptyList(),
) {
    val isEmpty: Boolean get() = categories.isEmpty() && relations.isEmpty()
    val total: Int get() = categories.size + relations.size
}

internal fun categorySuggestionFingerprint(conceptKey: String, path: List<String>): String =
    graphHashText(fnv1a64(conceptIdentityKey(conceptKey) + "|" + path.joinToString("/")))

internal fun relationSuggestionFingerprint(
    from: String,
    to: String,
    kind: GraphEdgeKind,
): String = graphHashText(
    fnv1a64(conceptIdentityKey(from) + "|" + conceptIdentityKey(to) + "|" + kind.name),
)

internal fun isCategorySuggestionIgnored(
    removed: List<RemovedSuggestion>,
    conceptKey: String,
    path: List<String>,
): Boolean {
    val fingerprint = categorySuggestionFingerprint(conceptKey, path)
    return removed.any { it.fingerprint == fingerprint }
}

internal fun isRelationSuggestionIgnored(
    removed: List<RemovedRelation>,
    from: String,
    to: String,
    kind: GraphEdgeKind,
): Boolean {
    val fromKey = conceptIdentityKey(from)
    val toKey = conceptIdentityKey(to)
    return removed.any { item ->
        val a = conceptIdentityKey(item.from)
        val b = conceptIdentityKey(item.to)
        item.kind == kind && ((a == fromKey && b == toKey) || (kind.isSymmetric() && a == toKey && b == fromKey))
    }
}

/**
 * 计算当前待确认建议。
 *
 * @param noteBodyHashes 当前各笔记的正文哈希（`noteId -> fnv1a64:…`）；笔记建议体哈希对不上就不展示。
 * @param globalCategoriesValid 全图建议的 conceptSetHash 是否仍与当前概念集一致。
 * @param conceptNames 当前图里存在的概念：identityKey -> 显示名（不在图里的建议直接丢）。
 */
internal fun collectPendingSuggestions(
    cache: GraphAiCache,
    user: GraphUserData,
    conceptNames: Map<String, String>,
    noteBodyHashes: Map<String, String>,
    globalCategoriesValid: Boolean,
): PendingGraphSuggestions {
    val categories = ArrayList<PendingCategorySuggestion>()
    val seen = HashSet<String>()
    val assigned = user.assignments.keys.map(::conceptIdentityKey).toSet()

    for ((noteId, suggestions) in cache.noteSuggestions) {
        if (suggestions.bodyHash != noteBodyHashes[noteId]) continue
        for (item in suggestions.categories) {
            val key = conceptIdentityKey(item.name)
            val name = conceptNames[key] ?: continue
            if (key in assigned) continue
            if (isCategorySuggestionIgnored(user.removed.suggestions, key, item.categoryPath)) continue
            val fingerprint = categorySuggestionFingerprint(key, item.categoryPath)
            if (!seen.add("$noteId|$fingerprint")) continue
            categories += PendingCategorySuggestion(
                conceptKey = key,
                conceptName = name,
                categoryPath = item.categoryPath,
                reason = item.reason,
                origin = SuggestionOrigin.NOTE,
                fingerprint = fingerprint,
                noteId = noteId,
            )
        }
    }

    if (globalCategoriesValid) {
        for (item in cache.globalCategories.categories) {
            val key = conceptIdentityKey(item.concept)
            val name = conceptNames[key] ?: continue
            if (key in assigned) continue
            if (isCategorySuggestionIgnored(user.removed.suggestions, key, item.categoryPath)) continue
            val fingerprint = categorySuggestionFingerprint(key, item.categoryPath)
            if (!seen.add("global|$fingerprint")) continue
            categories += PendingCategorySuggestion(
                conceptKey = key,
                conceptName = name,
                categoryPath = item.categoryPath,
                reason = item.reason,
                origin = SuggestionOrigin.GLOBAL,
                fingerprint = fingerprint,
                noteId = null,
            )
        }
    }

    val relations = ArrayList<PendingRelationSuggestion>()
    val seenRelations = HashSet<String>()
    val existingUserRelations = user.relations.map { relationKey(it.from, it.to, it.kind) }.toSet()
    for ((noteId, suggestions) in cache.noteSuggestions) {
        if (suggestions.bodyHash != noteBodyHashes[noteId]) continue
        for (item in suggestions.relations) {
            val fromKey = conceptIdentityKey(item.from)
            val toKey = conceptIdentityKey(item.to)
            val fromName = conceptNames[fromKey] ?: continue
            val toName = conceptNames[toKey] ?: continue
            if (fromKey == toKey) continue
            if (item.kind == GraphEdgeKind.CO_OCCUR) continue
            if (relationKey(fromKey, toKey, item.kind) in existingUserRelations) continue
            if (isRelationSuggestionIgnored(user.removed.relations, fromKey, toKey, item.kind)) continue
            val fingerprint = relationSuggestionFingerprint(fromKey, toKey, item.kind)
            if (!seenRelations.add(fingerprint)) continue
            relations += PendingRelationSuggestion(
                fromKey = fromKey,
                toKey = toKey,
                fromName = fromName,
                toName = toName,
                kind = item.kind,
                confidence = item.confidence,
                reason = item.reason,
                evidence = item.evidence,
                origin = SuggestionOrigin.NOTE,
                fingerprint = fingerprint,
                noteId = noteId,
            )
        }
    }

    return PendingGraphSuggestions(
        categories = categories.take(MAX_PENDING_CATEGORY_SUGGESTIONS),
        relations = relations.take(MAX_PENDING_RELATION_SUGGESTIONS),
    )
}

private fun relationKey(from: String, to: String, kind: GraphEdgeKind): String {
    val a = conceptIdentityKey(from)
    val b = conceptIdentityKey(to)
    return if (kind.isSymmetric()) {
        listOf(a, b).sorted().joinToString(">") + ">" + kind.name
    } else {
        a + ">" + b + ">" + kind.name
    }
}

// ---------------------------------------------------------------------------
// 确认 / 忽略：只返回新数据，落盘由调用方负责
// ---------------------------------------------------------------------------

/** 确认归类建议：复用 Taxonomy 的建路径 + 归属逻辑（含路径深度/墓碑校验）。 */
internal fun confirmCategorySuggestions(
    user: GraphUserData,
    suggestions: List<PendingCategorySuggestion>,
    now: Long,
): TaxonomyResult = applyCategorySuggestions(
    user = user,
    suggestions = suggestions.map {
        CategorySuggestion(
            conceptKey = it.conceptKey,
            categoryPath = it.categoryPath,
            reason = it.reason,
            origin = NodeOrigin.AI,
        )
    },
    now = now,
)

/** 确认关系建议：写成 user.relations（权威边）；同一对端点同类型只保留一条。 */
internal fun confirmRelationSuggestions(
    user: GraphUserData,
    suggestions: List<PendingRelationSuggestion>,
    now: Long,
): GraphUserData {
    if (suggestions.isEmpty()) return user
    val existing = user.relations.toMutableList()
    var added = 0
    for (item in suggestions) {
        val duplicate = existing.any {
            relationKey(it.from, it.to, it.kind) == relationKey(item.fromKey, item.toKey, item.kind)
        }
        if (duplicate) continue
        existing += GraphUserRelation(
            from = item.fromKey,
            to = item.toKey,
            kind = item.kind,
            confidence = item.confidence,
            reason = item.reason,
            createdAt = now,
        )
        added++
    }
    if (added == 0) return user
    return user.copy(relations = existing, updatedAt = now)
}

/** 忽略归类建议：记一条按（概念, 路径）指纹的墓碑，AI 以后再提同一建议会被过滤掉。 */
internal fun ignoreCategorySuggestion(
    user: GraphUserData,
    suggestion: PendingCategorySuggestion,
): GraphUserData {
    val tombstone = RemovedSuggestion(concept = suggestion.conceptKey, fingerprint = suggestion.fingerprint)
    if (user.removed.suggestions.any { it.fingerprint == tombstone.fingerprint }) return user
    return user.copy(
        removed = user.removed.copy(
            suggestions = (user.removed.suggestions + tombstone).takeLast(500),
        ),
    )
}

/** 忽略关系建议：写 RemovedRelation（和手动删关系同一份墓碑）。 */
internal fun ignoreRelationSuggestion(
    user: GraphUserData,
    suggestion: PendingRelationSuggestion,
): GraphUserData {
    if (isRelationSuggestionIgnored(user.removed.relations, suggestion.fromKey, suggestion.toKey, suggestion.kind)) {
        return user
    }
    val tombstone = RemovedRelation(
        from = suggestion.fromKey,
        to = suggestion.toKey,
        kind = suggestion.kind,
    )
    return user.copy(
        removed = user.removed.copy(
            relations = (user.removed.relations + tombstone).takeLast(500),
        ),
    )
}

/** 确认/忽略完，把对应条目从缓存里删掉（缓存是建议集合，不是历史）。 */
internal fun cacheWithoutCategorySuggestions(
    cache: GraphAiCache,
    handled: List<PendingCategorySuggestion>,
): GraphAiCache {
    if (handled.isEmpty()) return cache
    val noteFingerprints = handled.filter { it.noteId != null }
        .groupBy({ it.noteId!! }, { it.fingerprint })
    val globalFingerprints = handled.filter { it.noteId == null }.map { it.fingerprint }.toSet()
    val notes = cache.noteSuggestions.mapValues { (noteId, suggestions) ->
        suggestions.copy(
            categories = suggestions.categories.filterNot { item ->
                categorySuggestionFingerprint(conceptIdentityKey(item.name), item.categoryPath) in
                    noteFingerprints[noteId].orEmpty()
            },
        )
    }
    val global = cache.globalCategories.copy(
        categories = cache.globalCategories.categories.filterNot { item ->
            categorySuggestionFingerprint(item.concept, item.categoryPath) in globalFingerprints
        },
    )
    return cache.copy(noteSuggestions = notes, globalCategories = global)
}

internal fun cacheWithoutRelationSuggestions(
    cache: GraphAiCache,
    handled: List<PendingRelationSuggestion>,
): GraphAiCache {
    if (handled.isEmpty()) return cache
    val byNote = handled.groupBy({ it.noteId }, { it.fingerprint })
    val notes = cache.noteSuggestions.mapValues { (noteId, suggestions) ->
        val fingerprints = byNote[noteId].orEmpty()
        suggestions.copy(
            relations = suggestions.relations.filterNot { item ->
                relationSuggestionFingerprint(item.from, item.to, item.kind) in fingerprints
            },
        )
    }
    return cache.copy(noteSuggestions = notes)
}

// ---------------------------------------------------------------------------
// 小工具
// ---------------------------------------------------------------------------

private fun JsonObject.arrayOrEmpty(key: String): JsonArray =
    (this[key] as? JsonArray) ?: JsonArray(emptyList())

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.numberOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
