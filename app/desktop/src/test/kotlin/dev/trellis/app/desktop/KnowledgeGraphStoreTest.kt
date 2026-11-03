package dev.trellis.app.desktop

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 知识网络两个 json 的读写测试（设计 §7）。
 *
 * 这一层只测「DTON ⇄ 文件」：往返一致、坏文件留档不崩、不认识的字段不丢、
 * 分类 id 形状、哈希稳定。分类树规则与建图不在这里（`KnowledgeGraphTaxonomyTest` /
 * `KnowledgeGraphBuildTest`），布局指纹的语义见 `KnowledgeGraphModel` 的 `layoutFingerprint`。
 */
class KnowledgeGraphStoreTest {

    private val zone: ZoneId = ZoneOffset.ofHours(8)
    private var nowMs: Long = Instant.parse("2026-11-03T02:00:00Z").toEpochMilli() // 10:00 +08:00

    private fun clock(): TrellisClock = object : TrellisClock {
        override val zone: ZoneId = this@KnowledgeGraphStoreTest.zone
        override fun nowMs(): Long = nowMs
        override fun monotonicMs(): Long = nowMs
        override fun today(): LocalDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        override fun isoWeek(): String = isoWeekOf(today())
        override fun simulatedDate(): LocalDate? = null
    }

    private fun tempHome(): File {
        val dir = File.createTempFile("trellis-graph-", "")
        dir.delete()
        dir.mkdirs()
        return dir
    }

    private fun store(home: File = tempHome()): KnowledgeGraphStore = KnowledgeGraphStore(home, clock())

    private fun sampleUser(): GraphUserData = GraphUserData(
        updatedAt = 1_700_000_000_000L,
        categories = listOf(
            GraphCategory("c_aaaaaaaa", "检索", parentId = null, order = 0),
            GraphCategory(
                id = "c_bbbbbbbb",
                name = "向量检索",
                parentId = "c_aaaaaaaa",
                order = 0,
                origin = NodeOrigin.AI,
                description = "把文本变成向量",
                confirmed = false,
            ),
        ),
        assignments = mapOf("向量检索" to "c_bbbbbbbb", "嵌入" to "c_aaaaaaaa"),
        relations = listOf(
            GraphUserRelation("嵌入", "向量检索", GraphEdgeKind.PREREQUISITE, confidence = 1f, reason = "先有表示"),
        ),
        removed = GraphRemovals(
            relations = listOf(RemovedRelation("A", "B", GraphEdgeKind.RELATED)),
            assignments = listOf(RemovedAssignment("C", listOf("检索"))),
            categories = listOf(RemovedCategory("c_cccccccc", listOf("旧分类"), 123L)),
            suggestions = listOf(RemovedSuggestion("D", "fp-1")),
        ),
        mergedInto = mapOf("c_old" to "c_aaaaaaaa"),
    )

    @Test
    fun missingFilesLoadAsEmptyData() {
        val store = store()
        assertEquals(GraphUserData.Empty, store.loadUser())
        assertEquals(GraphAiCache.Empty, store.loadCache())
        assertNull(store.loadLayout())
        assertNull(store.userLoadError)
        assertNull(store.cacheLoadError)
    }

    @Test
    fun userFileRoundTrips() {
        val store = store()
        assertTrue(store.saveUser(sampleUser()))

        val loaded = store.loadUser()
        assertNull(store.userLoadError)
        assertEquals(2, loaded.version)
        assertEquals(sampleUser().updatedAt, loaded.updatedAt)
        assertEquals(sampleUser().categories, loaded.categories)
        assertEquals(sampleUser().assignments, loaded.assignments)
        assertEquals(sampleUser().relations, loaded.relations)
        assertEquals(sampleUser().removed, loaded.removed)
        assertEquals(sampleUser().mergedInto, loaded.mergedInto)
    }

