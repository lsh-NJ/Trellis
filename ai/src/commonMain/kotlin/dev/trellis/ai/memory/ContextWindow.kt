package dev.trellis.ai.memory

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole

/**
 * 会话历史（L1）的预算：什么时候折叠、折叠后留多少、摘要最多多长。
 *
 * 这些数字是**起点不是结论**：它们决定「多久做一次摘要」和「一次摘要多贵」。
 * 改之前先看 [dev.trellis.ai.memory.planWindow] 的注释 —— 窗口是按轮切的，
 * 不是按条切的。
 */
object ContextBudget {

    /**
     * 历史涨到这个 token 数，宿主就该在下一次收尾时折叠一次。
     *
     * 12k 是**真 token**（按 DeepSeek 公布的 0.6 token/汉字折算，约 2 万汉字）。
     * 静态前缀本身约 2 万多 token，两者是同一个量级；再往上加，每轮成本会明显变贵，
     * 该先削工具目录而不是继续放历史。
     */
    const val HISTORY_TOKENS: Int = 12_000

    /** 折叠后给近期原文留的预算；留太少模型会接不上，留太多摘要就白做了。 */
    const val KEEP_TOKENS: Int = 6_000

    /** 不管预算多紧，最近这么多轮必须保留原文（一轮 = 一条用户消息 + 它后面的回复）。 */
    const val MIN_TURNS: Int = 3

    /** 一次摘要调用最多喂这么多 token；超了从最老的开始丢，并在正文里注明省略了多少条。 */
    const val COMPACT_INPUT_TOKENS: Int = 16_000

    /** 摘要本身的硬上限（字符）。摘要越长，每轮为它付的钱越多。 */
    const val CHECKPOINT_CHARS: Int = 1_200

    /** 单条消息喂给摘要器时最多保留多少字符（工具长文不该原样进摘要请求）。 */
    const val COMPACT_MESSAGE_CHARS: Int = 1_500
}

/**
 * 一次窗口规划的结果。
 *
 * [kept] 是留在上下文里的近期原文，[folded] 是要折进检查点的那一段。
 * 两者拼起来必须等于原历史 —— 调用方靠这一点保证「压缩只换表示，不丢轮次」。
 */
data class ContextWindowPlan(
    val kept: List<LlmMessage>,
    val folded: List<LlmMessage>,
) {
    /** 有没有真的需要折叠。 */
    val changed: Boolean get() = folded.isNotEmpty()
}

/**
 * 估算一段文本的 token 数。
 *
 * 用 DeepSeek 公布的换算比例（中文约 0.6 token/字、ASCII 约 0.3 token/字符），
 * 按「十分之一 token」累加后四舍五入 —— 不做逐字分词，只要**单调**且**量级对**。
 *
 * 换到中文分词更碎的模型（部分 Llama 系，一字 1.2~1.5 token）会偏低；预算只是护栏，
 * 真实用量以响应里的 usage.prompt_tokens 为准。单字符不做向上取整，
 * 否则「1 个字 = 1 个 token」会让短会话的预算凭空空转。
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var wide = 0
    var narrow = 0
    for (ch in text) {
        val code = ch.code
        when {
            // CJK 统一表意文字、扩展 A、兼容表意、日文假名、韩文、全角标点。
            code in 0x2E80..0x9FFF -> wide++
            code in 0xAC00..0xD7AF -> wide++
            code in 0xF900..0xFAFF -> wide++
            code in 0xFF00..0xFFEF -> wide++
            else -> narrow++
        }
    }
    // wide 记 6 个十分之一 token、narrow 记 3 个，最后四舍五入回整数。
    return (wide * 6 + narrow * 3 + 5) / 10
}

/** 估算一组消息的 token 数；每条消息另算一点角色/分隔开销。 */
fun estimateTokens(messages: List<LlmMessage>): Int =
    messages.sumOf { message ->
        // 图片按固定开销算：data URL 的 base64 长度没有参考价值，算进去会吓死预算。
        val images = if (message.images.isEmpty()) 0 else message.images.size * 1_000
        4 + images + estimateTokens(message.content)
    }

