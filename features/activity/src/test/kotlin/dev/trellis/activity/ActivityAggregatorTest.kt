package dev.trellis.activity

import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整理层的验收测试。
 *
 * 每个用例都对应一条「不这么写就会算错」的具体场景，不是给代码凑覆盖率：
 * AFK 不扣会把 QQ 的一条 107 分钟算成聊天 107 分钟；碎片不并会让时间轴有三千行。
 */
class ActivityAggregatorTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2026, 10, 27)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val noon: Long = at(day, 12)

    private fun win(id: Long, fromMs: Long, durMs: Long, app: String, title: String? = null, bucket: String = "win") =
        RawEvent(bucket = bucket, awId = id, tsMs = fromMs, durMs = durMs, app = app, title = title)

    private fun away(id: Long, fromMs: Long, durMs: Long) =
        RawEvent(bucket = "afk", awId = id, tsMs = fromMs, durMs = durMs, status = "afk")

    private fun store(vararg events: RawEvent): ActivityStore {
        val store = ActivityStore.inMemory()
        store.upsertBucket(AwBucket("win", "currentwindow"))
        store.upsertBucket(AwBucket("afk", "afkstatus"))
        store.upsertEvents(events.toList())
        return store
    }

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
        val taxonomy = ActivityTaxonomy(
            rules = listOf(
                CategoryRule(name = listOf("浏览"), pattern = "chrome", field = RuleField.APP, priority = 100),
                CategoryRule(name = listOf("影音", "视频"), pattern = "bilibili", field = RuleField.TITLE, priority = 120),
            ),
        )
        store(win(1, noon, 600_000, "chrome.exe", "哔哩哔哩 bilibili - Google Chrome")).use { store ->
            val segments = ActivityAggregator(store, ActivitySettings(), taxonomy).segments(noon)

            assertEquals(listOf("影音", "视频"), segments.single().category)
        }
    }

    @Test
    fun `没命中任何规则就落到未分类`() {
        store(win(1, noon, 600_000, "some-unknown-tool.exe")).use { store ->
            val segments = ActivityAggregator(store).segments(noon)

            assertEquals(ActivityTaxonomy.UNCATEGORIZED, segments.single().category)
        }
    }

    @Test
    fun `app 排行按时长倒序，可以按分类前缀过滤`() {
        store(
            win(1, noon, 300_000, "chrome.exe"),
            win(2, noon + 300_000, 600_000, "Code.exe"),
        ).use { store ->
            val aggregator = ActivityAggregator(store)

            assertEquals(listOf("Code.exe", "chrome.exe"), aggregator.appRanking(noon).map { it.app })

            // 默认分类里 Code.exe 是 工作/编程、chrome.exe 是 浏览/浏览器；传一级分类要能带出二级。
            val work = aggregator.appRanking(noon, listOf("工作"))
            assertEquals(listOf("Code.exe"), work.map { it.app })
            assertEquals(600_000L, work.single().durationMs)
        }
    }

    @Test
    fun `两个 bucket 同一时刻都有事件时不重复计时`() {
        val store = ActivityStore.inMemory()
        store.upsertBucket(AwBucket("w1", "currentwindow"))
        store.upsertBucket(AwBucket("w2", "currentwindow"))
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
}