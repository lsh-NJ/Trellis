package dev.trellis.app.desktop

/**
 * 知识网络的分类树纯逻辑（设计 §3.3、§6.3、§6.7、§7.2）。
 *
 * 这一层只做「内存 DTO ⇄ 内存 DTO」的增删改查与校验：
 * - 不读文件、不写文件（落盘在 [KnowledgeGraphStore]）；
 * - 不取时间（`now` 全部由调用方传，来自 `TrellisClock`）；
 * - 不碰 Compose，纯函数，同输入同输出。
 *
 * 分类树规则（设计 §3.3）：
 * 1. 单父：每个分类最多一个 `parentId`；
 * 2. 只能「分类装概念」：概念归属写在 `assignments`，分类没有归属；
 * 3. 无环、深度上限 [MAX_CATEGORY_DEPTH]（根算第 1 层）；
 * 4. 同一父分类下不许同名（忽略大小写与空白），不同父下可以同名；
 * 5. AI 可以建缺失的父分类（`origin = AI`、`confirmed = false`）；
 * 6. 删除分类：子分类上移到被删分类的父级，概念回到未分类，两者都写墓碑；
 * 7. 合并分类 A → B：A 的子分类与概念归属改挂 B，`mergedInto[A] = B`，并写 `RemovedCategory`。
 *
 * 所有函数都返回新对象（data class copy），从不原地改传入的 [GraphUserData]。
 */

/** 分类树深度上限（根分类算第 1 层，设计 §3.3）。 */
internal const val MAX_CATEGORY_DEPTH: Int = 5

/** 分类名字符上限（设计 §3.3 / §7.2）。 */
internal const val MAX_CATEGORY_NAME_LENGTH: Int = 40

/** 分类 id 前缀；与 [KnowledgeGraphStore.newCategoryId] 保持同一形状。 */
private const val CATEGORY_ID_PREFIX: String = "c_"

/** 生成分类 id 时冲突重试次数（设计 §7.5）。 */
private const val CATEGORY_ID_ATTEMPTS: Int = 100

/** 一批分类写操作的结果：成功时 `error == null`，失败时 [user] 原样返回。 */
internal data class TaxonomyResult(
    val user: GraphUserData,
    val changes: List<String> = emptyList(),
    val error: String? = null,
)

/** 校验/修复后的分类树 + 修复过程中产生的诊断。 */
internal data class TaxonomyValidation(
    val user: GraphUserData,
    val diagnostics: List<GraphDiagnostic>,
)

// ---------------------------------------------------------------------------
// 小工具：名字规范化、查找、深度、路径
// ---------------------------------------------------------------------------

/** 规范化分类名：空白折叠 + trim；长度由调用方校验（不在这里截断）。 */
internal fun normalizeCategoryName(raw: String): String =
    raw.replace(Regex("\\s+"), " ").trim()

/** 判重用键：忽略大小写与全部空白。 */
private fun categoryNameKey(raw: String): String =
    normalizeCategoryName(raw).lowercase().filterNot { it.isWhitespace() }

/** 分类名合法性校验；合法返回 null。 */
private fun categoryNameError(name: String): String? = when {
    name.isEmpty() -> "分类名不能为空。"
    name.length > MAX_CATEGORY_NAME_LENGTH ->
        "分类名最多 $MAX_CATEGORY_NAME_LENGTH 个字符（当前 ${name.length} 个）。"
    else -> null
}

private fun categoryById(user: GraphUserData, categoryId: String?): GraphCategory? {
    if (categoryId.isNullOrBlank()) return null
    return user.categories.firstOrNull { it.id == categoryId }
}

/**
 * 沿 `mergedInto` 解析旧分类 id（设计 §6.3「旧 id 读时自动解析」）。
 *
 * 纯链式解析、带环保护：成环时返回进入环的那个 id（不抛异常、不死循环）。
 * 想拿到「闭包后的干净映射」请走 [validateTaxonomy]。
 */
internal fun resolveMergedCategoryId(user: GraphUserData, categoryId: String): String {
    var current = categoryId.trim()
    val seen = HashSet<String>()
    while (seen.add(current)) {
        val next = user.mergedInto[current]?.trim().orEmpty()
        if (next.isEmpty() || next == current) return current
        current = next
        if (seen.size > 64) return current
    }
    return current
}

/** 先按精确 id 找分类，找不到再沿 [mergedInto] 解析一次（读旧 id / 已合并 id）。 */
private fun findCategory(user: GraphUserData, categoryId: String?): GraphCategory? {
    if (categoryId.isNullOrBlank()) return null
    categoryById(user, categoryId)?.let { return it }
    val resolved = resolveMergedCategoryId(user, categoryId)
    return categoryById(user, resolved)
}

/** 分类深度（根 = 1）；带环保护，脏数据时的结果只用于判定，不会写回。 */
private fun categoryDepth(user: GraphUserData, categoryId: String): Int {
    var depth = 1
    var current = categoryById(user, categoryId)?.parentId
    val seen = HashSet<String>()
    seen.add(categoryId)
    while (current != null && seen.add(current)) {
        depth++
        if (depth > MAX_CATEGORY_DEPTH + 1) break
        current = categoryById(user, current)?.parentId
    }
    return depth
}

