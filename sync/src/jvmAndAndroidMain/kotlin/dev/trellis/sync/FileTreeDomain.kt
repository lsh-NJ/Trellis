package dev.trellis.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把一个目录树当成可同步域：key = 相对路径（用 `/`），默认只收 `.md`。
 *
 * 适用于 `knowledge/notes/`、`knowledge/plan-docs/` 下这类「文件是真相」的数据。
 * 注意：同步只覆盖 [root] 下的文件；冲突副本由 [SyncStore] 写到 `sync/conflicts/`，
 * 不会污染这个目录（否则笔记列表会多出冲突文件）。
 */
class FileTreeDomain(
    override val id: String,
    private val root: File,
    private val extensions: Set<String> = setOf(".md"),
    private val include: (File) -> Boolean = { true },
) : SyncDomain {

    init {
        root.mkdirs()
    }

    override suspend fun scanLocal(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        if (!root.exists()) return@withContext emptyMap()
        buildMap {
            root.walkTopDown()
                .filter { it.isFile && include(it) }
                .filter { file -> extensions.any { file.name.endsWith(it, ignoreCase = true) } }
                .filter { !it.name.contains(".tmp-") }
                .forEach { file ->
                    val key = file.relativeTo(root).invariantSeparatorsPath
                    runCatching { file.readBytes() }.getOrNull()?.let { put(key, it) }
                }
        }
    }

    override suspend fun applyRemote(key: String, bytes: ByteArray?, deleted: Boolean) = withContext(Dispatchers.IO) {
        val target = fileOf(key)
        if (deleted) {
            if (target.isFile) target.delete()
            cleanupEmptyParents(target.parentFile)
            return@withContext
        }
        requireNotNull(bytes) { "applyRemote 收到非删除却没有内容：$key" }
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp-" + System.nanoTime())
        try {
            tmp.writeBytes(bytes)
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun fileOf(key: String): File {
        val normalized = key.replace('\\', '/').trim('/')
        require(normalized.isNotEmpty()) { "key 不能为空" }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "非法 key：$key" }
        val file = File(root, normalized)
        val rootPath = root.canonicalFile
        val filePath = file.canonicalFile
        require(filePath.path.startsWith(rootPath.path + File.separator)) { "key 越界：$key" }
        return filePath
    }

    private fun cleanupEmptyParents(dir: File?) {
        var current = dir
        while (current != null && current != root && current.isDirectory) {
            val children = current.list() ?: break
            if (children.isNotEmpty()) break
            if (!current.delete()) break
            current = current.parentFile
        }
    }
}
