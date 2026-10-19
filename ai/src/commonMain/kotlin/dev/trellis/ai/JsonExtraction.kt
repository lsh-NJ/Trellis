package dev.trellis.ai

/**
 * 从模型的原始输出里抠出那个 JSON 对象。
 *
 * 模型很爱干这几件事：
 *  - 用 ```json 围栏包起来
 *  - 前面来一句「好的，这是你要的插件：」
 *  - 后面补一句「这个插件会在首页显示……」
 *
 * 这些都不该让一次生成作废。**能容忍的就容忍，不能容忍的才报错。**
 * 但容忍是有边界的：只抠**第一个完整的、括号配平的**对象，剩下的全部丢掉。
 */
object JsonExtraction {

    /**
     * 返回第一个完整的 JSON 对象文本；找不到返回 null。
     *
     * 括号配平时会跳过字符串里的括号和转义引号 —— 否则正文里写个 `{` 就会把它带偏。
     */
    fun extractFirstObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
