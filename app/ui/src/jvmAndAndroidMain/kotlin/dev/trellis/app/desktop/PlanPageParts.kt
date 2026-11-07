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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.domain.plan.DayOccurrence
import dev.trellis.domain.plan.StageView
import dev.trellis.domain.plan.UnitResourceView
import dev.trellis.domain.plan.WeekDay
import dev.trellis.domain.plan.WeekDayItem
import java.time.LocalDate
import kotlinx.coroutines.launch

/**
 * 计划页的「零件」：周刻度条、每日折叠卡、像素进度。
 *
 * 单个文件不是为了拆而拆：PlanScreen.kt 已经有 1200 多行，页面骨架（左栏 / 页头 / 复盘 / 对话框）
 * 和高密度的小控件混在一起，改一处要找半天。这里只放**没有页面级状态**的展示件：
 * 数据与动作一律经 [AppRuntime] 走，选中态由 `planSelectedWeek` / `planSelectedDay` 决定。
 *
 * 视觉约定（继续贴合计划页现有语言）：贴边、硬朗、少描边；数字一律等宽；
 * 选中用主题的浅强调底 + accent 线，不用 2dp 纯黑框（黑框在暖纸底上像报错）。
 */

// ---------------------------------------------------------------- 周刻度条

/**
 * 横向「刻度条」：一周一格，像尺子上的刻度——比原来一排 196dp 的大方框省地方，
 * 周数多了也能横着滚，不会把页面挤成两行。
 *
 * 选中周在刻度条上方写成一句「第 N 周 · 章节（日期区间）」：刻度本身只留短标签，
 * 完整信息放在这条标题上，扫一眼就知道现在看的是哪一周、讲什么。
 */
@Composable
internal fun PlanWeekTicker(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val cards = runtime.planWeekCards
    Column(verticalArrangement = Arrangement.spacedBy(tokens.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "总览",
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(tokens.space.s))
            Text("按周排", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            runtime.planMessage?.let { message ->
                Text(
                    message,
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(tokens.space.s))
            }
            Text(
                cards.size.toString() + " 周",
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val selected = cards.firstOrNull { it.week == runtime.planSelectedWeek }
        when {
            runtime.planPendingSelected -> Text(
                "待安排 · " + runtime.planPendingNodes.size + " 个节点",
                style = MaterialTheme.typography.titleSmall,
            )
            selected != null -> Text(
                buildString {
                    append(selected.label)
                    if (selected.chapter.isNotBlank()) append(" · ").append(selected.chapter)
                    if (selected.rangeLabel.isNotBlank()) append("（").append(selected.rangeLabel).append("）")
                    if (!selected.scheduled) append(" · 还没排到天")
                },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (cards.isNotEmpty()) PlanWeekScale(runtime, cards)
    }
}

/**
 * 刻度条本体：LazyRow 放刻度，桌面端没有横向滚轮，按住拖动等于往右看（沿用原周条的手感）。
 * 刻度下面压一条 1dp 发丝线，把一排刻度「坐」在同一条基线上。
 */
@Composable
private fun PlanWeekScale(runtime: AppRuntime, cards: List<PlanWeekCard>) {
    val tokens = LocalTrellisTokens.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 换周之后把当前选中刻度滚进视野中间偏左：周数多时不至于「点了却不知道滚去哪」。
    val selectedIndex = cards.indexOfFirst { it.week == runtime.planSelectedWeek }
    LaunchedEffect(runtime.planSelectedWeek, cards.size) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }
    Column {
        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        scope.launch { listState.scrollBy(-drag.x) }
                    }
                },
        ) {
            items(cards, key = { it.week }) { card ->
                PlanWeekTick(
                    card = card,
                    selected = !runtime.planPendingSelected && card.week == runtime.planSelectedWeek,
                    current = card.state == "current",
                    onClick = { runtime.selectPlanWeek(card.week) },
                )
            }
            if (runtime.planPendingNodes.isNotEmpty()) {
                item(key = "pending") {
                    PlanPendingTick(
                        count = runtime.planPendingNodes.size,
                        selected = runtime.planPendingSelected,
                        onClick = { runtime.selectPlanPending() },
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 2.dp), color = tokens.colors.rule)
    }
}

/**
 * 一个刻度：上面是周次（当前周直接写「本周」），中间一根竖刻，下面是这一周的开始日期。
 *
 * 竖刻高度区分「选中 / 当前 / 普通」，不靠加粗整格底来抢视觉——一排里有且只有一个选中，
 * 那个用浅强调底；当前周即使没选中也要能一眼认出来，所以刻线用 accent 色。
 */
@Composable
private fun PlanWeekTick(
    card: PlanWeekCard,
    selected: Boolean,
    current: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.control)
    val emphasized = selected || current
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(shape)
            .background(if (selected) tokens.colors.accentSoft.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            if (current) "本周" else card.label,
            style = tokens.type.meta,
            fontFamily = AppFonts.mono,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasized) tokens.colors.accent else tokens.colors.ink3,
            maxLines = 1,
        )
        Box(
            Modifier
                .width(if (selected) 3.dp else 2.dp)
                .height(if (selected) 22.dp else 14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (emphasized) tokens.colors.accent else tokens.colors.ruleStrong),
        )
        Text(
            card.rangeLabel.substringBefore(" – ").ifBlank { card.label },
            style = tokens.type.caption,
            fontFamily = AppFonts.mono,
            color = if (emphasized) tokens.colors.ink2 else tokens.colors.ink3,
            maxLines = 1,
        )
    }
}

