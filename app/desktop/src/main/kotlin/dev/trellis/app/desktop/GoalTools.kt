package dev.trellis.app.desktop

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import dev.trellis.feature.goal.Clarity
import dev.trellis.feature.goal.Confidence
import dev.trellis.feature.goal.FieldKey
import dev.trellis.feature.goal.FieldState
import dev.trellis.feature.goal.GoalEngine
import dev.trellis.feature.goal.GoalKind
import dev.trellis.feature.goal.GoalMirror
import dev.trellis.feature.goal.GoalPhase
import dev.trellis.feature.goal.GoalService
import dev.trellis.feature.goal.GoalStateMachine
import dev.trellis.feature.goal.RequiredFields
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 「目标达成规划」的手：让对话能真的把目标写进去。
 *
 * 为什么必须有这一层：插件自己只交卡片和上下文片段，那两条都是**往外给**的。
 * 没有工具，`GoalEngine` 的 start / answer / toDraft 就没有任何生产调用方，
 * 三张卡会永远停在「还没有目标」—— 状态机测得再全也进不去。
 * 所以这不是顺手加个便利功能，是把回路接上。
 *
 * 一个工具、七个动作，而不是七个工具：模型一次只想干一件事，
 * 七个名字相近的工具只会让它挑错；动作枚举也让「参数写错时列出全部可用值」变得好写。
 */
