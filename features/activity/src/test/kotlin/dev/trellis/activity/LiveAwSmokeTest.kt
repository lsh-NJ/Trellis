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
 *     $env:TRELLIS_AW_LIVE=1; .\gradlew.bat :features:activity:test --tests "*LiveAwSmokeTest*"
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
}