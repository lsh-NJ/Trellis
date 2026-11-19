package dev.trellis.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom

/** 某个域的本地基线：key → 上次同步后双方认可的最新 head。 */
data class DomainState(
    val baseline: MutableMap<String, ObjectHead> = mutableMapOf(),
)

/** `<home>/sync/state.json` 的内存表示。 */
data class SyncState(
    val deviceId: String,
    val lastSyncAt: Long? = null,
    val domains: MutableMap<String, DomainState> = mutableMapOf(),
)

/**
 * 本机同步元数据：`<home>/sync/`。
 *
 * 这个目录**不参与同步**（它是每台设备自己的账本）。里面放：
 * - `device.json`：设备 id/名字；
 * - `state.json`：baseline、上次同步时间；
 * - `conflicts.jsonl` + `conflicts/`：冲突记录与副本；
 * - `sync.log`：同步流水。
 */
class SyncStore(private val home: File) {

    val dir: File = File(home, "sync").apply { mkdirs() }

    private val deviceFile = File(dir, "device.json")
    private val stateFile = File(dir, "state.json")
    private val conflictLog = File(dir, "conflicts.jsonl")
    private val logFile = File(dir, "sync.log")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val deviceId: String by lazy { loadOrCreateDevice() }

    fun deviceId(): String = deviceId

    fun deviceName(): String {
        val text = readText(deviceFile) ?: return "设备"
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return "设备"
        return root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "设备"
    }

    fun loadState(): SyncState {
        val raw = readText(stateFile) ?: return SyncState(deviceId)
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return SyncState(deviceId)
        val domains = buildMap<String, DomainState> {
            val domainsObj = root["domains"] as? JsonObject ?: JsonObject(emptyMap())
            domainsObj.forEach { (domain, element) ->
                val obj = element as? JsonObject ?: return@forEach
                val baselineObj = obj["baseline"] as? JsonObject ?: JsonObject(emptyMap())
                val baseline = buildMap<String, ObjectHead> {
                    baselineObj.forEach { (key, headElement) ->
                        val headObj = headElement as? JsonObject ?: return@forEach
                        val hlc = headObj["hlc"]?.jsonPrimitive?.contentOrNull?.let(Hlc::parse) ?: return@forEach
                        val sha = headObj["sha"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val size = headObj["size"]?.jsonPrimitive?.longOrNull ?: 0L
                        val deleted = headObj["deleted"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                        put(key, ObjectHead(hlc, sha, size, deleted))
                    }
                }
                put(domain, DomainState(baseline.toMutableMap()))
            }
        }
        return SyncState(
            deviceId = deviceId,
            lastSyncAt = root["lastSyncAt"]?.jsonPrimitive?.longOrNull,
            domains = domains.toMutableMap(),
        )
    }

    fun saveState(state: SyncState) {
        val root = buildJsonObject {
            put("schema", 1)
            put("deviceId", state.deviceId)
            state.lastSyncAt?.let { put("lastSyncAt", it) }
            put("domains", buildJsonObject {
                state.domains.forEach { (domain, domainState) ->
                    put(domain, buildJsonObject {
                        put("baseline", buildJsonObject {
                            domainState.baseline.forEach { (key, head) ->
                                put(key, buildJsonObject {
                                    put("hlc", head.hlc.encode())
                                    put("sha", head.sha256)
                                    put("size", head.size)
                                    put("deleted", head.deleted)
                                })
                            }
                        })
                    })
                }
            })
        }
        writeText(stateFile, root.toString())
    }

    /** 把远端冲突版本落盘；返回相对 `sync/` 的路径（给 UI/记录用）。 */
    fun writeConflict(domain: String, key: String, hlc: Hlc, bytes: ByteArray?, deleted: Boolean): String {
        val safeKey = key
            .replace('\\', '/')
            .replace("/", "__")
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "object" }
        val suffix = if (deleted) ".deleted.txt" else ".conflict"
        val file = File(File(dir, "conflicts/$domain").apply { mkdirs() }, "$safeKey.${hlc.encode()}$suffix")
        file.parentFile?.mkdirs()
        file.writeText(
            if (bytes != null && bytes.isNotEmpty()) bytes.toString(Charsets.UTF_8)
            else "（远端这个对象已被删除，没有可恢复的内容。）\n",
            Charsets.UTF_8,
        )
        return file.relativeTo(dir).invariantSeparatorsPath
    }

    fun recordConflict(
        domain: String,
        key: String,
        remoteHlc: Hlc,
        remoteSha: String,
        remoteDeleted: Boolean,
        copyPath: String?,
    ) {
        val line = encodeConflictRecord(
            at = System.currentTimeMillis(),
            domain = domain,
            key = key,
            localDevice = deviceId,
            remoteHlc = remoteHlc,
            remoteSha = remoteSha,
            remoteDeleted = remoteDeleted,
            copyPath = copyPath,
        )
        runCatching { conflictLog.appendText(line + "\n", Charsets.UTF_8) }
    }

    fun log(line: String) {
        runCatching {
            logFile.appendText("[${System.currentTimeMillis()}] $line\n", Charsets.UTF_8)
        }
    }

    private fun loadOrCreateDevice(): String {
        val existing = runCatching {
            json.parseToJsonElement(readText(deviceFile) ?: return@runCatching null).jsonObject
        }.getOrNull()
        existing?.get("deviceId")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val id = "dev_" + randomId()
        writeText(
            deviceFile,
            buildJsonObject {
                put("deviceId", id)
                put("name", "设备 " + id.takeLast(4))
                put("createdAt", System.currentTimeMillis())
            }.toString(),
        )
        return id
    }

    private fun readText(file: File): String? =
        runCatching { if (file.isFile) file.readText(Charsets.UTF_8).removePrefix("\uFEFF") else null }.getOrNull()

    private fun writeText(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp-" + System.nanoTime())
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun randomId(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val random = SecureRandom()
        val bytes = ByteArray(16).also(random::nextBytes)
        val chars = CharArray(26)
        var acc = 0L
        var bits = 0
        var index = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5 && index < chars.size) {
                bits -= 5
                chars[index++] = alphabet[((acc ushr bits) and 31L).toInt()]
            }
        }
        while (index < chars.size) chars[index++] = alphabet[0]
        return String(chars)
    }
}
