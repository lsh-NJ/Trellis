package dev.trellis.kernel.spi

import dev.trellis.kernel.core.PluginId

/**
 * 插件文件的去留。
 *
 * 内核不认识文件系统（领域中立、common 代码里没有 java.io），
 * 但「删除」必须真的动文件  界面上写着删除、插件目录却原样留着，
 * 那不是删除，那是撒谎。
 *
 * 所以这里只留一个**端口**：内核负责决定「该动了」，具体怎么动交给平台层。
 * 默认实现什么都不做，内核自带的那批测试因此完全不受影响。
 */
interface PluginFileStore {

    /** 移入回收站。文件不再参与发现，但还能还原。 */
    suspend fun moveToTrash(id: PluginId)

    /** 从回收站搬回来。不在回收站里就应该什么都不做（幂等）。 */
    suspend fun restoreFromTrash(id: PluginId)

    /** 回收期到了，真删。 */
    suspend fun purgeTrash(id: PluginId)
}

/** 没有文件系统的场景：内核单测、纯内存宿主。 */
object NoopPluginFileStore : PluginFileStore {
    override suspend fun moveToTrash(id: PluginId) = Unit
    override suspend fun restoreFromTrash(id: PluginId) = Unit
    override suspend fun purgeTrash(id: PluginId) = Unit
}
