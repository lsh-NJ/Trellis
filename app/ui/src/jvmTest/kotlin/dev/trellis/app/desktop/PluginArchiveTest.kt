package dev.trellis.app.desktop

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 插件压缩包的读取规则。
 *
 * 这一层是「用户随手选的一个 zip」和「内核要的文件地图」之间唯一的关口，
 * 所以每条规则都值得钉住：路径穿越、只认文本、剥掉多余的一层目录。
 * 这些规则写错的表现都很安静 —— 插件装上去了但少了文件，或者什么都没装上却报成功。
 */
class PluginArchiveTest {

    @Test
    fun `正常压缩包读成文件地图`() {
        val zip = zipOf(
            "manifest.json" to """{"id":"dev.demo","name":"演示"}""",
            "main.dsl.json" to """{"pages":[]}""",
        )
        val files = readPluginArchive(zip)
        assertEquals(setOf("manifest.json", "main.dsl.json"), files.keys)
        assertTrue(files.getValue("manifest.json").contains("dev.demo"))
    }

    @Test
    fun `多套一层目录会被剥掉`() {
        // 从 GitHub 下载的包基本都是这个形状：`my-plugin/manifest.json`。
        // 不剥的话 kernel 找不到 manifest，用户看到的是「压缩包里没有 manifest.json」。
        val zip = zipOf(
            "my-plugin/manifest.json" to """{"id":"dev.demo"}""",
            "my-plugin/main.dsl.json" to "{}",
        )
        val files = readPluginArchive(zip)
        assertEquals(setOf("manifest.json", "main.dsl.json"), files.keys)
    }

    @Test
    fun `路径穿越与二进制文件被丢掉`() {
        val zip = zipOf(
            "manifest.json" to """{"id":"dev.demo"}""",
            "../../escape.json" to "{}",
            "/etc/passwd" to "root",
            "cover.png" to "not-really-a-png",
            "__MACOSX/._manifest.json" to "xjunk",
        )
        val files = readPluginArchive(zip)
        assertEquals(setOf("manifest.json"), files.keys, "只应该留下 manifest.json：" + files.keys)
        assertFalse(files.keys.any { it.contains("..") })
        assertFalse(files.keys.any { it.endsWith(".png") })
    }

    @Test
    fun `空压缩包读成空地图而不是抛异常`() {
        // 调用方靠「空」来报一句人话，不应该收到一个 ZipException。
        val files = readPluginArchive(zipOf())
        assertTrue(files.isEmpty())
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("trellis-plugin-", ".zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }
}
