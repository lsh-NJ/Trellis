package dev.trellis.activity

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分类体系的单元测试。
 *
 * 重点不是「能分类」，而是几条**会被用户感知到的语义**：
 * 同一条事件命中多个分类时谁赢、改规则后行为是否可解释、写坏的正则会不会静默失效。
 */
class ActivityTaxonomyTest {

    private fun taxonomyOf(vararg nodes: CategoryNode): ActivityTaxonomy =
        ActivityTaxonomy(categories = nodes.toList())

    @Test
    fun `出厂分类自身必须通过校验，且所有正则都能编译`() {
        val taxonomy = ActivityTaxonomy()

        assertTrue(taxonomy.validate().isEmpty(), "出厂数据自带问题：${taxonomy.validate()}")
        val classifier = taxonomy.classifier()
        assertTrue(classifier.problems.isEmpty(), "出厂规则有编译不过的正则：${classifier.problems}")
    }

    @Test
    fun `默认分类按层级归类，没命中落未分类`() {
        val classifier = ActivityTaxonomy().classifier()

        assertEquals("work.coding", classifier.classify("idea64.exe", "Trellis").categoryId)
        assertEquals("comm.qq", classifier.classify("QQ.exe", null).categoryId)
        assertEquals("comm.wechat", classifier.classify("Weixin.exe", null).categoryId)
        assertEquals("media.video", classifier.classify("chrome.exe", "哔哩哔哩 bilibili").categoryId)
        assertEquals(
            "media.video",
            classifier.classify("chrome.exe", "Google Chrome", "https://www.bilibili.com/video/BV1").categoryId,
            "浏览器窗口靠域名规则归类",
        )
        assertEquals("work.coding", classifier.classify("chrome.exe", "Google Chrome", "https://github.com/x").categoryId)
        assertEquals(ActivityTaxonomy.UNCATEGORIZED_ID, classifier.classify("nope.exe", "nope").categoryId)
    }

    @Test
    fun `显式 priority 压过更深的层级`() {
        val taxonomy = taxonomyOf(
            CategoryNode("a", "A", rules = listOf(CategoryRule("foo"))), // 深度 1 -> 排名 10
            CategoryNode("a.b", "B", parentId = "a", rules = listOf(CategoryRule("foo", priority = 50))),
        )

        assertEquals("a.b", taxonomy.classifier().classify("foo.exe", null).categoryId)

        val shallowWins = taxonomyOf(
            CategoryNode("a", "A", rules = listOf(CategoryRule("foo", priority = 99))),
            CategoryNode("a.b", "B", parentId = "a", rules = listOf(CategoryRule("foo", priority = 50))),
        )
        assertEquals("a", shallowWins.classifier().classify("foo.exe", null).categoryId)
    }

    @Test
    fun `同排名时按分类声明顺序取第一条`() {
        val firstWins = taxonomyOf(
            CategoryNode("first", "第一", rules = listOf(CategoryRule("foo"))),
            CategoryNode("second", "第二", rules = listOf(CategoryRule("foo"))),
        )
        assertEquals("first", firstWins.classifier().classify("foo.exe", null).categoryId)

        val reversed = taxonomyOf(
            CategoryNode("second", "第二", rules = listOf(CategoryRule("foo"))),
            CategoryNode("first", "第一", rules = listOf(CategoryRule("foo"))),
        )
        assertEquals("second", reversed.classifier().classify("foo.exe", null).categoryId, "同排名靠声明顺序，不靠 id")
    }

    @Test
    fun `默认只匹配进程名，标题必须显式声明字段`() {
        val taxonomy = taxonomyOf(
            CategoryNode("media.video", "视频", rules = listOf(CategoryRule("bilibili"))),
        )

        assertEquals(
            ActivityTaxonomy.UNCATEGORIZED_ID,
            taxonomy.classifier().classify("vlc.exe", "bilibili 视频").categoryId,
            "进程名没命中时，标题里的词不该抢走整段时间",
        )

        val explicit = taxonomyOf(
            CategoryNode(
                "media.video", "视频",
                rules = listOf(CategoryRule("bilibili", fields = setOf(RuleField.APP, RuleField.TITLE))),
            ),
        )
        assertEquals("media.video", explicit.classifier().classify("vlc.exe", "bilibili 视频").categoryId)
    }

    @Test
    fun `一条规则的多个字段是或关系，URL 字段能匹配整条 URL`() {
        val taxonomy = taxonomyOf(
            CategoryNode(
                "media.video", "视频",
                rules = listOf(
                    CategoryRule("bilibili\\.com/video", fields = setOf(RuleField.URL)),
                ),
            ),
        )

        assertEquals(
            "media.video",
            taxonomy.classifier().classify("chrome.exe", null, "https://www.bilibili.com/video/BV1").categoryId,
        )
        assertEquals(
            ActivityTaxonomy.UNCATEGORIZED_ID,
            taxonomy.classifier().classify("chrome.exe", null, "https://www.bilibili.com/").categoryId,
        )
    }

