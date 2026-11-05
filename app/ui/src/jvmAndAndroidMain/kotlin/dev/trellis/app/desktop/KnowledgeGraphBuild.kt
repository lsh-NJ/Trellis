package dev.trellis.app.desktop

/**
 * 知识网络的确定性构建（设计 §3.2–§3.6、§8.4）。
 *
 * 输入：一期知识库的笔记（Markdown 是真相）+ 概念定义（concepts.json）+ 用户策展
 * （graph.user.json）。输出一份「现算」的 [KnowledgeGraph]：
 *
 * 1. 概念节点：frontmatter `concepts:` 按 [conceptIdentityKey] 聚合（frontmatter-only 标记）；
 * 2. 分类节点：先 [validateTaxonomy] 拿修复后的分类树，再转 [GraphNode]；
 * 3. 归属：概念用 `assignments`（容忍一期旧 key），值沿 `mergedInto` 解析；
 * 4. 派生关系边：正文 `[[…]]` 标注 → 同段共现 / 相邻段共现 / 相邻标注 RELATED；
 * 5. 用户关系：`user.relations` 转 USER 边；
 * 6. 墓碑过滤 + 同 (from,to,kind) 合并（USER > EXPLICIT > HEURISTIC > AI）；
 * 7. 诊断与统计。
 *
 * 全部是纯函数：不读文件、不写文件、不取时间、不碰 Compose；所有集合先排序，
 * 同输入同输出（设计 §10.2 不变式）。G0 不读 [cache]，参数保留给 G1 的 AI 建议。
 */
