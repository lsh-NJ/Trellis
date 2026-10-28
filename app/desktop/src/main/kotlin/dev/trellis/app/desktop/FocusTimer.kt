package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 计时器的模型层（不碰 Compose，方便单测）。
 *
 * 两种模式：
 * - 倒计时：番茄钟。有总时长、有进度条；到 0 停住并提示「时间到」。
 * - 正计时：只往上数，没有总时长也**没有进度条**（用户定的）。
 */
enum class FocusMode { Countdown, Stopwatch }

/** 秒 → "mm:ss"；超过一小时给 "h:mm:ss"。 */
internal fun formatClock(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val rest = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, rest)
    } else {
        "%02d:%02d".format(minutes, rest)
    }
}

/** 倒计时进度 0..1；正计时永远返回 0（界面据此不画进度条）。 */
internal fun timerProgress(elapsedSeconds: Int, totalSeconds: Int, mode: FocusMode): Float {
    if (mode != FocusMode.Countdown || totalSeconds <= 0) return 0f
    return (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f)
}

internal fun remainingSeconds(elapsedSeconds: Int, totalSeconds: Int): Int =
    (totalSeconds - elapsedSeconds).coerceAtLeast(0)

/** 番茄钟可设 5–180 分钟（步进 5）。 */
internal fun clampTimerMinutes(minutes: Int): Int = minutes.coerceIn(5, 180)

internal data class TimerAdvance(val elapsedSeconds: Int, val finished: Boolean)

/** 走一秒（或 n 秒）：倒计时封顶在 total 并标记 finished，正计时一直加。 */
internal fun advanceTimer(elapsedSeconds: Int, seconds: Int, totalSeconds: Int, mode: FocusMode): TimerAdvance {
    val next = elapsedSeconds + seconds.coerceAtLeast(0)
    return if (mode == FocusMode.Countdown && totalSeconds > 0 && next >= totalSeconds) {
        TimerAdvance(totalSeconds, true)
    } else {
        TimerAdvance(next, false)
    }
}

/** 计时结束后折算成「投入分钟」：不足一分钟按一分钟算，免得白干。 */
internal fun minutesToLog(elapsedSeconds: Int): Int =
    if (elapsedSeconds <= 0) 0 else ((elapsedSeconds + 59) / 60).coerceAtLeast(1)

@Composable
internal fun FocusTimerWindow(runtime: AppRuntime) {
    val mode = runtime.timerMode
    val started = runtime.timerStarted
    val finished = runtime.timerFinished
    val total = runtime.timerTotalSeconds
    val elapsed = runtime.timerElapsedSeconds
    val shown = when {
        finished -> 0
        mode == FocusMode.Countdown && started -> remainingSeconds(elapsed, total)
        else -> elapsed
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = rememberCoroutineScope()

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = runtime.timerTaskTitle.ifBlank { "计时" },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 大数字：等宽字体，避免每一秒宽度跳动。
            Text(
                text = formatClock(shown),
                fontSize = 64.sp,
                lineHeight = 68.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                color = if (finished) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )

            if (mode == FocusMode.Countdown && started) {
                LinearProgressIndicator(
                    progress = { timerProgress(elapsed, total, mode) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Text(
                    if (finished) "时间到" else "倒计时 · 共 ${clampTimerMinutes(total / 60)} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (finished) MaterialTheme.colorScheme.primary else muted,
                )
            } else if (mode == FocusMode.Stopwatch) {
                Text(
                    if (finished) "已结束" else "正计时 · 没有进度条，到停为止",
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                )
            }

            if (!started) {
                ModeRow(runtime, mode)
                if (mode == FocusMode.Countdown) {
                    MinutesRow(runtime)
                }
                Spacer(Modifier.height(2.dp))
                ChromeButton("开始", onClick = { runtime.startTimer() }, modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { scope.launch { runtime.logManualMinutes(25) } }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "不计时，直接记 25 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChromeButton(
                        if (runtime.timerRunning) "暂停" else if (finished) "再来一轮" else "继续",
                        onClick = {
                            if (finished) runtime.restartTimer() else runtime.toggleTimerPause()
                        },
                        modifier = Modifier.weight(1f),
                        filled = true,
                    )
                    if (mode == FocusMode.Countdown) {
                        ChromeButton("+5 分钟", onClick = { runtime.extendTimer(5) }, modifier = Modifier.weight(1f), filled = false)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChromeButton(
                        label = "结束并记入",
                        onClick = { scope.launch { runtime.stopTimerAndLog() } },
                        modifier = Modifier.weight(1f),
                        filled = true,
                    )
                    ChromeButton("关闭", onClick = { runtime.closeTimer() }, modifier = Modifier.weight(1f), filled = false)
                }
            }
        }
    }
}

@Composable
private fun ModeRow(runtime: AppRuntime, mode: FocusMode) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip("倒计时", selected = mode == FocusMode.Countdown) { runtime.chooseTimerMode(FocusMode.Countdown) }
        ModeChip("正计时", selected = mode == FocusMode.Stopwatch) { runtime.chooseTimerMode(FocusMode.Stopwatch) }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MinutesRow(runtime: AppRuntime) {
    val minutes = runtime.timerMinutes
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SmallStep("−") { runtime.bumpTimerMinutes(-5) }
        Text(
            "番茄钟 $minutes 分钟",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(112.dp),
            textAlign = TextAlign.Center,
        )
        SmallStep("+") { runtime.bumpTimerMinutes(5) }
        Spacer(Modifier.weight(1f))
        for (preset in listOf(15, 25, 45)) {
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .clickable { runtime.chooseTimerMinutes(preset) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$preset′", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SmallStep(glyph: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyMedium)
    }
}
