package dev.trellis.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 把一个本地目录当成「远端」：可以是 iCloud/Dropbox/Syncthing 同步的文件夹，
 * 也可以是测试里的临时目录。
 *
 * 安全：所有路径都相对 [root]，拒绝绝对路径和 `..`；写文件先写同目录临时文件再 rename，
 * 避免半截 JSON 被另一台设备读到。
 */
class FolderTransport(private val root: File) : SyncTransport {

    private val canonicalRoot: File

    init {
        root.mkdirs()
        canonicalRoot = root.canonicalFile
    }

    override suspend fun get(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = resolve(path)
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    override suspend fun put(path: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = resolve(path)
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
        Unit
    }

    override suspend fun list(prefix: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val normalized = normalize(prefix)
        val base = if (normalized.isEmpty()) canonicalRoot else resolve(normalized)
        if (!base.exists()) return@withContext emptyList()
        val files = if (base.isDirectory) base.walkTopDown().filter { it.isFile } else sequenceOf(base)
        files
            .filter { !it.name.contains(".tmp-") }
            .map { file ->
                val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                RemoteFile(relative, file.length())
            }
            .toList()
    }

    private fun normalize(path: String): String =
        path.replace('\\', '/').trim('/')

    private fun resolve(path: String): File {
        require(!path.startsWith("/") && !path.startsWith("\\\\")) { "远端路径必须是相对路径：$path" }
        val normalized = normalize(path)
        require(normalized.isNotEmpty()) { "远端路径不能为空" }
        val segments = normalized.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." }) { "非法远端路径：$path" }
        val file = File(canonicalRoot, normalized)
        val rootPath = canonicalRoot
        val filePath = file.canonicalFile
        require(filePath == rootPath || filePath.path.startsWith(rootPath.path + File.separator)) {
            "远端路径越界：$path"
        }
        return filePath
    }
}
