package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分类树的纯函数单测（设计 §3.3、§6.3、§6.7、§10.1）。
 *
 * 只构造 [GraphUserData] 内存对象，不碰磁盘、不取时钟：所有时间都由测试传入，
 * 这样才能断言「同输入两次结果逐位相等」。
 */
class KnowledgeGraphTaxonomyTest {

    private val now: Long = 1_700_000_000_000L

    private fun category(
        id: String,
        name: String,
        parentId: String? = null,
        order: Int = 0,
    ): GraphCategory = GraphCategory(id = id, name = name, parentId = parentId, order = order)

    private fun user(
        categories: List<GraphCategory> = emptyList(),
        assignments: Map<String, String> = emptyMap(),
        relations: List<GraphUserRelation> = emptyList(),
        removed: GraphRemovals = GraphRemovals.Empty,
        mergedInto: Map<String, String> = emptyMap(),
    ): GraphUserData = GraphUserData(
        updatedAt = 0L,
        categories = categories,
        assignments = assignments,
        relations = relations,
        removed = removed,
        mergedInto = mergedInto,
    )

    private fun created(result: TaxonomyResult, name: String): GraphCategory {
        assertNull(result.error, "创建「$name」不应失败：${result.error}")
        val matches = result.user.categories.filter { it.name == name }
        assertEquals(1, matches.size, "应当恰好有 1 个「$name」分类")
        return matches.single()
    }

    private fun prepareTree(): GraphUserData {
        var current = user()
        current = createCategory(current, "A", null, now).user
        val a = current.categories.single { it.name == "A" }
        current = createCategory(current, "B", a.id, now).user
        return current
    }

    // ---- 创建 ----

    @Test
    fun createsRootAndChildCategory() {
        val rootResult = createCategory(user(), "检索", null, now)
        assertNull(rootResult.error)
        assertEquals(1, rootResult.user.categories.size)
        val root = rootResult.user.categories.single()
        assertEquals("检索", root.name)
        assertNull(root.parentId)
        assertEquals(NodeOrigin.USER, root.origin)
        assertTrue(root.confirmed)
        assertEquals(0, root.order)
        assertEquals(now, rootResult.user.updatedAt)
        assertTrue(Regex("c_[0-9a-f]{8}").matches(root.id), "id 形状应为 c_ + 8 位 hex：${root.id}")
        assertTrue(rootResult.changes.isNotEmpty())

        val childResult = createCategory(rootResult.user, "向量检索", root.id, now)
        val child = created(childResult, "向量检索")
        assertEquals(root.id, child.parentId)

        // 同级末尾：第二个子分类 order = 已有最大 order + 1
        val secondChild = createCategory(childResult.user, "生成", root.id, now)
        assertEquals(1, created(secondChild, "生成").order)
    }

    @Test
    fun createRejectsBlankName() {
        for (blank in listOf("", "   ", "\t\n")) {
            val result = createCategory(user(), blank, null, now)
            assertNotNull(result.error, "空名「$blank」应当被拒绝")
            assertTrue(result.user.categories.isEmpty())
        }
    }

    @Test
    fun createRejectsTooLongName() {
        val result = createCategory(user(), "分".repeat(MAX_CATEGORY_NAME_LENGTH + 1), null, now)
        assertNotNull(result.error)
        assertTrue(result.user.categories.isEmpty())
        // 恰好 40 字可以
        val boundary = createCategory(user(), "分".repeat(MAX_CATEGORY_NAME_LENGTH), null, now)
        assertNull(boundary.error)
        assertEquals(1, boundary.user.categories.size)
    }

    @Test
    fun rejectsDuplicateSiblingName() {
        val first = createCategory(user(), "检索", null, now)
        // trim + 忽略大小写空白后同名
        val second = createCategory(first.user, "  检索 ", null, now)
        assertNotNull(second.error)
        assertEquals(1, second.user.categories.size)
    }

    @Test
    fun allowsSameNameUnderDifferentParents() {
        var current = user()
        current = createCategory(current, "领域A", null, now).user
        current = createCategory(current, "领域B", null, now).user
        val a = current.categories.single { it.name == "领域A" }
        val b = current.categories.single { it.name == "领域B" }

        val childA = createCategory(current, "细节", a.id, now)
        assertNull(childA.error)
        val childB = createCategory(childA.user, "细节", b.id, now)
        assertNull(childB.error)
        assertEquals(2, childB.user.categories.count { it.name == "细节" })
    }

