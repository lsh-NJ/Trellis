package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ai.tool.DeepSeekMarkup
import dev.trellis.ui.LocalExtraColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * 和插件管理员对话。
 *
 * 这一页之前的问题是「两个人抢同一块地」：agent 的执行轨迹（判断 / 第 N 步 / 工具返回）
 * 和它的答复同字号、同颜色、同左边距，读者得读完才知道哪句是结论。
 *
 * 现在的信息分五层：
 * - L1 你的输入：右侧青柠气泡；
 * - L2 结论：15.5px / 行高 1.78 / 最宽 34em，全页唯一需要完整读的文本；
 * - L3 过程摘要：一行「过程 · N 个动作 · 改动 M 处」，可展开；
 * - L4 过程细节：等宽小字，机器语法只允许出现在这一层；
 * - L5 元信息：时间 · 步数 · 耗时，以及「撤销 / 看差异 / 复制」。
 */
@Composable
fun ChatScreen(runtime: AppRuntime, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    // 右栏默认开：等待时用户的眼睛在对话线程上，清单得和线程同屏、且不随滚动跑掉。
    // 关掉不是让清单消失，而是换成输入框上方那条常驻摘要（见 PlanDock）。
    var planOpen by rememberSaveable(runtime.currentChatId) { mutableStateOf(true) }
    var diffOpen by remember(runtime.currentChatId) { mutableStateOf(false) }
    var planNote by remember(runtime.currentChatId) { mutableStateOf<String?>(null) }

    val planLine = runtime.latestPlanLine()
    val roundKey = remember(runtime.chat.size, planLine?.id) { planRoundKeyOf(runtime.chat, planLine) }
    val planDiff = remember(runtime.chatSessions.size, runtime.chatBusy, roundKey, runtime.cardLayout, runtime.uiPrefs) {
        roundKey?.let { runtime.diffForRound(it) }.orEmpty()
    }

    // 新一轮清单出现时把右栏打开一次；用户手动关掉后不再抢回去。
    LaunchedEffect(planLine?.id) {
        val fresh = planLine?.plan?.any {
            it.state == PlanStepState.Running || it.state == PlanStepState.Pending
        } == true
        if (fresh) planOpen = true
    }

    Row(
        modifier = modifier.onPreviewKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.isCtrlPressed && event.key == Key.K -> {
                    focus.requestFocus()
                    true
                }
                event.key == Key.Escape -> {
                    runtime.stopChat()
                    true
                }
                else -> false
            }
        },
    ) {
        ChatSessionList(runtime, Modifier.width(240.dp).fillMaxHeight())
        ChatConversation(
            runtime = runtime,
            focus = focus,
            planLine = if (planOpen) null else planLine,
            onOpenPlan = { planOpen = true },
            note = planNote,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 18.dp),
        )
        if (planOpen && planLine != null && planLine.plan.isNotEmpty()) {
            PlanSidebar(
                line = planLine,
                running = runtime.chatBusy,
                diffCount = planDiff.size,
                onClose = { planOpen = false },
                onUndo = {
                    if (roundKey != null) planNote = runtime.undoRound(roundKey)
                },
                onDiff = { diffOpen = true },
                onStop = { runtime.stopChat() },
                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            )
        }
    }

    if (diffOpen) {
        AlertDialog(
            onDismissRequest = { diffOpen = false },
            title = { Text("这一轮改了什么") },
            text = {
                if (planDiff.isEmpty()) {
                    Text("没有改动。")
                } else {
                    Text(
                        planDiff.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { diffOpen = false }) { Text("关闭") }
            },
        )
    }
}

// ---------------------------------------------------------------- 会话列表

