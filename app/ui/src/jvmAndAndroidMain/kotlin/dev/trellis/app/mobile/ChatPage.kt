package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.ai.tool.DeepSeekMarkup
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.ChatAsk
import dev.trellis.app.desktop.ChatBlock
import dev.trellis.app.desktop.ChatChoice
import dev.trellis.app.desktop.ChatLine
import dev.trellis.app.desktop.ChatToolTrace
import dev.trellis.app.desktop.MarkdownText
import dev.trellis.app.desktop.PlanStepState
import dev.trellis.app.desktop.PracticeProposal
import dev.trellis.app.desktop.PracticeProposalState
import dev.trellis.app.desktop.ProcessRowView
import dev.trellis.app.desktop.ProposalState
import dev.trellis.app.desktop.SessionKind
import dev.trellis.app.desktop.StewardChange
import dev.trellis.app.desktop.ThinkingLabel
import dev.trellis.app.desktop.blocksOf
import dev.trellis.app.desktop.prettyArguments
import dev.trellis.app.desktop.processPanelMaxHeight
import dev.trellis.app.desktop.processRowView
import dev.trellis.app.desktop.toolActionLabel

/**
 * 对话：和一个「插件管理员 / 管家」说话。
 *
 * 手机上的形态是聊天软件，不是「消息板 + 侧栏」：底部固定输入条、消息从下往上长、
 * 会话切换收进独立页面。过程和清单默认折起来 —— 手机上把十几行工具日志铺开，
 * 回答就被挤到屏幕外了；想看的点回合头那一行（状态字后面的 ▾）展开。
 *
 * 一轮 = 用户一句话 + 若干过程 + 一段回答 + 一个状态。所以「撤销这一轮」挂在轮尾，
 * 而不是每条消息上：撤销的对象是这一轮改过的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPage(runtime: AppRuntime) {
    val listState = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var sessionsOpen by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // 过程区两种展开状态都提在这一层：
    // - openRounds 记哪些回合被点开过（默认全部折叠，运行中也不自动加）；
    // - openStepId 保证同一时刻整个对话只展开一条过程，点另一条会自动收起上一条。
    var openRounds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var openStepId by remember { mutableStateOf<Long?>(null) }
    // 输入框上方「还有一问没答」点击后需要滚到底：用状态触发而不是在点击里拿 scope。
    var scrollToAsk by remember { mutableStateOf(false) }

    // 必须按「内容变化」做 key，不能只拿 runtime.chat 当 key：
    // 它是同一个 mutableStateList 实例，`newChat()` 清空、`ask()` 再写入之后 key 没变，
    // remember 会把**上一次会话**的 blocks 一直还回来 —— 表现是「发了消息，界面不动」，
    // 而消息其实已经落盘、模型也回了。text.length 这一项负责流式回答的逐段刷新。
    val blocks = remember(
        runtime.chat.size,
        runtime.chat.lastOrNull()?.id,
        runtime.chat.lastOrNull()?.text?.length,
    ) { blocksOf(runtime.chat) }
    val steward = runtime.chatSessions.firstOrNull { it.id == runtime.currentChatId }?.kind == SessionKind.Steward

    // 会话列表是**一整页**，不是弹层：弹层盖住的是输入框和最后几条消息，
    // 而「换个会话」这件事本身就该把当前对话收起来 —— 两件事同屏只会互相挤。
    if (sessionsOpen) {
        ChatSessionPage(
            runtime = runtime,
            onBack = { sessionsOpen = false },
            onOpen = { id ->
                runtime.markChatRead(id)
                runtime.openChat(id)
                sessionsOpen = false
            },
        )
        return
    }

    LaunchedEffect(runtime.chat.size, runtime.chat.lastOrNull()?.text?.length) {
        // 流式回答时这个 effect 每 60ms 会重启一次。用 animate 的话每次都在
        // 「起一段新动画 → 被取消」，主线程全花在取消动画上；直接跳到底更省，
        // 而且回答是往下长的，用户本来就在看末尾。
        if (blocks.isNotEmpty()) {
            runCatching { listState.scrollToItem(blocks.lastIndex) }
        }
    }

    LaunchedEffect(scrollToAsk) {
        if (scrollToAsk) {
            if (blocks.isNotEmpty()) {
                // 提问卡排在最后一轮的结论之后，不是独立列表项；给一个足够大的
                // offset 等于「滚到这一项的下沿」，它才会真正出现在屏幕里。
                listState.scrollToItem(blocks.lastIndex, 10_000)
            }
            scrollToAsk = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Gap.l, vertical = Gap.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (steward) "管家" else "对话", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        runtime.chatBusy -> "正在处理…"
                        steward -> "每天早晚看一眼，能改的才提建议"
                        else -> "说一句话，它来动手"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PhoneButton("会话", { sessionsOpen = true }, filled = false)
            Spacer(Modifier.width(Gap.s))
            PhoneButton("新对话", { runtime.newChat() })
        }

        if (runtime.chat.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.padding(horizontal = Gap.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有对话", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(Gap.s))
                    Text("先说你要什么，再让它动手", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(Gap.m))
                    listOf(
                        "我想学计算机网络，六周后考试，帮我排个计划",
                        "把本周完成情况做成一张卡",
                        "现在装了哪些插件？",
                    ).forEach { example ->
                        Box(
                            Modifier
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(LocalCardColor.current)
                                .clickable { draft = example }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(example, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // 过程窗口最多占视口 55%，钳 220–560dp：再多的工具日志也不能把回答顶出屏幕。
                val processPanelMax = processPanelMaxHeight(maxHeight)
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Gap.l, vertical = Gap.s),
                    verticalArrangement = Arrangement.spacedBy(Gap.m),
                ) {
                    // key 带上序号：历史会话里可能有重复的行 id（旧版本/恢复文件），
                    // 只用 id 会撞键，LazyColumn 直接抛异常。
                    itemsIndexed(
                        items = blocks,
                        key = { index, block ->
                            when (block) {
                                is ChatBlock.User -> "u" + index + "-" + block.line.id
                                is ChatBlock.Round -> "r" + index + "-" + block.keyId
                            }
                        },
                    ) { index, block ->
                        when (block) {
                            is ChatBlock.User -> UserBubble(block.line)
                            is ChatBlock.Round -> RoundView(
                                runtime = runtime,
                                round = block,
                                isLast = index == blocks.lastIndex,
                                stewardSession = steward,
                                processOpen = block.keyId in openRounds,
                                onToggleProcess = {
                                    openRounds = if (block.keyId in openRounds) {
                                        openRounds - block.keyId
                                    } else {
                                        openRounds + block.keyId
                                    }
                                },
                                openStepId = openStepId,
                                onToggleStep = { id ->
                                    openStepId = if (openStepId == id) null else id
                                },
                                processPanelMax = processPanelMax,
                                liveAsk = if (index == blocks.lastIndex) runtime.chatAsk else null,
                                onPickChoice = { runtime.applyChoice(it) },
                                onNote = { note = it },
                            )
                        }
                    }
                }
            }
        }

        // 模型在等用户拍板：提问卡已经排进这一轮的结论之后，输入框上方只留一行
        // 跳转提醒 —— 别再占一块屏幕重复问题和选项。
        if (runtime.chatAsk != null && !runtime.chatBusy) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gap.l)
                    .padding(bottom = Gap.xs)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable { scrollToAsk = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "还有一问没答，点这里去看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        note?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = Gap.l).padding(bottom = Gap.xs),
            )
        }

        if (!runtime.llmConfigured()) {
            CardBox(Modifier.padding(horizontal = Gap.l).padding(bottom = Gap.m)) {
                Text("还没有配置模型 API key", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "去「我的 → 模型 API → 导入配置」，把服务商给的那段粘进去就行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Gap.l).padding(bottom = Gap.m),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f)) {
                    PhoneField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = if (steward) "回管家一句" else "说一件要它做的事",
                        singleLine = false,
                        minLines = 1,
                    )
                }
                Spacer(Modifier.width(Gap.s))
                if (runtime.chatBusy) {
                    PhoneButton("停止", { runtime.stopChat() }, danger = true)
                } else {
                    PhoneButton(
                        "发送",
                        enabled = draft.isNotBlank(),
                        onClick = {
                            val text = draft
                            draft = ""
                            // 交给 runtime 自己的 scope：以前用页面的 rememberCoroutineScope，
                            // agent 一调 open_tab 切页，这一轮就被连根取消（桌面上踩过）。
                            // 而且 ask() 会把漏网异常写进 app.log，不再直接闪退。
                            runtime.ask(text)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 会话列表页。
 *
 * 和「当前对话」是**两个页面**：进来只做一件事——挑一个会话或删掉它。
 * 列表项右边留删除位，管家会话不给删（它是系统造的，删了下次还会来）。
 */
