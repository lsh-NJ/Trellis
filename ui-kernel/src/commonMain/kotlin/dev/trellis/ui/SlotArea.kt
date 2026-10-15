package dev.trellis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.slot.TextSlot

/**
 * 渲染一个挂载点上的全部贡献。对应设计文档 5.2  界面上预留的「插座」。
 *
 * ### 一个必须说清楚的限制：Compose 没有局部错误边界
 *
 * 在 [dev.trellis.kernel.slot.SlotHost]（控制台渲染）里，
 * 单张卡片出错只降级它自己，因为有 `try/catch` 兜着。
 *
 * **但在 Compose 里做不到**  编译器直接拒绝：
 *
 * ```
 * Try catch is not supported around composable function invocations.
 * ```
 *
 * 这不是可以绕过的小事，它直接决定了两类贡献的**信任级别**：
 *
 * | | [TextSlot] 这类数据型贡献 | [ComposeSlot] 这类代码型贡献 |
 * |---|---|---|
 * | 本质 | 只是数据 | 在你 UI 里跑任意代码 |
 * | 出错后果 | 顶多显示难看 | **整个窗口一起崩** |
 * | 能不能校验 | 能 | 不能 |
 * | 能让 Agent 生成吗 | 能（声明式插件走的就是这条） | 不能 |
 * | 谁可以用 | 任何人 | 只应该是第一方插件 |
 *
 * 换句话说：**「一张卡片崩了不影响别的卡片」这条保证，只在数据型贡献上成立。**
 * 把任意第三方代码注入你的 UI，本来就是不该做的事 
 * Compose 拒绝 try/catch，某种程度上是在替你守住这条线。
 *
 * 于是插件生态的正确形态是：第三方（含 Agent 生成的）只能提交**数据**，
 * 由宿主渲染；只有第一方插件才允许提交 composable。
 */
@Composable
fun SlotArea(
    registry: ExtensionRegistry,
    point: ExtensionPoint<SlotContribution>,
    modifier: Modifier = Modifier,
) {
    val contributions = registry.all(point)
    if (contributions.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (contribution in contributions) {
            key(contribution.id) {
                SlotCard(contribution)
            }
        }
    }
}

@Composable
private fun SlotCard(contribution: SlotContribution) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            when (contribution) {
                is ComposeSlot -> contribution.content()
                is TextSlot -> TextSlotView(contribution)
                else -> Text("此卡片类型当前未接入界面", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TextSlotView(slot: TextSlot) {
    if (slot.title.isNotEmpty()) {
        Text(slot.title, style = MaterialTheme.typography.titleSmall)
    }
    if (slot.body.isNotEmpty()) {
        Text(slot.body, style = MaterialTheme.typography.bodyMedium)
    }
}
