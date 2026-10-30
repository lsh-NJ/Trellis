package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.domain.plan.DayOccurrence
import dev.trellis.domain.plan.DayView
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.PoolUnit
import dev.trellis.domain.plan.WeekDay
import dev.trellis.ui.LocalCardStyle
import kotlinx.coroutines.launch

/**
 * 计划页：上面是整个计划（一周一格），下面是选中那一周的选中一天。
 *
 * 这一页只做三件事：把后端算好的 view model 摆开、把「选了哪周哪天」记在运行时、把勾选转发给命令。
 * 周次怎么切、还剩几件、哪条该顺延——都是 `:features:learning-process` 算的，页面里不做四则运算。
 */

/** 一周一格的卡片数据。字段全部来自 `MapView` + `WeekView`，这里只是拼在一起好排。 */
internal data class PlanWeekCard(
    val week: String,
    val label: String,
    val state: String,
    /** 这一周是否已经排到具体某天。false = 还在「待安排」里吃灰（灰卡）。 */
    val scheduled: Boolean,
    val chapter: String,
    val units: List<String>,
    val rangeLabel: String,
    val unitCount: Int,
)

@Composable
internal fun PlanScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    // 进页面才开库：不进计划页的人不该为 SQLite 付启动时间。
    LaunchedEffect(Unit) { runtime.refreshPlan() }

    Row(modifier = modifier) {
        PlanList(runtime, Modifier.width(232.dp).fillMaxHeight())
        Spacer(Modifier.width(20.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { PlanHeader(runtime) }
            if (runtime.isSimulatingTime) item { PlanSimulatedBanner(runtime) }
            if (runtime.planStartsInPast) item { PlanStaleBanner(runtime) }
            if (runtime.planRhythmHasNews) item { PlanRhythmCard(runtime) }

            if (runtime.planTitle == null) {
                item { PlanEmpty(runtime) }
            } else {
                item { WeekStrip(runtime) }
                if (runtime.planPendingSelected) {
                    item { PendingDetail(runtime) }
                } else {
                    item { WeekDetail(runtime) }
                }
            }
        }
    }
    if (runtime.planSettingsOpen) PlanSettingsDialog(runtime)
}

/** 左侧计划列表：按大类分组（没有大类归「未分类」）。已结束的计划不占列表。 */
@Composable
private fun PlanList(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val visible = runtime.planAll.filter { it.status != "ended" }
    val grouped = visible.groupBy { runtime.planCategories[it.id] ?: "未分类" }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "计划",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        grouped.forEach { (category, plans) ->
            Text(
                category,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
            )
            plans.forEach { plan ->
                ListRow(
                    text = plan.title,
                    sub = when {
                        plan.id == runtime.planFocusId -> "进行中"
                        plan.status == "draft" -> "草稿"
                        else -> ""
                    },
                    selected = runtime.planFocusId == plan.id,
                    onClick = { runtime.selectPlan(plan.id) },
                )
            }
        }
    }
}

@Composable
private fun ListRow(text: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlanStaleBanner(runtime: AppRuntime) {
    val firstWeek = runtime.planWeekCards.firstOrNull()?.week ?: return
    PlanCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("这份计划的第一周在过去", style = MaterialTheme.typography.titleSmall)
                Text(
                    "周次写成了 $firstWeek，所以整页都显示「过去」。可以把它平移到本周起——单元和顺序不变，只是换一份周次正确的计划。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            ChromeButton(label = "挪到本周", onClick = { runtime.repairPastPlan() })
        }
    }
}

/**
 * 「计划节奏」报告：系统刚替用户推进了一步（结算旧周 / 补下周承诺 / 铺天 / 生成复盘）。
 *
 * 这是状态触发的主动汇报，不是模型说话：只陈述做了什么、还剩什么，用户点「知道了」就收起。
 * 下一次真的又有动作时才会再出现；没有动作时页面保持安静。
 */
