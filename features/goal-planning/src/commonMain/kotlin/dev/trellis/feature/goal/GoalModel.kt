package dev.trellis.feature.goal

import kotlinx.serialization.Serializable

/**
 * 目标有多清楚。
 *
 * 这个枚举不是标签，是**生成策略的开关**：VAGUE/DIRECTED 下禁止出现具体院校/公司，
 * 必须先给通用层；只有 CONCRETE 才允许针对性内容。把它当成一个装饰字段，
 * 就会生成出一份「假装知道用户要考哪儿」的计划 —— 那是这个功能最坏的失败形态。
 */
@Serializable
enum class Clarity { VAGUE, DIRECTED, CONCRETE }

/** 目标是哪一类，决定「必备字段」用哪一套。 */
@Serializable
enum class GoalKind { GENERAL, ACADEMIC, JOB }

/**
 * 一个画像字段的可靠程度。
 *
 * 这三档是置信度公式的输入，所以必须是**可观察的事实**（用户说了 / 从上下文推出 / 完全不知道），
 * 不是「模型觉得有多确定」。模型自评不可测、不可复现，同一个输入两次跑出两个数。
 */
@Serializable
enum class FieldState {
    UNKNOWN,
    INFERRED,
    CONFIRMED;

    /** 计分：确认 1.0，推断 0.7，不知道 0。 */
    val score: Double
        get() = when (this) {
            CONFIRMED -> 1.0
            INFERRED -> 0.7
            UNKNOWN -> 0.0
        }
}

/**
 * 画像字段。两套必备字段共用这一套键，避免「学业类一个模型、求职类一个模型」。
 */
@Serializable
enum class FieldKey(val label: String) {
    // 通用
    DAILY_MINUTES("每天能投入多久"),
    DEADLINE("截止时间"),
    CURRENT_LEVEL("当前水平"),
    HARD_CONSTRAINTS("不可协商的条件"),

    // 学业
    MAJOR("专业"),
    TARGET_TIER("目标层次"),
    WEAK_SUBJECTS("薄弱科目"),
    EXAM_SUBJECTS("考试科目"),

    // 求职
    TARGET_ROLE("目标岗位"),
    TARGET_INDUSTRY("目标行业"),
    EXPERIENCE("经验"),
    PORTFOLIO("作品集状态"),
}

/**
 * 一个字段的当前值。
 *
 * [skips] 是「连续跳过几次」。跳过 2 次后权重降到 0.5 并停止追问 ——
 * 这条是防「为了把置信度凑到 0.95 反复盘问同一个点」的：
 * 用户已经说了两次不知道，第三次问只会让他关掉这个功能。
 */
@Serializable
data class Field(
    val value: String = "",
    val state: FieldState = FieldState.UNKNOWN,
    val weight: Double = 1.0,
    val skips: Int = 0,
) {
    val resolved: Boolean get() = state != FieldState.UNKNOWN
}

/** 变更来源。留痕是为了回答「这个数是谁改的」。 */
@Serializable
enum class RevisionSource { DIALOGUE, FEEDBACK, MANUAL, MODEL_ADJUSTMENT }

/** 一条变更记录。 */
@Serializable
data class Revision(
    val atMs: Long,
    val field: String,
    val oldValue: String,
    val newValue: String,
    val source: RevisionSource,
    val reason: String = "",
)

/**
 * 目标契约：用户原话 + 结构化画像 + 变更史。
 *
 * [statement] **不改写**。它是用户的原话，任何「帮你润色一下」都会让之后所有的
 * 判断失去锚点（用户说「想考研」，模型写成「备考 985 计算机」，后面全错）。
 */
