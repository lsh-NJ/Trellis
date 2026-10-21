package dev.trellis.ai.flow

/**
 * 一条「哪里不对」。
 *
 * 四个字段是刻意分开的：**位置**和**怎么改**必须能独立取用。
 * 只给一句「插件有问题」的报错，模型改不动，只能瞎猜。
 */
data class Issue(
    val code: String,
    val where: String,
    val message: String,
    val suggestion: String = "",
) {
    fun render(): String = buildString {
        append(where)
        append(" [")
        append(code)
        append("] ")
        append(message)
        if (suggestion.isNotBlank()) {
            append(" 建议：")
            append(suggestion)
        }
    }
}

/**
 * 试跑一遍插件。
 *
 * 抽成接口而不是直接实现，有两个原因：
 * 一是 AI 模块不认识脚本引擎（它只认识提示词和 JSON）；
 * 二是测试里可以塞一个「前两次报错第三次通过」的假试跑器，用来验证循环本身。
 */
fun interface SelfTestTool {
    /** 返回空列表表示通过。 */
    suspend fun test(files: Map<String, String>): List<Issue>
}
