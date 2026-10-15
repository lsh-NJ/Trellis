package dev.trellis.dsl

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmPluginFileSystemTest {

    private fun tempDir(): File =
        Files.createTempDirectory("ct-fs").toFile().apply { deleteOnExit() }

    /**
     * Windows 上的记事本、PowerShell 的 `Set-Content -Encoding UTF8` 都会加 BOM。
     * 插件作者大多不是程序员，他们只会用记事本  所以这个坑必须在加载器里补，
     * 而不是在文档里写一句「请不要用带 BOM 的编辑器」。
     */
    @Test
    fun stripsUtf8BomSoWindowsAuthoredPluginsStillParse() {
        val file = File(tempDir(), "manifest.json")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        file.writeBytes(bom + """{"id":"ai.demo"}""".toByteArray(Charsets.UTF_8))

        val text = JvmPluginFileSystem().readText(file.absolutePath)

        assertEquals("""{"id":"ai.demo"}""", text)
    }

    @Test
    fun readsPlainUtf8Unchanged() {
        val file = File(tempDir(), "plain.json")
        file.writeBytes("""{"中文":"内容"}""".toByteArray(Charsets.UTF_8))

        assertEquals("""{"中文":"内容"}""", JvmPluginFileSystem().readText(file.absolutePath))
    }

    @Test
    fun missingFileReturnsNullInsteadOfThrowing() {
        assertNull(JvmPluginFileSystem().readText(File(tempDir(), "nope.json").absolutePath))
    }

    @Test
    fun listsOnlyDirectories() {
        val root = tempDir()
        File(root, "a").mkdirs()
        File(root, "b").mkdirs()
        File(root, "loose.json").writeText("{}")

        assertEquals(listOf("a", "b"), JvmPluginFileSystem().listDirectories(root.absolutePath))
    }
}
