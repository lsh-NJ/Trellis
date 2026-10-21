package dev.trellis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.slot.SlotAction
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.slot.TemplateSlot
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.TextSlot
import kotlinx.coroutines.launch

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
    /**
     * 模板卡片什么时候重新求值。
     *
     * 由调用方给  内核不假装知道数据什么时候会变。
     * 现在传的是一个每秒加一的计数（应用在轮询），
     * 以后领域层能发变更通知了，这里换成那个 Flow 就行。
     */
    refreshKey: Any? = Unit,
    /**
     * 卡片上的按钮被按下去时走这里。
     *
     * 界面只管转发，**不自己判断能不能执行**  授权、审批、执行、留痕
     * 全在内核里。少了那条路，按钮就成了绕过审批门的一扇后门。
     */
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)? = null,
) {
    val contributions = registry.all(point)
    if (contributions.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (contribution in contributions) {
            key(contribution.id) {
                SlotCard(registry, contribution, refreshKey, onAction)
            }
        }
    }
}

@Composable
private fun SlotCard(
    registry: ExtensionRegistry,
    contribution: SlotContribution,
    refreshKey: Any?,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            when (contribution) {
                is ComposeSlot -> contribution.content()
                is TextSlot -> TextSlotView(contribution.title, contribution.body)
                is TemplateSlot -> TemplateSlotView(registry, contribution, refreshKey, onAction)
                is DynamicSlot -> DynamicSlotView(contribution, refreshKey, onAction)
                else -> Text("此卡片类型当前未接入界面", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 模板卡片：先求值，再画。
 *
 * 求值是挂起的（数据源可能要读存储），所以放进 [LaunchedEffect]；
 * 求值结果落在本地 state 里，取值失败的部分会变成 `--`，**不会让整张卡片消失**。
 */
@Composable
private fun TemplateSlotView(
    registry: ExtensionRegistry,
    slot: TemplateSlot,
    refreshKey: Any?,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    var title by remember(slot.id) { mutableStateOf(slot.title) }
    var body by remember(slot.id) { mutableStateOf(slot.body) }

    LaunchedEffect(slot.id, refreshKey) {
        // 每次都重新算一遍「今天」：用户可能跨过午夜，也可能改了系统时区
        val context = currentDataContext()
        title = registry.renderTemplate(slot.title, context)
        body = registry.renderTemplate(slot.body, context)
    }

    TextSlotView(title, body)

    if (slot.actions.isNotEmpty()) {
        SlotButtons(slot, onAction)
    }
}

/**
 * 卡片下面那排按钮。
 *
 * 点下去之后把内核回的那句话原地显示出来  「点了没反应」是最糟的反馈，
 * 用户会以为界面卡了，然后开始连点。
 */
@Composable
private fun SlotButtons(
    slot: TemplateSlot,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
) {
    val scope = rememberCoroutineScope()
    var feedback by remember(slot.id) { mutableStateOf("") }
    var running by remember(slot.id) { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (action in slot.actions) {
            OutlinedButton(
                enabled = onAction != null && !running,
                onClick = {
                    val handler = onAction ?: return@OutlinedButton
                    running = true
                    feedback = ""
                    scope.launch {
                        feedback = try {
                            handler(slot.owner, action).message
                        } catch (t: Throwable) {
                            t.message ?: "执行失败"
                        } finally {
                            running = false
                        }
                    }
                },
            ) {
                Text(action.label)
            }
        }
    }

    if (feedback.isNotEmpty()) {
        Text(feedback, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TextSlotView(title: String, body: String) {
    if (title.isNotEmpty()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
    if (body.isNotEmpty()) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
