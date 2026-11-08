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

    /**
     * 同一个工具，但允许它把「正在做什么」报给界面。
     *
     * 默认直接忽略进度：绝大多数工具是一步完成的。慢的复合工具
     * （比如 research_plan：搜索 → 拆阶段 → 建计划 → 排本周）重写这个重载，
     * 把这些阶段一条条吐出来，过程行才不会十几秒只有一行静态名字。
     */
    suspend fun call(arguments: JsonObject, onProgress: (String) -> Unit): ToolResult =
        call(arguments)
}

/**
 * 一次工具调用的结果。
 *
 * [done] 为真表示「不用再想了，收工」。只有 deploy 会这么回：
 * 插件装上去了这件事本身就是答案，再让模型补一段话只会画蛇添足。
 *
 * [image] 是附带的截图（data URL）。工具循环会把它当成图片交给模型 ——
 * 模型看不见屏幕这件事，就是靠这一条补上的。
 */
data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    val done: Boolean = false,
    val image: String? = null,
)
