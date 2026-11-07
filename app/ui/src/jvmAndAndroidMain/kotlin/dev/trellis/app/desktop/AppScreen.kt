package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
            // 对话 / 知识库是「列表 + 正文」的横向结构：列表要贴导航栏、正文贴右边界。
            // 其它页维持 1120dp 居中——把表单/卡片拉满宽屏反而更难读。
            val flushPage = runtime.currentTab == TAB_CHAT || runtime.currentTab == TAB_KNOWLEDGE
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .then(if (flushPage) Modifier else Modifier.widthIn(max = 1120.dp))
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        // flush 页的页头只占自身高度，把剩下整屏留给正文区；
                        // 非 flush 页仍由这层列撑满（TabContent 在里面按 weight 分）。
                        .then(if (flushPage) Modifier else Modifier.weight(1f)),
                ) {
                    // 审批条长在最上面：它是「规则要动手了」的唯一信号。
                    // 藏进弹窗或者标签页里，用户就会以为什么都没发生。
                    AskDialogs(runtime)

                    // 导航放哪边是宿主样式（uiPrefs.nav），和配色/密度一样可调：
                    // 宽屏上左栏比顶栏省横向空间，窄屏上顶栏更省横向空间。
                    if (!navLeft) {
                        TabBar(runtime)
                        Spacer(Modifier.height(16.dp))
                    }
                    // flush 页把正文区单独拎出来贴满宽；非 flush 页仍在这层列里排。
                    if (!flushPage) {
                        TabContent(runtime, Modifier.weight(1f))
                    }
                }
                if (flushPage) {
                    TabContent(runtime, Modifier.weight(1f).fillMaxWidth())
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

/** 小圆角硬边小牌：倒计时、状态、delta 都用它。 */
@Composable
private fun Pill(text: String, background: Color, foreground: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(3.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = LocalTrellisTokens.current.type.meta, color = foreground)
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

/** 导航里的六页，顺序就是标签栏/左栏的顺序。 */
private val NAV_TABS = listOf(TAB_TASKS, TAB_PLAN, TAB_KNOWLEDGE, TAB_CHAT, TAB_ACTIVITY, TAB_SETTINGS)

/**
 * 当前该点亮第几个导航项。
 *
 * 插件页和诊断页都不在导航里（它们从设置页进去），所以不能直接拿 `currentTab`
 * 当索引：停在那里的把「设置」点亮 —— 用户是从那里来的，回去也是那一条路。
 */
private fun navIndexOf(tab: Int): Int {
    val index = NAV_TABS.indexOf(tab)
    if (index >= 0) return index
    // 练习二级页从会话页进去：回去也是回会话，点亮「对话」。
    if (tab == TAB_PRACTICE) return NAV_TABS.indexOf(TAB_CHAT)
    if (tab == TAB_GOALS) return NAV_TABS.indexOf(TAB_PLAN)
    return NAV_TABS.indexOf(TAB_SETTINGS)
}

/** 顶栏式导航。 */
@Composable
private fun TabBar(runtime: AppRuntime) {
    TabRow(
        selectedTabIndex = navIndexOf(runtime.currentTab),
        containerColor = navContainerColor(runtime),
        contentColor = navContentColor(runtime),
    ) {
        Tab(
            selected = runtime.currentTab == TAB_TASKS,
            onClick = { runtime.currentTab = TAB_TASKS },
            text = { Text("任务") },
        )
        Tab(
            selected = runtime.currentTab == TAB_PLAN,
            onClick = { runtime.currentTab = TAB_PLAN },
            text = { Text("计划") },
        )
        Tab(
            selected = runtime.currentTab == TAB_KNOWLEDGE,
            onClick = { runtime.currentTab = TAB_KNOWLEDGE },
            text = { Text("知识库") },
        )
        Tab(
            selected = runtime.currentTab == TAB_CHAT,
            onClick = { runtime.currentTab = TAB_CHAT },
            text = { Text(if (runtime.chatBusy) "对话 ···" else "对话") },
        )
        Tab(
            selected = runtime.currentTab == TAB_ACTIVITY,
            onClick = { runtime.currentTab = TAB_ACTIVITY },
            text = { Text("活动") },
        )
        Tab(
            selected = runtime.currentTab == TAB_SETTINGS,
            onClick = { runtime.currentTab = TAB_SETTINGS },
            text = { Text("设置") },
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
        NavRailItem(runtime, ChromeIcon.Knowledge, "知识库", TAB_KNOWLEDGE, foreground)
        NavRailItem(runtime, ChromeIcon.Chat, if (runtime.chatBusy) "对话 ···" else "对话", TAB_CHAT, foreground)
        NavRailItem(runtime, ChromeIcon.Activity, "活动", TAB_ACTIVITY, foreground)
        NavRailItem(runtime, ChromeIcon.Settings, "设置", TAB_SETTINGS, foreground)
    }
}

@Composable
private fun NavRailItem(runtime: AppRuntime, icon: ChromeIcon, label: String, tab: Int, foreground: Color) {
    // 插件页、诊断页不在导航里：停在那里时点亮「设置」，因为用户是从设置进来的。
    val selected = navIndexOf(runtime.currentTab) == navIndexOf(tab)
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
        Box {
            ChromeIconGlyph(icon, content, Modifier.size(16.dp))
            // 只有「对话」有主动消息红点：管家的晨/夜仪式把话留在了那段会话里。
            if (tab == TAB_CHAT && runtime.hasUnreadChat) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(6.dp)
                        .background(MaterialTheme.colorScheme.error, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
        Text(label, style = LocalTrellisTokens.current.type.meta, color = content)
    }
}

@Composable
private fun TabContent(runtime: AppRuntime, modifier: Modifier) {
    when (runtime.currentTab) {
        TAB_TASKS -> TasksTab(runtime, modifier)
        TAB_PLAN -> PlanScreen(runtime, modifier)
        TAB_KNOWLEDGE -> KnowledgeScreen(runtime, modifier)
        TAB_PRACTICE -> PracticeScreen(runtime, modifier)
        TAB_GOALS -> GoalScreen(runtime, modifier)
        TAB_CHAT -> ChatScreen(runtime, modifier)
        TAB_ACTIVITY -> ActivityScreen(runtime, modifier)
        TAB_PLUGINS -> PluginsScreen(runtime, modifier)
        TAB_SETTINGS -> SettingsScreen(runtime, modifier)
        else -> DiagnosticsScreen(runtime, modifier)
    }
}

@Composable
private fun TasksTab(runtime: AppRuntime, modifier: Modifier) {
    // 输入框的焦点请求器放在 runtime 上：任务卡现在是插件贡献的，
    // 宿主这边按 Ctrl+K 也得能聚焦到同一个输入框。
    val taskFocus = runtime.taskFocus
    val keyModifier = modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K) {
            runCatching { taskFocus.requestFocus() }
            true
        } else {
            false
        }
    }
    // 两栏：左边是「今天要做的事」（一整列可滚），右边是「本周的样子」（进度 + 盘点 + 报表）。
    // 原来一列 LazyColumn 把两种东西上下叠着，下半屏一大片空白；窗口不够宽就退回单列，
    // 断点先给一个够用的值（< 900dp 单列），精细的三档断点留批 6。
    BoxWithConstraints(keyModifier.fillMaxSize()) {
        // 门槛按「对话正文列最大宽度」算：760 已经是正常阅读宽度，再往上才分栏太苛刻。
        val twoColumn = maxWidth >= TrellisBreakpoints.twoColumn.dp
        // 刚过门槛时右栏收窄一点，别把左边清单挤成 300 多 dp。
        val railWidth = if (maxWidth < 900.dp) 300.dp else 340.dp
        if (!twoColumn) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TaskHeaderSlot(runtime)
                TaskBoardSlot(runtime)
                ReportChartSlot(runtime)
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TaskBoardSlot(runtime)
                }
                Column(
                    modifier = Modifier.width(railWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TaskHeaderSlot(runtime)
                    WeekProgressCard(runtime)
                    ReportChartSlot(runtime)
                }
            }
        }
    }
}

