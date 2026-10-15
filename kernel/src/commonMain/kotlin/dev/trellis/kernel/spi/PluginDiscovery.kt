package dev.trellis.kernel.spi

import dev.trellis.kernel.diag.KernelError

/**
 * 插件发现源。**与平台无关**。
 *
 * 为什么把它和 [PluginLoader] 拆开：设计文档要求「同一份插件目录在手机和桌面上
 * 都能被识别，只是能力不同」。如果发现动作绑在 loader 上，那么在某平台上没有
 * 对应 loader 的插件会连「被发现」的机会都没有，UNAVAILABLE 这个状态就永远
 * 不可能出现，跨端一致性也就无从谈起。
 *
 * 所以职责被切成两段：
 *  - [PluginDiscovery]：这个平台上有哪些插件？（读 manifest.json，不执行任何代码）
 *  - [PluginLoader]：这个插件的代码该怎么跑？（可以跑不了 -> UNAVAILABLE）
 */
interface PluginDiscovery {

    /** 用于诊断信息，例如 dsl-directory / builtin。 */
    val name: String

    /** 扫描 root，返回成功发现的插件 **以及** 逐个失败的原因。 */
    suspend fun discover(root: String): DiscoveryResult
}

/**
 * 一轮扫描的结果。
 *
 * 刻意不做成「要么全成、要么抛异常」：目录里有一个 manifest 写坏了，
 * 不该让另外九个正常插件一起消失。这与 [dev.trellis.kernel.registry.ResolveResult]
 * 是同一个教训  别把「部分成功」硬塞进二值结构里。
 */
data class DiscoveryResult(
    val plugins: List<DiscoveredPlugin> = emptyList(),
    val errors: List<KernelError> = emptyList(),
)