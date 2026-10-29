package dev.trellis.feature.goal

import dev.trellis.kernel.time.KernelClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 插件对外的唯一门面：缓存 + 结算 + 推送，都在这里。
 *
 * 为什么不把 kv 读写散到三个卡片里：三张卡同时渲染时，各自 load 一次会读到
 * 三份不同的快照（一张卡显示 3/5、另一张显示 2/5），而用户会以为数据坏了。
 */
class GoalService(
    private val kit: GoalKit,
    private val clock: KernelClock,
    /** 每天能投入多少分钟。宿主注入（读 study.json），插件不自己存一份。 */
    private val dailyMinutes: () -> Int,
    /** 今天（ISO 日期）。宿主注入：common 里没有时区，猜一个会比不猜更糟。 */
    private val today: () -> String,
) {

    private val mutex = Mutex()
    private var cache: GoalMirror? = null

    /** 今天该显示的那条推送。空 = 今天没有推送（没到点 / 已经看过 / 不是 ACTIVE）。 */
    var pushNote: String? = null
        private set

    suspend fun mirror(): GoalMirror = mutex.withLock { loadLocked() }

    private suspend fun loadLocked(): GoalMirror {
        cache?.let { return it }
        val loaded = kit.load()
        cache = loaded
        return loaded
    }

    /**
     * 启动/进入时跑一次：结算昨天、补排明天、按日期键补推。
     *
     * 幂等由 [GoalEngine.settle] 保证：同一天第二次调用 changed = false，不写盘。
     */
    suspend fun refresh(): GoalMirror = mutex.withLock {
        val current = loadLocked()
        if (current.isEmpty) return@withLock current

        var working = current
        val settled = GoalEngine.settle(working, today(), dailyMinutes())
        if (settled.changed) working = settled.mirror

        val note = GoalEngine.pushDue(working, today(), hourOfDay(), minuteOfHour())
        if (note != null) {
            working = note.mirror
            pushNote = note.text
        }

        if (working != current) {
            kit.save(working)
            cache = working
        }
        working
    }

    /** 改一份状态并落盘。所有写入都从这里过，避免两处并发写出两份。 */
    suspend fun update(block: (GoalMirror) -> GoalMirror): GoalMirror = mutex.withLock {
        val current = loadLocked()
        val next = block(current)
        if (next != current) {
            kit.save(next)
            cache = next
        }
        next
    }

    /** 强刷（取消草稿、重置之后用）。 */
    suspend fun invalidate() {
        mutex.withLock { cache = null }
    }

    /** 删除这份规划：业务数据清空，插件本体保留，状态回 EMPTY。 */
    suspend fun reset(): GoalMirror = mutex.withLock {
        val current = loadLocked()
        val next = GoalEngine.reset(current, today())
        kit.clear()
        kit.save(next)
        cache = next
        pushNote = null
        next
    }

    private fun hourOfDay(): Int = ((clock.nowMs() / 3_600_000L) % 24L).toInt()

    private fun minuteOfHour(): Int = ((clock.nowMs() / 60_000L) % 60L).toInt()
}
