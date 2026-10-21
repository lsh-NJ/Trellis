package dev.trellis.ai.flow

import dev.trellis.ai.LlmMessage

/**
 * 工作流唯一的那份状态。
 *
 * 所有节点都只读它、返回一个新的它，没有别的可变量。
 * 好处很实在：每一步都能存盘、能回看、能从第三步接着跑。
 */
data class AgentState(
    val round: Int = 0,
    val messages: List<LlmMessage> = emptyList(),
    val files: Map<String, String> = emptyMap(),
    val pluginId: String = "",
    val capabilities: Set<String> = emptySet(),
    val issues: List<Issue> = emptyList(),
    /** 到目前为止最好的一轮有几个毛病。用来判断「越改越好」还是「越改越糟」。 */
    val bestIssues: Int = Int.MAX_VALUE,
    val bestRound: Int = 0,
    val refusal: String? = null,
    val installedId: String? = null,
    val gaveUp: Boolean = false,
    val log: List<String> = emptyList(),
) {
    fun say(line: String): AgentState = copy(log = log + line)
}
