package dev.trellis.app.desktop

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 「计划检查」主动消息的落盘状态。
 *
 * 只记一件事：上一次往计划检查会话里主动说话是哪一天（**应用内日期**，ISO）。
 * 用应用内日期而不是墙钟，是为了和计划后端的 `today()` 一致——调试跳日期时，
 * 「今天已经提醒过」也跟着跳，不会出现墙钟跨越零点导致重复提醒。
 */
internal class PlanCheckStore(home: File) {
    private val file = File(home, "plan-check.json")

    /** 没有记录、文件读坏都当「没提醒过」；最多多发一次，不会丢数据。 */
    fun lastDay(): String? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?.removePrefix("\uFEFF") ?: return null
        val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return obj["lastDay"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }

    fun save(lastDay: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildJsonObject { put("lastDay", lastDay) }.toString(), Charsets.UTF_8)
        }
    }
}

/** 计划检查的全局会话 id / 标题：跨计划只留这一段，以后全局管理也在这里。 */
internal const val PLAN_CHECK_SESSION_ID = "plan-check"
internal const val PLAN_CHECK_TITLE = "计划检查"
