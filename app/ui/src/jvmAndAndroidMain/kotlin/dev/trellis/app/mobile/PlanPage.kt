package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.AutoCompletionEntry
import kotlinx.coroutines.launch

/**
 * 计划：一条周节奏 + 今天该做什么。
 *
 * 桌面上它是「左列表 + 周条 + 当日详情 + 右侧排期抽屉」四块拼起来的；
 * 手机上砍掉的是**同时可见**，不是功能：计划切换收进弹层，周条横滑，
 * 当日单元竖排，每个单元点开有「完成 / 今天做 / 挪一天 / 跳过」，排期用「排到哪天」的日期条。
 *
 * 新增的两块（和桌面同步）：上方是「计划节奏」报告，下方是「AI 自动完成」的账本 ——
 * 机器勾掉的节点必须看得见、能撤销。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanPage(runtime: AppRuntime) {
    var planSheet by remember { mutableStateOf(false) }
    var unitSheetId by remember { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var placeTarget by remember { mutableStateOf<PlaceTarget?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 进页面才开库：不进计划页的人不该为 SQLite 付启动时间。
    LaunchedEffect(Unit) {
        runtime.refreshPlan()
        runtime.refreshAutoCompletions()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Gap.l, end = Gap.l, top = Gap.s, bottom = Gap.xxl),
        verticalArrangement = Arrangement.spacedBy(Gap.m),
    ) {
        item {
            PageHead(
                title = "计划",
                subtitle = runtime.planTitle ?: "还没有进行中的计划",
                trailing = {
                    Row {
                        if (runtime.planTitle != null) {
                            PhoneButton("设置", { runtime.openPlanSettings(); settingsOpen = true }, filled = false)
                            Spacer(Modifier.width(Gap.xs))
                        }
                        PhoneButton("切换", { planSheet = true }, filled = false)
                    }
                },
            )
        }

        if (runtime.simulatedToday != null) {
            item { SimulatedBanner(runtime) }
        }

        if (runtime.planTitle == null) {
            item {
                EmptyState(
                    "还没有计划",
                    "在「对话」里说：我想学 XX，帮我排个计划 —— 管理员会排好周次和单元",
                )
            }
            return@LazyColumn
        }

        // 周条：已排到天的周各占一格，「待安排」收成一格 —— 和桌面同一套信息结构，
        // 只是竖排改成横滑。
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Gap.s),
            ) {
                runtime.planWeekCards.forEach { card ->
                    val selected = !runtime.planPendingSelected && runtime.planSelectedWeek == card.week
                    WeekChip(
                        label = card.label,
                        sub = if (card.unitCount > 0) card.unitCount.toString() else "·",
                        selected = selected,
                        muted = false,
                    ) { runtime.selectPlanWeek(card.week) }
                }
                if (runtime.planPendingNodes.isNotEmpty()) {
                    WeekChip(
                        label = "待安排",
                        sub = runtime.planPendingNodes.size.toString(),
                        selected = runtime.planPendingSelected,
                        muted = true,
                    ) { runtime.selectPlanPending() }
                }
            }
        }

        // 逾期：只提示 + 给一个「今天补上」的动作，不在这里摊开全部积压。
        val overdue = runtime.planOverdue
        if (overdue != null && (overdue.thisWeek.isNotEmpty() || overdue.backlog.isNotEmpty())) {
            item {
                CardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "有 " + (overdue.thisWeek.size + overdue.backlog.size) + " 个单元没跟上",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "本周还剩 " + overdue.remainingDays + " 天 · " + minutesLabel(overdue.remainingMinutes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PhoneButton("补上", {
                            runtime.planCatchUpToday(overdue.dailyMinutes)
                            scope.launch { runtime.refreshPlan() }
                        }, filled = false)
                    }
                }
            }
        }

        // 计划节奏：系统自动结算/补承诺后的一句话。没有动作时后端返回空报告，这里不出现。
        val rhythm = runtime.planRhythmReport
        if (rhythm != null && runtime.planRhythmHasNews) {
            item {
                CardBox {
                    SectionLabel("计划节奏", action = "知道了" to { runtime.dismissPlanRhythmReport() })
                    Spacer(Modifier.height(Gap.s))
                    Text(
                        rhythm.note.ifBlank { "计划在轨道上。" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (rhythm.reviewedWeeks.isNotEmpty()) {
                        Spacer(Modifier.height(Gap.xs))
                        Text(
                            "周复盘已生成：" + rhythm.reviewedWeeks.joinToString("、"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (rhythm.poolRemaining > 0) {
                        Text(
                            "待安排还剩 " + rhythm.poolRemaining + " 个",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (runtime.planPendingSelected) {
            item { SectionLabel("待安排 " + runtime.planPendingNodes.size) }
            if (runtime.planPendingNodes.isEmpty()) {
                item { EmptyState("待安排的都排完了", "从「对话」里让管理员再补几周，或者直接休息。") }
            }
            runtime.planPendingNodes.forEach { unit ->
                item(key = "p" + unit.id) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = Gap.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(unit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                buildList {
                                    if (unit.chapter.isNotBlank()) add(unit.chapter)
                                    add(unit.estimateMinutes.toString() + " 分钟")
                                }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        PhoneButton("排期", {
                            placeTarget = PlaceTarget.Unit(unit.id, unit.title)
                        }, filled = false)
                    }
                }
            }
        } else {
            // 一周七天横滑：手机上不挤成七等分，改成可以滑的方块。
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Gap.s),
                ) {
                    runtime.planDays.forEach { day ->
                        val selected = runtime.planDayView?.day == day.day
                        Column(
                            Modifier
                                .width(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else LocalCardColor.current,
                                )
                                .clickable { runtime.selectPlanDay(day.day) }
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                day.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                day.items.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else if (day.items.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .height(3.dp)
                                    .width(if (day.today) 18.dp else 0.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.primary,
                                    ),
                            )
                        }
                    }
                }
            }

            val dayView = runtime.planDayView
            item {
                CardBox {
                    Text(
                        dayView?.headline ?: "这一天没有安排",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (dayView != null && dayView.occurrences.isNotEmpty()) {
                        Spacer(Modifier.height(Gap.m))
                        dayView.occurrences.forEachIndexed { index, occ ->
                            if (index > 0) {
                                Spacer(Modifier.height(Gap.m))
                                DividerLine()
                                Spacer(Modifier.height(Gap.m))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        occ.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        buildList {
                                            if (occ.chapter.isNotBlank()) add(occ.chapter)
                                            add(occ.minutes.toString() + " 分钟")
                                            if (occ.carried) add("从上次带过来")
                                        }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(Gap.s))
                                PhoneButton(
                                    "处理",
                                    { unitSheetId = occ.id },
                                    filled = false,
                                )
                            }
                        }
                    } else if (dayView != null) {
                        Spacer(Modifier.height(Gap.s))
                        Text(
                            "这一天是空的 —— 可以休息，或者从待办里挑一个提到今天。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 已完成的节点单独列：撤销入口在「AI 自动完成」卡片里，
                    // 这里只让人看到「今天做完了什么」。
                    if (dayView != null && dayView.completed.isNotEmpty()) {
                        Spacer(Modifier.height(Gap.m))
                        DividerLine()
                        Spacer(Modifier.height(Gap.m))
                        Text(
                            "已完成 " + dayView.completed.size,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        dayView.completed.forEach { done ->
                            Text(
                                "✓ " + done.title,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // AI 自动完成：笔记整理后按知识点判断、自动勾掉的计划节点。必须看得见、能撤销。
        val autoDone = runtime.planAutoCompletions
        if (autoDone.isNotEmpty() || runtime.planAutoCompletionBlockedCount > 0) {
            item {
                AutoCompletionCard(runtime, autoDone)
            }
        }

        // 待办池：不属于这一周节奏的零碎事项。
        if (runtime.planPool.isNotEmpty() && !runtime.planPendingSelected) {
            item { SectionLabel("待办 " + runtime.planPool.size) }
            runtime.planPool.forEach { unit ->
                item(key = unit.id) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { runtime.planDoToday(unit.id); scope.launch { runtime.refreshPlan() } }
                            .padding(vertical = Gap.m, horizontal = Gap.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("·", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(Gap.s))
                        Text(
                            unit.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            unit.estimateMinutes.toString() + " 分",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        runtime.planMessage?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (planSheet) {
        ModalBottomSheet(
            onDismissRequest = { planSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading("切换计划", "每个计划有自己的周节奏与复盘")
                Spacer(Modifier.height(Gap.m))
                runtime.planAll.filter { it.status != "ended" }.forEach { plan ->
                    val active = plan.id == runtime.planFocusId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                runtime.selectPlan(plan.id)
                                scope.launch { runtime.refreshPlan() }
                                planSheet = false
                            }
                            .padding(vertical = Gap.m, horizontal = Gap.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            plan.title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (active) Pill("当前", Tone.Good) else if (plan.status == "draft") Pill("草稿")
                    }
                }
                if (runtime.planAll.size <= 1) {
                    Text(
                        "只有一个计划。在「对话」里让管理员再排一个。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val unit = unitSheetId
    if (unit != null) {
        ModalBottomSheet(
            onDismissRequest = { unitSheetId = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading("这个单元", "完成会记一条事件；挪一天只改排期，不改内容")
                Spacer(Modifier.height(Gap.m))
                PhoneButton("标记完成", {
                    runtime.completePlanOccurrence(unit)
                    scope.launch { runtime.refreshPlan() }
                    unitSheetId = null
                }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Gap.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    PhoneButton("今天做", {
                        runtime.planDoToday(unit); scope.launch { runtime.refreshPlan() }; unitSheetId = null
                    }, modifier = Modifier.weight(1f), filled = false)
                    PhoneButton("挪一天", {
                        runtime.planDeferOneDay(unit); scope.launch { runtime.refreshPlan() }; unitSheetId = null
                    }, modifier = Modifier.weight(1f), filled = false)
                    PhoneButton("跳过", {
                        runtime.planSkipToday(unit); scope.launch { runtime.refreshPlan() }; unitSheetId = null
                    }, modifier = Modifier.weight(1f), filled = false)
                }
                Spacer(Modifier.height(Gap.s))
                PhoneButton("排到指定的一天", {
                    placeTarget = PlaceTarget.Occurrence(unit)
                    unitSheetId = null
                }, modifier = Modifier.fillMaxWidth(), filled = false)
            }
        }
    }

    val target = placeTarget
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { placeTarget = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading(
                    "排到哪天",
                    when (target) {
                        is PlaceTarget.Unit -> "「" + target.title + "」会从待安排里挪到选定的一天"
                        is PlaceTarget.Occurrence -> "这个单元会挪到选定的一天，内容不动"
                    },
                )
                Spacer(Modifier.height(Gap.m))
                val days = runtime.planDays.ifEmpty {
                    // 还没有选周时（待安排视图），给今天起的一周：排期必须有一个落脚点。
                    (0..6).map { offset ->
                        val date = runtime.clock.today().plusDays(offset.toLong())
                        dev.trellis.domain.plan.WeekDay(
                            day = date.toString(),
                            label = weekdayOnly(date),
                            today = offset == 0,
                        )
                    }
                }
                days.forEach { day ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                when (target) {
                                    is PlaceTarget.Unit -> runtime.planPlaceUnitToDay(target.unitId, day.day)
                                    is PlaceTarget.Occurrence -> runtime.planDragOccurrenceToDay(target.occurrenceId, day.day)
                                }
                                placeTarget = null
                                scope.launch { runtime.refreshPlan() }
                            }
                            .padding(vertical = Gap.m, horizontal = Gap.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            day.label + " · " + dayShort(day.day),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        if (day.today) Pill("今天", Tone.Good)
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        ModalBottomSheet(
            onDismissRequest = { runtime.closePlanSettings(); settingsOpen = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading("每天投入", "排程按这个数把单元铺到天：×7 就是这周的容量")
                Spacer(Modifier.height(Gap.l))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(runtime.planDailyDraft.toString() + " 分钟", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.weight(1f))
                    PhoneButton("−15", { runtime.bumpPlanDailyDraft(-15) }, filled = false)
                    Spacer(Modifier.width(Gap.s))
                    PhoneButton("+15", { runtime.bumpPlanDailyDraft(15) }, filled = false)
                }
                Spacer(Modifier.height(Gap.s))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    listOf(30, 45, 60, 90, 120).forEach { minutes ->
                        PhoneButton(minutes.toString(), { runtime.choosePlanDailyDraft(minutes) }, filled = false)
                    }
                }
                Spacer(Modifier.height(Gap.l))
                PhoneButton("保存并重排", {
                    runtime.savePlanDailyMinutes()
                    settingsOpen = false
                    scope.launch { runtime.refreshPlan() }
                }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Gap.s))
                Text(
                    "改小会让这周更紧、改大会把单元往后铺；已经完成的节点不动。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 排期目标：池里的单元，或已排到某天的单元。 */
