package dev.trellis.app.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import java.time.LocalDate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.ui.LocalCardStyle
import kotlinx.coroutines.launch

/** 单元类型的界面后缀：学习 / 实践 / 复习。 */
internal fun unitKindLabel(kind: String): String = when (kind) {
    "practice" -> "实践"
    "review" -> "复习"
    else -> "学习"
}

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

/**
 * 计划页：上面是整个计划（一周一格刻度），下面是选中那一周的七天折叠卡。
 *
 * 这一页只做三件事：把后端算好的 view model 摆开、把「选了哪周哪天」记在运行时、把勾选转发给命令。
 * 周次怎么切、还剩几件、哪条该顺延——都是 `:features:learning-process` 算的，页面里不做四则运算。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PlanScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    // 进页面才开库：不进计划页的人不该为 SQLite 付启动时间。
    LaunchedEffect(Unit) {
        runtime.refreshPlan()
        runtime.refreshAutoCompletions()
    }
    val dragController = remember { PlanDragController() }
    // 抽屉打开时把内容整块让开：日期格（尤其周五～周日）不能被抽屉盖住，否则拖不过去。
    val panelWidth by animateDpAsState(
        targetValue = if (runtime.planRearrangeOpen) 296.dp else 0.dp,
        animationSpec = tween(durationMillis = 220),
        label = "rearrange-panel-width",
    )

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { coordinates -> rootOrigin = coordinates.positionInRoot() },
    ) {
        // 右栏的显示条件看**窗口**宽度：本页被外壳限宽成 1120dp（还得减左右各 20dp 内边距），
        // 1440pt 的窗口里 maxWidth 最多 1080dp，拿页面宽度判永远够不到 medium 断点。
        val windowWidthDp = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.width.toDp().value
        }
        // 抽屉是不是「盖在内容上」：只有窗口真的放不下 296 抽屉 + 一列可读的日卡时才覆盖，
        // 否则把内容列让开——不然宽屏也会盖住右侧阶段栏和周末的日期格，拖不过去。
        val drawerOverlays = windowWidthDp < 1280f
        Row(Modifier.fillMaxSize().padding(end = if (drawerOverlays) 0.dp else panelWidth)) {
            PlanList(runtime)
            ListSidebarSplitter()
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 页头：计划页此前没有页标题，左边 232dp 的列表里孤零零一个「计划」。
                // 计划级动作（重排 / 计划设置）从周卡标题行搬到页头右上角，和周卡内容解耦。
                item { PlanHeader(runtime) }
                // 错误提示条：不随 refreshPlan 消失，用户点「知道了」才收。
                runtime.planNotice?.let { notice -> item { PlanErrorBanner(notice) { runtime.dismissPlanNotice() } } }
                if (runtime.isSimulatingTime) item { PlanSimulatedBanner(runtime) }
                if (runtime.planRhythmHasNews) item { PlanRhythmCard(runtime) }

                if (runtime.planTitle == null) {
                    item { PlanEmpty(runtime) }
                } else {
                    // 先「阶段」再「周刻度」：整份计划按大纲分几段，段里再挑周。
                    item { PlanStageStrip(runtime) }
                    item { PlanWeekTicker(runtime) }
                    if (runtime.planPendingSelected) {
                        item { PendingDetail(runtime) }
                    } else {
                        item { WeekDetail(runtime, dragController) }
                        if (runtime.planAutoCompletions.isNotEmpty() || runtime.planAutoCompletionBlockedCount > 0) {
                            item { PlanAutoCompletionCard(runtime) }
                        }
                    }
                }
            }
            // 宽屏右栏：阶段进度常驻。抽屉打开时先让位——296 抽屉 + 232 列表 + 196 右栏
            // 会把中间内容列压成一列字（现场复现过「计划」一字一行）；收起抽屉再回来。
            if (!runtime.planRearrangeOpen && shouldShowStageRail(windowWidthDp, runtime.planStages.size)) {
                PlanStageRail(runtime)
            }
        }
        AnimatedVisibility(
            visible = runtime.planRearrangeOpen,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PlanRearrangeDrawer(runtime, dragController)
        }
        PlanDragGhost(dragController, rootOrigin)
    }
    if (runtime.planSettingsOpen) PlanSettingsDialog(runtime)
}

/** 左侧计划列表：和对话页/知识库共用 `ListSidebar` 骨架（贴边、可拖宽、同一套行样式）。 */
@Composable
private fun PlanList(runtime: AppRuntime) {
    val visible = runtime.planAll.filter { it.status != "ended" }
    val grouped = visible.groupBy { runtime.planCategories[it.id] ?: "未分类" }
    ListSidebar(
        title = "学习计划",
        count = visible.size,
        actionLabel = "",
        onAction = null,
    ) {
        Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState())) {
            grouped.forEach { (category, plans) ->
                Text(
                    category,
                    style = LocalTrellisTokens.current.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                )
                plans.forEach { plan ->
                    PlanSidebarRow(
                        title = plan.title,
                        sub = when {
                            // 放弃的计划还留在左栏（历史还能回来看），但要和进行中的一眼分开。
                            plan.status == "abandoned" -> "已放弃"
                            plan.id == runtime.planFocusId -> "进行中"
                            plan.status == "draft" -> "草稿"
                            else -> "已排"
                        },
                        selected = runtime.planFocusId == plan.id,
                        muted = plan.status == "abandoned",
                        onClick = { runtime.selectPlan(plan.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlanSidebarRow(
    title: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
    muted: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tokens = LocalTrellisTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction)
            .listRowSurface(selected, hovered)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListRowSelectionBar(selected)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (muted) 0.6f else 0.8f),
                )
            }
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
    val tokens = LocalTrellisTokens.current
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "计划节奏",
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                ChromeTextLink("知道了", onClick = { runtime.dismissPlanRhythmReport() })
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
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「AI 自动完成」：笔记整理后按知识点判断、自动勾掉的计划节点。
 *
 * 放在「当天安排」下面：这些勾是机器改的，得让人看得见、能撤销；
 * 撤销过的节点会进屏蔽名单，以后不再被自动完成（卡片右上角可一次性解除）。
 */
@Composable
private fun PlanAutoCompletionCard(runtime: AppRuntime) {
    val entries = runtime.planAutoCompletions
    if (entries.isEmpty() && runtime.planAutoCompletionBlockedCount == 0) return
    val tokens = LocalTrellisTokens.current
    var expanded by remember { mutableStateOf(false) }
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AI 自动完成",
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    " " + entries.size,
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (runtime.planAutoCompletionBlockedCount > 0) {
                    ChromeTextLink(
                        label = "解除屏蔽（" + runtime.planAutoCompletionBlockedCount + "）",
                        onClick = { runtime.clearAutoCompletionBlocks() },
                    )
                }
            }
            if (entries.isEmpty()) {
                Text(
                    "笔记里讲到本周节点时，AI 整理完会自动勾上并记在这里。现在没有记录。",
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val shown = if (expanded) entries else entries.take(5)
                shown.forEach { entry -> AutoCompletionRow(runtime, entry) }
                if (entries.size > 5) {
                    ChromeTextLink(
                        label = if (expanded) "收起" else "展开全部 " + entries.size + " 条",
                        onClick = { expanded = !expanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun AutoCompletionRow(runtime: AppRuntime, entry: AutoCompletionEntry) {
    var showEvidence by remember(entry.id) { mutableStateOf(false) }
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✔", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            ChromeTextLink("撤销", onClick = { runtime.undoAutoCompletion(entry.id) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        entry.day.ifBlank { "未标注日期" },
                        style = tokens.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "来自《" + entry.noteTitle.ifBlank { "未命名笔记" } + "》",
                        style = tokens.type.control,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { runtime.openAutoCompletionNote(entry.id) },
                    )
                }
                if (entry.chapter.isNotBlank()) {
                    Text(
                        entry.chapter,
                        style = tokens.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (entry.evidence.isNotBlank()) {
                Text(
                    if (showEvidence) "收起证据" else "看证据",
                    style = tokens.type.control,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { showEvidence = !showEvidence },
                )
            }
        }
        if (showEvidence && entry.evidence.isNotBlank()) {
            Text(
                "“" + entry.evidence + "”",
                style = tokens.type.meta,
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
    val tokens = LocalTrellisTokens.current
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
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (runtime.devClockAllowed) {
                ChromeButton(label = "+1 天", onClick = { runtime.jumpSimulatedDays(1) })
                Spacer(Modifier.width(6.dp))
                ChromeButton(label = "+1 周", onClick = { runtime.jumpSimulatedWeeks(1) })
                Spacer(Modifier.width(6.dp))
                ChromeTextLink("退出模拟", onClick = { runtime.applySimulatedToday(null) })
            }
        }
    }
}

/**
 * 计划页错误提示条：失败原因在页面上停住，不被 `refreshPlan` 的清理和弹窗的 scrim 吞掉。
 *
 * 计划页以前只有周条里那一行 `planMessage`：它 maxLines=1、每次刷新都清空，
 * 弹窗里的保存失败也被弹窗盖住——用户看到的就是「点了保存，什么也没发生」。
 */
@Composable
private fun PlanErrorBanner(message: String, onDismiss: () -> Unit) {
    val tokens = LocalTrellisTokens.current
    PlanCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                style = tokens.type.body,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            ChromeTextLink("知道了", onClick = onDismiss)
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
    var confirmAbandon by remember { mutableStateOf(false) }
    val planTitle = runtime.planTitle ?: ""
    AlertDialog(
        onDismissRequest = { runtime.closePlanSettings() },
        title = { Text("计划设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "只改当前这个计划（" + planTitle + "）；换一个计划就是另一套。",
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
                        ChromeTextLink(minutes.toString() + "′", onClick = { runtime.choosePlanDailyDraft(minutes) })
                    }
                }
                Text(
                    "排程按这个数往一天里塞节点；已经排好的天不会自动重排，只影响之后铺的天数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                runtime.planSettingsError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // 危险区放在最后、和上面的「改数字」隔开：放弃是这一页唯一不可撤销的动作。
                Spacer(Modifier.height(4.dp))
                Text(
                    "放弃这个计划",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "放弃后计划会结束：排好的节点不再出现，也不会再自动排周。左栏会留一行灰色「已放弃」，之后还能回来看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ChromeButton(
                    label = "放弃这个计划",
                    filled = false,
                    destructive = true,
                    onClick = { confirmAbandon = true },
                )
            }
        },
        confirmButton = {
            ChromeButton(label = "保存", onClick = { runtime.savePlanDailyMinutes() })
        },
        dismissButton = {
            ChromeTextLink("取消", onClick = { runtime.closePlanSettings() })
        },
    )
    if (confirmAbandon) {
        AlertDialog(
            onDismissRequest = { confirmAbandon = false },
            title = { Text("放弃「" + planTitle + "」？") },
            text = {
                Text(
                    "已经排好的节点不会消失，只是不再排在待办里、也不会再自动排周。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                ChromeButton(
                    label = "确认放弃",
                    destructive = true,
                    onClick = {
                        confirmAbandon = false
                        runtime.planFocusId?.let { runtime.abandonPlan(it) }
                    },
                )
            },
            dismissButton = {
                ChromeTextLink("再想想", onClick = { confirmAbandon = false })
            },
        )
    }
}

/** 计划页页头：标题 + 当前计划名 + 计划级动作。 */
@Composable
private fun PlanHeader(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val focusedAbandoned = runtime.planAll
        .firstOrNull { it.id == runtime.planFocusId }
        ?.status == "abandoned"
    ChromePageHeader(
        title = "计划",
        subtitle = (runtime.planTitle ?: "还没有计划") + if (focusedAbandoned) " · 已放弃" else "",
        actions = {
            if (runtime.planTitle != null) {
                val activeGoals = runtime.goals.count { it.status == "active" }
                ChromeTextLink(
                    if (activeGoals > 0) "目标 · $activeGoals" else "目标",
                    onClick = { runtime.openGoalScreen() },
                )
                Spacer(Modifier.width(tokens.space.s))
                ChromeTextLink("计划文档", onClick = { runtime.openPlanDocs(runtime.planFocusId) })
                Spacer(Modifier.width(tokens.space.s))
                ChromeTextLink(
                    label = when {
                        runtime.planRearrangeOpen -> "收起重排"
                        runtime.planRearrangeCount > 0 -> "重排 · " + runtime.planRearrangeCount
                        else -> "重排"
                    },
                    onClick = { runtime.togglePlanRearrange() },
                )
                Spacer(Modifier.width(tokens.space.s))
                ChromeTextLink("计划设置", onClick = { runtime.openPlanSettings() })
            }
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
            Spacer(Modifier.height(6.dp))
            // 空态必须有一个动作：原来只有两句说明，看完了也不知道下一步点哪。
            ChromeButton(
                label = "去对话里安排",
                filled = false,
                onClick = { runtime.currentTab = TAB_CHAT },
            )
        }
    }
}

/**
 * 「待安排」详情：把整个集合平铺出来（带预计分钟），按章节分组。
 *
 * 这里不排程、不勾选：这些节点还没落到某一天，勾选得先等它被排进某周某天。
 */
@Composable
private fun PendingDetail(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
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
                    style = tokens.type.meta,
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
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            runtime.planPendingNodes.groupBy { it.chapter.ifBlank { "未分章" } }.forEach { (chapter, units) ->
                Text(
                    chapter,
                    style = tokens.type.meta,
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
                            unit.estimateMinutes.toString() + " 分钟 · " + unitKindLabel(unit.kind),
                            style = tokens.type.caption,
                            fontFamily = AppFonts.mono,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 下半：选中周里的七天，一天一张折叠卡（今天默认摊开）。 */
@Composable
private fun WeekDetail(runtime: AppRuntime, controller: PlanDragController) {
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlanDayAccordion(runtime, controller)
            PlanReviewSection(runtime)
        }
    }
}

/**
 * 周复盘：本周结束时由系统生成（`advanceRhythm`），也可以在欠着的时候手动补一份。
 *
 * 结构完全按后端 `ReviewView` 的字段摆：narrative 是给人看的那段话，facts 是数字
 * （完成/逾期/投入），question 是要用户回答的问题，suggestion 是「要不要调整」的建议。
 *
 * 另一种时机是**周日当天**：这一周还没结束，但已经是最后一天，后端 `reviewableWeek` 会
 * 把本周交出来，这里就多一个「生成本周总结」按钮——不用等到周一被系统追着补。
 */
@Composable
private fun PlanReviewSection(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val pendingWeek = runtime.planPendingReviewWeek
    val review = runtime.planReview
    val selectedWeek = runtime.planSelectedWeek
    val reviewableWeek = runtime.planReviewableWeek
    // 只对「选中的就是这一周」开放：在别的周上看复盘时不该冒出一个总结本周的按钮。
    val canGenerateThisWeek = reviewableWeek != null && reviewableWeek == selectedWeek && review == null
    if (pendingWeek == null && review == null && !canGenerateThisWeek) return
    val scope = rememberCoroutineScope()
    PlanCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (canGenerateThisWeek) "本周总结" else "周复盘",
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (pendingWeek != null) {
                    ChromeTextLink("生成复盘", onClick = { runtime.generatePlanReview() })
                }
            }
            if (review != null) {
                Text(review.narrative, style = MaterialTheme.typography.bodyMedium)
                if (review.facts.isNotEmpty()) {
                    review.facts.forEach { fact ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                fact.label,
                                style = tokens.type.meta,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(fact.value, style = tokens.type.meta)
                        }
                    }
                }
                if (review.question.isNotBlank()) {
                    Text(
                        review.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                review.suggestion?.let { suggestion ->
                    Text(
                        "建议：" + suggestion.action +
                            (if (suggestion.cost.isBlank()) "" else "（代价：" + suggestion.cost + "）") +
                            (if (suggestion.expectedEffect.isBlank()) "" else "，预期：" + suggestion.expectedEffect),
                        style = tokens.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (pendingWeek != null) {
                Text(
                    pendingWeek + " 已经结束，复盘还没生成。点「生成复盘」补一份。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else if (canGenerateThisWeek) {
                Text(
                    "今天是这周最后一天：不用等周一，先把这一周的总结做了。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ChromeButton(
                    label = "生成本周总结",
                    filled = false,
                    onClick = {
                        selectedWeek?.let { week ->
                            scope.launch { runtime.generateWeekReview(week) }
                        }
                    },
                )
            }
        }
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
