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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trellis.activity.Attribution
import dev.trellis.activity.AttributionStatus
import dev.trellis.activity.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 任务时间视图：把「窗口活动」翻译成「在哪个任务上花了多久」。
 *
 * 数据链路：计划页当天的 occurrence → `occurrence:<id>`（taskRef）→ 归因表里的时间区间
 * → 聚合器按区间求交 → 这里按任务汇总。视图只负责展示与写归因，不重新计算时间。
 *
 * 第一次进来只看得到「未归因」，点了「归到任务…」或跑一次 AI 分析之后才有数字。
 */

/** 任务时间视图的一行：当天的计划任务 + 已归因时长。 */
internal data class ActivityTaskRow(
    val taskRef: String,
    val title: String,
    val detail: String?,
    val plannedMinutes: Int?,
    val attributedMs: Long,
    val done: Boolean,
    val unknown: Boolean = false,
)

/** 任务时间视图的一次快照；[rows] 已按计划顺序 + 计划外任务排好。 */
internal data class ActivityTaskReport(
    val dayMs: Long,
    val rows: List<ActivityTaskRow>,
    val unattributed: List<Segment>,
    val proposals: List<Attribution>,
    val planTitle: String?,
    val hasPlan: Boolean,
)

@Composable
internal fun ActivityTaskView(
    runtime: AppRuntime,
    dayMs: Long,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var report by remember { mutableStateOf<ActivityTaskReport?>(null) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var assignSegment by remember { mutableStateOf<Segment?>(null) }

    val scope = rememberCoroutineScopeCompat()
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(dayMs, reload) {
        loading = true
        report = runtime.activityTaskReport(dayMs)
        loading = false
    }

    fun refresh() {
        reload++
        onChanged()
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            TaskHeader(
                planTitle = report?.planTitle,
                proposalCount = report?.proposals?.size ?: 0,
                busy = busy,
                message = message,
                onAi = {
                    scope.launch {
                        busy = true
                        message = runtime.activityProposeAttributions(dayMs)
                        busy = false
                        refresh()
                    }
                },
            )
        }

        val proposals = report?.proposals.orEmpty()
        if (proposals.isNotEmpty()) {
            item { TaskProposalsCard(runtime, proposals, report, onChanged = { refresh() }) }
        }

        item {
            TaskRowsCard(
                rows = report?.rows.orEmpty(),
                hasPlan = report?.hasPlan == true,
                loading = loading,
            )
        }

        item {
            UnattributedCard(
                segments = report?.unattributed.orEmpty(),
                loading = loading,
                onAssign = { assignSegment = it },
                onIgnore = { segment ->
                    scope.launch {
                        val problems = runtime.activityIgnoreRange(segment.fromMs, segment.toMs)
                        message = problems.firstOrNull()
                        refresh()
                    }
                },
            )
        }
    }

    assignSegment?.let { segment ->
        AssignTaskDialog(
            runtime = runtime,
            fromMs = segment.fromMs,
            toMs = segment.toMs,
            label = (segment.app ?: "（未知进程）") + " · " + taskClock(segment.fromMs) + "-" + taskClock(segment.toMs),
            onDismiss = { assignSegment = null },
            onSaved = {
                assignSegment = null
                refresh()
            },
        )
    }
}

@Composable
private fun TaskHeader(
    planTitle: String?,
    proposalCount: Int,
    busy: Boolean,
    message: String?,
    onAi: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("任务时间", style = MaterialTheme.typography.titleMedium)
            Text(
                (planTitle ?: "还没有计划") + " · AI 只分析没判断过的空档",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (proposalCount > 0) {
            Text(
                "$proposalCount 条 AI 建议待确认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        ChromeButton(
            label = if (busy) "分析中…" else "AI 分析未归因",
            onClick = onAi,
            enabled = !busy,
        )
    }
    message?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskProposalsCard(
    runtime: AppRuntime,
    proposals: List<Attribution>,
    report: ActivityTaskReport?,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScopeCompat()
    val titles = report?.rows?.associate { it.taskRef to it.title }.orEmpty()
    TaskCard(title = "AI 建议", subtitle = "确认后才计入任务时长；忽略的区间不会再次分析。") {
        proposals.forEach { proposal ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        titles[proposal.taskRef] ?: proposal.taskRef,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        taskClock(proposal.fromMs) + "-" + taskClock(proposal.toMs) +
                            " · " + taskDuration(proposal.durationMs) +
                            " · 置信度 " + (proposal.confidence * 100).toInt() + "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        runtime.activitySetAttributionStatus(proposal.id, AttributionStatus.APPLIED)
                        onChanged()
                    }
                }) { Text("接受") }
                TextButton(onClick = {
                    scope.launch {
                        runtime.activitySetAttributionStatus(proposal.id, AttributionStatus.REJECTED)
                        onChanged()
                    }
                }) { Text("忽略") }
            }
        }
    }
}

