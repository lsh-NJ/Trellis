package dev.trellis.kernel.time

/**
 * 内核时钟。设计文档 8：
 * 内核与插件一律禁止直接使用系统墙钟，否则测试与沙箱干跑都做不了。
 */
interface KernelClock {
    /** 墙钟，Unix 毫秒。可用 FakeClock 快进。 */
    fun nowMs(): Long

    /** 单调时钟，仅用于测量耗时，不受墙钟调整影响。 */
    fun monotonicMs(): Long
}

/** 平台原语：这是 expect/actual 唯一被允许的使用场景。 */
expect fun platformCurrentTimeMillis(): Long

/** 平台原语：单调毫秒。 */
expect fun platformMonotonicTimeMs(): Long

object SystemClock : KernelClock {
    override fun nowMs(): Long = platformCurrentTimeMillis()
    override fun monotonicMs(): Long = platformMonotonicTimeMs()
}

/** 测试与沙箱干跑用的假时钟。手工推进时间。 */
class FakeClock(private var wallMs: Long = 0L) : KernelClock {

    private var monotonic: Long = 0L

    override fun nowMs(): Long = wallMs

    override fun monotonicMs(): Long = monotonic

    fun advanceBy(ms: Long) {
        require(ms >= 0) { "时间不能倒流" }
        wallMs += ms
        monotonic += ms
    }

    fun setWallClock(ms: Long) {
        wallMs = ms
    }
}