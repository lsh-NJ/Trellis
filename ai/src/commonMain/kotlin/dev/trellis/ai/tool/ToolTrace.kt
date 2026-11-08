package dev.trellis.ai.tool

/**
 * 工具循环里发生的事，供界面拼「过程」。
 *
 * 和 [ToolAgent] 的 `onLog` 不同：`onLog` 是给人扫一眼的短文本；这里是
 * 结构化详情，界面点开单条工具时用来显示工具说明、完整参数和结果原文。
 */
sealed interface ToolTrace {
    /**
     * 模型的原生思考（DeepSeek `reasoning_content`）。
     *
     * 没有就不发（模型没开思考、或这一跳没返回）；有的话属于「思考过程」，
     * 不该跟工具结果混在同一行。
     */
    data class Thinking(val text: String) : ToolTrace

    /**
     * 工具**开始**执行。
     *
     * 和 [Call] 分开是为了让界面在工具跑起来的那一刻就占一行、亮出参数，
     * 而不是等工具跑完（research_plan 可能要十几秒）才突然出现。
     * 结束时一定会跟一条 [Call]，把结果补上。
     */
    data class CallStarted(
        val step: Int,
        val tool: String,
        val description: String,
        val arguments: String,
    ) : ToolTrace

    /** 一次工具调用：名字、说明（给用户看的用途）、参数、结果。 */
    data class Call(
        val step: Int,
        val tool: String,
        val description: String,
        val arguments: String,
        val result: String,
        val isError: Boolean,
    ) : ToolTrace
}
