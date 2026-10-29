package dev.trellis.feature.goal

import kotlinx.serialization.Serializable

/**
 * 插件状态。
 *
 * 每个状态在界面上都必须**可区分**：用户得知道「它是在问我问题、在等我确认、
 * 还是在正常推进」。六合一糊成一个「已开启」，出了事没人知道卡在哪。
 */
@Serializable
enum class GoalPhase {
    /** 还没有目标。唯一显示的是「开始规划 →」。 */
    EMPTY,

    /** 在问问题。界面显示「还差 N 个信息」。 */
    COLLECTING,

    /** 通用计划已出，等用户点确认。 */
    DRAFT,

    /** 每日推进。 */
    ACTIVE,

    /** 反馈周期到了，等复盘。 */
    REVIEWING,

    /** 用户删了这份规划。业务数据已清，插件本体还在。 */
    ARCHIVED,
}

/** 插件自己的设置（和宿主 study.json 分开：那个是全局学习节奏，这个是这份规划的节奏）。 */
@Serializable
data class GoalSettings(
    /** 复盘周期，天。 */
    val feedbackCycleDays: Int = DEFAULT_CYCLE_DAYS,
    /** 每天推送的小时（0–23）。 */
    val pushHour: Int = DEFAULT_PUSH_HOUR,
    val pushMinute: Int = 0,
) {
    fun normalized(): GoalSettings = copy(
        feedbackCycleDays = feedbackCycleDays.coerceIn(MIN_CYCLE_DAYS, MAX_CYCLE_DAYS),
        pushHour = pushHour.coerceIn(0, 23),
        pushMinute = pushMinute.coerceIn(0, 59),
    )

    fun pushLabel(): String =
        pushHour.toString().padStart(2, '0') + ":" + pushMinute.toString().padStart(2, '0')

    companion object {
        const val DEFAULT_CYCLE_DAYS = 10
        const val MIN_CYCLE_DAYS = 3
        const val MAX_CYCLE_DAYS = 30
        const val DEFAULT_PUSH_HOUR = 8
    }
}

/**
 * 插件落盘的全部状态。
 *
 * 一份整体而不是十几个分散的 key：读的时候要么全读到、要么全退回默认，
 * 不会出现「契约读到了、日计划没读到」这种半截状态 —— 那种状态最难查，
 * 因为界面看起来正常，只是某一块永远是空的。
 */
@Serializable
data class GoalMirror(
    val phase: GoalPhase = GoalPhase.EMPTY,
    val contract: GoalContract = GoalContract(),
    val milestone: Milestone? = null,
    val days: Map<String, DayPlan> = emptyMap(),
    val dayDigests: List<DayDigest> = emptyList(),
    val weekDigests: List<WeekDigest> = emptyList(),
    val phaseDigest: PhaseDigest? = null,
    val settings: GoalSettings = GoalSettings(),
    /** 同一天最多推一条：这是幂等的唯一依据。 */
    val lastPushedDate: String? = null,
    /** 已经问过几轮。到 [Confidence.MAX_ROUNDS] 就出草稿，不再问。 */
    val collectingRounds: Int = 0,
    /** 上次结算到哪一天。启动时用它判断要不要补结算。 */
    val settledThrough: String? = null,
    /** 复盘周期起算日。 */
    val cycleStartedOn: String? = null,
) {
    val isEmpty: Boolean get() = phase == GoalPhase.EMPTY || phase == GoalPhase.ARCHIVED

    fun dayPlan(date: String): DayPlan? = days[date]
}

/** 状态转移。只允许表里的边，别的组合直接说「不能从 X 到 Y」。 */
object GoalStateMachine {

    private val EDGES: Map<GoalPhase, Set<GoalPhase>> = mapOf(
        GoalPhase.EMPTY to setOf(GoalPhase.COLLECTING),
        GoalPhase.COLLECTING to setOf(GoalPhase.DRAFT, GoalPhase.EMPTY),
        GoalPhase.DRAFT to setOf(GoalPhase.ACTIVE, GoalPhase.COLLECTING, GoalPhase.EMPTY),
        GoalPhase.ACTIVE to setOf(GoalPhase.REVIEWING, GoalPhase.ARCHIVED),
        GoalPhase.REVIEWING to setOf(GoalPhase.ACTIVE, GoalPhase.ARCHIVED),
        GoalPhase.ARCHIVED to setOf(GoalPhase.EMPTY, GoalPhase.COLLECTING),
    )

    fun canMove(from: GoalPhase, to: GoalPhase): Boolean =
        from == to || EDGES[from]?.contains(to) == true

    fun move(mirror: GoalMirror, to: GoalPhase): Result<GoalMirror> {
        if (!canMove(mirror.phase, to)) {
            return Result.failure(
                IllegalStateException(
                    "状态不能从 " + mirror.phase + " 变成 " + to +
                        "；当前允许的下一步是 " + EDGES[mirror.phase].orEmpty().joinToString("、"),
                ),
            )
        }
        return Result.success(mirror.copy(phase = to))
    }

    /** 界面上那句「还差 N 个信息」。 */
    fun describe(mirror: GoalMirror): String = when (mirror.phase) {
        GoalPhase.EMPTY -> "还没有目标，开始规划 →"
        GoalPhase.COLLECTING -> "还差 " + Confidence.remaining(mirror.contract) + " 个信息"
        GoalPhase.DRAFT -> "计划已生成，等你确认"
        GoalPhase.ACTIVE -> {
            val today = mirror.days.values.maxByOrNull { it.date }
            if (today == null) "今天还没排" else "今天 " + today.doneCount + "/" + today.entries.size
        }
        GoalPhase.REVIEWING -> "复盘周期到了"
        GoalPhase.ARCHIVED -> "这份规划已删除"
    }
}
