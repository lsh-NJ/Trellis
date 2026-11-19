package dev.trellis.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 一个对象（文件/记录）在某一台设备上的最新版本声明。 */
data class ObjectHead(
    val hlc: Hlc,
    val sha256: String,
    val size: Long,
    val deleted: Boolean = false,
)

/** 一台设备对某个域内所有对象的 head；远端路径 `heads/<domain>/<deviceId>.json`。 */
data class DeviceHeads(
    val schema: Int = SCHEMA,
    val deviceId: String,
    val objects: Map<String, ObjectHead>,
) {
    companion object {
        const val SCHEMA: Int = 1
    }
}

/** 一次同步的统计；UI/日志用。 */
data class SyncReport(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val conflicts: Int = 0,
    val errors: List<String> = emptyList(),
) {
    val ok: Boolean get() = errors.isEmpty()

    fun summary(): String =
        "拉取 $pulled，推送 $pushed，冲突 $conflicts" +
            if (errors.isEmpty()) "" else "，错误：${errors.joinToString("；")}"
}

/** 远端的文件条目（只要路径和大小，列表用）。 */
data class RemoteFile(val path: String, val size: Long)

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val chars = CharArray(digest.size * 2)
    val hex = "0123456789abcdef"
    for (i in digest.indices) {
        val v = digest[i].toInt() and 0xFF
        chars[i * 2] = hex[v ushr 4]
        chars[i * 2 + 1] = hex[v and 0x0F]
    }
    return String(chars)
}

fun encodeDeviceHeads(heads: DeviceHeads): ByteArray {
    val root = buildJsonObject {
        put("schema", heads.schema)
        put("deviceId", heads.deviceId)
        put("objects", buildJsonObject {
            heads.objects.forEach { (key, head) ->
                put(key, buildJsonObject {
                    put("hlc", head.hlc.encode())
                    put("sha", head.sha256)
                    put("size", head.size)
                    put("deleted", head.deleted)
                })
            }
        })
    }
    return root.toString().toByteArray(Charsets.UTF_8)
}

fun decodeDeviceHeads(raw: ByteArray): DeviceHeads? = runCatching {
    val root = json.parseToJsonElement(raw.toString(Charsets.UTF_8).removePrefix("﻿")).jsonObject
    val deviceId = root["deviceId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val objectsObj = root["objects"] as? JsonObject ?: JsonObject(emptyMap())
    val objects = buildMap {
        objectsObj.forEach { (key, element) ->
            val obj = element as? JsonObject ?: return@forEach
            val hlc = obj["hlc"]?.jsonPrimitive?.contentOrNull?.let(Hlc::parse) ?: return@forEach
            val sha = obj["sha"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            val deleted = obj["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
            put(key, ObjectHead(hlc, sha, size, deleted))
        }
    }
    DeviceHeads(schema = (root["schema"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1), deviceId = deviceId, objects = objects)
}.getOrNull()

fun encodeDeviceInfo(deviceId: String, name: String, platform: String, lastSeen: Long): ByteArray =
    buildJsonObject {
        put("deviceId", deviceId)
        put("name", name)
        put("platform", platform)
        put("lastSeen", lastSeen)
    }.toString().toByteArray(Charsets.UTF_8)

fun encodeConflictRecord(
    at: Long,
    domain: String,
    key: String,
    localDevice: String,
    remoteHlc: Hlc,
    remoteSha: String,
    remoteDeleted: Boolean,
    copyPath: String?,
): String = buildJsonObject {
    put("at", at)
    put("domain", domain)
    put("key", key)
    put("localDevice", localDevice)
    put("remoteHlc", remoteHlc.encode())
    put("remoteSha", remoteSha)
    put("remoteDeleted", remoteDeleted)
    if (copyPath != null) put("copyPath", copyPath)
}.toString()