/** 分类子树的相对高度（自己算 1 层）。 */
private fun categorySubtreeHeight(user: GraphUserData, rootId: String): Int {
    var height = 1
    var frontier = listOf(rootId)
    val seen = HashSet<String>()
    var depth = 1
    while (frontier.isNotEmpty() && depth <= MAX_CATEGORY_DEPTH + 2) {
        val next = ArrayList<String>()
        for (id in frontier) {
            if (!seen.add(id)) continue
            for (category in user.categories) {
                if (category.parentId == id) next.add(category.id)
            }
        }
        if (next.isEmpty()) break
        depth++
        height = maxOf(height, depth)
        frontier = next.sorted()
    }
    return height
}

/**
 * 分类路径（根 → 该分类的名字列表，设计 §3.2「分类路径」）。
 *
 * [categoryId] 为 null 或不存在时返回空列表；只有一层时返回单元素列表。
 * 旧 id（已合并）会先沿 `mergedInto` 解析。
 */
internal fun categoryPathOf(user: GraphUserData, categoryId: String?): List<String> {
    if (categoryId.isNullOrBlank()) return emptyList()
    val resolved = resolveMergedCategoryId(user, categoryId)
    val names = ArrayList<String>()
    val seen = HashSet<String>()
    var current: String? = resolved
    while (current != null && seen.add(current)) {
        val category = categoryById(user, current) ?: break
        names.add(category.name)
        current = category.parentId
        if (names.size > MAX_CATEGORY_DEPTH + 1) break
    }
    names.reverse()
    return names
}

/** 路径比较键：名字规范化后按层级拼起来（墓碑匹配用）。 */
private fun conceptPathKey(path: List<String>): String =
    path.map { categoryNameKey(it) }.joinToString("/")

/** 父分类不存在时把可用分类全列出来，方便 UI/AI 直接改（铁律 7）。 */
private fun availableCategoriesText(user: GraphUserData): String {
    if (user.categories.isEmpty()) return "当前还没有任何分类。"
    val lines = user.categories
        .sortedWith(compareBy({ categoryPathOf(user, it.id).joinToString("/") }, { it.id }))
        .joinToString("、") { "「${it.name}」(${it.id})" }
    return "可用分类：$lines。"
}

/** 生成 `c_` + 8 位十六进制 id；与 [now] 和名字绑定，冲突时加计数重试。 */
private fun generateCategoryId(user: GraphUserData, now: Long, name: String): String {
    val existing = user.categories.mapTo(HashSet()) { it.id }
    for (attempt in 0 until CATEGORY_ID_ATTEMPTS) {
        val seed = if (attempt == 0) "$now:$name" else "$now:$name:$attempt"
        val id = CATEGORY_ID_PREFIX +
            graphHashText(fnv1a64(seed)).removePrefix(GRAPH_HASH_PREFIX).take(8)
        if (!existing.contains(id)) return id
    }
    var counter = CATEGORY_ID_ATTEMPTS
    while (true) {
        val id = CATEGORY_ID_PREFIX +
            graphHashText(fnv1a64("$now:$name:$counter")).removePrefix(GRAPH_HASH_PREFIX).take(8)
        if (!existing.contains(id)) return id
        counter++
    }
}

/** 新建分类的内部结果（供 [createCategory] 与 [applyCategorySuggestions] 共用）。 */
private data class CategoryInsertion(
    val user: GraphUserData,
    val categoryId: String?,
    val error: String?,
    val change: String?,
)

/**
 * 新建分类的共用实现。[origin]/[confirmed] 由入口决定：
 * 用户手动建 = USER/true；AI 建议建 = AI/false（设计 §3.3 规则 5）。
 */
private fun insertCategory(
    user: GraphUserData,
    name: String,
    parentId: String?,
    now: Long,
    requestedId: String?,
    origin: NodeOrigin,
    confirmed: Boolean,
): CategoryInsertion {
    val cleaned = normalizeCategoryName(name)
    val nameError = categoryNameError(cleaned)
    if (nameError != null) return CategoryInsertion(user, null, nameError, null)

    val parentRef = parentId?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedParentId = parentRef?.let { resolveMergedCategoryId(user, it) }
    val parent = resolvedParentId?.let { categoryById(user, it) }
    if (parentRef != null && parent == null) {
        return CategoryInsertion(
            user,
            null,
            "父分类不存在：「$parentRef」。${availableCategoriesText(user)}",
            null,
        )
    }
    if (parent != null && categoryDepth(user, parent.id) >= MAX_CATEGORY_DEPTH) {
        return CategoryInsertion(
            user,
            null,
            "分类树最多 $MAX_CATEGORY_DEPTH 层，不能再给「${parent.name}」加子分类。",
            null,
        )
    }

    val nameKey = categoryNameKey(cleaned)
    val sibling = user.categories.firstOrNull {
        it.parentId == parent?.id && categoryNameKey(it.name) == nameKey
    }
    if (sibling != null) {
        return CategoryInsertion(
            user,
            null,
            "同一父分类下已有同名分类「${sibling.name}」，不能重复创建。",
            null,
        )
    }

    val newId: String = if (requestedId != null) {
        val trimmedId = requestedId.trim()
        if (trimmedId.isEmpty()) return CategoryInsertion(user, null, "分类 id 不能为空。", null)
        if (user.categories.any { it.id == trimmedId }) {
            return CategoryInsertion(user, null, "分类 id「$trimmedId」已存在，不能再使用。", null)
        }
        trimmedId
    } else {
        generateCategoryId(user, now, cleaned)
    }

    val order = user.categories
        .filter { it.parentId == parent?.id }
        .maxOfOrNull { it.order }
        ?.plus(1)
        ?: 0
    val category = GraphCategory(
        id = newId,
        name = cleaned,
        parentId = parent?.id,
        order = order,
        origin = origin,
        description = null,
        confirmed = confirmed,
    )
    return CategoryInsertion(
        user.copy(categories = user.categories + category, updatedAt = now),
        newId,
        null,
        "新建分类「$cleaned」",
    )
}

