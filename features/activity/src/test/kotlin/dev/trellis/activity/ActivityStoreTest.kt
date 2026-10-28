package dev.trellis.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityStoreTest {

    private val window = "aw-watcher-window_TEST"

    @Test
    fun `同一批事件重复写入不会变成两倍`() {
        ActivityStore.inMemory().use { store ->
            val events = listOf(
                RawEvent(window, awId = 1, tsMs = 1_000, durMs = 500, app = "chrome.exe", title = "A"),
                RawEvent(window, awId = 2, tsMs = 1_500, durMs = 500, app = "chrome.exe", title = "B"),
            )
            store.upsertEvents(events)
            store.upsertEvents(events)
            assertEquals(2, store.count())
        }
    }

    @Test
    fun `已经落库的事件被 aw-server 延长后要就地更新时长`() {
        ActivityStore.inMemory().use { store ->
            store.upsertEvents(listOf(RawEvent(window, awId = 7, tsMs = 1_000, durMs = 500, app = "code.exe", title = "T")))
            store.upsertEvents(listOf(RawEvent(window, awId = 7, tsMs = 1_000, durMs = 4_000, app = "code.exe", title = "T")))
            assertEquals(1, store.count())
            assertEquals(4_000, store.eventsByRange(0, 10_000).single().durMs)
        }
    }

    @Test
    fun `maxEndMs 用结束时刻而不是开始时刻`() {
        ActivityStore.inMemory().use { store ->
            assertEquals(0L, store.maxEndMs(window))
            store.upsertEvents(listOf(RawEvent(window, awId = 3, tsMs = 10_000, durMs = 2_500)))
            store.upsertEvents(listOf(RawEvent(window, awId = 4, tsMs = 500, durMs = 100)))
            assertEquals(12_500L, store.maxEndMs(window))
        }
    }

    @Test
    fun `按时间窗查询会把跨越窗口边界的事件算进去`() {
        ActivityStore.inMemory().use { store ->
            store.upsertEvents(listOf(RawEvent(window, awId = 1, tsMs = 0, durMs = 10_000, app = "a.exe")))
            assertTrue(store.eventsByRange(9_000, 10_000).isNotEmpty(), "跨边界的事件不能漏")
            assertTrue(store.eventsByRange(11_000, 20_000).isEmpty())
        }
    }

    @Test
    fun `保留策略只删整段都在界外的事件`() {
        ActivityStore.inMemory().use { store ->
            store.upsertEvents(listOf(RawEvent(window, awId = 1, tsMs = 0, durMs = 1_000)))
            store.upsertEvents(listOf(RawEvent(window, awId = 2, tsMs = 10_000, durMs = 1_000)))
            assertEquals(1, store.pruneBefore(5_000))
            assertEquals(1, store.count())
        }
    }

    @Test
    fun `重复写入时保留更长的时长，不被边界裁剪削短`() {
        // 实测：aw-server 会把跨越查询区间的 AFK 事件裁到边界上，
        // 同一个事件换成更窄的区间只返回一小段。库里必须留住完整的那份。
        ActivityStore.inMemory().use { store ->
            store.upsertEvents(listOf(RawEvent(window, awId = 7, tsMs = 0, durMs = 35_346_000, app = "a.exe")))
            store.upsertEvents(listOf(RawEvent(window, awId = 7, tsMs = 0, durMs = 3_600_000, app = "a.exe")))

            val stored = store.eventsByRange(0, 40_000_000).single()
            assertEquals(35_346_000L, stored.durMs)
            assertEquals(35_346_000L, store.maxDurMs(window))
        }
    }
    @Test
    fun `不同 bucket 的同一个 id 不会互相覆盖`() {
        ActivityStore.inMemory().use { store ->
            store.upsertEvents(listOf(RawEvent(window, awId = 1, tsMs = 0, durMs = 100, app = "a.exe")))
            store.upsertEvents(listOf(RawEvent("aw-watcher-afk_TEST", 1, 0, 100, status = "afk")))
            assertEquals(2, store.count())
        }
    }
}