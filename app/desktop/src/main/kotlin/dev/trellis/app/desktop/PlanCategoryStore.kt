package dev.trellis.app.desktop

import java.io.File

/**
 * 计划的大类：`<trellis home>/plans/categories.json`，形如 `{"pln_xxx": "操作系统"}`。
 *
 * 跟会话分组一样，只是给页面「按大类摆」用的；没有登记的计划归「未分类」。
 * 计划本身（单元、承诺、事件）全在 `plan.db` 里，这里只放一个显示用的标签。
 */
internal class PlanCategoryStore(private val home: File) {

    private val file: File get() = File(home, "plans/categories.json")

    fun load(): Map<String, String> {
        val text = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyMap()
        return parse(text)
    }

    fun save(categories: Map<String, String>) {
        val json = categories.entries.joinToString(",", "{", "}") { (id, category) ->
            "\"" + id.escape() + "\":\"" + category.escape() + "\""
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }

    private fun parse(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        // 极简 JSON 对象解析：只认 "键":"值" 对，够这一份文件用。
        val regex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        regex.findAll(text).forEach { match ->
            result[match.groupValues[1].unescape()] = match.groupValues[2].unescape()
        }
        return result
    }

    private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun String.unescape(): String = replace("\\\"", "\"").replace("\\\\", "\\")
}