    @Test
    fun createRejectsMissingParentAndListsAvailableCategories() {
        val withRoot = createCategory(user(), "检索", null, now).user
        // 传一个「概念」的 key（设计 §10.1 的 rejectsParentThatIsConcept）
        val result = createCategory(withRoot, "子", "概念-向量检索", now)
        assertNotNull(result.error)
        assertTrue(result.error.contains("可用分类"), "错误文案要能指导修改：${result.error}")
        assertEquals(1, result.user.categories.size)
    }

    @Test
    fun createRejectsExplicitDuplicateId() {
        val first = createCategory(user(), "A", null, now, id = "c_aaaaaaaa")
        assertNull(first.error)
        val second = createCategory(first.user, "B", null, now, id = "c_aaaaaaaa")
        assertNotNull(second.error)
        assertEquals(1, second.user.categories.size)
    }

    @Test
    fun createRespectsExplicitId() {
        val result = createCategory(user(), "A", null, now, id = "c_1234abcd")
        assertEquals("c_1234abcd", created(result, "A").id)
    }

    // ---- 改名 / 移动 ----

    @Test
    fun renameKeepsIdAndAssignments() {
        var current = createCategory(user(), "检索", null, now).user
        val id = current.categories.single().id
        current = assignConcept(current, "向量检索", id).user

        val renamed = renameCategory(current, id, " 检索与生成 ")
        assertNull(renamed.error)
        assertEquals(id, renamed.user.categories.single().id)
        assertEquals("检索与生成", renamed.user.categories.single().name)
        assertEquals(id, renamed.user.assignments["向量检索"])
        assertTrue(renamed.changes.isNotEmpty())
    }

    @Test
    fun renameRejectsDuplicateSiblingName() {
        var current = createCategory(user(), "A", null, now).user
        current = createCategory(current, "B", null, now).user
        val b = current.categories.single { it.name == "B" }

        val result = renameCategory(current, b.id, "a")
        assertNotNull(result.error)
        assertEquals("B", result.user.categories.single { it.id == b.id }.name)
    }

    @Test
    fun moveRejectsCycle() {
        var current = createCategory(user(), "A", null, now).user
        val root = current.categories.single()
        current = createCategory(current, "B", root.id, now).user
        val b = current.categories.single { it.name == "B" }

        // 把 A 移到自己的子分类 B 下 → 成环
        val result = moveCategory(current, root.id, b.id)
        assertNotNull(result.error)
        assertEquals(2, result.user.categories.size)
        assertNull(result.user.categories.single { it.id == root.id }.parentId)
    }

    @Test
    fun moveRejectsTooDeep() {
        var current = user()
        var parentId: String? = null
        for (name in listOf("A", "B", "C", "D", "E")) {
            val result = createCategory(current, name, parentId, now)
            assertNull(result.error)
            current = result.user
            parentId = result.user.categories.single { it.name == name }.id
        }
        current = createCategory(current, "X", null, now).user
        val e = current.categories.single { it.name == "E" }
        val x = current.categories.single { it.name == "X" }

        // X 移到第 5 层的 E 下 → 会到第 6 层，拒绝
        val result = moveCategory(current, x.id, e.id)
        assertNotNull(result.error)
        assertTrue(result.error.contains("层"), "错误文案应说明层数：${result.error}")
    }

    @Test
    fun moveReparentsCategory() {
        val current = prepareTree()
        val b = current.categories.single { it.name == "B" }
        assertEquals(current.categories.single { it.name == "A" }.id, b.parentId)

        val result = moveCategory(current, b.id, null)
        assertNull(result.error)
        assertNull(result.user.categories.single { it.id == b.id }.parentId)
    }

    @Test
    fun moveToMetParentIsNoOp() {
        val current = prepareTree()
        val b = current.categories.single { it.name == "B" }
        val result = moveCategory(current, b.id, b.parentId)
        assertNull(result.error)
        assertEquals(current.categories, result.user.categories)
    }

    // ---- 删除 / 合并 ----

    @Test
    fun deleteMovesChildrenUpAndConceptsHome() {
        var current = createCategory(user(), "检索与生成", null, now).user
        val root = current.categories.single()
        current = createCategory(current, "向量检索", root.id, now).user
        current = assignConcept(current, "向量检索", root.id).user

        val result = deleteCategory(current, root.id, now + 5)
        assertNull(result.error)
        assertEquals(1, result.user.categories.size)
        assertNull(result.user.categories.single().parentId, "子分类应上移到根")
        assertTrue(result.user.assignments.isEmpty(), "概念应回到未分类")

        val assignmentTombstone = result.user.removed.assignments.single()
        assertEquals("向量检索", assignmentTombstone.concept)
        assertEquals(listOf("检索与生成"), assignmentTombstone.categoryPath)

        val categoryTombstone = result.user.removed.categories.single()
        assertEquals(root.id, categoryTombstone.id)
        assertEquals(listOf("检索与生成"), categoryTombstone.path)
        assertEquals(now + 5, categoryTombstone.at)
        assertEquals(now + 5, result.user.updatedAt)
    }

