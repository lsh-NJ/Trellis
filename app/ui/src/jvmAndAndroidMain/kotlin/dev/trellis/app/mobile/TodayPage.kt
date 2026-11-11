package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.domain.Task
import dev.trellis.domain.TaskStatus
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.SlotArea
import kotlinx.coroutines.launch

/**
 * 今日：一屏回答「现在该做什么」。
 *
 * 排序是行动顺序，不是结构：逾期 → 今天 → 之后 → 没有日期 → 已完成的。
 * 计划分组在「计划」页看，这里不重复。
 */
@Composable
fun TodayPage(runtime: AppRuntime) {
    val scope = rememberCoroutineScope()
    val now = runtime.clock.nowMs()
    val today = runtime.clock.today()
    val startToday = startOfDayMs(today)
    val endToday = startOfDayMs(today.plusDays(1))

    val open = runtime.tasks.filter { !it.isDone }
    val overdue = open.filter { it.dueAt != null && it.dueAt!! < now }
    val dueToday = open.filter { it.dueAt != null && it.dueAt!! in now until endToday }
    val later = open.filter { it.dueAt != null && it.dueAt!! >= endToday }
    val someday = open.filter { it.dueAt == null }
    val doneToday = runtime.tasks.filter { it.isDone && (it.completedAt ?: 0) >= startToday }
    val doneBefore = runtime.tasks.filter { it.isDone && (it.completedAt ?: 0) < startToday }

    var draft by remember { mutableStateOf("") }
    var sheetTaskId by remember { mutableStateOf<String?>(null) }
    val planNames = remember(runtime.planAll) { runtime.planAll.associate { it.id to it.title } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = Gap.l, end = Gap.l, top = Gap.s, bottom = Gap.xxl),
        verticalArrangement = Arrangement.spacedBy(Gap.m),
    ) {
        item {
            PageHead(
                title = "今天",
                subtitle = weekdayText(today) + " · 还有 " + open.size + " 件没做",
                trailing = runtime.summary.streakDays.takeIf { it > 0 }?.let {
                    { Pill("连续 $it 天", Tone.Good) }
                },
            )
        }

        // 模拟时间：拨过日期就必须让人一眼看见，否则截图、演示、真实使用都会分不清「今天」是哪天。
        if (runtime.simulatedToday != null) {
            item { SimulatedBanner(runtime) }
        }

        // 插件卡片：首页顶部与分区贡献。手机不砍这块，否则「插件化」在手机上就只剩个设置页。
        item {
            SlotArea(
                runtime.kernel.extensions,
                Slots.HomeHeader,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.HomeHeader.id),
            )
        }

        // 本周进度：放在插件头卡之后、任务列表之前 —— 先回答「这周推到哪了」，
        // 再回答「现在该做什么」。没有计划时卡片自己给一句引导，不消失。
        item { WeekCard(runtime) }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PhoneField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = "加一件要做的事",
                    )
                }
                Spacer(Modifier.width(Gap.s))
                PhoneButton(
                    label = "加入",
                    enabled = draft.isNotBlank(),
                    onClick = {
                        val text = draft
                        draft = ""
                        scope.launch { runtime.addTask(text) }
                    },
                )
            }
        }

        if (overdue.isNotEmpty()) {
            item { GroupHead("逾期", overdue.size, warn = true) }
            overdue.forEach { task ->
                item(key = "o" + task.id.value) {
                    TaskRow(runtime, task, planNames[task.planId?.value], overdue = true) { sheetTaskId = task.id.value }
                }
            }
        }

        if (dueToday.isEmpty() && overdue.isEmpty()) {
            item {
                EmptyState(
                    if (runtime.tasks.isEmpty()) "还没有任务" else "今天没有排定的事",
                    "在上面加一条，或者去「计划」里排",
                )
            }
        }

        if (dueToday.isNotEmpty()) {
            item { GroupHead("今天", dueToday.size) }
            dueToday.forEach { task ->
                item(key = "t" + task.id.value) {
                    TaskRow(runtime, task, planNames[task.planId?.value]) { sheetTaskId = task.id.value }
                }
            }
        }

        if (later.isNotEmpty()) {
            item { GroupHead("之后", later.size) }
            later.forEach { task ->
                item(key = "l" + task.id.value) {
                    TaskRow(runtime, task, planNames[task.planId?.value]) { sheetTaskId = task.id.value }
                }
            }
        }

        if (someday.isNotEmpty()) {
            item { GroupHead("没有日期", someday.size) }
            someday.forEach { task ->
                item(key = "s" + task.id.value) {
                    TaskRow(runtime, task, planNames[task.planId?.value]) { sheetTaskId = task.id.value }
                }
            }
        }

        if (doneToday.isNotEmpty()) {
            item { GroupHead("今天完成", doneToday.size) }
            doneToday.forEach { task ->
                item(key = "d" + task.id.value) {
                    TaskRow(runtime, task, planNames[task.planId?.value], done = true) { sheetTaskId = task.id.value }
                }
            }
        }

        if (doneBefore.isNotEmpty()) {
            item { GroupHead("更早完成", doneBefore.size) }
            doneBefore.take(20).forEach { task ->
                item(key = "e" + task.id.value) {
                    TaskRow(runtime, task, planNames[task.planId?.value], done = true) { sheetTaskId = task.id.value }
                }
            }
        }

        item {
            SlotArea(
                runtime.kernel.extensions,
                Slots.HomeSection,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.HomeSection.id),
            )
        }
    }

    val sheetTask = runtime.tasks.firstOrNull { it.id.value == sheetTaskId }
    if (sheetTask != null) {
        TaskDetailSheet(
            runtime = runtime,
            task = sheetTask,
            planTitle = planNames[sheetTask.planId?.value],
            onDismiss = { sheetTaskId = null },
        )
    }
}

