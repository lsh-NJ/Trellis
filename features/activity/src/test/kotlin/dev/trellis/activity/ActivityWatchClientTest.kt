package dev.trellis.activity

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityWatchClientTest {

    @Test
    fun `解析原始事件并保留 aw-server 的 id`() = runTest {
        val client = ActivityWatchClient(
            fetch = { url, _ ->
                assertTrue(url.contains("/buckets/aw-watcher-window_H/events"), url)
                assertTrue(url.contains("start=") && url.contains("&end=") && url.contains("&limit="), url)
                """
                [
                  {"id": 11337, "timestamp": "2026-10-27T16:55:07.329000+00:00", "duration": 101.69,
                   "data": {"app": "chrome.exe", "title": "某网页 - Google Chrome"}},
                  {"id": 11338, "timestamp": "2026-10-27T16:00:00.000000+00:00", "duration": 30.0,
                   "data": {"app": "chrome.exe", "title": "更早的一条"}}
                ]
                """.trimIndent()
            },
        )
        val events = client.events("aw-watcher-window_H", 0, 1_000_000_000)
        assertEquals(2, events.size)
        // 顺序必须是时间升序，aw-server 给的是最新在前
        assertTrue(events[0].tsMs < events[1].tsMs)
        assertEquals(11337L, events[1].awId)
        assertEquals(101_690L, events[1].durMs)
        assertEquals("chrome.exe", events[1].app)
        assertNotNull(events[1].title)
        assertNull(events[1].status)
    }

    @Test
    fun `没有 id 的事件用内容算一个稳定替代 id`() = runTest {
        val payload = """
            [{"id": null, "timestamp": "2026-10-27T16:00:00.000000+00:00", "duration": 5.0,
              "data": {"status": "afk"}}]
        """.trimIndent()
        val client = ActivityWatchClient(fetch = { _, _ -> payload })
        val first = client.events("aw-watcher-afk_H", 0, 1_000_000_000).single()
        val second = client.events("aw-watcher-afk_H", 0, 1_000_000_000).single()
        assertEquals("afk", first.status)
        assertEquals(5_000L, first.durMs)
        assertEquals(first.awId, second.awId, "替代 id 必须稳定，否则同步会重复插")
    }

    @Test
    fun `列出 bucket 时跳过没有 type 的脏数据`() = runTest {
        val client = ActivityWatchClient(
            fetch = { url, _ ->
                assertTrue(url.endsWith("/buckets/"), url)
                """
                {
                  "aw-watcher-window_H": {"id": "aw-watcher-window_H", "type": "currentwindow", "hostname": "H"},
                  "aw-watcher-afk_H": {"id": "aw-watcher-afk_H", "type": "afkstatus"},
                  "broken": {"id": "broken"}
                }
                """.trimIndent()
            },
        )
        val buckets = client.buckets()
        assertEquals(2, buckets.size)
        assertEquals(setOf("currentwindow", "afkstatus"), buckets.map { it.type }.toSet())
    }

    @Test
    fun `服务没开时返回空列表而不是抛异常`() = runTest {
        val client = ActivityWatchClient(fetch = { _, _ -> throw java.io.IOException("Connection refused") })
        assertTrue(client.events("any", 0, 1_000).isEmpty())
        assertTrue(client.buckets().isEmpty())
    }
}