// ---------------------------------------------------------------------------
// 分类增删改
// ---------------------------------------------------------------------------

/**
 * 新建分类（设计 §3.3）。
 *
 * [id] 传入时直接用（必须与现有 id 不冲突）；不传则生成 `c_` + 8 位十六进制。
 * 失败（重名 / 父不存在 / 超深 / id 冲突）时返回原 [user] 与中文错误，不抛异常。
 */
internal fun createCategory(
    user: GraphUserData,
    name: String,
    parentId: String?,
    now: Long,
    id: String? = null,
): TaxonomyResult {
    val insertion = insertCategory(user, name, parentId, now, id, NodeOrigin.USER, confirmed = true)
    return if (insertion.error != null) {
        TaxonomyResult(user, emptyList(), insertion.error)
    } else {
        TaxonomyResult(insertion.user, listOfNotNull(insertion.change), null)
    }
}

/** 重命名分类：id 不变、归属不动（设计 §6.3）。 */
internal fun renameCategory(user: GraphUserData, categoryId: String, newName: String): TaxonomyResult {
    val category = findCategory(user, categoryId)
        ?: return TaxonomyResult(user, emptyList(), "分类不存在：「$categoryId」。${availableCategoriesText(user)}")
    val cleaned = normalizeCategoryName(newName)
    val nameError = categoryNameError(cleaned)
    if (nameError != null) return TaxonomyResult(user, emptyList(), nameError)
    if (cleaned == category.name) return TaxonomyResult(user, emptyList(), null)

    val nameKey = categoryNameKey(cleaned)
    val sibling = user.categories.firstOrNull {
        it.id != category.id && it.parentId == category.parentId && categoryNameKey(it.name) == nameKey
    }
    if (sibling != null) {
        return TaxonomyResult(
            user,
            emptyList(),
            "同一父分类下已有同名分类「${sibling.name}」，不能改成重名。",
        )
    }
    val categories = user.categories.map {
        if (it.id == category.id) it.copy(name = cleaned) else it
    }
    return TaxonomyResult(
        user.copy(categories = categories),
        listOf("重命名分类「${category.name}」→「$cleaned」"),
        null,
    )
}

/** 移动分类（换父）；环、超深、父不存在都拒绝，[user] 原样返回。 */
internal fun moveCategory(user: GraphUserData, categoryId: String, newParentId: String?): TaxonomyResult {
    val category = findCategory(user, categoryId)
        ?: return TaxonomyResult(user, emptyList(), "分类不存在：「$categoryId」。${availableCategoriesText(user)}")
    val parentRef = newParentId?.trim()?.takeIf { it.isNotEmpty() }
    val resolvedParentId = parentRef?.let { resolveMergedCategoryId(user, it) }
    val parent = resolvedParentId?.let { categoryById(user, it) }
    if (parentRef != null && parent == null) {
        return TaxonomyResult(user, emptyList(), "父分类不存在：「$parentRef」。${availableCategoriesText(user)}")
    }
    if (parent != null && parent.id == category.id) {
        return TaxonomyResult(user, emptyList(), "不能把分类移动到它自己下面。")
    }
    // 成环检查：从新父往上走，若撞到被移动的分类就是把自己的祖先挂进自己的子树。
    var cursor: String? = parent?.id
    val seen = HashSet<String>()
    while (cursor != null && seen.add(cursor)) {
        if (cursor == category.id) {
            return TaxonomyResult(user, emptyList(), "不能把分类「${category.name}」移动到它自己的子分类下面。")
        }
        cursor = categoryById(user, cursor)?.parentId
    }
    val newDepth = if (parent != null) categoryDepth(user, parent.id) + 1 else 1
    val subtreeHeight = categorySubtreeHeight(user, category.id)
    if (newDepth + subtreeHeight - 1 > MAX_CATEGORY_DEPTH) {
        return TaxonomyResult(
            user,
            emptyList(),
            "移动后分类树会超过 $MAX_CATEGORY_DEPTH 层（该子树最高 $subtreeHeight 层，新位置第 $newDepth 层）。",
        )
    }
    if (category.parentId == parent?.id) return TaxonomyResult(user, emptyList(), null)

    val categories = user.categories.map {
        if (it.id == category.id) it.copy(parentId = parent?.id) else it
    }
    val targetName = parent?.name ?: "根"
    return TaxonomyResult(
        user.copy(categories = categories),
        listOf("移动分类「${category.name}」到「$targetName」"),
        null,
    )
}

