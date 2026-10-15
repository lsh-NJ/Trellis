package dev.trellis.kernel.spi

import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest

/** 内核运行所在的平台。用于裁决某个 loader 在当前设备上是否可用。 */
enum class Platform { ANDROID, JVM, IOS, WASM }

data class DiscoveredPlugin(val manifest: PluginManifest, val source: String)

/**
 * 插件加载器 SPI。见设计文档 9。
 *
 * 轨道 A（声明式）/ B（沙箱脚本）/ C（原生二进制）都只是这个接口的实现，
 * 内核不关心它们是哪一种。
 *
 * 它只负责「怎么跑」，不负责「有哪些」 后者是 [PluginDiscovery] 的活。
 * 这两件事分开是刻意的：发现必须与平台无关，装载才可以挑平台。
 *
 * 关键约定：如果 [platforms] 不含当前平台，内核会把插件置为 UNAVAILABLE，
 * **不报错、不崩溃、不阻塞启动**  这是跨端一致性的落点。
 */
interface PluginLoader {

    /** 对应 manifest 里的 loader 字段，例如 builtin / dsl / zipline / shadow。 */
    val scheme: String

    /** 本 loader 支持的平台。 */
    val platforms: Set<Platform>

    /** 把 manifest 变成可运行的 Plugin 实例。允许抛异常，内核会兜住。 */
    suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin
}