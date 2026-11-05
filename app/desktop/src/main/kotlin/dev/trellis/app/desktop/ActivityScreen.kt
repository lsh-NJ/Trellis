package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.activity.AppSlice
import dev.trellis.activity.CategorySlice
import dev.trellis.activity.ClassificationExplanation
import dev.trellis.activity.DaySummary
import dev.trellis.activity.RangeSummary
import dev.trellis.activity.Segment
import dev.trellis.ui.LocalCardStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 活动页：ActivityWatch 原始记录按分类整理后，回答「今天/这段时间花在哪了」。
 *
 * 页面只摆数据：分类、颜色、时长都是 `:features:activity` 的 ActivityAggregator 算好的，
 * 这里不做规则匹配，也不重新累计数字。
 *
 * 隐私：默认只显示「进程名 + 分类」；窗口标题、标签页 URL 收进点开后的详情浮层。
 * 未分类记录不占主视线，收在页面底部的折叠块里。
 */
@Composable
internal fun ActivityScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(ActivityMode.Day) }
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    var rangeStart by remember { mutableStateOf(LocalDate.now().minusDays(6)) }
    var rangeEnd by remember { mutableStateOf(LocalDate.now()) }

    var daySummary by remember { mutableStateOf<DaySummary?>(null) }
    var daySegments by remember { mutableStateOf<List<Segment>>(emptyList()) }
    var rangeSummary by remember { mutableStateOf<RangeSummary?>(null) }
    var loading by remember { mutableStateOf(false) }

    // 同步成功一次就加一，让下面的 LaunchedEffect 重新取数；同步失败只显示一句话，不清空已有画面。
    var reloadTick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Segment?>(null) }
    var classify by remember { mutableStateOf<Segment?>(null) }

    // 饼图里是否隐藏「未追踪 / 未分类」：日视图和范围总览共用，切换视图时保持用户选择。
    var hideUntracked by remember { mutableStateOf(false) }

    // 进页面才开库，然后按 settings.json 的间隔轮询；离开页面时作用域取消，后台不再跑。
    LaunchedEffect(Unit) {
        runtime.ensureActivityEngine()
        while (true) {
            runtime.syncActivity()
            message = runtime.activityMessage
            reloadTick++
            delay(runtime.activitySyncIntervalMs)
        }
    }

    LaunchedEffect(mode, selectedDay, reloadTick) {
        if (mode != ActivityMode.Day) return@LaunchedEffect
        loading = true
        daySummary = runtime.activityDay(noonOf(selectedDay))
        daySegments = runtime.activitySegments(noonOf(selectedDay))
        loading = false
    }

    LaunchedEffect(mode, rangeStart, rangeEnd, reloadTick) {
        if (mode != ActivityMode.Range) return@LaunchedEffect
        loading = true
        rangeSummary = runtime.activityRange(noonOf(rangeStart), noonOf(rangeEnd))
        loading = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 1120.dp)
            .padding(horizontal = 4.dp),
    ) {
        ActivityHeader(
            runtime = runtime,
            mode = mode,
            onMode = { mode = it },
            selectedDay = selectedDay,
            onDay = { selectedDay = it },
            rangeStart = rangeStart,
            rangeEnd = rangeEnd,
            onPickStart = { showStartPicker = true },
            onPickEnd = { showEndPicker = true },
            onQuickRange = { days ->
                rangeEnd = LocalDate.now()
                rangeStart = rangeEnd.minusDays(days)
            },
            message = message,
            onSync = {
                scope.launch {
                    runtime.syncActivity()
                    message = runtime.activityMessage
                    reloadTick++
                }
            },
        )

        Spacer(Modifier.height(16.dp))

        when (mode) {
            ActivityMode.Day -> DayView(
                summary = daySummary,
                segments = daySegments,
                loading = loading,
                hideUntracked = hideUntracked,
                onHideUntrackedChange = { hideUntracked = it },
                onOpenDetail = { detail = it },
            )

            ActivityMode.Range -> RangeView(
                summary = rangeSummary,
                loading = loading,
                hideUntracked = hideUntracked,
                onHideUntrackedChange = { hideUntracked = it },
            )

            ActivityMode.Rules -> ActivityRulesView(
                runtime = runtime,
                onChanged = { reloadTick++ },
            )
        }
    }

    if (showStartPicker) {
        ActivityDatePicker(
            initial = rangeStart,
            onDismiss = { showStartPicker = false },
            onPick = { picked ->
                rangeStart = picked
                if (rangeEnd < rangeStart) rangeEnd = rangeStart
                showStartPicker = false
            },
        )
    }

    if (showEndPicker) {
        ActivityDatePicker(
            initial = rangeEnd,
            onDismiss = { showEndPicker = false },
            onPick = { picked ->
                rangeEnd = picked
                if (rangeStart > rangeEnd) rangeStart = rangeEnd
                showEndPicker = false
            },
        )
    }

    detail?.let { segment ->
        SegmentDetailDialog(
            runtime = runtime,
            segment = segment,
            onDismiss = { detail = null },
            onClassify = {
                classify = segment
                detail = null
            },
        )
    }

    classify?.let { segment ->
        QuickClassifyDialog(
            runtime = runtime,
            segment = segment,
            onDismiss = { classify = null },
            onSaved = {
                classify = null
                reloadTick++
            },
        )
    }
}

