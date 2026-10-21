package dev.trellis.app.desktop

import dev.trellis.ai.flow.Issue
import dev.trellis.ai.flow.SelfTestTool
import dev.trellis.dsl.DslLoader
import dev.trellis.dsl.PluginFileSystem

/**
 * 装之前先把插件过一遍：**能跑的跑一遍，能解析的解析一遍**。
 *
 * 两条路分开走，因为它们的错法不一样：
 *   - 脚本插件（main.js）：只有真跑才知道画不画得出来，交给 [ScriptSelfTest]
 *   - 声明式插件（main.dsl.json）：拿真正的 DSL 解析器读一遍，比逐个字段自检更接近事实
 */
class PluginSelfTest(private val script: ScriptSelfTest = ScriptSelfTest()) : SelfTestTool {

    override suspend fun test(files: Map<String, String>): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (files.containsKey("main.js")) {
            issues += script.test(files)
            return issues
        }
        if (!files.containsKey("main.dsl.json")) return issues
        issues += dsl(files)
        return issues
    }

    private suspend fun dsl(files: Map<String, String>): List<Issue> {
        val loader = DslLoader(MemoryFiles(files))
        val result = loader.discover("")
        return result.errors.map {
            Issue(
                code = "DSL_" + it.code.name,
                where = it.pluginId?.value ?: "插件",
                message = it.detail,
                suggestion = "照报错改 main.dsl.json 或 manifest.json",
            )
        }
    }
}

/** 把内存里的几个文件当成一个插件目录，喂给真正的解析器。 */
private class MemoryFiles(private val files: Map<String, String>) : PluginFileSystem {

    override fun listDirectories(root: String): List<String> = listOf(DIRECTORY)

    override fun exists(path: String): Boolean = files.containsKey(clean(path))

    override fun readText(path: String): String? = files[clean(path)]

    override fun join(vararg parts: String): String {
        val joined = parts.filter { it.isNotEmpty() }.joinToString("/")
        return if (joined.startsWith("/")) joined else "/" + joined
    }

    private fun clean(path: String): String =
        path.trimStart('/').removePrefix(DIRECTORY + "/")

    private companion object {
        const val DIRECTORY = "plugin"
    }
}
