package dev.trellis.app.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.FocusMode
import dev.trellis.app.desktop.GoalScreen
import dev.trellis.app.desktop.PlanDocPage
import dev.trellis.app.desktop.PracticeScreen
import dev.trellis.app.desktop.TAB_GOALS
import dev.trellis.app.desktop.TAB_PRACTICE
import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.capability.Capabilities
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay

/** 五个页签，顺序就是一天的用法：看今天 → 排计划 → 问 AI → 记笔记 → 改设置。 */
enum class PhoneTab(val label: String) {
    Today("今日"),
    Plan("计划"),
    Chat("对话"),
    Knowledge("知识"),
    Me("我的"),
}

/**
 * 二级页。知识网络是「知识」页里的一个分段，不算二级页。
 *
 * 前三个从「我的」手点进来；后三个是**运行时驱动的**（目标/练习/计划文档）：
 * 入口在桌面别处（计划页「目标 · N」「计划文档」、对话页的练习提议卡），
 * 手机靠下面的导航 LaunchedEffect 把 currentTab / planDocBrowseId 翻译成 sub。
 */
enum class PhoneSubPage { Plugins, Activity, Diagnostics, Goals, Practice, PlanDoc }

/**
 * 手机端根界面。
 *
 * 和桌面版的关系：**同一套运行时，完全不同的排版**。
 * 桌面是一屏铺开 12 栏（一次看很多），手机是一次一件事（底部五个页签 + 单列）。
 * 这里不做「把桌面界面缩到 390dp」，而是按手指的尺寸重新组织信息。
 *
 * [backHandler] 是给安卓壳用的：返回键的逻辑属于宿主（桌面没有这个键），
 * 但「现在能不能回退」只有界面自己知道，所以由壳注入一个包装器，而不是把
 * `androidx.activity` 引进共享源码。
 */