private enum class ActivityMode { Day, Range, Rules }

/** 页头：标题 + 日/范围切换 + 日期导航 + 同步状态。 */
@Composable
private fun ActivityHeader(
    runtime: AppRuntime,
    mode: ActivityMode,
    onMode: (ActivityMode) -> Unit,
    selectedDay: LocalDate,
    onDay: (LocalDate) -> Unit,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    onPickStart: () -> Unit,
    onPickEnd: () -> Unit,
    onQuickRange: (Long) -> Unit,
    message: String?,
    onSync: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChromeButton(
                    label = "日视图",
                    onClick = { onMode(ActivityMode.Day) },
                    filled = mode == ActivityMode.Day,
                )
                ChromeButton(
                    label = "范围总览",
                    onClick = { onMode(ActivityMode.Range) },
                    filled = mode == ActivityMode.Range,
                )
                ChromeButton(
                    label = "分类规则",
                    onClick = { onMode(ActivityMode.Rules) },
                    filled = mode == ActivityMode.Rules,
                )
                ChromeButton(label = "同步", onClick = onSync, filled = false)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (mode == ActivityMode.Day) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChromeButton(label = "‹", onClick = { onDay(selectedDay.minusDays(1)) }, filled = false)
                Text(
                    dayLabel(selectedDay),
                    style = MaterialTheme.typography.titleMedium,
                )
                ChromeButton(label = "›", onClick = { onDay(selectedDay.plusDays(1)) }, filled = false)
                ChromeButton(
                    label = "今天",
                    onClick = { onDay(LocalDate.now()) },
                    filled = false,
                    enabled = selectedDay != LocalDate.now(),
                )
                Text(
                    syncLabel(runtime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (mode == ActivityMode.Range) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChromeButton(label = dayShortLabel(rangeStart), onClick = onPickStart, filled = false)
                Text("到", style = MaterialTheme.typography.labelSmall)
                ChromeButton(label = dayShortLabel(rangeEnd), onClick = onPickEnd, filled = false)
                Spacer(Modifier.width(4.dp))
                ChromeButton(label = "最近 7 天", onClick = { onQuickRange(6) }, filled = false)
                ChromeButton(label = "最近 30 天", onClick = { onQuickRange(29) }, filled = false)
                Text(
                    syncLabel(runtime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                message!!,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 日视图：统计条 → 时间轴（全天概览 + 分钟网格）→ 分类占比 → 分类树 → 应用排行 → 未分类折叠块。 */
@Composable
private fun DayView(
    summary: DaySummary?,
    segments: List<Segment>,
    loading: Boolean,
    hideUntracked: Boolean,
    onHideUntrackedChange: (Boolean) -> Unit,
    onOpenDetail: (Segment) -> Unit,
) {
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var selectedCategoryPath by remember { mutableStateOf<List<String>?>(null) }
    var uncategorizedOpen by remember { mutableStateOf(false) }

    if (summary == null) {
        Text(
            if (loading) "正在读取活动数据…" else "今天还没有活动数据（先确认 ActivityWatch 在运行）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val selectedSlice = summary.categories.firstOrNull { it.categoryId == selectedCategoryId }
    val appRows = remember(segments, selectedCategoryPath) {
        appRowsFrom(segments, selectedCategoryPath)
    }
    val uncategorized = remember(segments) {
        segments.filter { it.categoryId == ActivityCategoryUncategorized }.sortedByDescending { it.durationMs }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ActivityCard {
                Text("今天", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    ActivityStat("活跃", formatDuration(summary.activeMs))
                    ActivityStat("AFK", formatDuration(summary.afkMs))
                    ActivityStat("观察窗口", formatDuration(summary.observedMs))
                    ActivityStat("其他", formatDuration((summary.observedMs - summary.activeMs - summary.afkMs).coerceAtLeast(0)))
                    ActivityStat("片段", summary.segmentCount.toString() + " 段")
                }
            }
        }

        item {
            TimelineCard(summary = summary, segments = segments, onOpenDetail = onOpenDetail)
        }

        item {
            CategoryPieCard(
                title = "分类占比",
                slices = summary.categories,
                hideUntracked = hideUntracked,
                onHideUntrackedChange = onHideUntrackedChange,
            )
        }

        item {
            ActivityCard {
                Text("分类", style = MaterialTheme.typography.titleSmall)
                if (summary.categories.isEmpty()) {
                    Text(
                        "今天没有可分类的活动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    orderedCategories(summary.categories).forEach { slice ->
                        CategoryRow(
                            slice = slice,
                            selected = slice.categoryId == selectedCategoryId,
                            onClick = {
                                if (selectedCategoryId == slice.categoryId) {
                                    selectedCategoryId = null
                                    selectedCategoryPath = null
                                } else {
                                    selectedCategoryId = slice.categoryId
                                    selectedCategoryPath = slice.name
                                }
                            },
                        )
                    }
                    if (selectedSlice != null) {
                        Text(
                            "已只看「" + selectedSlice.path + "」，再点一次取消。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            ActivityCard {
                Text("应用排行", style = MaterialTheme.typography.titleSmall)
                if (appRows.isEmpty()) {
                    Text(
                        "没有匹配的应用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val maxMs = appRows.maxOf { it.durationMs }.coerceAtLeast(1)
                    appRows.forEach { row ->
                        AppRow(row = row, maxMs = maxMs)
                    }
                }
            }
        }

        if (uncategorized.isNotEmpty()) {
            item {
                ActivityCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { uncategorizedOpen = !uncategorizedOpen },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("未分类", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            formatDuration(uncategorized.sumOf { it.durationMs }) + if (uncategorizedOpen) " 收起" else " 展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (uncategorizedOpen) {
                        Spacer(Modifier.height(8.dp))
                        uncategorized.take(20).forEach { segment ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenDetail(segment) }
                                    .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    segment.app ?: UnknownApp,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatDuration(segment.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "点一条看窗口标题、网址和归类原因。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 范围总览：一段日期按分类/应用汇总，全部按时长倒序。 */
@Composable
private fun RangeView(
    summary: RangeSummary?,
    loading: Boolean,
    hideUntracked: Boolean,
    onHideUntrackedChange: (Boolean) -> Unit,
) {
    if (summary == null) {
        Text(
            if (loading) "正在汇总这段日期…" else "还没有可以汇总的活动数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            ActivityCard {
                Text(
                    dayShortLabel(LocalDate.ofInstant(Instant.ofEpochMilli(summary.fromMs), ZoneId.systemDefault())) +
                        " – " +
                        dayShortLabel(LocalDate.ofInstant(Instant.ofEpochMilli(summary.toMs - 1), ZoneId.systemDefault())) +
                        "（" + summary.days + " 天）",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    ActivityStat("活跃", formatDuration(summary.activeMs))
                    ActivityStat("AFK", formatDuration(summary.afkMs))
                    ActivityStat("观察窗口", formatDuration(summary.observedMs))
                    ActivityStat("天数", summary.days.toString() + " 天")
                }
            }
        }

        item {
            CategoryPieCard(
                title = "分类占比",
                slices = summary.categories,
                hideUntracked = hideUntracked,
                onHideUntrackedChange = onHideUntrackedChange,
            )
        }

        item {
            ActivityCard {
                Text("这段时间的分类（按时长）", style = MaterialTheme.typography.titleSmall)
                if (summary.categories.isEmpty()) {
                    Text(
                        "这段时间没有可分类的活动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val maxMs = summary.categories.maxOf { it.totalMs }.coerceAtLeast(1)
                    summary.categories.forEach { slice ->
                        RangeCategoryRow(slice = slice, maxMs = maxMs)
                    }
                }
            }
        }

        if (summary.topApps.isNotEmpty()) {
            item {
                ActivityCard {
                    Text("应用排行", style = MaterialTheme.typography.titleSmall)
                    val maxMs = summary.topApps.maxOf { it.durationMs }.coerceAtLeast(1)
                    summary.topApps.forEach { slice ->
                        RangeAppRow(slice = slice, maxMs = maxMs)
                    }
                }
            }
        }
    }
}

/** 日视图时间轴：上面一条 24 小时概览，下面按小时分行的分钟网格。 */
@Composable
private fun TimelineCard(summary: DaySummary, segments: List<Segment>, onOpenDetail: (Segment) -> Unit) {
    ActivityCard {
        Text("时间轴", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "上面是全天概览；下面每小时一行、每格一分钟，颜色是该分钟里占时最多的分类。点格子看片段详情。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val track = MaterialTheme.colorScheme.surfaceVariant
        val span = (summary.dayEndMs - summary.dayStartMs).coerceAtLeast(1)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(track),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(segments) {
                        detectTapGestures { offset ->
                            if (size.width <= 0) return@detectTapGestures
                            val ms = summary.dayStartMs + (offset.x / size.width * span).toLong()
                            // 碎片在 24 小时比例下常常只有 1px 宽，允许点偏 8px 找最近的一段。
                            val toleranceMs = (span * 8f / size.width).toLong()
                            val nearest = segments.minByOrNull { segment -> distanceTo(segment, ms) }
                            if (nearest != null && distanceTo(nearest, ms) <= toleranceMs) onOpenDetail(nearest)
                        }
                    },
            ) {
                segments.forEach { segment ->
                    val x = ((segment.fromMs - summary.dayStartMs).toFloat() / span) * size.width
                    val w = ((segment.toMs - segment.fromMs).toFloat() / span) * size.width
                    drawRect(
                        color = tintOf(segment.color),
                        topLeft = Offset(x, 0f),
                        size = Size(w.coerceAtLeast(1f), size.height),
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { hour ->
                Text(hour, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(14.dp))
        val hours = remember(segments, summary.dayStartMs) { activeHourRange(summary.dayStartMs, segments) }
        if (hours == null) {
            Text(
                "今天还没有活跃片段。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            HourMinuteGrid(
                dayStartMs = summary.dayStartMs,
                hours = hours,
                segments = segments,
                track = track,
                onOpenDetail = onOpenDetail,
            )
            Spacer(Modifier.height(10.dp))
            TimelineLegend(segments = segments)
        }
    }
}

/** 分钟网格左侧小时标签的宽度，刻度行与格子行都按它对齐。 */
private val HourLabelWidth = 46.dp

private const val MinuteMs = 60_000L
private const val HourMs = 3_600_000L

/** 一分钟格：代表片段是这一分钟里重叠时间最长的那个。 */
private data class MinuteCell(val segment: Segment, val coverage: Float)

private class MinuteRow(val hour: Int, val cells: List<MinuteCell?>)

/** 只列出有活动的小时，避免 24 行里大半是空白。 */
private fun activeHourRange(dayStartMs: Long, segments: List<Segment>): IntRange? {
    if (segments.isEmpty()) return null
    val first = ((segments.minOf { it.fromMs } - dayStartMs).coerceAtLeast(0L) / HourMs).toInt().coerceIn(0, 23)
    val last = ((segments.maxOf { it.toMs } - dayStartMs - 1).coerceAtLeast(0L) / HourMs).toInt().coerceIn(0, 23)
    return first..last
}

/** 每分钟取「重叠时间最长」的片段作为该格的代表；coverage 是它占这一分钟的比例，用来调色块深浅。 */
private fun minuteRows(dayStartMs: Long, hours: IntRange, segments: List<Segment>): List<MinuteRow> {
    if (segments.isEmpty()) return emptyList()
    val sorted = segments.sortedBy { it.fromMs }
    return hours.map { hour ->
        val cells = ArrayList<MinuteCell?>(60)
        for (minute in 0 until 60) {
            val start = dayStartMs + hour * HourMs + minute * MinuteMs
            val end = start + MinuteMs
            var best: Segment? = null
            var bestOverlap = 0L
            for (segment in sorted) {
                if (segment.fromMs >= end) break
                val overlap = minOf(segment.toMs, end) - maxOf(segment.fromMs, start)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = segment
                }
            }
            cells += if (best == null) {
                null
            } else {
                MinuteCell(best, (bestOverlap.toFloat() / MinuteMs).coerceIn(0f, 1f))
            }
        }
        MinuteRow(hour, cells)
    }
}

private fun hourLabel(hour: Int): String = when {
    hour >= 24 -> "24:00"
    hour < 10 -> "0$hour:00"
    else -> "$hour:00"
}

/** 分钟热力网格：每行一小时、每格一分钟，点格子打开该分钟代表片段的详情。 */
@Composable
private fun HourMinuteGrid(
    dayStartMs: Long,
    hours: IntRange,
    segments: List<Segment>,
    track: Color,
    onOpenDetail: (Segment) -> Unit,
) {
    val rows = remember(dayStartMs, hours, segments) { minuteRows(dayStartMs, hours, segments) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.width(HourLabelWidth))
            Spacer(Modifier.width(8.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(60) { minute ->
                    Box(Modifier.weight(1f)) {
                        if (minute % 15 == 0) {
                            Text(
                                minute.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hourLabel(row.hour),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(HourLabelWidth),
                    textAlign = TextAlign.End,
                )
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    row.cells.forEach { cell ->
                        val base = Modifier
                            .weight(1f)
                            .height(13.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (cell == null) {
                                    track.copy(alpha = 0.35f)
                                } else {
                                    tintOf(cell.segment.color).copy(alpha = 0.35f + 0.65f * cell.coverage)
                                },
                            )
                        Box(
                            modifier = if (cell == null) base else base.clickable { onOpenDetail(cell.segment) },
                        )
                    }
                }
            }
        }
        Text(
            "只显示有活动的小时（" + hourLabel(hours.first) + "–" + hourLabel(hours.last + 1) +
                "）；空白格是 AFK 或没采集到。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 时间轴图例：按分类汇总时长取前 8 个，色点和格子里的颜色一致（叶子分类）。 */
@Composable
private fun TimelineLegend(segments: List<Segment>) {
    val items = remember(segments) { timelineLegend(segments) }
    if (items.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(item.color),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    item.label + " " + formatDuration(item.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class TimelineLegendItem(val label: String, val color: Color, val durationMs: Long)

private fun timelineLegend(segments: List<Segment>): List<TimelineLegendItem> =
    segments.groupBy { it.categoryId }
        .map { (_, group) ->
            val first = group.first()
            TimelineLegendItem(
                label = first.category.lastOrNull() ?: "未分类",
                color = tintOf(first.color),
                durationMs = group.sumOf { it.durationMs },
            )
        }
        .sortedByDescending { it.durationMs }
        .take(8)

/**
 * 分类占比饼图：只切一级分类（[CategorySlice.totalMs] 已含子类，不会重复计数）。
 *
 * 「不显示未追踪」勾上后把「未分类」从图里去掉——它常是采集噪声或还没补规则的时间，
 * 想看「真正被归好类的时间花在哪」时用。图中空环用分类色，图例同时给时长和百分比。
 */
@Composable
private fun CategoryPieCard(
    title: String,
    slices: List<CategorySlice>,
    hideUntracked: Boolean,
    onHideUntrackedChange: (Boolean) -> Unit,
) {
    val data = remember(slices, hideUntracked) { pieSlices(slices, hideUntracked) }
    // 选中项放在卡片内：切换日期/勾选后数据变了，选中项如果不在图里就自动清空。
    var selectedId by remember(data) { mutableStateOf<String?>(null) }
    val selected = data.firstOrNull { it.categoryId == selectedId }
    val selectedChildren = remember(selected, slices) {
        selected?.let { childSlices(slices, it) } ?: emptyList()
    }

    ActivityCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Checkbox(checked = hideUntracked, onCheckedChange = onHideUntrackedChange)
            Spacer(Modifier.width(2.dp))
            Text(
                "不显示未追踪",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onHideUntrackedChange(!hideUntracked) },
            )
        }
        Spacer(Modifier.height(10.dp))

        if (data.isEmpty()) {
            Text(
                if (hideUntracked) "隐藏未追踪后没有可显示的分类。" else "还没有可分类的活动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val totalMs = data.sumOf { it.totalMs }.coerceAtLeast(1)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActivityPie(
                    data = data,
                    totalMs = totalMs,
                    selectedId = selectedId,
                    onSelect = { id -> selectedId = if (selectedId == id) null else id },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    data.forEach { slice ->
                        PieLegendRow(
                            slice = slice,
                            totalMs = totalMs,
                            selected = slice.categoryId == selectedId,
                            onClick = { selectedId = if (selectedId == slice.categoryId) null else slice.categoryId },
                        )
                    }
                }
            }
            if (selected != null) {
                Spacer(Modifier.height(12.dp))
                PieSelectionDetail(slice = selected, children = selectedChildren, shownTotalMs = totalMs)
            }
        }
    }
}

/**
 * 环形饼图本体：外环按分类色切分，环上直接标分类名（扇区太窄放不下就省略），
 * 点击扇区聚焦；中心显示合计时长，聚焦后改成选中分类的名字和时长。
 */
@Composable
private fun ActivityPie(
    data: List<CategorySlice>,
    totalMs: Long,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val focus = data.firstOrNull { it.categoryId == selectedId }
    Box(modifier = Modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(4.dp)
                .pointerInput(data, totalMs) {
                    detectTapGestures { offset ->
                        val radius = minOf(size.width, size.height) / 2f
                        val dx = offset.x - size.width / 2f
                        val dy = offset.y - size.height / 2f
                        val distance = sqrt(dx * dx + dy * dy)
                        if (distance > radius) return@detectTapGestures
                        val stroke = minOf(size.width, size.height) * 0.20f
                        // 点中心空白 = 取消聚焦。
                        if (distance < radius - stroke) {
                            onSelect(null)
                            return@detectTapGestures
                        }
                        val angle = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f) + 360f) % 360f
                        var cursor = 0f
                        data.forEach { slice ->
                            val sweep = slice.totalMs.toFloat() / totalMs * 360f
                            if (angle >= cursor && angle < cursor + sweep) {
                                onSelect(slice.categoryId)
                                return@detectTapGestures
                            }
                            cursor += sweep
                        }
                    }
                },
        ) {
            val stroke = size.minDimension * 0.20f
            val ringRadius = size.minDimension / 2f - stroke / 2f
            var start = -90f
            data.forEach { slice ->
                val sweep = slice.totalMs.toFloat() / totalMs * 360f
                val dimmed = selectedId != null && selectedId != slice.categoryId
                drawArc(
                    color = if (dimmed) tintOf(slice.color).copy(alpha = 0.28f) else tintOf(slice.color),
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke),
                )

                // 标签贴着环的中线放；只画「未被压暗」的扇区，而且弧长要放得下文字。
                val showLabel = !dimmed && sweep >= 12f
                if (showLabel) {
                    val chord = 2f * ringRadius * sin(Math.toRadians(sweep.toDouble() / 2.0)).toFloat()
                    val label = slice.name.lastOrNull() ?: "未分类"
                    val style = TextStyle(
                        color = labelColorOn(tintOf(slice.color)),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val layout = textMeasurer.measure(AnnotatedString(label), style = style)
                    if (layout.size.width <= chord * 0.92f) {
                        val midRad = Math.toRadians((start + sweep / 2f).toDouble())
                        val cx = size.width / 2f + cos(midRad).toFloat() * ringRadius
                        val cy = size.height / 2f + sin(midRad).toFloat() * ringRadius
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                        )
                    }
                }
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (focus != null) (focus.name.lastOrNull() ?: "未分类") else formatDuration(totalMs),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (focus != null) {
                    formatDuration(focus.totalMs) + " · " + percentLabel(focus.totalMs, totalMs)
                } else {
                    "已显示"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PieLegendRow(
    slice: CategorySlice,
    totalMs: Long,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tintOf(slice.color)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            slice.name.lastOrNull() ?: "未分类",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            formatDuration(slice.totalMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            percentLabel(slice.totalMs, totalMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 饼图聚焦后的详情：全路径、合计/占比、直接时长、直接子分类。 */
@Composable
private fun PieSelectionDetail(slice: CategorySlice, children: List<CategorySlice>, shownTotalMs: Long) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tintOf(slice.color)),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(slice.path, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "合计 " + formatDuration(slice.totalMs) +
                    " · 占已显示 " + percentLabel(slice.totalMs, shownTotalMs) +
                    (if (slice.directMs != slice.totalMs) " · 直接 " + formatDuration(slice.directMs) else ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (children.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            "子分类：" + children.take(5).joinToString(" · ") { child ->
                (child.name.lastOrNull() ?: "未分类") + " " + formatDuration(child.totalMs)
            } + if (children.size > 5) " 等 " + children.size + " 项" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "再点一次切片或图例取消聚焦。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 分类树一行：缩进 + 颜色点 + 名称，右侧 direct/total；点击筛选应用排行。 */
@Composable
private fun CategoryRow(slice: CategorySlice, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(((slice.depth - 1).coerceAtLeast(0) * 14).dp))
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(tintOf(slice.color)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            slice.name.lastOrNull() ?: "未分类",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (slice.depth <= 1) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (slice.directMs != slice.totalMs) {
                "直接 " + formatDuration(slice.directMs) + " / 合计 " + formatDuration(slice.totalMs)
            } else {
                formatDuration(slice.totalMs)
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 范围总览的分类行：细进度条 + 名称 + 合计时长。 */
@Composable
private fun RangeCategoryRow(slice: CategorySlice, maxMs: Long) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(((slice.depth - 1).coerceAtLeast(0) * 14).dp))
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tintOf(slice.color)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                slice.path,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (slice.depth <= 1) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDuration(slice.totalMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((slice.totalMs.toFloat() / maxMs).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(tintOf(slice.color)),
            )
        }
    }
}

/** 应用排行一行：应用名 + 主分类 + 时长 + 细进度条。 */
@Composable
private fun AppRow(row: AppRowData, maxMs: Long) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.app,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    row.categoryPath.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                formatDuration(row.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((row.durationMs.toFloat() / maxMs).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun RangeAppRow(slice: AppSlice, maxMs: Long) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                slice.app,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatDuration(slice.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((slice.durationMs.toFloat() / maxMs).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** 片段详情浮层：窗口标题、网址、分类、时间段，以及「为什么归到这里」；可顺手建规则归类。 */
@Composable
private fun SegmentDetailDialog(
    runtime: AppRuntime,
    segment: Segment,
    onDismiss: () -> Unit,
    onClassify: () -> Unit,
) {
    val explanation by produceState<ClassificationExplanation?>(initialValue = null, segment) {
        value = runtime.activityExplain(segment.app, segment.title, segment.url)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClassify) { Text("归到分类…") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
        title = { Text("片段详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(segment.app ?: UnknownApp, style = MaterialTheme.typography.titleSmall)
                if (!segment.title.isNullOrBlank()) {
                    Text("标题：" + segment.title, style = MaterialTheme.typography.bodySmall)
                }
                if (!segment.url.isNullOrBlank()) {
                    Text("网址：" + segment.url, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(tintOf(segment.color)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("分类：" + segment.category.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "时间：" + formatClock(segment.fromMs) + " – " + formatClock(segment.toMs) +
                        "（" + formatDuration(segment.durationMs) + "）",
                    style = MaterialTheme.typography.bodySmall,
                )
                explanation?.let { info ->
                    val hit = info.chosen
                    if (hit.pattern != null) {
                        Text(
                            "命中规则：" + hit.pattern + "（" + hit.pathText + "）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "没有规则命中，归到未分类。点「归到分类…」可以就地建一条规则。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDatePicker(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = noonOf(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(dateOf(it)) }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun ActivityStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 页内卡片壳：跟首页/计划页同一套卡片样式（圆角/填充/描边都听 [LocalCardStyle]）。 */
@Composable
private fun ActivityCard(content: @Composable () -> Unit) {
    val style = LocalCardStyle.current
    val shape = RoundedCornerShape(style.radius)
    val container = if (style.filled) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 1120.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(18.dp),
    ) {
        content()
    }
}

// ------------------------------------------------------------------ 数据/格式化小工具

private const val ActivityCategoryUncategorized = "uncategorized"
private const val UnknownApp = "（未知）"

private data class AppRowData(val app: String, val durationMs: Long, val categoryPath: List<String>)

/** 按应用聚合片段，取占时最多的分类作主分类；[categoryPath] 非空时只算该分类及子类。 */
private fun appRowsFrom(segments: List<Segment>, categoryPath: List<String>?): List<AppRowData> {
    val filtered = if (categoryPath == null) {
        segments
    } else {
        segments.filter { it.category.take(categoryPath.size) == categoryPath }
    }
    return filtered.groupBy { it.app ?: UnknownApp }
        .map { (app, list) ->
            val main = list.groupBy { it.category }
                .maxByOrNull { entry -> entry.value.sumOf { it.durationMs } }
                ?.key
                ?: listOf(ActivityCategoryUncategorized)
            AppRowData(app = app, durationMs = list.sumOf { it.durationMs }, categoryPath = main)
        }
        .sortedByDescending { it.durationMs }
        .take(12)
}

/** 点到时间轴上某毫秒时光标离片段有多远；点在片段里为 0。 */
private fun distanceTo(segment: Segment, ms: Long): Long = when {
    ms < segment.fromMs -> segment.fromMs - ms
    ms > segment.toMs -> ms - segment.toMs
    else -> 0L
}

/** 分类树顺序：大类按 total 倒序，每个大类后面紧跟它自己的小类（同样按 total 倒序）。 */
private fun orderedCategories(slices: List<CategorySlice>): List<CategorySlice> {
    fun walk(parent: List<String>): List<CategorySlice> =
        slices.filter { it.name.size == parent.size + 1 && it.name.take(parent.size) == parent }
            .sortedByDescending { it.totalMs }
            .flatMap { listOf(it) + walk(it.name) }
    return walk(emptyList())
}

/** 饼图切片：只取一级分类；超过 8 个时把尾部并成「其他」，免得图例比饼还长。 */
private fun pieSlices(slices: List<CategorySlice>, hideUntracked: Boolean): List<CategorySlice> {
    val top = slices
        .filter { slice ->
            slice.depth == 1 &&
                slice.totalMs > 0 &&
                (!hideUntracked || slice.categoryId != ActivityCategoryUncategorized)
        }
        .sortedByDescending { it.totalMs }
    if (top.size <= 8) return top
    val rest = top.drop(7)
    return top.take(7) + CategorySlice(
        categoryId = "activity-other",
        name = listOf("其他"),
        color = "#9CA3AF",
        depth = 1,
        directMs = rest.sumOf { it.directMs },
        totalMs = rest.sumOf { it.totalMs },
    )
}

/** 百分比文案：≥10% 取整，小切片留一位小数（否则 0% 看不出有没有）。 */
private fun percentLabel(partMs: Long, totalMs: Long): String {
    if (totalMs <= 0L || partMs <= 0L) return "0%"
    val pct = partMs * 100.0 / totalMs
    return if (pct >= 10) {
        pct.roundToInt().toString() + "%"
    } else {
        ((pct * 10).roundToInt() / 10.0).toString() + "%"
    }
}

/** 饼图聚焦时列出的直接子分类（路径正好比父级多一层）。 */
private fun childSlices(slices: List<CategorySlice>, parent: CategorySlice): List<CategorySlice> =
    slices
        .filter { slice ->
            slice.categoryId != parent.categoryId &&
                slice.depth == parent.depth + 1 &&
                slice.name.size == parent.name.size + 1 &&
                slice.name.subList(0, parent.name.size) == parent.name
        }
        .sortedByDescending { it.totalMs }

/** 环上标签要贴在色块上，按亮度选深灰或白，别用主题色。 */
private fun labelColorOn(color: Color): Color {
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (luminance > 0.55f) Color(0xFF1F2937) else Color.White
}

/** 分类颜色是数据里的 `#RRGGBB`，不接主题语义色；非法值退回未分类灰。 */
private fun tintOf(hex: String): Color {
    val clean = hex.removePrefix("#")
    return runCatching {
        require(clean.length == 6)
        Color(("FF" + clean).toLong(16).toInt())
    }.getOrElse { Color(0xFF9CA3AF) }
}

/** 当天中午：日期换毫秒时统一用这个，避免时区/夏令时把「哪一天」算错。 */
private fun noonOf(date: LocalDate): Long =
    date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun dateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

private fun formatClock(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes <= 0 -> "0 分钟"
        minutes < 60 -> minutes.toString() + " 分钟"
        minutes % 60 == 0L -> (minutes / 60).toString() + " 小时"
        else -> (minutes / 60).toString() + " 小时 " + (minutes % 60) + " 分"
    }
}

private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天 " + date.format(DateTimeFormatter.ofPattern("M月d日")) + " " + weekdayName(date)
        today.minusDays(1) -> "昨天 " + date.format(DateTimeFormatter.ofPattern("M月d日")) + " " + weekdayName(date)
        else -> date.format(DateTimeFormatter.ofPattern("M月d日")) + " " + weekdayName(date)
    }
}

private fun dayShortLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("M月d日"))

private fun weekdayName(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** 头部右侧小字：上次同步时间。 */
private fun syncLabel(runtime: AppRuntime): String {
    val syncedAt = runtime.activitySyncedAtMs ?: return "还没同步过"
    return "上次同步 " + formatClock(syncedAt)
}