/**
 * 「本周」卡：手机单列版的周进度。
 *
 * 数字全部取自运行时已有的周视图，不在页面里重新算：
 * `planWeekProgress` 由 refreshPlan 从后端当前周算好（本周 done/total、全部剩余、
 * 已完成预估分钟）；实际投入按活动归因现取（`planWeekAttributedMinutes()`），
 * 取不到就显示「—」，不拿 0 分钟冒充「没学」。
 */
@Composable
private fun WeekCard(runtime: AppRuntime) {
    val progress = runtime.planWeekProgress
    var investedMinutes by remember(runtime.planDays, runtime.planSelectedWeek) {
        mutableStateOf<Long?>(null)
    }
    // 计划数据本来只在进「计划」页时才开库（PlanPage.kt 的进页 LaunchedEffect）；
    // 首页要显示周进度，就得在这里补一次。只补一次：读到了就不再调，不改变别处的懒加载。
    LaunchedEffect(Unit) {
        if (runtime.planWeekProgress == null) runCatching { runtime.refreshPlan() }
    }
    // 活动归因要读库，是 suspend；跟着计划周变化重新取一次。
    LaunchedEffect(runtime.planDays, runtime.planSelectedWeek) {
        investedMinutes = runCatching { runtime.planWeekAttributedMinutes() }.getOrNull()
    }

    CardBox {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "本周",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (progress != null) Pill(progress.label)
        }
        Spacer(Modifier.height(Gap.s))
        if (progress == null) {
            Text(
                "还没有学习计划。去「对话」里说想学什么，管家会把周次和单元排好。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CardBox
        }
        val invested = investedMinutes
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.l)) {
            BigStat(
                value = progress.done.toString() + "/" + progress.total,
                label = "本周节点",
                modifier = Modifier.weight(1f),
            )
            BigStat(
                value = invested?.let { minutesLabel(it.toInt()) } ?: "—",
                label = "已投入",
                modifier = Modifier.weight(1f),
                hint = if (invested != null && invested > 0) "屏幕活动归因" else null,
            )
        }
        Spacer(Modifier.height(Gap.s))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        ) {
            if (progress.progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.progress)
                        .height(6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Spacer(Modifier.height(Gap.s))
        val meta = buildList {
            add(if (progress.allRemaining > 0) "全部还剩 " + progress.allRemaining + " 件" else "全部做完")
            if (progress.plannedMinutes > 0) add("已完成预估 " + progress.plannedMinutes + " 分钟")
        }.joinToString(" · ")
        Text(
            meta,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PageHead(title: String, subtitle: String? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Gap.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/** 模拟时间徽章：拨过日期就固定在首页最上面，带两个最常用的跳转。 */
@Composable
fun SimulatedBanner(runtime: AppRuntime) {
    val simulated = runtime.simulatedToday ?: return
    CardBox(padding = Gap.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("模拟时间", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(2.dp))
                Text(
                    simulated.toString() + "（真实 " + java.time.LocalDate.now() + "）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PhoneButton("+1 天", { runtime.jumpSimulatedDays(1) }, filled = false)
            Spacer(Modifier.width(Gap.xs))
            PhoneButton("+1 周", { runtime.jumpSimulatedWeeks(1) }, filled = false)
        }
        Spacer(Modifier.height(Gap.xs))
        Text(
            "拨动后计划会立刻按新日期结算/补周；活动与计时器仍是真实数据。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun GroupHead(title: String, count: Int, warn: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(top = Gap.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Gap.s))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = (if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                .copy(alpha = 0.6f),
        )
        Spacer(Modifier.width(Gap.m))
        Box(Modifier.weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)))
    }
}

@Composable
private fun TaskRow(
    runtime: AppRuntime,
    task: Task,
    planTitle: String?,
    done: Boolean = false,
    overdue: Boolean = false,
    onOpen: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = done) {
            scope.launch {
                runtime.toggle(task)
            }
        }
        Spacer(Modifier.width(Gap.s))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            val meta = buildList {
                if (planTitle != null) add(planTitle)
                if (!done) {
                    if (overdue && task.dueAt != null) add(overdueLabel(task.dueAt!!, runtime.clock.nowMs()))
                    else add(dueLabel(task.dueAt, runtime.clock.nowMs()))
                }
                if (task.spentMinutes > 0) add(minutesLabel(task.spentMinutes))
            }.joinToString(" · ")
            Spacer(Modifier.height(3.dp))
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (task.status == TaskStatus.DOING) Pill("进行中", Tone.Good)
    }
}