@Composable
private fun ChatSessionPage(runtime: AppRuntime, onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = Gap.l)) { SubPageBar("会话", onBack) }
        Spacer(Modifier.height(Gap.s))
        Text(
            "会话记录落盘，关掉应用再打开还能接着聊",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Gap.l),
        )
        Spacer(Modifier.height(Gap.m))

        if (runtime.chatSessions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Text(
                    "还没有会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Gap.xl),
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = Gap.l, end = Gap.l, bottom = Gap.xxl),
            verticalArrangement = Arrangement.spacedBy(Gap.s),
        ) {
            items(runtime.chatSessions, key = { it.id }) { session ->
                val active = session.id == runtime.currentChatId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else LocalCardColor.current)
                        .clickable { onOpen(session.id) }
                        .padding(horizontal = Gap.m, vertical = Gap.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (session.kind == SessionKind.Steward) {
                                Pill("管家", Tone.Good)
                                Spacer(Modifier.width(Gap.xs))
                            }
                            if (session.unread) {
                                Pill("未读", Tone.Warn)
                                Spacer(Modifier.width(Gap.xs))
                            }
                            Text(
                                session.title.ifBlank { "新对话" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            agoLabel(session.updatedAt, runtime.clock.nowMs()) + " · " + session.lines.size + " 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (session.kind != SessionKind.Steward) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .semantics { contentDescription = "删除会话「" + session.title + "」" }
                                .clickable { runtime.deleteChat(session.id) }
                                .padding(8.dp),
                        ) {
                            Text(
                                "✕",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(line: ChatLine) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(line.text, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * 一轮：用户那句话 + 过程 + 回答 + 状态。
 *
 * 回合头整行就是过程的折叠把手：状态字后面紧跟 ▾/▴，默认折起来，运行中也不自动展开。
 * 展开后每条过程只占一行（思考 / 工具名 / 判断 / 结果），点某一行才看正文、参数、结果；
 * 整块限高内滚，同一时刻只展开一条（openStepId 由会话层持有）。
 */
@Composable
private fun RoundView(
    runtime: AppRuntime,
    round: ChatBlock.Round,
    isLast: Boolean,
    stewardSession: Boolean,
    processOpen: Boolean,
    onToggleProcess: () -> Unit,
    openStepId: Long?,
    onToggleStep: (Long) -> Unit,
    processPanelMax: Dp,
    /** 模型这一轮在等用户拍板（`ask_choices`）：问题和选项排在结论之后。 */
    liveAsk: ChatAsk?,
    onPickChoice: (ChatChoice) -> Unit,
    onNote: (String?) -> Unit,
) {
    // 管家主动消息没有用户那一句：keyId 会退到这一轮的第一行，撤销/差异仍能对上。
    val key = round.keyId
    val undone = runtime.roundIsUndone(key)
    val running = isLast && runtime.chatBusy
    val steps = round.steps
    val failed = round.status?.text?.let {
        it.contains("没做成") || it.contains("出错") || it.contains("已停止")
    } ?: false
    // 清单没结清但这一轮已经不在跑：只能是被打断/崩溃恢复，说「完成」是撒谎。
    val planUnfinished = round.plan?.plan?.any {
        it.state == PlanStepState.Running || it.state == PlanStepState.Pending
    } == true
    var diffOpen by remember(key) { mutableStateOf(false) }
    val diff = remember(key, round.status?.id, runtime.chatBusy) { runtime.diffForRound(key) }
    val dotColor = when {
        running -> MaterialTheme.colorScheme.primary
        failed -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when {
        running -> "运行中"
        planUnfinished -> "中断"
        failed -> "没做成"
        else -> "完成"
    }
    val duration = if (running) "" else roundDurationLabel(round)

    Column(Modifier.alpha(if (undone) 0.55f else 1f)) {
        val opener = round.user
        if (opener != null) {
            UserBubble(opener)
        } else {
            // 主动消息：没有「用户那一句」，用一个小标签代替，保持时间线可读。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("管家", Tone.Good)
                Spacer(Modifier.width(Gap.xs))
                Text(
                    "主动说的",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(Gap.s))

        // 回合头：谁在说 + 状态 + 耗时；有过程时整行是折叠把手（折角紧跟状态字）。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (steps.isNotEmpty()) {
                        Modifier.clickable { onToggleProcess() }
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(6.dp).height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(dotColor),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (stewardSession) "管家" else "Trellis",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = PhoneMono,
                color = dotColor,
                maxLines = 1,
            )
            if (duration.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    duration,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = PhoneMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
            if (steps.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    if (processOpen) "▴" else "▾",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                )
            }
            Spacer(Modifier.weight(1f))
            if (running) {
                Text(
                    "停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { runtime.stopChat() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        if (steps.isNotEmpty() && processOpen) {
            Spacer(Modifier.height(Gap.xs))
            ProcessRows(
                steps = steps,
                running = running,
                openStepId = openStepId,
                maxHeight = processPanelMax,
                onToggle = onToggleStep,
            )
        }

        // 清单：先有计划、再逐条打勾。手机没有桌面那种右侧常驻栏，
        // 就地把这一轮的清单跟在过程后面展示。
        round.plan?.let { planLine ->
            if (planLine.plan.isNotEmpty()) {
                Spacer(Modifier.height(Gap.s))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LocalCardColor.current)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    planLine.plan.forEach { step ->
                        val mark = when (step.state) {
                            PlanStepState.Done -> "✓"
                            PlanStepState.Failed -> "✕"
                            PlanStepState.Skipped -> "–"
                            PlanStepState.Running -> "▸"
                            else -> "·"
                        }
                        Text(
                            "$mark ${step.text}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (step.state) {
                                PlanStepState.Done -> MaterialTheme.colorScheme.primary
                                PlanStepState.Failed -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }

        val reply = round.reply
        val conclusion = reply?.text.orEmpty()
        if (conclusion.isBlank() && running) {
            Spacer(Modifier.height(Gap.s))
            Text(
                "…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (reply != null && conclusion.isNotBlank()) {
            Spacer(Modifier.height(Gap.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Column(
                    Modifier
                        .fillMaxWidth(0.94f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocalCardColor.current)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    // agent 的答复天然是 Markdown（小标题/清单/代码块）：和桌面共用
                    // 同一套按块渲染，不再把 ** 和 - 原样丢给用户看。历史里可能还留着
                    // 旧版本存下的协议壳（DSML 方言），这里再剥一遍。
                    MarkdownText(
                        DeepSeekMarkup.strip(conclusion),
                        modifier = Modifier.fillMaxWidth(),
                        bodyStyle = MaterialTheme.typography.bodyMedium,
                    )
                    val proposal = reply.proposal
                    if (proposal != null) {
                        Spacer(Modifier.height(Gap.m))
                        StewardProposalCard(runtime, reply)
                    }
                    Spacer(Modifier.height(Gap.xs))
                    Text(
                        "复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                runtime.copyToClipboard(conclusion)
                                onNote("已复制这一段")
                            }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // 提问卡紧跟结论：先读它的判断，再回答问题。收尾前不显示 ——
        // 这一轮还在跑时点下去会被 ask() 的门闩挡掉。
        liveAsk?.takeIf { !running }?.let { ask ->
            Spacer(Modifier.height(Gap.s))
            AskCard(ask = ask, onPick = onPickChoice)
        }

        round.reply?.let { replyLine ->
            replyLine.practiceProposal?.let { practice ->
                Spacer(Modifier.height(Gap.s))
                PracticeProposalCard(runtime, replyLine, practice)
            }
        }

        val meta = metaLabel(round)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(Gap.xs))
            Text(
                meta,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = PhoneMono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }

        if (failed) {
            Spacer(Modifier.height(Gap.s))
            FailureBlock(round, steps, diff)
        }

        if (!running && !undone && (runtime.hasRoundActions(key) || diff.isNotEmpty())) {
            Spacer(Modifier.height(Gap.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (runtime.hasRoundActions(key)) {
                    Text(
                        "撤销这一轮",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                val result = runtime.undoRound(key)
                                onNote(result)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                if (diff.isNotEmpty()) {
                    Spacer(Modifier.width(Gap.xs))
                    Text(
                        "看差异",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { diffOpen = !diffOpen }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (undone) {
            Spacer(Modifier.height(Gap.xs))
            Text(
                "这一轮已撤销",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (diffOpen) {
            Spacer(Modifier.height(Gap.xs))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
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
                    diff.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = PhoneMono,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 过程窗口：限高内滚，每条只看一行。
 *
 * 这一块只负责排版「一行」，把长文本裁掉；正文/参数/结果在点开时由
 * [ProcessTextRow] / [ToolStepDetail] 展开，避免一展开就铺满整屏。
 */
@Composable
private fun ProcessRows(
    steps: List<ChatLine>,
    running: Boolean,
    openStepId: Long?,
    maxHeight: Dp,
    onToggle: (Long) -> Unit,
) {
    val scroll = rememberScrollState()
    // 运行中自动跟到底：用户关心的是最新一条在干什么。
    LaunchedEffect(steps.size, steps.lastOrNull()?.text?.length, scroll.maxValue) {
        if (running && scroll.maxValue > 0) {
            scroll.animateScrollTo(scroll.maxValue)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        steps.forEachIndexed { index, line ->
            val trace = line.tool
            val view = if (trace == null) processRowView(line.text) else null
            when {
                trace != null -> ToolStepRow(
                    trace = trace,
                    open = openStepId == line.id,
                    onClick = { onToggle(line.id) },
                )
                view != null -> ProcessTextRow(
                    view = view,
                    open = openStepId == line.id,
                    active = running && index == steps.lastIndex,
                    onClick = { onToggle(line.id) },
                )
            }
        }
    }
}

/** 一行文字过程：思考、判断、结果、状态。有正文时才可点开。 */
@Composable
private fun ProcessTextRow(view: ProcessRowView, open: Boolean, active: Boolean, onClick: () -> Unit) {
    val label = if (active && view.label == ThinkingLabel) "思考中" else view.label
    val detail = view.detail
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (detail != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = PhoneMono,
                color = if (label.contains("出错")) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (detail != null) {
                Text(
                    if (open) "▴" else "▾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        if (open && detail != null) {
            Text(
                detail,
                style = processDetailStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/** 一行工具过程：只写工具名 + 状态点；点开才看参数、结果和宿主提示。 */
@Composable
private fun ToolStepRow(trace: ChatToolTrace, open: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(5.dp).height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            trace.isError -> MaterialTheme.colorScheme.error
                            trace.running -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        },
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                trace.tool,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = PhoneMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    trace.running -> "运行中"
                    open -> "▴"
                    else -> "▾"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        if (open) {
            ToolStepDetail(trace)
        }
    }
}

@Composable
private fun ToolStepDetail(trace: ChatToolTrace) {
    val args = remember(trace.arguments) { prettyArguments(trace.arguments) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 11.dp, top = 2.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            toolActionLabel(trace.tool),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 宿主纠正模型的返工（格式不对被要求重说、想调不存在的工具）不算过程，
        // 只在这里留一句提示 —— 用户需要知道「为什么重跑了一遍」。
        if (trace.notes.isNotEmpty()) {
            Text(
                "宿主提示",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            trace.notes.forEach { note ->
                Text(
                    note,
                    style = processDetailStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (args.isNotBlank() && args != "{}") {
            Text("参数", style = MaterialTheme.typography.labelSmall)
            Text(
                args,
                style = processDetailStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("结果", style = MaterialTheme.typography.labelSmall)
        Text(
            if (trace.running) {
                "（还在跑…）"
            } else {
                trace.result.trim().ifBlank { "（没有输出）" }
            },
            style = processDetailStyle(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 提问卡：桌面把 `ask_choices` 排进当轮回复，手机同样排在结论之后。
 *
 * 选项按编号排（问题在上），多了在框内滚动；点一下就是这一问的答案。
 */
@Composable
private fun AskCard(ask: ChatAsk, onPick: (ChatChoice) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(LocalCardColor.current)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            "问题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(ask.question, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(Gap.xs))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 264.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ask.options.forEachIndexed { index, choice ->
                if (index > 0) DividerLine()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(choice) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = PhoneMono,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(28.dp),
                    )
                    Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/**
 * 失败块：这一轮没做成时把四件事说清楚，别让用户只看到「原因见上」。
 *
 * 失败原因在 `status` 行里（AppRuntime 写入），过程最后一条通常就是卡住的那一步；
 * 差异由「看差异」按钮展开，和撤销共用同一份快照数据。
 */
@Composable
private fun FailureBlock(round: ChatBlock.Round, steps: List<ChatLine>, diff: List<String>) {
    val lastStep = steps.lastOrNull { it.text.contains("出错") }?.text
        ?: steps.lastOrNull()?.text
        ?: "（没有留下过程）"
    val reason = round.status?.text?.takeIf { it.isNotBlank() } ?: "没有说明"
    val data = if (diff.isEmpty()) "没有改动宿主数据" else "改动了 ${diff.size} 处（点「看差异」查看）"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "这一轮没做成",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text("卡在哪一步：$lastStep", style = MaterialTheme.typography.bodySmall, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        Text("为什么：$reason", style = MaterialTheme.typography.bodySmall, maxLines = 3,
            overflow = TextOverflow.Ellipsis)
        Text("数据动没动：$data", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 练习提议卡：学完单元后管家提的一道题。
 *
 * 接受动作由 runtime 负责标记状态并切到练习页（二级页由 PhoneApp 承载）；
 * 卡片自己只负责讲清「这一道是什么」，并显示已接受 / 这次先不练，不自行切页。
 */
@Composable
private fun PracticeProposalCard(runtime: AppRuntime, line: ChatLine, proposal: PracticeProposal) {
    val decided = line.practiceState != PracticeProposalState.Open
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.07f))
            .border(
                1.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                RoundedCornerShape(12.dp),
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "练习建议",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(proposal.title, style = MaterialTheme.typography.titleSmall)
        if (proposal.detail.isNotBlank()) {
            Text(
                proposal.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            listOf(proposal.planTitle, proposal.subject).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (decided) {
            Text(
                if (line.practiceState == PracticeProposalState.Accepted) {
                    "已接受，练习页里只有这一道"
                } else {
                    "这次先不练"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.s)) {
                PhoneButton("开始练习", { runtime.acceptPracticeProposal(line.id) })
                PhoneButton("先不练", { runtime.dismissPracticeProposal(line.id) }, filled = false)
            }
        }
    }
}

/**
 * 管家建议卡：模板化的一条「把 A 挪到周三」。
 *
 * 三个动作的语义不一样，不能合并：采纳 = 直接执行；调整一下 = 回到对话里协商；
 * 先这样 = 这次不动（会写进记忆，下次不再提同一件事）。
 */
@Composable
private fun StewardProposalCard(runtime: AppRuntime, line: ChatLine) {
    val proposal = line.proposal ?: return
    val decided = line.proposalState != ProposalState.Open
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("管家建议", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(proposal.title, style = MaterialTheme.typography.bodyMedium)
        // 变更条目可能十几条：内容区限高内滚，别让一张建议卡把整屏顶掉；
        // 三个动作按钮留在滚动区外面，随时可点。
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (proposal.detail.isNotBlank()) {
                Text(
                    proposal.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            proposal.changes.forEach { change ->
                val verb = if (change is StewardChange.Move) "挪到" else "排到"
                Text(
                    "· 把「${change.title}」$verb ${dayShort(change.day)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (decided) {
            Text(
                if (line.proposalState == ProposalState.Adopted) "已采纳" else "这次先不动",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Gap.xs)) {
                PhoneButton("采纳", { runtime.launchAdoptStewardProposal(line.id) }, filled = true)
                PhoneButton("调整一下", { runtime.negotiateStewardProposal(line.id) }, filled = false)
                PhoneButton("先这样", { runtime.dismissStewardProposal(line.id) }, filled = false)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 文案小工具：只在这一层拼「耗时 / 元信息」，领域层不碰展示格式。
// ---------------------------------------------------------------------------

/** 过程正文统一 12sp 等宽，和桌面 L4 的「等宽过程窗口」保持同一语气。 */
@Composable
private fun processDetailStyle() = MaterialTheme.typography.bodySmall.copy(
    fontFamily = PhoneMono,
    fontSize = 12.sp,
    lineHeight = 19.sp,
)

/** 这一轮耗时：`3.2s` / `1.4min`；未结束或没有起点时返回空串。 */
private fun roundDurationLabel(round: ChatBlock.Round): String {
    val start = round.startedAtMs
    val end = (round.status ?: round.reply)?.atMs ?: return ""
    if (start <= 0L || end <= start) return ""
    val seconds = (end - start) / 1000.0
    return if (seconds < 60) {
        "%.1fs".format(seconds)
    } else {
        "%.1fmin".format(seconds / 60)
    }
}

/** 轮尾元信息：`HH:mm · N 步 · 5.2s`。 */
private fun metaLabel(round: ChatBlock.Round): String {
    val start = round.startedAtMs
    if (start <= 0L) return ""
    val duration = roundDurationLabel(round)
    val base = timeText(start) + " · " + round.steps.size + " 步"
    return if (duration.isEmpty()) base else base + " · " + duration
}