/**
 * 删除分类（设计 §3.3 规则 6、§6.3）：
 * - 子分类上移到被删分类的父级；
 * - 该分类下的概念回到未分类，并为每个概念写 `RemovedAssignment(concept, 分类路径)`；
 * - 写 `RemovedCategory(id, path, now)` 墓碑；
 * - `mergedInto` 里指向被删 id 的旧映射清掉。
 */
internal fun deleteCategory(user: GraphUserData, categoryId: String, now: Long): TaxonomyResult {
    val category = findCategory(user, categoryId)
        ?: return TaxonomyResult(user, emptyList(), "分类不存在：「$categoryId」。${availableCategoriesText(user)}")
    val path = categoryPathOf(user, category.id).ifEmpty { listOf(category.name) }

    val keptAssignments = LinkedHashMap<String, String>()
    val orphaned = ArrayList<RemovedAssignment>()
    for ((concept, categoryRef) in user.assignments.toSortedMap()) {
        val resolved = resolveMergedCategoryId(user, categoryRef)
        if (resolved == category.id) {
            orphaned.add(RemovedAssignment(concept, path))
        } else {
            keptAssignments[concept] = categoryRef
        }
    }
    var removedAssignments = user.removed.assignments
    for (tombstone in orphaned) {
        removedAssignments = appendRemovedAssignment(removedAssignments, tombstone.concept, tombstone.categoryPath)
    }

    val categories = user.categories
        .filterNot { it.id == category.id }
        .map { child ->
            val parentRef = child.parentId
            val underDeleted = parentRef != null &&
                (parentRef == category.id || resolveMergedCategoryId(user, parentRef) == category.id)
            if (underDeleted) child.copy(parentId = category.parentId) else child
        }

    val mergedInto = LinkedHashMap<String, String>()
    for ((from, to) in user.mergedInto.toSortedMap()) {
        if (from == category.id) continue
        if (resolveMergedCategoryId(user, from) == category.id) continue
        if (resolveMergedCategoryId(user, to) == category.id) continue
        mergedInto[from] = to
    }

    val removedCategories = user.removed.categories.filterNot { it.id == category.id } +
        RemovedCategory(category.id, path, now)
    val removed = user.removed.copy(
        assignments = removedAssignments,
        categories = removedCategories,
    )
    val changes = ArrayList<String>()
    changes.add("删除分类「${category.name}」")
    val childCount = user.categories.count { it.parentId == category.id }
    if (childCount > 0) changes.add("$childCount 个子分类上移到「${category.parentId ?: "根"}」")
    if (orphaned.isNotEmpty()) changes.add("${orphaned.size} 个概念回到未分类")
    return TaxonomyResult(
        user.copy(
            categories = categories,
            assignments = keptAssignments.toSortedMap(),
            mergedInto = mergedInto,
            removed = removed,
            updatedAt = now,
        ),
        changes,
        null,
    )
}

/**
 * 合并分类（设计 §3.3 规则 7、§6.3）：[fromId] 并入 [intoId]。
 *
 * - [fromId] 的子分类和概念归属改挂 [intoId]；
 * - `mergedInto[fromId] = intoId`（旧 id 读时解析）；
 * - 写 `RemovedCategory(fromId, 路径, now)` 墓碑，删掉 [fromId] 分类本身。
 */
internal fun mergeCategories(
    user: GraphUserData,
    fromId: String,
    intoId: String,
    now: Long,
): TaxonomyResult {
    val from = findCategory(user, fromId)
        ?: return TaxonomyResult(user, emptyList(), "要合并的分类不存在：「$fromId」。${availableCategoriesText(user)}")
    val into = findCategory(user, intoId)
        ?: return TaxonomyResult(user, emptyList(), "合并目标分类不存在：「$intoId」。${availableCategoriesText(user)}")
    if (from.id == into.id) return TaxonomyResult(user, emptyList(), "不能把分类合并到它自己。")
    // 不能把父分类并进自己的子孙节点（否则子孙的 parent 会指向自己，树就坏了）。
    var cursor: String? = into.parentId
    val seen = HashSet<String>()
    while (cursor != null && seen.add(cursor)) {
        if (cursor == from.id) {
            return TaxonomyResult(user, emptyList(), "不能把分类「${from.name}」合并到它自己的子分类「${into.name}」里。")
        }
        cursor = categoryById(user, cursor)?.parentId
    }

    val path = categoryPathOf(user, from.id).ifEmpty { listOf(from.name) }
    val categories = user.categories
        .filterNot { it.id == from.id }
        .map { child -> if (child.parentId == from.id) child.copy(parentId = into.id) else child }

    val assignments = LinkedHashMap<String, String>()
    var movedAssignments = 0
    for ((concept, categoryRef) in user.assignments.toSortedMap()) {
        val resolved = resolveMergedCategoryId(user, categoryRef)
        if (resolved == from.id) {
            assignments[concept] = into.id
            movedAssignments++
        } else {
            assignments[concept] = categoryRef
        }
    }

    val mergedInto = LinkedHashMap<String, String>()
    for ((oldId, targetId) in user.mergedInto.toSortedMap()) {
        if (oldId == from.id) continue
        val resolvedTarget = resolveMergedCategoryId(user, targetId)
        mergedInto[oldId] = if (resolvedTarget == from.id) into.id else targetId
    }
    mergedInto[from.id] = into.id

    val removedCategories = user.removed.categories.filterNot { it.id == from.id } +
        RemovedCategory(from.id, path, now)
    val removed = user.removed.copy(categories = removedCategories)
    val childrenMoved = user.categories.count { it.parentId == from.id }
    val changes = ArrayList<String>()
    changes.add("合并分类「${from.name}」→「${into.name}」")
    if (childrenMoved > 0) changes.add("$childrenMoved 个子分类改挂到「${into.name}」")
    if (movedAssignments > 0) changes.add("$movedAssignments 个概念归属改挂到「${into.name}」")
    return TaxonomyResult(
        user.copy(
            categories = categories,
            assignments = assignments.toSortedMap(),
            mergedInto = mergedInto.toSortedMap(),
            removed = removed,
            updatedAt = now,
        ),
        changes,
        null,
    )
}