internal fun buildKnowledgeGraph(
    notes: List<KnowledgeNote>,
    concepts: Map<String, ConceptMeta>,
    user: GraphUserData,
    @Suppress("UNUSED_PARAMETER") cache: GraphAiCache,
): KnowledgeGraph {
    // 1. 校验/修复分类树：建树与校验用同一份修复结果。
    val validation = validateTaxonomy(user)
    val repaired = validation.user
    val diagnostics = ArrayList<GraphDiagnostic>(validation.diagnostics.size + 4)
    diagnostics.addAll(validation.diagnostics)

    val sortedNotes = notes.sortedBy { it.id }
    val normalizedConcepts = normalizeConceptMetas(concepts)

    // 2. 概念节点（frontmatter 派生）。
    val conceptNodes = aggregateConceptNodes(sortedNotes, normalizedConcepts)
    val categoryById = LinkedHashMap<String, GraphCategory>()
    for (category in repaired.categories) categoryById[category.id] = category
    val categoryNodes = repaired.categories.map { categoryToNode(it) }

    // 3. 归属：assignments[conceptIdentityKey]（容忍一期旧 key），值经 mergedInto 解析。
    val invalidAssignments = HashSet<String>()
    for ((rawKey, rawValue) in user.assignments.toSortedMap()) {
        val identity = conceptIdentityKey(rawKey)
        if (identity.isEmpty()) continue
        val resolved = resolveMergedCategoryId(repaired, rawValue)
        if (!categoryById.containsKey(resolved)) invalidAssignments.add(identity)
    }
    var droppedAssignments = invalidAssignments.size
    val conceptParents = LinkedHashMap<String, String?>()
    for (node in conceptNodes) {
        val rawAssignment = assignmentFor(repaired.assignments, node)
        if (rawAssignment == null) {
            conceptParents[node.key] = null
            continue
        }
        val resolved = resolveMergedCategoryId(repaired, rawAssignment)
        if (!categoryById.containsKey(resolved)) {
            conceptParents[node.key] = null
            if (invalidAssignments.add(node.key)) {
                droppedAssignments++
                diagnostics.add(
                    GraphDiagnostic(
                        DiagnosticKind.InvalidAssignment,
                        node.key,
                        "概念「${node.name}」的归属分类「$rawAssignment」不存在，已放进未分类。",
                    ),
                )
            }
        } else {
            conceptParents[node.key] = resolved
        }
    }
    val finalConceptNodes = conceptNodes.map { it.copy(parentKey = conceptParents[it.key]) }

    // 4. 节点表（同 key 冲突时分类节点优先；分类 id 与概念身份键理论上不会撞）。
    val nodes = LinkedHashMap<String, GraphNode>()
    for (node in (finalConceptNodes + categoryNodes).sortedBy { it.key }) {
        nodes[node.key] = node
    }

    // 5. childrenOf：分类下子分类在前、概念在后；null 下根分类在前、未分类概念在后。
    val childrenOf = buildChildrenOf(finalConceptNodes, categoryNodes)

    // 6. 派生关系边（G0 不读 AI cache）。
    val conceptNodeKeys = finalConceptNodes.mapTo(HashSet()) { it.key }
    val frontmatterOnlyKeys = finalConceptNodes.filter { it.frontmatterOnly }.mapTo(HashSet()) { it.key }
    val identities = normalizedConcepts.values
        .sortedBy { it.name }
        .map { ConceptIdentity(it.name, it.aliases) }
    val tombstonedKeys = tombstonedRelationKeys(repaired.removed.relations, conceptNodeKeys)
    val candidates = ArrayList<GraphEdge>()
    for (note in sortedNotes) {
        candidates.addAll(
            derivedEdgesForNote(note, identities, conceptNodeKeys, frontmatterOnlyKeys, tombstonedKeys),
        )
    }
    candidates.addAll(userRelationEdges(repaired, conceptNodeKeys, tombstonedKeys, diagnostics))

    // 7. 同一 (from,to,kind) 去重：USER > EXPLICIT > HEURISTIC > AI，confidence 取高者，
    //    evidence 优先保留有证据的那条。
    val mergedEdges = LinkedHashMap<EdgeKey, GraphEdge>()
    for (candidate in candidates) {
        val key = EdgeKey(candidate.fromKey, candidate.toKey, candidate.kind)
        mergedEdges[key] = mergeEdges(mergedEdges[key], candidate)
    }
    val edges = mergedEdges.values.sortedWith(
        compareBy<GraphEdge>({ it.fromKey }, { it.toKey }, { it.kind.name }, { it.source.name }),
    )

    // 8. 统计。
    val classifiedCount = finalConceptNodes.count { it.parentKey != null }
    val maxDepth = if (categoryNodes.isEmpty()) {
        0
    } else {
        categoryNodes.maxOf { categoryDepthInGraph(nodes, it.key) }
    }
    val stats = GraphStats(
        conceptCount = finalConceptNodes.size,
        categoryCount = categoryNodes.size,
        rootCategoryCount = categoryNodes.count { it.parentKey == null },
        maxDepth = maxDepth,
        classifiedCount = classifiedCount,
        unclassifiedCount = finalConceptNodes.size - classifiedCount,
        relationEdgeCount = edges.size,
        aiSuggestionCount = 0,
        droppedAssignments = droppedAssignments,
    )
    return KnowledgeGraph(
        nodes = nodes,
        childrenOf = childrenOf,
        edges = edges,
        stats = stats,
        diagnostics = diagnostics,
    )
}

// ---------------------------------------------------------------------------
// 概念节点
// ---------------------------------------------------------------------------

/** 单概念聚合：出现的笔记、最近更新笔记里的写法、首末时间。 */
private class ConceptAccumulator {
    private val seenNoteIds = LinkedHashSet<String>()
    val notes = ArrayList<KnowledgeNote>()
    private var latestUpdatedAt: Long = Long.MIN_VALUE
    private var latestSpelling: String = ""
    var firstSeenAt: Long = Long.MAX_VALUE
    var lastSeenAt: Long = Long.MIN_VALUE

    fun addObservation(note: KnowledgeNote, spelling: String) {
        if (!seenNoteIds.add(note.id)) return
        notes.add(note)
        // 同 updatedAt 时后处理的（笔记 id 更大）胜出，保证确定性。
        if (note.updatedAt >= latestUpdatedAt) {
            latestUpdatedAt = note.updatedAt
            latestSpelling = spelling
        }
        if (note.createdAt < firstSeenAt) firstSeenAt = note.createdAt
        if (note.updatedAt > lastSeenAt) lastSeenAt = note.updatedAt
    }

    fun latestSpelling(): String = latestSpelling
}

