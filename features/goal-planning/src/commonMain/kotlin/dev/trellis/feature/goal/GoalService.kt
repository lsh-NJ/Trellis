package dev.trellis.feature.goal

import dev.trellis.kernel.time.KernelClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 今天实际投入了多少。
 *
 * [hasAfkData] 不是附带信息，它决定这个数**能不能和计划比**：只装了窗口采集时，
 * `activeMs` 就是「窗口开着的时间」，人离开电脑那一段照样算进去。
 * 把它当成「真实专注时长」去对计划，用户会以为自己在偷懒。
 */
data class TodayEffort(
    val minutes: Int,
    /** 这一天的数据里有没有 AFK 记录。false 表示「窗口开着」，不等于「人在」。 */
    val hasAfkData: Boolean,
) {
    /**
     * 值得显示吗。
     *
     * 0 分不显示：那更常见的意思是「没同步 / 没开采集」，而不是「今天什么都没干」。
     * 把两者混在一起显示，用户第一次看到就会觉得这个功能在骗他。
     */
    val visible: Boolean get() = minutes > 0
}

/**
 * 一句话被认成了某个字段的答案。
 *
 * 带上现成的回话，是为了让宿主不必知道「下一个该问什么」——
 * 那句话本身就是插件对这次写入的解释，谁调用谁直接显示。
 */
data class CapturedAnswer(val field: FieldKey, val message: String)

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
    /**
     * 今天的实际投入。**可选依赖**：宿主没接活动采集、或今天还没同步过，
     * 就返回 null，界面直接不显示这一行 —— 不能显示成 0，那会被读成「今天什么都没干」。
     *
     * 用 lambda 而不是 `ctx.services`：活动采集是宿主层能力，没有发布成服务契约。
     * 硬 import `:features:activity` 会让插件和某个具体实现绑死。
     */
    private val effort: suspend () -> TodayEffort? = { null },
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

    /** 今天的实际投入；没有活动数据时 null（不是 0）。 */
    suspend fun todayEffort(): TodayEffort? = effort()

    /**
     * 回答一个字段：写进契约，并把「问到第几轮」一起推进。
     *
     * 卡片按钮、输入框、模型的工具三条路都走这一个入口。三处各写一遍的话，
     * 迟早有一处忘了记轮数，那个入口就可以无限追问下去 —— 这正是之前的状态。
     */
    suspend fun answer(key: FieldKey, value: String): GoalMirror = update { mirror ->
        val answered = GoalEngine.answer(mirror, key, value, FieldState.CONFIRMED, clock.nowMs())
        answered.copy(collectingRounds = GoalEngine.roundsOf(answered.contract))
    }

    /** 跳过：记 UNKNOWN，两次之后这个字段降权并不再追问。 */
    suspend fun skip(key: FieldKey): GoalMirror = update { mirror ->
        val skipped = GoalEngine.skip(mirror, key, clock.nowMs())
        skipped.copy(collectingRounds = GoalEngine.roundsOf(skipped.contract))
    }

    /** 出草稿（今天 + 明天）。只做状态转移，不替用户确认。 */
    suspend fun draft(dailyMinutes: Int): GoalMirror =
        update { mirror -> GoalEngine.toDraft(mirror, today(), dailyMinutes.coerceIn(5, 240)) }

    /**
     * 用户把答案打在对话里时，先把这句话记进契约。
     *
     * 为什么需要这一条：对话那一轮走的意图分类器只看**用户这一句话**，
     * 「4 小时以上」这种短答会被判成聊天，而聊天按设计不给任何工具 ——
     * 模型只能回一句「我这没有能记录的工具」，用户看到的就是「功能坏了」。
     * 所以答案在进模型之前就被识别并落库，模型只需要接着聊。
     *
     * 只在「正在收集、且这句话恰好等于当前某个选项」时生效：宁可漏记，不可误记。
     * 漏了还有卡片上的按钮兜底；误记则是把用户没说过的话写进了他的契约。
     */
    suspend fun captureSpokenAnswer(text: String): CapturedAnswer? {
        val mirror = mirror()
        if (mirror.phase != GoalPhase.COLLECTING) return null
        val clean = text.trim().trimEnd('。', '.', '！', '!', '，', ',')
        if (clean.isEmpty()) return null
        val asking = GoalEngine.questions(mirror)
        if (asking.isEmpty()) return null

        // 先认「正好是某个选项」，这是最可靠的；认不出来再看形状。
        val hit = asking.firstOrNull { key -> GoalQuestions.options(key).any { it == clean } }
            ?: asking.firstOrNull { key -> clean.length <= 20 && looksLikeAnswer(key, clean) }
            ?: return null

        val after = answer(hit, clean)
        val next = GoalEngine.questions(after).firstOrNull()
        val message = "记下了：".plus(hit.label).plus(" = ").plus(clean).plus("。").plus(
            if (next == null) {
                "信息够了，右边卡片上点「出计划」。"
            } else {
                "下一个：".plus(next.label).plus("（点右边的选项，或直接说）")
            },
        )
        return CapturedAnswer(hit, message)
    }

    /**
     * 「这句话像不像那个字段的答案」。
     *
     * 只给两个字段开这个口子，因为只有它们的答案**一定带形状**：
     * 时长会带「小时 / 分钟」，截止时间会带「个月 / 半年 / 明年」。
     * 别的字段（比如「当前水平」）自由发挥的空间太大，按形状猜会把「今天太累了」
     * 记成「零基础」——那比不记更糟。
     *
     * 这个判断只在**该字段正好是当前要问的那一个**时才会跑，所以「2 小时」这句话
     * 出现在别的问题上不会被误收。
     */
    private fun looksLikeAnswer(key: FieldKey, text: String): Boolean = when (key) {
        FieldKey.DAILY_MINUTES ->
            text.contains("小时") || text.contains("分钟") || text.contains("半小时")
        FieldKey.DEADLINE ->
            text.contains("个月") || text.contains("半年") || text.contains("年") || text.contains("月")
        else -> false
    }

    private fun hourOfDay(): Int = ((clock.nowMs() / 3_600_000L) % 24L).toInt()

    private fun minuteOfHour(): Int = ((clock.nowMs() / 60_000L) % 60L).toInt()
}
