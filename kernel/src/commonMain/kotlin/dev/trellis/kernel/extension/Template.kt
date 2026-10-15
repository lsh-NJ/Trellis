package dev.trellis.kernel.extension

/**
 * 极简模板：只认 `{{ 命名空间.路径 }}`，其余原样。
 *
 * ### 刻意不做表达式
 *
 * 没有算术、没有条件、没有循环、没有函数调用。只有「取值」和「替换」。
 *
 * 因为这段文本可能是 **Agent 生成的**。能被计算的东西越多，能搞出的花样就越多 
 * 而一旦允许表达式，就得再补一层沙箱、超时、复杂度限制。
 * 现在这个语法，最坏情况就是「显示一串错的字」，没有第二条路。
 *
 * 需要算点什么？那是**行为型插件**（轨道 B）的活，不该塞进模板里。
 */
object Template {

    private const val OPEN = "{{"
    private const val CLOSE = "}}"

    /** 一个占位符在原文里的位置，以及它要问的命名空间与路径。 */
    data class Placeholder(
        /** `{{` 的下标。 */
        val start: Int,
        /** `}}` 之后的下标。 */
        val end: Int,
        val namespace: String,
        val path: String,
    )

    /**
     * 扫出全部占位符。
     *
     * 不成形的（没有 `}}`、没有点号、`{{ }}` 里面是空的）**一律跳过原样保留** 
     * 宁可显示一段奇怪的原文，也不要悄悄吃掉用户写的东西。
     */
    fun parse(text: String): List<Placeholder> {
        val found = mutableListOf<Placeholder>()
        var cursor = 0
        while (true) {
            val open = text.indexOf(OPEN, cursor)
            if (open < 0) break
            val close = text.indexOf(CLOSE, open + OPEN.length)
            if (close < 0) break

            val expr = text.substring(open + OPEN.length, close).trim()
            val dot = expr.indexOf('.')
            if (dot > 0 && dot < expr.length - 1) {
                found += Placeholder(
                    start = open,
                    end = close + CLOSE.length,
                    namespace = expr.substring(0, dot).trim(),
                    path = expr.substring(dot + 1).trim(),
                )
            }
            cursor = close + CLOSE.length
        }
        return found
    }
}
