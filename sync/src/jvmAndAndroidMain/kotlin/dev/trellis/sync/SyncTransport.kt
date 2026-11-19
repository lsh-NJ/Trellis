package dev.trellis.sync

/**
 * 远端存储的最小抽象。
 *
 * 刻意只要求三件事：读、写、列目录。协议本身**不依赖 CAS/ETag/事务**：
 * - 每台设备只写自己的 `heads/<domain>/<deviceId>.json` 和 `logs/...`；
 * - blob 按内容寻址、内容不可变，重复写是幂等的；
 * - 读端自己做 head 合并。
 *
 * 所以「文件夹（云盘/Syncthing）」「WebDAV」「自建 HTTP」都能实现这个接口，
 * 同步逻辑只写一遍。
 */
interface SyncTransport {
    /** 读一个对象；不存在返回 null。 */
    suspend fun get(path: String): ByteArray?

    /** 写一个对象（覆盖或创建）。上层保证只写自己的命名空间。 */
    suspend fun put(path: String, bytes: ByteArray)

    /** 列出某个前缀下的文件；prefix 以 `/` 结尾表示目录。 */
    suspend fun list(prefix: String): List<RemoteFile>
}