/** 「待安排」那一个刻度：不是某一周，是一个集合；灰一档，但要在同一条刻度线上。 */
@Composable
private fun PlanPendingTick(count: Int, selected: Boolean, onClick: () -> Unit) {
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .width(74.dp)
            .clip(RoundedCornerShape(tokens.radius.control))
            .background(if (selected) tokens.colors.accentSoft.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "待安排",
            style = tokens.type.meta,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) tokens.colors.accent else tokens.colors.ink3,
            maxLines = 1,
        )
        Box(
            Modifier
                .width(2.dp)
                .height(if (selected) 22.dp else 14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected) tokens.colors.accent else tokens.colors.ruleStrong),
        )
        Text(
            count.toString() + " 个",
            style = tokens.type.caption,
            fontFamily = AppFonts.mono,
            color = if (selected) tokens.colors.ink2 else tokens.colors.ink3,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- 每日折叠

/**
 * 周视图的七天：一天一张折叠卡，今天默认摊开。
 *
 * 为什么不再用「上面一条日期格 + 下面一整块当天安排」：日期格只有 96dp 宽，放不下完成进度；
 * 展开的当天又离日期格很远，点完还得往下找。折叠卡把「哪天 / 几件 / 做到哪」和当天的节点
 * 放在同一个框里，扫一列就知道这周的负荷长什么样。
 */
@Composable
internal fun PlanDayAccordion(runtime: AppRuntime, controller: PlanDragController) {
    val tokens = LocalTrellisTokens.current
    val unscheduled = runtime.planWeekCards
        .firstOrNull { it.week == runtime.planSelectedWeek }
        ?.scheduled == false
    // 展开状态跟「当前看的周」绑定：换周就重新落到那周的今天（或后端给的默认选中天）。
    var expandedDay by remember(runtime.planSelectedWeek) {
        mutableStateOf(runtime.planSelectedDay ?: runtime.planDays.firstOrNull { it.today }?.day)
    }
    Column(verticalArrangement = Arrangement.spacedBy(tokens.space.xs + 2.dp)) {
        if (unscheduled) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("这一周还没排", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "到了当周（或周日晚上）应用会自动把这一周排到每一天；也可以在上面选已经排好的周看看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (runtime.planDays.isEmpty()) {
            Text(
                "这一周还没有排内容。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        runtime.planDays.forEach { day ->
            val expanded = day.day == expandedDay
            PlanDayCard(
                runtime = runtime,
                controller = controller,
                day = day,
                expanded = expanded,
                onClick = {
                    if (expanded) {
                        expandedDay = null
                    } else {
                        expandedDay = day.day
                        // 展开哪一天就选中哪一天：节点行里的勾选/拖拽都跟着 planSelectedDay 走。
                        runtime.selectPlanDay(day.day)
                    }
                },
            )
        }
    }
}

/** 一天一张卡：标题行（周几 / 日期 / 像素进度 / N 件）常驻，点标题行展开或收起。 */
@Composable
private fun PlanDayCard(
    runtime: AppRuntime,
    controller: PlanDragController,
    day: WeekDay,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.control)
    val today = runtime.clock.today().toString()
    val droppable = day.day >= today
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val dropping = controller.payload != null && droppable && bounds.contains(controller.pointer)
    val total = day.items.size
    val done = day.items.count { it.status == "done" }
    val background = when {
        dropping -> tokens.colors.accentSoft.copy(alpha = 0.45f)
        expanded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    }
    val border = when {
        dropping || expanded -> tokens.colors.ruleStrong
        else -> tokens.colors.rule
    }
    // 收起/换周后把这一天从拖拽命中表里摘掉：不然上一周同位置的旧矩形还留在表里，
    // 拖过去会落到一个页面上已经看不见的日子。
    DisposableEffect(day.day) {
        onDispose { controller.unregisterDay(day.day) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                bounds = coordinates.boundsInRoot()
                if (droppable) controller.registerDay(day.day, bounds)
            }
            .graphicsLayer { alpha = if (controller.payload != null && !droppable) 0.45f else 1f }
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = tokens.space.m, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
        ) {
            Text(
                day.label.substringBefore(" "),
                style = tokens.type.control,
                fontWeight = if (day.today) FontWeight.SemiBold else FontWeight.Normal,
                color = if (day.today) tokens.colors.accent else tokens.colors.ink,
            )
            Text(
                day.day.substring(5),
                style = tokens.type.meta,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (day.today) TodayTag()
            PlanPixelProgress(day.items)
            Spacer(Modifier.weight(1f))
            Text(
                total.toString() + " 件",
                style = tokens.type.meta,
                fontFamily = AppFonts.mono,
                color = if (total > 0) tokens.colors.ink2 else tokens.colors.ink3,
            )
            Text(
                if (expanded) "−" else "+",
                style = tokens.type.control,
                fontFamily = AppFonts.mono,
                color = tokens.colors.ink3,
            )
        }
        if (expanded) PlanDayBody(runtime, controller, day)
    }
}

/** 「今天」的小标签：和页头的小圆点语言不同，这里需要能读的字。 */
@Composable
private fun TodayTag() {
    val tokens = LocalTrellisTokens.current
    Box(
        Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(tokens.colors.accentSoft)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text("今天", style = tokens.type.caption, color = tokens.colors.onAccentSoft)
    }
}

/**
 * 像素进度：一个 occurrence 一格，11dp 方块 + 2dp 圆角。
 * 完成填主题强调色，未完成只留描边——一格一格的，扫一眼就知道还剩几个。
 *
 * 溢出保护：一天真的排了十几件时只画前 14 格，后面用「+N」收口，
 * 不把标题行撑成两行（那会把整列的高度差打乱）。
 */
@Composable
private fun PlanPixelProgress(items: List<WeekDayItem>) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(2.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (items.isEmpty()) {
            Text("—", style = tokens.type.caption, color = tokens.colors.ink3)
            return@Row
        }
        items.take(MAX_PIXELS).forEach { item ->
            val done = item.status == "done"
            Box(
                Modifier
                    .size(11.dp)
                    .clip(shape)
                    .then(
                        if (done) {
                            Modifier.background(tokens.colors.accent)
                        } else {
                            Modifier.border(1.dp, tokens.colors.ruleStrong, shape)
                        },
                    ),
            )
        }
        if (items.size > MAX_PIXELS) {
            Text(
                "+" + (items.size - MAX_PIXELS),
                style = tokens.type.caption,
                fontFamily = AppFonts.mono,
                color = tokens.colors.ink3,
            )
        }
    }
}