/** 把 concepts 的 key 统一成 conceptIdentityKey（调用方理应已经这样，这里兜底）。 */
private fun normalizeConceptMetas(concepts: Map<String, ConceptMeta>): Map<String, ConceptMeta> {
    val normalized = LinkedHashMap<String, ConceptMeta>()
    for ((rawKey, meta) in concepts.toSortedMap()) {
        val key = conceptIdentityKey(rawKey)
        if (key.isEmpty()) continue
        normalized.putIfAbsent(key, meta)
    }
    return normalized
}

/**
 * 概念节点（设计 §3.2/§3.6）：
 * - 显示名优先 concepts.json 里的规范名，其次最近更新笔记里的写法；
 * - noteCount = 出现笔记数；mentionCount = 这些笔记正文里显示名的出现次数之和；
 * - firstSeenAt = 最早 createdAt，lastSeenAt = 最晚 updatedAt；
 * - frontmatterOnly = 所有笔记正文都没有出现显示名。
 */
private fun aggregateConceptNodes(
    notes: List<KnowledgeNote>,
    concepts: Map<String, ConceptMeta>,
): List<GraphNode> {
    val accumulators = LinkedHashMap<String, ConceptAccumulator>()
    for (note in notes) {
        val removedKeys = note.removedConcepts.mapTo(HashSet()) { conceptIdentityKey(it) }
        for (rawName in note.concepts) {
            val name = normalizeConceptName(rawName)
            if (!isAcceptableConceptName(name)) continue
            val identity = conceptIdentityKey(name)
            if (identity.isEmpty() || identity in removedKeys) continue
            accumulators.getOrPut(identity) { ConceptAccumulator() }.addObservation(note, name)
        }
    }
    val nodes = ArrayList<GraphNode>(accumulators.size)
    for (key in accumulators.keys.sorted()) {
        val accumulator = accumulators.getValue(key)
        val meta = concepts[key]
        val fallbackName = accumulator.latestSpelling()
        val displayName = meta?.name?.takeIf { it.isNotBlank() } ?: fallbackName
        var mentions = 0
        var mentionedInBody = false
        for (note in accumulator.notes) {
            val count = conceptOccurrences(note.body, displayName)
            mentions += count
            if (count > 0) mentionedInBody = true
        }
        nodes.add(
            GraphNode(
                key = key,
                kind = GraphNodeKind.CONCEPT,
                name = displayName.ifBlank { key },
                definition = meta?.definition.orEmpty(),
                noteCount = accumulator.notes.size,
                mentionCount = mentions,
                firstSeenAt = accumulator.firstSeenAt.takeIf { it != Long.MAX_VALUE } ?: 0L,
                lastSeenAt = accumulator.lastSeenAt.takeIf { it != Long.MIN_VALUE } ?: 0L,
                frontmatterOnly = !mentionedInBody,
                parentKey = null,
                origin = NodeOrigin.DERIVED,
                confirmed = true,
            ),
        )
    }
    return nodes
}

/** 分类节点（设计 §3.2）：父子关系只存 parentKey，绝不进 GraphEdge。 */
private fun categoryToNode(category: GraphCategory): GraphNode = GraphNode(
    key = category.id,
    kind = GraphNodeKind.CATEGORY,
    name = category.name,
    definition = category.description.orEmpty(),
    parentKey = category.parentId,
    origin = category.origin,
    description = category.description,
    confirmed = category.confirmed,
    sortOrder = category.order,
)

/** 概念归属查找：先规范身份键，再容忍一期旧风格 key（展示名 / conceptKey）。 */
private fun assignmentFor(assignments: Map<String, String>, node: GraphNode): String? {
    assignments[node.key]?.let { return it }
    val identity = conceptIdentityKey(node.name)
    assignments[identity]?.let { return it }
    val legacy = conceptKey(node.name)
    return assignments[legacy]
}

// ---------------------------------------------------------------------------
// childrenOf
// ---------------------------------------------------------------------------

