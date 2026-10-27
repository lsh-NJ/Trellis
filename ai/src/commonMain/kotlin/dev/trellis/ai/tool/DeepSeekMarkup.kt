package dev.trellis.ai.tool

/**
 * DeepSeek 的「DSML」工具调用方言。
 *
 * 正常路径是提示词约定的 JSON：`{"tool": "名字", "arguments": {...}}`。
 * 但模型有时会输出它自己训练里的标记语言：
 *
 * ```
 * <||DSML||calls><||DSML||invoke name="search_bilibili">
 * {"keyword": "操作系统 考研", "order": "热度"}
 * </||DSML||parameter></||DSML||invoke></||DSML||calls>
 * ```
 *
 * 这不是坏输出 —— 它在按另一种协议调工具。把标签原样摊到气泡里，用户看到的是
 * 一屏乱码，而且**工具一次都没执行**，于是就出现「查到了却没有下文」。
 * 这里只做两件事：认出来、剥干净；执行交给工具循环。
 */
object DeepSeekMarkup {

    data class Invocation(val name: String, val arguments: String)

    // 竖线有三种：ASCII 的 `|`、全角 `｜`（真实样本就是它，见 `~/.trellis/chat` 里
    // 存下的原文），以及竖线之间再夹空格。三种都得认，否则界面上就是一屏乱码。
    private val TAG = """[|｜]\s*[|｜]?\s*(?:DSML|DSLM)\s*[|｜]?\s*[|｜]"""

    private val invokeOpen = Regex(
        """<\s*$TAG\s*invoke\s+name\s*=\s*["']([^"']+)["']\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val invokeClose = Regex("""<\s*/\s*$TAG\s*invoke\s*>""", RegexOption.IGNORE_CASE)
    private val blockOpen = Regex("""<\s*$TAG\s*calls\s*>""", RegexOption.IGNORE_CASE)
    private val blockClose = Regex("""<\s*/\s*$TAG\s*calls\s*>""", RegexOption.IGNORE_CASE)
    private val parameterTag = Regex("""<\s*/?\s*$TAG\s*parameter\s*>""", RegexOption.IGNORE_CASE)
    private val marker = Regex("""<\s*$TAG""", RegexOption.IGNORE_CASE)

    /** 第一个标记出现的位置；-1 = 没有。流式回显靠它在标记处截断。 */
    fun indexOf(text: String): Int = marker.find(text)?.range?.first ?: -1

    fun contains(text: String): Boolean = indexOf(text) >= 0

    /** 取出第一个工具调用；参数原样返回（是 JSON 字符串），没有就 null。 */
    fun firstInvocation(text: String): Invocation? {
        val open = invokeOpen.find(text) ?: return null
        val name = open.groupValues[1].trim()
        val after = text.substring(open.range.last + 1)
        val close = invokeClose.find(after)
        val raw = if (close == null) after else after.substring(0, close.range.first)
        val json = parameterTag.replace(raw, "").trim()
        return Invocation(name, json.ifBlank { "{}" })
    }

    /**
     * 把整块标记剥掉，留下它前面/后面的话。
     *
     * 流式中途截断时可能只有开口没有闭合 —— 那就从开口一路砍到末尾，
     * 别把半截标签糊到界面上。
     */
    fun strip(text: String): String {
        var out = text
        while (true) {
            val open = blockOpen.find(out) ?: break
            val close = blockClose.find(out, open.range.last + 1) ?: break
            out = out.removeRange(open.range.first, close.range.last + 1)
        }
        val danglingBlock = blockOpen.find(out)
        if (danglingBlock != null) out = out.substring(0, danglingBlock.range.first)
        val danglingInvoke = invokeOpen.find(out)
        if (danglingInvoke != null) out = out.substring(0, danglingInvoke.range.first)
        return out.trim()
    }
}
