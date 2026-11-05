package dev.trellis.app.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
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

/** 从「我的」进的二级页。知识网络是「知识」页里的一个分段，不算二级页。 */
enum class PhoneSubPage { Plugins, Activity, Diagnostics }

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

        // 通知点开：落到对应页签。用 token 而不是拿 tab 本身做 key ——
        // 「点同一条通知两次」也要重新跳一次，否则第二次什么都不会发生。
        LaunchedEffect(openTabToken) {
            if (openTabToken > 0 && openTab != null) {
                sub = null
                tab = openTab.coerceIn(0, PhoneTab.entries.size - 1)
            }
        }

        // 「去知识里看」这类跨页跳转：计划页点「打开来源笔记」写的是 AppRuntime 的
        // pendingKnowledgeReveal，桌面上靠 currentTab 切页；手机上页签是自己的状态，
        // 得在这里把「知识」顶上来，否则字段被写进去、人还停在计划页，看起来像没反应。
        LaunchedEffect(runtime.pendingKnowledgeReveal) {
            if (runtime.pendingKnowledgeReveal != null) tab = PhoneTab.Knowledge.ordinal
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
                if (sub != null) sub = null else tab = PhoneTab.Today.ordinal
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
                        onSelect = { tab = it; sub = null },
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
                        val current = sub
                        if (current != null) {
                            SubPageHost(
                                runtime = runtime,
                                page = current,
                                onBack = { sub = null },
                                onPickPluginArchive = onPickPluginArchive,
                            )
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
    }
}

/**
 * 复用页面（活动、诊断）。
 *
 * 这两页是「一列数据 + 几个筛选」，桌面版本来就是单列，直接整页嵌进来不会走形 ——
 * 所以先不重写（重写就是复制一份取数逻辑，那才是真正的隐患）。
 */
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
