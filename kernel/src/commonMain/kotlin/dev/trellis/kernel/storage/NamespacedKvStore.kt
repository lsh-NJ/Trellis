package dev.trellis.kernel.storage

/**
 * 绑定死命名空间的 [KvStore]。插件的每一次读写都只会落到自己的那一格。
 *
 * 配额检查也在这里做：见设计文档 6.3。
 */
internal class NamespacedKvStore(
    private val backend: StorageBackend,
    private val namespace: String,
    private val label: String,
    private val quota: StorageQuota,
) : KvStore {

    override suspend fun get(key: String): String? {
        requireUsableKey(key)
        return backend.read(namespace, key)
    }

    override suspend fun put(key: String, value: String) {
        requireUsableKey(key)

        val valueBytes = value.encodeToByteArray().size
        if (valueBytes > quota.maxValueBytes) {
            throw StorageQuotaExceededException(
                "单个值 " + valueBytes + " 字节，超过上限 " + quota.maxValueBytes +
                    " 字节（插件 " + label + "，键 " + key + "）",
            )
        }

        // 覆盖写要算增量，否则反复改写同一个键会被误判为超限
        val previousBytes = backend.read(namespace, key)?.encodeToByteArray()?.size ?: 0
        val projected = backend.sizeOf(namespace) + (valueBytes - previousBytes)
        if (projected > quota.maxNamespaceBytes) {
            throw StorageQuotaExceededException(
                "命名空间总用量将达到 " + projected + " 字节，超过上限 " +
                    quota.maxNamespaceBytes + " 字节（插件 " + label + "）",
            )
        }

        backend.write(namespace, key, value)
    }

    override suspend fun delete(key: String) {
        requireUsableKey(key)
        backend.delete(namespace, key)
    }

    override suspend fun keys(prefix: String): List<String> = backend.keys(namespace, prefix)

    override suspend fun clear() {
        backend.clearNamespace(namespace)
    }

    private fun requireUsableKey(key: String) {
        require(key.isNotEmpty()) { "存储键不能为空" }
        require(key.length <= quota.maxKeyLength) {
            "存储键过长：" + key.length + " > " + quota.maxKeyLength
        }
    }
}
