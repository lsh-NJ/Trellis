package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import dev.trellis.ui.LocalExtraColors
import kotlinx.coroutines.delay

/**
 * 工作流清单的常驻视图。
 *
 * 两种形态、同一份数据：
 * - [PlanSidebar]：右侧 296dp 常驻栏。**默认形态** —— 用户等待时眼睛在对话线程上，
 *   清单必须和线程同屏，且线程滚动它不动；
 * - [PlanDock]：关掉右栏后贴在输入框上方的常驻条，一行摘要 + 展开按钮。
 *   「常驻」是硬要求：关闭只是换个形态，不是让清单消失。
 *
 * 状态形状优先于颜色（勾 / 叉 / 脉冲点 / 虚线框），颜色只做辅助 ——
 * 只靠颜色区分状态，色盲用户读不出进度。
 */
@Composable
fun PlanSidebar(
    line: ChatLine,
    running: Boolean,
    diffCount: Int,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onDiff: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = line.plan
    val status = planStatus(plan, running)
    var collapsed by remember(line.id, status) { mutableStateOf(false) }

    // 全部结束 1.5s 后自动收成摘要：收起≠消失，点标题还能展开回看每一步。
    LaunchedEffect(line.id, status) {
        if (status == PlanStatus.Done) {
            delay(1500)
            collapsed = true
        }
    }

    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current

    Column(
        modifier
            .width(296.dp)
            .fillMaxHeight()
            .padding(top = 8.dp, bottom = 12.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .background(scheme.surface.copy(alpha = 0.5f)),
    ) {
        // 标题行：执行清单 2/4 + 状态 + 关闭
        Row(
            Modifier.fillMaxWidth().height(40.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "执行清单",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.8.sp,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                settledCount(plan).toString() + "/" + plan.size,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (status == PlanStatus.Done) extra.accentText else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (status == PlanStatus.Running) {
                Text(
                    "进行中",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = extra.warning,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (collapsed) {
                TextButton(onClick = { collapsed = false }) {
                    Text("展开", style = MaterialTheme.typography.labelSmall)
                }
            }
            Box(
                Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("×", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }

        if (collapsed) {
            PlanSummaryRow(line, status, Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        } else {
            // 进度条：完成数 / 总数，靛色
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(extra.dataTrack),
            ) {
                val fraction = if (plan.isEmpty()) 0f else settledCount(plan).toFloat() / plan.size
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(extra.data),
                )
            }

            Column(Modifier.padding(top = 10.dp, bottom = 4.dp)) {
                plan.forEachIndexed { index, step ->
                    PlanStepRow(index, step, plan.size == index + 1)
                }
            }
        }

        Spacer(Modifier.weight(1f))
        PlanFooter(
            status = status,
            running = running,
            diffCount = diffCount,
            undoable = plan.any { it.undoable || it.state == PlanStepState.Done },
            onUndo = onUndo,
            onDiff = onDiff,
            onStop = onStop,
        )
    }
}

/** 右栏关掉后的常驻条：一行摘要，任何时刻都能展开回去。 */
@Composable
fun PlanDock(
    line: ChatLine,
    running: Boolean,
    onOpen: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val plan = line.plan
    val status = planStatus(plan, running)
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val current = plan.firstOrNull { it.state == PlanStepState.Running }
        ?: plan.firstOrNull { it.state == PlanStepState.Pending }

    Row(
        modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .background(scheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlanStepGlyph(if (current?.state == PlanStepState.Running) PlanStepState.Running else status.glyph())
        Spacer(Modifier.width(8.dp))
        Text(
            "执行清单 " + settledCount(plan) + "/" + plan.size,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            current?.text ?: status.label(),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(1f))
        if (running) {
            TextButton(onClick = onStop) {
                Text("停止", style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onOpen) {
            Text("展开", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlanSummaryRow(line: ChatLine, status: PlanStatus, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val plan = line.plan
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlanStepGlyph(PlanStepState.Done)
            Spacer(Modifier.width(8.dp))
            Text(
                "已完成 " + plan.size + " 步",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            val cost = plan.sumOf { it.ms }
            if (cost > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "· " + durationText(cost),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "耗时 " + durationText(plan.sumOf { it.ms }) +
                " · 改动 " + plan.count { it.impact.isNotBlank() } + " 处" +
                " · " + (if (status == PlanStatus.Failed) "有失败步骤" else "可撤销"),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = extra.dataMuted,
        )
    }
}

@Composable
private fun PlanStepRow(index: Int, step: PlanStep, last: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val active = step.state == PlanStepState.Running
    val rowBg = if (active) extra.warningContainer else Color.Transparent
    val titleColor = when (step.state) {
        PlanStepState.Pending -> scheme.onSurfaceVariant.copy(alpha = 0.85f)
        PlanStepState.Running -> scheme.onSurface
        PlanStepState.Done -> scheme.onSurfaceVariant.copy(alpha = 0.75f)
        PlanStepState.Failed -> extra.danger
        PlanStepState.Skipped -> scheme.onSurfaceVariant.copy(alpha = 0.55f)
    }

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .background(rowBg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.CenterStart) {
                PlanStepGlyph(step.state)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (index + 1).toString().padStart(2, '0'),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        step.text,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.5.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                            textDecoration = if (step.state == PlanStepState.Done ||
                                step.state == PlanStepState.Skipped
                            ) {
                                TextDecoration.LineThrough
                            } else {
                                null
                            },
                        ),
                        color = titleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    stepMeta(step, active),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    fontFamily = FontFamily.Monospace,
                    color = if (step.state == PlanStepState.Failed) {
                        extra.danger
                    } else {
                        scheme.onSurfaceVariant.copy(alpha = 0.85f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 当前步展开：在做什么 / 会改动什么 / 能不能撤销 —— 三行，都是用户此刻最想问的。
        AnimatedVisibility(visible = active) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 42.dp, end = 12.dp)
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(extra.dataTrack.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "这一步在做什么" + (if (step.tool.isNotBlank()) "：" + step.tool else ""),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    "会改动：" + (step.impact.ifBlank { "未知（等它做完再看）" }),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    if (step.undoable) "✓ 可撤销，随时能回退" else "· 提交后生效，不能撤销",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = if (step.undoable) extra.accentText else scheme.onSurfaceVariant,
                )
            }
        }

        if (!last) {
            HorizontalDivider(
                Modifier.padding(start = 42.dp, end = 12.dp),
                thickness = 1.dp,
                color = scheme.outlineVariant.copy(alpha = 0.45f),
            )
        }
    }
}

@Composable
private fun PlanFooter(
    status: PlanStatus,
    running: Boolean,
    diffCount: Int,
    undoable: Boolean,
    onUndo: () -> Unit,
    onDiff: () -> Unit,
    onStop: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    Column {
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                running -> {
                    TextButton(onClick = onStop) {
                        Text("中止并停止本轮", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "预计剩余 " + if (status == PlanStatus.Running) "几秒" else "",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = extra.warning,
                    )
                }
                diffCount > 0 -> {
                    TextButton(onClick = onUndo, enabled = undoable) {
                        Text("撤销这次改动", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = onDiff) {
                        Text("看差异", style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> {
                    Text(
                        if (status == PlanStatus.Failed) "本轮有失败步骤" else "本轮没有改动",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (status == PlanStatus.Failed) extra.danger else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanStepGlyph(state: PlanStepState) {
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        when (state) {
            PlanStepState.Pending -> Box(
                Modifier
                    .size(14.dp)
                    .border(1.dp, scheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp)),
            )
            PlanStepState.Running -> {
                // 脉冲点而不是转圈：清单是「进度」，转圈是「等待」；转圈看久了会以为卡住。
                val pulse = rememberInfiniteTransition(label = "plan-pulse")
                val alpha by pulse.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(700, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "plan-pulse-alpha",
                )
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(extra.warning.copy(alpha = alpha)),
                )
            }
            PlanStepState.Done -> Canvas(Modifier.size(14.dp)) {
                drawRoundRect(
                    color = extra.data,
                    size = Size(size.width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.28f),
                )
                val s = size.minDimension
                val stroke = s * 0.16f
                drawLine(
                    color = Color.White,
                    start = Offset(s * 0.26f, s * 0.52f),
                    end = Offset(s * 0.44f, s * 0.70f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(s * 0.44f, s * 0.70f),
                    end = Offset(s * 0.76f, s * 0.32f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            PlanStepState.Failed -> Canvas(Modifier.size(14.dp)) {
                drawRoundRect(
                    color = extra.danger,
                    size = Size(size.width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.28f),
                )
                val s = size.minDimension
                val stroke = s * 0.15f
                drawLine(
                    color = Color.White,
                    start = Offset(s * 0.32f, s * 0.32f),
                    end = Offset(s * 0.68f, s * 0.68f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(s * 0.68f, s * 0.32f),
                    end = Offset(s * 0.32f, s * 0.68f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            PlanStepState.Skipped -> Canvas(Modifier.size(14.dp)) {
                drawRoundRect(
                    color = scheme.onSurfaceVariant.copy(alpha = 0.5f),
                    size = Size(size.width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.28f),
                    style = Stroke(
                        width = 1.2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 2.5f)),
                    ),
                )
                drawLine(
                    color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    start = Offset(size.width * 0.3f, size.height * 0.5f),
                    end = Offset(size.width * 0.7f, size.height * 0.5f),
                    strokeWidth = 1.2f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private enum class PlanStatus { Waiting, Running, Done, Failed }

private fun PlanStatus.glyph(): PlanStepState = when (this) {
    PlanStatus.Waiting -> PlanStepState.Pending
    PlanStatus.Running -> PlanStepState.Running
    PlanStatus.Done -> PlanStepState.Done
    PlanStatus.Failed -> PlanStepState.Failed
}

private fun PlanStatus.label(): String = when (this) {
    PlanStatus.Waiting -> "等它开始"
    PlanStatus.Running -> "正在执行"
    PlanStatus.Done -> "已完成"
    PlanStatus.Failed -> "有失败步骤"
}

private fun settledCount(plan: List<PlanStep>): Int = plan.count {
    it.state == PlanStepState.Done || it.state == PlanStepState.Failed || it.state == PlanStepState.Skipped
}

private fun planStatus(plan: List<PlanStep>, running: Boolean): PlanStatus {
    if (plan.isEmpty()) return PlanStatus.Waiting
    val failed = plan.any { it.state == PlanStepState.Failed }
    val allSettled = plan.all {
        it.state == PlanStepState.Done || it.state == PlanStepState.Failed || it.state == PlanStepState.Skipped
    }
    return when {
        allSettled -> if (failed) PlanStatus.Failed else PlanStatus.Done
        running || plan.any { it.state == PlanStepState.Running } -> PlanStatus.Running
        else -> PlanStatus.Waiting
    }
}

private fun stepMeta(step: PlanStep, active: Boolean): String = when (step.state) {
    PlanStepState.Done -> "完成" + if (step.ms > 0) " · " + durationText(step.ms) else ""
    PlanStepState.Running -> "正在执行" + if (step.ms > 0) " · " + durationText(step.ms) else ""
    PlanStepState.Failed -> "失败" + if (step.note.isNotBlank()) " · " + step.note else ""
    PlanStepState.Skipped -> "跳过" + if (step.note.isNotBlank()) " · " + step.note else ""
    PlanStepState.Pending -> if (active) "开始" else "等待"
}

private fun durationText(ms: Long): String = when {
    ms <= 0 -> ""
    ms < 1000 -> String.format("%.2fs", ms / 1000.0)
    ms < 60_000 -> String.format("%.1fs", ms / 1000.0)
    else -> String.format("%.1fmin", ms / 60_000.0)
}
