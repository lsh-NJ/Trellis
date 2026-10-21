package dev.trellis.ai.tool

import kotlinx.serialization.json.JsonObject

/**
 * 一个 agent 能调用的工具。
 *
 * 工具是**我们**写的，参数是**我们**定的 —— 这是它和「给 AI 一个 shell」的
 * 根本区别：它能做的事有名字、有边界、有返回值形状，而不是一片什么都行的黑箱。
 */
interface AgentTool {
    val name: String
    val description: String

    /** 参数长什么样。直接进提示词，所以要说人话。 */
    val parameters: String

    suspend fun call(arguments: JsonObject): ToolResult
}

/**
 * 一次工具调用的结果。
 *
 * [done] 为真表示「不用再想了，收工」。只有 deploy 会这么回：
 * 插件装上去了这件事本身就是答案，再让模型补一段话只会画蛇添足。
 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val done: Boolean = false,
)
