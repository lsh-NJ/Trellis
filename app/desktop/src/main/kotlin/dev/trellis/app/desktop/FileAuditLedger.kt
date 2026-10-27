package dev.trellis.app.desktop

import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.audit.AuditEntry
import dev.trellis.kernel.audit.AuditLedger
import dev.trellis.kernel.audit.AuditOutcome
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 落盘的审计日志：一行一个 JSON 对象，只追加、不修改历史。
 *
 * 为什么要有它：内存账本一重启就没了，而「昨天是谁把插件删了」这个问题
 * 恰恰是重启之后才会问的。所以内核把条目交给它，它负责写 `~/.trellis/audit.log`。
 *
 * 只留最近 [capacity] 条：审计是「查最近发生了什么」，不是数据仓库；
 * 留太多会变成又一个没人看的大文件。超出后从最老的开始丢。
 *
 * 写失败不影响主流程（磁盘满、文件被占用），丢一条审计好过让删除按钮报错。
 * 但内存里那份仍然完整，本次运行期间还能在诊断页看到。
 */
internal class FileAuditLedger(
    private val file: File,
    private val capacity: Int = 500,
) : AuditLedger {

    private val json = Json { ignoreUnknownKeys = true }

    private val log = mutableListOf<AuditEntry>()

    init {
        load()
    }

    override fun append(entry: AuditEntry) {
        log.add(entry)
        trim()
        runCatching { rewrite() }
    }

    override fun entries(): List<AuditEntry> = log.toList()

    private fun load() {
        if (!file.isFile) return
        runCatching {
            file.readLines(Charsets.UTF_8).forEach { line ->
                val text = line.trim()
                if (text.isEmpty()) return@forEach
                parse(text)?.let { log.add(it) }
            }
        }
        trim()
    }

    private fun trim() {
        while (log.size > capacity) log.removeAt(0)
    }

    private fun parse(line: String): AuditEntry? = runCatching {
        val o = json.parseToJsonElement(line).jsonObject
        AuditEntry(
            atMs = o.getValue("at").jsonPrimitive.content.toLong(),
            actor = Actor.valueOf(o.getValue("actor").jsonPrimitive.content),
            action = o.getValue("action").jsonPrimitive.content,
            pluginId = o["plugin"]?.jsonPrimitive?.contentOrNull?.let { PluginId(it) },
            capability = o["capability"]?.jsonPrimitive?.contentOrNull?.let { CapabilityId(it) },
            detail = o["detail"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            outcome = AuditOutcome.valueOf(o.getValue("outcome").jsonPrimitive.content),
        )
    }.getOrNull()

    private fun rewrite() {
        file.parentFile?.mkdirs()
        val body = buildString {
            for (entry in log) {
                append(encode(entry))
                append('\n')
            }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(body, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun encode(entry: AuditEntry): String = buildJsonObject {
        put("at", entry.atMs)
        put("actor", entry.actor.name)
        put("action", entry.action)
        put("plugin", entry.pluginId?.value)
        put("capability", entry.capability?.value)
        put("detail", entry.detail)
        put("outcome", entry.outcome.name)
    }.toString()
}