    @Test
    fun `写坏的正则收进 problems，不抛异常也不静默算错`() {
        val taxonomy = taxonomyOf(
            CategoryNode("broken", "坏规则", rules = listOf(CategoryRule("(unclosed"))),
        )
        val classifier = taxonomy.classifier()

        assertEquals(ActivityTaxonomy.UNCATEGORIZED_ID, classifier.classify("x.exe", null).categoryId)
        assertTrue(classifier.problems.any { it.contains("编译失败") }, classifier.problems.toString())
        assertTrue(ActivityTaxonomy().validate().isEmpty())
        assertTrue(taxonomy.validate().any { it.contains("编译不过") })
    }

    @Test
    fun `空正则与停用规则都不参与匹配`() {
        val taxonomy = taxonomyOf(
            CategoryNode(
                "x", "X",
                rules = listOf(CategoryRule("   "), CategoryRule("foo", enabled = false)),
            ),
        )
        val classifier = taxonomy.classifier()

        assertEquals(ActivityTaxonomy.UNCATEGORIZED_ID, classifier.classify("foo.exe", null).categoryId)
        assertTrue(classifier.problems.any { it.contains("空正则") })
    }

    @Test
    fun `explain 按排名列出全部命中并标出命中的字段`() {
        val taxonomy = taxonomyOf(
            CategoryNode("browse", "浏览"),
            CategoryNode(
                "browse.browser", "浏览器", parentId = "browse",
                rules = listOf(CategoryRule("chrome", priority = 100)),
            ),
            CategoryNode("media", "影音"),
            CategoryNode(
                "media.video", "视频", parentId = "media",
                rules = listOf(
                    CategoryRule("bilibili", fields = setOf(RuleField.TITLE), priority = 120),
                ),
            ),
        )

        val explanation = taxonomy.classifier().explain("chrome.exe", "哔哩哔哩 bilibili", null)

        assertEquals("media.video", explanation.chosen.categoryId)
        assertEquals(listOf("media.video", "browse.browser"), explanation.hits.map { it.categoryId })
        assertEquals(listOf(RuleField.TITLE), explanation.hits.first().matchedFields)
        assertEquals("影音/视频", explanation.hits.first().pathText)
        assertEquals(120, explanation.hits.first().rank)
    }

    @Test
    fun `normalised 补齐颜色、补上未分类兜底`() {
        val taxonomy = taxonomyOf(
            CategoryNode("a", "A", color = ""),
            CategoryNode("b", "B", color = "not-a-color"),
        )

        val normalised = taxonomy.normalised()

        assertNotNull(normalised.node("a"))
        assertTrue(ActivityTaxonomy.isHexColor(normalised.node("a")!!.color))
        assertTrue(ActivityTaxonomy.isHexColor(normalised.node("b")!!.color))
        assertNotNull(normalised.node(ActivityTaxonomy.UNCATEGORIZED_ID), "缺兜底分类时要补一个")
    }

    @Test
    fun `validate 能报出重复 id、缺失父分类、成环、坏颜色和空正则`() {
        val taxonomy = ActivityTaxonomy(
            categories = listOf(
                CategoryNode("dup", "重复 1"),
                CategoryNode("dup", "重复 2"),
                CategoryNode("orphan", "孤儿", parentId = "missing"),
                CategoryNode("cycle.a", "环 A", parentId = "cycle.b"),
                CategoryNode("cycle.b", "环 B", parentId = "cycle.a"),
                CategoryNode("bad", "坏颜色", color = "#XYZ"),
                CategoryNode("blank", "空正则", rules = listOf(CategoryRule("  "))),
                CategoryNode(ActivityTaxonomy.UNCATEGORIZED_ID, "未分类"),
            ),
        )

        val issues = taxonomy.validate().joinToString("\n")
        assertContains(issues, "id 重复")
        assertContains(issues, "父分类")
        assertContains(issues, "成环")
        assertContains(issues, "不是 #RRGGBB")
        assertContains(issues, "空正则")
    }

    @Test
    fun `路径、深度、后代、前缀判断和按路径查找`() {
        val taxonomy = ActivityTaxonomy()

        assertEquals(listOf("工作", "编程"), taxonomy.pathNames("work.coding"))
        assertEquals(2, taxonomy.depthOf("work.coding"))
        assertEquals(1, taxonomy.depthOf("work"))
        assertTrue(taxonomy.descendantsOf("work").any { it.id == "work.coding" })
        assertTrue(taxonomy.descendantsOf("work").none { it.id == "media.video" })
        assertTrue(taxonomy.isDescendantOf("work.coding", "work"))
        assertTrue(taxonomy.isDescendantOf("work", "work"))
        assertFalse(taxonomy.isDescendantOf("work", "work.coding"))
        assertEquals("work.coding", taxonomy.findByPath(listOf("工作", "编程"))?.id)
        assertNull(taxonomy.findByPath(listOf("不存在")))
        assertEquals(listOf("工作", "编程"), taxonomy.pathOf("work.coding").map { it.name })
    }

