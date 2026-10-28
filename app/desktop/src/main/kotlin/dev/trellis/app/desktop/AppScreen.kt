package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.domain.Task
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.LocalCardStyle
import dev.trellis.ui.LocalExtraColors
import dev.trellis.ui.SlotArea
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppScreen(runtime: AppRuntime) {
    val scope = rememberCoroutineScope()

    // 每秒刷一次。
    //
    // 不是最终形态：真实产品里应该由数据变更通知驱动（领域层发 Flow，界面订阅）。
    // 先轮询是因为插件也能改数据（比如卡片上的按钮），而「变更通知」
    // 是领域层要新增的一项能力，不在这一轮范围内。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            runtime.refresh()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // 左栏贴着窗口边，内容列限宽 1120 居中：
        // 宽屏上把内容拉满（2500px）时，KPI 之间会空出 500px——那不是留白，是没填满。
        val navLeft = runtime.uiPrefs.nav == "left"
        Row(Modifier.fillMaxSize().padding(vertical = 16.dp)) {
            if (navLeft) {
                NavRail(runtime)
            }
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 1120.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .weight(1f),
                ) {
                    AppHeader(runtime)

                    Spacer(Modifier.height(12.dp))

                    // 审批条长在最上面：它是「规则要动手了」的唯一信号。
                    // 藏进弹窗或者标签页里，用户就会以为什么都没发生。
                    AskDialogs(runtime)

                    // 导航放哪边是宿主样式（uiPrefs.nav），和配色/密度一样可调：
                    // 宽屏上左栏比顶栏省纵向空间，窄屏上顶栏更省横向空间。
                    if (!navLeft) {
                        TabBar(runtime)
                        Spacer(Modifier.height(16.dp))
                    }
                    TabContent(runtime, Modifier.weight(1f))
                }
            }
        }

        // 全局浮层：插件可以往这里挂东西（倒计时、提示条），浮在所有页面之上
        Box(Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
            SlotArea(
                runtime.kernel.extensions,
                Slots.AppOverlay,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.AppOverlay.id),
            )
        }
    }
}

@Composable
private fun AppHeader(runtime: AppRuntime) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // 品牌块：样片里的 logo 是一块实心色 + 一个字，这里用主色方块 + 「T」。
        Box(
            modifier = Modifier.size(30.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "T",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.width(12.dp))
        // 两行「刊头」：上面一行等宽小标是刊名，下面一行才是当前计划 ——
        // 计划名是这页唯一需要被一眼读到的字，所以它独占一行、可以省略号截断。
        Column(Modifier.weight(1f)) {
            Text(
                "TRELLIS · 任务台",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = muted,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                runtime.goal?.title ?: "还没有计划",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(16.dp))
        val s = runtime.summary
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(
                text = if (s.overdue > 0) "逾期 " + s.overdue + " 条" else "剩 " + (s.total - s.done) + " 条",
                background = MaterialTheme.colorScheme.primaryContainer,
                foreground = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            // 内核状态：点 + 等宽小标。点用主色，不用红绿 —— 它不是告警，只是「在跑」。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "内核 " + runtime.kernel.currentState,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = AppFonts.mono,
                    color = muted,
                )
            }
        }
    }
}

/** 小圆角硬边小牌：倒计时、状态、delta 都用它。 */
@Composable
private fun Pill(text: String, background: Color, foreground: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(3.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = foreground)
    }
}

/** 导航区域的底色，顶栏和左栏共用一套，免得同一个偏好只在一种形态生效。 */
@Composable
private fun navContainerColor(runtime: AppRuntime): Color = when (runtime.uiPrefs.navTone) {
    "dark" -> Color(0xFF211D29)
    "accent" -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surface
}

