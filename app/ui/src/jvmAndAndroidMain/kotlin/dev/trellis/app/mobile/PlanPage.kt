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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.AutoCompletionEntry
import dev.trellis.app.desktop.AutoCompletionKind
import dev.trellis.app.desktop.PlanWeekCard
import dev.trellis.domain.plan.DayOccurrence
import dev.trellis.domain.plan.LearningPlanInfo
import dev.trellis.domain.plan.StageView
import dev.trellis.domain.plan.UnitResourceView
import kotlinx.coroutines.launch

/**
 * 计划：一条周节奏 + 今天该做什么。
 *
 * 桌面上它是「左列表 + 周条 + 当日详情 + 右侧排期抽屉」四块拼起来的；
 * 手机上砍掉的是**同时可见**，不是功能：计划切换收进弹层，周条横滑，
 * 当日单元竖排，每个单元点开有「完成 / 今天做 / 挪一天 / 跳过」，排期用「排到哪天」的日期条。
 *
 * 新增的两块（和桌面同步）：上方是「计划节奏」报告，下方是「AI 自动完成」的账本 ——
 * 机器勾掉的节点必须看得见、能撤销。账本来源有三种：笔记整理、计时跑满、屏幕使用达到时长，
 * 三种的文案与「能不能跳回去看」各不相同，卡片里按 kind/source 分开渲染。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanPage(runtime: AppRuntime) {
    var planSheet by remember { mutableStateOf(false) }
    // 存整个 DayOccurrence 而不是 id：弹层里的「开始计时」需要标题，
    // 再按 id 去 dayView 里找一遍既绕又容易在刷新后找不到。
    var unitSheet by remember { mutableStateOf<DayOccurrence?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var placeTarget by remember { mutableStateOf<PlaceTarget?>(null) }
    // 阶段进度默认收起：它是一行参考信息，不该在手机单列里占掉首屏。
    var stagesOpen by remember { mutableStateOf(false) }
    // 当前展开资料的单元 id；同一时刻只展开一条，和桌面「展开一条」的语义一致。
    var resourcesFor by remember { mutableStateOf<String?>(null) }
    // 放弃计划要二次确认：第一次点只是把危险按钮换成「确认放弃」。
    var abandonArmed by remember { mutableStateOf(false) }
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
                            PhoneButton("设置", {
                                abandonArmed = false
                                runtime.openPlanSettings()
                                settingsOpen = true
                            }, filled = false)
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

        // 计划操作失败（比如选中的计划已被放弃、后端拒绝写）：桌面用 planNotice 横幅提示，
        // 手机上以前完全没有这条通道，用户点了「标记完成」只会觉得没反应。
        runtime.planNotice?.let { notice ->
            item {
                CardBox {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "计划操作没成功",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                notice,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(Gap.s))
                        Text(
                            "知道了",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { runtime.dismissPlanNotice() }
                                .padding(horizontal = Gap.s, vertical = Gap.m),
                        )
                    }
                }
            }
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
                        // 「3 个」而不是「3」：卡片上只有一个数字时，人不知道它是单元数、
                        // 日期还是进度。多一个字就不用猜。
                        sub = if (card.unitCount > 0) card.unitCount.toString() + " 个" else "·",
                        selected = selected,
                        muted = false,
                    ) { runtime.selectPlanWeek(card.week) }
                }
                if (runtime.planPendingNodes.isNotEmpty()) {
                    WeekChip(
                        label = "待安排",
                        sub = runtime.planPendingNodes.size.toString() + " 个",
                        selected = runtime.planPendingSelected,
                        muted = true,
                    ) { runtime.selectPlanPending() }
                }
            }
        }

        // 阶段进度：桌面是右侧常驻栏（窗口 ≥1100dp 才出现），手机改成顶部一行摘要，
        // 点开才铺开全部阶段。没有阶段就不占位。
        val stages = runtime.planStages
        if (stages.isNotEmpty()) {
            item {
                val completedStages = stages.count { it.total > 0 && it.done >= it.total }
                val selectedWeek = runtime.planSelectedWeek
                val current = stages.firstOrNull { stage ->
                    selectedWeek != null && selectedWeek >= stage.weekStart && selectedWeek <= stage.weekEnd
                } ?: stages.firstOrNull { it.done < it.total } ?: stages.last()
                CardBox {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { stagesOpen = !stagesOpen }
                            .heightIn(min = Touch)
                            .padding(vertical = Gap.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "阶段进度 " + completedStages + "/" + stages.size + " · " + current.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (stagesOpen) {
                                    "点阶段跳到那一阶段的第一周"
                                } else {
                                    "当前 · " + stageProgressLabel(current) +
                                        (if (current.focus.isNotBlank()) " · " + current.focus else "")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(Gap.s))
                        Text(
                            if (stagesOpen) "收起" else "展开",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (stagesOpen) {
                        Spacer(Modifier.height(Gap.s))
                        DividerLine()
                        stages.forEach { stage ->
                            StageProgressRow(stage) {
                                stageFirstPlanWeek(runtime.planWeekCards, stage)?.let { runtime.selectPlanWeek(it) }
                            }
                        }
                    }
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
                                    { unitSheet = occ },
                                    filled = false,
                                )
                            }
                            // 单元资料：阶段大纲里的「- 资源：」会被挂到单元上（桌面日卡里
                            // 是「▸ 资源 N」），手机在行下给同样一个展开入口。
                            if (occ.resources.isNotEmpty()) {
                                val expanded = resourcesFor == occ.id
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { resourcesFor = if (expanded) null else occ.id }
                                        .heightIn(min = Touch)
                                        .padding(vertical = Gap.xs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        (if (expanded) "▾ " else "▸ ") + "资料 " + occ.resources.size,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        if (expanded) "收起" else "展开",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (expanded) {
                                    ResourceRows(occ.resources)
                                }
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
                    // 已完成的节点单独列，并允许点一下改回没做：桌面日卡里就是这个语义
                    // （PlanPageParts 的「点一下可以改回没做」）。以前手机只显示、把撤销入口
                    // 全指向 AI 自动完成卡，那只覆盖机器勾的节点，手工勾的节点改不回来。
                    if (dayView != null && dayView.completed.isNotEmpty()) {
                        Spacer(Modifier.height(Gap.m))
                        DividerLine()
                        Spacer(Modifier.height(Gap.m))
                        Text(
                            "已完成 " + dayView.completed.size + " · 点一下可以改回没做",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        dayView.completed.forEach { done ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        runtime.uncompletePlanOccurrence(done.id)
                                        scope.launch { runtime.refreshPlan() }
                                    }
                                    .heightIn(min = Touch)
                                    .padding(vertical = Gap.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "✓ " + done.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = TextDecoration.LineThrough,
                                )
                            }
                        }
                    }
                }
            }
        }

        // 周复盘：桌面放在周详情卡底部（PlanReviewSection），手机放到当日卡之后。
        // 入口只有两个：欠着的那一周「生成复盘」、本周最后一天「生成本周总结」；
        // 已经生成过的复盘正文在这里读，历史复盘仍在上面节奏卡里看。
        val pendingReviewWeek = runtime.planPendingReviewWeek
        val review = runtime.planReview
        val reviewableWeek = runtime.planReviewableWeek
        val selectedWeek = runtime.planSelectedWeek
        val canGenerateThisWeek = reviewableWeek != null && reviewableWeek == selectedWeek && review == null
        if (pendingReviewWeek != null || review != null || canGenerateThisWeek) {
            item {
                CardBox {
                    SectionLabel(
                        if (canGenerateThisWeek) "本周总结" else "周复盘",
                        action = if (pendingReviewWeek != null) {
                            "生成复盘" to ({ runtime.generatePlanReview() })
                        } else {
                            null
                        },
                    )
                    Spacer(Modifier.height(Gap.s))
                    when {
                        review != null -> {
                            if (review.narrative.isNotBlank()) {
                                Text(review.narrative, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (review.facts.isNotEmpty()) {
                                Spacer(Modifier.height(Gap.s))
                                review.facts.forEach { fact ->
                                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                        Text(
                                            fact.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.width(Gap.s))
                                        Text(
                                            fact.value,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                            if (review.question.isNotBlank()) {
                                Spacer(Modifier.height(Gap.s))
                                Text(
                                    "问：" + review.question,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            review.suggestion?.let { suggestion ->
                                Spacer(Modifier.height(Gap.s))
                                Text(
                                    "建议：" + suggestion.action +
                                        "（代价：" + suggestion.cost + "，预期：" + suggestion.expectedEffect + "）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        pendingReviewWeek != null -> Text(
                            pendingReviewWeek + " 已经结束，复盘还没生成。点「生成复盘」补一份。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> {
                            Text(
                                "今天是这周最后一天：不用等周一，先把这一周的总结做了。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Gap.m))
                            PhoneButton("生成本周总结", {
                                // 生成要调模型：必须起协程，不能像「生成复盘」那样同步调。
                                reviewableWeek?.let { week -> scope.launch { runtime.generateWeekReview(week) } }
                            }, modifier = Modifier.fillMaxWidth())
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
                        Text(
                            "·",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
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
                // 和桌面左栏同一套分组：进行中 / 已完成 / 已放弃。
                // 以前所有计划平铺成一行行、只带「当前/草稿」标签，完成和放弃的计划混在
                // 里面；用户还能选中已放弃的计划去点「标记完成」，后端拒绝后手机上又没有
                // 任何提示。分组 + 状态小字是最小成本的对齐。
                val visible = runtime.planAll.filter { it.status != "ended" }
                val abandoned = visible.filter { it.status == "abandoned" }
                val completed = visible.filter { it.status != "abandoned" && it.id in runtime.completedPlanIds }
                val ongoing = visible.filter { it.status != "abandoned" && it.id !in runtime.completedPlanIds }
                fun pick(id: String) {
                    runtime.selectPlan(id)
                    scope.launch { runtime.refreshPlan() }
                    planSheet = false
                }
                if (ongoing.isNotEmpty()) {
                    SectionLabel("进行中 " + ongoing.size)
                    ongoing.forEach { plan ->
                        PlanSwitchRow(plan, plan.id == runtime.planFocusId, "进行中") { pick(plan.id) }
                    }
                    Spacer(Modifier.height(Gap.s))
                }
                if (completed.isNotEmpty()) {
                    SectionLabel("已完成 " + completed.size)
                    completed.forEach { plan ->
                        PlanSwitchRow(plan, plan.id == runtime.planFocusId, "已完成") { pick(plan.id) }
                    }
                    Spacer(Modifier.height(Gap.s))
                }
                if (abandoned.isNotEmpty()) {
                    SectionLabel("已放弃 " + abandoned.size)
                    abandoned.forEach { plan ->
                        PlanSwitchRow(plan, plan.id == runtime.planFocusId, "已放弃") { pick(plan.id) }
                    }
                    Spacer(Modifier.height(Gap.s))
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

    val unit = unitSheet
    if (unit != null) {
        ModalBottomSheet(
            onDismissRequest = { unitSheet = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gap.xl)
                    .padding(bottom = Gap.xxl),
            ) {
                SheetHeading("这个单元", "完成会记一条事件；挪一天只改排期，不改内容")
                // 资料先摆出来：阶段大纲里带的资源链接是「开始做之前」要看的东西。
                if (unit.resources.isNotEmpty()) {
                    Spacer(Modifier.height(Gap.m))
                    Text(
                        "资料 " + unit.resources.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Gap.xs))
                    ResourceRows(unit.resources)
                    Spacer(Modifier.height(Gap.m))
                    DividerLine()
                }
                Spacer(Modifier.height(Gap.m))
                PhoneButton("标记完成", {
                    runtime.completePlanOccurrence(unit.id)
                    scope.launch { runtime.refreshPlan() }
                    unitSheet = null
                }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(Gap.s))
                // 计划节点计时：和任务共用同一个小窗，落账走 session（计划侧只关心
                // 「这段时间在做什么」，分钟由起止自己算）。
                PhoneButton("开始计时", {
                    runtime.openOccurrenceTimer(
                        planId = runtime.planFocusId ?: "",
                        occurrenceId = unit.id,
                        title = unit.title,
                    )
                    // openOccurrenceTimer 只把计时条摆出来（等人按开始），手机上这里
                    // 没有「开始」按钮；用户点「开始计时」的意思就是现在开始，所以
                    // 直接把整点时长的秒表跑起来，否则计时条恒 0:00、也开不出 session。
                    runtime.startTimer()
                    unitSheet = null
                }, modifier = Modifier.fillMaxWidth(), filled = false)
                Spacer(Modifier.height(Gap.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    PhoneButton("今天做", {
                        runtime.planDoToday(unit.id); scope.launch { runtime.refreshPlan() }; unitSheet = null
                    }, modifier = Modifier.weight(1f), filled = false)
                    PhoneButton("挪一天", {
                        runtime.planDeferOneDay(unit.id); scope.launch { runtime.refreshPlan() }; unitSheet = null
                    }, modifier = Modifier.weight(1f), filled = false)
                    PhoneButton("跳过", {
                        runtime.planSkipToday(unit.id); scope.launch { runtime.refreshPlan() }; unitSheet = null
                    }, modifier = Modifier.weight(1f), filled = false)
                }
                Spacer(Modifier.height(Gap.s))
                PhoneButton("排到指定的一天", {
                    placeTarget = PlaceTarget.Occurrence(unit.id)
                    unitSheet = null
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
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Gap.xl)
                    .padding(bottom = Gap.xxl),
            ) {
                SheetHeading("计划设置", "时间只做参考：排程会按这个数把单元尽量摊到每天，不强制卡满")
                // 目标与计划文档在桌面是页头两颗按钮；手机页头只有「设置/切换」，
                // 这两个入口收进设置弹层，点了由 PhoneApp 接二级页。
                val focusedPlan = runtime.planAll.firstOrNull { it.id == runtime.planFocusId }
                Spacer(Modifier.height(Gap.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    PhoneButton(
                        if (runtime.goals.isEmpty()) "目标" else "目标 · " + runtime.goals.size,
                        { settingsOpen = false; runtime.openGoalScreen() },
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )
                    PhoneButton(
                        "计划文档",
                        { settingsOpen = false; runtime.openPlanDocs(runtime.planFocusId) },
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )
                }
                Spacer(Modifier.height(Gap.l))
                DividerLine()
                Spacer(Modifier.height(Gap.l))
                SectionLabel("每天投入")
                Spacer(Modifier.height(Gap.s))
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
                // 危险区：放弃计划不删除任何数据，但不会再排期；已放弃的计划不再给这个按钮。
                if (focusedPlan != null && focusedPlan.status != "abandoned") {
                    Spacer(Modifier.height(Gap.l))
                    DividerLine()
                    Spacer(Modifier.height(Gap.l))
                    if (!abandonArmed) {
                        PhoneButton(
                            "放弃这份计划",
                            { abandonArmed = true },
                            modifier = Modifier.fillMaxWidth(),
                            filled = false,
                            danger = true,
                        )
                    } else {
                        Text(
                            "放弃后它不会再排期，也不会再出现在「进行中」；单元、复盘和历史都还在，" +
                                "可以在「切换」里的「已放弃」找到它，但只能查看。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(Gap.m))
                        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                            PhoneButton(
                                "确认放弃",
                                {
                                    runtime.abandonPlan(focusedPlan.id)
                                    abandonArmed = false
                                    settingsOpen = false
                                },
                                modifier = Modifier.weight(1f),
                                danger = true,
                            )
                            PhoneButton(
                                "取消",
                                { abandonArmed = false },
                                modifier = Modifier.weight(1f),
                                filled = false,
                            )
                        }
                    }
                }
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

/** 计划切换弹层的一行：标题 + 状态小字，「当前」标记；已放弃的计划只读，标题压灰。 */
@Composable
private fun PlanSwitchRow(plan: LearningPlanInfo, selected: Boolean, group: String, onClick: () -> Unit) {
    val abandoned = plan.status == "abandoned"
    val state = when {
        abandoned -> "已放弃 · 只能查看"
        group == "已完成" -> "已完成 · 可以继续打开"
        plan.status == "draft" -> "草稿 · 还没排到天"
        else -> "进行中"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .heightIn(min = Touch)
            .padding(vertical = Gap.m, horizontal = Gap.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                plan.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (abandoned) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                state,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) Pill("当前", Tone.Good)
    }
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
                "笔记整理讲到本周节点、计时跑满预估时长、屏幕使用达到预估时长时，都会自动勾上并记在这里。现在没有记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CardBox
        }
        val shown = if (expanded) entries else entries.take(4)
        shown.forEach { entry ->
            // 来源三分支，和桌面 PlanScreen 的 AutoCompletionRow 同一口径：
            //   笔记 → 来自《笔记》，可跳正文；计时 → 实际/预估分钟；屏幕使用 → 学习 · 窗口标题。
            // 以前这里无条件拼「来自《noteTitle》」；计时/屏幕使用条目的 noteId 为空，
            // 于是显示成「未命名笔记」、点击也毫无反应。
            val fromActivity = entry.kind == AutoCompletionKind.Time && entry.source == "activity"
            val sourceLabel = when {
                fromActivity ->
                    if (entry.sourceTitle.isNotBlank()) "学习 · " + entry.sourceTitle else "来自屏幕使用"
                entry.kind == AutoCompletionKind.Time ->
                    "来自计时 · 实际 " + entry.actualMinutes + " 分钟" +
                        if (entry.plannedMinutes > 0) "（预估 " + entry.plannedMinutes + " 分钟）" else ""
                else -> "来自《" + entry.noteTitle.ifBlank { "未命名笔记" } + "》"
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Gap.xs)
                    .then(
                        // 屏幕使用来源和桌面一样整行可点：跳到归因那一天的活动页。
                        // openActivityDay 会写 pendingActivityDay + runtime.currentTab。
                        if (fromActivity) {
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { runtime.openActivityDay(entry.day) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✔", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(Gap.s))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildList {
                                add(dayShort(entry.day))
                                add(sourceLabel)
                            }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            // 笔记来源像链接一样用主色；机器自动记的时长是事实陈述，用次级墨色。
                            color = if (entry.kind == AutoCompletionKind.Note) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (entry.kind == AutoCompletionKind.Note) {
                                Modifier
                                    .weight(1f)
                                    .clickable { runtime.openAutoCompletionNote(entry.id) }
                            } else {
                                Modifier.weight(1f)
                            },
                        )
                        if (fromActivity) {
                            Spacer(Modifier.width(Gap.xs))
                            Text(
                                "详情",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { runtime.openActivityDay(entry.day) }
                                    .padding(horizontal = Gap.xs, vertical = 2.dp),
                            )
                        }
                    }
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
                    // 只有笔记来源才有正文可跳：计时/屏幕使用的依据是时长，没有笔记。
                    if (entry.kind == AutoCompletionKind.Note) {
                        // 证据看完了，下一步必然是「这篇笔记到底写了什么」。
                        // 桌面是滚到正文里选中那句；手机上打开笔记、把原句顶到第一屏（见 KnowledgePage）。
                        Text(
                            "打开《" + entry.noteTitle.ifBlank { "未命名笔记" } + "》",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { runtime.openAutoCompletionNote(entry.id) }
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                        )
                    }
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

/**
 * 单元资料：桌面 PlanPageParts 的 `PlanResourceList` 同语义。
 * 链接用 `LocalUriHandler` 交给系统浏览器 —— 桌面和 Android 都能用，不需要各写一套。
 */
@Composable
private fun ResourceRows(resources: List<UnitResourceView>) {
    val uriHandler = LocalUriHandler.current
    Column(Modifier.fillMaxWidth()) {
        resources.forEach { resource ->
            val hasUrl = resource.url.isNotBlank()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (hasUrl) {
                            Modifier.clickable { runCatching { uriHandler.openUri(resource.url) } }
                        } else {
                            Modifier
                        },
                    )
                    .heightIn(min = Touch)
                    .padding(vertical = Gap.xs, horizontal = Gap.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        resource.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (hasUrl) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        textDecoration = if (hasUrl) TextDecoration.Underline else null,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (resource.locator.isNotBlank()) {
                        Text(
                            resource.locator,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(Gap.s))
                Text(
                    resourceKindLabel(resource.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 资料类型的中文短名（和桌面 `resourceKindLabel` 一致）。 */
private fun resourceKindLabel(kind: String): String = when (kind) {
    "bilibili" -> "B 站"
    "web" -> "网页"
    "doc" -> "文档"
    else -> "其它"
}

/** 阶段进度的一行：阶段名 + done/total + 3dp 进度条；点一下跳到该阶段第一周。 */
@Composable
private fun StageProgressRow(stage: StageView, onClick: () -> Unit) {
    val fraction = if (stage.total > 0) {
        (stage.done.toFloat() / stage.total.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .heightIn(min = Touch)
            .padding(vertical = Gap.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stage.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stageProgressLabel(stage),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stage.total > 0) {
            Spacer(Modifier.height(5.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** 有单元时「done/total」，还没排单元时退化成周区间（桌面同语义）。 */
private fun stageProgressLabel(stage: StageView): String =
    if (stage.total > 0) {
        stage.done.toString() + "/" + stage.total
    } else {
        "W" + stage.weekStart.takeLast(2) + "–" + stage.weekEnd.takeLast(2)
    }

/** 阶段 → 周卡：区间内第一张 → 区间之后第一张 → 最后一张（桌面 `stageFirstPlanWeek` 同语义）。 */
private fun stageFirstPlanWeek(cards: List<PlanWeekCard>, stage: StageView): String? =
    cards.firstOrNull { it.week >= stage.weekStart && it.week <= stage.weekEnd }?.week
        ?: cards.firstOrNull { it.week >= stage.weekStart }?.week
        ?: cards.lastOrNull()?.week
