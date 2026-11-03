package dev.trellis.ai.steward

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole

/**
 * 一天里主动开口的两个时刻。打开应用时如果昨晚的收尾也错过了，
 * 用 [MorningWithEveningCatchUp] 把两件事合成一条消息（用户 m03368 定下的规则）：
 * 同一次打开只出现一条，不做「先补昨晚、再发今早」两条。
 */
enum class StewardRitual { Morning, Evening, MorningWithEveningCatchUp }

/** 本周逾期按天分组（「周四 2 件」），只做展示。 */
data class StewardOverdueDay(val dayLabel: String, val count: Int)

/**
 * 管家一次仪式能看到的事实。
 *
 * 这里是**纯排版**：所有数字由 app 层算好（计划后端的 view model），提示词里
 * 只出现事实和「可以怎么说」的约束 —— 模型不能自己算、也不该被要求编。
 */
data class StewardContext(
    /** 应用内日期，如「11月4日 周二」。 */
    val dayLabel: String,
    val planTitle: String? = null,
    val plannedToday: Int = 0,
    val doneToday: Int = 0,
    val todayMinutes: Int = 0,
    val overdueThisWeek: List<StewardOverdueDay> = emptyList(),
    val backlogCount: Int = 0,
    val remainingMinutesInWeek: Int? = null,
    val yesterdayDigest: String? = null,
    /** 最近几天的记忆行（已经排成「11-03 晨：…」这种短行）。 */
    val recentMemory: List<String> = emptyList(),
    /** 补发的昨晚收尾内容；只有 [StewardRitual.MorningWithEveningCatchUp] 用。 */
    val catchUpEvening: String? = null,
) {
    val hasAnything: Boolean
        get() = overdueThisWeek.isNotEmpty() || backlogCount > 0 || plannedToday > 0 || yesterdayDigest != null
}

/**
 * 管家的人格与提示词。
 *
 * 语气刻意克制（用户 m03352：「语气刚开始就克制一些就行」）：不喊口号、不堆感叹号、
 * 不假装比用户更了解他的生活。所有建议都必须来自事实，且一次只给一条。
 */
object StewardPrompt {

    const val MAX_REPLY_CHARS = 120

    fun systemPrompt(): String = """
        你是 Trellis 的学习管家。你的话很短、很克制：每次只说 2–4 句，不说教、不用感叹号、不喊口号、不堆 emoji。
        下面会给你一组事实（今天的计划、本周逾期、昨晚的摘要、最近的记忆）。规则：
        1. 只使用给出的数字和标题，不推测不存在的课程、日期或用户感受。
        2. 不重复罗列全部事实，挑最值得说的一两点；没有值得说的就少说。
        3. 如果要给建议，最多一条，且要具体到「做什么」；不要替用户执行任何写操作——写操作需要用户在界面上确认。
        4. 不用「加油」「你一定可以」这类空话；可以用「今天先做哪一件」这种具体问句收尾。
        5. 你记住的是发生过的对话和计划事实，不替用户下结论、不评价他的人。
        6. 用中文。整体不超过 $MAX_REPLY_CHARS 个字。
    """.trimIndent()

    fun messages(ritual: StewardRitual, context: StewardContext): List<LlmMessage> = listOf(
        LlmMessage(LlmRole.System, systemPrompt()),
        LlmMessage(LlmRole.User, factsText(ritual, context) + "\n\n把上面的事实写成一条给用户的消息。"),
    )

    /**
     * 确定性骨架：LLM 没配/失败/超时的时候直接发这段。
     *
     * 和给模型的素材是同一组事实，所以两条路径不会出现「模板有、模型没有」的漂移。
     */
    fun template(ritual: StewardRitual, context: StewardContext): String = when (ritual) {
        StewardRitual.Morning -> morningTemplate(context)
        StewardRitual.Evening -> eveningTemplate(context)
        StewardRitual.MorningWithEveningCatchUp ->
            listOfNotNull(
                context.catchUpEvening?.takeIf { it.isNotBlank() },
                morningTemplate(context),
            ).joinToString("\n")
    }

    fun factsText(ritual: StewardRitual, context: StewardContext): String {
        val lines = mutableListOf<String>()
        lines += "应用内日期：${context.dayLabel}"
        context.planTitle?.let { lines += "当前计划：$it" }

        if (ritual != StewardRitual.Evening) {
            lines += "今天计划：${context.plannedToday} 件、约 ${context.todayMinutes} 分钟"
        } else {
            lines += "今天完成：${context.doneToday}/${context.plannedToday} 件（约 ${context.todayMinutes} 分钟的量）"
        }

        if (context.overdueThisWeek.isNotEmpty()) {
            lines += "本周还没有做的排期：" + context.overdueThisWeek
                .joinToString("、") { "${it.dayLabel} ${it.count} 件" }
        }
        if (context.backlogCount > 0) {
            lines += "更早欠下、还没安排进任何一周的节点：${context.backlogCount} 个"
        }
        context.remainingMinutesInWeek?.let { lines += "本周还剩约 $it 分钟可投入" }

        if (ritual == StewardRitual.Evening) {
            lines += "如果今天没做完，可以提议把剩下的挪到本周剩下的天数；一次只说一条建议。"
        }
        context.yesterdayDigest?.let { lines += "昨晚的收尾：$it" }
        context.catchUpEvening?.let { lines += "补发的昨晚收尾：$it" }

        if (context.recentMemory.isNotEmpty()) {
            lines += "最近的记忆（按时间从旧到新）："
            lines += context.recentMemory.takeLast(8).joinToString("\n") { "- $it" }
        }
        return lines.joinToString("\n")
    }

    private fun morningTemplate(context: StewardContext): String {
        val parts = mutableListOf<String>()
        parts += "早上好。"
        context.planTitle?.let { title ->
            if (context.plannedToday > 0) {
                parts += "今天「$title」有 ${context.plannedToday} 件、约 ${context.todayMinutes} 分钟。"
            } else {
                parts += "今天「$title」还没有排期。"
            }
        }
        if (context.overdueThisWeek.isNotEmpty()) {
            val days = context.overdueThisWeek.joinToString("、") { "${it.dayLabel} ${it.count} 件" }
            parts += "本周还有没做完的：$days。"
        }
        if (context.backlogCount > 0) {
            parts += "更早欠下 ${context.backlogCount} 个，还没排进这周。"
        }
        context.yesterdayDigest?.takeIf { it.isNotBlank() }?.let { parts += "昨晚：$it" }
        if (context.plannedToday > 0) parts += "先做哪一件？"
        return parts.joinToString(" ")
    }

    private fun eveningTemplate(context: StewardContext): String {
        val parts = mutableListOf<String>()
        parts += "今天结束了。"
        if (context.plannedToday > 0) {
            parts += "计划 ${context.plannedToday} 件，完成 ${context.doneToday} 件。"
        } else {
            parts += "今天没有排期。"
        }
        val left = context.overdueThisWeek.sumOf { it.count }
        if (left > 0) {
            val days = context.overdueThisWeek.joinToString("、") { "${it.dayLabel} ${it.count} 件" }
            parts += "还有 $left 件没做（$days）。"
        }
        if (context.backlogCount > 0) parts += "更早欠下 ${context.backlogCount} 个还没排。"
        context.remainingMinutesInWeek?.let { parts += "本周还剩约 $it 分钟。" }
        if (left > 0 || context.backlogCount > 0) parts += "要不要把其中几件挪到明天的排期里？"
        return parts.joinToString(" ")
    }
}
