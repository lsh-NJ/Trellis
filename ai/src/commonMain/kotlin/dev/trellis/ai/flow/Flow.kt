package dev.trellis.ai.flow

/** 一个节点：读状态、干活、返回新状态。 */
fun interface FlowNode {
    suspend fun run(state: AgentState): AgentState
}

/** 一条边：满足条件才走。没有边可走 = 结束。 */
data class FlowEdge(
    val from: String,
    val to: String,
    val guard: (AgentState) -> Boolean = { true },
)

/** 每走完一步，状态可以存一份。留痕、续跑、回滚都靠它。 */
fun interface CheckpointSink {
    suspend fun save(step: Int, node: String, state: AgentState)
}

object NoCheckpoints : CheckpointSink {
    override suspend fun save(step: Int, node: String, state: AgentState) = Unit
}

/**
 * 工作流本身。
 *
 * 刻意做成**数据**（节点表 + 边表）而不是写死的一串 if：
 * 以后要换成别的执行器（或者外部的图引擎），这张表原样搬过去就行。
 */
data class Flow(
    val nodes: Map<String, FlowNode>,
    val edges: List<FlowEdge>,
    val entry: String,
    val maxSteps: Int = 12,
) {
    suspend fun run(
        initial: AgentState,
        checkpoints: CheckpointSink = NoCheckpoints,
        onStep: suspend (node: String, state: AgentState) -> Unit = { _, _ -> },
    ): AgentState {
        var state = initial
        var current = entry
        var steps = 0
        while (steps < maxSteps) {
            val node = nodes[current] ?: return state.say("工作流里没有节点 " + current)
            state = node.run(state)
            steps++
            checkpoints.save(steps, current, state)
            onStep(current, state)
            val next = edges.firstOrNull { it.from == current && it.guard(state) } ?: return state
            current = next.to
        }
        return state.say("步数用完了，停在 " + current)
    }
}
