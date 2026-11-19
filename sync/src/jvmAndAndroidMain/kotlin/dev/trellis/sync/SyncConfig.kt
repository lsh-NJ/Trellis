package dev.trellis.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * `<home>/sync/config.json`：同步的开关、传输方式、远端地址、启用的域。
 *
 * 传输方式：
 * - `folder`：本地/挂载目录（WebDAV 客户端、iCloud/Dropbox/Syncthing 或测试目录）；
 * - `http`：自建 HTTP 服务（可放在 token 网关 + 隧道后面）。
 *
 * token 目前和配置放同一个文件（桌面 600 / Android 应用私有目录）；如果以后要更严，
 * 再接系统安全存储。
 */
data class SyncConfig(
    val enabled: Boolean = false,
    val transport: String = TRANSPORT_FOLDER,
    val remotePath: String = "",
    val baseUrl: String = "",
    val token: String = "",
    val domains: Set<String> = setOf(DOMAIN_NOTES, DOMAIN_PLAN_DOCS),
) {
    fun isValid(): Boolean = enabled && when (transport) {
        TRANSPORT_HTTP -> baseUrl.isNotBlank()
        else -> remotePath.isNotBlank()
    }

    companion object {
        const val TRANSPORT_FOLDER: String = "folder"
        const val TRANSPORT_HTTP: String = "http"
        val SUPPORTED_TRANSPORTS: Set<String> = setOf(TRANSPORT_FOLDER, TRANSPORT_HTTP)

        const val DOMAIN_NOTES: String = "notes"
        const val DOMAIN_PLAN_DOCS: String = "plan-docs"
        val SUPPORTED_DOMAINS: Set<String> = setOf(DOMAIN_NOTES, DOMAIN_PLAN_DOCS)
    }
}

object SyncConfigStore {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun file(home: File): File = File(File(home, "sync"), "config.json")

    fun load(home: File): SyncConfig {
        val file = file(home)
        if (!file.isFile) return SyncConfig()
        val root = runCatching {
            json.parseToJsonElement(file.readText(Charsets.UTF_8).removePrefix("\uFEFF")).jsonObject
        }.getOrNull() ?: return SyncConfig()
        val domains = (root["domains"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filter { it in SyncConfig.SUPPORTED_DOMAINS }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: SyncConfig().domains
        val transport = root["transport"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in SyncConfig.SUPPORTED_TRANSPORTS }
            ?: SyncConfig.TRANSPORT_FOLDER
        return SyncConfig(
            enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
            transport = transport,
            remotePath = root["remotePath"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            baseUrl = root["baseUrl"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            token = root["token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            domains = domains,
        )
    }

    fun save(home: File, config: SyncConfig) {
        val file = file(home)
        file.parentFile?.mkdirs()
        val root = buildJsonObject {
            put("schema", 1)
            put("enabled", config.enabled)
            put("transport", config.transport)
            put("remotePath", config.remotePath)
            put("baseUrl", config.baseUrl)
            put("token", config.token)
            put("domains", buildJsonArray { config.domains.sorted().forEach { add(it) } })
        }
        val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
        try {
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
