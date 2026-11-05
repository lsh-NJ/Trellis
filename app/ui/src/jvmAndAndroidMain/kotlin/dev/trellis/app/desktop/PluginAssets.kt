package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import java.io.File

/** 单张卡片图片的上限。超过这个数的图不是插图，是资源泄漏。 */
internal const val MAX_IMAGE_BYTES = 1_048_576L

/**
 * 插件卡片的图片只允许来自**插件自己的目录**。
 *
 * 三条边界，缺一条都不放：
 *  - 不许绝对路径、不许 `..`、不许盘符：图片是插件的附属物，不是文件系统入口；
 *  - 规范化之后仍要以插件目录开头（防符号链接绕出来）；
 *  - 必须是存在的普通文件、非空、不超过 [MAX_IMAGE_BYTES]。
 *
 * 魔数校验在 [looksLikeImage]：扩展名会骗人，头几个字节不会。
 */
internal fun pluginAssetFile(pluginsDir: File, pluginId: PluginId, ref: String): File? {
    if (ref.isBlank() || ref.length > 200) return null
    val normalized = ref.replace('\\', '/')
    if (normalized.startsWith("/") || normalized.contains(':')) return null
    if (normalized.split('/').any { it == ".." || it.isBlank() }) return null
    val dir = runCatching { File(pluginsDir, pluginId.value).canonicalFile }.getOrNull() ?: return null
    val file = runCatching { File(dir, normalized).canonicalFile }.getOrNull() ?: return null
    if (!file.path.startsWith(dir.path + File.separator)) return null
    if (!file.isFile) return null
    if (file.length() <= 0L || file.length() > MAX_IMAGE_BYTES) return null
    return file
}

/** PNG / JPEG / GIF / WebP 的魔数。 */
internal fun looksLikeImage(bytes: ByteArray): Boolean {
    if (bytes.size < 12) return false
    val png = bytes[0] == 0x89.toByte() &&
        bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() &&
        bytes[3] == 0x47.toByte()
    val jpeg = bytes[0] == 0xFF.toByte() &&
        bytes[1] == 0xD8.toByte() &&
        bytes[2] == 0xFF.toByte()
    val gif = bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte()
    val webp = bytes[0] == 'R'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() &&
        bytes[9] == 'E'.code.toByte() &&
        bytes[10] == 'B'.code.toByte() &&
        bytes[11] == 'P'.code.toByte()
    return png || jpeg || gif || webp
}