/** 历史是否已经超过折叠阈值。 */
fun needsCompaction(history: List<LlmMessage>, limit: Int = ContextBudget.HISTORY_TOKENS): Boolean =
    estimateTokens(history) > limit

/**
 * 打开一段历史会话时，只取尾部放得进预算的部分。
 *
 * 和 [planWindow] 的区别：这里没有摘要器可用（还没发请求），所以只是截断。
 * 截断点同样落在轮边界上，永远至少保留最后一轮 —— 否则用户会看到模型「不知道刚才说了什么」。
 */
fun tailWithin(history: List<LlmMessage>, budget: Int = ContextBudget.HISTORY_TOKENS): List<LlmMessage> {
    if (history.isEmpty()) return emptyList()
    val turns = turnsOf(history)
    val kept = ArrayDeque<List<LlmMessage>>()
    var tokens = 0
    for (index in turns.indices.reversed()) {
        val turn = turns[index]
        val turnTokens = estimateTokens(turn)
        if (kept.isNotEmpty() && tokens + turnTokens > budget) break
        kept.addFirst(turn)
        tokens += turnTokens
    }
    return kept.flatten()
}

/**
 * 把历史切成「保留」和「折叠」两段。
 *
 * 规则只有两条，顺序不能反：
 * 1. 最近 [minTurns] 轮无条件保留 —— 预算是软的，对话的连贯性是硬的；
 * 2. 在此之上，能多留一轮就多留一轮，直到 [keepTokens] 用完。
 *
 * 切点只能落在轮边界上：把一条用户消息留在旧窗口、把它的回答折进摘要，
 * 接手的模型会看到一句没头没尾的回答。
 */
fun planWindow(
    history: List<LlmMessage>,
    keepTokens: Int = ContextBudget.KEEP_TOKENS,
    minTurns: Int = ContextBudget.MIN_TURNS,
): ContextWindowPlan {
    if (history.isEmpty()) return ContextWindowPlan(emptyList(), emptyList())
    val turns = turnsOf(history)
    if (turns.size <= minTurns) return ContextWindowPlan(history, emptyList())

    val keptTurns = ArrayDeque<List<LlmMessage>>()
    var keptTokens = 0
    for (index in turns.indices.reversed()) {
        val turn = turns[index]
        val turnTokens = estimateTokens(turn)
        val stillNeededForMinimum = keptTurns.size < minTurns
        if (!stillNeededForMinimum && keptTokens + turnTokens > keepTokens) break
        keptTurns.addFirst(turn)
        keptTokens += turnTokens
    }
    if (keptTurns.size >= turns.size) return ContextWindowPlan(history, emptyList())

    val cut = turns.size - keptTurns.size
    return ContextWindowPlan(
        kept = turns.drop(cut).flatten(),
        folded = turns.take(cut).flatten(),
    )
}

/**
 * 生成一次摘要调用的消息。
 *
 * [previous] 是上一次的检查点：折叠是滚动的，新摘要要接住旧摘要，
 * 否则每折一次就把更早的事实洗掉一遍。
 *
 * 提示词只要四类事实（进度 / 决策 / 下一步 / 关键数据），不要求文采：
 * 摘要会被每轮重新读一遍，写得漂亮但塞满无关细节等于没压缩。
 */
fun checkpointMessages(previous: String, folded: List<LlmMessage>): List<LlmMessage> {
    val body = buildString {
        val prev = previous.trim()
        if (prev.isNotEmpty()) {
            appendLine("<已有的检查点>")
            appendLine(prev)
            appendLine("</已有的检查点>")
            appendLine()
        }
        appendLine("<被折叠的会话>")
        appendLine(transcriptOf(folded))
        appendLine("</被折叠的会话>")
    }
    return listOf(
        LlmMessage(LlmRole.System, COMPACT_SYSTEM_PROMPT),
        LlmMessage(LlmRole.User, body.trim()),
    )
}