private fun buildChildrenOf(
    conceptNodes: List<GraphNode>,
    categoryNodes: List<GraphNode>,
): Map<String?, List<String>> {
    val categoryComparator = compareBy<GraphNode>({ it.sortOrder }, { it.name }, { it.key })
    val conceptComparator = compareByDescending<GraphNode> { it.noteCount }
        .thenBy { it.name }
        .thenBy { it.key }
    val childrenOf = LinkedHashMap<String?, List<String>>()

    val rootCategories = categoryNodes.filter { it.parentKey == null }.sortedWith(categoryComparator)
    val unclassified = conceptNodes.filter { it.parentKey == null }.sortedWith(conceptComparator)
    if (rootCategories.isNotEmpty() || unclassified.isNotEmpty()) {
        childrenOf[null] = rootCategories.map { it.key } + unclassified.map { it.key }
    }
    for (category in categoryNodes.sortedBy { it.key }) {
        val subCategories = categoryNodes.filter { it.parentKey == category.key }.sortedWith(categoryComparator)
        val chips = conceptNodes.filter { it.parentKey == category.key }.sortedWith(conceptComparator)
        if (subCategories.isNotEmpty() || chips.isNotEmpty()) {
            childrenOf[category.key] = subCategories.map { it.key } + chips.map { it.key }
        }
    }
    return childrenOf
}

// ---------------------------------------------------------------------------
// 派生关系边（设计 §3.5）
// ---------------------------------------------------------------------------

/** 一个已映射到概念身份键的正文标注区间（end 不含）。 */
private data class MappedAnnotation(val key: String, val start: Int, val end: Int)

/** 边的去重/墓碑键。 */
private data class EdgeKey(val fromKey: String, val toKey: String, val kind: GraphEdgeKind)

private const val EVIDENCE_SNIPPET_MAX: Int = 120
private const val DERIVED_EDGE_LIMIT_MAX: Int = 24
private const val DERIVED_EDGE_LIMIT_MIN: Int = 8
private const val CO_OCCUR_SAME_PARAGRAPH: Float = 0.5f
private const val CO_OCCUR_ADJACENT_PARAGRAPHS: Float = 0.25f
private const val RELATED_EXPLICIT_CONFIDENCE: Float = 0.8f

private val derivedPriorityComparator: Comparator<GraphEdge> =
    compareByDescending<GraphEdge> { edgeSourceRank(it.source) }
        .thenByDescending { it.confidence }
        .thenBy { it.fromKey }
        .thenBy { it.toKey }
        .thenBy { it.kind.name }

/**
 * 单篇笔记的派生边（设计 §3.5）：
 * - 同段落内两两 → CO_OCCUR 0.5（HEURISTIC）；
 * - 相邻段落之间两两 → CO_OCCUR 0.25（HEURISTIC）；
 * - 同段落内相邻两个标注（中间没有第三个）→ RELATED 0.8（EXPLICIT）；
 * - 每篇上限 `min(24, max(8, concepts.size * 2))`，强的优先。
 *
 * 只有正文 `[[…]]` 标注产生派生边；跳过 fenced code 与 `:::ai` 块（[parseConceptAnnotations] 已处理）。
 * frontmatter-only 概念不生成共现边（但仍允许 RELATED，因为标注目标可能映射到它）。
 */
