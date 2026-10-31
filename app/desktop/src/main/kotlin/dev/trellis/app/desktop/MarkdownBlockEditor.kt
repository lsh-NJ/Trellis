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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 知识库正文的「块级编辑器」——行内编辑，行外预览。
 *
 * 一篇笔记按行切成 [BodyBlock]：标题、列表、引用、代码围栏、表格、`$$` 公式、`:::ai` 块
 * 各自成块，其余连续非空行是一段。平时每块都用 [MarkdownText] 渲染（概念高亮、AI 绿引号
 * 都在里面），点哪块哪块变成 [BasicTextField]；改完的内容用 [spliceBlockRange] 换回正文，
 * 通过 `onBodyChange` 交给页面，自动保存、抽取都不需要知道块的存在。
 *
 * 这个文件只做两件事：**切块（纯函数）** 和 **块的编辑状态机**。它不读文件、不存盘、
 * 不内嵌滚动，也不使用 LazyList——外层页面已经有滚动容器。
 */

/** 正文块的类型；只影响对话框/语义，渲染统一交给 MarkdownText。 */
internal enum class BlockKind {
    Paragraph,
    Heading,
    ListItem,
    Quote,
    Code,
    Table,
    AiBlock,
    Math,
}

/** 正文里的一块。[start]/[end] 是正文里的下标（end 不含），[raw] == body.substring(start, end)。 */
internal data class BodyBlock(
    val start: Int,
    val end: Int,
    val raw: String,
    val kind: BlockKind,
)

private data class BodyLine(val start: Int, val end: Int)

private val headingLine = Regex("^#{1,6}\\s+")
private val bulletLine = Regex("^[-*+]\\s+")
private val numberedLine = Regex("^\\d+[.)]\\s+")
private val inlineMath = Regex("(?<![\\\\$])\\$(?!\\$)[^$\\n]+(?<!\\\\)\\$(?!\\$)")
private val latexMath = Regex("(?<!\\\\)\\\\\\(.+?\\\\\\\\?\\)")

private fun bodyLines(body: String): List<BodyLine> {
    val lines = mutableListOf<BodyLine>()
    var index = 0
    while (true) {
        val next = body.indexOf('\n', index)
        val end = if (next < 0) body.length else next
        lines += BodyLine(index, end)
        if (next < 0) break
        index = next + 1
    }
    return lines
}

private fun BodyLine.isBlank(body: String): Boolean = body.substring(start, end).isBlank()

private fun BodyLine.trimmed(body: String): String = body.substring(start, end).trimStart()

private fun isFence(text: String): Boolean = text.startsWith("```") || text.startsWith("~~~")

private fun fenceMarker(text: String): String = if (text.startsWith("~~~")) "~~~" else "```"

private fun isAiBlockStart(text: String): Boolean = text.startsWith(":::ai")

private fun isAiBlockEnd(text: String): Boolean = text == ":::" || (text.startsWith(":::") && text.substring(3).isBlank())

private fun isTableRow(text: String): Boolean = text.startsWith("|")

private fun isMathStart(text: String): Boolean = text.startsWith("$$")

/** 这一行是否会开启一个「非段落」块；段落遇到这些行要断开。 */
private fun startsNonParagraph(text: String): Boolean =
    isFence(text) ||
        isAiBlockStart(text) ||
        isMathStart(text) ||
        isTableRow(text) ||
        headingLine.containsMatchIn(text) ||
        bulletLine.containsMatchIn(text) ||
        numberedLine.containsMatchIn(text) ||
        text.startsWith("> ")

/**
 * 把正文切成块。
 *
 * - 空行不是块；块与块之间的换行不进 `raw`（渲染层用 6dp 间距体现间隔）；
 * - 围栏代码块从 ``` / ~~~ 到闭合行（含），未闭合到文末；代码里的 `#` 只是内容；
 * - `$$` 行到下一个以 `$$` 结尾的行；单行 `$$x$$` 自己一块；
 * - `:::ai` 到 `:::`（含），未闭合到文末；
 * - 连续 `|` 行合并成一张表；
 * - `#{1,6}+空格` 是标题，列表前缀/`> ` 各占一行；
 * - 其它连续非空行是一段（遇到空行或其它块的起始行就断开）。
 *
 * 每个块的 [BodyBlock.raw] 都等于 `body.substring(start, end)`，结束位置停在块尾
 * （不含块尾换行）；把相邻块用 `\n` 拼回去可以还原没有空行的正文，空行属于「间隔」，
 * 由调用方用 spacing 表现。空正文返回一块 offset 0..0 的空 Paragraph。
 */