    @Test
    fun deleteClearsMergedIntoEntries() {
        var current = createCategory(user(), "目标", null, now).user
        val target = current.categories.single()
        current = current.copy(mergedInto = mapOf("c_old0001" to target.id))

        val result = deleteCategory(current, target.id, now)
        assertNull(result.error)
        assertTrue(result.user.mergedInto.isEmpty(), "指向被删分类的旧 id 映射要清掉")
    }

    @Test
    fun mergeRewritesAssignmentsAndMergedInto() {
        var current = createCategory(user(), "旧", null, now).user
        val from = current.categories.single()
        current = createCategory(current, "新", null, now).user
        val into = current.categories.single { it.name == "新" }
        current = createCategory(current, "子", from.id, now).user
        current = assignConcept(current, "向量检索", from.id).user

        val result = mergeCategories(current, from.id, into.id, now + 9)
        assertNull(result.error)
        assertEquals(2, result.user.categories.size, "旧分类应被删掉，子分类保留")
        assertEquals(into.id, result.user.mergedInto[from.id])
        assertEquals(into.id, result.user.assignments["向量检索"])
        assertEquals(into.id, result.user.categories.single { it.name == "子" }.parentId)
        assertTrue(result.user.removed.categories.any { it.id == from.id })
    }

    @Test
    fun mergeRejectsSelfMissingAndDescendant() {
        var current = createCategory(user(), "A", null, now).user
        val a = current.categories.single()
        current = createCategory(current, "B", a.id, now).user
        val b = current.categories.single { it.name == "B" }

        assertNotNull(mergeCategories(current, a.id, a.id, now).error)
        assertNotNull(mergeCategories(current, a.id, "c_missing0", now).error)
        assertNotNull(mergeCategories(current, "c_missing0", a.id, now).error)
        // 把父分类并进自己的子孙：拒绝
        assertNotNull(mergeCategories(current, a.id, b.id, now).error)
    }

    // ---- 概念归属 ----

    @Test
    fun assignNullWritesTombstoneAndMovesToUnclassified() {
        var current = createCategory(user(), "检索与生成", null, now).user
        val root = current.categories.single()
        current = assignConcept(current, "向量检索", root.id).user
        assertEquals(root.id, current.assignments["向量检索"])

        val result = assignConcept(current, "向量检索", null)
        assertNull(result.error)
        assertTrue(result.user.assignments.isEmpty())
        val tombstone = result.user.removed.assignments.single()
        assertEquals("向量检索", tombstone.concept)
        assertEquals(listOf("检索与生成"), tombstone.categoryPath)
    }

    @Test
    fun assignRejectsUnknownCategory() {
        val current = createCategory(user(), "A", null, now).user
        val result = assignConcept(current, "向量检索", "c_missing0")
        assertNotNull(result.error)
        assertTrue(result.user.assignments.isEmpty())
    }

    @Test
    fun manualAssignClearsMatchingTombstone() {
        var current = createCategory(user(), "A", null, now).user
        val a = current.categories.single()
        current = assignConcept(current, "x", a.id).user
        current = assignConcept(current, "x", null).user
        assertEquals(1, current.removed.assignments.size)

        val back = assignConcept(current, "x", a.id)
        assertNull(back.error)
        assertEquals(a.id, back.user.assignments["x"])
        assertTrue(back.user.removed.assignments.isEmpty(), "墓碑不是黑名单，手动归类应清掉忽略记录")
    }

    // ---- AI 分类建议 ----

    @Test
    fun applySuggestionsCreatesMissingCategories() {
        val suggestion = CategorySuggestion(
            conceptKey = "向量检索",
            categoryPath = listOf("检索与生成", "向量检索"),
        )
        val result = applyCategorySuggestions(GraphUserData.Empty, listOf(suggestion), now)
        assertNull(result.error)

        val root = result.user.categories.single { it.name == "检索与生成" }
        val child = result.user.categories.single { it.name == "向量检索" }
        assertEquals(root.id, child.parentId)
        assertEquals(NodeOrigin.AI, root.origin)
        assertFalse(root.confirmed)
        assertEquals(NodeOrigin.AI, child.origin)
        assertFalse(child.confirmed)
        assertEquals(child.id, result.user.assignments["向量检索"])
        assertTrue(result.changes.isNotEmpty())
        assertEquals(now, result.user.updatedAt)
    }