private fun derivedEdgesForNote(
    note: KnowledgeNote,
    identities: List<ConceptIdentity>,
    conceptNodeKeys: Set<String>,
    frontmatterOnlyKeys: Set<String>,
    tombstonedKeys: Set<EdgeKey>,
): List<GraphEdge> {
    if (conceptNodeKeys.isEmpty()) return emptyList()
    val annotations = parseConceptAnnotations(note.body)
    if (annotations.size < 2) return emptyList()
    val text = note.body.replace("\r\n", "\n")
    val mapped = ArrayList<MappedAnnotation>(annotations.size)
    for (annotation in annotations) {
        val name = conceptAnnotationName(annotation, identities)
        if (name.isBlank()) continue
        val identity = conceptIdentityKey(name)
        if (identity.isEmpty() || identity !in conceptNodeKeys) continue
        mapped.add(MappedAnnotation(identity, annotation.start, annotation.end))
    }
    if (mapped.size < 2) return emptyList()
    mapped.sortWith(compareBy({ it.start }, { it.end }))
    val paragraphs = splitParagraphs(text, mapped)
    if (paragraphs.isEmpty()) return emptyList()

    val candidates = ArrayList<GraphEdge>()
    for (paragraph in paragraphs) {
        val distinct = paragraphDistinct(paragraph)
        for (firstIndex in distinct.indices) {
            for (secondIndex in firstIndex + 1 until distinct.size) {
                val first = distinct[firstIndex]
                val second = distinct[secondIndex]
                if (first.key in frontmatterOnlyKeys || second.key in frontmatterOnlyKeys) continue
                candidates.add(coOccurrenceEdge(note, first, second, CO_OCCUR_SAME_PARAGRAPH, text))
            }
        }
        for (index in 0 until paragraph.size - 1) {
            val first = paragraph[index]
            val second = paragraph[index + 1]
            if (first.key == second.key) continue
            candidates.add(relatedEdge(note, first, second, text))
        }
    }
    for (index in 0 until paragraphs.size - 1) {
        val left = paragraphDistinct(paragraphs[index])
        val right = paragraphDistinct(paragraphs[index + 1])
        for (first in left) {
            for (second in right) {
                if (first.key == second.key) continue
                if (first.key in frontmatterOnlyKeys || second.key in frontmatterOnlyKeys) continue
                candidates.add(
                    coOccurrenceEdge(note, first, second, CO_OCCUR_ADJACENT_PARAGRAPHS, text),
                )
            }
        }
    }

    val perNote = LinkedHashMap<EdgeKey, GraphEdge>()
    for (candidate in candidates) {
        val key = EdgeKey(candidate.fromKey, candidate.toKey, candidate.kind)
        if (key in tombstonedKeys) continue
        perNote[key] = mergeEdges(perNote[key], candidate)
    }
    val limit = minOf(DERIVED_EDGE_LIMIT_MAX, maxOf(DERIVED_EDGE_LIMIT_MIN, note.concepts.size * 2))
    return perNote.values.sortedWith(derivedPriorityComparator).take(limit)
}

private fun paragraphDistinct(annotations: List<MappedAnnotation>): List<MappedAnnotation> {
    val distinct = LinkedHashMap<String, MappedAnnotation>()
    for (annotation in annotations) distinct.putIfAbsent(annotation.key, annotation)
    return distinct.values.toList()
}

private fun coOccurrenceEdge(
    note: KnowledgeNote,
    first: MappedAnnotation,
    second: MappedAnnotation,
    confidence: Float,
    text: String,
): GraphEdge {
    val direction = normalizeEdgeDirection(first.key, second.key, GraphEdgeKind.CO_OCCUR)
    return GraphEdge(
        fromKey = direction.first,
        toKey = direction.second,
        kind = GraphEdgeKind.CO_OCCUR,
        source = GraphEdgeSource.HEURISTIC,
        confidence = confidence,
        evidence = evidenceFor(note, first, text),
    )
}

private fun relatedEdge(
    note: KnowledgeNote,
    first: MappedAnnotation,
    second: MappedAnnotation,
    text: String,
): GraphEdge {
    val direction = normalizeEdgeDirection(first.key, second.key, GraphEdgeKind.RELATED)
    return GraphEdge(
        fromKey = direction.first,
        toKey = direction.second,
        kind = GraphEdgeKind.RELATED,
        source = GraphEdgeSource.EXPLICIT,
        confidence = RELATED_EXPLICIT_CONFIDENCE,
        evidence = evidenceFor(note, first, text),
    )
}

private fun evidenceFor(note: KnowledgeNote, annotation: MappedAnnotation, text: String): GraphEvidence =
    GraphEvidence(
        noteId = note.id,
        start = annotation.start,
        end = annotation.end,
        snippet = lineSnippet(text, annotation.start),
    )

/** 命中行的 trim 片段，最多 [EVIDENCE_SNIPPET_MAX] 字（设计 §3.4 evidence.snippet）。 */
private fun lineSnippet(text: String, offset: Int): String {
    if (text.isEmpty()) return ""
    val safeOffset = offset.coerceIn(0, text.length)
    val newlineBefore = if (safeOffset <= 0) -1 else text.lastIndexOf('\n', safeOffset - 1)
    val lineStart = newlineBefore + 1
    val newlineAfter = text.indexOf('\n', safeOffset)
    val lineEnd = if (newlineAfter < 0) text.length else newlineAfter
    val line = text.substring(lineStart, lineEnd).trim()
    return if (line.length > EVIDENCE_SNIPPET_MAX) line.take(EVIDENCE_SNIPPET_MAX) else line
}

