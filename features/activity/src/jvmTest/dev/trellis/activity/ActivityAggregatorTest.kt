package dev.trellis.activity

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 整理层的验收测试。
 *
 * 每个用例都对应一条「不这么写就会算错」的具体场景，不是给代码凑覆盖率：
 * AFK 不扣会把 QQ 的一条 107 分钟算成聊天 107 分钟；碎片不并会让时间轴有三千行；
 * 浏览器 URL 贴错段会把「在 B 站看视频」算到旁边的记事本上。
 */
class ActivityAggregatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2026, 10, 27)
    private val noon: Long = at(day, 12)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun win(id: Long, fromMs: Long, durMs: Long, app: String, title: String? = null, bucket: String = "win") =
        RawEvent(bucket = bucket, awId = id, tsMs = fromMs, durMs = durMs, app = app, title = title)

    private fun tab(id: Long, fromMs: Long, durMs: Long, url: String, title: String? = null, bucket: String = "web") =
        RawEvent(bucket = bucket, awId = id, tsMs = fromMs, durMs = durMs, title = title, url = url)

    private fun away(id: Long, fromMs: Long, durMs: Long) =
        RawEvent(bucket = "afk", awId = id, tsMs = fromMs, durMs = durMs, status = "afk")

    private fun store(vararg events: RawEvent): ActivityStore {
        val store = ActivityStore.inMemory()
        store.upsertBucket(AwBucket("win", ActivitySettings.WINDOW_TYPE))
        store.upsertBucket(AwBucket("afk", ActivitySettings.AFK_TYPE))
        store.upsertBucket(AwBucket("web", ActivitySettings.WEB_TYPE))
        store.upsertEvents(events.toList())
        return store
    }

    /** 小分类树：测试只关心被验证的那几条规则，不依赖出厂默认值。 */
    private fun taxonomyOf(vararg nodes: CategoryNode): ActivityTaxonomy =
        ActivityTaxonomy(categories = nodes.toList())

    @Test
    fun `AFK 重叠按交集扣除，而不是整条扣或整条留`() {
        // 10 分钟窗口，中间 2 分钟人不在。
        store(win(1, noon, 600_000, "Code.exe"), away(1, noon + 240_000, 120_000)).use { store ->
            val aggregator = ActivityAggregator(store)
            val segments = aggregator.segments(noon)

            assertEquals(2, segments.size, "应被切成「在」的前 4 分钟和「回来」的后 4 分钟")
            assertEquals(480_000L, segments.sumOf { it.durationMs })

            val summary = aggregator.daySummary(noon)
            assertEquals(600_000L, summary.observedMs)
            assertEquals(120_000L, summary.afkMs)
            assertEquals(480_000L, summary.activeMs)
            assertTrue(summary.hasAfkData)
        }
    }

    @Test
    fun `短于阈值的碎片并进前一段，总时长不变`() {
        store(
            win(1, noon, 600_000, "Code.exe"),
            win(2, noon + 600_000, 2_000, "explorer.exe"),
            win(3, noon + 602_000, 600_000, "Code.exe"),
        ).use { store ->
            val segments = ActivityAggregator(store).segments(noon)

            assertEquals(2, segments.size, "那条 2 秒的 explorer 不该单独成段")
            assertEquals("Code.exe", segments[0].app)
            assertEquals(602_000L, segments[0].durationMs, "碎片并进前一段，前一段变长")
            assertEquals(1_202_000L, segments.sumOf { it.durationMs }, "总时长不能因为合并而丢")
        }
    }

    @Test
    fun `排除的进程不进时间轴，但汇总里露一个数字`() {
        store(win(1, noon, 600_000, "KeePass.exe"), win(2, noon + 600_000, 300_000, "Code.exe")).use { store ->
            val aggregator = ActivityAggregator(store, ActivitySettings(excludedApps = listOf("keepass.exe")))

            assertEquals(listOf("Code.exe"), aggregator.segments(noon).map { it.app })

            val summary = aggregator.daySummary(noon)
            assertEquals(600_000L, summary.excludedMs)
            assertEquals(300_000L, summary.observedMs)
            assertEquals(300_000L, summary.activeMs)
        }
    }

    @Test
    fun `日界按 dayStartHour 切，凌晨两点算前一天`() {
        val lateNight = at(LocalDate.of(2026, 10, 28), 2)
        store(win(1, lateNight, 600_000, "Code.exe")).use { store ->
            val aggregator = ActivityAggregator(store, ActivitySettings(dayStartHour = 4))

            assertTrue(aggregator.segments(noon).isNotEmpty(), "28 日凌晨 2 点属于 27 日这一天（4 点起算）")
            assertTrue(aggregator.segments(at(LocalDate.of(2026, 10, 28), 12)).isEmpty(), "它不属于 28 日那一天")
        }
    }

    @Test
    fun `标题规则可以压过进程规则`() {
        // 在 B 站看视频：进程是 chrome（浏览），标题才是真正的内容。优先级让影音赢。
        val taxonomy = taxonomyOf(
            CategoryNode("browse", "浏览"),
            CategoryNode("browse.browser", "浏览器", parentId = "browse", order = 0, rules = listOf(CategoryRule("chrome", priority = 100))),
            CategoryNode("media", "影音"),
            CategoryNode(
                "media.video", "视频", parentId = "media", order = 0,
                rules = listOf(
                    CategoryRule("bilibili", fields = setOf(RuleField.TITLE), priority = 120),
                ),
            ),
        )
        store(win(1, noon, 600_000, "chrome.exe", "哔哩哔哩 bilibili - Google Chrome")).use { store ->
            val segment = ActivityAggregator(store, ActivitySettings(), taxonomy).segments(noon).single()

            assertEquals("media.video", segment.categoryId)
            assertEquals(listOf("影音", "视频"), segment.category)
        }
    }

    @Test
    fun `浏览器窗口按时间重叠贴上标签页 URL，域名规则因此生效`() {
        // 窗口 watcher 只知道 chrome 在前台；B 站这件事只有 web.tab.current 的 url 知道。
        store(
            win(1, noon, 600_000, "chrome.exe", "Google Chrome"),
            tab(1, noon, 600_000, "https://www.bilibili.com/video/BV1xx411c7mD"),
        ).use { store ->
            val segment = ActivityAggregator(store).segments(noon).single()

            assertEquals("https://www.bilibili.com/video/BV1xx411c7mD", segment.url)
            assertEquals("media.video", segment.categoryId, "默认分类里 bilibili 域名 -> 影音/视频")
        }
    }

    @Test
    fun `标签页 URL 只贴给浏览器进程，不会贴到旁边的记事本上`() {
        store(
            win(1, noon, 600_000, "Code.exe", "Trellis - IntelliJ"),
            tab(1, noon, 600_000, "https://www.bilibili.com/video/BV1"),
        ).use { store ->
            val segment = ActivityAggregator(store).segments(noon).single()

            assertNull(segment.url, "非浏览器片段不该继承浏览器标签页的 URL")
            assertEquals("work.coding", segment.categoryId)
        }
    }

    @Test
    fun `多条标签页重叠时取重叠最多的那条`() {
        store(
            win(1, noon, 600_000, "chrome.exe", "Google Chrome"),
            tab(1, noon, 100_000, "https://github.com/x"),
            tab(2, noon + 100_000, 500_000, "https://www.youtube.com/watch?v=1"),
        ).use { store ->
            val segment = ActivityAggregator(store).segments(noon).single()

            assertEquals("https://www.youtube.com/watch?v=1", segment.url)
            assertEquals("media.video", segment.categoryId)
        }
    }

    @Test
    fun `分类时长按层级向上汇总，直接时长和总时长分开`() {
        store(
            win(1, noon, 600_000, "Code.exe"), // 工作/编程
            win(2, noon + 600_000, 300_000, "WINWORD.EXE"), // 工作/办公
        ).use { store ->
            val summary = ActivityAggregator(store).daySummary(noon)

            val work = assertNotNull(summary.category("work"))
            assertEquals(listOf("工作"), work.name)
            assertEquals(900_000L, work.totalMs, "「工作」要包含编程 + 办公")
            assertEquals(0L, work.directMs, "没有规则的直接命中落在「工作」自己身上")
            assertEquals(1, work.depth)

            val coding = assertNotNull(summary.category("work.coding"))
            assertEquals(600_000L, coding.totalMs)
            assertEquals(600_000L, coding.directMs, "叶子分类的 direct 和 total 相等")
            assertEquals(listOf("工作", "编程"), coding.name)

            assertEquals("work", summary.categories.first().categoryId, "按总时长倒序，一级分类「工作」最大")
        }
    }

    @Test
    fun `没命中任何规则就落到未分类`() {
        store(win(1, noon, 600_000, "some-unknown-tool.exe")).use { store ->
            val summary = ActivityAggregator(store).daySummary(noon)
            val slice = summary.categories.single()

            assertEquals(ActivityTaxonomy.UNCATEGORIZED_ID, slice.categoryId)
            assertEquals(listOf(ActivityTaxonomy.UNCATEGORIZED_NAME), slice.name)
            assertEquals(ActivityTaxonomy.UNCATEGORIZED_COLOR, slice.color)
            assertEquals(600_000L, summary.category(ActivityTaxonomy.UNCATEGORIZED_ID)?.totalMs)
        }
    }

    @Test
    fun `app 排行按时长倒序，可以按分类 id 连同后代过滤`() {
        store(
            win(1, noon, 300_000, "chrome.exe"),
            win(2, noon + 300_000, 600_000, "Code.exe"),
        ).use { store ->
            val aggregator = ActivityAggregator(store)

            assertEquals(listOf("Code.exe", "chrome.exe"), aggregator.appRanking(noon).map { it.app })

            // Code.exe 是 work.coding，chrome.exe 是 browse.browser；传一级分类要能带出二级。
            val work = aggregator.appRanking(noon, "work")
            assertEquals(listOf("Code.exe"), work.map { it.app })
            assertEquals(600_000L, work.single().durationMs)

            assertTrue(aggregator.appRanking(noon, "media").isEmpty())
        }
    }

    @Test
    fun `explain 能说明命中了哪些规则、最后为什么选它`() {
        ActivityStore.inMemory().use { store ->
            val explanation = ActivityAggregator(store).explain("chrome.exe", "哔哩哔哩", null)

            assertEquals("media.video", explanation.chosen.categoryId)
            assertTrue(explanation.hits.any { it.categoryId == "browse.browser" }, "低优先级的浏览器规则也应出现在命中列表里")
            assertEquals("media.video", explanation.hits.first().categoryId, "命中列表按排名从高到低")
        }
    }

    @Test
    fun `两个 bucket 同一时刻都有事件时不重复计时`() {
        val store = ActivityStore.inMemory()
        store.upsertBucket(AwBucket("w1", ActivitySettings.WINDOW_TYPE))
        store.upsertBucket(AwBucket("w2", ActivitySettings.WINDOW_TYPE))
        store.upsertEvents(
            listOf(
                win(1, noon, 600_000, "Code.exe", bucket = "w1"),
                win(1, noon, 600_000, "Code.exe", bucket = "w2"),
            ),
        )
        store.use {
            val summary = ActivityAggregator(store).daySummary(noon)

            assertEquals(600_000L, summary.activeMs, "两台机器报了同一段时间，只能算一次")
            assertEquals(600_000L, summary.observedMs)
        }
    }

    @Test
    fun `库里没有数据时返回空汇总而不是抛异常`() {
        ActivityStore.inMemory().use { store ->
            val summary = ActivityAggregator(store).daySummary(noon)

            assertTrue(summary.isEmpty)
            assertEquals(0L, summary.activeMs)
            assertTrue(summary.categories.isEmpty())
            assertTrue(ActivityAggregator(store).appRanking(noon).isEmpty())
        }
    }

    @Test
    fun `范围汇总把多天按分类和应用加在一起`() {
        val nextNoon = at(day.plusDays(1), 12)
        store(
            win(1, noon, 600_000, "Code.exe"), // 27 日：工作/编程 10 分钟
            win(2, nextNoon, 300_000, "WINWORD.EXE"), // 28 日：工作/办公 5 分钟
            win(3, nextNoon + 300_000, 120_000, "QQ.exe"), // 28 日：通讯/QQ 2 分钟
        ).use { store ->
            val range = ActivityAggregator(store).rangeSummary(noon, nextNoon)

            assertEquals(2, range.days)
            assertEquals(1_020_000L, range.activeMs)
            assertEquals(3, range.segmentCount)

            val work = assertNotNull(range.category("work"))
            assertEquals(900_000L, work.totalMs, "跨天的「工作」要合并成一个总数")
            assertEquals(600_000L, range.category("work.coding")?.totalMs)
            assertEquals(300_000L, range.category("work.office")?.totalMs)

            assertEquals("Code.exe", range.topApps.first().app, "应用排行按时长倒序")
            assertEquals(600_000L, range.topApps.first().durationMs)
        }
    }

    @Test
    fun `结束日早于开始日时返回空范围`() {
        val nextNoon = at(day.plusDays(1), 12)
        ActivityStore.inMemory().use { store ->
            val range = ActivityAggregator(store).rangeSummary(nextNoon, noon)

            assertTrue(range.isEmpty)
            assertEquals(0, range.days)
            assertTrue(range.categories.isEmpty())
            assertTrue(range.topApps.isEmpty())
        }
    }
}