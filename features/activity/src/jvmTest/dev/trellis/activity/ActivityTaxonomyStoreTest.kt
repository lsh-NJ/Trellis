package dev.trellis.activity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ActivityTaxonomyStoreTest {

    private fun tempDir(): Path = Files.createTempDirectory("trellis-taxonomy-test")

    @Test
    fun `文件不存在时返回出厂分类，不创建文件`() {
        val file = tempDir().resolve("activity/taxonomy.json")

        val taxonomy = ActivityTaxonomyStore(file).load()

        assertEquals(ActivityTaxonomy.DEFAULT_CATEGORIES.map { it.id }, taxonomy.categories.map { it.id })
        assertTrue(!file.exists(), "只读不该产生副作用")
    }

    @Test
    fun `保存后再读回，分类树和规则都在`() {
        val file = tempDir().resolve("activity/taxonomy.json")
        val store = ActivityTaxonomyStore(file)
        val original = ActivityTaxonomy(
            categories = listOf(
                CategoryNode("study", "学习", color = "#112233", rules = listOf(CategoryRule("anki"))),
                CategoryNode(ActivityTaxonomy.UNCATEGORIZED_ID, "未分类"),
            ),
        )

        store.save(original)
        val restored = store.load()

        val study = assertNotNull(restored.node("study"))
        assertEquals("学习", study.name)
        assertEquals("#112233", study.color)
        assertEquals("anki", study.rules.single().pattern)
        assertTrue(file.readText().contains("\"study\""))
    }

    @Test
    fun `读坏的文件回到默认值，并把坏文件改名留档`() {
        val dir = tempDir()
        val file = dir.resolve("activity/taxonomy.json")
        Files.createDirectories(file.parent)
        file.writeText("{ 这不是 JSON")

        val taxonomy = ActivityTaxonomyStore(file).load()

        assertEquals(ActivityTaxonomy.DEFAULT_CATEGORIES.map { it.id }, taxonomy.categories.map { it.id })
        val quarantined = dir.resolve("activity").listDirectoryEntries("taxonomy.json.bad-*")
        assertEquals(1, quarantined.size, "坏文件要留档，用户可能只写错一个字符")
    }
}