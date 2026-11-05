package dev.trellis.dsl

import java.io.File

/** 桌面 / Android 用的真实文件系统实现。 */
class JvmPluginFileSystem : PluginFileSystem {

    private companion object {
        const val BOM = "\uFEFF"
    }

    override fun listDirectories(root: String): List<String> {
        val dir = File(root)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            // 跳过点目录：`<plugins>/.trash/<id>` 是回收站，不是插件。
            // 不跳的话每次启动都会把回收站里的东西当插件扫一遍，然后报一堆错。
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun readText(path: String): String? =
        runCatching {
            // 去掉 UTF-8 BOM。
            //
            // 这不是洁癖：Windows 上的记事本、PowerShell 的 `Set-Content -Encoding UTF8`
            // 都会给文件加 BOM（EF BB BF）。Java 按 UTF-8 读出来会变成字符串开头的
            // U+FEFF，而 JSON 解析器遇到它会直接报「Unexpected JSON token」
            // 用户看到的是一句莫名其妙的解析错误，而文件内容其实完全正确。
            //
            // 插件作者大多不是程序员，他们只会用记事本。这个坑必须在这里补。
            File(path).readText(Charsets.UTF_8).removePrefix(BOM)
        }.getOrNull()

    override fun join(vararg parts: String): String = parts.joinToString(File.separator)
}