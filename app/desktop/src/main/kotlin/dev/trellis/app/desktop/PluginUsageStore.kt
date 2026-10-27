package dev.trellis.app.desktop

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 「这个插件最近一次被用是什么时候」。
 *
 * 为什么要有：插件列表能回答「这是谁、活着没」，回答不了「我上次用它是什么时候」。
 * 主题尤其明显——它本身不产生卡片，只能在插件页出现一行名字，用户分不清自己
 * 是不是还开着它、上次切换是不是刚才。于是给每行补一句「上次用：今天 21:27」。
 *
 * 只记**动作**，不记推断：点「应用主题」时才写一次；读文件时只看最后一条。
 * 数据量小、语义清楚，比猜「主题是不是在用」可靠。
 *
 * 为什么要单独一个文件、不塞进 `store.json`：这是**界面层的习惯**，不是插件数据；
 * 插件删了、数据清空，这条记录也不该跟着丢（用户还想知道「它上次是什么时候用的」）。
 */
internal class PluginUsageStore(private val home: File) {

    private val file = File(home, "plugin-usage.json")
    private val json = Json { prettyPrint = true }

    fun load(): Map<String, Long> = runCatching {
        if (!file.isFile) {
            emptyMap()
        } else {
            val text = file.readText(Charsets.UTF_8).removePrefix("\uFEFF")
            val used = json.parseToJsonElement(text).jsonObject["used"]?.jsonObject
                ?: JsonObject(emptyMap())
            used.mapNotNull { (id, value) ->
                val at = value.jsonPrimitive.longOrNull ?: return@mapNotNull null
                if (at <= 0L) null else id to at
            }.toMap()
        }
    }.getOrElse { emptyMap() }

    fun save(usage: Map<String, Long>) {
        val root = buildJsonObject {
            put(
                "used",
                buildJsonObject {
                    for ((id, at) in usage) put(id, JsonPrimitive(at))
                },
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    companion object {

        /** 「上次用」的写法。看不懂的时点（未来时间、0）按没用过处理。 */
        fun describe(nowMs: Long, atMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String {
            if (atMs == null || atMs <= 0L) return "还没用过"
            if (atMs > nowMs) return "还没用过"
            val delta = nowMs - atMs
            if (delta < 60_000L) return "刚刚"
            if (delta < 3_600_000L) return (delta / 60_000L).toString() + " 分钟前"

            val now = Instant.ofEpochMilli(nowMs).atZone(zone)
            val at = Instant.ofEpochMilli(atMs).atZone(zone)
            val clock = DateTimeFormatter.ofPattern("HH:mm")
            return when (at.toLocalDate()) {
                now.toLocalDate() -> "今天 " + at.format(clock)
                now.toLocalDate().minusDays(1) -> "昨天 " + at.format(clock)
                else -> at.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            }
        }
    }
}
