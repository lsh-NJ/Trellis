package dev.trellis.ui

import androidx.compose.runtime.Composable
import dev.trellis.kernel.slot.SlotContribution

/**
 * 内容由 Compose 绘制的挂载点贡献。
 *
 * 注意**内核一行代码都不用改**：`SlotContribution` 契约只有 id 和 order，
 * 具体长什么样是渲染层的事。这就是 M2 时把「契约」和「绘制」分开的回报 
 * 当时只是为了避免过早引入 Compose，现在它变成了真实的可扩展点。
 *
 * ### 但它是「代码型」贡献，只应该给第一方插件用
 *
 * Compose 不支持在组合调用周围做 try/catch（编译器直接拒绝），
 * 所以这个 content 里抛出的任何异常都会把整个窗口带走。
 * 第三方插件（包括 Agent 生成的）应当被限制在 [dev.trellis.kernel.slot.TextSlot]
 * 这类**数据型**贡献里  详见 [SlotArea] 的说明。
 */
class ComposeSlot(
    override val id: String,
    override val order: Int = 100,
    val content: @Composable () -> Unit,
) : SlotContribution
