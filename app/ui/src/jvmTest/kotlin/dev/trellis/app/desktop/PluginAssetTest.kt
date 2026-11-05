package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 插件图片的边界。
 *
 * 这组测试守的不是「能不能显示」，而是「能不能越界」：图片是插件唯一
 * 能碰到宿主文件系统的地方，穿过这道门的每一条路都必须被堵死。
 */
class PluginAssetTest {

    private val png = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0,
    )

    private fun tempPluginsDir(): File = Files.createTempDirectory("trellis-assets").toFile()

    @Test
    fun anImageInsideThePluginFolderIsAllowed() {
        val root = tempPluginsDir()
        val art = File(root, "dev.test/art").apply { mkdirs() }
        File(art, "hero.png").writeBytes(png)

        val file = pluginAssetFile(root, PluginId("dev.test"), "art/hero.png")

        assertNotNull(file)
        assertEquals("hero.png", file.name)
    }

    @Test
    fun climbingOutOfThePluginFolderIsRefused() {
        val root = tempPluginsDir()
        File(root, "dev.test").mkdirs()
        File(root, "stolen.png").writeBytes(png)

        assertNull(pluginAssetFile(root, PluginId("dev.test"), "../stolen.png"), "不许 ..")
        assertNull(pluginAssetFile(root, PluginId("dev.test"), "/etc/passwd"), "不许绝对路径")
        assertNull(pluginAssetFile(root, PluginId("dev.test"), "C:/Windows/win.ini"), "不许盘符")
        assertNull(pluginAssetFile(root, PluginId("dev.test"), "art/../../stolen.png"), "不许绕出来")
    }

    @Test
    fun bigFilesAndNonImagesAreRefused() {
        val root = tempPluginsDir()
        val dir = File(root, "dev.test").apply { mkdirs() }
        File(dir, "huge.png").writeBytes(ByteArray((MAX_IMAGE_BYTES + 1).toInt()) { 0x89.toByte() })
        File(dir, "note.txt").writeText("这不是图片")

        assertNull(pluginAssetFile(root, PluginId("dev.test"), "huge.png"), "超过 1MB 的不收")
        assertFalse(looksLikeImage(File(dir, "note.txt").readBytes()), "扩展名不代表内容")
        assertTrue(looksLikeImage(png), "真 PNG 的头几个字节要认出来")
    }

    @Test
    fun missingFilesAreRefused() {
        val root = tempPluginsDir()
        File(root, "dev.test").mkdirs()

        assertNull(pluginAssetFile(root, PluginId("dev.test"), "art/none.png"))
    }
}