    @Test
    fun applySuggestionsReusesExistingPathAndAppendsOrder() {
        val first = applyCategorySuggestions(
            GraphUserData.Empty,
            listOf(CategorySuggestion("向量检索", listOf("检索与生成", "向量检索"))),
            now,
        )
        val second = applyCategorySuggestions(
            first.user,
            listOf(CategorySuggestion("文本分块", listOf("检索与生成", "文本分块"))),
            now,
        )
        assertNull(second.error)
        assertEquals(3, second.user.categories.size, "已存在的路径不应重复建分类")
        val root = second.user.categories.single { it.name == "检索与生成" }
        val chunks = second.user.categories.single { it.name == "文本分块" }
        assertEquals(root.id, chunks.parentId)
        assertEquals(1, chunks.order, "同级末尾 + 1")
    }

    @Test
    fun applySuggestionsSkipsInvalidPaths() {
        val suggestions = listOf(
            CategorySuggestion("a", emptyList()),
            CategorySuggestion("b", listOf("", "x")),
            CategorySuggestion("c", listOf("分".repeat(MAX_CATEGORY_NAME_LENGTH + 1))),
        )
        val result = applyCategorySuggestions(GraphUserData.Empty, suggestions, now)
        assertNotNull(result.error)
        assertTrue(result.error.contains("3"), "应汇总跳过条数：${result.error}")
        assertTrue(result.user.categories.isEmpty())
        assertTrue(result.user.assignments.isEmpty())
    }

    @Test
    fun applySuggestionsSkipsTombstonedAssignment() {
        val tombstoned = GraphRemovals(
            assignments = listOf(
                RemovedAssignment("向量检索", listOf("检索与生成", "向量检索")),
            ),
        )
        val current = GraphUserData(removed = tombstoned)
        val result = applyCategorySuggestions(
            current,
            listOf(CategorySuggestion("向量检索", listOf("检索与生成", "向量检索"))),
            now,
        )
        assertNotNull(result.error)
        assertTrue(result.user.categories.isEmpty(), "命中忽略记录的路径不应建分类")
        assertTrue(result.user.assignments.isEmpty())
    }

    // ---- 校验 / 修复 ----

    @Test
    fun validateFixesMissingParent() {
        val current = user(categories = listOf(category("c_a", "A", parentId = "c_missing")))
        val validation = validateTaxonomy(current)
        assertEquals(1, validation.user.categories.size)
        assertNull(validation.user.categories.single().parentId)
        assertTrue(validation.diagnostics.any { it.kind == DiagnosticKind.ParentMissing })
    }

    @Test
    fun validateBreaksCycle() {
        val current = user(
            categories = listOf(
                category("c_a", "A", parentId = "c_b"),
                category("c_b", "B", parentId = "c_a"),
            ),
        )
        val validation = validateTaxonomy(current)
        assertTrue(validation.diagnostics.any { it.kind == DiagnosticKind.Cycle })
        assertEquals(1, validation.user.categories.count { it.parentId == null })

        // 修复后每条父链都必须走到根，不能绕回自己
        val byId = validation.user.categories.associateBy { it.id }
        for (category in validation.user.categories) {
            var cursor = category.parentId
            val seen = HashSet<String>()
            while (cursor != null) {
                assertTrue(seen.add(cursor), "父链仍然成环")
                cursor = byId[cursor]?.parentId
            }
        }
    }

    @Test
    fun validateEnforcesMaxDepth() {
        val categories = ArrayList<GraphCategory>()
        var parentId: String? = null
        for (index in 1..7) {
            val id = "c_d$index"
            categories.add(category(id, "L$index", parentId))
            parentId = id
        }
        val validation = validateTaxonomy(user(categories = categories))
        assertTrue(validation.diagnostics.any { it.kind == DiagnosticKind.TooDeep })

        val byId = validation.user.categories.associateBy { it.id }
        for (category in validation.user.categories) {
            var depth = 1
            var cursor = category.parentId
            val seen = HashSet<String>()
            while (cursor != null) {
                assertTrue(seen.add(cursor), "父链成环")
                depth++
                cursor = byId[cursor]?.parentId
            }
            assertTrue(depth <= MAX_CATEGORY_DEPTH, "「${category.name}」深度 $depth 超过上限")
        }
    }

    @Test
    fun validateRenamesDuplicateSiblings() {
        val current = user(categories = listOf(category("c_1", "检索"), category("c_2", "检索")))
        val validation = validateTaxonomy(current)
        assertTrue(validation.diagnostics.any { it.kind == DiagnosticKind.DuplicateSibling })
        val names = validation.user.categories.map { it.name }
        assertEquals(2, names.toSet().size)
        assertTrue(names.contains("检索"))
        assertTrue(names.any { it.startsWith("检索·2") }, "后来的重名应加「·2」：$names")
    }