/**
 * 按段落分组标注（设计 §3.5：段落 = 连续非空行；标题行单独成块；
 * 跳过 fenced code 与 `:::ai` 块）。
 */
private fun splitParagraphs(text: String, annotations: List<MappedAnnotation>): List<List<MappedAnnotation>> {
    if (annotations.isEmpty()) return emptyList()
    val lineStarts = ArrayList<Int>()
    val lineEnds = ArrayList<Int>()
    var offset = 0
    while (offset <= text.length) {
        lineStarts.add(offset)
        val newline = text.indexOf('\n', offset)
        val end = if (newline < 0) text.length else newline
        lineEnds.add(end)
        if (newline < 0) break
        offset = newline + 1
    }

    val paragraphOfLine = IntArray(lineStarts.size) { -1 }
    var paragraphIndex = -1
    var paragraphOpen = false
    var inCodeFence = false
    var inAiBlock = false
    for (index in lineStarts.indices) {
        val line = text.substring(lineStarts[index], lineEnds[index])
        val trimmedStart = line.trimStart()
        paragraphOfLine[index] = when {
            trimmedStart.startsWith("```") -> {
                inCodeFence = !inCodeFence
                paragraphOpen = false
                -1
            }
            inCodeFence -> -1
            inAiBlock -> {
                if (trimmedStart.startsWith(AI_BLOCK_END)) inAiBlock = false
                paragraphOpen = false
                -1
            }
            trimmedStart.startsWith(AI_BLOCK_START) -> {
                inAiBlock = true
                paragraphOpen = false
                -1
            }
            line.isBlank() -> {
                paragraphOpen = false
                -1
            }
            trimmedStart.startsWith("#") -> {
                paragraphIndex++
                paragraphOpen = false
                paragraphIndex
            }
            else -> {
                if (!paragraphOpen) {
                    paragraphIndex++
                    paragraphOpen = true
                }
                paragraphIndex
            }
        }
    }

    val groups = LinkedHashMap<Int, MutableList<MappedAnnotation>>()
    for (annotation in annotations) {
        val lineIndex = lineIndexAt(lineStarts, annotation.start)
        val paragraph = if (lineIndex in paragraphOfLine.indices) paragraphOfLine[lineIndex] else -1
        if (paragraph < 0) continue
        groups.getOrPut(paragraph) { ArrayList() }.add(annotation)
    }
    return groups.keys.sorted().map { paragraph -> groups.getValue(paragraph) }
}

