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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
            if (runtime.planStartsInPast) item { PlanStaleBanner(runtime) }

            if (runtime.planTitle == null) {
                item { PlanEmpty(runtime) }
            } else {
                item { WeekStrip(runtime) }
                if (runtime.planPool.isNotEmpty()) item { PoolCard(runtime) }
                item { WeekDetail(runtime) }
            }
        }
    }
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
        if (runtime.planWeekCards.isNotEmpty()) {
            Text(
                runtime.planWeekCards.size.toString() + " 周",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
                WeekCard(card, selected = card.week == runtime.planSelectedWeek) {
                    runtime.selectPlanWeek(card.week)
                }
            }
        }
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

/** 待安排池：还没排到具体某天的单元，按时间顺序列出来（最多 4 条）。 */
@Composable
private fun PoolCard(runtime: AppRuntime) {
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("待安排 · " + runtime.planPool.size, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "这些单元有了格式，但还没落到某一天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            runtime.planPool.take(4).forEach { unit ->
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
                        buildString {
                            if (unit.chapter.isNotBlank()) append(unit.chapter).append(" · ")
                            append(unit.estimateMinutes).append(" 分钟")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (runtime.planPool.size > 4) {
                Text(
                    "还有 " + (runtime.planPool.size - 4) + " 个没列出来",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        if (occurrence.minimal.available) {
            Text(
                "最小版 " + occurrence.minimal.minutes + " 分",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (occurrence.mode == "minimal") "最小版" else "待做",
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