@Composable
private fun PlanRhythmCard(runtime: AppRuntime) {
    val report = runtime.planRhythmReport ?: return
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "计划节奏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { runtime.dismissPlanRhythmReport() }) {
                    Text("知道了", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(
                report.note.ifBlank { "计划在轨道上。" },
                style = MaterialTheme.typography.bodyMedium,
            )
            val extras = buildList {
                if (report.reviewedWeeks.isNotEmpty()) {
                    add("周复盘已生成：" + report.reviewedWeeks.joinToString("、") + "（可在对话里让 AI 讲一遍）")
                }
                if (report.poolRemaining > 0) add("待安排还剩 " + report.poolRemaining + " 个")
            }
            extras.forEach { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlanHeader(runtime: AppRuntime) {
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "计划",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                runtime.planTitle?.let { it + " · 一周一格，点开看那一天" } ?: "还没有计划",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (runtime.planTitle != null) {
            TextButton(onClick = { runtime.openPlanSettings() }) {
                Text("计划设置", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (runtime.planWeekCards.isNotEmpty()) {
            Text(
                runtime.planWeekCards.size.toString() + " 周",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「模拟时间」徽章。
 *
 * 开发者模式拨了日期后必须让人一眼看见，否则截图、演示、真实使用都会分不清
 * 页面上的「今天」到底是哪天。顺带把最常用的两个跳转按钮放在这里：
 * +1 天（看日切/逾期）、+1 周（看结算/结转/周日预铺）。
 */
@Composable
private fun PlanSimulatedBanner(runtime: AppRuntime) {
    val simulated = runtime.simulatedToday ?: return
    PlanCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "模拟时间 · " + simulated,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "真实时间 " + java.time.LocalDate.now() +
                        "；计划相关的结算、补周、复盘、铺天都按模拟日期走。这只是调试工具，不要在上面决定真实计划。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (runtime.devClockAllowed) {
                ChromeButton(label = "+1 天", onClick = { runtime.jumpSimulatedDays(1) })
                Spacer(Modifier.width(6.dp))
                ChromeButton(label = "+1 周", onClick = { runtime.jumpSimulatedWeeks(1) })
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { runtime.applySimulatedToday(null) }) {
                    Text("退出模拟", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * 计划设置：只放「这个计划自己的属性」。
 *
 * 为什么不做成全局设置页的一项：每天投入是计划属性——同一台机器上「考研」和「读书」的节奏不一样，
 * 所以入口必须在计划页、只改当前选中的计划。
 */
@Composable
private fun PlanSettingsDialog(runtime: AppRuntime) {
    AlertDialog(
        onDismissRequest = { runtime.closePlanSettings() },
        title = { Text("计划设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "只改当前这个计划（" + (runtime.planTitle ?: "") + "）；换一个计划就是另一套。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("每天能投入多少分钟", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = { runtime.bumpPlanDailyDraft(-5) }) { Text("−") }
                    Text(
                        runtime.planDailyDraft.toString(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("分钟", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { runtime.bumpPlanDailyDraft(5) }) { Text("+") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(30, 45, 60, 90, 120).forEach { minutes ->
                        TextButton(onClick = { runtime.choosePlanDailyDraft(minutes) }) {
                            Text(minutes.toString() + "′", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Text(
                    "排程按这个数往一天里塞节点；已经排好的天不会自动重排，只影响之后铺的天数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { runtime.savePlanDailyMinutes() }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = { runtime.closePlanSettings() }) { Text("取消") }
        },
    )
}

@Composable
private fun PlanEmpty(runtime: AppRuntime) {
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("没有正在进行的计划", style = MaterialTheme.typography.titleMedium)
            Text(
                runtime.planMessage ?: "在对话里说「我想学 xx，帮我排个计划」，管理员会把周次和单元排好，然后这里就有内容了。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 上半：总视图，一周一格，横向滚。 */
@Composable
private fun WeekStrip(runtime: AppRuntime) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "总览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text("按周排", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            runtime.planMessage?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        val state = rememberLazyListState()
        val scope = rememberCoroutineScope()
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.pointerInput(Unit) {
                // 桌面端没有横向滚轮：按住往左拖 = 往右看，和手机上的手感一致。
                detectDragGestures { change, drag ->
                    change.consume()
                    scope.launch { state.scrollBy(-drag.x) }
                }
            },
        ) {
            items(runtime.planWeekCards, key = { it.week }) { card ->
                WeekCard(card, selected = !runtime.planPendingSelected && card.week == runtime.planSelectedWeek) {
                    runtime.selectPlanWeek(card.week)
                }
            }
            // 没铺到天的周不再各占一张卡：全部收进一张「待安排」，宽度是周卡的一半。
            if (runtime.planPendingNodes.isNotEmpty()) {
                item(key = "pending") {
                    PendingWeekCard(
                        count = runtime.planPendingNodes.size,
                        selected = runtime.planPendingSelected,
                    ) { runtime.selectPlanPending() }
                }
            }
        }
    }
}

/**
 * 「待安排」那一张卡：宽度是周卡的一半，灰得更明显，右上角挂一个显眼的待安排标记。
 *
 * 只显示一个 —— 待安排不是「第 N 周」，而是一个集合；点开在下面看全部节点。
 */
@Composable
private fun PendingWeekCard(count: Int, selected: Boolean, onClick: () -> Unit) {
    val style = LocalCardStyle.current
    val shape = RoundedCornerShape(style.radius)
    Column(
        modifier = Modifier
            .width(98.dp)
            .height(184.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.outline)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                "待安排",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.surface,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            count.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = AppFonts.mono,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "个节点",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "点开看",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekCard(card: PlanWeekCard, selected: Boolean, onClick: () -> Unit) {
    val style = LocalCardStyle.current
    val shape = RoundedCornerShape(style.radius)
    val border = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    // 还没排到具体某天的周：整张卡灰掉 + 左上角「待安排」，免得跟已排的混在一起看。
    val background = when {
        !card.scheduled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        style.filled -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .width(196.dp)
            .height(184.dp)
            .clip(shape)
            .background(background)
            .border(if (selected) 2.dp else 1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(14.dp)
            .alpha(if (card.scheduled) 1f else 0.72f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = AppFonts.mono,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (card.scheduled) WeekStateChip(card.state) else PendingChip()
        }
        Text(
            card.chapter,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (card.scheduled) {
                card.units.take(3).forEach { unit ->
                    Text(
                        "· " + unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    "这一周还没排",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "到当周、或者周日晚上会自动排到每一天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                card.rangeLabel,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                card.unitCount.toString() + " 件",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingChip() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "待安排",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 「待安排」详情：把整个集合平铺出来（带预计分钟），按章节分组。
 *
 * 这里不排程、不勾选：这些节点还没落到某一天，勾选得先等它被排进某周某天。
 */
@Composable
private fun PendingDetail(runtime: AppRuntime) {
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "待安排 · " + runtime.planPendingNodes.size + " 个节点",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "还没排到某一天，排的时候按每天投入分钟数往里塞",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val totalMinutes = runtime.planPendingNodes.sumOf { it.estimateMinutes }
            Text(
                buildString {
                    append("合计 ").append(totalMinutes).append(" 分钟")
                    val daily = runtime.planDailyMinutes
                    if (daily != null && daily > 0) {
                        append(" · 按每天 ").append(daily).append(" 分钟算，约 ")
                        append((totalMinutes + daily - 1) / daily).append(" 天")
                    } else {
                        append(" · 还没定每天投入多少分钟，排程会问你要")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            runtime.planPendingNodes.groupBy { it.chapter.ifBlank { "未分章" } }.forEach { (chapter, units) ->
                Text(
                    chapter,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                units.forEach { unit ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            Modifier.size(16.dp).clip(RoundedCornerShape(3.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
                        )
                        Text(unit.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            unit.estimateMinutes.toString() + " 分钟",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = AppFonts.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStateChip(state: String) {
    val label = when (state) {
        "current" -> "本周"
        "past" -> "过去"
        else -> "以后"
    }
    val background = if (state == "current") {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val foreground = if (state == "current") {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = foreground)
    }
}

/** 下半：选中周里的选中一天，按待办的样子排。 */
@Composable
private fun WeekDetail(runtime: AppRuntime) {
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DayStrip(runtime)
            DayBody(runtime)
        }
    }
}

@Composable
private fun DayStrip(runtime: AppRuntime) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        runtime.planDays.forEach { day ->
            DayChip(
                day = day,
                selected = day.day == runtime.planSelectedDay,
                modifier = Modifier.weight(1f),
                onClick = { runtime.selectPlanDay(day.day) },
            )
        }
    }
}

@Composable
private fun DayChip(
    day: WeekDay,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val foreground = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(day.label.substringBefore(" "), style = MaterialTheme.typography.labelSmall, color = foreground)
            if (day.today) {
                Box(
                    Modifier.size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Text(
            day.label.substringAfter(" ", "").ifBlank { day.day.takeLast(5) },
            style = MaterialTheme.typography.labelMedium,
            fontFamily = AppFonts.mono,
            color = foreground,
        )
        Text(
            if (day.items.isEmpty()) "—" else day.items.size.toString() + " 件",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) foreground else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayBody(runtime: AppRuntime) {
    val unscheduled = runtime.planWeekCards
        .firstOrNull { it.week == runtime.planSelectedWeek }
        ?.scheduled == false
    if (unscheduled) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("这一周还没排", style = MaterialTheme.typography.bodyMedium)
            Text(
                "到了当周（或周日晚上）应用会自动把这一周排到每一天；也可以在上面选已经排好的周看看。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val view = runtime.planDayView
    if (view == null) {
        Text(
            "这一周还没有排内容。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(view.headline, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (view.occurrences.isEmpty() && view.completed.isEmpty()) {
            Text(
                "上面点别的周或别的天看看。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        view.occurrences.forEach { occurrence ->
            OccurrenceRow(occurrence) { runtime.completePlanOccurrence(occurrence.id) }
        }
        if (view.completed.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "已完成 · " + view.completed.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "点一下可以改回没做",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            view.completed.forEach { done ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { runtime.uncompletePlanOccurrence(done.id) }
                        .padding(vertical = 2.dp),
                ) {
                    Box(
                        Modifier.size(18.dp).clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ChromeIconGlyph(ChromeIcon.Check, MaterialTheme.colorScheme.onPrimary, Modifier.size(12.dp))
                    }
                    Text(
                        done.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = TextDecoration.LineThrough,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(occurrence: DayOccurrence, onComplete: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(4.dp))
                .clickable(onClick = onComplete),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(occurrence.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    if (occurrence.chapter.isNotBlank()) append(occurrence.chapter).append(" · ")
                    append(occurrence.minutes).append(" 分钟")
                    if (occurrence.carried) append(" · 上周顺延")
                    if (occurrence.movedCount > 0) append(" · 改过 ").append(occurrence.movedCount).append(" 次")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "待做",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 页内的卡片壳：跟首页同一套卡片样式（圆角/填充/描边都听 [LocalCardStyle]）。 */
@Composable
private fun PlanCard(content: @Composable () -> Unit) {
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