/** 和底色成对的文字色：深色底要亮字，主色底要 onPrimaryContainer。 */
@Composable
private fun navContentColor(runtime: AppRuntime): Color = when (runtime.uiPrefs.navTone) {
    "dark" -> Color(0xFFEDE7F6)
    "accent" -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * 顶栏式导航。
 *
 * 不用 M3 的 [TabRow]：它的指示条是「铺满整个 tab 宽」的下划线 + 默认内边距，
 * 在这套「等宽小标 + 细线」的语言里会显得又软又飘 —— 一行 5 个 tab 铺满 1120px 时，
 * 指示条比文字长一倍，视觉重心落在空白上。
 * 这里自己画：等宽小标、悬停淡底、选中项一条和文字等宽的 2dp 实线，
 * 数字角标只在该页真的有时才出现（诊断页 0 个错误不该长出一个「0」）。
 */
@Composable
private fun TabBar(runtime: AppRuntime) {
    val background = navContainerColor(runtime)
    val foreground = navContentColor(runtime)
    val divider = MaterialTheme.colorScheme.outlineVariant
    val tabs = listOf(
        NavTab(TAB_TASKS, "任务", null),
        NavTab(TAB_PLAN, "计划", null),
        NavTab(TAB_CHAT, if (runtime.chatBusy) "对话 ···" else "对话", null),
        NavTab(TAB_PLUGINS, "插件", runtime.pluginInfos.size.takeIf { it > 0 }),
        NavTab(TAB_DIAG, "诊断", runtime.kernelErrors.size.takeIf { it > 0 }),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabEntry(
                label = tab.label,
                badge = tab.badge,
                selected = runtime.currentTab == tab.id,
                foreground = foreground,
                onClick = { runtime.currentTab = tab.id },
            )
        }
    }
}

private data class NavTab(val id: Int, val label: String, val badge: Int?)

/**
 * 单个顶栏 tab。
 *
 * 选中态同时给三样东西：更深的字色、加粗、以及和文字等宽的实线下划线 ——
 * 只给颜色的话，在低对比度主题下「现在在哪一页」会消失。
 */
