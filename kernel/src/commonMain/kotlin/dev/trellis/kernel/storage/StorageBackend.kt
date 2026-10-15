package dev.trellis.kernel.storage

import dev.trellis.kernel.core.PluginId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 存储后端 SPI。**只有内核拿得到它**  插件永远只拿到绑定好命名空间的 [KvStore]。
 *
 * 命名空间在这里是显式参数，因为内核需要它来做卸载清理与过期回收；
 * 而插件不需要。同一个存储，两个不同的侧面，各拿各的那一面。
 */
interface StorageBackend {

    suspend fun read(namespace: String, key: String): String?

    suspend fun write(namespace: String, key: String, value: String)

    suspend fun delete(namespace: String, key: String)

    suspend fun keys(namespace: String, prefix: String): List<String>

    /** 清空一个命名空间，返回删掉的键数。 */
    suspend fun clearNamespace(namespace: String): Int

    suspend fun namespaces(): List<String>

    /** 该命名空间当前占用字节数（键 + 值）。用于配额检查。 */
    suspend fun sizeOf(namespace: String): Long
}

/** 见设计文档 6.3 配额表的存储部分。 */
data class StorageQuota(
    val maxValueBytes: Int = 1_048_576,
    val maxNamespaceBytes: Long = 33_554_432,
    val maxKeyLength: Int = 256,
)

class StorageQuotaExceededException(detail: String) : IllegalStateException(detail)

/**
 * 内存后端。它是**默认实现**，也是测试与沙箱干跑用的实现。
 *
 * 真实持久化（SQLDelight / 文件）落地时，实现同一个接口即可，内核一行都不用改。
 * 这也意味着现在可以先把隔离性质钉死，而不必先选定存储引擎。
 */
class InMemoryStorageBackend : StorageBackend {

    private val lock = Mutex()
    private val data = mutableMapOf<String, MutableMap<String, String>>()

    override suspend fun read(namespace: String, key: String): String? = lock.withLock {
        data[namespace]?.get(key)
    }

    override suspend fun write(namespace: String, key: String, value: String) = lock.withLock {
        data.getOrPut(namespace) { mutableMapOf() }[key] = value
    }

    override suspend fun delete(namespace: String, key: String) = lock.withLock {
        data[namespace]?.remove(key)
        Unit
    }

    override suspend fun keys(namespace: String, prefix: String): List<String> = lock.withLock {
        data[namespace]?.keys?.filter { it.startsWith(prefix) }?.sorted().orEmpty()
    }

    override suspend fun clearNamespace(namespace: String): Int = lock.withLock {
        data.remove(namespace)?.size ?: 0
    }

    override suspend fun namespaces(): List<String> = lock.withLock {
        data.filterValues { it.isNotEmpty() }.keys.sorted()
    }

    override suspend fun sizeOf(namespace: String): Long = lock.withLock {
        data[namespace]?.entries?.sumOf { (key, value) ->
            (key.encodeToByteArray().size + value.encodeToByteArray().size).toLong()
        } ?: 0L
    }
}

/** 插件数据的命名空间。带前缀，保证与内核自己的命名空间不可能撞上。 */
internal fun storageNamespaceOf(id: PluginId): String = "plugin/" + id.value

/** 内核自己的记账命名空间：待回收计划之类的内核内部状态。插件永远碰不到它。 */
internal const val KERNEL_NAMESPACE: String = "kernel"