internal fun splitBodyBlocks(body: String): List<BodyBlock> {
    if (body.isEmpty()) return listOf(BodyBlock(0, 0, "", BlockKind.Paragraph))
    val lines = bodyLines(body)
    val blocks = mutableListOf<BodyBlock>()

    fun add(kind: BlockKind, first: Int, last: Int) {
        val start = lines[first].start
        val end = lines[last].end
        blocks += BodyBlock(start, end, body.substring(start, end), kind)
    }

    /** 从 first 到文末；文末的空行不算进块里（它们只是文件尾部的换行）。 */
    fun lastContentLineFrom(first: Int): Int {
        var last = lines.size - 1
        while (last > first && lines[last].isBlank(body)) last--
        return last
    }

    var index = 0
    while (index < lines.size) {
        if (lines[index].isBlank(body)) {
            index++
            continue
        }
        val text = lines[index].trimmed(body)
        when {
            isFence(text) -> {
                val marker = fenceMarker(text)
                var last = index + 1
                var closed = false
                while (last < lines.size) {
                    if (lines[last].trimmed(body).startsWith(marker)) {
                        closed = true
                        break
                    }
                    last++
                }
                val end = if (closed) last else lastContentLineFrom(index)
                add(BlockKind.Code, index, end)
                index = end + 1
            }

            isAiBlockStart(text) -> {
                var last = index + 1
                var closed = false
                while (last < lines.size) {
                    if (isAiBlockEnd(lines[last].trimmed(body))) {
                        closed = true
                        break
                    }
                    last++
                }
                val end = if (closed) last else lastContentLineFrom(index)
                add(BlockKind.AiBlock, index, end)
                index = end + 1
            }

            isMathStart(text) -> {
                val singleLine = text.length > 2 && text.trimEnd().endsWith("$$")
                var last = index
                if (!singleLine) {
                    var scan = index + 1
                    while (scan < lines.size) {
                        if (lines[scan].trimmed(body).trimEnd().endsWith("$$")) {
                            last = scan
                            break
                        }
                        scan++
                    }
                    if (last == index) last = lastContentLineFrom(index)
                }
                add(BlockKind.Math, index, last)
                index = last + 1
            }

            isTableRow(text) -> {
                var last = index
                while (last + 1 < lines.size &&
                    !lines[last + 1].isBlank(body) &&
                    isTableRow(lines[last + 1].trimmed(body))
                ) {
                    last++
                }
                add(BlockKind.Table, index, last)
                index = last + 1
            }

            headingLine.containsMatchIn(text) -> {
                add(BlockKind.Heading, index, index)
                index++
            }

            bulletLine.containsMatchIn(text) || numberedLine.containsMatchIn(text) -> {
                add(BlockKind.ListItem, index, index)
                index++
            }

            text.startsWith("> ") -> {
                add(BlockKind.Quote, index, index)
                index++
            }

            else -> {
                var last = index
                while (last + 1 < lines.size) {
                    val next = lines[last + 1]
                    if (next.isBlank(body)) break
                    if (startsNonParagraph(next.trimmed(body))) break
                    last++
                }
                add(BlockKind.Paragraph, index, last)
                index = last + 1
            }
        }
    }

    if (blocks.isEmpty()) return listOf(BodyBlock(0, 0, "", BlockKind.Paragraph))
    return blocks
}

/**
 * 用 [replacement] 替换正文里 [start]..[end) 这一段。
 *
 * 越界的请求一律不动正文（返回 [body]），避免编辑状态机在重切块的间隙把内容写坏。
 */
internal fun spliceBlockRange(body: String, start: Int, end: Int, replacement: String): String {
    if (start < 0 || end < start || end > body.length) return body
    return body.substring(0, start) + replacement + body.substring(end)
}

/**
 * 找 [offset] 落在哪个块里。
 *
 * 块尾换行（`offset == block.end`）算前一块——用户把光标放在这一行末尾时，
 * 期望编辑的是这一块，而不是下一块。空行/文末换行也算前一块；offset 在正文最前面时给第一块。
 */
internal fun blockAt(blocks: List<BodyBlock>, offset: Int): BodyBlock? {
    if (blocks.isEmpty()) return null
    var found: BodyBlock? = null
    for (block in blocks) {
        if (block.start <= offset) found = block else break
    }
    return found ?: blocks.first()
}

/** 这段文本里有没有行内公式（`$…$` 或 `\(…\)`）；块级 `$$` 不算。 */
internal fun containsInlineMath(text: String): Boolean =
    inlineMath.containsMatchIn(text) || latexMath.containsMatchIn(text)