/** 今日盘点（插件贡献到 HomeHeader 的卡）。 */
@Composable
private fun TaskHeaderSlot(runtime: AppRuntime) {
    SlotArea(
        runtime.kernel.extensions,
        Slots.HomeHeader,
        refreshKey = runtime.tick,
        onAction = { owner, action -> runtime.performSlotAction(owner, action) },
        layout = runtime.layoutFor(Slots.HomeHeader.id),
    )
}

/** 任务区（待办清单，插件贡献到 HomeSection 的卡）。 */
@Composable
private fun TaskBoardSlot(runtime: AppRuntime) {
    SlotArea(
        runtime.kernel.extensions,
        Slots.HomeSection,
        refreshKey = runtime.tick,
        onAction = { owner, action -> runtime.performSlotAction(owner, action) },
        layout = runtime.layoutFor(Slots.HomeSection.id),
    )
}

/** 报表挂载点：没有插件贡献就整张卡不出现（空卡片比没有更难看）。 */
@Composable
private fun ReportChartSlot(runtime: AppRuntime) {
    if (runtime.kernel.extensions.contributionsAt(Slots.ReportChart).isEmpty()) return
    BentoCard(title = "近七天") {
        SlotArea(
            runtime.kernel.extensions,
            Slots.ReportChart,
            refreshKey = runtime.tick,
            onAction = { owner, action -> runtime.performSlotAction(owner, action) },
            layout = runtime.layoutFor(Slots.ReportChart.id),
        )
    }
}