class GoalTools(
    /** 拿不到就是插件没起来：这时要回一句人话，而不是抛异常把整轮对话打掉。 */
    private val service: () -> GoalService?,
    private val dailyMinutes: () -> Int,
    private val today: () -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    val guidance: String = listOf(
        "<how_to_plan_a_long_term_goal>",
        "用户说的是自己的长期目标（考研 / 求职 / 学一门手艺）时走 goal_plan，不要只回一段安慰的话。",
        "1. 第一次只调 action=start，statement 放**用户原话**，一个字都不要润色：" +
            "他说「想考研」就写「想考研」，写成「备考 211 计算机」会让后面所有判断失去锚点。",
        "2. 工具会回「这一轮问」两个字段。把这两个原样问用户，一轮最多两个；" +
            "用户说不知道就调 action=skip，不要换个说法再问一遍同一个点。",
        "3. 信息够了（或问满三轮）调 action=draft 出计划；把今天几条、共多少分钟念给用户，" +
            "他说行再 action=confirm。没确认之前不要说「已经开始了」。",
        "4. 目标只有方向、还没定死时，不要替他挑学校或公司 —— 计划里已经排了一条「查 N 所」的决策任务，" +
            "那是他自己的事。",
        "5. 改目标用 action=answer 改具体字段（不要用 start 覆盖整份契约），复盘结束用 action=review_done。",
        "</how_to_plan_a_long_term_goal>",
    ).joinToString("\n")

    val all: List<AgentTool> = listOf(GoalPlan())

    private inner class GoalPlan : AgentTool {

        override val name: String = "goal_plan"

        override val description: String =
            "维护用户这份长期目标（目标契约 + 今天和明天的安排）。" +
                "action=start 起目标、answer 回答一个字段、skip 跳过、draft 出草稿、" +
                "confirm 开始推进、review_done 结束本期复盘、status 看现状。" +
                "会写用户数据，只在用户明确在说自己的目标 / 备考 / 求职时调用。"

        override val parameters: String =
            "action：必填，start | answer | skip | draft | confirm | review_done | status；" +
                "statement：用户原话，start 必填，不改写；" +
                "kind：academic | job | general（默认 general）；" +
                "clarity：vague | directed | concrete（默认 vague，拿不准就 vague）；" +
                "field：字段名，用工具返回的「这一轮问」里括号里的那个键（如 DAILY_MINUTES）；" +
                "value：用户答的原话；" +
                "dailyMinutes：出草稿时每天投入多少分钟（5-240，不填就用设置里的值）。"

        override suspend fun call(arguments: JsonObject): ToolResult {
            val target = service()
                ?: return ToolResult("目标插件没启动，这次没写进去。重启应用再试。", isError = true)
            val action = arguments.text("action")
            val now = nowMs()

            when (action) {
                "start" -> {
                    val statement = arguments.text("statement")
                    if (statement.isBlank()) {
                        return ToolResult(
                            "start 需要 statement：把用户的原话放进去，不要改写（例：「我想考研」）。",
                            isError = true,
                        )
                    }
                    val kind = parseKind(arguments.text("kind"))
                        ?: return ToolResult(unknownKind(arguments.text("kind")), isError = true)
                    val clarity = parseClarity(arguments.text("clarity"))
                        ?: return ToolResult(unknownClarity(arguments.text("clarity")), isError = true)
                    target.update { GoalEngine.start(it, statement, kind, clarity, today(), now) }
                }

                "answer" -> {
                    val key = parseField(arguments.text("field"))
                        ?: return ToolResult(unknownField(arguments.text("field")), isError = true)
                    val value = arguments.text("value")
                    if (value.isBlank()) {
                        return ToolResult(
                            "answer 需要 value：把这一个字段的用户原话放进去。不想答就改用 action=skip。",
                            isError = true,
                        )
                    }
                    target.update { mirror ->
                        val answered = GoalEngine.answer(mirror, key, value, FieldState.CONFIRMED, now)
                        answered.copy(collectingRounds = roundsOf(answered))
                    }
                }

                "skip" -> {
                    val key = parseField(arguments.text("field"))
                        ?: return ToolResult(unknownField(arguments.text("field")), isError = true)
                    target.update { mirror ->
                        val skipped = GoalEngine.skip(mirror, key, now)
                        skipped.copy(collectingRounds = roundsOf(skipped))
                    }
                }

                "draft" -> {
                    val minutes = arguments.int("dailyMinutes") ?: dailyMinutes()
                    target.update { GoalEngine.toDraft(it, today(), minutes.coerceIn(5, 240)) }
                }

                "confirm" -> target.update { GoalEngine.confirm(it) }

                "review_done" -> target.update { mirror ->
                    GoalStateMachine.move(mirror, GoalPhase.ACTIVE)
                        .getOrElse { mirror }
                        .copy(cycleStartedOn = today())
                }

                "status" -> Unit

                "" -> return ToolResult(
                    "缺少 action。可用的是 start / answer / skip / draft / confirm / review_done / status。",
                    isError = true,
                )

                else -> return ToolResult(
                    "没有这个 action：" + action +
                        "。可用的是 start / answer / skip / draft / confirm / review_done / status。",
                    isError = true,
                )
            }

            return ToolResult(describe(target.mirror()))
        }
    }

    /**
     * 回给模型的一段状态。
     *
     * 必须包含「这一轮问哪两个、括号里的键是什么」：模型下一步要拿这个键去调 answer，
     * 让它自己猜键名，猜错一次就是一轮白问。
     */
    private fun describe(mirror: GoalMirror): String = buildString {
        append("[状态] ").append(
            when (mirror.phase) {
                GoalPhase.EMPTY -> "还没有目标"
                GoalPhase.COLLECTING -> "在收集信息"
                GoalPhase.DRAFT -> "草稿已出，等用户确认"
                GoalPhase.ACTIVE -> "推进中"
                GoalPhase.REVIEWING -> "复盘周期到了，等用户复盘"
                GoalPhase.ARCHIVED -> "这份规划已删除"
            },
        )
        if (mirror.isEmpty) return@buildString

        append(" · 信息完整度 ").append((Confidence.of(mirror.contract) * 100).toInt()).append("%")
        append("\n[目标] ").append(mirror.contract.statement)

        val asking = GoalEngine.questions(mirror)
        if (asking.isNotEmpty()) {
            append("\n[这一轮问] ")
            append(asking.joinToString("、") { it.label + "（" + it.name + "）" })
            append("。一次只问这两个，用户答完再调 answer。")
        } else if (mirror.phase == GoalPhase.COLLECTING) {
            append("\n[下一步] 信息够了或已经问满 ")
            append(Confidence.MAX_ROUNDS)
            append(" 轮，调 action=draft 出计划。")
        }

        val missing = RequiredFields.missing(mirror.contract)
        if (missing.isNotEmpty() && asking.isEmpty()) {
            append("\n[仍然未知] ").append(missing.joinToString("、") { it.label })
            append("（已问过就不许再追问同一个点）")
        }

        val plan = mirror.dayPlan(today())
        if (plan != null) {
            append("\n[今天] ").append(plan.entries.size).append(" 条 · ").append(plan.totalMinutes).append(" 分钟：")
            append(plan.entries.joinToString(" / ") { it.text })
        }
        if (mirror.contract.unknownDecisions.isNotEmpty()) {
            append("\n[待决策] ").append(mirror.contract.unknownDecisions.joinToString("、"))
        }
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    /**
     * 已经问到第几轮 = 问过的必备字段数 ÷ 2，向上取整。
     *
     * 为什么由工具来算而不是让模型自己报：`Confidence.MAX_ROUNDS` 这条闸门要有东西推动它。
     * 之前 `collectingRounds` 谁都不写，于是「最多问三轮」在实际调用链里永远不生效 ——
     * 建了目标之后可以无限问下去，用户只会觉得被盘问。
     *
     * 用「问过几个字段」推而不是收到第几次调用就加一：一轮两个字段，
     * 按调用次数算会把 8 个字段记成 8 轮，等于没有上限。
     */
    private fun roundsOf(mirror: GoalMirror): Int {
        val asked = RequiredFields.of(mirror.contract.kind).count { key ->
            val field = mirror.contract.field(key)
            field.resolved || field.skips > 0
        }
        return (asked + 1) / 2
    }

    private fun parseKind(raw: String): GoalKind? = when (raw.lowercase()) {
        "", "general" -> GoalKind.GENERAL
        "academic" -> GoalKind.ACADEMIC
        "job" -> GoalKind.JOB
        else -> null
    }

    private fun parseClarity(raw: String): Clarity? = when (raw.lowercase()) {
        "", "vague" -> Clarity.VAGUE
        "directed" -> Clarity.DIRECTED
        "concrete" -> Clarity.CONCRETE
        else -> null
    }

    /** 字段既能按键名（DAILY_MINUTES）给，也能按中文标签（每天能投入多久）给。 */
    private fun parseField(raw: String): FieldKey? =
        enumValues<FieldKey>().firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: enumValues<FieldKey>().firstOrNull { it.label == raw }

    private fun unknownKind(raw: String): String =
        "没有这个 kind：" + raw + "。可用的是 academic / job / general。"

    private fun unknownClarity(raw: String): String =
        "没有这个 clarity：" + raw + "。可用的是 vague / directed / concrete。"

    /** 报错要能指导修改：一次性把全部可用值列出来，模型下一轮就能自己改对。 */
    private fun unknownField(raw: String): String {
        val available = enumValues<FieldKey>().joinToString("、") { it.name + "（" + it.label + "）" }
        return "没有这个 field：" + raw + "。可用的是 " + available + "。"
    }
}
