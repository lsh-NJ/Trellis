package dev.trellis.app.desktop

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.steward.StewardContext
import dev.trellis.ai.steward.StewardOverdueDay
import dev.trellis.ai.steward.StewardPrompt
import dev.trellis.ai.steward.StewardRitual
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 仪式要用的计划事实。由 AppRuntime 从 `LearningPlanService` 的 view model 现算；
 * 运行时只排版和决定「该不该说话」，不直接碰数据库，测试就不用起整个 AppRuntime。
 */
internal data class StewardPlanFacts(
    val planTitle: String?,
    val plannedToday: Int,
    val doneToday: Int,
    val todayMinutes: Int,
    /** 本周逾期，按天分组：ISO 日期 → 件数。 */
    val overdueThisWeek: Map<String, Int> = emptyMap(),
    val backlogCount: Int = 0,
    val remainingMinutesInWeek: Int? = null,
)

/** 运行时与宿主之间的全部出入口；测试用假实现，生产实现挂在 AppRuntime 上。 */
internal interface StewardPorts {
    /** 读今天的计划事实；没有活动计划返回 null（没有可说的就不说）。 */
    suspend fun planFacts(today: LocalDate): StewardPlanFacts?

    /** 往管家会话追加一条主动消息。会话正忙（用户在对话里）返回 false：这次不消耗 checkpoint，稍后重试。 */
    suspend fun appendProactive(sessionId: String, text: String, proposal: StewardProposal?): Boolean

    /** 生成一条模板化建议（从计划事实里按规则挑）；没有可改的就返回 null。 */
    suspend fun proposal(today: LocalDate): StewardProposal?

    /** 给模型润色；没配置、失败、超时都返回 null，调用方回退确定性模板。 */
    suspend fun polish(messages: List<LlmMessage>): String?

    /** 系统通知；平台不支持时实现自己吞掉。 */
    fun notify(title: String, body: String)

    /** 应用在前台：不弹系统通知，只在会话里留未读。 */
    fun appFocused(): Boolean
}

/** 一次检查的结论，给调用方记日志/测试断言用。 */
internal enum class StewardRunResult {
    /** 今天该发的都发过了，或两个仪式都关着。 */
    Nothing,

    /** 只发了晨间。 */
    Morning,

    /** 只发了晚间。 */
    Evening,

    /** 昨晚收尾 + 今晨合并成一条发了。 */
    MorningWithEveningCatchUp,

    /** 该发了，但会话正忙：不消耗 checkpoint，下次 tick 再来。 */
    Busy,
}

/**
 * 管家的仪式引擎：决定「现在该不该主动说话」，组织事实、润色、落盘。
 *
 * 触发点是状态事件（应用启动/窗口重新获得焦点）+ 应用运行中的每分钟 tick，
 * 不是独立定时器：程序关着就不存在「到点发消息」，也没法在关着时通知你。
 * 是否重复由 [StewardStore.checkpoints] 决定，同一个应用内日期每个仪式最多一次。
 */