@Serializable
data class GoalContract(
    val statement: String = "",
    val clarity: Clarity = Clarity.VAGUE,
    val kind: GoalKind = GoalKind.GENERAL,
    val startDate: String = "",
    val targetDate: String? = null,
    val hardConstraints: List<String> = emptyList(),
    val profile: Map<FieldKey, Field> = emptyMap(),
    val unknownDecisions: List<String> = emptyList(),
    val revisionLog: List<Revision> = emptyList(),
    /**
     * 模型在公式结果上的手动微调，范围 ±0.05。
     *
     * 单独存一个数而不是直接改 profile：这样「公式算出来多少」和「模型动了多少」
     * 永远是两个可分别核对的量，[adjustmentReason] 为空时不许生效。
     */
    val adjustment: Double = 0.0,
    val adjustmentReason: String = "",
) {
    val hasStatement: Boolean get() = statement.isNotBlank()

    fun field(key: FieldKey): Field = profile[key] ?: Field()

    fun withField(
        key: FieldKey,
        value: String,
        state: FieldState,
        source: RevisionSource,
        atMs: Long,
    ): GoalContract {
        val old = field(key)
        val entry = Revision(atMs, key.name, old.value, value, source)
        return copy(
            profile = profile + (key to Field(value, state, old.weight, old.skips)),
            revisionLog = (revisionLog + entry).takeLast(200),
        )
    }
}

/**
 * 必备字段：按目标类型分两套，不搞一套打天下。
 *
 * 求职类问「薄弱科目」、学业类问「作品集状态」都是错的 —— 用户会觉得这个功能
 * 根本没在听他说什么。返回的是**有序**列表：顺序就是提问顺序，先问影响最大的。
 */
object RequiredFields {

    private val GENERAL = listOf(
        FieldKey.DAILY_MINUTES,
        FieldKey.DEADLINE,
        FieldKey.CURRENT_LEVEL,
        FieldKey.HARD_CONSTRAINTS,
    )

    private val ACADEMIC = GENERAL + listOf(
        FieldKey.MAJOR,
        FieldKey.TARGET_TIER,
        FieldKey.WEAK_SUBJECTS,
        FieldKey.EXAM_SUBJECTS,
    )

    private val JOB = GENERAL + listOf(
        FieldKey.TARGET_ROLE,
        FieldKey.TARGET_INDUSTRY,
        FieldKey.EXPERIENCE,
        FieldKey.PORTFOLIO,
    )

    fun of(kind: GoalKind): List<FieldKey> = when (kind) {
        GoalKind.GENERAL -> GENERAL
        GoalKind.ACADEMIC -> ACADEMIC
        GoalKind.JOB -> JOB
    }

    /** 还没拿到的字段，按权重从高到低（跳过两次的排到最后，不再追问）。 */
    fun missing(contract: GoalContract): List<FieldKey> =
        of(contract.kind)
            .filter { !contract.field(it).resolved }
            .sortedByDescending { contract.field(it).weight }
}

/** 置信度门：三档全部确认就是 1.0，全不知道就是 0。 */
object Confidence {

    /** 采集终止线。 */
    const val STOP = 0.95

    /** 模型允许的手动调整幅度。 */
    const val MAX_ADJUSTMENT = 0.05

    /** 采集最多几轮。到点就出草稿，不再问。 */
    const val MAX_ROUNDS = 3

    /**
     * `Σ(weight × stateScore) / Σ(weight)`，再加模型的手动调整。
     *
     * 权重是「这个字段有多重要」，不是「模型有多相信」—— 后者不可测。
     * 跳过的字段权重被降到 0.5：它仍然算分母（没拿到就是没拿到），但不再主导结果，
     * 也不会被反复追问。
     */
    fun of(contract: GoalContract): Double {
        val keys = RequiredFields.of(contract.kind)
        val fields = keys.map { contract.field(it) }
        val totalWeight = fields.sumOf { it.weight }
        if (totalWeight <= 0.0) return 0.0
        val earned = fields.sumOf { it.weight * it.state.score }
        val adjusted = earned / totalWeight + effectiveAdjustment(contract)
        return adjusted.coerceIn(0.0, 1.0)
    }

    /** 没有理由的调整一律不算 —— 「模型觉得应该高一点」不是理由。 */
    fun effectiveAdjustment(contract: GoalContract): Double {
        if (contract.adjustmentReason.isBlank()) return 0.0
        return contract.adjustment.coerceIn(-MAX_ADJUSTMENT, MAX_ADJUSTMENT)
    }

    /** 还差多少才到线。给界面写「还差 N 个信息」用。 */
    fun remaining(contract: GoalContract): Int = RequiredFields.missing(contract).size
}
