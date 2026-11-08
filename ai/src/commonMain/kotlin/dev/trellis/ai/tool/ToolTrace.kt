package dev.trellis.ai.tool

/**
 * 工具循环里发生的事，供界面拼「过程」。
 *
 * 和 [ToolAgent] 的 `onLog` 不同：`onLog` 是给人扫一眼的短文本；这里是
 * 结构化详情，界面点开单条工具时用来显示工具说明、完整参数和结果原文。
 */
sealed interface ToolTrace {
    /**
     * 模型在动手前写的一句话思路（工具调用 JSON 之外的前言）。
     *
     * 没有就是 null（大多数模型只吐 JSON）；有的话属于「思考过程」，
     * 不该跟工具结果混在同一行。
     */
    data class Thinking(val text: String) : ToolTrace

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
