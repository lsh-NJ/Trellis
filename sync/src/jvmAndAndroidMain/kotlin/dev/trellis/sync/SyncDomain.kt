package dev.trellis.sync

/**
 * 一个可同步的数据域（notes / plan-docs / records / plan…）。
 *
 * 引擎只认「key → bytes」：文件域 key 是相对路径，记录域 key 是 record id。
 * 具体 store 的读写策略留在实现方；引擎负责比较 head、决定拉/推/冲突。
 */
interface SyncDomain {

    /** 域 id，同时是远端路径 `heads/<id>/`、`blobs/<id>/` 的一段。 */
    val id: String

    /** 扫描本机当前内容（key → bytes）。读失败的对象可以跳过，不要抛整个域。 */
    suspend fun scanLocal(): Map<String, ByteArray>

    /**
     * 应用远端版本。
     *
     * @param bytes 远端内容；[deleted] 为 true 时忽略
     * @param deleted 远端已删除
     */
    suspend fun applyRemote(key: String, bytes: ByteArray?, deleted: Boolean)
}
