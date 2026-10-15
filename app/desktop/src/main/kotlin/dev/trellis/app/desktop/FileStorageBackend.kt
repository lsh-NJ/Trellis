package dev.trellis.app.desktop

import dev.trellis.kernel.storage.StorageBackend
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 把存储落到一个 JSON 文件里。
 *
 * 刻意做得很笨：整个数据集读进内存，每次写完整落盘一次。
 * 对「一个人自己的待办清单」这个量级完全够用，而且**没有并发和事务的坑**。
 *
 * 真正的 SQL 后端落地时替换这个类即可  内核只认 [StorageBackend] 接口，
 * 上层插件只认 `PlanService`，两边都不用改。
 */
class FileStorageBackend(private val file: File) : StorageBackend {

    private val json = Json { prettyPrint = true }
    private val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private val lock = Mutex()

    private val data: MutableMap<String, MutableMap<String, String>> = load()

    private fun load(): MutableMap<String, MutableMap<String, String>> {
        if (!file.exists()) return mutableMapOf()
        return try {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
                .mapValues { it.value.toMutableMap() }
                .toMutableMap()
        } catch (t: Exception) {
            // 存储文件坏了不该让 App 打不开  备份一份再从空开始
            val backup = File(file.parentFile, file.name + ".broken")
            runCatching { file.copyTo(backup, overwrite = true) }
            mutableMapOf()
        }
    }

    private fun persist() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, data), Charsets.UTF_8)
    }

    override suspend fun read(namespace: String, key: String): String? = lock.withLock {
        data[namespace]?.get(key)
    }

    override suspend fun write(namespace: String, key: String, value: String) = lock.withLock {
        data.getOrPut(namespace) { mutableMapOf() }[key] = value
        persist()
        Unit
    }

    override suspend fun delete(namespace: String, key: String) = lock.withLock {
        data[namespace]?.remove(key)
        persist()
        Unit
    }

    override suspend fun keys(namespace: String, prefix: String): List<String> = lock.withLock {
        data[namespace]?.keys?.filter { it.startsWith(prefix) }?.sorted().orEmpty()
    }

    override suspend fun clearNamespace(namespace: String): Int = lock.withLock {
        val removed = data.remove(namespace)?.size ?: 0
        persist()
        removed
    }

    override suspend fun namespaces(): List<String> = lock.withLock {
        data.filterValues { it.isNotEmpty() }.keys.sorted()
    }

    override suspend fun sizeOf(namespace: String): Long = lock.withLock {
        data[namespace]?.entries?.sumOf { (k, v) ->
            (k.encodeToByteArray().size + v.encodeToByteArray().size).toLong()
        } ?: 0L
    }
}