/** 把检查点包成提示词里的一块；空串返回空串，调用方据此决定插不插。 */
fun checkpointBlock(text: String): String {
    val body = text.trim()
    if (body.isEmpty()) return ""
    return buildString {
        appendLine("<session_checkpoint>")
        appendLine("这是本次会话更早部分的交接摘要（原文已不在上下文里）。把它当作已发生的事实继续用，不要向用户复述这份摘要。")
        appendLine(body)
        append("</session_checkpoint>")
    }
}

/**
 * 收尾模型返回的摘要：去代码围栏、压掉多余空行、按 [ContextBudget.CHECKPOINT_CHARS] 截断。
 *
 * 截断而不是拒绝：摘要被截掉尾巴，也比整段丢掉、让模型从零开始强。
 */
fun normalizeCheckpoint(raw: String): String {
    var text = raw.trim()
    if (text.isEmpty()) return ""
    text = text.replace(Regex("^```[a-zA-Z]*\\s*"), "")
    text = text.replace(Regex("\\s*```$"), "")
    text = text.replace(Regex("\\n{3,}"), "\n\n").trim()
    if (text.length <= ContextBudget.CHECKPOINT_CHARS) return text
    return text.take(ContextBudget.CHECKPOINT_CHARS - 1) + "…"
}

// ---- 内部 ----

/**
 * 按「轮」分组：一条 User 开启一轮，后面的 Assistant 都归这一轮。
 *
 * 历史开头可能没有 User（管家主动消息只把管家那句话接回上下文），
 * 这种孤儿消息单独成组，不往前并 —— 并进去会把两轮的话粘在一起。
 */
private fun turnsOf(history: List<LlmMessage>): List<List<LlmMessage>> {
    val turns = mutableListOf<MutableList<LlmMessage>>()
    for (message in history) {
        if (turns.isEmpty() || message.role == LlmRole.User) {
            turns += mutableListOf(message)
        } else {
            turns.last() += message
        }
    }
    return turns.map { it.toList() }
}

/** 摘要请求里的会话正文：从新往旧装，装不下就停，并注明省了多少条。 */
private fun transcriptOf(folded: List<LlmMessage>): String {
    val kept = ArrayDeque<LlmMessage>()
    var tokens = 0
    for (index in folded.indices.reversed()) {
        val message = folded[index]
        val text = message.content.trim().take(ContextBudget.COMPACT_MESSAGE_CHARS)
        val cost = estimateTokens(text) + 4
        if (kept.isNotEmpty() && tokens + cost > ContextBudget.COMPACT_INPUT_TOKENS) break
        kept.addFirst(message)
        tokens += cost
    }
    val omitted = folded.size - kept.size
    return buildString {
        if (omitted > 0) appendLine("（更早的 " + omitted + " 条消息已省略）")
        kept.forEach { message ->
            append(roleLabel(message.role))
            append("：")
            appendLine(message.content.trim().take(ContextBudget.COMPACT_MESSAGE_CHARS))
        }
    }.trim()
}

private fun roleLabel(role: LlmRole): String = when (role) {
    LlmRole.User -> "用户"
    LlmRole.Assistant -> "助手"
    LlmRole.System -> "系统"
}

private val COMPACT_SYSTEM_PROMPT = """
    你在做一次上下文检查点压缩：把下面这段会话压成一份交接摘要，交给接手的模型继续干活。

    只写事实，不写过程感想：
    - 做到了哪一步、哪些结论已经确认；
    - 用户明确说过的约束、偏好、验收标准（保留关键数字、日期和原话里的限定词）；
    - 还剩什么没做，下一步该干什么；
    - 续跑需要的具体数据：文件名、ID、命令、错误码、链接。

    删掉寒暄、重复解释、已经被推翻的方案、工具返回的长文。
    分四节输出，没有内容就整节省略：
    ## 进度
    ## 决策与约束
    ## 下一步
    ## 关键数据

    直接给摘要正文，不要前言，不要代码围栏；总长 600 字以内。
""".trimIndent()