@Composable
private fun TabEntry(
    label: String,
    badge: Int?,
    selected: Boolean,
    foreground: Color,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val muted = foreground.copy(alpha = 0.58f)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
            .background(if (hovered && !selected) foreground.copy(alpha = 0.045f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 12.dp)
            // 收成内容宽：不然下面那条 fillMaxWidth 的指示线会把整个 tab 撑满，
            // 五个 tab 各占满一行，导航就只剩第一个露在外面。
            .width(IntrinsicSize.Max),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontFamily = AppFonts.mono,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) foreground else muted,
            )
            if (badge != null) {
                Spacer(Modifier.width(5.dp))
                Text(
                    badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = AppFonts.mono,
                    color = if (selected) foreground.copy(alpha = 0.7f) else muted.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/**
 * 左栏式导航。
 *
 * 不复用 M3 的 NavigationRail：它自带胶囊选中态、56dp 图标位和固定内边距，
 * 换主题也只能换颜色 —— 而「Bento」这类风格要的是方角小项、等宽小标、88dp 窄栏。
 * 这里用 Column 自己画，仍然只取 MaterialTheme 的语义色，所以换主题/暗色自动跟随。
 */
@Composable
private fun NavRail(runtime: AppRuntime) {
    val background = navContainerColor(runtime)
    val foreground = navContentColor(runtime)
    val divider = MaterialTheme.colorScheme.outlineVariant
    val errorCount = runtime.kernelErrors.size
    Column(
        modifier = Modifier.fillMaxHeight().width(88.dp)
            .background(background)
            // 样片里左栏和内容之间是一条 1dp 的细线，不靠阴影分隔。
            .drawBehind {
                val x = size.width - 1.dp.toPx()
                drawLine(
                    color = divider,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            ChromeIconGlyph(
                ChromeIcon.Logo,
                MaterialTheme.colorScheme.onPrimaryContainer,
                Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        NavRailItem(runtime, ChromeIcon.Tasks, "任务", TAB_TASKS, foreground)
        NavRailItem(runtime, ChromeIcon.Plan, "计划", TAB_PLAN, foreground)
        NavRailItem(runtime, ChromeIcon.Chat, if (runtime.chatBusy) "对话 ···" else "对话", TAB_CHAT, foreground)
        NavRailItem(runtime, ChromeIcon.Plugins, "插件 " + runtime.pluginInfos.size, TAB_PLUGINS, foreground)
        NavRailItem(
            runtime,
            ChromeIcon.Diagnostics,
            if (errorCount == 0) "诊断" else "诊断 " + errorCount,
            TAB_DIAG,
            foreground,
        )
    }
}

@Composable
private fun NavRailItem(runtime: AppRuntime, icon: ChromeIcon, label: String, tab: Int, foreground: Color) {
    val selected = runtime.currentTab == tab
    // 导航底色是主色时，选中项再刷主色就看不见了 —— 换成 primary 打底、onPrimary 出字。
    val accentBackground = runtime.uiPrefs.navTone == "accent"
    val container = when {
        !selected -> Color.Transparent
        accentBackground -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        !selected -> foreground
        accentBackground -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(container)
            .clickable { runtime.currentTab = tab }
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ChromeIconGlyph(icon, content, Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

@Composable
private fun TabContent(runtime: AppRuntime, modifier: Modifier) {
    when (runtime.currentTab) {
        TAB_TASKS -> TasksTab(runtime, modifier)
        TAB_PLAN -> PlanScreen(runtime, modifier)
        TAB_CHAT -> ChatScreen(runtime, modifier)
        TAB_PLUGINS -> PluginsScreen(runtime, modifier)
        else -> DiagnosticsScreen(runtime, modifier)
    }
}

@Composable
private fun TasksTab(runtime: AppRuntime, modifier: Modifier) {
    // 输入框的焦点请求器放在 runtime 上：任务卡现在是插件贡献的，
    // 宿主这边按 Ctrl+K 也得能聚焦到同一个输入框。
    val taskFocus = runtime.taskFocus
    // Ctrl+K：不用鼠标也能直接开始写任务（评审里的快捷键项）。
    LazyColumn(
        modifier = modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K) {
                runCatching { taskFocus.requestFocus() }
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 页面顶部的横幅（插件可能会往这里放一句「今天还剩 3 条」）
        item {
            SlotArea(
                runtime.kernel.extensions,
                Slots.HomeHeader,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.HomeHeader.id),
            )
        }

        // 任务区（今日盘点 + 待办清单）也是插件贡献的卡片：
        // 由 dev.planui 这个内置插件注册到 HomeSection，agent 能像别的卡一样排序、改样式。
        item {
            SlotArea(
                runtime.kernel.extensions,
                Slots.HomeSection,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.HomeSection.id),
            )
        }

        // 报表挂载点：插件可以往这里放图表、趋势、复盘卡片。
        // 没有任何插件往这里放东西时整张卡不出现——空卡片比没有更难看。
        if (runtime.kernel.extensions.contributionsAt(Slots.ReportChart).isNotEmpty()) {
            item {
                BentoCard(kicker = "TREND", title = "近七天") {
                    SlotArea(
                        runtime.kernel.extensions,
                        Slots.ReportChart,
                        refreshKey = runtime.tick,
                        onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                        layout = runtime.layoutFor(Slots.ReportChart.id),
                    )
                }
            }
        }
    }
}

/** 房主画的外壳：硬边小圆角 + 细边 + 贴纸阴影，全部跟着卡片样式走。 */
@Composable
private fun BentoCard(
    kicker: String,
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    /** false：外面已经有一层宿主卡片（插件卡槽），这里只画内容，别再套一层壳。 */
    framed: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalCardStyle.current
    val shape = RoundedCornerShape(style.radius)
    val container = if (style.filled) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surface
    }
    val framedModifier = if (!framed) {
        modifier.fillMaxWidth()
    } else {
        modifier
            .fillMaxWidth()
            .then(if (style.hardShadow) Modifier.padding(end = 4.dp, bottom = 4.dp) else Modifier)
            .clip(shape)
            .background(container)
            .then(
                if (style.border) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                } else {
                    Modifier
                },
            )
            .padding(18.dp)
    }
    Column(modifier = framedModifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    kicker,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            trailing()
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** 任务区：一块 bento 卡片里放清单 + 输入框，空列表时让插件说一句话。 */
@Composable
internal fun TaskBoard(runtime: AppRuntime, taskFocus: FocusRequester, framed: Boolean = true) {
    val scope = rememberCoroutineScope()
    val tasks = runtime.tasks
    BentoCard(
        kicker = "TASKS",
        title = "待办",
        framed = framed,
        trailing = {
            Text(
                tasks.count { !it.isDone }.toString() + " 条未完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        if (tasks.isEmpty()) {
            Text("还没有任务，在下面加一条试试。", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            SlotArea(
                runtime.kernel.extensions,
                Slots.EmptyState,
                refreshKey = runtime.tick,
                onAction = { owner, action -> runtime.performSlotAction(owner, action) },
                layout = runtime.layoutFor(Slots.EmptyState.id),
            )
        } else {
            val now = System.currentTimeMillis()
            val startOfToday = run {
                val zone = java.time.ZoneId.systemDefault()
                java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                    .atStartOfDay(zone).toInstant().toEpochMilli()
            }
            val endOfToday = startOfToday + 86_400_000L
            // dueAt 是跨模块的 public 属性，Kotlin 不给智能转换；先读成本地值再比。
            fun due(task: Task): Long? = task.dueAt
            val groups = listOf(
                "逾期" to tasks.filter { !it.isDone && due(it)?.let { d -> d < startOfToday } == true },
                "今天" to tasks.filter {
                    !it.isDone && due(it)?.let { d -> d >= startOfToday && d < endOfToday } == true
                },
                "以后" to tasks.filter { !it.isDone && due(it)?.let { d -> d >= endOfToday } == true },
                "没有日期" to tasks.filter { !it.isDone && due(it) == null },
            )

            // 已完成默认展开：折叠是为了「翻旧账方便」，不是为了把任务藏起来。
            var doneOpen by remember { mutableStateOf(true) }
            val done = tasks.filter { it.isDone }

            var firstGroup = true
            groups.forEach { (label, groupTasks) ->
                if (groupTasks.isEmpty()) return@forEach
                if (!firstGroup) Spacer(Modifier.height(6.dp))
                firstGroup = false
                GroupLabel(label, groupTasks.size)
                groupTasks.forEach { task ->
                    RowDivider()
                    TaskRow(
                        task = task,
                        onToggle = { scope.launch { runtime.toggle(it) } },
                        onLog = { scope.launch { runtime.logTime(it, 25) } },
                        onDelete = { scope.launch { runtime.delete(it) } },
                    )
                }
            }

            if (done.isNotEmpty()) {
                if (!firstGroup) Spacer(Modifier.height(6.dp))
                GroupLabel("已完成 · " + done.size + if (doneOpen) "（收起）" else "（展开）", null) {
                    doneOpen = !doneOpen
                }
                if (doneOpen) {
                    done.forEach { task ->
                        RowDivider()
                        TaskRow(
                            task = task,
                            onToggle = { scope.launch { runtime.toggle(it) } },
                            onLog = { scope.launch { runtime.logTime(it, 25) } },
                            onDelete = { scope.launch { runtime.delete(it) } },
                        )
                    }
                }
            }
        }
        // 输入区和清单之间画一条细线：这一张卡里其实有两个区（看清单 / 写一条），
        // 之前只靠 14dp 的空隙，滚到长清单底部时输入框像是清单的一部分。
        Spacer(Modifier.height(12.dp))
        RowDivider()
        Spacer(Modifier.height(14.dp))
        AddTaskRow(runtime, taskFocus)

        // 删除撤销：删完给 5 秒，条子自己会消失；不弹确认框。
        runtime.pendingDelete?.let { pending ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ControlShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "已删除「" + pending.task.title + "」",
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { scope.launch { runtime.undoDelete() } }) {
                    Text("撤销")
                }
            }
        }
    }
}

/**
 * 行间分隔线。
 *
 * 不铺满整行：两侧各留 4dp 缩进，线就只在「内容区」里出现，
 * 卡片左右内边距不会因为一条线被切出一条硬边。
 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 分组小标：等宽大写 + 条数，像样片里每个卡片的 kicker。点了能折叠的会带下划线暗示。 */
@Composable
private fun GroupLabel(text: String, count: Int?, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = AppFonts.mono,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null) {
            Spacer(Modifier.width(7.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** 四个数字一条横排：等宽小标 + 大号窄体数字，这是样片最像「看板」的地方。 */
@Composable
internal fun Header(runtime: AppRuntime, framed: Boolean = true) {
    val s = runtime.summary
    val extra = LocalExtraColors.current
    val cells = listOf(
        Quad("待办", (s.total - s.done).toString(), "条", MaterialTheme.colorScheme.onSurface),
        Quad("今天到期", s.dueToday.toString(), "条", MaterialTheme.colorScheme.onSurface),
        // 「逾期」是唯一需要被追问的数字，用语义红；但 0 条逾期不该红 ——
        // 一个红色的 0 会让人以为出了问题。
        Quad("逾期", s.overdue.toString(), "条", if (s.overdue > 0) extra.danger else MaterialTheme.colorScheme.onSurface),
        Quad("已投入", s.spentMinutes.toString(), "分钟", MaterialTheme.colorScheme.onSurface),
    )
    BentoCard(
        kicker = "DAILY SNAPSHOT",
        title = "今日盘点",
        framed = framed,
        trailing = {
            // 完成率不再重复写一遍：下面的进度条就是同一个数，重复的小牌只会分散注意力。
            Pill(
                "连续 " + s.streakDays + " 天",
                extra.data.copy(alpha = 0.12f),
                extra.data,
            )
        },
    ) {
        // 四个数：标签在上（等宽小标、加宽字距），数字在下（大号窄体、贴底对齐小数单位）。
        // 单位跟着数字走基线，不另起一行 —— 否则「45 分钟」会被读成两个格子。
        Row(Modifier.fillMaxWidth()) {
            cells.forEachIndexed { index, cell ->
                if (index > 0) {
                    Box(
                        Modifier.padding(horizontal = 14.dp)
                            .width(1.dp)
                            .height(44.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        cell.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = AppFonts.mono,
                        letterSpacing = 0.6.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text(
                            cell.value,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = cell.color,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            cell.unit,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alignByBaseline(),
                        )
                    }
                }
            }
        }

        // 完成率：标签 + 数值 + 一条 4dp 的进度条。
        // 只画线不写标签的话，它会被读成卡片的下边框（第一版就是这样）；只写数字又看不出比例。
        Spacer(Modifier.height(18.dp))
        val fraction = (s.completionRate / 100f).coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "完成进度",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                letterSpacing = 0.6.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                s.completionRate.toString() + "%",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private data class Quad(
    val label: String,
    val value: String,
    val unit: String,
    val color: Color,
)

@Composable
private fun TaskRow(
    task: Task,
    onToggle: (Task) -> Unit,
    onLog: (Task) -> Unit,
    onDelete: (Task) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val extra = LocalExtraColors.current
    val dueAt = task.dueAt
    val due = dueLabel(dueAt, done = task.isDone)
    val overdue = !task.isDone && dueAt != null && dueAt < System.currentTimeMillis()

    Row(
        modifier = Modifier.fillMaxWidth().hoverable(interaction)
            // 三档底色，从弱到强：普通行透明、悬停一层淡面、已完成给淡底。
            // 完成用「面」表示（主色淡底），比给整行字上色更轻；逾期不用面，只给红点红字。
            .clip(RoundedCornerShape(4.dp))
            .background(
                when {
                    task.isDone -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                    hovered -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
            )
            // 行高 46dp：低于 44 的行在触控板上容易点空，高于 48 一屏就少一条任务。
            .defaultMinSize(minHeight = 46.dp)
            .padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 点击区 44dp，视觉框 20dp：勾选框是整行里最常点的东西，不能只有 20px。
        Box(
            modifier = Modifier.size(44.dp).clickable { onToggle(task) },
            contentAlignment = Alignment.Center,
        ) {
            val shape = RoundedCornerShape(4.dp)
            Box(
                modifier = Modifier.size(20.dp)
                    .clip(shape)
                    .background(
                        if (task.isDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    )
                    .border(
                        1.dp,
                        if (task.isDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (task.isDone) {
                    Text(
                        "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.width(6.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textDecoration = if (task.isDone) TextDecoration.LineThrough else null,
                color = if (task.isDone) muted else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (due != null) {
                    // 逾期三重编码：行首红点（位置/形状）+ 红字（文字），不只靠颜色。
                    if (overdue) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(extra.danger))
                    }
                    Text(
                        due,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) extra.danger else muted.copy(alpha = 0.8f),
                    )
                }
                if (task.spentMinutes > 0) {
                    Text(
                        "已投入 " + task.spentMinutes + " 分钟",
                        style = MaterialTheme.typography.labelSmall,
                        color = muted.copy(alpha = 0.8f),
                    )
                }
            }
        }

        // 行内操作只在悬停时出现：不悬停时那一整行都是「任务」，不是「按钮条」。
        if (hovered) {
            IconAction("⏱", "记 25 分钟投入") { onLog(task) }
            IconAction("⌫", "删除", tint = extra.danger) { onDelete(task) }
        }
    }
}

@Composable
private fun IconAction(glyph: String, label: String, tint: Color? = null, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick, onClickLabel = label),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            style = MaterialTheme.typography.bodyMedium,
            color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskRow(runtime: AppRuntime, focus: FocusRequester) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    // 输入里带了日期（今天/周三/3 天后/9月30日）就先认出来：
    // 认出来的日期变成 chip，标题里那几个字被剪掉；手动选的日期优先。
    val parsed = remember(text) { parseNaturalDate(text, today) }
    // 输入框不抢启动焦点：一启动就 focus 会把 LazyColumn 滚到页面底部，
    // 每次打开应用都像「首页没了」。Ctrl+K 或点输入框才聚焦。

    fun submit() {
        if (text.isBlank()) return
        val date = dueDate ?: parsed.date
        val title = parsed.title.ifBlank { text }
        text = ""
        dueDate = null
        scope.launch { runtime.addTask(title, date?.let { endOfDayMs(it) }) }
        runCatching { focus.requestFocus() }
    }

    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth()) {
        // 常驻 label + 快捷键提示：占位符不能兼当字段名（一输入就消失）。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "新任务",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (dueDate == null && parsed.date != null) {
                    "写到的日期：" + shortDateLabel(parsed.date) + " · ENTER 添加 · ESC 清空"
                } else {
                    "ENTER 添加 · ESC 清空"
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        Spacer(Modifier.height(6.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChromeTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter -> { submit(); true }
                        Key.Escape -> { text = ""; dueDate = null; true }
                        else -> false
                    }
                },
                placeholder = "比如：周三前把方案发出去",
            )

            Spacer(Modifier.width(8.dp))

            // 日期只有一个入口：一个 chip + 菜单。三个并排按钮会被读成「提交」。
            Box {
                ChromeButton(
                    label = (dueDate ?: parsed.date)?.let { shortDateLabel(it) } ?: "日期",
                    filled = dueDate != null || parsed.date != null,
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("今天") },
                        onClick = { dueDate = today; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("明天") },
                        onClick = { dueDate = tomorrow; menuOpen = false },
                    )
                    DropdownMenuItem(
                        text = { Text("选一个日期…") },
                        onClick = { menuOpen = false; pickerOpen = true },
                    )
                    if (dueDate != null) {
                        DropdownMenuItem(
                            text = { Text("清除日期") },
                            onClick = { dueDate = null; menuOpen = false },
                        )
                    }
                }
            }

            Spacer(Modifier.width(8.dp))

            // 一屏只留一个实心主按钮；空输入时禁用而不是报错。
            ChromeButton(
                label = "添加",
                enabled = text.isNotBlank(),
                modifier = Modifier.height(ControlHeight),
                onClick = { submit() },
            )
        }
    }

    if (pickerOpen) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = (dueDate ?: today)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickerOpen = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        dueDate = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDate()
                    }
                    pickerOpen = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pickerOpen = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** chip 上的短日期：今天/明天说人话，其余用「5月14日」。 */
private fun shortDateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        else -> dateLabel(date)
    }
}
