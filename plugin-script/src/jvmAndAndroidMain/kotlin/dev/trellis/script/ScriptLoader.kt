package dev.trellis.script

import dev.trellis.dsl.PluginFileSystem
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.diag.KernelError
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.spi.DiscoveredPlugin
import dev.trellis.kernel.spi.DiscoveryResult
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.spi.PluginDiscovery
import dev.trellis.kernel.spi.PluginLoader
import dev.trellis.kernel.time.KernelClock
import dev.trellis.kernel.time.SystemClock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 轨道 B：磁盘上的脚本插件。
 *
 * 目录结构：
 *
 *   plugins/
 *     dev.reading/
 *       manifest.json    身份、权限、想挂哪
 *       main.js          代码
 *
 * 和轨道 A 的分工：轨道 A 是**填表**，轨道 B 是**写程序**。
 * 能填表解决的别写程序；填表解决不了的，才来这里。
 */
class ScriptLoader(
    private val fileSystem: PluginFileSystem,
    private val clock: KernelClock = SystemClock,
) : PluginDiscovery, PluginLoader {

    override val scheme: String = SCHEME

    override val name: String = "script-directory"

    /** 只在 JVM 上跑：桌面端有进程，手机端还没有沙箱。 */
    override val platforms: Set<Platform> = setOf(Platform.JVM)

    private val json: Json = Json { ignoreUnknownKeys = true }
    private val directories: MutableMap<PluginId, String> = mutableMapOf()

    override suspend fun discover(root: String): DiscoveryResult {
        val plugins = mutableListOf<DiscoveredPlugin>()
        val errors = mutableListOf<KernelError>()

        for (directoryName in fileSystem.listDirectories(root).sorted()) {
            val directory = fileSystem.join(root, directoryName)
            val manifestPath = fileSystem.join(directory, MANIFEST_FILE)
            if (!fileSystem.exists(manifestPath)) continue

            val raw = try {
                fileSystem.readText(manifestPath)?.let { json.decodeFromString(ScriptManifestJson.serializer(), it) }
            } catch (t: SerializationException) {
                errors += reject(null, manifestPath + " 解析失败：" + t.message)
                continue
            } ?: continue

            // 别的加载器的地盘，一律不碰。两个加载器抢同一个目录是灾难的开始。
            if (raw.loader.trim() != SCHEME) continue

            val idText = raw.id.trim()
            if (idText.isEmpty()) {
                errors += reject(null, manifestPath + " 没写 id")
                continue
            }
            val pluginId = PluginId(idText)
            val version = try {
                SemVer.parse(raw.version.trim())
            } catch (t: IllegalArgumentException) {
                errors += reject(pluginId, manifestPath + " 的 version 不是合法版本号：" + raw.version)
                continue
            }

            val entry = raw.entry.trim().ifEmpty { DEFAULT_ENTRY }
            val entryPath = fileSystem.join(directory, entry)
            if (!fileSystem.exists(entryPath)) {
                errors += reject(pluginId, "插件 " + idText + " 的代码文件不存在：" + entryPath)
                continue
            }

            val manifest = PluginManifest(
                id = pluginId,
                version = version,
                name = raw.name.trim().ifEmpty { idText },
                description = raw.description.trim(),
                usage = raw.usage.trim(),
                limitation = raw.limitation.trim(),
                loader = SCHEME,
                entry = entry,
                capabilities = raw.capabilities.map { CapabilityId(it.trim()) }.toSet(),
                contributes = raw.contributes.mapKeys { ExtensionPointId(it.key) }
                    .mapValues { (_, list) -> list.map { ContributionDecl(it.id, it.order, it.payload) } },
            )

            directories[pluginId] = directory
            plugins += DiscoveredPlugin(manifest, directory)
        }

        return DiscoveryResult(plugins, errors)
    }

    override suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin {
        val directory = directories[manifest.id]
            ?: throw IllegalStateException("插件 " + manifest.id.value + " 没有被发现过，装不了")
        val entry = manifest.entry?.trim()?.ifEmpty { DEFAULT_ENTRY } ?: DEFAULT_ENTRY
        val entryPath = fileSystem.join(directory, entry)
        val source = fileSystem.readText(entryPath)
            ?: throw IllegalStateException("读不出插件的代码：" + entryPath)
        return ScriptPlugin(manifest, ScriptSandbox(manifest.id.value, source, entry))
    }

    private fun reject(pluginId: PluginId?, detail: String): KernelError =
        KernelError(ErrorCode.MANIFEST_INVALID, pluginId, detail, clock.nowMs())

    /**
     * 脚本插件的 manifest。
     *
     * 刻意**不复用**声明式插件那份 DTO：两份 schema 会各自长大，
     * 共用一个 DTO 的话，某一方的字段迟早会污染另一方。
     */
    @Serializable
    private data class ScriptManifestJson(
        val id: String = "",
        val version: String = "0.0.0",
        val name: String = "",
        val description: String = "",
        val usage: String = "",
        val limitation: String = "",
        val loader: String = SCHEME,
        val entry: String = DEFAULT_ENTRY,
        val capabilities: List<String> = emptyList(),
        val contributes: Map<String, List<ContributionJson>> = emptyMap(),
    )

    @Serializable
    private data class ContributionJson(
        val id: String,
        val order: Int = 100,
        val payload: String? = null,
    )

    private companion object {
        const val SCHEME = "script"
        const val MANIFEST_FILE = "manifest.json"
        const val DEFAULT_ENTRY = "main.js"
    }
}