// ---------------------------------------------------------------------------
// 概念归属
// ---------------------------------------------------------------------------

/** 把概念挂到某分类；[categoryId] = null 表示移到未分类并写墓碑（设计 §6.3）。 */
internal fun assignConcept(user: GraphUserData, conceptKey: String, categoryId: String?): TaxonomyResult {
    val identity = conceptIdentityKey(conceptKey)
    if (identity.isEmpty()) return TaxonomyResult(user, emptyList(), "概念标识不能为空。")

    if (categoryId.isNullOrBlank()) {
        val previous = user.assignments[identity]
            ?: user.assignments.entries.firstOrNull { conceptIdentityKey(it.key) == identity }?.value
        if (previous == null) return TaxonomyResult(user, emptyList(), null)
        val path = categoryPathOf(user, previous).ifEmpty { listOf(previous) }
        val remaining = LinkedHashMap<String, String>()
        for ((key, value) in user.assignments.toSortedMap()) {
            if (conceptIdentityKey(key) == identity) continue
            remaining[key] = value
        }
        val removed = user.removed.copy(
            assignments = appendRemovedAssignment(user.removed.assignments, identity, path),
        )
        return TaxonomyResult(
            user.copy(assignments = remaining, removed = removed),
            listOf("概念「$identity」移到未分类"),
            null,
        )
    }

    val category = findCategory(user, categoryId)
        ?: return TaxonomyResult(user, emptyList(), "分类不存在：「$categoryId」。${availableCategoriesText(user)}")
    val path = categoryPathOf(user, category.id).ifEmpty { listOf(category.name) }
    val remaining = LinkedHashMap<String, String>()
    for ((key, value) in user.assignments.toSortedMap()) {
        if (conceptIdentityKey(key) == identity) continue
        remaining[key] = value
    }
    remaining[identity] = category.id
    // 手动归类不受墓碑限制（设计 §6.7「墓碑不是黑名单」）：清掉同组合的忽略记录。
    val removed = user.removed.copy(
        assignments = clearRemovedAssignment(user.removed.assignments, identity, path),
    )
    return TaxonomyResult(
        user.copy(assignments = remaining.toSortedMap(), removed = removed),
        listOf("概念「$identity」归入「${category.name}」"),
        null,
    )
}

/**
 * 批量应用 AI 分类建议（设计 §6.1 的合并阶段，G1 校验后调用）。
 *
 * 每条建议的 `categoryPath` 是「根 → 叶子」的绝对路径：逐级找同名分类，缺的按
 * `origin = AI`、`confirmed = false`、`order = 同级末尾 + 1` 新建；最后把概念挂到叶子。
 * 非法路径（空段 / 名字不合规 / 超过 [MAX_CATEGORY_DEPTH] 层 / 命中 ignored 墓碑）整条跳过并计数，
 * 汇总成 [TaxonomyResult.error]（不用 GraphDiagnostic，Build/UI 看 changes 即可）。
 */