    @Test
    fun unknownTopLevelFieldsSurviveRoundTrip() {
        val home = tempHome()
        val file = File(File(home, "knowledge"), "graph.user.json")
        file.parentFile.mkdirs()
        file.writeText(
            """
            {"version":2,"updatedAt":1,"categories":[],"assignments":{},"relations":[],
             "removed":{},"mergedInto":{},"futureField":{"nested":true},"another":42}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val store = store(home)
        val loaded = store.loadUser()
        assertEquals(emptyList(), loaded.categories)
        assertTrue(store.saveUser(loaded))

        val saved = file.readText(Charsets.UTF_8)
        assertTrue(saved.contains("\"futureField\""), "未知字段应原样保留：$saved")
        assertTrue(saved.contains("\"another\""), "未知字段应原样保留：$saved")
    }

    @Test
    fun badUserFileIsQuarantinedAndLoadsEmpty() {
        val home = tempHome()
        val file = File(File(home, "knowledge"), "graph.user.json")
        file.parentFile.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        nowMs = Instant.parse("2026-11-03T02:00:00Z").toEpochMilli()

        val store = store(home)
        val loaded = store.loadUser()

        assertEquals(GraphUserData.Empty, loaded)
        assertNotNull(store.userLoadError)
        assertFalse(file.exists(), "坏文件应被改名留档")
        val quarantined = file.parentFile.listFiles().orEmpty()
            .filter { it.name.startsWith("graph.user.json.bad-") }
        assertEquals(1, quarantined.size, "应留下一个 .bad-<ms> 文件")
        assertTrue(store.saveUser(sampleUser()), "留档后仍应能重新写新文件")
        assertTrue(file.isFile)
    }

    @Test
    fun cacheRoundTripsSuggestionsAndLayout() {
        val cache = GraphAiCache(
            promptVersion = 3,
            model = "test-model",
            updatedAt = 99L,
            noteSuggestions = mapOf(
                "n_1" to AiNoteSuggestions(
                    bodyHash = graphHashText(7L),
                    categories = listOf(
                        AiCategorySuggestion("向量检索", listOf("检索", "向量检索"), "同段出现", 5L),
                    ),
                    relations = listOf(
                        AiRelationSuggestion(
                            from = "嵌入",
                            to = "向量检索",
                            kind = GraphEdgeKind.PREREQUISITE,
                            confidence = 0.6f,
                            reason = "先有表示",
                            evidence = "向量检索把文本编码",
                            createdAt = 6L,
                        ),
                    ),
                ),
            ),
            globalCategories = AiGlobalCategories(
                conceptSetHash = graphHashText(8L),
                categories = listOf(AiGlobalCategorySuggestion("多模态", listOf("模型"), "同域", 0, 9L)),
                failed = listOf(AiFailedCategory("幻觉", "名字不在输入集")),
            ),
            globalRelations = AiGlobalRelations(
                conceptSetHash = graphHashText(10L),
                relations = listOf(
                    AiRelationSuggestion("RAG", "嵌入", GraphEdgeKind.APPLIES, 0.5f),
                ),
            ),
            layout = LayoutCache(
                fingerprint = 42L,
                rootOrder = listOf("c_a", "c_b"),
                anchors = mapOf("c_a" to GraphPoint(12f, 34f)),
            ),
        )

        val store = store()
        assertTrue(store.saveCache(cache))
        val loaded = store.loadCache()

        assertNull(store.cacheLoadError)
        assertEquals(cache.promptVersion, loaded.promptVersion)
        assertEquals(cache.model, loaded.model)
        assertEquals(cache.noteSuggestions, loaded.noteSuggestions)
        assertEquals(cache.globalCategories, loaded.globalCategories)
        assertEquals(cache.globalRelations, loaded.globalRelations)
        assertEquals(cache.layout, loaded.layout)
    }

    @Test
    fun badCacheFileIsQuarantinedAndLoadsEmpty() {
        val home = tempHome()
        val file = File(File(home, "knowledge"), "graph.cache.json")
        file.parentFile.mkdirs()
        file.writeText("[]", Charsets.UTF_8) // 合法 JSON，但不是对象

        val store = store(home)
        assertEquals(GraphAiCache.Empty, store.loadCache())
        assertNotNull(store.cacheLoadError)
        val quarantined = file.parentFile.listFiles().orEmpty()
            .filter { it.name.startsWith("graph.cache.json.bad-") }
        assertEquals(1, quarantined.size)
    }

    @Test
    fun saveLayoutOnlyTouchesLayoutSection() {
        val store = store()
        val cache = GraphAiCache(
            promptVersion = 7,
            model = "m",
            noteSuggestions = mapOf("n_1" to AiNoteSuggestions(bodyHash = "h")),
        )
        assertTrue(store.saveCache(cache))

        val layout = LayoutCache(123L, listOf("c_a"), mapOf("c_a" to GraphPoint(1f, 2f)))
        assertTrue(store.saveLayout(layout))

        val reloaded = store.loadCache()
        assertEquals(cache.noteSuggestions, reloaded.noteSuggestions)
        assertEquals(cache.promptVersion, reloaded.promptVersion)
        assertEquals(layout, reloaded.layout)
        assertEquals(layout, store.loadLayout())
    }

    @Test
    fun savingOverwriteLeavesBackup() {
        val home = tempHome()
        val store = store(home)
        assertTrue(store.saveUser(sampleUser()))
        assertTrue(store.saveUser(sampleUser().copy(updatedAt = 2L)))

        val backup = File(File(home, "knowledge"), "graph.user.json.bak")
        assertTrue(backup.isFile, "覆盖前应留 .bak")
        assertTrue(backup.readText(Charsets.UTF_8).contains("1700000000000"))
    }

    @Test
    fun newCategoryIdHasStableShape() {
        val store = store()
        val first = store.newCategoryId()
        val second = store.newCategoryId()
        assertTrue(Regex("c_[0-9a-f]{8}").matches(first), "分类 id 形状不对：$first")
        assertTrue(Regex("c_[0-9a-f]{8}").matches(second))
        assertNotEquals(first, second)
    }

    @Test
    fun hashesAreStableAndSensitive() {
        fun graph(vararg concepts: Pair<String, String?>): KnowledgeGraph {
            val nodes = concepts.associate { (key, parent) ->
                key to GraphNode(key = key, kind = GraphNodeKind.CONCEPT, name = key, parentKey = parent)
            } + mapOf(
                "c_one" to GraphNode(
                    key = "c_one",
                    kind = GraphNodeKind.CATEGORY,
                    name = "检索",
                    sortOrder = 0,
                ),
            )
            return KnowledgeGraph(nodes = nodes, childrenOf = mapOf("c_one" to concepts.map { it.first }))
        }

        val a = graph("嵌入" to "c_one")
        val same = graph("嵌入" to "c_one")
        val moved = graph("嵌入" to null)
        val extra = graph("嵌入" to "c_one", "RAG" to "c_one")

        assertEquals(conceptSetHash(a), conceptSetHash(same))
        assertEquals(layoutFingerprint(a), layoutFingerprint(same))
        assertNotEquals(conceptSetHash(a), conceptSetHash(extra))
        assertNotEquals(layoutFingerprint(a), layoutFingerprint(moved))

        // 只改分类名不影响指纹（改名不该让布局跳）。
        val renamedCategory = a.copy(
            nodes = a.nodes + (
                "c_one" to a.nodes.getValue("c_one").copy(name = "换了个名字")
                ),
        )
        assertEquals(conceptSetHash(a), conceptSetHash(renamedCategory))
        assertEquals(layoutFingerprint(a), layoutFingerprint(renamedCategory))
    }

    @Test
    fun noteBodyHashFollowsTitleAndBody() {
        val note = KnowledgeNote(
            id = "n_1",
            title = "RAG 笔记",
            body = "向量检索把文本编码成向量。",
            createdAt = 1L,
            updatedAt = 2L,
        )
        val hash = noteBodyHash(note)
        assertTrue(hash.startsWith(GRAPH_HASH_PREFIX), "哈希文本应带前缀：$hash")
        assertEquals(hash, noteBodyHash(note.copy()))
        assertNotEquals(hash, noteBodyHash(note.copy(body = "换了正文")))
        assertNotEquals(hash, noteBodyHash(note.copy(title = "换了标题")))
        assertNotNull(graphHashFromText(hash))
    }
}
