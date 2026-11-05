package dev.trellis.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityAttributionTest {

    private fun row(
        fromMs: Long,
        toMs: Long,
        taskRef: String,
        source: String = AttributionSource.MANUAL,
        status: String = AttributionStatus.APPLIED,
        id: Long = 0,
    ) = Attribution(
        id = id,
        fromMs = fromMs,
        toMs = toMs,
        taskRef = taskRef,
        source = source,
        status = status,
    )

    @Test
    fun `手动归因优先于 ai，重叠更长的同源归因优先`() {
        val manual = row(0, 10_000, "manual-task", source = AttributionSource.MANUAL, id = 1)
        val ai = row(0, 10_000, "ai-task", source = AttributionSource.AI, id = 2)
        assertEquals("manual-task", Attributions.best(0, 10_000, listOf(ai, manual))?.taskRef)

        val short = row(0, 4_000, "short", id = 3)
        val long = row(0, 9_000, "long", id = 4)
        assertEquals("long", Attributions.best(0, 10_000, listOf(short, long))?.taskRef)
    }

    @Test
    fun `未生效的归因不参与匹配，没有归因时返回 null`() {
        val proposed = row(0, 10_000, "t", status = AttributionStatus.PROPOSED)
        val rejected = row(0, 10_000, "t", status = AttributionStatus.REJECTED)
        assertNull(Attributions.best(0, 10_000, listOf(proposed, rejected)))
        assertNull(Attributions.best(0, 10_000, emptyList()))
    }

    @Test
    fun `重叠时长按交集算，完全不重叠为 0`() {
        val r = row(1_000, 5_000, "t")
        assertEquals(2_000, Attributions.overlapMs(r, 3_000, 9_000))
        assertEquals(0, Attributions.overlapMs(r, 5_000, 9_000))
        assertEquals(0, Attributions.overlapMs(r, 0, 1_000))
    }

    @Test
    fun `同一任务的重叠归因只算一次时长`() {
        val rows = listOf(
            row(0, 6_000, "t"),
            row(4_000, 10_000, "t"),
            row(20_000, 25_000, "t"),
            row(0, 30_000, "other"),
        )
        assertEquals(15_000, Attributions.durationFor("t", rows))
        assertEquals(30_000, Attributions.durationFor("other", rows))
    }

    @Test
    fun `未判断区间是归因表所有行的补集，proposed 与 rejected 也算判断过`() {
        val rows = listOf(
            row(1_000, 3_000, "t", status = AttributionStatus.PROPOSED),
            row(5_000, 7_000, "t", status = AttributionStatus.REJECTED),
            row(6_000, 9_000, "t"),
        )
        val gaps = Attributions.uncovered(0, 12_000, rows)
        assertEquals(listOf(0L until 1_000L, 3_000L until 5_000L, 9_000L until 12_000L), gaps)

        assertEquals(listOf(0L until 12_000L), Attributions.uncovered(0, 12_000, emptyList()))
        // 窗口收窄到 [2000, 8000) 后，只剩 3000–5000 那段没被判断过。
        assertEquals(listOf(3_000L until 5_000L), Attributions.uncovered(2_000, 8_000, rows))
    }

    @Test
    fun `合并区间处理重叠与首尾相接`() {
        val merged = Attributions.mergeRanges(
            listOf(0L until 1_000L, 500L until 2_000L, 2_000L until 3_000L, 5_000L until 6_000L),
        )
        assertEquals(listOf(0L until 3_000L, 5_000L until 6_000L), merged)
        assertTrue(Attributions.mergeRanges(emptyList()).isEmpty())
    }
}