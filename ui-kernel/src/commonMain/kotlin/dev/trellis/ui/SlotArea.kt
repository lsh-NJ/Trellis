package dev.trellis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import dev.trellis.kernel.slot.SlotBadge
import dev.trellis.kernel.slot.SlotProgress
import dev.trellis.kernel.slot.SlotStat
import dev.trellis.kernel.slot.SlotTextSize
import dev.trellis.kernel.slot.SlotTone
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
    var card by remember(slot.id) {
        mutableStateOf(
            RenderedCard(
                title = slot.title,
                body = slot.body,
                stat = slot.stat,
                progress = slot.progress,
                progressValue = 0f,
                progressMax = 1f,
                badges = slot.badges,
            ),
        )
    }

    LaunchedEffect(slot.id, refreshKey) {
        // 每次都重新算一遍「今天」：用户可能跨过午夜，也可能改了系统时区
        val context = currentDataContext()
        suspend fun render(text: String): String = registry.renderTemplate(text, context)
        val stat = slot.stat?.let { SlotStat(render(it.value), render(it.unit), render(it.label), it.tone) }
        val progress = slot.progress?.let { SlotProgress(render(it.value), render(it.max), render(it.label)) }
        val rawMax = progress?.max?.trim()?.toFloatOrNull() ?: 1f
        card = RenderedCard(
            title = render(slot.title),
            body = render(slot.body),
            stat = stat,
            progress = progress,
            progressValue = progress?.value?.trim()?.toFloatOrNull() ?: 0f,
            progressMax = if (rawMax <= 0f) 1f else rawMax,
            badges = slot.badges.map { SlotBadge(render(it.text), it.tone) },
        )
    }

    SlotCardBody(slot, card)

    if (slot.actions.isNotEmpty()) {
        SlotButtons(slot, onAction)
    }
}

/** 渲染一次之后的卡片内容：模板已经全部变成了字符串。 */
private data class RenderedCard(
    val title: String,
    val body: String,
    val stat: SlotStat?,
    val progress: SlotProgress?,
    val progressValue: Float,
    val progressMax: Float,
    val badges: List<SlotBadge>,
)

@Composable
private fun SlotCardBody(slot: TemplateSlot, card: RenderedCard) {
    if (card.title.isNotEmpty()) {
        Text(
            card.title,
            style = when (slot.titleSize) {
                SlotTextSize.SMALL -> MaterialTheme.typography.titleSmall
                SlotTextSize.LARGE -> MaterialTheme.typography.titleLarge
                SlotTextSize.NORMAL -> MaterialTheme.typography.titleMedium
            },
            color = toneColor(slot.titleTone),
        )
    }

    card.stat?.let { stat ->
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                stat.value,
                style = MaterialTheme.typography.headlineSmall,
                color = toneColor(stat.tone),
            )
            if (stat.unit.isNotEmpty()) {
                Text(
                    " " + stat.unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stat.label.isNotEmpty()) {
                Text(
                    "  " + stat.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    card.progress?.let { progress ->
        LinearProgressIndicator(
            progress = { (card.progressValue / card.progressMax).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        if (progress.label.isNotEmpty()) {
            Text(
                progress.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (card.body.isNotEmpty()) {
        Text(
            card.body,
            style = MaterialTheme.typography.bodyMedium,
            color = toneColor(slot.bodyTone),
        )
    }

    if (card.badges.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (badge in card.badges) {
                BadgeView(badge)
            }
        }
    }
}

/** 一个小标签。底色是语气色的淡化版，所以换主题时不会撞色。 */
@Composable
private fun BadgeView(badge: SlotBadge) {
    val color = toneColor(badge.tone)
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(badge.text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * 语气 -> 颜色。
 *
 * 从主题里取，所以深色模式自动跟着变。
 * 成功色 M3 没有对应语义槽，只能自己定一个：这是宿主该操心的事，不是插件的。
 */
@Composable
private fun toneColor(tone: SlotTone): Color = when (tone) {
    SlotTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    SlotTone.ACCENT -> MaterialTheme.colorScheme.primary
    SlotTone.DANGER -> MaterialTheme.colorScheme.error
    SlotTone.SUCCESS -> Color(0xFF2E7D32)
    SlotTone.DEFAULT -> MaterialTheme.colorScheme.onSurface
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
                enabled = onAction != null && !running && action.disabledReason.isEmpty(),
                colors = when (action.tone) {
                    SlotTone.DANGER -> ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                    SlotTone.ACCENT -> ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    )
                    SlotTone.MUTED -> ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SlotTone.SUCCESS -> ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFF2E7D32),
                    )
                    SlotTone.DEFAULT -> ButtonDefaults.outlinedButtonColors()
                },
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

    // 按不了就说为什么。只把按钮变灰，用户只会以为程序坏了。
    for (action in slot.actions) {
        if (action.disabledReason.isNotEmpty()) {
            Text(
                action.label + "：" + action.disabledReason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
