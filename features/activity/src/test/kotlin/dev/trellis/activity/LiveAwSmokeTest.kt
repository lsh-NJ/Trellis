package dev.trellis.activity

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 真机冒烟：只有显式设置环境变量 `TRELLIS_AW_LIVE=1` 才会真的去打 aw-server。
 *
 * 平时跑 `gradlew test` 一律跳过 —— 单测不该依赖「开发机上刚好开着 ActivityWatch」。
 * 排查「AW 到底通不通」时用它：
 *
 *     $env:TRELLIS_AW_LIVE=1; .\gradlew.bat :features:activity:test --tests "*LiveAwSmokeTest*" -i
 */
class LiveAwSmokeTest {

    @Test
    fun `从真实 aw-server 拉一天原始数据`() = runTest {
        if (System.getenv("TRELLIS_AW_LIVE") != "1") return@runTest

        val source = ActivityWatchClient()
        val info = source.info()
        println("[live] aw-server info = $info")

        val buckets = source.buckets()
        println("[live] buckets = " + buckets.map { it.type + ":" + it.id })

        val now = System.currentTimeMillis()
        val config = ActivitySettings(backfillDays = 1, overlapSeconds = 0)
        ActivityStore.inMemory().use { store ->
            val report = ActivitySync(source, store, clock = { now }, settings = { config }).syncOnce()
            println("[live] sync report = $report")
            println("[live] 库里原始事件 = " + store.count())
            store.buckets().forEach { println("[live]   " + it.id + " -> " + store.countOf(it.id)) }

            val day = store.eventsByRange(now - ActivitySettings.DAY_MS, now, "aw-watcher-window_DESKTOP-9DEEU93")
            println("[live] 最近 24h window 事件 = " + day.size)
            day.sortedByDescending { it.durMs }.take(3).forEach {
                println("[live]   " + it.durMs + "ms  " + it.app + "  |  " + it.title)
            }
            assertTrue(report.error == null, "report.error = " + report.error)
        }
    }

    @Test
    fun `从真实数据算今天的分层分类汇总`() = runTest {
        if (System.getenv("TRELLIS_AW_LIVE") != "1") return@runTest

        val source = ActivityWatchClient()
        val now = System.currentTimeMillis()
        val config = ActivitySettings(backfillDays = 3, overlapSeconds = 300)
        ActivityStore.inMemory().use { store ->
            val report = ActivitySync(source, store, clock = { now }, settings = { config }).syncOnce()
            println("[live] sync report = $report")

            val taxonomy = ActivityTaxonomy()
            val classifier = taxonomy.classifier()
            println("[live] 规则编译问题 = " + classifier.problems)
            val aggregator = ActivityAggregator(store, config, taxonomy)
            val day = aggregator.daySummary(now)
            println(
                "[live] 日区间 " + day.dayStartMs + ".." + day.dayEndMs +
                    " 观察=" + day.observedMs / 60_000 + "min" +
                    " AFK=" + day.afkMs / 60_000 + "min" +
                    " 活跃=" + day.activeMs / 60_000 + "min" +
                    " 片段=" + day.segmentCount,
            )
            day.categories.forEach { c ->
                println(
                    "[live]   " + "  ".repeat((c.depth - 1).coerceAtLeast(0)) +
                        c.name.joinToString("/") + " " + c.color +
                        " direct=" + c.directMs / 60_000 + "min" +
                        " total=" + c.totalMs / 60_000 + "min",
                )
            }
            println("[live] 应用排行:")
            day.topApps.forEach { println("[live]   " + it.app + " " + it.durationMs / 60_000 + "min") }

            val segments = aggregator.segments(now)
            val withUrl = segments.filter { it.url != null }
            println("[live] 带标签页 URL 的片段 = " + withUrl.size + "/" + segments.size)
            withUrl.take(6).forEach {
                println("[live]   " + it.app + " -> " + it.url + " => " + it.category.joinToString("/"))
            }
            assertTrue(day.observedMs > 0, "今天还没有窗口事件？day.observedMs = " + day.observedMs)
        }
    }
}