internal fun applyCategorySuggestions(
    user: GraphUserData,
    suggestions: List<CategorySuggestion>,
    now: Long,
): TaxonomyResult {
    if (suggestions.isEmpty()) return TaxonomyResult(user, emptyList(), null)
    var current = user
    var createdCount = 0
    var assignedCount = 0
    var skippedCount = 0

    for (suggestion in suggestions) {
        val identity = conceptIdentityKey(suggestion.conceptKey)
        val rawSegments = suggestion.categoryPath.map { it.trim() }
        val invalidPath = identity.isEmpty() ||
            rawSegments.isEmpty() ||
            rawSegments.size > MAX_CATEGORY_DEPTH ||
            rawSegments.any { it.isEmpty() || it.length > MAX_CATEGORY_NAME_LENGTH }
        if (invalidPath) {
            skippedCount++
            continue
        }
        val segments = rawSegments.map { normalizeCategoryName(it) }
        if (isRemovedAssignment(user.removed, identity, segments)) {
            skippedCount++
            continue
        }
        // 先模拟一遍深度：路径里已存在的分类按真实深度算，缺的按父深度 + 1 递增，
        // 超深就整条跳过（不留下建了一半的分类枝）。
        var simulatedParentId: String? = null
        var simulatedDepth = 0
        var pathValid = true
        for (segment in segments) {
            simulatedDepth = if (simulatedParentId == null) {
                1
            } else {
                categoryDepth(current, simulatedParentId) + 1
            }
            val existing = current.categories.firstOrNull {
                it.parentId == simulatedParentId && categoryNameKey(it.name) == categoryNameKey(segment)
            }
            if (existing != null) {
                simulatedParentId = existing.id
                simulatedDepth = categoryDepth(current, existing.id)
            } else if (simulatedDepth > MAX_CATEGORY_DEPTH) {
                pathValid = false
                break
            } else {
                simulatedParentId = null
            }
            if (simulatedDepth > MAX_CATEGORY_DEPTH) {
                pathValid = false
                break
            }
        }
        if (!pathValid) {
            skippedCount++
            continue
        }

        var parentId: String? = null
        var insertionFailed = false
        for (segment in segments) {
            val existing = current.categories.firstOrNull {
                it.parentId == parentId && categoryNameKey(it.name) == categoryNameKey(segment)
            }
            if (existing != null) {
                parentId = existing.id
                continue
            }
            val insertion = insertCategory(
                user = current,
                name = segment,
                parentId = parentId,
                now = now,
                requestedId = null,
                origin = NodeOrigin.AI,
                confirmed = false,
            )
            if (insertion.error != null || insertion.categoryId == null) {
                insertionFailed = true
                break
            }
            current = insertion.user
            createdCount++
            parentId = insertion.categoryId
        }
        if (insertionFailed || parentId == null) {
            skippedCount++
            continue
        }
        current = putAssignment(current, identity, parentId)
        assignedCount++
    }

    if (createdCount == 0 && assignedCount == 0 && skippedCount == 0) {
        return TaxonomyResult(user, emptyList(), null)
    }
    val changes = ArrayList<String>()
    if (createdCount > 0) changes.add("按 AI 建议新建 $createdCount 个分类")
    if (assignedCount > 0) changes.add("按 AI 建议归类 $assignedCount 个概念")
    if (skippedCount > 0) changes.add("跳过 $skippedCount 条分类建议")
    val error = if (skippedCount > 0) {
        "$skippedCount 条 AI 分类建议路径非法或命中忽略记录，已跳过。"
    } else {
        null
    }
    val updated = current.copy(updatedAt = if (createdCount > 0 || assignedCount > 0) now else current.updatedAt)
    return TaxonomyResult(updated, changes, error)
}

// ---------------------------------------------------------------------------
// 校验 / 修复
// ---------------------------------------------------------------------------

/**
 * 校验并修复分类树（设计 §3.3、§10.1）。**只改内存，不写盘**。
 *
 * 返回的 [TaxonomyValidation.user] 保证结构合法、可直接交给
 * [KnowledgeGraphStore.saveUser]；修复过程在 [TaxonomyValidation.diagnostics] 里说明。
 *
 * 修复项：
 * - 父分类不存在 → 挂到根（`ParentMissing`）；
 * - 成环 → 断开成根（`Cycle`）；
 * - 深度超过 [MAX_CATEGORY_DEPTH] → 上移到第 [MAX_CATEGORY_DEPTH] 层（`TooDeep`）；
 * - 同父同名 → 保留先出现者，后来的改名加「·2」（`DuplicateSibling`）；
 * - 归属指向不存在的分类 → 删除归属（`InvalidAssignment`）；
 * - `mergedInto` 做传递闭包并防环。
 */
