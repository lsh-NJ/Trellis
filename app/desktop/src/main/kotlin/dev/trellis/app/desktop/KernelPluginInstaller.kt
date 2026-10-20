package dev.trellis.app.desktop

import dev.trellis.ai.PluginInstaller
import dev.trellis.kernel.api.InstallResult
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把「一堆文件」真正变成跑起来的插件。
 *
 * ### 失败要清干净
 *
 * 装失败了就把刚写的目录删掉、把内核里的残留摘掉。
 * 否则下次生成会撞上「id 已经存在」，而那个「存在」的其实是个半成品：
 * 模型会一遍遍收到一条它无法修正的错误，最后放弃。
 */
class KernelPluginInstaller(
    private val pluginsDir: File,
    private val kernel: Kernel,
) : PluginInstaller {

    /**
     * 哪些权限算「用户已经在这次请求里答应了」。
     *
     * 只有读和写：它们改的是用户自己的任务数据，看得见、可撤销、而且
     * 每次执行仍然要过审批门。删数据、联网、读剪贴板这些不在这里 ——
     * 那些必须由用户去插件详情里亲手点。
     */
    private val autoGranted = setOf("read_data", "write_data")

    override suspend fun ensureGrants(id: String, capabilities: Set<String>): String? {
        if (capabilities.isEmpty()) return null

        val ids = capabilities.map { CapabilityId(it) }.toSet()
        val auto = ids.filter { it.value in autoGranted }.toSet()
        val manual = ids - auto

        if (auto.isNotEmpty()) kernel.grants.grant(PluginId(id), auto)

        if (manual.isNotEmpty()) {
            return "这个插件要用「" + manual.map { it.value }.sorted().joinToString("、") +
                "」权限，这类权限不能替你点头。请先装一个不要这些权限的版本，" +
                "或者装好之后到插件详情里自己授权。"
        }
        return null
    }

    override suspend fun install(files: Map<String, String>): String? {
        val id = pluginIdOf(files) ?: return "manifest.json 里没有合法的 id"
        val pluginId = PluginId(id)
        val dir = File(pluginsDir, id)

        // 覆盖安装：先把可能正在跑的旧版本摘下来。
        // 不存在时 disable 返回 NotFound，是无害的。
        kernel.disable(pluginId)

        dir.deleteRecursively()
        if (!dir.mkdirs()) return "无法创建插件目录：" + dir.absolutePath

        for ((name, content) in files) {
            val target = File(dir, name)
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
        }

        val problems = when (val result = kernel.install(pluginId)) {
            // 装载成功还不够：贡献被拒（K019）的话卡片根本不会出现，
            // 用户会以为「AI 生成的插件不工作」。所以这也算失败。
            is InstallResult.Installed -> kernel.plugin(pluginId)?.errors.orEmpty()
            is InstallResult.Rejected -> result.errors
        }

        if (problems.isEmpty()) return null

        dir.deleteRecursively()
        kernel.disable(pluginId)
        return problems.joinToString("\n") { it.detail }
    }

    /** 从文件地图里读出插件 id。只读不信任：解析不出来就当没有。 */
    private fun pluginIdOf(files: Map<String, String>): String? {
        val manifest = files["manifest.json"] ?: return null
        return runCatching {
            Json.parseToJsonElement(manifest).jsonObject["id"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