internal class StewardRuntime(
    private val store: StewardStore,
    private val ports: StewardPorts,
    private val clock: () -> Long,
    private val zone: ZoneId,
) {
    private val mutex = Mutex()

    fun settings(): StewardSettings = store.settings()

    suspend fun check(): StewardRunResult = mutex.withLock {
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), zone)
        val today = now.toLocalDate()
        val todayKey = today.toString()
        val yesterdayKey = today.minusDays(1).toString()
        val settings = store.settings()
        val checkpoints = store.checkpoints()

        val morningDue = settings.morningEnabled && checkpoints.lastMorningDay != todayKey
        val eveningDue = settings.eveningEnabled &&
            checkpoints.lastEveningDay != todayKey &&
            !now.toLocalTime().isBefore(LocalTime.parse(settings.eveningTime))
        // 昨晚的收尾没发出去：今天第一次打开时补上（不单独发两条，见下面的合并规则）。
        // 从没发过晚间（刚装/刚升级）不算「昨晚漏了」——那时候还没有可补的收尾。
        val eveningCatchUp = settings.eveningEnabled &&
            checkpoints.lastEveningDay != null &&
            checkpoints.lastEveningDay != yesterdayKey

        val facts = ports.planFacts(today) ?: return@withLock StewardRunResult.Nothing

        // 已经过了晚间时间：先发今晚的收尾；晨间来不及就跳过（把 checkpoint 一起写上，
        // 免得每次 tick 又想补发一条上午的问候）。
        if (eveningDue) {
            val polished = polish(StewardRitual.Evening, today, facts)
            if (send(polished, today, StewardMemoryKind.Evening, digest = true)) {
                store.saveCheckpoints(
                    checkpoints.copy(lastMorningDay = todayKey, lastEveningDay = todayKey),
                )
                return@withLock StewardRunResult.Evening
            }
            return@withLock StewardRunResult.Busy
        }

        if (!morningDue) return@withLock StewardRunResult.Nothing

        // 合并规则（用户 m03368 定的）：打开时昨晚的收尾也错过了，就和今晨合成一条。
        val ritual = if (eveningCatchUp) {
            StewardRitual.MorningWithEveningCatchUp
        } else {
            StewardRitual.Morning
        }
        val text = polish(ritual, today, facts)
        if (!send(text, today, StewardMemoryKind.Morning, digest = false)) {
            return@withLock StewardRunResult.Busy
        }
        store.saveCheckpoints(
            checkpoints.copy(
                lastMorningDay = todayKey,
                lastEveningDay = if (eveningCatchUp) yesterdayKey else checkpoints.lastEveningDay,
            ),
        )
        return@withLock if (eveningCatchUp) {
            StewardRunResult.MorningWithEveningCatchUp
        } else {
            StewardRunResult.Morning
        }
    }

    /**
     * 开发者调试：立刻按 [ritual] 发一次，不管到没到点、今天发过没有。
     *
     * 和 [check] 的区别只有两处：不读也不写 checkpoints（同一个应用内日期可以反复触发），
     * 强制触发时即使应用在前台也尝试弹一次系统通知。消息、记忆、摘要、建议卡都走
     * 与真实仪式完全相同的路径——调它就是在验证那条路径。
     */
    suspend fun forceRun(ritual: StewardRitual): StewardRunResult = mutex.withLock {
        val today = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(clock()), zone).toLocalDate()
        val facts = ports.planFacts(today) ?: return@withLock StewardRunResult.Nothing
        val text = polish(ritual, today, facts)
        val kind = when (ritual) {
            StewardRitual.Morning, StewardRitual.MorningWithEveningCatchUp -> StewardMemoryKind.Morning
            StewardRitual.Evening -> StewardMemoryKind.Evening
        }
        if (!send(text, today, kind, digest = ritual == StewardRitual.Evening, forceNotify = true)) {
            return@withLock StewardRunResult.Busy
        }
        when (ritual) {
            StewardRitual.Morning -> StewardRunResult.Morning
            StewardRitual.Evening -> StewardRunResult.Evening
            StewardRitual.MorningWithEveningCatchUp -> StewardRunResult.MorningWithEveningCatchUp
        }
    }

    /** 建议被采纳/被「先这样」：记一条，下一次仪式能读到上次的结论。 */
    fun rememberProposalDecision(day: LocalDate, adopted: Boolean, summary: String) {
        store.appendMemory(
            StewardMemoryEvent(
                at = clock(),
                day = day.toString(),
                kind = if (adopted) StewardMemoryKind.Adopted else StewardMemoryKind.Rejected,
                text = summary.take(200),
            ),
        )
    }

    /** 追加记忆里的「用户对管家的回应」。用户在管家会话里说话时由 AppRuntime 调。 */
    fun rememberUserReply(day: LocalDate, quote: String) {
        store.appendMemory(
            StewardMemoryEvent(
                at = clock(),
                day = day.toString(),
                kind = StewardMemoryKind.UserReply,
                text = quote.take(200),
                quote = quote.take(200),
            ),
        )
    }

    private suspend fun polish(ritual: StewardRitual, today: LocalDate, facts: StewardPlanFacts): String {
        val context = contextFor(ritual, today, facts)
        val polished = runCatching { ports.polish(StewardPrompt.messages(ritual, context)) }.getOrNull()
        return polished?.trim()?.takeIf { it.isNotBlank() }
            ?: StewardPrompt.template(ritual, context)
    }

    private fun contextFor(
        ritual: StewardRitual,
        today: LocalDate,
        facts: StewardPlanFacts,
    ): StewardContext {
        val yesterday = today.minusDays(1)
        val digest = store.digest(yesterday.toString())
        val merged = ritual == StewardRitual.MorningWithEveningCatchUp
        return StewardContext(
            dayLabel = stewardDayLabel(today),
            planTitle = facts.planTitle,
            plannedToday = facts.plannedToday,
            doneToday = facts.doneToday,
            todayMinutes = facts.todayMinutes,
            overdueThisWeek = facts.overdueThisWeek.entries
                .mapNotNull { (day, count) ->
                    runCatching { LocalDate.parse(day) }.getOrNull()?.let { StewardOverdueDay(stewardDayLabel(it), count) }
                }
                .sortedBy { it.dayLabel },
            backlogCount = facts.backlogCount,
            remainingMinutesInWeek = facts.remainingMinutesInWeek,
            yesterdayDigest = if (merged) null else digest,
            recentMemory = store.recentMemory(today.toString(), days = 7, limit = 20).map { event ->
                "${event.day.takeLast(5)} ${stewardKindLabel(event.kind)}：${event.text.take(60)}"
            },
            catchUpEvening = if (merged) digest else null,
        )
    }

    private suspend fun send(
        text: String,
        today: LocalDate,
        kind: StewardMemoryKind,
        digest: Boolean,
        forceNotify: Boolean = false,
    ): Boolean {
        val body = text.trim().takeIf { it.isNotBlank() } ?: return false
        val proposal = runCatching { ports.proposal(today) }.getOrNull()
        if (!ports.appendProactive(StewardRuntime.STEWARD_SESSION_ID, body, proposal)) return false
        store.appendMemory(
            StewardMemoryEvent(
                at = clock(),
                day = today.toString(),
                kind = kind,
                text = body.take(400),
                topics = emptyList(),
            ),
        )
        if (digest) store.saveDigest(today.toString(), body)
        if (store.settings().notifyDesktop && (forceNotify || !ports.appFocused())) {
            runCatching { ports.notify(StewardRuntime.STEWARD_TITLE, body.take(120)) }
        }
        return true
    }

    companion object {
        const val STEWARD_SESSION_ID = "steward"
        const val STEWARD_TITLE = "管家"
    }
}

/** 应用内日期 → 「11月4日 周二」。 */
internal fun stewardDayLabel(date: LocalDate): String {
    val weekday = when (date.dayOfWeek) {
        java.time.DayOfWeek.MONDAY -> "周一"
        java.time.DayOfWeek.TUESDAY -> "周二"
        java.time.DayOfWeek.WEDNESDAY -> "周三"
        java.time.DayOfWeek.THURSDAY -> "周四"
        java.time.DayOfWeek.FRIDAY -> "周五"
        java.time.DayOfWeek.SATURDAY -> "周六"
        else -> "周日"
    }
    return "${date.monthValue}月${date.dayOfMonth}日 $weekday"
}

private fun stewardKindLabel(kind: StewardMemoryKind): String = when (kind) {
    StewardMemoryKind.Morning -> "晨"
    StewardMemoryKind.Evening -> "晚"
    StewardMemoryKind.UserReply -> "你"
    StewardMemoryKind.Adopted -> "采纳"
    StewardMemoryKind.Rejected -> "没采纳"
    StewardMemoryKind.Note -> "记"
    StewardMemoryKind.Goal -> "目标"
    StewardMemoryKind.Preference -> "偏好"
}