@Composable
fun PhoneApp(
    runtime: AppRuntime,
    startTab: Int = 0,
    backHandler: (@Composable (enabled: Boolean, onBack: () -> Unit) -> Unit)? = null,
    /** 只给预览器用：知识页默认停在「笔记」还是「网络」，网络页默认「结构」还是「总览」。 */
    startKnowledgeTab: Int = 0,
    startGraphMode: Int = 0,
    /** 只给预览器用：进来就打开某一篇笔记。 */
    startNoteId: String? = null,
    /**
     * 通知点开时该落到哪个页签（序号见 [PhoneTab]）。
     *
     * 和 [startTab] 的区别：那是首次组合的初值，这是**任何时刻**都可能来的指令 ——
     * 应用在后台时点了通知，Activity 走 onNewIntent，界面早就组合过了。
     * [openTabToken] 每次点击自增，用来表达「又点了同一条」。
     */
    openTab: Int? = null,
    openTabToken: Int = 0,
    /**
     * 「从文件安装插件」的选文件动作。
     *
     * 共享层没有文件选择器：桌面是 `JFileChooser`，安卓是 SAF，都是壳的事。
     * 传 null（预览器、没有宿主的场景）时这个按钮就不出现 —— 不摆一个点了没反应的入口。
     */
    onPickPluginArchive: ((onPicked: (java.io.File) -> Unit) -> Unit)? = null,
    /** 只给预览器用：进来就停在某个二级页（插件 / 活动 / 诊断）。 */
    startSubPage: PhoneSubPage? = null,
) {
    PhoneTheme(runtime.uiPrefs.appearance, runtime.uiPrefs.fontScale) {
        var tab by remember { mutableIntStateOf(startTab.coerceIn(0, PhoneTab.entries.size - 1)) }
        var sub by remember { mutableStateOf(startSubPage) }
        // 这个二级页是不是运行时顶起来的（目标 / 练习 / 计划文档）。
        // 预览器可以用 startSubPage 直接停在某一页，那种不算「运行时驱动」，
        // 不能被下面「运行时已经关了 → 收掉 sub」的分支误清。
        var subFromRuntime by remember { mutableStateOf(false) }

        // 二级页的「返回」有两层：桌面二级页自己的返回键只复位运行时
        // （closeGoalScreen / closePractice / closePlanDocs），不会碰手机本地的 sub。
        // 从底部页签或系统返回离开时要反过来：先替它们把运行时复位，再收 sub；
        // 否则下面的导航 LaunchedEffect 看到 currentTab 还停在 TAB_GOALS / TAB_PRACTICE，
        // 下一帧又把人拽回那一页 —— 表现是「点返回没反应」。
        fun closeSubPage() {
            when (sub) {
                PhoneSubPage.Goals -> runtime.closeGoalScreen()
                PhoneSubPage.Practice -> runtime.closePractice()
                PhoneSubPage.PlanDoc -> runtime.closePlanDocs()
                else -> Unit
            }
            sub = null
            subFromRuntime = false
        }

        // 通知点开：落到对应页签。用 token 而不是拿 tab 本身做 key ——
        // 「点同一条通知两次」也要重新跳一次，否则第二次什么都不会发生。
        LaunchedEffect(openTabToken) {
            if (openTabToken > 0 && openTab != null) {
                closeSubPage()
                tab = openTab.coerceIn(0, PhoneTab.entries.size - 1)
            }
        }

        // 跨页跳转统一在这里消费。
        //
        // 桌面靠 currentTab 一个字段切页；手机页签是局部状态，所以要把「运行时想去哪」
        // 翻译成 tab/sub。优先级：知识定位 > 计划文档 > 练习 > 目标 > 活动归因
        // （正常不会同时挂起；真撞上时只取一个，剩下的下一帧继续消费）。
        //
        // pendingActivityDay 是普通 var，不是 Compose 状态，不会触发重组；这里跟 tick
        // 一起轮询（根组合每秒 refresh 一次 tick），最多晚一秒切页，代价只有一次空判。
        LaunchedEffect(
            runtime.pendingKnowledgeReveal,
            runtime.currentTab,
            runtime.pendingActivityDay,
            runtime.planDocBrowseId,
            runtime.tick,
        ) {
            // 用布尔快照而不是可空 id：Kotlin 会在后续 when 分支里把 docPlanId
            // 智能转成非空，导致「运行时已关」分支的判空被编译器当成恒假。
            val planDocOpen = runtime.planDocBrowseId != null
            when {
                // 「去知识里看」：计划页点「打开来源笔记」写 pendingKnowledgeReveal。
                // 先把可能占着整页的运行时二级页复位（否则知识页会被 sub 挡住，
                // 而且 currentTab 不改的话下一帧又会被下面几条分支拽回去）。
                runtime.pendingKnowledgeReveal != null -> {
                    if (runtime.currentTab == TAB_GOALS) runtime.closeGoalScreen()
                    if (runtime.currentTab == TAB_PRACTICE) runtime.closePractice()
                    if (planDocOpen) runtime.closePlanDocs()
                    tab = PhoneTab.Knowledge.ordinal
                    sub = null
                    subFromRuntime = false
                }
                // 计划文档：桌面是在知识库里让出整页（desktop/KnowledgeScreen.kt:77-80），
                // 手机照样落到「知识」页签，由二级页自带页头/返回。
                planDocOpen -> {
                    tab = PhoneTab.Knowledge.ordinal
                    sub = PhoneSubPage.PlanDoc
                    subFromRuntime = true
                }
                // 练习：对话页接受提议后运行时把 currentTab 拨到 TAB_PRACTICE。
                runtime.currentTab == TAB_PRACTICE -> {
                    tab = PhoneTab.Chat.ordinal
                    sub = PhoneSubPage.Practice
                    subFromRuntime = true
                }
                // 目标：计划页「目标 · N」或 AI 工具把 currentTab 拨到 TAB_GOALS。
                runtime.currentTab == TAB_GOALS -> {
                    tab = PhoneTab.Plan.ordinal
                    sub = PhoneSubPage.Goals
                    subFromRuntime = true
                }
                // 活动归因详情：计划页自动完成卡点「学习 · 网页标题」写 pendingActivityDay。
                // 日期不在这里清 —— ActivityScreen 首次组合时拿它当初始日期再自行清空。
                runtime.pendingActivityDay != null -> {
                    tab = PhoneTab.Me.ordinal
                    sub = PhoneSubPage.Activity
                }
                // 反过来：桌面页自己的返回键只复位了运行时，没碰 sub。看到运行时
                // 已经不在那一页，就把二级页收掉。closePlanDocs 只清 id、不改
                // currentTab，所以计划文档看 id、目标/练习看 currentTab。
                // 只对「运行时顶起来的」生效：预览器的 startSubPage 不能被误清。
                subFromRuntime && sub == PhoneSubPage.Goals && runtime.currentTab != TAB_GOALS -> {
                    sub = null
                    subFromRuntime = false
                }
                subFromRuntime && sub == PhoneSubPage.Practice && runtime.currentTab != TAB_PRACTICE -> {
                    sub = null
                    subFromRuntime = false
                }
                subFromRuntime && sub == PhoneSubPage.PlanDoc && !planDocOpen -> {
                    sub = null
                    subFromRuntime = false
                }
            }
        }

        // 数据每秒刷新一次：插件也能改数据，而「变更通知」这件事领域层还没做。
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                runtime.refresh()
            }
        }

        // 计时器每秒推进一次。
        //
        // 桌面上这件事由 `TimerWindow` 自己的 LaunchedEffect 做（窗口关掉就自然停）；
        // 手机上计时器是底部悬浮条，没有独立窗口，所以驱动必须放在根界面。
        // 少了这段的表现很隐蔽：计时器能开、能显示，但数字一直是 0:00。
        LaunchedEffect(runtime.timerRunning) {
            while (runtime.timerRunning) {
                delay(1_000)
                runtime.timerTick(1)
            }
        }


        if (backHandler != null) {
            backHandler(sub != null || tab != PhoneTab.Today.ordinal) {
                if (sub != null) closeSubPage() else tab = PhoneTab.Today.ordinal
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // 页面内容限宽：平板上拉到 1000dp 也不该让一行字横穿整屏。
            val contentMax = 560.dp

            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    PhoneBottomBar(
                        current = tab,
                        hasUnreadChat = runtime.hasUnreadChat,
                        onSelect = { next -> closeSubPage(); tab = next },
                    )
                },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner)) {
                    Column(
                        // 顺序有讲究：先 widthIn 再 fillMaxWidth，否则 fillMaxSize 会把
                        // 约束顶到全屏，限宽在平板上完全失效（第一版截图上就是这样）。
                        Modifier
                            .widthIn(max = contentMax)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .align(Alignment.TopCenter),
                    ) {
                        // 审批条：和桌面 AppScreen 顶部那条同一个职责 —— 规则要动手时
                        // 它是唯一出口。放在页签内容之前、不进页面的 LazyColumn，
                        // 所以切页签、进二级页都还在原地，且不占位（没有待审批直接返回）。
                        ApprovalCard(runtime)

                        val current = sub
                        if (current != null) {
                            // 二级页拿剩余高度（审批条出现时不会被顶出屏幕）。
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                SubPageHost(
                                    runtime = runtime,
                                    page = current,
                                    onBack = { closeSubPage() },
                                    onPickPluginArchive = onPickPluginArchive,
                                )
                            }
                        } else {
                            when (PhoneTab.entries[tab]) {
                                PhoneTab.Today -> TodayPage(runtime)
                                PhoneTab.Plan -> PlanPage(runtime)
                                PhoneTab.Chat -> ChatPage(runtime)
                                PhoneTab.Knowledge -> KnowledgePage(runtime, startTab = startKnowledgeTab, startGraphMode = startGraphMode, startNoteId = startNoteId)
                                PhoneTab.Me -> MePage(runtime, onOpen = { sub = it })
                            }
                        }
                    }

                    // 计时器：手机上不另外开窗（那是桌面的做法），做成底部悬浮条。
                    AnimatedVisibility(
                        visible = runtime.timerVisible && sub == null,
                        enter = slideInVertically { it },
                        exit = slideOutVertically { it },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        TimerBar(runtime)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubPageHost(
    runtime: AppRuntime,
    page: PhoneSubPage,
    onBack: () -> Unit,
    onPickPluginArchive: (((java.io.File) -> Unit) -> Unit)?,
) {
    when (page) {
        PhoneSubPage.Plugins -> PluginsPage(
            runtime = runtime,
            onPickArchive = onPickPluginArchive,
            onBack = onBack,
        )
        PhoneSubPage.Activity -> WrappedPage("活动", onBack) {
            dev.trellis.app.desktop.ActivityScreen(runtime)
        }
        PhoneSubPage.Diagnostics -> WrappedPage("诊断", onBack) {
            dev.trellis.app.desktop.DiagnosticsScreen(runtime)
        }
        // 目标 / 练习 / 计划文档直接复用桌面二级页：
        // 它们都自带 ChromeSecondaryHeader（标题 + 返回箭号，返回键已经接好运行时），
        // 再包一层 WrappedPage 会叠出两个页头，所以这里只交给页面自己。
        // 三页本来就是单列滚动（练习内容再限宽 760dp），手机宽度直接适用。
        //
        // 但桌面页假设外面已经给了页边距（桌面由 AppScreen 的 flush 布局提供），
        // 直接铺到 390dp 上时正文会贴左边缘、标题贴着状态栏。这里补一层手机边距；
        // 页头在边距内，和正文对齐。
        PhoneSubPage.Goals -> ReusedDesktopPage { GoalScreen(runtime) }
        PhoneSubPage.Practice -> ReusedDesktopPage { PracticeScreen(runtime) }
        PhoneSubPage.PlanDoc -> {
            val planId = runtime.planDocBrowseId
            if (planId == null) {
                // 运行时已经把计划文档关掉了：这一帧先空着，导航 LaunchedEffect
                // 下一帧会把 sub 收掉。不要在这里直接渲染会拿 null 的页面。
                LaunchedEffect(Unit) { onBack() }
            } else {
                ReusedDesktopPage { PlanDocPage(runtime, planId, onClose = onBack) }
            }
        }
    }
}

/**
 * 复用页面（活动、诊断）。
 *
 * 这两页是「一列数据 + 几个筛选」，桌面版本来就是单列，直接整页嵌进来不会走形 ——
 * 所以先不重写（重写就是复制一份取数逻辑，那才是真正的隐患）。
 */
/** 复用桌面二级页时的手机边距：桌面由外层提供，手机得自己补。 */
@Composable
private fun ReusedDesktopPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = Gap.l, vertical = Gap.s)) {
        content()
    }
}