/**
 * 正文块编辑器。
 *
 * 参数与 [MarkdownEditor] 对齐：`noteId` 变化时重置编辑状态，`body` 是外部正文
 * （自动保存/抽取的结果），`onBodyChange` 在每次编辑后回写。`preview` 为 true 时
 * 只渲染不允许编辑；`reveal` 是概念详情「定位」要选中的范围，`revealToken` 每请求一次加一。
 */
@Composable
internal fun MarkdownBlockEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    preview: Boolean = false,
    conceptNames: List<String> = emptyList(),
    onDeleteAiLine: ((String) -> Unit)? = null,
    reveal: TextRange? = null,
    revealToken: Int = 0,
) {
    // 本地正文：先写到 draft，再通知页面。父级 state 回到这里通常已经等于 draft，
    // 这样连续击键（父级还没重组）也不会拿旧正文做 splice、把上一个字覆盖掉。
    var draft by remember(noteId) { mutableStateOf(body) }
    val blocks = remember(draft) { splitBodyBlocks(draft) }

    var activeStart by remember(noteId) { mutableStateOf<Int?>(null) }
    var activeEnd by remember(noteId) { mutableStateOf(0) }
    var field by remember(noteId) { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // 斜杠命令菜单：slashStart 是那个 `/` 的下标，slashQuery 是 `/` 之后打的过滤词。
    var slashStart by remember(noteId) { mutableStateOf<Int?>(null) }
    var slashQuery by remember(noteId) { mutableStateOf("") }
    var slashIndex by remember(noteId) { mutableStateOf(0) }
    val slashMatches = remember(slashQuery) { filterMarkdownCommands(slashQuery) }

    // 外部正文变了（切笔记、AI 补全、页面重新载入）：跟上；正在编辑的块保持光标。
    LaunchedEffect(body) {
        if (body == draft) return@LaunchedEffect
        draft = body
        val start = activeStart ?: return@LaunchedEffect
        val current = splitBodyBlocks(body).firstOrNull { it.start == start }
        if (current == null) {
            activeStart = null
            slashStart = null
            slashQuery = ""
        } else {
            field = TextFieldValue(
                current.raw,
                TextRange(field.selection.start.coerceIn(0, current.raw.length), field.selection.end.coerceIn(0, current.raw.length)),
            )
            activeEnd = current.end
        }
    }

    LaunchedEffect(activeStart) {
        if (activeStart == null || preview) return@LaunchedEffect
        focus.requestFocusWhenReady()
    }

    // 概念「定位」：找到包含 reveal.start 的块，切编辑态，把命中范围选中，再交回焦点。
    LaunchedEffect(revealToken) {
        if (revealToken == 0) return@LaunchedEffect
        val requested = reveal ?: return@LaunchedEffect
        val start = requested.start.coerceIn(0, draft.length)
        val block = blocks.firstOrNull { start in it.start..it.end } ?: blocks.lastOrNull() ?: return@LaunchedEffect
        val from = (start - block.start).coerceIn(0, block.raw.length)
        val to = (requested.end - block.start).coerceIn(from, block.raw.length)
        activeStart = block.start
        activeEnd = block.end
        field = TextFieldValue(block.raw, TextRange(from, to))
        slashStart = null
        slashQuery = ""
        slashIndex = 0
        if (!preview) focus.requestFocusWhenReady()
    }

    fun closeSlash() {
        slashStart = null
        slashQuery = ""
        slashIndex = 0
    }

    fun startEditing(block: BodyBlock, selection: TextRange? = null) {
        if (preview) return
        activeStart = block.start
        activeEnd = block.end
        field = TextFieldValue(block.raw, selection ?: TextRange(block.raw.length))
        closeSlash()
    }

    fun exitEdit() {
        activeStart = null
        activeEnd = 0
        closeSlash()
    }

    fun refreshSlash(updated: TextFieldValue) {
        val cursor = updated.selection.max
        val start = slashStart
        if (start != null) {
            val query = slashQueryAt(updated.text, start, cursor)
            if (query == null) {
                closeSlash()
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

    /** 编辑一块：替换 draft 里 [activeStart]..[activeEnd)，再通知页面。 */
    fun edit(updated: TextFieldValue) {
        val start = activeStart ?: return
        field = updated
        val newDraft = spliceBlockRange(draft, start, activeEnd, updated.text)
        if (newDraft != draft) {
            draft = newDraft
            onBodyChange(newDraft)
        }
        activeEnd = start + updated.text.length
        refreshSlash(updated)
    }

    fun applySlash(command: MarkdownCommand) {
        val start = slashStart ?: return
        val result = applySlashCommand(field.text, start, field.selection.max, command)
        closeSlash()
        edit(TextFieldValue(result.text, result.selection))
        scope.launch { focus.requestFocusWhenReady() }
    }

    fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val commandPressed = event.isCtrlPressed || event.isMetaPressed
        if (event.key == Key.Escape) {
            if (slashStart != null) closeSlash() else exitEdit()
            return true
        }
        if (slashStart != null) {
            when (event.key) {
                Key.DirectionDown -> {
                    if (slashMatches.isNotEmpty()) slashIndex = (slashIndex + 1) % slashMatches.size
                    return true
                }
                Key.DirectionUp -> {
                    if (slashMatches.isNotEmpty()) slashIndex = (slashIndex - 1 + slashMatches.size) % slashMatches.size
                    return true
                }
                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    val chosen = slashMatches.getOrNull(slashIndex)
                    if (chosen == null) {
                        closeSlash()
                        return false
                    }
                    applySlash(chosen)
                    return true
                }
                else -> Unit
            }
        }
        if (commandPressed && event.key == Key.Enter) {
            exitEdit()
            return true
        }
        if (commandPressed) {
            val binding = markdownBindingFor(event.key, event.isShiftPressed) ?: return false
            val command = markdownCommand(binding.commandId) ?: return false
            val result = command.apply(field.text, field.selection)
            edit(TextFieldValue(result.text, result.selection))
            return true
        }
        if (event.key == Key.Enter && !event.isShiftPressed) {
            val continued = continueMarkdownList(field) ?: return false
            edit(continued)
            return true
        }
        return false
    }

    val paragraphStyle = MaterialTheme.typography.bodyLarge
    val editStyle = paragraphStyle.merge(
        TextStyle(color = MaterialTheme.colorScheme.onSurface, lineHeight = 26.sp),
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (preview) {
            blocks.forEach { block ->
                MarkdownText(
                    block.raw,
                    conceptNames = conceptNames,
                    onDeleteAiBlockLine = onDeleteAiLine,
                )
            }
            return@Column
        }

        if (draft.isEmpty() && activeStart == null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(ControlShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        startEditing(BodyBlock(0, 0, "", BlockKind.Paragraph), TextRange(0))
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "开始写吧。敲 / 插入标题、列表；Ctrl/Cmd+/ 看菜单。",
                    style = paragraphStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
            return@Column
        }

        blocks.forEach { block ->
            if (activeStart == block.start) {
                Box(Modifier.fillMaxWidth()) {
                    // onFocusChanged 在节点挂载时会先回调一次「未聚焦」，不能当成失焦退出，
                    // 否则点进编辑态的当场就被取消。只有真正聚焦过再丢焦点才退出。
                    var hadFocus by remember(block.start) { mutableStateOf(false) }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(ControlShape)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        BasicTextField(
                            value = field,
                            onValueChange = { updated -> edit(updated) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus)
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        hadFocus = true
                                    } else if (hadFocus && activeStart == block.start) {
                                        exitEdit()
                                    }
                                }
                                .onPreviewKeyEvent { event -> handleKey(event) },
                            textStyle = editStyle,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (slashStart != null) {
                        BlockSlashMenu(
                            matches = slashMatches,
                            query = slashQuery,
                            selectedIndex = slashIndex,
                            onPick = { applySlash(it) },
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                    }
                }
            } else {
                BlockPreview(
                    block = block,
                    bodyStyle = paragraphStyle,
                    conceptNames = conceptNames,
                    onDeleteAiLine = onDeleteAiLine,
                    onClick = { startEditing(block) },
                )
            }
        }
    }
}

/** 一块的渲染态：hover 时整块浮现淡主色底，点一下进入编辑。 */
@Composable
private fun BlockPreview(
    block: BodyBlock,
    bodyStyle: TextStyle,
    conceptNames: List<String>,
    onDeleteAiLine: ((String) -> Unit)?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(if (hovered) accent.copy(alpha = 0.05f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        MarkdownText(
            block.raw,
            modifier = Modifier.fillMaxWidth(),
            bodyStyle = bodyStyle,
            conceptNames = conceptNames,
            onDeleteAiBlockLine = onDeleteAiLine,
        )
    }
}

/** 斜杠命令菜单：贴着当前编辑块的左下角浮起来（和整篇编辑器的菜单同一份命令表）。 */
@Composable
private fun BlockSlashMenu(
    matches: List<MarkdownCommand>,
    query: String,
    selectedIndex: Int,
    onPick: (MarkdownCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(300.dp)
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
                    BlockSlashRow(
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
private fun BlockSlashRow(command: MarkdownCommand, selected: Boolean, onClick: () -> Unit) {
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
            )
        }
    }
}