/**
 * 右栏「本周进度」：本周排了几件、做完几件、估时多少，再给一个计时入口。
 *
 * 数字来自 `runtime.planWeekProgress`（后端算的当前周，不是页面选中的周）。
 * 没有计划时不留空卡：直接给「去对话里安排」这一个动作。
 */
@Composable
private fun WeekProgressCard(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    val progress = runtime.planWeekProgress
    // 实际投入来自活动归因，异步取；取不到（没开活动采集/没授权）就不显示这一行，
    // 不拿 0 分钟冒充「没学」。
    var attributedMinutes by remember(runtime.planSelectedWeek, runtime.planDays) {
        mutableStateOf<Long?>(null)
    }
    LaunchedEffect(runtime.planSelectedWeek, runtime.planDays) {
        attributedMinutes = runCatching { runtime.planWeekAttributedMinutes() }.getOrNull()
    }
    // 大数字是「全部节点」，右上角就不再挂「第 1 周」（会让人以为数字是本周的）。
    BentoCard(title = "完成情况") {
        if (progress == null) {
            Text(
                "还没有学习计划。在对话里说「我想学 xx」，管家会把周次和单元排好。",
                style = tokens.type.meta,
                color = tokens.colors.ink3,
            )
            Spacer(Modifier.height(tokens.space.m))
            ChromeButton(
                label = "去对话里安排",
                filled = false,
                onClick = { runtime.currentTab = TAB_CHAT },
            )
            return@BentoCard
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                progress.allDone.toString() + "/" + progress.allTotal,
                style = tokens.type.stat,
                color = tokens.colors.ink,
            )
            if (progress.allTotal > 0 && progress.allDone == progress.allTotal) {
                Spacer(Modifier.width(tokens.space.s))
                CheckMark(size = 26.dp)
            }
            Spacer(Modifier.weight(1f))
            Text("全部节点", style = tokens.type.meta, color = tokens.colors.ink3)
        }
        Spacer(Modifier.height(tokens.space.s))
        // 进度条：底是 sunken，条是 accent；0 时不画条（fillMaxWidth(0) 不合法）。
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(tokens.colors.sunken),
        ) {
            if (progress.allProgress > 0f) {
                Box(
                    Modifier.fillMaxWidth(progress.allProgress).fillMaxHeight()
                        .clip(RoundedCornerShape(percent = 50))
                        .background(tokens.colors.accent),
                )
            }
        }
        Spacer(Modifier.height(tokens.space.s))
        Text(
            "本周 " + progress.done + "/" + progress.total +
                (if (progress.allRemaining > 0) " · 全部还剩 " + progress.allRemaining + " 件" else " · 全部做完"),
            style = tokens.type.meta,
            color = tokens.colors.ink3,
        )
        if (progress.plannedMinutes > 0) {
            Text(
                "本周已完成预估 " + progress.plannedMinutes + " 分钟",
                style = tokens.type.meta,
                color = tokens.colors.ink3,
            )
        }
        val invested = attributedMinutes
        if (invested != null && invested > 0) {
            Text(
                "本周实际投入 " + invested + " 分钟（窗口活动归因）",
                style = tokens.type.meta,
                color = tokens.colors.ink3,
            )
        }
        Spacer(Modifier.height(tokens.space.m))
        val nextNode = runtime.todoPlanNodes.firstOrNull()?.nodes?.firstOrNull()
        when {
            nextNode != null -> ChromeButton(
                label = if (runtime.timerRunning) "计时中…" else "开始计时",
                onClick = { runtime.openNodeTimer(nextNode) },
            )
            progress.total == 0 -> Text("这一周还没排节点。", style = tokens.type.meta, color = tokens.colors.ink3)
            else -> Text("没排到具体日期的节点在计划页的待安排里。", style = tokens.type.meta, color = tokens.colors.ink3)
        }
    }
}