@Composable
private fun WrappedPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Gap.l)) { SubPageBar(title, onBack) }
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

@Composable
private fun PhoneBottomBar(current: Int, hasUnreadChat: Boolean, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().background(LocalCardColor.current)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)))
        Row(
            Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhoneTab.entries.forEachIndexed { index, item ->
                val selected = index == current
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 管家主动说的那句还没看：一个小圆点，比「未读 1」省地方。
                        if (item == PhoneTab.Chat && hasUnreadChat) {
                            Spacer(Modifier.width(4.dp))
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .size(width = if (selected) 16.dp else 0.dp, height = 2.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
    }
}

/** 计时器悬浮条：点开是小弹层，能停、能加时。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimerBar(runtime: AppRuntime) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val label = when (runtime.timerMode) {
        FocusMode.Stopwatch -> clockText(runtime.timerElapsedSeconds)
        else -> if (runtime.timerFinished) "时间到"
        else clockText((runtime.timerTotalSeconds - runtime.timerElapsedSeconds).coerceAtLeast(0))
    }

    Row(
        Modifier
            .padding(bottom = Gap.m)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable { open = true }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.width(Gap.s))
        Text(
            runtime.timerTaskTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
            maxLines = 1,
        )
        if (runtime.timerRunning && !runtime.timerFinished) {
            Spacer(Modifier.width(Gap.s))
            Text(
                "▶",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
            )
        }
    }

    if (open) {
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Gap.xl).padding(bottom = Gap.xxl)) {
                SheetHeading(
                    if (runtime.timerFinished) "这一轮结束了" else "正在计时",
                    // 用 m:ss 而不是「几分钟」：不到一分钟时分钟档只会显示「—」，
                    // 看着像计时器没走。
                    runtime.timerTaskTitle + " · 已过 " + clockText(runtime.timerElapsedSeconds),
                )
                Spacer(Modifier.height(Gap.l))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    if (runtime.timerMode == FocusMode.Countdown) {
                        PhoneButton("+5 分", { runtime.extendTimer(5) }, modifier = Modifier.weight(1f), filled = false)
                    }
                    PhoneButton(
                        if (runtime.timerRunning) "暂停" else "继续",
                        { runtime.toggleTimerPause() },
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )
                    PhoneButton("重新开始", { runtime.restartTimer() }, modifier = Modifier.weight(1f), filled = false)
                }
                Spacer(Modifier.height(Gap.s))
                Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                    PhoneButton(
                        "结束并记下",
                        {
                            scope.launch {
                                runtime.stopTimerAndLog()
                                open = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    PhoneButton(
                        "不记了",
                        { runtime.closeTimer(); open = false },
                        modifier = Modifier.weight(1f),
                        filled = false,
                    )
                }
                Spacer(Modifier.height(Gap.s))
                if (!runtime.llmConfigured()) {
                    Text(
                        "计时结束会把这一段时间记到任务投入里；记不下时去「我的 → 模型」检查配置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 移动端审批卡。
 *
 * 为什么不直接用桌面的 `AskDialogs`：它依赖 LocalTrellisTokens 与桌面 Chrome 组件，
 * 手机没有那套主题注入；而且桌面那条长在窗口最上方、可以整窗铺开，手机上页签内容
 * 是单列滚动的，必须按手机版式自己写一张。
 *
 * 数据只有一份：`runtime.pendingApproval`。回答后 runtime 会把它清空，卡片自己消失，
 * 所以不设「正在提交」之类的本地中间态 —— 那个状态只会和真相不一致。
 */
@Composable
private fun ApprovalCard(runtime: AppRuntime) {
    val request = runtime.pendingApproval ?: return
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gap.l, vertical = Gap.s)
            .clip(shape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f), shape)
            .padding(Gap.l),
    ) {
        Text(
            "需要你确认",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(Gap.s))
        Text(
            request.summary,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(Gap.s))
        Text(
            "发起者 " + approvalActorLabel(request.actor) +
                " · 插件 " + request.pluginId.value +
                " · 能力 " + Capabilities.describe(request.capability) +
                " · 动作 " + request.action,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f),
        )
        Spacer(Modifier.height(Gap.xs))
        Text(
            "规则是后台触发的，它正卡在这里等你点头；不理它，它就一直不动。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.78f),
        )
        Spacer(Modifier.height(Gap.m))
        Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
            PhoneButton(
                "拒绝",
                { runtime.answerApproval(false) },
                modifier = Modifier.weight(1f),
                filled = false,
            )
            PhoneButton(
                "允许",
                { runtime.answerApproval(true) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 审批卡上的「是谁要动手」：和桌面 AskDialogs 的 actorLabel 同口径。 */
private fun approvalActorLabel(actor: Actor): String = when (actor) {
    Actor.USER -> "你"
    Actor.AGENT -> "智能体"
    Actor.SYSTEM -> "系统"
}