@Composable
private fun ChatSessionList(runtime: AppRuntime, modifier: Modifier) {
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("会话", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { runtime.newChat() }, enabled = !runtime.chatBusy) {
                Text("新对话", style = MaterialTheme.typography.labelMedium)
            }
        }

        if (runtime.chatSessions.isEmpty()) {
            Text(
                "还没有对话 —— 在下面输入一句话开始",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        val grouped = runtime.chatSessions.groupBy { session ->
            when (chatTimeLabel(session.updatedAt)) {
                "昨天" -> "昨天"
                else -> if (isToday(session.updatedAt)) "今天" else "更早"
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (title in listOf("今天", "昨天", "更早")) {
                val sessions = grouped[title] ?: continue
                item(key = "group-" + title) {
                    Text(
                        title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == runtime.currentChatId,
                        onOpen = { runtime.openChat(session.id) },
                        onDelete = { pendingDelete = session },
                        enabled = !runtime.chatBusy,
                    )
                }
            }
        }
    }

    val victim = pendingDelete
    if (victim != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这段对话？") },
            text = { Text("「" + victim.title + "」的记录会从磁盘删掉，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    runtime.deleteChat(victim.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SessionRow(
    session: ChatSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    enabled: Boolean,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menuOpen by remember { mutableStateOf(false) }
    val rounds = session.lines.count { it.role == ChatRole.User }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .hoverable(interaction, enabled = enabled)
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(enabled = enabled, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { "新对话" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chatTimeLabel(session.updatedAt) + " · " + rounds + " 轮",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
        // 主动消息没看的会话行标一个点（与导航「对话」的红点同源）。
        if (session.unread) {
            Box(
                Modifier.size(6.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
        }
        Box {
            if (hovered || menuOpen) {
                Box(
                    modifier = Modifier
                        .height(24.dp)
                        .width(24.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = enabled) { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("⋯", style = MaterialTheme.typography.labelLarge)
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("删除对话", style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
        Spacer(Modifier.width(4.dp))
    }
}

// ---------------------------------------------------------------- 对话线程

@Composable
private fun ChatConversation(
    runtime: AppRuntime,
    focus: FocusRequester,
    planLine: ChatLine?,
    onOpenPlan: () -> Unit,
    note: String?,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var input by remember(runtime.currentChatId) { mutableStateOf("") }
    var lastPrompt by remember(runtime.currentChatId) { mutableStateOf("") }
    var localNote by remember(runtime.currentChatId) { mutableStateOf<String?>(null) }

    val blocks = remember(runtime.chat.size, runtime.chat.lastOrNull()?.text?.length) { blocksOf(runtime.chat) }

    LaunchedEffect(runtime.chat.size, runtime.chat.lastOrNull()?.text?.length) {
        if (runtime.chat.isNotEmpty()) listState.animateScrollToItem(runtime.chat.size)
    }

    // 从插件页带过来的草稿：落地就把那句话填进输入框并聚焦，用户补完后半句即可。
    LaunchedEffect(runtime.pendingChatPrompt) {
        val draft = runtime.pendingChatPrompt ?: return@LaunchedEffect
        input = draft
        runtime.takePendingChatPrompt()
        runCatching { focus.requestFocus() }
    }

    Column(modifier) {
        if (runtime.chat.isEmpty()) {
            EmptyConversation(onPick = { input = it }, modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(blocks, key = { block ->
                    when (block) {
                        is ChatBlock.User -> "u-" + block.line.id
                        is ChatBlock.Round -> "r-" + block.user.id
                        is ChatBlock.Orphan -> "o-" + block.lines.first().id
                    }
                }) { block ->
                    when (block) {
                        is ChatBlock.User -> UserBubble(block.line)
                        is ChatBlock.Orphan -> OrphanSteps(block.lines)
                        is ChatBlock.Round -> {
                            RoundView(
                                runtime = runtime,
                                round = block,
                                isLast = block === blocks.lastOrNull(),
                                onOpenPlan = onOpenPlan,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }

        // 右栏关掉时清单换成贴输入框的常驻条：常驻，不是消失。
        if (planLine != null && planLine.plan.isNotEmpty()) {
            PlanDock(
                line = planLine,
                running = runtime.chatBusy,
                onOpen = onOpenPlan,
                onStop = { runtime.stopChat() },
            )
        }

        // 模型主动开了个选择（ask_choices）：选项竖着摆在输入框上方，等用户点。
        // 这里不再有「运行时猜下一步」的建议条 —— 要不要继续做什么，由模型决定要不要问。
        if (runtime.chatChoices.isNotEmpty()) {
            ChoiceBar(
                choices = runtime.chatChoices,
                onPick = { runtime.applyChoice(it) },
                onDismiss = { runtime.dismissChoices() },
            )
        }

        val shownNote = localNote ?: note
        shownNote?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }

        HorizontalDivider()
        Spacer(Modifier.height(8.dp))

        if (!runtime.llmConfigured()) {
            Text(
                runtime.llmSetupHint(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            return@Column
        }

        InputBar(
            runtime = runtime,
            focus = focus,
            input = input,
            onInput = { input = it },
            onSend = {
                val text = input.trim()
                if (text.isEmpty()) return@InputBar
                if (runtime.chatBusy) {
                    localNote = "还在跑，等这一轮结束再发"
                } else {
                    input = ""
                    lastPrompt = text
                    localNote = null
                    runtime.ask(text)
                }
            },
            onRecall = {
                if (input.isBlank() && lastPrompt.isNotBlank()) input = lastPrompt
            },
        )
    }
}

// ---------------------------------------------------------------- 空态与气泡

private val EMPTY_EXAMPLES = listOf(
    "把这周的完成情况做成一张卡",
    "把首页三张卡片横着排",
    "现在装了哪些插件？",
)

/**
 * 输入框上方的「选一个」列表 —— 竖着排，一行一个选项。
 *
 * 只有模型主动要问（`ask_choices`）时才会出现；运行时不再按「刚刚查过资源」之类
 * 的痕迹替它猜下一步。竖排的理由：横排在窄窗口里会把一串短语挤成两行，
 * 竖排每行都能写完整一句「选它会怎样」，也更好点。
 */
@Composable
internal fun ChoiceBar(
    choices: List<ChatChoice>,
    onPick: (ChatChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "选一个",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (choice in choices) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onPick(choice) }
                    .padding(horizontal = 2.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "→",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(18.dp),
                )
                Text(
                    choice.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("不用", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun EmptyConversation(onPick: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有对话",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text("先说你要什么，再让它动手", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "它也可以被问「现在装了什么」，或者被要求改布局、改样式。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        for (example in EMPTY_EXAMPLES) {
            Row(
                modifier = Modifier
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .clickable { onPick(example) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(example, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UserBubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 3.dp, bottomStart = 10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                "你 · " + clockLabel(line.atMs),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun OrphanSteps(lines: List<ChatLine>) {
    Column {
        for (line in lines) {
            Text(
                line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 线程里的清单入口：一行、可点，点开右栏。
 *
 * 不在这里重复渲染整张清单——活清单在右栏，用户滚到哪儿都看得见；
 * 线程里每轮都摊开一张，翻历史时会占掉大半屏，还得跟右栏重复。
 */
@Composable
private fun PlanThreadLink(line: ChatLine, running: Boolean, onOpen: () -> Unit) {
    val plan = line.plan
    val settled = plan.count {
        it.state == PlanStepState.Done || it.state == PlanStepState.Failed || it.state == PlanStepState.Skipped
    }
    val allSettled = settled == plan.size
    val failed = plan.any { it.state == PlanStepState.Failed }
    val active = plan.firstOrNull { it.state == PlanStepState.Running }?.text
    val scheme = MaterialTheme.colorScheme
    val extra = LocalExtraColors.current
    val tone = when {
        failed -> scheme.error
        allSettled -> extra.accentText
        running -> RunningAmber
        else -> scheme.onSurfaceVariant
    }

    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (allSettled) "执行计划 · 已完成" else "执行计划 · 进行中",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$settled/${plan.size}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = tone,
        )
        if (!allSettled && active != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                active,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("›", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

@Composable
private fun RoundView(
    runtime: AppRuntime,
    round: ChatBlock.Round,
    isLast: Boolean,
    onOpenPlan: () -> Unit = {},
) {
    val key = round.user.id
    val steps = round.steps
    val running = isLast && runtime.chatBusy
    val failed = round.status?.text?.let {
        it.contains("没做成") || it.contains("出错") || it.contains("已停止")
    } ?: false
    val undone = runtime.roundIsUndone(key)
    val diff = remember(
        runtime.chatSessions.size,
        round.status?.id,
        runtime.chatBusy,
        runtime.cardLayout,
        runtime.uiPrefs,
        runtime.pluginCardStyles,
    ) { runtime.diffForRound(key) }

    var expanded by remember(key) { mutableStateOf(false) }
    var diffOpen by remember(key) { mutableStateOf(false) }
    var note by remember(key) { mutableStateOf<String?>(null) }

    val expandedNow = expanded || running || failed
    val toolNames = steps.mapNotNull { step ->
        Regex("^第 \\d+ 步：(.+)$").find(step.text)?.groupValues?.get(1)?.trim()
    }.filter { it.isNotEmpty() }
    val changedCount = diff.size
    val dotColor = when {
        running -> RunningAmber
        failed -> MaterialTheme.colorScheme.error
        else -> LocalExtraColors.current.data
    }

    Column(Modifier.alpha(if (undone) 0.55f else 1f)) {
        UserBubble(round.user)
        Spacer(Modifier.height(10.dp))

        // 线程里只留一行入口：活的清单在右栏，这里不重复渲染（结束后折叠成摘要）。
        round.plan?.let { planLine ->
            if (planLine.plan.isNotEmpty()) {
                PlanThreadLink(planLine, running = running, onOpen = onOpenPlan)
                Spacer(Modifier.height(10.dp))
            }
        }

        // 清单没结清但这一轮已经不在跑：只能是被打断/崩溃恢复，说「完成」是撒谎。
        val planUnfinished = round.plan?.plan?.any {
            it.state == PlanStepState.Running || it.state == PlanStepState.Pending
        } == true

        // 回合头：谁在说 + 状态 + 耗时
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).height(6.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.width(6.dp))
            Text("Trellis", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    running -> "运行中"
                    planUnfinished -> "中断"
                    failed -> "没做成"
                    else -> "完成"
                },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = dotColor,
            )
            val duration = roundDurationLabel(round)
            if (!running && duration.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    duration,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            if (running) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.height(11.dp).width(11.dp), strokeWidth = 1.6.dp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { runtime.stopChat() }) {
                    Text("停止生成", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (steps.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ProcessSummary(
                steps = steps,
                toolNames = toolNames,
                changedCount = changedCount,
                expanded = expandedNow,
                onClick = { expanded = !expanded },
            )
            if (expandedNow) {
                Spacer(Modifier.height(6.dp))
                ProcessDetails(steps)
            }
        }

        Spacer(Modifier.height(8.dp))
        val conclusion = round.reply?.text.orEmpty()
        if (conclusion.isBlank() && running) {
            Text(
                "…",
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 27.6.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 527.dp),
            )
        } else {
            // agent 的答复天然是 Markdown（小标题/清单/代码块）：按块渲染，
            // 不再把 ** 和 - 原样丢给用户看。
            //
            // 历史里可能还留着旧版本存下的协议壳（修复前模型吐的 DSML 方言被原样
            // 当正文存进了盘），这里再剥一遍：存下来的文本改不了，但可以不给人看。
            MarkdownText(
                DeepSeekMarkup.strip(conclusion),
                modifier = Modifier.widthIn(max = 527.dp),
                bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 27.6.sp),
            )
        }

        val meta = metaLabel(round)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }

        if (failed) {
            Spacer(Modifier.height(8.dp))
            FailureBlock(round, diff)
        }

        if (undone) {
            Spacer(Modifier.height(6.dp))
            Text(
                "已撤销这次改动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!running) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasSnapshot = runtime.hasRoundActions(key)
                TextButton(
                    onClick = { note = runtime.undoRound(key) },
                    enabled = hasSnapshot,
                ) { Text("撤销这次改动", style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = { diffOpen = !diffOpen },
                    enabled = diff.isNotEmpty(),
                ) { Text("看差异", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { runtime.copyToClipboard(conclusion); note = "已复制" }) {
                    Text("复制", style = MaterialTheme.typography.labelSmall)
                }
                if (!hasSnapshot) {
                    Text(
                        "（重启后旧轮次不能撤销）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }

        note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (diffOpen) {
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("这一轮改了什么", style = MaterialTheme.typography.labelSmall)
                if (diff.isEmpty()) {
                    Text(
                        "没有改动宿主状态（没有改布局、偏好或卡片样式）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    for (line in diff) {
                        Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessSummary(
    steps: List<ChatLine>,
    toolNames: List<String>,
    changedCount: Int,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val text = buildString {
        append("过程 · ")
        append(steps.size)
        append(" 个动作")
        if (changedCount > 0) {
            append(" · 改动 ")
            append(changedCount)
            append(" 处")
        }
        if (toolNames.isNotEmpty()) {
            append("   ")
            append(toolNames.take(4).joinToString(" → "))
            if (toolNames.size > 4) append(" …")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).height(5.dp).clip(CircleShape).background(LocalExtraColors.current.data))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(if (expanded) "▴" else "▾", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ProcessDetails(steps: List<ChatLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (line in steps) {
            val isResult = line.text.startsWith("　→")
            val isJudgement = line.text.startsWith("判断：")
            val text = line.text
                .replace("判断：", "")
                .replace(Regex("^第 \\d+ 步："), "· ")
                .replace("　→", "↳")
                .trim()
            if (text.isEmpty()) continue
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .padding(top = 6.dp)
                        .width(5.dp)
                        .height(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (line.text.contains("出错")) MaterialTheme.colorScheme.error
                            else LocalExtraColors.current.data.copy(alpha = if (isResult) 0.45f else 0.8f),
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    (if (isResult) "↳ " else "") + (if (isJudgement) "判断 · " else "") + text.removePrefix("↳ "),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (isResult) 11.sp else 12.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (isResult) 0.75f else 1f,
                    ),
                    modifier = Modifier.padding(start = if (isResult) 8.dp else 0.dp),
                )
            }
        }
    }
}

@Composable
private fun FailureBlock(round: ChatBlock.Round, diff: List<String>) {
    val lastStep = round.steps.lastOrNull { it.text.contains("出错") }?.text
        ?: round.steps.lastOrNull()?.text ?: "（没有留下过程）"
    val reason = round.status?.text ?: "没有说明"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "这一轮没做成",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text("卡在哪一步：" + lastStep, style = MaterialTheme.typography.bodySmall)
        Text("为什么：" + reason, style = MaterialTheme.typography.bodySmall)
        Text(
            "数据动没动：" + if (diff.isEmpty()) "没有改动宿主数据" else "改动了 " + diff.size + " 处，可以点「撤销这次改动」退回去",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ---------------------------------------------------------------- 输入条

@Composable
private fun InputBar(
    runtime: AppRuntime,
    focus: FocusRequester,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onRecall: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            ChromeTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 160.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            // Shift+Enter 自己插换行，不依赖 BasicTextField 的默认行为：
                            // 桌面端 Enter 会先被输入法/焦点遍历吃掉，默认插入并不可靠。
                            event.key == Key.Enter && event.isShiftPressed -> {
                                onInput(input + "\n")
                                true
                            }
                            event.key == Key.Enter -> {
                                onSend()
                                true
                            }
                            event.key == Key.DirectionUp && input.isBlank() -> {
                                onRecall()
                                true
                            }
                            else -> false
                        }
                    },
                placeholder = "说一句你想让它做什么",
                enabled = true,
                singleLine = false,
            )
            Spacer(Modifier.width(8.dp))
            ChromeButton(
                label = if (runtime.chatBusy) "在想…" else "发送",
                enabled = input.isNotBlank(),
                onClick = onSend,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Enter 发送 · Shift+Enter 换行 · ↑ 召回上一条 · Esc 停止",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// ---------------------------------------------------------------- 数据

private sealed interface ChatBlock {
    data class User(val line: ChatLine) : ChatBlock
    data class Round(
        val user: ChatLine,
        val plan: ChatLine?,
        val steps: List<ChatLine>,
        val reply: ChatLine?,
        val status: ChatLine?,
    ) : ChatBlock
    data class Orphan(val lines: List<ChatLine>) : ChatBlock
}

/** 清单属于哪一轮：从清单行往上找最近的一条用户消息，它才是这一轮的起点。 */
private fun planRoundKeyOf(lines: List<ChatLine>, planLine: ChatLine?): Long? {
    if (planLine == null) return null
    val index = lines.indexOfFirst { it.id == planLine.id }
    val head = if (index >= 0) lines.subList(0, index) else lines
    return head.lastOrNull { it.role == ChatRole.User }?.id
}
private fun blocksOf(lines: List<ChatLine>): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    val orphan = mutableListOf<ChatLine>()
    var user: ChatLine? = null
    var plan: ChatLine? = null
    var steps = mutableListOf<ChatLine>()
    var reply: ChatLine? = null
    var status: ChatLine? = null

    fun flush() {
        val opened = user ?: return
        out += ChatBlock.Round(opened, plan, steps, reply, status)
        user = null
        plan = null
        steps = mutableListOf()
        reply = null
        status = null
    }

    for (line in lines) {
        when (line.role) {
            ChatRole.User -> {
                flush()
                user = line
            }
            ChatRole.Plan -> if (user == null) orphan += line else plan = line
            ChatRole.Step -> if (user == null) orphan += line else steps += line
            ChatRole.Assistant -> if (user == null) orphan += line else reply = line
            ChatRole.Status -> if (user == null) orphan += line else status = line
        }
    }
    flush()
    if (orphan.isNotEmpty()) out.add(0, ChatBlock.Orphan(orphan))
    return out
}

private fun clockLabel(ms: Long): String {
    if (ms <= 0L) return ""
    return Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))
}

private fun isToday(ms: Long): Boolean {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return date == java.time.LocalDate.now(zone)
}

private fun roundDurationLabel(round: ChatBlock.Round): String {
    val start = round.user.atMs
    val end = (round.status ?: round.reply)?.atMs ?: return ""
    if (start <= 0L || end <= start) return ""
    val seconds = (end - start) / 1000.0
    return if (seconds < 60) String.format("%.1fs", seconds) else String.format("%.1fmin", seconds / 60)
}

private fun metaLabel(round: ChatBlock.Round): String {
    val end = (round.status ?: round.reply)?.atMs ?: return ""
    if (end <= 0L) return ""
    val time = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    val steps = round.steps.size
    val duration = roundDurationLabel(round)
    return buildString {
        append(time)
        append(" · ")
        append(steps)
        append(" 步")
        if (duration.isNotEmpty()) {
            append(" · ")
            append(duration)
        }
    }
}

private val RunningAmber = androidx.compose.ui.graphics.Color(0xFF8F5C12)