    @Test
    fun `domainOf 去掉协议和 www，裸域名也能解析`() {
        assertEquals("bilibili.com", ActivityTaxonomy.domainOf("https://www.bilibili.com/video/BV1"))
        assertEquals("example.com", ActivityTaxonomy.domainOf("example.com/path"))
        assertEquals("localhost", ActivityTaxonomy.domainOf("http://localhost:8080/x"))
        assertNull(ActivityTaxonomy.domainOf(null))
        assertNull(ActivityTaxonomy.domainOf("   "))
    }

    @Test
    fun `paletteColor 稳定且一定是合法颜色`() {
        val a = ActivityTaxonomy.paletteColor("work.coding")
        assertEquals(a, ActivityTaxonomy.paletteColor("work.coding"))
        assertTrue(ActivityTaxonomy.isHexColor(a))
        assertTrue(ActivityTaxonomy.PALETTE.all { ActivityTaxonomy.isHexColor(it) })
    }

    @Test
    fun `regexOptions 默认忽略大小写并多行，可以单条关掉`() {
        val defaults = ActivityTaxonomy.regexOptions(CategoryRule("x"))
        assertTrue(defaults.contains(RegexOption.IGNORE_CASE))
        assertTrue(defaults.contains(RegexOption.MULTILINE))

        val caseSensitive = ActivityTaxonomy.regexOptions(CategoryRule("x", ignoreCase = false))
        assertFalse(caseSensitive.contains(RegexOption.IGNORE_CASE))
        assertTrue(caseSensitive.contains(RegexOption.MULTILINE))
    }

    @Test
    fun `序列化往返保留分类树`() {
        val original = ActivityTaxonomy()
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val text = json.encodeToString(original)

        val restored = json.decodeFromString<ActivityTaxonomy>(text)

        assertEquals(original.categories.map { it.id }, restored.categories.map { it.id })
        assertEquals(
            original.categories.sumOf { it.rules.size },
            restored.categories.sumOf { it.rules.size },
        )
    }

    @Test
    fun `withCategory 按 id 插入或替换，不原地改旧树`() {
        val base = taxonomyOf(
            CategoryNode(id = "a", name = "甲", color = "#4ADE80"),
            CategoryNode(id = ActivityTaxonomy.UNCATEGORIZED_ID, name = "未分类", color = "#9CA3AF"),
        )

        val added = base.withCategory(CategoryNode(id = "b", name = "乙", parentId = "a", color = "#F87171"))
        assertEquals(listOf("a", "uncategorized", "b"), added.categories.map { it.id })
        assertEquals(2, base.categories.size, "旧树不能被改")

        val renamed = added.withCategory(added.node("b")!!.copy(name = "乙改"))
        assertEquals("乙改", renamed.node("b")?.name)
        assertEquals("乙", added.node("b")?.name)
    }

    @Test
    fun `withoutCategory 把子类挂到父级并删掉自己的规则，未分类删不掉`() {
        val base = taxonomyOf(
            CategoryNode(id = "a", name = "甲", color = "#4ADE80"),
            CategoryNode(id = "b", name = "乙", parentId = "a", color = "#F87171", rules = listOf(CategoryRule("x"))),
            CategoryNode(id = "c", name = "丙", parentId = "b", color = "#60A5FA"),
            CategoryNode(id = ActivityTaxonomy.UNCATEGORIZED_ID, name = "未分类", color = "#9CA3AF"),
        )

        val removed = base.withoutCategory("b")
        assertEquals(listOf("a", "c", "uncategorized"), removed.categories.map { it.id })
        assertEquals("a", removed.node("c")?.parentId, "丙应该挂到甲的下面，而不是一起被删")
        assertEquals(null, removed.node("b"))

        val kept = base.withoutCategory(ActivityTaxonomy.UNCATEGORIZED_ID)
        assertEquals(base.categories, kept.categories, "未分类是保留分类，删不掉")
    }

    @Test
    fun `nextCategoryId 跳过已占用的 cat-n`() {
        val base = taxonomyOf(
            CategoryNode(id = "cat-1", name = "一", color = "#4ADE80"),
            CategoryNode(id = "cat-3", name = "三", color = "#F87171"),
        )
        assertEquals("cat-2", base.nextCategoryId())

        val withTwo = base.withCategory(CategoryNode(id = "cat-2", name = "二", color = "#60A5FA"))
        assertEquals("cat-4", withTwo.nextCategoryId())
    }
}