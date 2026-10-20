package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.spi.PluginFileStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌面上「插件文件」的去留。
 *
 * 回收站就是插件目录下的一个点目录：`<plugins>/.trash/<id>/`。
 * 点开头有两个用处：一眼看出它不是插件，以及扫描时被跳过。
 *
 * 为什么是「移动」而不是「删除」：删除必须能反悔。7 天窗口就是那个反悔期，
 * 而反悔要连文件一起反悔  只把数据还回来、插件本身没了，那不叫还原。
 */
class JvmPluginFileStore(private val pluginsDir: File) : PluginFileStore {

    private val trash = File(pluginsDir, ".trash")

    override suspend fun moveToTrash(id: PluginId) {
        withContext(Dispatchers.IO) {
            val src = File(pluginsDir, id.value)
            if (!src.exists()) return@withContext
            val dst = File(trash, id.value)
            if (dst.exists()) dst.deleteRecursively()
            dst.parentFile?.mkdirs()
            move(src, dst)
        }
    }

    override suspend fun restoreFromTrash(id: PluginId) {
        withContext(Dispatchers.IO) {
            val src = File(trash, id.value)
            if (!src.exists()) return@withContext
            val dst = File(pluginsDir, id.value)
            // 目录里已经有一份（用户手动放回去过）：以回收站那份为准，它是「最后的状态」。
            if (dst.exists()) dst.deleteRecursively()
            move(src, dst)
        }
    }

    override suspend fun purgeTrash(id: PluginId) {
        withContext(Dispatchers.IO) { File(trash, id.value).deleteRecursively() }
    }

    /** 同一个卷上 rename 是原子的；跨卷才会失败，那就退化成复制再删。 */
    private fun move(src: File, dst: File) {
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst, overwrite = true)
            src.deleteRecursively()
        }
    }
}