/**
 * 任务详情：底部弹层。
 *
 * 手机上没有右键、没有悬停，所以「改这件事」必须有一个明确的落点 ——
 * 整行可点，动作收进弹层，而不是在行里塞五个小图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailSheet(
    runtime: AppRuntime,
    task: Task,
    planTitle: String?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var note by remember(task.id.value) { mutableStateOf(task.note) }
    var editingTitle by remember(task.id.value) { mutableStateOf(task.title) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
            Text("任务", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(Gap.s))
            PhoneField(
                value = editingTitle,
                onValueChange = { editingTitle = it },
                singleLine = false,
                minLines = 1,
            )
            Spacer(Modifier.height(Gap.s))
            Text(
                buildList {
                    if (planTitle != null) add(planTitle)
                    add(dueLabel(task.dueAt, runtime.clock.nowMs()))
                    add(minutesLabel(task.spentMinutes) + " 已投入")
                }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Gap.l))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                PhoneButton(
                    if (task.isDone) "标为未完成" else "完成",
                    {
                        scope.launch {
                            runtime.toggle(task)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (!task.isDone) {
                    PhoneButton("开始计时", {
                        // 按钮写的是「开始」，那就真的开始：
                        // 只调 openTimer 的话，屏幕上会出现一根 0:00 的悬浮条，
                        // 而计时其实没跑（要用的人得再点开、再点「继续」）。
                        runtime.openTimer(task)
                        runtime.startTimer()
                        onDismiss()
                    }, modifier = Modifier.weight(1f), filled = false)
                }
            }

            Spacer(Modifier.height(Gap.m))
            SectionLabel("记一笔投入")
            Spacer(Modifier.height(Gap.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                listOf(15, 25, 45).forEach { minutes ->
                    PhoneButton("+$minutes 分", {
                        scope.launch {
                            runtime.logTime(task, minutes)
                        }
                    }, modifier = Modifier.weight(1f), filled = false)
                }
            }

            Spacer(Modifier.height(Gap.m))
            SectionLabel("排到别的日子")
            Spacer(Modifier.height(Gap.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                PhoneButton("今天", {
                    scope.launch { setDue(runtime, task, startOfDayMs(runtime.clock.today())); onDismiss() }
                }, modifier = Modifier.weight(1f), filled = false)
                PhoneButton("明天", {
                    scope.launch { setDue(runtime, task, startOfDayMs(runtime.clock.today().plusDays(1))); onDismiss() }
                }, modifier = Modifier.weight(1f), filled = false)
                PhoneButton("下周", {
                    scope.launch { setDue(runtime, task, startOfDayMs(runtime.clock.today().plusDays(7))); onDismiss() }
                }, modifier = Modifier.weight(1f), filled = false)
                PhoneButton("清空", {
                    scope.launch {
                        runtime.planService.save(task.copy(dueAt = null))
                        runtime.refresh()
                        onDismiss()
                    }
                }, modifier = Modifier.weight(1f), filled = false)
            }

            Spacer(Modifier.height(Gap.m))
            SectionLabel("做完之后的总结")
            Spacer(Modifier.height(Gap.s))
            PhoneField(
                value = note,
                onValueChange = { note = it },
                placeholder = "写一句：卡在哪、下次怎么更快",
                singleLine = false,
                minLines = 3,
            )
            Spacer(Modifier.height(Gap.s))
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                PhoneButton("保存", {
                    scope.launch {
                        runtime.planService.summarize(task.id, note)
                        if (editingTitle.trim() != task.title && editingTitle.isNotBlank()) {
                            runtime.planService.save(task.copy(title = editingTitle.trim()))
                        }
                        runtime.refresh()
                        onDismiss()
                    }
                }, modifier = Modifier.weight(1f), filled = false)
                PhoneButton("删除", {
                    scope.launch {
                        runtime.delete(task)
                        onDismiss()
                    }
                }, modifier = Modifier.weight(1f), filled = false, danger = true)
            }
        }
    }
}

/** 改截止时间：走领域契约整条保存，不碰存储细节。 */
private suspend fun setDue(runtime: AppRuntime, task: Task, dueAt: Long?) {
    runtime.planService.save(task.copy(dueAt = dueAt))
    runtime.refresh()
}