private const val MAX_PIXELS = 14

// ---------------------------------------------------------------- 展开的一天

/**
 * 展开后的一天：沿用原来「当天安排」的内容（勾选框 / 标题 / 章节 / 分钟 / 归因），
 * 只是从「页面下半部分」搬进了当天的卡里。
 */
@Composable
private fun PlanDayBody(
    runtime: AppRuntime,
    controller: PlanDragController,
    day: WeekDay,
) {
    val tokens = LocalTrellisTokens.current
    val view = runtime.planDayView?.takeIf { it.day == day.day }
    val today = runtime.clock.today().toString()
    val droppable = day.day >= today
    // 收起时把「整块落点」注销，免得拖拽还往一块已经不存在的地方落。
    DisposableEffect(day.day) {
        onDispose { controller.unregisterList() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                if (droppable) controller.registerList(day.day, coordinates.boundsInRoot())
            }
            .padding(start = tokens.space.m, end = tokens.space.m, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        HorizontalDivider(color = tokens.colors.rule)
        if (view == null) {
            Text(
                "这一天还没有排内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        // 窗口活动归因到各 occurrence 的时长：行尾显示「归因 N 分钟」。
        var attributed by remember(view.day) { mutableStateOf<Map<String, Long>>(emptyMap()) }
        LaunchedEffect(view.day) {
            attributed = runtime.activityOccurrenceMinutes(LocalDate.parse(view.day))
        }
        Text(
            view.headline,
            style = tokens.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        view.occurrences.forEach { occurrence ->
            PlanOccurrenceRow(runtime, controller, occurrence, attributed[occurrence.id] ?: 0L) {
                runtime.completePlanOccurrence(occurrence.id)
            }
        }
        if (view.occurrences.isEmpty() && view.completed.isEmpty()) {
            Text(
                "这一天还没排内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (view.completed.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "已完成 · " + view.completed.size,
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "点一下可以改回没做",
                    style = tokens.type.meta,
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
                        Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(3.dp))
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

/**
 * 一条节点：方形圆角勾选框（和任务页共用 `ChromeCheckbox`）+ 标题 + 硬朗的元信息。
 *
 * 勾选框只负责「做完」；点标题会把这条节点的**关联资源**展开在下面（B 站分集 / 网页 / 文档）。
 * 展开动作放在标题列而不是整行：整行还要留给拖拽，两个手势抢同一块区域会互相打架。
 */
@Composable
private fun PlanOccurrenceRow(
    runtime: AppRuntime,
    controller: PlanDragController,
    occurrence: DayOccurrence,
    attributedMs: Long,
    onComplete: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val tokens = LocalTrellisTokens.current
    val payload = PlanDragPayload.Occurrence(occurrence.id, occurrence.title, occurrence.minutes)
    // 展开态属于这一条节点：换天 / 重新排期后 id 变了就自动收起，不用手动 sync。
    var expanded by remember(occurrence.id) { mutableStateOf(false) }
    val resourceCount = occurrence.resources.size
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .planDragSource(
                payload = payload,
                controller = controller,
                onDragStart = { runtime.openPlanRearrange() },
                onDrop = { dropped, day ->
                    if (dropped is PlanDragPayload.Occurrence) {
                        runtime.planDragOccurrenceToDay(dropped.occurrenceId, day)
                    }
                    runtime.selectPlanDay(day)
                },
            )
            .graphicsLayer { alpha = if (controller.payload == payload) 0.35f else 1f }
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 行内勾选框和任务页共用同一个：同一个动作在三个页面里不该长三个样。
                .padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChromeCheckbox(checked = false, onCheckedChange = onComplete)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(occurrence.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    buildString {
                        if (occurrence.chapter.isNotBlank()) append(occurrence.chapter).append(" · ")
                        append(occurrence.minutes).append(" 分钟")
                        append(" · ").append(unitKindLabel(occurrence.kind))
                        if (occurrence.carried) append(" · 上周顺延")
                        if (occurrence.movedCount > 0) append(" · 改过 ").append(occurrence.movedCount).append(" 次")
                    },
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val attributedMinutes = (attributedMs + 59_999L) / 60_000L
            if (attributedMinutes > 0L) {
                Text(
                    "归因 " + attributedMinutes + " 分钟",
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "待做",
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 资源入口只用一个短标签，不摆按钮：数字是页面的主角，资源是补充。
            Text(
                when {
                    expanded -> "收起"
                    resourceCount > 0 -> "资源 " + resourceCount
                    else -> "▸"
                },
                style = tokens.type.caption,
                color = if (resourceCount > 0 || expanded) tokens.colors.accent else tokens.colors.ink3,
            )
        }
        if (expanded) PlanResourceList(occurrence.resources)
    }
}

/**
 * 节点展开后的关联资源列表。
 *
 * 数据来自后端 `unit_resource`（kind = bilibili / web / doc / other），这里只做展示与打开：
 * 有 url 的标题可点（`LocalUriHandler` 在桌面与 Android 上都能落到系统浏览器），
 * 没资源时明确说「还没有关联资源」——空着不解释会让人以为加载失败。
 */
@Composable
private fun PlanResourceList(resources: List<UnitResourceView>) {
    val tokens = LocalTrellisTokens.current
    val uriHandler = LocalUriHandler.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 46.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(tokens.space.xs),
    ) {
        if (resources.isEmpty()) {
            Text(
                "还没有关联资源",
                style = tokens.type.meta,
                color = tokens.colors.ink3,
            )
            return@Column
        }
        resources.forEach { resource ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.space.m),
            ) {
                Text(
                    resourceKindLabel(resource.kind),
                    style = tokens.type.caption,
                    fontFamily = AppFonts.mono,
                    color = tokens.colors.accent,
                    modifier = Modifier.width(44.dp),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val titleModifier = if (resource.url.isNotBlank()) {
                        Modifier.clickable {
                            runCatching { uriHandler.openUri(resource.url) }
                        }
                    } else {
                        Modifier
                    }
                    Text(
                        resource.title,
                        modifier = titleModifier,
                        style = tokens.type.control,
                        color = if (resource.url.isNotBlank()) MaterialTheme.colorScheme.primary else tokens.colors.ink,
                        textDecoration = if (resource.url.isNotBlank()) TextDecoration.Underline else null,
                    )
                    if (resource.locator.isNotBlank()) {
                        Text(
                            resource.locator,
                            style = tokens.type.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 资源类型的中文短标签：和计划页其它「类型」一样用等宽字，不额外做图标。 */
private fun resourceKindLabel(kind: String): String = when (kind) {
    "bilibili" -> "B 站"
    "web" -> "网页"
    "doc" -> "文档"
    else -> "资料"
}

// ---------------------------------------------------------------- 阶段条 / 阶段进度

/**
 * 阶段条（周刻度条上方）：整份计划按大纲切成几段，一段一个 chip，当前选中周落在哪段哪段高亮。
 *
 * chip 上同时摆「阶段名 + 完成/总数」：窄窗口右侧阶段进度栏会隐藏，进度得在这里还能看到。
 * 点 chip 跳到该阶段第一张周卡——不是跳到周号本身，因为阶段区间未必和排出来的周严丝合缝。
 */
@Composable
internal fun PlanStageStrip(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val stages = runtime.planStages
    if (stages.isEmpty()) return
    val selectedWeek = runtime.planSelectedWeek
    val scroll = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val active = stages.firstOrNull { stage -> stage.covers(selectedWeek) }
    Column(verticalArrangement = Arrangement.spacedBy(tokens.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "阶段",
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (active != null && active.focus.isNotBlank()) {
                Spacer(Modifier.width(tokens.space.s))
                Text(
                    active.focus,
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        LazyRow(
            state = scroll,
            horizontalArrangement = Arrangement.spacedBy(tokens.space.xs),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    // 桌面端没有横向滚轮：按在条上拖就是滚，和下面的周刻度条同一个手感。
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            scope.launch { scroll.scrollBy(-drag.x) }
                        },
                    )
                },
        ) {
            items(stages, key = { it.id }) { stage ->
                PlanStageChip(
                    stage = stage,
                    selected = stage.covers(selectedWeek),
                    onClick = {
                        stageFirstPlanWeek(runtime, stage)?.let { runtime.selectPlanWeek(it) }
                    },
                )
            }
        }
    }
}

/**
 * 一个阶段 chip：28dp 高、4dp 圆角的硬朗小条（不是药丸），名字 + 等宽进度。
 *
 * 选中用浅强调底 + accent 边；未选中只留发丝线——和页面「少描边、选中才亮」的规矩一致。
 */
@Composable
private fun PlanStageChip(stage: StageView, selected: Boolean, onClick: () -> Unit) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(tokens.radius.control)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(if (selected) tokens.colors.accentSoft else Color.Transparent)
            .border(1.dp, if (selected) tokens.colors.accent else tokens.colors.rule, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = tokens.space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tokens.space.s),
    ) {
        Text(
            stage.name,
            style = tokens.type.control,
            color = if (selected) tokens.colors.onAccentSoft else tokens.colors.ink2,
            maxLines = 1,
        )
        Text(
            stage.done.toString() + "/" + stage.total,
            style = tokens.type.caption,
            fontFamily = AppFonts.mono,
            color = if (selected) tokens.colors.onAccentSoft else tokens.colors.ink3,
        )
    }
}

/**
 * 右侧阶段进度栏的显示开关：宽窗 + 有阶段才显示。
 *
 * 判据用**窗口宽度**，不是页面自己的 `maxWidth`：计划页被外壳限宽在 1120dp（再减左右各 20dp 内边距），
 * 所以 1440pt 的窗口里页面最多只量到 1080dp，永远够不到 `TrellisBreakpoints.medium`——拿页面宽度判
 * 会连宽屏也不显示右栏（联调踩过）。窗口宽度在 PlanScreen 里用 `LocalWindowInfo` 取，和知识库页同一套。
 */
internal fun shouldShowStageRail(windowWidthDp: Float, stageCount: Int): Boolean =
    windowWidthDp >= TrellisBreakpoints.medium && stageCount > 0

/**
 * 右侧阶段进度栏（宽屏才显示）：一列「阶段名 / 完成 M/N / 3dp 细进度条」，点一行跳到该阶段第一周。
 *
 * 为什么放右栏而不是塞进卡片：阶段是「整份计划的形状」，翻周时它要一直在——
 * 周卡只回答这一周，右栏回答「现在走到哪、后面还有几段」。
 */
@Composable
internal fun PlanStageRail(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val tokens = LocalTrellisTokens.current
    val stages = runtime.planStages
    if (stages.isEmpty()) return
    Column(
        modifier = modifier
            .width(196.dp)
            .padding(start = tokens.space.m, top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(tokens.space.m),
    ) {
        Text(
            "阶段进度",
            style = tokens.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        stages.forEach { stage ->
            val selected = stage.covers(runtime.planSelectedWeek)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radius.control))
                    .clickable {
                        stageFirstPlanWeek(runtime, stage)?.let { runtime.selectPlanWeek(it) }
                    }
                    .padding(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(tokens.space.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stage.name,
                        style = tokens.type.control,
                        color = if (selected) tokens.colors.ink else tokens.colors.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (stage.total > 0) stage.done.toString() + "/" + stage.total else "—",
                        style = tokens.type.caption,
                        fontFamily = AppFonts.mono,
                        color = if (selected) tokens.colors.accent else tokens.colors.ink3,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(tokens.colors.rule),
                ) {
                    val fraction = if (stage.total > 0) stage.done.toFloat() / stage.total else 0f
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(if (selected) tokens.colors.accent else tokens.colors.ink3),
                    )
                }
            }
        }
    }
}

/** 选中周是否落在这个阶段的 ISO 周号区间里（周号零填充，字符串比较就是周序）。 */
private fun StageView.covers(week: String?): Boolean =
    week != null && week >= weekStart && week <= weekEnd

/**
 * 阶段 → 计划里实际存在的周卡：优先区间内的第一张，其次区间后的第一张，最后退回最后一张。
 *
 * 阶段区间来自大纲，周卡来自实际排出来的周，两边不求严丝合缝，跳转不能跳进空周。
 */
private fun stageFirstPlanWeek(runtime: AppRuntime, stage: StageView): String? {
    val cards = runtime.planWeekCards
    return cards.firstOrNull { it.week >= stage.weekStart && it.week <= stage.weekEnd }?.week
        ?: cards.firstOrNull { it.week >= stage.weekStart }?.week
        ?: cards.lastOrNull()?.week
}