@Composable
private fun TaskRowsCard(rows: List<ActivityTaskRow>, hasPlan: Boolean, loading: Boolean) {
    TaskCard(
        title = "任务时长",
        subtitle = if (hasPlan) "归因时长来自窗口活动；计划分钟数来自当天的计划。" else "计划里今天没有任务，归因只会列出计划外的任务。",
    ) {
        if (loading) {
            Text("读取中…", style = MaterialTheme.typography.bodySmall)
            return@TaskCard
        }
        if (rows.isEmpty()) {
            Text("还没有任务。先去计划页排今天要做什么，再回来把窗口使用归到任务上。", style = MaterialTheme.typography.bodySmall)
            return@TaskCard
        }
        rows.forEach { row -> TaskRowView(row) }
    }
}

@Composable
private fun TaskRowView(row: ActivityTaskRow) {
    val plannedMs = (row.plannedMinutes ?: 0) * 60_000L
    val ratio = if (plannedMs > 0) (row.attributedMs.toFloat() / plannedMs).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.title + if (row.done) "  ✓" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                row.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                "归因 " + taskDuration(row.attributedMs) +
                    if (row.plannedMinutes != null) " / 计划 " + row.plannedMinutes + " 分钟" else "",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (ratio > 0f) {
                Box(
                    Modifier.fillMaxWidth(ratio).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

@Composable
private fun UnattributedCard(
    segments: List<Segment>,
    loading: Boolean,
    onAssign: (Segment) -> Unit,
    onIgnore: (Segment) -> Unit,
) {
    TaskCard(
        title = "未归因的窗口活动",
        subtitle = "这些片段还没对上任务。归到任务后会计入上面的时长；标成「无关」的以后不再出现。",
    ) {
        if (loading) {
            Text("读取中…", style = MaterialTheme.typography.bodySmall)
            return@TaskCard
        }
        if (segments.isEmpty()) {
            Text("没有待归因的片段。", style = MaterialTheme.typography.bodySmall)
            return@TaskCard
        }
        segments.take(60).forEach { segment ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (segment.app ?: UnknownTaskApp) + " · " + taskClock(segment.fromMs) + "-" + taskClock(segment.toMs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val title = segment.title?.takeIf { it.isNotBlank() }
                    Text(
                        listOfNotNull(segment.categoryPath, title).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text(taskDuration(segment.durationMs), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { onAssign(segment) }) { Text("归到任务") }
                TextButton(onClick = { onIgnore(segment) }) { Text("无关") }
            }
        }
    }
}

/**
 * 「归到任务…」对话框：列当天计划任务 + 与任务无关。
 *
 * 单独做成一个 composable，因为片段详情浮层和任务时间列表都要用它；任务清单自己读，
 * 调用方只要给时间区间。
 */
@Composable
internal fun AssignTaskDialog(
    runtime: AppRuntime,
    fromMs: Long,
    toMs: Long,
    label: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    var report by remember { mutableStateOf<ActivityTaskReport?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScopeCompat()

    LaunchedEffect(fromMs, toMs) {
        report = runtime.activityTaskReport((fromMs + toMs) / 2)
    }

    fun save(taskRef: String, source: String) {
        scope.launch {
            saving = true
            val problems = runtime.activityAddAttribution(fromMs, toMs, taskRef, source = source)
            saving = false
            if (problems.isEmpty()) {
                onSaved()
            } else {
                error = problems.joinToString("；")
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("归到任务") },
        text = {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val rows = report?.rows.orEmpty()
                if (rows.isEmpty()) {
                    Text("当天的计划里没有任务。", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.height(260.dp)) {
                        items(rows) { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable(enabled = !saving) { save(row.taskRef, dev.trellis.activity.AttributionSource.MANUAL) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.title, style = MaterialTheme.typography.bodyMedium)
                                    row.detail?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(taskDuration(row.attributedMs), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { save(dev.trellis.activity.NO_TASK_REF, dev.trellis.activity.AttributionSource.MANUAL) },
                enabled = !saving,
            ) { Text("与任务无关") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 任务时间视图的小卡片外壳；和活动页其它卡片同一套边框/底色。 */
@Composable
private fun TaskCard(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 1120.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        subtitle?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

private const val UnknownTaskApp = "（未知）"

private fun taskClock(ms: Long): String {
    val time = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return String.format("%02d:%02d", time.hour, time.minute)
}

private fun taskDuration(ms: Long): String {
    val minutes = (ms + 59_999L) / 60_000L
    return when {
        minutes <= 0L -> "0 分钟"
        minutes < 60L -> "$minutes 分钟"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}

/** Compose Desktop 的作用域取用；与 ActivityScreen 保持一致。 */
@Composable
private fun rememberCoroutineScopeCompat() = androidx.compose.runtime.rememberCoroutineScope()