package dev.trellis.app.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.hoverable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
@OptIn(ExperimentalComposeUiApi::class)
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

    // 窄窗（最小 880×600）里左右两条 296 栏会把对话列挤到不足 200dp：
    // 按窗口宽度收窄右栏、缩小对话区左右留白，正文至少还能读。
    val containerWidthDp = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp().value
    }
    val narrowChat = containerWidthDp < 1024f
    val planPanelWidth = if (narrowChat) 232.dp else 296.dp
    val conversationPadding = if (narrowChat) 12.dp else 24.dp

    // 右栏是否占位决定对话区要不要留右边距（不留时正文直接贴到窗口右边界）。
    val planSidebarVisible = planOpen && planLine != null && planLine.plan.isNotEmpty()
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
        // 会话列表与知识库页的笔记/概念列表共用 ListSidebar（样式、宽度、拖拽都一致）。
        ChatSessionList(runtime)
        ListSidebarSplitter()
        ChatConversation(
            runtime = runtime,
            focus = focus,
            planLine = if (planOpen) null else planLine,
            onOpenPlan = { planOpen = true },
            note = planNote,
            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = conversationPadding),
        )
        if (planSidebarVisible) {
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
                modifier = Modifier.padding(start = if (narrowChat) 8.dp else 14.dp, end = 4.dp),
                width = planPanelWidth,
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
                ChromeTextLink("关闭", onClick = { diffOpen = false })
            },
        )
    }
}

// ---------------------------------------------------------------- 会话列表