private sealed interface PlaceTarget {
    val title: String

    data class Unit(val unitId: String, override val title: String) : PlaceTarget
    data class Occurrence(val occurrenceId: String, override val title: String = "") : PlaceTarget
}

@Composable
private fun WeekChip(label: String, sub: String, selected: Boolean, muted: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else LocalCardColor.current)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else if (muted) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            sub,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun AutoCompletionCard(runtime: AppRuntime, entries: List<AutoCompletionEntry>) {
    var expanded by remember { mutableStateOf(false) }
    var evidenceFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    CardBox {
        SectionLabel(
            "AI 自动完成 " + entries.size,
            action = if (runtime.planAutoCompletionBlockedCount > 0) {
                "解除屏蔽（" + runtime.planAutoCompletionBlockedCount + "）" to { runtime.clearAutoCompletionBlocks() }
            } else {
                null
            },
        )
        Spacer(Modifier.height(Gap.s))
        if (entries.isEmpty()) {
            Text(
                "笔记里讲到本周节点时，AI 整理完会自动勾上并记在这里。现在没有记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CardBox
        }
        val shown = if (expanded) entries else entries.take(4)
        shown.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = Gap.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✔", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Gap.s))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildList {
                            add(dayShort(entry.day))
                            add("来自《" + entry.noteTitle.ifBlank { "未命名笔记" } + "》")
                        }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "撤销",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            runtime.undoAutoCompletion(entry.id)
                            scope.launch { runtime.refreshPlan() }
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
            if (entry.evidence.isNotBlank()) {
                Text(
                    if (evidenceFor == entry.id) "收起证据" else "看证据",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { evidenceFor = if (evidenceFor == entry.id) null else entry.id }
                        .padding(vertical = 4.dp),
                )
                if (evidenceFor == entry.id) {
                    Text(
                        "“" + entry.evidence + "”",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DividerLine()
        }
        if (entries.size > 4) {
            Text(
                if (expanded) "收起" else "展开全部 " + entries.size + " 条",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 8.dp),
            )
        }
    }
}