/** 房主画的外壳：硬边小圆角 + 细边 + 贴纸阴影，全部跟着卡片样式走。 */
@Composable
private fun BentoCard(
    title: String,
    /** 旧样片在标题上方有一行等宽大写英文小标；2026-11-06 批 2 起默认不画。 */
    kicker: String? = null,
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
                if (kicker != null) {
                    Text(
                        kicker,
                        style = LocalTrellisTokens.current.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
        title = "待办",
        framed = framed,
        trailing = {
            Text(
                tasks.count { !it.isDone }.toString() + " 条未完成",
                style = LocalTrellisTokens.current.type.meta,
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
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    TaskRow(
                        task = task,
                        onToggle = { scope.launch { runtime.toggle(it) } },
                        onTimer = { runtime.openTimer(it) },
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
                        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                        TaskRow(
                            task = task,
                            onToggle = { scope.launch { runtime.toggle(it) } },
                            onTimer = { runtime.openTimer(it) },
                            onDelete = { scope.launch { runtime.delete(it) } },
                        )
                    }
                }
            }
        }

        TaskBoardPlanNodes(runtime)
        TaskBoardTodayDone(runtime)

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
                ChromeTextLink("撤销", onClick = { scope.launch { runtime.undoDelete() } })
            }
        }
    }
}

/** 待办板上摊开的学习计划节点：每个没结束的计划一组，组头点进去就是计划页。 */
@Composable
private fun TaskBoardPlanNodes(runtime: AppRuntime) {
    val groups = runtime.todoPlanNodes
    if (groups.isEmpty()) return

    GroupLabel("学习计划", groups.sumOf { it.nodes.size })
    groups.forEach { group ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .clickable { runtime.openPlanNode(group.planId) }
                .padding(top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group.planTitle,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                group.nodes.size.toString() + " 个节点 · 约 " + group.minutes + " 分钟",
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        group.nodes.forEach { node ->
            PlanNodeRowView(
                node = node,
                onComplete = { runtime.completeTodoPlanNode(node) },
                onTimer = { runtime.openNodeTimer(node) },
                onClick = { runtime.openPlanNode(group.planId) },
            )
        }
        if (group.hidden > 0) {
            Text(
                "还有 " + group.hidden + " 个节点，去计划页看",
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 18.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * 「今日完成」：今天在计划里勾掉的节点。
 *
 * 勾完就从待办里摘掉是对的（它不再是待办），但不能让它凭空消失——否则用户不知道自己
 * 刚才那一下成没成。做完的节点挪到这张小清单里，给对勾、给标题，点一下回计划页看当天。
 */
@Composable
private fun TaskBoardTodayDone(runtime: AppRuntime) {
    val done = runtime.todayDonePlanNodes
    if (done.isEmpty()) return
    val tokens = LocalTrellisTokens.current

    GroupLabel("今日完成", done.size)
    done.forEach { node ->
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(3.dp))
                .clickable {
                    runtime.currentTab = TAB_PLAN
                }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                CheckMark(size = 16.dp)
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    node.planTitle + if (node.minutes > 0) " · " + node.minutes + " 分钟" else "",
                    style = tokens.type.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
    }
}

/**
 * 一条节点：勾选框 + 标题 + 章与预估 + 行尾「日期 · ⏱」。
 *
 * 2026-11-06 批 3 起节点也能直接勾（决策 2）：勾掉 = 在计划页把这一天的 occurrence 勾完，
 * 走的是同一个后端命令，所以两处数字永远一致。⏱ 和任务行一样常显：
 * 能进待办的都是排到过具体某天的节点，session 只能挂在 occurrence 上。
 */
@Composable
private fun PlanNodeRowView(
    node: PlanNodeRow,
    onComplete: () -> Unit,
    onTimer: () -> Unit,
    onClick: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChromeCheckbox(checked = false, onCheckedChange = onComplete)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (node.chapter.isNotBlank()) {
                    Text(
                        node.chapter,
                        style = LocalTrellisTokens.current.type.meta,
                        color = muted.copy(alpha = 0.8f),
                    )
                }
                Text(
                    node.minutes.toString() + " 分钟",
                    style = LocalTrellisTokens.current.type.meta,
                    color = muted.copy(alpha = 0.8f),
                )
            }
        }
        Text(
            if (node.today) "今天" else "逾期 " + node.daysLate + " 天",
            style = LocalTrellisTokens.current.type.meta,
            color = if (node.today) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            },
        )
        Spacer(Modifier.width(2.dp))
        IconAction("⏱", "对这个节点开计时器") { onTimer() }
    }
}

