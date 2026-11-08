package dev.trellis.app.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.desktop.ChatBlock
import dev.trellis.app.desktop.blocksOf
import dev.trellis.app.desktop.ChatLine
import dev.trellis.app.desktop.ChatRole
import dev.trellis.app.desktop.PlanStepState
import dev.trellis.app.desktop.ProposalState
import dev.trellis.app.desktop.SessionKind
import dev.trellis.app.desktop.StewardChange

/**
 * 对话：和一个「插件管理员 / 管家」说话。
 *
 * 手机上的形态是聊天软件，不是「消息板 + 侧栏」：底部固定输入条、消息从下往上长、
 * 会话切换收进弹层。步骤过程（`Step` / `Status`）用等宽小字贴在气泡下方，
 * 不抢正文的位置 —— 桌面上它们是缩进块，手机上必须更省纵向空间。
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
    var expandedSteps by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var note by remember { mutableStateOf<String?>(null) }

    // 必须按「内容变化」做 key，不能只拿 runtime.chat 当 key：
    // 它是同一个 mutableStateList 实例，`newChat()` 清空、`ask()` 再写入之后 key 没变，
    // remember 会把**上一次会话**的 blocks 一直还回来 —— 表现是「发了消息，界面不动」，
    // 而消息其实已经落盘、模型也回了。
    val blocks = remember(runtime.chat.size, runtime.chat.lastOrNull()?.id) { blocksOf(runtime.chat) }
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
        if (runtime.chat.isNotEmpty()) {
            runCatching { listState.scrollToItem(runtime.chat.lastIndex) }
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
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = Gap.l, vertical = Gap.s),
                verticalArrangement = Arrangement.spacedBy(Gap.m),
            ) {
                blocks.forEach { block ->
                    when (block) {
                        is ChatBlock.User -> item(key = "u" + block.line.id) {
                            UserBubble(block.line)
                        }
                        is ChatBlock.Round -> item(key = "r" + block.keyId) {
                            RoundView(
                                runtime = runtime,
                                round = block,
                                isLast = block === blocks.lastOrNull(),
                                expanded = block.keyId in expandedSteps,
                                onToggleSteps = {
                                    expandedSteps = if (block.keyId in expandedSteps) {
                                        expandedSteps - block.keyId
                                    } else {
                                        expandedSteps + block.keyId
                                    }
                                },
                                note = note,
                                onNote = { note = it },
                            )
                        }
                    }
                }
            }
        }

        // 模型在等用户拍板：手机上没有侧栏，问题 + 选项贴在输入框上方（桌面端排进回复里）。
        runtime.chatAsk?.let { ask ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gap.l)
                    .padding(bottom = Gap.s),
            ) {
                Text(
                    "问题",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(ask.question, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(Gap.xs))
                ask.options.forEach { choice ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(LocalCardColor.current)
                            .clickable { runtime.applyChoice(choice) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
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
 * 过程和清单默认折起来，只留一行「过程 · N 步 · 5.2s」—— 手机上把十几行工具日志
 * 铺开，回答就被挤到屏幕外了。想看的点一下展开。
 */
@Composable
private fun RoundView(
    runtime: AppRuntime,
    round: ChatBlock.Round,
    isLast: Boolean,
    expanded: Boolean,
    onToggleSteps: () -> Unit,
    note: String?,
    onNote: (String?) -> Unit,
) {
    // 管家主动消息没有用户那一句：keyId 会退到这一轮的第一行，撤销/差异仍能对上。
    val key = round.keyId
    val undone = runtime.roundIsUndone(key)
    val running = isLast && runtime.chatBusy
    val steps = round.steps
    val toolNames = steps.mapNotNull { step ->
        Regex("^第 \\d+ 步：(.+)$").find(step.text)?.groupValues?.get(1)?.trim()
    }.filter { it.isNotEmpty() }

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

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(6.dp).height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            running -> MaterialTheme.colorScheme.primary
                            round.status?.text?.contains("没做成") == true -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
            )
            Spacer(Modifier.width(6.dp))
            Text("Trellis", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(Gap.s))
            Text(
                when {
                    running -> "运行中"
                    round.status == null -> "已结束"
                    else -> round.status.text.take(24)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (steps.isNotEmpty()) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "收起过程" else "过程 " + steps.size + " 步",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onToggleSteps() }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(Gap.xs))
            steps.forEach { step -> ProcessLine(step) }
            if (toolNames.isNotEmpty()) {
                Spacer(Modifier.height(Gap.xs))
                Text(
                    "用了：" + toolNames.distinct().joinToString("、"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }

        // 清单：先有计划、再逐条打勾。手机上只显示「正在做什么」和结果，
        // 完整清单点开过程才看。
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
        if (reply != null && reply.text.isNotBlank()) {
            Spacer(Modifier.height(Gap.s))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Column(
                    Modifier
                        .fillMaxWidth(0.94f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(LocalCardColor.current)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(reply.text, style = MaterialTheme.typography.bodyMedium)
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
                                runtime.copyToClipboard(reply.text)
                                onNote("已复制这一段")
                            }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // 撤销这一轮：只在这一轮真的改过东西时出现（diff 为空就不摆一个假按钮）。
        if (!running && !undone && runtime.hasRoundActions(key)) {
            Spacer(Modifier.height(Gap.xs))
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
        if (undone) {
            Spacer(Modifier.height(Gap.xs))
            Text(
                "这一轮已撤销",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProcessLine(line: ChatLine) {
    when (line.role) {
        ChatRole.User -> UserBubble(line)
        ChatRole.Assistant -> Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        else -> Text(
            line.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.padding(start = 4.dp),
        )
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
