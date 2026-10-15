package dev.trellis.kernel.audit

import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId

enum class Actor {
    USER,
    AGENT,
    SYSTEM,
}

enum class AuditOutcome {
    APPROVED_AUTO,
    APPROVED_MANUAL,
    DENIED,
}

data class AuditEntry(
    val atMs: Long,
    val actor: Actor,
    val action: String,
    val pluginId: PluginId?,
    val capability: CapabilityId?,
    val detail: String,
    val outcome: AuditOutcome,
)

/**
 * 审计日志。见设计文档 12：**只追加，不修改。**
 *
 * 这是「Agent 到底干了什么」唯一可信的一手证据  插件自己写的日志不算数，
 * 因为它们可以撒谎；审计日志是内核写的。
 */
interface AuditLedger {

    fun append(entry: AuditEntry)

    fun entries(): List<AuditEntry>

    fun entriesFor(pluginId: PluginId): List<AuditEntry> = entries().filter { it.pluginId == pluginId }
}

class InMemoryAuditLedger : AuditLedger {

    private val log = mutableListOf<AuditEntry>()

    override fun append(entry: AuditEntry) {
        log.add(entry)
    }

    override fun entries(): List<AuditEntry> = log.toList()
}