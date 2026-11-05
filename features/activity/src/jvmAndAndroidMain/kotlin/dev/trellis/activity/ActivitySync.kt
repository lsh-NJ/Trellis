package dev.trellis.activity

/**
 * 增量同步：把 aw-server 的原始事件搬进本地库。
 *
 * 三条不变量：
 * 1. **只搬原始事件**，不解分类、不算聚合 —— 那些是上层的事，改规则时不该重采数据。
 * 2. **幂等**：靠 `(bucket, aw_id)` 主键 + 尾部重叠重拉，重复跑多少遍结果一样。
 * 3. **不出网就降级**：AW 没开时返回带 `error` 的报告，不抛异常、不影响界面。
 */
class ActivitySync(
    private val source: ActivitySource,
    private val store: ActivityStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val settings: () -> ActivitySettings = { ActivitySettings() },
) {

    suspend fun syncOnce(): SyncReport {
        val config = settings()
        if (!config.enabled) return SyncReport(skipped = true, nowMs = clock())
        val now = clock()
        return try {
            val buckets = source.buckets().filter { it.type in config.watchedTypes }
            var rows = 0
            var from = Long.MAX_VALUE
            for (bucket in buckets) {
                store.upsertBucket(bucket)
                val start = startFrom(bucket.id, config, now)
                from = minOf(from, start)
                rows += pull(bucket.id, start, now)
                store.setLastSyncedMs(bucket.id, now)
            }
            var pruned = 0
            if (config.retentionDays > 0) {
                pruned = store.pruneBefore(now - config.retentionMs)
            }
            SyncReport(
                buckets = buckets.size,
                rows = rows,
                pruned = pruned,
                fromMs = if (from == Long.MAX_VALUE) now else from,
                nowMs = now,
            )
        } catch (t: Throwable) {
            SyncReport(error = t.message ?: t::class.simpleName ?: "未知错误", nowMs = now)
        }
    }

    /**
     * 起点怎么定：
     * - 库里没有数据 → 往前补 `backfillDays`；
     * - 库里有数据 → 从最晚结束时刻往前退 `overlapSeconds`（覆盖 aw-server 还在延长的尾部）。
     */
    private fun startFrom(bucket: String, config: ActivitySettings, now: Long): Long {
        val known = store.maxEndMs(bucket)
        return if (known <= 0L) now - config.backfillMs else known - config.overlapMs
    }

    /** 按天切窗，避免一次拉一个月的量把内存和 aw-server 都压住。 */
    /**
     * 按天切窗，避免一次拉一个月的量把内存和 aw-server 都压住。
     *
     * 每窗**向两侧多要一截**（[padMs]）：aw-server 会把跨越查询区间边界的事件裁剪到边界上
     * （实测同一个 589 分钟的事件，`?start=05:00&end=06:00` 只给 3600 秒）。不留余量的话，
     * 一条跨天的长事件每次同步都被削掉一截，库里剩下的时长取决于窗口切在哪里 —— 那没有意义。
     */
    private suspend fun pull(bucket: String, fromMs: Long, toMs: Long): Int {
        if (toMs <= fromMs) return 0
        val before = padMs(bucket)
        var rows = pullPass(bucket, fromMs, toMs, before)
        val after = padMs(bucket)
        // 这一轮才第一次见到「跨区间的长事件」（首次补采、或者刚从睡一觉回来）：
        // 用学到的余量再走一遍，把上一遍被裁在边界上的那几条补全。
        // 稳态下 `after == before`，所以这里不会每轮都翻倍请求。
        if (after > before) rows += pullPass(bucket, fromMs, toMs, after)
        return rows
    }

    private suspend fun pullPass(bucket: String, fromMs: Long, toMs: Long, pad: Long): Int {
        var cursor = fromMs
        var rows = 0
        while (cursor < toMs) {
            val chunkEnd = minOf(cursor + ActivitySettings.DAY_MS, toMs)
            rows += pullRange(bucket, cursor - pad, chunkEnd + pad)
            cursor = chunkEnd
        }
        return rows
    }

    /**
     * 补拉余量：至少装得下这个 bucket 里见过的最长事件，最多 [MAX_PAD_MS]。
     *
     * 自适应的原因：睡一晚上 10 小时的 AFK 事件会撑到 589 分钟，但没人会为它写死一个常数；
     * 反过来无上限也不行 —— 万一 aw-server 里有一条坏了的事件（比如一整年），
     * 每次同步就会去拉一年的量。
     */
    private fun padMs(bucket: String): Long = store.maxDurMs(bucket).coerceIn(0L, MAX_PAD_MS)

    private companion object {
        /** 补拉余量的上限：三天。 */
        const val MAX_PAD_MS: Long = 3 * ActivitySettings.DAY_MS
    }
    private suspend fun pullRange(bucket: String, fromMs: Long, toMs: Long): Int {
        val events = source.events(bucket, fromMs, toMs)
        if (events.isEmpty()) return 0
        // 拿满上限说明这一窗可能被 aw-server 截断了：对半切再拉，不允许静默丢中间段。
        if (events.size >= ActivitySource.DEFAULT_LIMIT && toMs - fromMs > 60_000L) {
            val mid = fromMs + (toMs - fromMs) / 2
            return pullRange(bucket, fromMs, mid) + pullRange(bucket, mid, toMs)
        }
        return store.upsertEvents(events)
    }
}

/** 一轮同步的结果。`error != null` 时其余字段可能是一半的。 */
data class SyncReport(
    val buckets: Int = 0,
    val rows: Int = 0,
    val pruned: Int = 0,
    val fromMs: Long = 0,
    val nowMs: Long = 0,
    val skipped: Boolean = false,
    val error: String? = null,
) {
    val ok: Boolean get() = error == null
}