internal fun validateTaxonomy(user: GraphUserData): TaxonomyValidation {
    val diagnostics = ArrayList<GraphDiagnostic>()

    // 1. mergedInto 闭包（防环）。assignments / parentId 都按闭包解析。
    val closure = mergedClosure(user.mergedInto)

    // 2. 分类本体清理：id 去空去重、名字规范化、确认态自洽。
    val seenIds = HashSet<String>()
    val categories = ArrayList<GraphCategory>()
    for (rawCategory in user.categories) {
        val id = rawCategory.id.trim()
        if (id.isEmpty()) continue
        if (!seenIds.add(id)) continue
        var name = normalizeCategoryName(rawCategory.name).take(MAX_CATEGORY_NAME_LENGTH)
        if (name.isEmpty()) name = "未命名分类"
        val description = rawCategory.description?.trim()?.take(MAX_CATEGORY_NAME_LENGTH)
            ?.takeIf { it.isNotEmpty() }
        val confirmed = if (rawCategory.origin == NodeOrigin.AI) rawCategory.confirmed else true
        categories.add(
            rawCategory.copy(
                id = id,
                name = name,
                parentId = rawCategory.parentId?.trim()?.takeIf { it.isNotEmpty() },
                description = description,
                confirmed = confirmed,
            ),
        )
    }
    val categoriesById = LinkedHashMap<String, GraphCategory>()
    for (category in categories) categoriesById[category.id] = category

    // 3. parentId 先按 mergedInto 解析，再检查存在性。
    val parents = LinkedHashMap<String, String?>()
    for (category in categories) {
        val rawParent = category.parentId
        if (rawParent == null) {
            parents[category.id] = null
            continue
        }
        val resolved = closure[rawParent] ?: rawParent
        when {
            !categoriesById.containsKey(resolved) -> {
                parents[category.id] = null
                diagnostics.add(
                    GraphDiagnostic(
                        DiagnosticKind.ParentMissing,
                        category.id,
                        "分类「${category.name}」的父分类「$rawParent」不存在，已挂到根。",
                    ),
                )
            }
            resolved == category.id -> {
                parents[category.id] = null
                diagnostics.add(
                    GraphDiagnostic(
                        DiagnosticKind.Cycle,
                        category.id,
                        "分类「${category.name}」的父分类是它自己，已断开成根。",
                    ),
                )
            }
            else -> parents[category.id] = resolved
        }
    }

    // 4. 成环修复：按分类顺序走父链，第一次回归就把它断成根。
    val clearedParents = HashSet<String>()
    for (category in categories) {
        if (category.id in clearedParents) continue
        val path = ArrayList<String>()
        val indexOf = HashMap<String, Int>()
        var cursor: String? = category.id
        while (cursor != null) {
            val repeatedIndex = indexOf[cursor]
            if (repeatedIndex != null) {
                val cycleRoot = path[repeatedIndex]
                parents[cycleRoot] = null
                clearedParents.add(cycleRoot)
                val cycleName = categoriesById[cycleRoot]?.name ?: cycleRoot
                diagnostics.add(
                    GraphDiagnostic(
                        DiagnosticKind.Cycle,
                        cycleRoot,
                        "分类「$cycleName」处于父分类环里，已断开成根。",
                    ),
                )
                break
            }
            indexOf[cursor] = path.size
            path.add(cursor)
            cursor = parents[cursor]
        }
        clearedParents.addAll(path)
    }

    // 5. 超深修复：深度 > 上限的节点上移到第 MAX_CATEGORY_DEPTH 层（父链截到第 4 层的祖先）。
    var depthLimitStable = false
    var guard = 0
    while (!depthLimitStable && guard <= MAX_CATEGORY_DEPTH * 2 + 4) {
        guard++
        val depths = computeDepths(categoriesById, parents)
        val tooDeep = categories.filter { (depths[it.id] ?: 1) > MAX_CATEGORY_DEPTH }
        if (tooDeep.isEmpty()) {
            depthLimitStable = true
            break
        }
        if (guard > MAX_CATEGORY_DEPTH * 2) {
            // 理论上到不了；兜底全部断成根，保证返回的树一定合法。
            for (category in tooDeep) parents[category.id] = null
            break
        }
        for (category in tooDeep.sortedBy { it.id }) {
            val currentDepth = depths[category.id] ?: 1
            var cursor = parents[category.id]
            var remaining = currentDepth - MAX_CATEGORY_DEPTH
            while (remaining > 0 && cursor != null) {
                cursor = parents[cursor]
                remaining--
            }
            parents[category.id] = if (cursor != null && cursor != category.id) cursor else null
            diagnostics.add(
                GraphDiagnostic(
                    DiagnosticKind.TooDeep,
                    category.id,
                    "分类「${category.name}」超过 $MAX_CATEGORY_DEPTH 层，已上移到第 $MAX_CATEGORY_DEPTH 层。",
                ),
            )
        }
    }

    // 6. 同父同名：保留先出现者，后来的改名加「·2」「·3」…。
    val usedNames = HashMap<String?, HashSet<String>>()
    for (index in categories.indices) {
        val category = categories[index]
        val parent = parents[category.id]
        val used = usedNames.getOrPut(parent) { HashSet() }
        if (used.add(categoryNameKey(category.name))) continue
        var attempt = 2
        var newName: String
        do {
            val suffix = "·$attempt"
            val head = category.name
                .take((MAX_CATEGORY_NAME_LENGTH - suffix.length).coerceAtLeast(1))
                .trimEnd()
            newName = (head + suffix).take(MAX_CATEGORY_NAME_LENGTH)
            attempt++
        } while (!used.add(categoryNameKey(newName)) && attempt <= 1000)
        categories[index] = category.copy(name = newName)
        diagnostics.add(
            GraphDiagnostic(
                DiagnosticKind.DuplicateSibling,
                category.id,
                "同父分类下有重名「${category.name}」，已改名为「$newName」。",
            ),
        )
    }

    // 7. 归属：key 规范成 conceptIdentityKey、值按 mergedInto 解析；指向不存在的分类就删掉。
    val validCategoryIds = categories.mapTo(HashSet()) { it.id }
    val assignments = LinkedHashMap<String, String>()
    for ((rawKey, rawValue) in user.assignments.toSortedMap()) {
        val identity = conceptIdentityKey(rawKey)
        val rawCategoryId = rawValue.trim()
        if (identity.isEmpty() || rawCategoryId.isEmpty()) {
            diagnostics.add(
                GraphDiagnostic(
                    DiagnosticKind.InvalidAssignment,
                    rawKey,
                    "归属「$rawKey」→「$rawValue」不完整，已删除。",
                ),
            )
            continue
        }
        val resolved = closure[rawCategoryId] ?: rawCategoryId
        if (!validCategoryIds.contains(resolved)) {
            diagnostics.add(
                GraphDiagnostic(
                    DiagnosticKind.InvalidAssignment,
                    identity,
                    "概念「$identity」的归属分类「$rawValue」不存在，已删归属（进未分类）。",
                ),
            )
            continue
        }
        if (!assignments.containsKey(identity)) assignments[identity] = resolved
    }

    // 8. mergedInto 闭包清理：去掉指向不存在分类 / 指向自己的条目，键也规范成已有旧 id。
    val cleanedMergedInto = LinkedHashMap<String, String>()
    for ((oldId, targetId) in closure.toSortedMap()) {
        if (oldId.isBlank() || oldId == targetId) continue
        if (validCategoryIds.contains(oldId)) continue
        if (!validCategoryIds.contains(targetId)) continue
        cleanedMergedInto[oldId] = targetId
    }

    val repaired = ArrayList<GraphCategory>(categories.size)
    for (category in categories) {
        repaired.add(category.copy(parentId = parents[category.id]))
    }
    val newUser = user.copy(
        categories = repaired,
        assignments = assignments.toSortedMap(),
        mergedInto = cleanedMergedInto,
    )
    return TaxonomyValidation(newUser, diagnostics)
}