/** 分组小标：标签字 + 条数。点了能折叠的会带下划线暗示。 */
@Composable
private fun GroupLabel(text: String, count: Int?, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = LocalTrellisTokens.current.type.meta,
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
        // 「逾期」是唯一需要被追问的数字，用语义红；其余数字保持墨色。
        Quad("逾期", s.overdue.toString(), "条", extra.danger),
        Quad("已投入", s.spentMinutes.toString(), "分钟", MaterialTheme.colorScheme.onSurface),
    )
    BentoCard(
        title = "今日盘点",
        framed = framed,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 连续天数：它是「测量值」，属数据岗位，用靛，不用强调色。
                Pill(
                    "连续 " + s.streakDays + " 天",
                    extra.data.copy(alpha = 0.12f),
                    extra.data,
                )
                Pill(
                    "完成率 " + s.completionRate + "%",
                    MaterialTheme.colorScheme.primaryContainer,
                    extra.accentText,
                )
            }
        },
    ) {
        // 大字号 / 窄栏（右栏 340dp 时）四个数字横排会被挤爆：标签压成两行、数字贴边。
        // 字体缩放过一档、或者这一栏本身不够宽，就改 2×2 —— 宁可多占一行高度，也不让文字互相挤。
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val bigFont = LocalDensity.current.fontScale >= 1.2f
            val narrow = maxWidth < 420.dp
            if (bigFont || narrow) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    cells.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                            pair.forEachIndexed { index, cell ->
                                if (index > 0) QuadDivider()
                                QuadCell(cell, Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    cells.forEachIndexed { index, cell ->
                        if (index > 0) QuadDivider()
                        QuadCell(cell, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadDivider() {
    Box(
        Modifier.padding(horizontal = 16.dp)
            .width(1.dp)
            .height(42.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun QuadCell(cell: Quad, modifier: Modifier) {
    Column(modifier) {
        Text(
            cell.label,
            style = LocalTrellisTokens.current.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                cell.value,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = cell.color,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                cell.unit,
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onTimer: (Task) -> Unit,
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
            // 完成用「面」表示（青柠淡底），比给整行字上色更轻；逾期不用面，只给红点红字。
            .clip(RoundedCornerShape(3.dp))
            .background(
                if (task.isDone) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f)
                else Color.Transparent,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 做完的行换成堆出来的对勾：完成应该像被奖励，不像被禁用（方框里打勾仍是「未完成的框」）。
        if (task.isDone) {
            Box(
                modifier = Modifier.size(40.dp).clickable { onToggle(task) },
                contentAlignment = Alignment.Center,
            ) {
                CheckMark(size = 18.dp)
            }
        } else {
            ChromeCheckbox(checked = false, onCheckedChange = { onToggle(task) })
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
            if (task.spentMinutes > 0) {
                Text(
                    "已投入 " + task.spentMinutes + " 分钟",
                    style = LocalTrellisTokens.current.type.meta,
                    color = muted.copy(alpha = 0.8f),
                )
            }
        }

        // 行尾和计划节点对齐：先日期、再 ⏱。⏱ 常显（悬停才加深）——同一种动作不该在两张卡里两种脾气。
        // 逾期三重编码：红点（形状/位置）+ 红字，不只靠颜色。
        if (due != null) {
            if (overdue) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(extra.danger))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                due,
                style = LocalTrellisTokens.current.type.meta,
                color = if (overdue) extra.danger else muted.copy(alpha = 0.8f),
            )
            Spacer(Modifier.width(4.dp))
        }
        if (!task.isDone) {
            IconAction("⏱", "开计时器", alpha = if (hovered) 1f else 0.45f) { onTimer(task) }
        }
        // 删除只在悬停时出现：不悬停时那一整行都是「任务」，不是「按钮条」。
        if (hovered) IconAction("⌫", "删除", tint = extra.danger) { onDelete(task) }
    }
}

@Composable
private fun IconAction(glyph: String, label: String, tint: Color? = null, alpha: Float = 1f, onClick: () -> Unit) {
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
            color = (tint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = alpha),
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
                style = LocalTrellisTokens.current.type.meta,
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
