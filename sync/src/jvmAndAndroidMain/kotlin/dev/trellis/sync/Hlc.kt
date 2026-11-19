package dev.trellis.sync

/**
 * 混合逻辑时钟（Hybrid Logical Clock）。
 *
 * 为什么不用纯墙钟：设备时钟会漂、会被用户改；纯墙钟做 LWW 会把「慢钟设备」的
 * 新改动判成旧改动。为什么不用向量时钟：单用户 2–3 台设备，不需要那么重。
 *
 * 编码成字符串 `<毫秒>-<计数器>-<deviceId>`，可直接塞进 JSON、按字典序不能比较，
 * 必须用 [compareTo]（先墙钟、再计数、最后设备 id）。deviceId 里不允许出现 `-`，
 * 由 [SyncStore] 生成。
 */
data class Hlc(
    val wallMillis: Long,
    val counter: Int,
    val deviceId: String,
) : Comparable<Hlc> {

    override fun compareTo(other: Hlc): Int {
        wallMillis.compareTo(other.wallMillis).let { if (it != 0) return it }
        counter.compareTo(other.counter).let { if (it != 0) return it }
        return deviceId.compareTo(other.deviceId)
    }

    fun encode(): String = "$wallMillis-$counter-$deviceId"

    override fun toString(): String = encode()

    companion object {
        private const val MAX_COUNTER = 0xFFFF

        fun parse(raw: String): Hlc? {
            val first = raw.indexOf('-')
            if (first <= 0) return null
            val second = raw.indexOf('-', first + 1)
            if (second <= first + 1 || second >= raw.lastIndex) return null
            val wall = raw.substring(0, first).toLongOrNull() ?: return null
            val counter = raw.substring(first + 1, second).toIntOrNull() ?: return null
            val device = raw.substring(second + 1)
            if (wall < 0 || counter !in 0..MAX_COUNTER || device.isEmpty() || device.contains('-')) return null
            return Hlc(wall, counter, device)
        }
    }
}

/**
 * 本机的 HLC 发生器。
 *
 * - [next]：产生一个严格大于本机历史上任何 HLC 的值（同一毫秒内递增计数器）；
 * - [observe]：看到远端 HLC 后推进本机，保证之后本机产生的值比它大。
 *
 * 同步元数据用真实墙钟（`System.currentTimeMillis`），**不走 TrellisClock**：
 * 调试拨日期不能影响同步顺序。这是 docs/time-conventions.md 的明确例外。
 */
class HlcClock(
    private val deviceId: String,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {

    private var last: Hlc = Hlc(0L, 0, deviceId)

    @Synchronized
    fun next(): Hlc {
        val now = wallClock()
        val wall = maxOf(last.wallMillis, now)
        val counter = if (wall == last.wallMillis) last.counter + 1 else 0
        last = normalize(Hlc(wall, counter, deviceId))
        return last
    }

    @Synchronized
    fun observe(remote: Hlc) {
        val now = wallClock()
        var wall = maxOf(last.wallMillis, now, remote.wallMillis)
        val baseCounter = when {
            wall == last.wallMillis && wall == remote.wallMillis -> maxOf(last.counter, remote.counter)
            wall == last.wallMillis -> last.counter
            wall == remote.wallMillis -> remote.counter
            else -> -1
        }
        val counter = if (baseCounter < 0) 0 else baseCounter + 1
        if (counter > 0xFFFF) {
            wall += 1
            last = Hlc(wall, 0, deviceId)
        } else {
            last = Hlc(wall, counter, deviceId)
        }
    }

    @Synchronized
    fun last(): Hlc = last

    private fun normalize(value: Hlc): Hlc =
        if (value.counter <= 0xFFFF) value else Hlc(value.wallMillis + 1, 0, value.deviceId)
}
