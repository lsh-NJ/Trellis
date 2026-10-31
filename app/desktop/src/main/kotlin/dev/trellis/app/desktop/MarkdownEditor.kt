package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 知识库的 Markdown 编辑器。
 *
 * 设计取舍：
 *
 * - **界面上不放格式工具栏**：十几种语法摆成按钮既占地方又记不住。格式从两个地方进：
 *   敲 `/` 唤起命令菜单（[markdownCommands]），或者直接按快捷键（[markdownKeyBindings]）；
 *   `Ctrl/Cmd+/` 随时调出完整清单，不用背。
 * - 纯本地：文本只经过 [onBodyChange] 回到页面状态，页面负责落盘；
 * - 所有文本变换走 [MarkdownEditResult] 纯函数，选区/光标可测试；
 * - 编辑 / 预览切换，预览复用会话页的 [MarkdownText]，不另造渲染器；
 * - 右侧目录（[MarkdownOutlinePanel]）可收起、可拖动改宽，点标题跳到正文对应位置；
 * - 回车自动续列表、空项回车退出列表——写长笔记时最省事的一个细节。
 */
@Composable
internal fun MarkdownEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    preview: Boolean,
    onTogglePreview: () -> Unit,
    statusText: String,
    modifier: Modifier = Modifier,
    /** 快捷键清单的开关由页面管（预览态没有输入框，按键要落到页面才收得到）。 */
    shortcutsOpen: Boolean = false,
    onToggleShortcuts: () -> Unit = {},
    /** 概念详情点「定位」时选中正文里的命中处；null = 不定位。 */
    reveal: TextRange? = null,
    /** 每次点「定位」+1：同一个范围再点一次也能重新选中（range 相同时靠它触发）。 */
    revealToken: Int = 0,
) {
    // 每篇笔记一个编辑缓冲；切笔记时重置。光标/选区留在内部，页面只关心文本。
    var field by remember(noteId) { mutableStateOf(TextFieldValue(body)) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // 斜杠命令菜单：slashStart = 那个 `/` 的字符下标，slashQuery = `/` 之后已经打进去的过滤词。
    var slashStart by remember(noteId) { mutableStateOf<Int?>(null) }
    var slashQuery by remember(noteId) { mutableStateOf("") }
    var slashIndex by remember(noteId) { mutableStateOf(0) }

    // 目录：随正文重算；折叠状态按笔记分开记。
    val outline = remember(body) { parseOutline(body) }
    var collapsedOutline by remember(noteId) { mutableStateOf(emptySet<Int>()) }
    val sidebarVisible = MarkdownSidebarState.visible

    // 预览态跳转：先记每块的纵坐标，再滚过去。
    val previewScroll = rememberScrollState()
    val blockOffsets = remember { mutableStateMapOf<Int, Int>() }

    // 页面可能从文件重新载入同一篇（例如初次打开），外部文本变化时跟上；
    // 用户自己打字时 field.text == body，不会触发重置光标。
    LaunchedEffect(body) {
        if (field.text != body) field = TextFieldValue(body)
    }

    // 打开/切换笔记就把焦点交给编辑区：进来就能打字，快捷键也不用先点一下。
    LaunchedEffect(noteId) {
        if (noteId.isNotEmpty()) focus.requestFocus()
    }

    // 概念详情点「定位」：选中命中处并把焦点交回编辑区（预览态没有光标，切回编辑再选）。
    LaunchedEffect(revealToken, preview) {
        if (preview) return@LaunchedEffect
        val requested = reveal ?: return@LaunchedEffect
        if (requested.start < 0) return@LaunchedEffect
        val start = requested.start.coerceIn(0, body.length)
        val end = requested.end.coerceIn(start, body.length)
        field = TextFieldValue(body, selection = TextRange(start, end))
        focus.requestFocus()
    }

    fun apply(result: MarkdownEditResult) {
        field = TextFieldValue(result.text, result.selection)
        onBodyChange(result.text)
        slashStart = null
        slashQuery = ""
        focus.requestFocus()
    }

    fun isCommand(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
        event.isCtrlPressed || event.isMetaPressed

    val slashMatches = remember(slashQuery) { filterMarkdownCommands(slashQuery) }

    fun applySlash(command: MarkdownCommand) {
        val start = slashStart ?: return
        apply(applySlashCommand(field.text, start, field.selection.max, command))
    }

    /** 正文变化时维护斜杠状态：还在 `/…` 里就跟着更新，移出去就关掉。 */
    fun refreshSlash(updated: TextFieldValue) {
        val cursor = updated.selection.max
        val start = slashStart
        if (start != null) {
            val query = slashQueryAt(updated.text, start, cursor)
            if (query == null) {
                slashStart = null
                slashQuery = ""
            } else if (query != slashQuery) {
                slashQuery = query
                slashIndex = 0
            }
        } else if (updated.selection.collapsed) {
            val trigger = slashTriggerStart(updated.text, cursor)
            if (trigger != null) {
                slashStart = trigger
                slashQuery = ""
                slashIndex = 0
            }
        }
    }

    /** 目录跳转：编辑态挪光标（输入框会把光标滚进可视区），预览态按块坐标滚动。 */
    fun jumpTo(item: OutlineItem) {
        if (preview) {
            val headingBlocks = parseMarkdown(body)
                .withIndex()
                .filter { it.value is MdBlock.Heading }
            val blockIndex = headingBlocks.getOrNull(item.ordinal)?.index ?: return
            scope.launch {
                // 首帧还没量完坐标：连着几帧试一下，拿到就滚。
                repeat(8) {
                    val y = blockOffsets[blockIndex]
                    if (y != null) {
                        previewScroll.animateScrollTo(y)
                        return@launch
                    }
                    delay(16)
                }
            }
        } else {
            val offset = item.offset.coerceIn(0, body.length)
            field = TextFieldValue(body, TextRange(offset))
            slashStart = null
            focus.requestFocus()
        }
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 只有三个开关，格式本身在 `/` 菜单和快捷键里，不再占一行按钮。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            EditorChip(
                label = if (preview) "编辑" else "预览",
                active = preview,
                onClick = onTogglePreview,
            )
            Spacer(Modifier.width(4.dp))
            EditorChip(
                label = if (sidebarVisible) "目录 ‹" else "目录 ›",
                active = sidebarVisible,
                onClick = { MarkdownSidebarState.visible = !MarkdownSidebarState.visible },
            )
            Spacer(Modifier.width(4.dp))
            EditorChip(
                label = "快捷键",
                active = shortcutsOpen,
                onClick = onToggleShortcuts,
            )
            Spacer(Modifier.weight(1f))
            Text(
                body.length.toString() + " 字符",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(ControlShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (preview) {
                    MarkdownPreview(body, previewScroll, blockOffsets)
                } else {
                    BasicTextField(
                        value = field,
                        onValueChange = { updated ->
                            field = updated
                            if (updated.text != body) onBodyChange(updated.text)
                            refreshSlash(updated)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focus)
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) {
                                    false
                                } else if (event.key == Key.Escape) {
                                    if (slashStart != null) {
                                        slashStart = null
                                        slashQuery = ""
                                        true
                                    } else {
                                        false
                                    }
                                } else if (slashStart != null && event.key == Key.DirectionDown) {
                                    if (slashMatches.isNotEmpty()) {
                                        slashIndex = (slashIndex + 1) % slashMatches.size
                                    }
                                    true
                                } else if (slashStart != null && event.key == Key.DirectionUp) {
                                    if (slashMatches.isNotEmpty()) {
                                        slashIndex = (slashIndex - 1 + slashMatches.size) % slashMatches.size
                                    }
                                    true
                                } else if (slashStart != null &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Tab)
                                ) {
                                    val chosen = slashMatches.getOrNull(slashIndex)
                                    if (chosen == null) {
                                        // 没有可选项：把菜单收起来，回车照常换行。
                                        slashStart = null
                                        slashQuery = ""
                                        false
                                    } else {
                                        applySlash(chosen)
                                        true
                                    }
                                } else if (isCommand(event)) {
                                    val binding = markdownBindingFor(event.key, event.isShiftPressed)
                                    val command = binding?.let { markdownCommand(it.commandId) }
                                    if (command == null) {
                                        false
                                    } else {
                                        apply(command.apply(field.text, field.selection))
                                        true
                                    }
                                } else if (event.key == Key.Enter && !event.isShiftPressed) {
                                    val continued = continueMarkdownList(field)
                                    if (continued == null) {
                                        false
                                    } else {
                                        field = continued
                                        onBodyChange(continued.text)
                                        true
                                    }
                                } else {
                                    false
                                }
                            }
                            .padding(20.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.merge(
                            TextStyle(color = MaterialTheme.colorScheme.onSurface, lineHeight = 26.sp),
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxSize()) {
                                if (field.text.isEmpty()) {
                                    Text(
                                        "开始写吧。敲 / 插入标题、列表、表格；不知道按什么就按 Ctrl/Cmd+/ 看快捷键。",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }

                if (!preview && slashStart != null) {
                    SlashCommandMenu(
                        matches = slashMatches,
                        query = slashQuery,
                        selectedIndex = slashIndex,
                        onPick = { applySlash(it) },
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                    )
                }

                if (shortcutsOpen) {
                    ShortcutSheet(
                        onClose = onToggleShortcuts,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp),
                    )
                }
            }

            if (sidebarVisible) {
                OutlineSplitter(onDrag = { delta -> MarkdownSidebarState.resize(-delta) })
                MarkdownOutlinePanel(
                    roots = outline,
                    collapsed = collapsedOutline,
                    modifier = Modifier.width(MarkdownSidebarState.width),
                    onToggle = { item ->
                        collapsedOutline = if (item.offset in collapsedOutline) {
                            collapsedOutline - item.offset
                        } else {
                            collapsedOutline + item.offset
                        }
                    },
                    onCollapseAll = { collapsedOutline = collectOutlineParents(outline) },
                    onExpandAll = { collapsedOutline = emptySet() },
                    onJump = { jumpTo(it) },
                    onHide = { MarkdownSidebarState.visible = false },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (preview) "预览 · 点目录跳转" else "输入 / 唤起命令 · Ctrl/Cmd+/ 看快捷键",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 有子标题的节点（「全部收起」只收这些，叶子节点收了也没意义）。 */
private fun collectOutlineParents(roots: List<OutlineItem>): Set<Int> {
    val keys = mutableSetOf<Int>()
    fun walk(nodes: List<OutlineItem>) {
        for (node in nodes) {
            if (node.children.isNotEmpty()) keys += node.offset
            walk(node.children)
        }
    }
    walk(roots)
    return keys
}

@Composable
private fun MarkdownPreview(
    body: String,
    scroll: androidx.compose.foundation.ScrollState,
    blockOffsets: MutableMap<Int, Int>,
) {
    if (body.isBlank()) {
        Box(Modifier.fillMaxSize().padding(20.dp)) {
            Text(
                "还没有内容。切回「编辑」写点什么。",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        return
    }
    var boxTop by remember { mutableStateOf(0f) }
    SelectionContainer {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { boxTop = it.positionInRoot().y }
                .verticalScroll(scroll),
        ) {
            Column(Modifier.padding(20.dp)) {
                MarkdownText(
                    body,
                    bodyStyle = MaterialTheme.typography.bodyLarge,
                    onBlockLayout = { index, y ->
                        // 记录时把当前滚动量加回去，得到「内容坐标」，滚动多少都不会失效。
                        blockOffsets[index] = (scroll.value + (y - boxTop)).toInt()
                    },
                )
            }
        }
    }
}

/** 顶部的小开关（编辑/预览、目录、快捷键）：比 [ChromeButton] 矮一档，一行放得下三个。 */
@Composable
private fun EditorChip(label: String, active: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(ControlShape)
            .background(
                when {
                    active -> accent.copy(alpha = 0.14f)
                    hovered -> accent.copy(alpha = 0.08f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = AppFonts.mono,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 斜杠命令菜单：贴着编辑区左下角浮起来，不跟着光标跑（BasicTextField 拿不到光标坐标）。 */
@Composable
private fun SlashCommandMenu(
    matches: List<MarkdownCommand>,
    query: String,
    selectedIndex: Int,
    onPick: (MarkdownCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(320.dp)
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (query.isEmpty()) "命令" else "/" + query,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "↑↓ 选 · 回车插入 · Esc 关",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (matches.isEmpty()) {
            Text(
                "没有匹配的命令",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(
                Modifier.heightIn(max = 232.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(matches, key = { it.id }) { command ->
                    SlashCommandRow(
                        command = command,
                        selected = matches.indexOf(command) == selectedIndex,
                        onClick = { onPick(command) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SlashCommandRow(command: MarkdownCommand, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .clip(ControlShape)
            .background(
                when {
                    selected -> accent.copy(alpha = 0.14f)
                    hovered -> accent.copy(alpha = 0.07f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                command.label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                command.group.label + " · " + command.hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val shortcut = markdownKeyBindings.firstOrNull { it.commandId == command.id }?.label.orEmpty()
        if (shortcut.isNotEmpty()) {
            Text(
                shortcut,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                maxLines = 1,
            )
        }
    }
}

/** 快捷键清单：`Ctrl/Cmd+/` 或右上角「快捷键」打开。 */
@Composable
private fun ShortcutSheet(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val editor = markdownShortcutHints.filter { it.owned }
    val page = markdownShortcutHints.filterNot { it.owned }
    Column(
        modifier = modifier
            .width(420.dp)
            .fillMaxHeight()
            .clip(ControlShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("快捷键", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(
                "Esc 关闭",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(ControlShape).clickable(onClick = onClose).padding(4.dp),
            )
        }
        // 窗口矮的时候（笔记本 125%/175% 缩放很常见）清单会撑出编辑区：让它自己滚。
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ShortcutSection("编辑器", editor)
            ShortcutSection("页面", page)
        }
    }
}

@Composable
private fun ShortcutSection(title: String, hints: List<ShortcutHint>) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    for (hint in hints) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                hint.keys,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(150.dp),
            )
            Text(
                hint.action,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