/** 二分找 offset 所在行号（offset 落在行首或行内）。 */
private fun lineIndexAt(lineStarts: List<Int>, offset: Int): Int {
    if (lineStarts.isEmpty()) return -1
    val safeOffset = offset.coerceAtLeast(0)
    var low = 0
    var high = lineStarts.size - 1
    var found = 0
    while (low <= high) {
        val middle = (low + high) / 2
        if (lineStarts[middle] <= safeOffset) {
            found = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return found
}

// ---------------------------------------------------------------------------
// 用户关系 + 合并/墓碑
// ---------------------------------------------------------------------------

/** 用户关系转 USER 边；两端必须是已知概念 key，否则丢弃并报 UnknownEdgeEnd。 */
private fun userRelationEdges(
    user: GraphUserData,
    conceptNodeKeys: Set<String>,
    tombstonedKeys: Set<EdgeKey>,
    diagnostics: MutableList<GraphDiagnostic>,
): List<GraphEdge> {
    val result = ArrayList<GraphEdge>()
    val relations = user.relations.sortedWith(compareBy({ it.from }, { it.to }, { it.kind.name }))
    for (relation in relations) {
        val fromKey = resolveConceptNodeKey(relation.from, conceptNodeKeys)
        val toKey = resolveConceptNodeKey(relation.to, conceptNodeKeys)
        if (fromKey == null || toKey == null) {
            diagnostics.add(
                GraphDiagnostic(
                    DiagnosticKind.UnknownEdgeEnd,
                    relation.from,
                    "用户关系「${relation.from}」→「${relation.to}」的一端不在概念节点里，已丢弃。",
                ),
            )
            continue
        }
        if (fromKey == toKey) continue
        val direction = normalizeEdgeDirection(fromKey, toKey, relation.kind)
        val key = EdgeKey(direction.first, direction.second, relation.kind)
        if (key in tombstonedKeys) continue
        result.add(
            GraphEdge(
                fromKey = direction.first,
                toKey = direction.second,
                kind = relation.kind,
                source = GraphEdgeSource.USER,
                confidence = relation.confidence,
                evidence = null,
                reason = relation.reason,
                createdAt = relation.createdAt,
            ),
        )
    }
    return result
}

/** 用户删过的关系三元组（设计 §6.7）；无向边先规范化方向，两端也按概念 key 兜底解析。 */
private fun tombstonedRelationKeys(
    relations: List<RemovedRelation>,
    conceptNodeKeys: Set<String>,
): Set<EdgeKey> {
    val result = HashSet<EdgeKey>()
    for (relation in relations) {
        val from = resolveConceptNodeKey(relation.from, conceptNodeKeys) ?: relation.from
        val to = resolveConceptNodeKey(relation.to, conceptNodeKeys) ?: relation.to
        val direction = normalizeEdgeDirection(from, to, relation.kind)
        result.add(EdgeKey(direction.first, direction.second, relation.kind))
    }
    return result
}

/** 同 (from,to,kind) 合并：来源优先级 USER > EXPLICIT > HEURISTIC > AI，confidence 取高者。 */
private fun mergeEdges(existing: GraphEdge?, candidate: GraphEdge): GraphEdge {
    if (existing == null) return candidate
    val existingRank = edgeSourceRank(existing.source)
    val candidateRank = edgeSourceRank(candidate.source)
    val winner = if (candidateRank > existingRank) candidate else existing
    val loser = if (candidateRank > existingRank) existing else candidate
    return winner.copy(
        confidence = maxOf(existing.confidence, candidate.confidence),
        evidence = winner.evidence ?: loser.evidence,
        reason = winner.reason ?: loser.reason,
        createdAt = winner.createdAt ?: loser.createdAt,
    )
}

private fun edgeSourceRank(source: GraphEdgeSource): Int = when (source) {
    GraphEdgeSource.USER -> 4
    GraphEdgeSource.EXPLICIT -> 3
    GraphEdgeSource.HEURISTIC -> 2
    GraphEdgeSource.AI -> 1
}

/** 无向边（RELATED / CO_OCCUR）按 key 字典序规范化方向（设计 §3.4 硬规则）。 */
private fun normalizeEdgeDirection(
    fromKey: String,
    toKey: String,
    kind: GraphEdgeKind,
): Pair<String, String> = when (kind) {
    GraphEdgeKind.RELATED, GraphEdgeKind.CO_OCCUR ->
        if (fromKey <= toKey) fromKey to toKey else toKey to fromKey
    GraphEdgeKind.PREREQUISITE, GraphEdgeKind.APPLIES -> fromKey to toKey
}

/** 关系/墓碑里的一期旧 key（展示名）也尽量解析到概念身份键。 */
private fun resolveConceptNodeKey(raw: String, conceptNodeKeys: Set<String>): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed in conceptNodeKeys) return trimmed
    val identity = conceptIdentityKey(trimmed)
    if (identity in conceptNodeKeys) return identity
    val legacy = conceptKey(trimmed)
    return if (legacy in conceptNodeKeys) legacy else null
}

// ---------------------------------------------------------------------------
// 深度
// ---------------------------------------------------------------------------

/** 分类节点在树里的深度（根 = 1），带环保护。 */
private fun categoryDepthInGraph(nodes: Map<String, GraphNode>, categoryKey: String): Int {
    var depth = 1
    var current = nodes[categoryKey]?.parentKey
    val seen = HashSet<String>()
    seen.add(categoryKey)
    while (current != null && seen.add(current)) {
        if (current !in nodes) break
        depth++
        if (depth > MAX_CATEGORY_DEPTH + 1) break
        current = nodes.getValue(current).parentKey
    }
    return depth
}