/** 只要诊断、不要修复后的 user 时的便捷入口（页面接线用）；语义同 [validateTaxonomy]。 */
internal fun validateTaxonomyDiagnostics(user: GraphUserData): List<GraphDiagnostic> =
    validateTaxonomy(user).diagnostics

// ---------------------------------------------------------------------------
// 内部工具
// ---------------------------------------------------------------------------

/** `mergedInto` 传递闭包：A→B、B→C 得到 A→C、B→C；成环的链整条丢弃。 */
private fun mergedClosure(raw: Map<String, String>): Map<String, String> {
    val filtered = LinkedHashMap<String, String>()
    for ((key, value) in raw.toSortedMap()) {
        val from = key.trim()
        val to = value.trim()
        if (from.isEmpty() || to.isEmpty() || from == to) continue
        filtered[from] = to
    }
    val resolved = HashMap<String, String>()
    for (start in filtered.keys.sorted()) {
        if (resolved.containsKey(start)) continue
        val path = ArrayList<String>()
        val indexOf = HashMap<String, Int>()
        var cursor = start
        var finalId: String? = null
        while (true) {
            if (!filtered.containsKey(cursor)) {
                finalId = cursor
                break
            }
            if (indexOf.containsKey(cursor)) break
            indexOf[cursor] = path.size
            path.add(cursor)
            cursor = filtered.getValue(cursor)
        }
        if (finalId != null) {
            for (id in path) resolved[id] = finalId
        }
    }
    return resolved.toSortedMap()
}

/** 按当前 parent 表算每个分类的深度（根 = 1）；结构已无环，仍带保护。 */
private fun computeDepths(
    categoriesById: Map<String, GraphCategory>,
    parents: Map<String, String?>,
): Map<String, Int> {
    val depths = HashMap<String, Int>()
    for (categoryId in categoriesById.keys) {
        val path = ArrayList<String>()
        var cursor: String? = categoryId
        var depth = 0
        while (cursor != null && !depths.containsKey(cursor) && path.size <= MAX_CATEGORY_DEPTH + 2) {
            path.add(cursor)
            depth++
            cursor = parents[cursor]
        }
        if (cursor != null) depth += depths[cursor] ?: 0
        for (index in path.indices) {
            depths[path[index]] = depth - index
        }
    }
    return depths
}

/**
 * 给概念挂归属的共用实现：清掉同一身份键的其他写法，写入规范身份键。
 * 不写墓碑、不改 updatedAt（由调用方决定）。
 */
private fun putAssignment(user: GraphUserData, identity: String, categoryId: String): GraphUserData {
    val assignments = LinkedHashMap<String, String>()
    for ((key, value) in user.assignments.toSortedMap()) {
        if (conceptIdentityKey(key) == identity) continue
        assignments[key] = value
    }
    assignments[identity] = categoryId
    return user.copy(assignments = assignments.toSortedMap())
}

/** 追加一条忽略归属墓碑（同一概念 + 同一路径去重）。 */
private fun appendRemovedAssignment(
    existing: List<RemovedAssignment>,
    concept: String,
    path: List<String>,
): List<RemovedAssignment> {
    val identity = conceptIdentityKey(concept)
    val pathKey = conceptPathKey(path)
    val kept = existing.filterNot {
        conceptIdentityKey(it.concept) == identity && conceptPathKey(it.categoryPath) == pathKey
    }
    return kept + RemovedAssignment(concept, path)
}

/** 清掉某概念指向某路径的忽略墓碑（用户手动归类时）。 */
private fun clearRemovedAssignment(
    existing: List<RemovedAssignment>,
    concept: String,
    path: List<String>,
): List<RemovedAssignment> {
    val identity = conceptIdentityKey(concept)
    val pathKey = conceptPathKey(path)
    return existing.filterNot {
        conceptIdentityKey(it.concept) == identity && conceptPathKey(it.categoryPath) == pathKey
    }
}

/** 判断某概念 + 某分类路径是否已被用户忽略（AI 不再原样建议，设计 §6.7）。 */
private fun isRemovedAssignment(
    removals: GraphRemovals,
    concept: String,
    path: List<String>,
): Boolean {
    val identity = conceptIdentityKey(concept)
    val pathKey = conceptPathKey(path)
    return removals.assignments.any {
        conceptIdentityKey(it.concept) == identity && conceptPathKey(it.categoryPath) == pathKey
    }
}