@Composable
private fun ChatSessionList(runtime: AppRuntime) {
    val tokens = LocalTrellisTokens.current
    var pendingDelete by remember { mutableStateOf<ChatSession?>(null) }
    var query by remember { mutableStateOf("") }

    // 与知识库页左栏同一套骨架（ListSidebar）；搜索也是两边都有。
    // 管家那段常驻会话置顶：它随时可能有主动消息，不该被普通会话挤下去。
    val filtered = runtime.chatSessions
        .filter { query.isBlank() || it.title.ifBlank { "新对话" }.contains(query.trim(), ignoreCase = true) }

    ListSidebar(
        title = "会话",
        count = filtered.size,
        actionLabel = "新对话",
        onAction = { runtime.newChat() },
        actionEnabled = !runtime.chatBusy,
    ) {
        ChromeTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "搜索会话",
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Text(
                if (query.isBlank()) "还没有对话 —— 在下面输入一句话开始" else "没有匹配的会话",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        val steward = filtered.firstOrNull { it.kind == SessionKind.Steward }
        val grouped = filtered.filter { it.kind != SessionKind.Steward }.groupBy { session ->
            when (chatTimeLabel(session.updatedAt)) {
                "昨天" -> "昨天"
                else -> if (isToday(session.updatedAt)) "今天" else "更早"
            }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (steward != null) {
                item(key = "steward-row") {
                    SessionRow(
                        session = steward,
                        selected = steward.id == runtime.currentChatId,
                        onOpen = { runtime.openChat(steward.id) },
                        onDelete = { pendingDelete = steward },
                        enabled = !runtime.chatBusy,
                    )
                }
                item(key = "steward-divider") {
                    Text(
                        "普通会话",
                        style = tokens.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
            }
            for (title in listOf("今天", "昨天", "更早")) {
                val sessions = grouped[title] ?: continue
                item(key = "group-" + title) {
                    Text(
                        title,
                        style = tokens.type.meta,
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
                ChromeButton(
                    label = "删除",
                    onClick = {
                        runtime.deleteChat(victim.id)
                        pendingDelete = null
                    },
                    destructive = true,
                )
            },
            dismissButton = {
                ChromeTextLink("取消", onClick = { pendingDelete = null })
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
    val tokens = LocalTrellisTokens.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interaction, enabled = enabled)
            .listRowSurface(selected, hovered)
            .clickable(enabled = enabled, onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListRowSelectionBar(selected)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                session.title.ifBlank { "新对话" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                chatTimeLabel(session.updatedAt) + " · " + rounds + " 轮",
                style = tokens.type.meta,
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
    val tokens = LocalTrellisTokens.current

    val blocks = remember(runtime.chat.size, runtime.chat.lastOrNull()?.text?.length) { blocksOf(runtime.chat) }
    // 管家会话里的主动消息没有用户那一句，但渲染与会话完全一致（回合头换成「管家」）。
    val stewardSession = runtime.currentChatId?.let { id ->
        runtime.chatSessions.firstOrNull { it.id == id }?.kind
    } == SessionKind.Steward

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

    Box(modifier) {
        BoxWithConstraints(
            modifier = Modifier.widthIn(max = MaxConversationWidth).fillMaxHeight().align(Alignment.TopCenter),
        ) {
            // 用户消息按列宽的比例收窄，agent 消息占满整列（见下面的条宽计算）。
            val userBubbleMax = maxWidth * 0.62f
            Column(Modifier.fillMaxSize()) {
            if (runtime.chat.isEmpty()) {
                EmptyConversation(onPick = { input = it }, modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 键里带上序号：历史会话文件里可能有重复的行 id（旧版本重启后计数器重置），
                    // 只按 id 做 key 会在 LazyColumn 里撞键崩溃。
                    itemsIndexed(blocks, key = { index, block ->
                        when (block) {
                            is ChatBlock.User -> "u-" + index + "-" + block.line.id
                            is ChatBlock.Round -> "r-" + index + "-" + block.keyId
                        }
                    }) { _, block ->
                        when (block) {
                            is ChatBlock.User -> UserBubble(block.line, userBubbleMax)
                            is ChatBlock.Round -> RoundView(
                                runtime = runtime,
                                round = block,
                                isLast = block === blocks.lastOrNull(),
                                stewardSession = stewardSession,
                                userBubbleMax = userBubbleMax,
                                onOpenPlan = onOpenPlan,
                            )
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
                    style = tokens.type.meta,
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
    }
}

// ---------------------------------------------------------------- 空态与气泡

private val EMPTY_EXAMPLES = listOf(
    "把这周的完成情况做成一张卡",
    "把首页三张卡片横着排",
    "现在装了哪些插件？",
)

/**
 * 输入框上方的「选一个」列表 —— 经典编号选项：`1.` / `2.` / `3.`，每项至少一整行。
 *
 * 只有模型主动要问（`ask_choices`）时才会出现。做成编号列表而不是一行一个箭头：
 * 选项经常是一整句话（「把第二章拆成两周」），每项占满一行才能读完整；
 * 序号让用户可以用键盘/口头直接指代，也比箭头更像「做决定」的界面。
 * 每项 hover 有底色、点击即选中，下面保留「不用」退出。
 */
@Composable
internal fun ChoiceBar(
    choices: List<ChatChoice>,
    onPick: (ChatChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = LocalTrellisTokens.current
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.rule, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选一个", style = tokens.type.meta, color = tokens.colors.ink3)
            Spacer(Modifier.weight(1f))
            Text(
                "点一行就是了",
                style = tokens.type.meta,
                color = tokens.colors.ink3.copy(alpha = 0.72f),
            )
        }
        // 最多 6 条、每条一整句话：限高 + 滚动，别把对话区和输入条挤出窗口。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 264.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            choices.forEachIndexed { index, choice ->
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        thickness = 1.dp,
                        color = tokens.colors.rule.copy(alpha = 0.55f),
                    )
                }
                ChoiceOptionRow(index = index, choice = choice, tokens = tokens, onClick = { onPick(choice) })
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            ChromeTextLink("不用", onClick = onDismiss)
        }
    }
}

/** 编号选项的一行：序号 + 整行可选中的文字；hover 时整行染一层浅强调色。 */
@Composable
private fun ChoiceOptionRow(
    index: Int,
    choice: ChatChoice,
    tokens: TrellisTokens,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (hovered) tokens.colors.accentSoft.copy(alpha = 0.8f) else Color.Transparent,
        label = "choice-hover",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(background)
            .alpha(if (pressed) 0.72f else 1f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            "${index + 1}.",
            style = tokens.type.control,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = tokens.colors.accent,
            modifier = Modifier.width(28.dp),
        )
        Text(
            choice.label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun EmptyConversation(onPick: (String) -> Unit, modifier: Modifier) {
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "还没有对话",
            style = tokens.type.meta,
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
private fun UserBubble(line: ChatLine, maxWidth: Dp = 560.dp) {
    val tokens = LocalTrellisTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomEnd = 3.dp, bottomStart = 10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                "你 · " + clockLabel(line.atMs),
                style = tokens.type.meta,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            )
            Text(line.text, style = MaterialTheme.typography.bodyLarge)
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
    val tokens = LocalTrellisTokens.current
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
            style = tokens.type.meta,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.6.sp,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$settled/${plan.size}",
            style = tokens.type.meta,
            fontFamily = FontFamily.Monospace,
            color = tone,
        )
        if (!allSettled && active != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                active,
                style = tokens.type.meta,
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
    stewardSession: Boolean,
    userBubbleMax: Dp,
    onOpenPlan: () -> Unit = {},
) {
    val key = round.keyId
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
    // 只有最后一轮常驻「撤销 / 看差异 / 复制」；旧轮次鼠标移上去才露出来，
    // 免得整页对话每轮尾巴上都挂三行链接。
    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()

    val tokens = LocalTrellisTokens.current
    val expandedNow = expanded || running || failed
    val toolNames = steps.mapNotNull { step ->
        val raw = Regex("^第 \\d+ 步：(.+)$").find(step.text)?.groupValues?.get(1)?.trim()
            ?: return@mapNotNull null
        when {
            raw.isEmpty() -> null
            raw == "它说完了" -> null
            raw.startsWith("它说完了，但 ") -> "还要补"
            else -> toolActionLabel(raw)
        }
    }
    val changedCount = diff.size
    val dotColor = when {
        running -> RunningAmber
        failed -> MaterialTheme.colorScheme.error
        else -> LocalExtraColors.current.data
    }

    Column(Modifier.alpha(if (undone) 0.55f else 1f).hoverable(hoverSource)) {
        round.user?.let { user ->
            UserBubble(user, userBubbleMax)
            Spacer(Modifier.height(10.dp))
        }

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
            Text(
                if (stewardSession) "管家" else "Trellis",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    running -> "运行中"
                    planUnfinished -> "中断"
                    failed -> "没做成"
                    else -> "完成"
                },
                style = tokens.type.meta,
                fontFamily = FontFamily.Monospace,
                color = dotColor,
            )
            val duration = roundDurationLabel(round)
            if (!running && duration.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(
                    duration,
                    style = tokens.type.meta,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            if (running) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.height(11.dp).width(11.dp), strokeWidth = 1.6.dp)
                Spacer(Modifier.weight(1f))
                ChromeTextLink("停止生成", onClick = { runtime.stopChat() })
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
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // agent 的答复天然是 Markdown（小标题/清单/代码块）：按块渲染，
            // 不再把 ** 和 - 原样丢给用户看。
            //
            // 历史里可能还留着旧版本存下的协议壳（修复前模型吐的 DSML 方言被原样
            // 当正文存进了盘），这里再剥一遍：存下来的文本改不了，但可以不给人看。
            MarkdownText(
                DeepSeekMarkup.strip(conclusion),
                modifier = Modifier.fillMaxWidth(),
                bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, lineHeight = 27.6.sp),
            )
        }

        round.reply?.proposal?.let { proposal ->
            Spacer(Modifier.height(10.dp))
            StewardProposalCard(runtime, round.reply, proposal)
        }

        round.reply?.practiceProposal?.let { practice ->
            Spacer(Modifier.height(10.dp))
            PracticeProposalCard(runtime, round.reply, practice)
        }

        val meta = metaLabel(round)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                meta,
                style = tokens.type.meta,
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
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (!running && (isLast || hovered || diffOpen)) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasSnapshot = runtime.hasRoundActions(key)
                ChromeTextLink(
                    label = "撤销这次改动",
                    onClick = { note = runtime.undoRound(key) },
                    enabled = hasSnapshot,
                )
                ChromeTextLink(
                    label = "看差异",
                    onClick = { diffOpen = !diffOpen },
                    enabled = diff.isNotEmpty(),
                )
                ChromeTextLink(label = "复制", onClick = { runtime.copyToClipboard(conclusion); note = "已复制" })
                if (!hasSnapshot) {
                    Text(
                        "（重启后旧轮次不能撤销）",
                        style = tokens.type.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        }

        note?.let {
            Text(
                it,
                style = tokens.type.meta,
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
                Text("这一轮改了什么", style = tokens.type.meta)
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
    val tokens = LocalTrellisTokens.current
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
            style = tokens.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(if (expanded) "▴" else "▾", style = tokens.type.caption)
    }
}

@Composable
private fun ProcessDetails(steps: List<ChatLine>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (line in steps) {
            // 新会话：工具调用单独一行，「点开看说明/参数/结果」。
            if (line.tool != null) {
                ToolStepRow(line)
                continue
            }
            val isResult = line.text.startsWith("　→")
            val isJudgement = line.text.startsWith("判断：")
            val isThinking = line.text.startsWith("思考：")
            // 旧会话里存过「它说完了」的收尾行：成功的那条不再显示，
            // 「…，但 X」保留补做原因，别把有用信息一起扔掉。
            if (line.text.contains("它说完了") && !line.text.contains("它说完了，但 ")) continue
            val text = line.text
                .replace("判断：", "")
                .replace("思考：", "")
                .replace(Regex("^第 \\d+ 步："), "· ")
                .replace("　→", "↳")
                .replace("· 它说完了，但 ", "· 还要补：")
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
                    (if (isResult) "↳ " else "") +
                        when {
                            isThinking -> "思考 · "
                            isJudgement -> "判断 · "
                            else -> ""
                        } + text.removePrefix("↳ "),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (isResult) 11.sp else 12.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (isResult) 0.75f else 1f,
                    ),
                    modifier = Modifier
                        .padding(start = if (isResult) 8.dp else 0.dp)
                        // 思考可能是一整段话：占满剩余宽度、自动换行，不截行数。
                        .fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ToolStepRow(line: ChatLine) {
    val trace = line.tool ?: return
    var open by remember(line.id) { mutableStateOf(false) }
    val args = remember(trace.arguments) { compactArguments(trace.arguments) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .clickable { open = !open }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .width(5.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(
                        if (trace.isError) MaterialTheme.colorScheme.error
                        else LocalExtraColors.current.data.copy(alpha = 0.8f),
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "· " + toolActionLabel(line.text),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    trace.tool + if (args.isNotEmpty()) "（" + args + "）" else "",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                if (open) "▴" else "▾",
                style = LocalTrellisTokens.current.type.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        if (open) ToolStepDetail(trace)
    }
}

@Composable
private fun ToolStepDetail(trace: ChatToolTrace) {
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .padding(start = 13.dp, top = 4.dp, bottom = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (trace.description.isNotBlank()) {
            Text(
                trace.description,
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val args = remember(trace.arguments) { prettyArguments(trace.arguments) }
        if (args.isNotBlank() && args != "{}") {
            Text("参数", style = tokens.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Text(
                args,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 130.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Text("结果", style = tokens.type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        Text(
            trace.result.trim().ifBlank { "（没有输出）" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** 参数一行预览：折叠时只给人扫一眼，长 JSON 不进过程行。 */
internal fun compactArguments(raw: String, max: Int = 48): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return ""
    val flat = trimmed.replace(Regex("\\s+"), " ")
    return if (flat.length <= max) flat else flat.take(max) + "…"
}

/** 缩进用的 Json 实例只建一次（每次展开都 new 一个，Kotlin 会警告）。 */
private val prettyJson = Json { prettyPrint = true }

/** 参数详情：能解析成 JSON 就缩进排开，解析不了原样给。 */
internal fun prettyArguments(raw: String): String = runCatching {
    prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
}.getOrDefault(raw.trim())

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
    val tokens = LocalTrellisTokens.current
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
            style = tokens.type.meta,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// ---------------------------------------------------------------- 数据

internal sealed interface ChatBlock {
    data class User(val line: ChatLine) : ChatBlock

    /**
     * 一轮对话。[user] 可空：管家的主动消息没有用户那一句开头，
     * 但它同样是一轮——回合头、过程折叠、正文、建议卡、元信息都照常渲染。
     */
    data class Round(
        val user: ChatLine?,
        val plan: ChatLine?,
        val steps: List<ChatLine>,
        val reply: ChatLine?,
        val status: ChatLine?,
    ) : ChatBlock {
        /** 列表 key / 撤销/差异用的稳定锚点。 */
        val keyId: Long
            get() = user?.id ?: reply?.id ?: plan?.id ?: steps.firstOrNull()?.id ?: status?.id ?: 0L

        /** 耗时从这一轮的第一行算起。 */
        val startedAtMs: Long
            get() = user?.atMs ?: plan?.atMs ?: steps.firstOrNull()?.atMs ?: reply?.atMs ?: status?.atMs ?: 0L
    }
}

/** 清单属于哪一轮：从清单行往上找最近的一条用户消息，它才是这一轮的起点。 */
private fun planRoundKeyOf(lines: List<ChatLine>, planLine: ChatLine?): Long? {
    if (planLine == null) return null
    val index = lines.indexOfFirst { it.id == planLine.id }
    val head = if (index >= 0) lines.subList(0, index) else lines
    return head.lastOrNull { it.role == ChatRole.User }?.id
}
internal fun blocksOf(lines: List<ChatLine>): List<ChatBlock> {
    val out = mutableListOf<ChatBlock>()
    var user: ChatLine? = null
    var plan: ChatLine? = null
    var steps = mutableListOf<ChatLine>()
    var reply: ChatLine? = null
    var status: ChatLine? = null

    fun opened(): Boolean =
        user != null || plan != null || steps.isNotEmpty() || reply != null || status != null

    fun flush() {
        if (!opened()) return
        out += ChatBlock.Round(user, plan, steps.toList(), reply, status)
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
            // 没有用户那一句时（管家主动消息），每一步都可能开新的一轮：
            // 上一轮已经有答复、又来了新的发言，说明是下一条主动消息。
            ChatRole.Plan -> {
                if (user == null && reply != null) flush()
                plan = line
            }
            ChatRole.Step -> {
                if (user == null && reply != null) flush()
                steps += line
            }
            ChatRole.Assistant -> {
                if (user == null && reply != null) flush()
                reply = line
            }
            ChatRole.Status -> status = line
        }
    }
    flush()
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
    val start = round.startedAtMs
    val end = (round.status ?: round.reply)?.atMs ?: return ""
    if (start <= 0L || end <= start) return ""
    val seconds = (end - start) / 1000.0
    return if (seconds < 60) String.format("%.1fs", seconds) else String.format("%.1fmin", seconds / 60)
}

/**
 * 管家建议卡：一条模板化改动 + 逐条建议 + 三个动作。
 *
 * 采纳是直接执行（走计划后端命令），不是再让模型跑一轮；「调整一下」把话带进输入框；
 * 「先这样」只记账不动计划 —— 三选一，不猜用户想不想。
 *
 * 视觉上用紫罗兰渐变底板把「这是建议、不是对话结论」区分出来；建议多时框内滚动，
 * 标题和按钮固定，采纳永远点得到。
 */
@Composable
private fun StewardProposalCard(
    runtime: AppRuntime,
    line: ChatLine,
    proposal: StewardProposal,
) {
    val decided = line.proposalState != ProposalState.Open
    val tokens = LocalTrellisTokens.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 圆角跟卡片 token 走（批 5）：12dp 是批 1 之前写死的，比插件卡还圆。
            .clip(RoundedCornerShape(tokens.radius.card))
            .background(
                Brush.verticalGradient(
                    listOf(StewardViolet.copy(alpha = 0.16f), StewardViolet.copy(alpha = 0f)),
                ),
            )
            .border(1.dp, StewardViolet.copy(alpha = 0.28f), RoundedCornerShape(tokens.radius.card))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "管家建议",
            style = tokens.type.meta,
            color = StewardViolet,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(StewardViolet.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            proposal.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (proposal.detail.isNotBlank()) {
            Text(
                proposal.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 计划/待办建议逐条一张小底板；超过限高就框内滑，标题与按钮不跟着滚。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            proposal.changes.forEach { change ->
                val verb = if (change is StewardChange.Move) "挪到" else "排到"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StewardViolet.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(StewardViolet.copy(alpha = 0.6f)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(change.title, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "$verb ${change.day.takeLast(5)}",
                            style = tokens.type.meta.copy(fontFamily = FontFamily.Monospace),
                            color = StewardViolet,
                        )
                    }
                }
            }
        }
        if (decided) {
            Text(
                if (line.proposalState == ProposalState.Adopted) "已采纳" else "这次先不动",
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeButton(label = "一键采纳", onClick = { runtime.launchAdoptStewardProposal(line.id) })
                ChromeTextLink("调整一下", onClick = { runtime.negotiateStewardProposal(line.id) })
                ChromeTextLink("先这样", onClick = { runtime.dismissStewardProposal(line.id) })
            }
        }
    }
}

/**
 * 练习提议卡：学完一个单元后，AI 从题库里挑的一道题。
 *
 * 和管家建议卡分开画：这张只有「开始练习 / 先不练」两个动作，接受后走二级页
 * 只显示这一道题，不在会话里展开题面（会话是聊天记录，不是做题界面）。
 */
@Composable
private fun PracticeProposalCard(
    runtime: AppRuntime,
    line: ChatLine,
    proposal: PracticeProposal,
) {
    val decided = line.practiceState != PracticeProposalState.Open
    val tokens = LocalTrellisTokens.current
    val accent = tokens.colors.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radius.card))
            .background(tokens.colors.accentSoft)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(tokens.radius.card))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "练习建议",
            style = tokens.type.meta,
            color = tokens.colors.onAccentSoft,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(accent.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Text(
            proposal.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        if (proposal.detail.isNotBlank()) {
            Text(
                proposal.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            proposal.planTitle + " · " + proposal.subject,
            style = tokens.type.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (decided) {
            Text(
                if (line.practiceState == PracticeProposalState.Accepted) "已接受，练习页里只有这一道" else "这次先不练",
                style = tokens.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChromeButton(label = "开始练习", onClick = { runtime.acceptPracticeProposal(line.id) })
                Spacer(Modifier.width(6.dp))
                ChromeTextLink("先不练", onClick = { runtime.dismissPracticeProposal(line.id) })
            }
        }
    }
}

/**
 * 过程行里的动作名：模型看到的是工具名（`create_learning_plan`），用户看到的该是人话。
 * 没登记的工具原样露出——宁可难看，也不要瞎编一个意思。
 */
internal fun toolActionLabel(raw: String): String {
    val name = raw.substringBefore('(').trim()
    val arg = raw.substringAfter('(', "").substringBefore(')').trim()
    val label = when (name) {
        "create_learning_plan" -> "建了学习计划"
        "get_learning_plan" -> "查了学习计划"
        "plan_next_week" -> "排了下一周"
        "complete_plan_node" -> "勾了计划节点"
        "move_plan_node" -> "挪了计划节点"
        "review_last_week" -> "看了上周复盘"
        "set_plan_pace" -> "调了学习节奏"
        "add_tasks" -> "加了待办"
        "search_bilibili" -> "搜了 B 站课程"
        "list_bilibili_parts" -> "看了课程分集"
        "set_plan" -> "列了步骤"
        "step_done" -> "记了一步"
        "ask_choices" -> "问了你要选哪个"
        "try_plugin" -> "试装了插件"
        "deploy" -> "装了插件"
        "read_plugin" -> "读了插件代码"
        "list_plugins" -> "看了插件列表"
        "list_events", "list_actions" -> "查了插件能力"
        "disable_plugin" -> "停了插件"
        "remove_plugin" -> "删了插件"
        "list_cards" -> "看了卡片"
        "arrange_cards" -> "重排了卡片"
        "list_ui_options" -> "查了样式选项"
        "set_ui" -> "改了界面样式"
        "set_plugin_card" -> "调了单卡样式"
        "list_themes" -> "看了主题"
        "set_theme" -> "换了主题"
        "describe_screen" -> "看了界面结构"
        "take_screenshot" -> "截了屏"
        "open_tab" -> "切了页面"
        "click" -> "点了界面"
        "type_text" -> "填了输入框"
        "press_key" -> "按了快捷键"
        "get_diagnostics" -> "查了运行环境"
        else -> return raw
    }
    // 参数只在很短的时候带上：过程行是给人扫一眼的，不是日志。
    return if (arg.isNotEmpty() && arg.length <= 18) label + "（" + arg + "）" else label
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

/** 对话列的最大宽度：窗口再宽也只到这里；左右留白因此对称。 */
private val MaxConversationWidth = 760.dp

/** 管家卡的专属强调色（不进入全局主题）：紫罗兰，和 Bento 的强调色区分开。 */
private val StewardViolet = Color(0xFF8B6CE0)
