package dev.trellis.app.desktop

/**
 * 对话流里的一行。
 *
 * [Step] 是 agent 干活时的过程（第几步、调了哪个工具、返回什么），
 * 和用户/助手的正式发言分开：过程可以很长，而且用户不一定要读，
 * 所以界面上用更小的字号、更灰的颜色。
 *
 * [Status] 是**一轮的界标**：完成 / 已停止 / 出错。流式回答的麻烦是它没有
 * 「话说完了」这个动作 —— 字停下来可能只是模型在喘气，也可能真的结束了。
 * 每一轮末尾压一条界标，用户不用猜。
 *
 * [atMs] 只用于显示时间（聊天记录要能看出「哪天说的」）；不参与模型上下文。
 */
enum class ChatRole { User, Assistant, Step, Status }

data class ChatLine(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val atMs: Long = 0L,
)

/**
 * 一段会话。对话记录按会话存盘，关掉程序再打开还能接着聊。
 *
 * [title] 取第一句用户发言（截断）；[updatedAt] 用来排序和显示「最近说过话」。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lines: List<ChatLine>,
)