    @Test
    fun validateAllowsSameNameUnderDifferentParents() {
        val current = user(
            categories = listOf(
                category("c_a", "领域A"),
                category("c_b", "领域B"),
                category("c_a1", "细节", "c_a"),
                category("c_b1", "细节", "c_b"),
            ),
        )
        val validation = validateTaxonomy(current)
        assertTrue(validation.diagnostics.isEmpty(), "不同父下同名是合法的")
        assertEquals(2, validation.user.categories.count { it.name == "细节" })
    }

    @Test
    fun validateDropsInvalidAssignment() {
        val current = user(
            categories = listOf(category("c_a", "A")),
            assignments = mapOf("向量检索" to "c_missing"),
        )
        val validation = validateTaxonomy(current)
        assertTrue(validation.user.assignments.isEmpty())
        assertTrue(validation.diagnostics.any { it.kind == DiagnosticKind.InvalidAssignment })
    }

    @Test
    fun validateResolvesMergedAssignmentsTransitively() {
        val current = user(
            categories = listOf(category("c_new", "新")),
            assignments = mapOf("向量检索" to "c_old1"),
            mergedInto = mapOf("c_old1" to "c_old2", "c_old2" to "c_new"),
        )
        val validation = validateTaxonomy(current)
        assertEquals("c_new", validation.user.assignments["向量检索"])
        assertEquals("c_new", validation.user.mergedInto["c_old1"])
        assertEquals("c_new", validation.user.mergedInto["c_old2"])
        assertEquals("c_new", resolveMergedCategoryId(validation.user, "c_old1"))
    }

    @Test
    fun resolveMergedFollowsChainAndBreaksCycles() {
        val chain = GraphUserData(mergedInto = mapOf("c_1" to "c_2", "c_2" to "c_3"))
        assertEquals("c_3", resolveMergedCategoryId(chain, "c_1"))

        val cycle = GraphUserData(mergedInto = mapOf("c_1" to "c_2", "c_2" to "c_1"))
        assertTrue(resolveMergedCategoryId(cycle, "c_1") in setOf("c_1", "c_2"))
        assertTrue(resolveMergedCategoryId(cycle, "c_2") in setOf("c_1", "c_2"))
    }

    @Test
    fun validateDropsMergedIntoCycles() {
        val current = GraphUserData(mergedInto = mapOf("c_1" to "c_2", "c_2" to "c_1"))
        val validation = validateTaxonomy(current)
        assertTrue(validation.user.mergedInto.isEmpty())
    }

    @Test
    fun categoryPathFollowsParentsAndHandlesNull() {
        val current = user(
            categories = listOf(
                category("c_root", "领域"),
                category("c_child", "子领域", "c_root"),
                category("c_leaf", "概念组", "c_child"),
            ),
        )
        assertEquals(listOf("领域", "子领域", "概念组"), categoryPathOf(current, "c_leaf"))
        assertEquals(emptyList(), categoryPathOf(current, null))
        assertEquals(listOf("领域"), categoryPathOf(current, "c_root"))
    }

    // ---- 确定性 ----

    @Test
    fun taxonomyOperationsAreDeterministic() {
        val suggestions = listOf(
            CategorySuggestion("向量检索", listOf("检索与生成", "向量检索")),
            CategorySuggestion("文本分块", listOf("检索与生成", "文本分块")),
            CategorySuggestion("RAG", listOf("检索与生成", "生成", "RAG")),
        )
        val first = applyCategorySuggestions(GraphUserData.Empty, suggestions, now)
        val second = applyCategorySuggestions(GraphUserData.Empty, suggestions, now)
        assertEquals(first.user, second.user)
        assertEquals(first.changes, second.changes)
        assertEquals(first.error, second.error)
        assertEquals(
            first.user.categories.map { it.id },
            second.user.categories.map { it.id },
            "同一 now 与名字必须生成同一批 id",
        )

        val validationA = validateTaxonomy(first.user)
        val validationB = validateTaxonomy(first.user)
        assertEquals(validationA.user, validationB.user)
        assertEquals(validationA.diagnostics, validationB.diagnostics)

        val createdA = createCategory(GraphUserData.Empty, "领域", null, now)
        val createdB = createCategory(GraphUserData.Empty, "领域", null, now)
        assertEquals(createdA.user.categories.single().id, createdB.user.categories.single().id)
    }
}
