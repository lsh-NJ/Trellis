package dev.trellis.kernel.spi

import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest

/**
 * 内置插件加载器：插件已经编译进 App 包里，只是仍然走完整的插件生命周期。
 *
 * 这就是 dogfooding  第一方功能与第三方插件接受完全相同的待遇。
 * 它同时充当发现源（内置插件的清单在编译期就已知）。
 */
class BuiltinLoader(private val plugins: List<Plugin>) : PluginLoader, PluginDiscovery {

    override val scheme: String = SCHEME

    override val name: String = "builtin"

    override val platforms: Set<Platform> = Platform.entries.toSet()

    override suspend fun discover(root: String): DiscoveryResult =
        DiscoveryResult(
            plugins = plugins.map { DiscoveredPlugin(it.manifest, "builtin:" + it.manifest.id.value) },
        )

    override suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin =
        plugins.firstOrNull { it.manifest.id == manifest.id }
            ?: error("内置插件 ${manifest.id.value} 未注册到 BuiltinLoader")

    companion object {
        const val SCHEME: String = "builtin"
    }
}