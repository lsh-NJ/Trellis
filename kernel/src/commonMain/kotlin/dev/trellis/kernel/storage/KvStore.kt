package dev.trellis.kernel.storage

/**
 * 插件的数据抽屉。见设计文档 7。
 *
 * **接口里没有「命名空间」这个参数  这不是疏忽，这个设计的全部要点就在这儿。**
 *
 * 每个插件拿到的 [KvStore] 实例已经绑死了自己的命名空间，于是「读别人的数据」
 * 这件事**在类型层面就无法表达**，而不是靠「约定 key 要写成 插件id:真实key」。
 *
 * 反例：如果接口是 `get(key)` 而内部约定 key 必须带插件 id 前缀，
 * 那插件传一个 "dev.b:secret" 就直接越权  那种隔离是纸做的，挡不住任何人。
 */
interface KvStore {

    suspend fun get(key: String): String?

    suspend fun put(key: String, value: String)

    suspend fun delete(key: String)

    /** 列出本插件自己的键。prefix 只在自己命名空间内生效。 */
    suspend fun keys(prefix: String = ""): List<String>

    /** 清空本插件自己的全部数据。 */
    suspend fun clear()
}
