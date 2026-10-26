package dev.trellis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.slot.SlotAction
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.slot.TemplateSlot
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.TextSlot
import kotlinx.coroutines.launch

/**
 * 布局里的一格：哪个插件的卡片，占几栏（1..12）。
 *
 * 为什么按插件而不是按贡献：用户和 agent 说的是「哪张卡片」，不是贡献 id；
 * 一个插件在一个点上通常就一张卡片。
 *
 * span 只在同一行有多格时才有意义：一行里 8 + 4 就是 8 栏、4 栏；
 * 只写 id 不写 span 时按等宽处理。
 */
data class SlotCell(val pluginId: PluginId, val span: Int = 1)

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
    /**
     * 怎么摆。外层是行，内层是同一行里的插件 id。
     *
     * 空 = 老样子，一张一张往下叠。布局只是**偏好**：没被点名的卡片
     * 不会消失，它们各自一行排在后面。界面上「看不见」不该成为排版的结果。
     */
    layout: List<List<SlotCell>> = emptyList(),
) {
    val contributions = registry.contributionsAt(point)
    if (contributions.isEmpty()) return

    val rows = groupIntoRows(contributions, layout)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            if (row.size == 1 && row[0].span <= 1 && row[0].contributions.size == 1) {
                val contribution = row[0].contributions[0]
                key(contribution.owner.value + "/" + contribution.id) {
                    SlotCard(registry, contribution.value as SlotContribution, refreshKey, onAction, contribution.owner)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (cell in row) {
                        // 宽度按 span 分：8 栏 + 4 栏 = 2:1，不要求加起来正好 12。
                        Box(Modifier.weight(cell.span.coerceIn(1, 12).toFloat())) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (contribution in cell.contributions) {
                                    key(contribution.owner.value + "/" + contribution.id) {
                                        SlotCard(
                                            registry,
                                            contribution.value as SlotContribution,
                                            refreshKey,
                                            onAction,
                                            contribution.owner,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 一行里的一格：占几栏 + 这一格里的卡片（通常一张）。 */
private data class RowCell(val span: Int, val contributions: List<Contribution>)

/**
 * 把贡献排成行。
 *
 * 布局里点名的行先占位；没被点名的卡片各自一行排在后面。
 * 为什么不让布局决定「显示/隐藏」：藏卡片用户看不出来，坏了也查不到。
 */
private fun groupIntoRows(
    contributions: List<Contribution>,
    layout: List<List<SlotCell>>,
): List<List<RowCell>> {
    if (layout.isEmpty()) return contributions.map { listOf(RowCell(1, listOf(it))) }

    val used = mutableSetOf<String>()
    val rows = mutableListOf<List<RowCell>>()
    for (row in layout) {
        val cells = mutableListOf<RowCell>()
        for (cell in row) {
            val matched = contributions.filter { contribution ->
                val key = contribution.owner.value + "/" + contribution.id
                contribution.owner == cell.pluginId && key !in used
            }
            matched.forEach { used += it.owner.value + "/" + it.id }
            if (matched.isNotEmpty()) cells += RowCell(cell.span.coerceIn(1, 12), matched)
        }
        if (cells.isNotEmpty()) rows += cells
    }
    for (contribution in contributions) {
        val key = contribution.owner.value + "/" + contribution.id
        if (key !in used) rows += listOf(RowCell(1, listOf(contribution)))
    }
    return rows
}

@Composable
private fun SlotCard(
    registry: ExtensionRegistry,
    contribution: SlotContribution,
    refreshKey: Any?,
    onAction: (suspend (PluginId?, SlotAction) -> ActionOutcome)?,
    owner: PluginId?,
) {
    // 单个插件可以有自己的卡片样式；没设过就回到全局样式。
    val style = LocalPluginCardStyle.current(owner) ?: LocalCardStyle.current
    val shape = RoundedCornerShape(style.radius)
    val shadowColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    Box(
        modifier = Modifier.fillMaxWidth().then(
            // 硬阴影要在布局里留出右下角那 4dp，否则会画到邻居上面去。
            if (style.hardShadow) Modifier.padding(end = 4.dp, bottom = 4.dp) else Modifier,
        ),
    ) {
        if (style.hardShadow) {
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRoundRect(
                        color = shadowColor,
                        topLeft = Offset(4.dp.toPx(), 4.dp.toPx()),
                        size = Size(size.width, size.height),
                        cornerRadius = CornerRadius(style.radius.toPx()),
                    )
                },
            )
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                // 每张插件卡片由宿主画边框和圆角：卡片之间的边界是宿主的事，
                // 插件只能决定里面写什么。用户要「每张卡有个圆角边界」时，
                // 这一步就已经给他了，不需要为此新造插件。
                // 具体样式可以经由界面偏好调（AppTheme 提供 LocalCardStyle）——
                // 调的是宿主，不是插件契约。
                .then(
                    if (style.border) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    } else {
                        Modifier
                    },
                ),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = if (style.filled) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                when (contribution) {
                    is ComposeSlot -> contribution.content()
                    is TextSlot -> TextSlotView(contribution.title, contribution.body)
                    is TemplateSlot -> TemplateSlotView(registry, contribution, refreshKey, onAction)
                    is DynamicSlot -> DynamicSlotView(contribution, refreshKey, onAction, owner)
                    else -> Text("此卡片类型当前未接入界面", style = MaterialTheme.typography.bodySmall)
                }
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
