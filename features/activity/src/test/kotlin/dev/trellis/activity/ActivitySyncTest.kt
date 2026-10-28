package dev.trellis.activity

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivitySyncTest {

    private val window = "aw-watcher-window_T"
    private val afk = "aw-watcher-afk_T"
    private val now = 1_700_000_000_000L

    private class FakeSource(
        private val bucketList: List<AwBucket>,
        private val events: List<RawEvent>,
        var failing: Boolean = false,
    ) : ActivitySource {
        var calls: Int = 0

        /** 每次被问的区间，用来断言「补拉余量」这件事。 */
        val ranges: MutableList<Pair<Long, Long>> = mutableListOf()

        override suspend fun info(): JsonObject = JsonObject(emptyMap())

        override suspend fun buckets(): List<AwBucket> {
            if (failing) error("aw-server 连不上")
            return bucketList
        }

        override suspend fun events(bucket: String, fromMs: Long, toMs: Long, limit: Int): List<RawEvent> {
            calls++
            ranges += fromMs to toMs
            return events.filter { it.bucket == bucket }
        }
    }

    private fun source() = FakeSource(
        bucketList = listOf(
            AwBucket(window, "currentwindow", "H"),
            AwBucket(afk, "afkstatus", "H"),
        ),
        events = listOf(
            RawEvent(window, awId = 1, tsMs = now - 60_000, durMs = 30_000, app = "code.exe", title = "Trellis"),
            RawEvent(afk, awId = 2, tsMs = now - 60_000, durMs = 30_000, status = "not-afk"),
        ),
    )

    @Test
    fun `首轮把两个 bucket 的原始事件都收进来`() = runTest {
        ActivityStore.inMemory().use { store ->
            val report = ActivitySync(source(), store, clock = { now }).syncOnce()
            assertNull(report.error)
            assertTrue(report.ok)
            assertEquals(2, report.buckets)
            assertEquals(2, store.count())
            assertEquals(2, store.buckets().size)
        }
    }

    @Test
    fun `连跑两轮不会插重复`() = runTest {
        ActivityStore.inMemory().use { store ->
            val source = source()
            ActivitySync(source, store, clock = { now }).syncOnce()
            val second = ActivitySync(source, store, clock = { now + 60_000 }).syncOnce()
            assertNull(second.error)
            assertEquals(2, store.count(), "幂等性靠 (bucket, aw_id) 主键")
        }
    }

    @Test
    fun `第二轮只补尾部重叠窗口，不重扫历史`() = runTest {
        ActivityStore.inMemory().use { store ->
            val source = source()
            ActivitySync(source, store, clock = { now }).syncOnce()
            val afterFirst = source.calls
            ActivitySync(source, store, clock = { now + 60_000 }).syncOnce()
            val delta = source.calls - afterFirst
            // 首轮按天切窗扫 backfillDays 天 × 2 个 bucket；
            // 第二轮从 maxEnd - overlap 拉到现在，落在同一天内 → 每个 bucket 只一次。
            assertEquals(2, delta, "第二轮不该重扫历史，只补尾部重叠窗口")
        }
    }

    @Test
    fun `超出保留期的原始事件会被清掉`() = runTest {
        ActivityStore.inMemory().use { store ->
            val config = ActivitySettings(retentionDays = 1, backfillDays = 30)
            val later = now + 10L * ActivitySettings.DAY_MS
            val report = ActivitySync(source(), store, clock = { later }, settings = { config }).syncOnce()
            assertNull(report.error)
            assertEquals(2, report.pruned)
            assertEquals(0, store.count())
        }
    }

    @Test
    fun `总开关关掉时直接返回 skipped，不访问网络`() = runTest {
        ActivityStore.inMemory().use { store ->
            val source = source()
            val report = ActivitySync(source, store, clock = { now }, settings = { ActivitySettings(enabled = false) })
                .syncOnce()
            assertTrue(report.skipped)
            assertEquals(0, source.calls)
            assertEquals(0, store.count())
        }
    }

    @Test
    fun `补拉余量按最长事件自适应，绕开 aw-server 的边界裁剪`() = runTest {
        ActivityStore.inMemory().use { store ->
            val source = source()
            ActivitySync(source, store, clock = { now }).syncOnce()

            // 首轮库是空的 → 没有余量；拉完之后库里最长事件 30 秒 → 第二轮余量变成 30 秒。
            val mark = source.ranges.size
            val later = now + 600_000
            ActivitySync(source, store, clock = { later }).syncOnce()

            val first = source.ranges[mark]
            val start = (now - 30_000) - 300_000L // maxEnd - overlapSeconds
            assertEquals(start - 30_000L, first.first, "请求区间必须比同步起点更早，否则长事件会被裁到边界上")
            assertTrue(first.second >= later, "右侧也要留余量")
        }
    }
    @Test
    fun `aw-server 没开时返回带 error 的报告而不是抛异常`() = runTest {
        ActivityStore.inMemory().use { store ->
            val source = source().apply { failing = true }
            val report = ActivitySync(source, store, clock = { now }).syncOnce()
            assertNotNull(report.error)
            assertTrue(!report.ok)
        }
    }
}