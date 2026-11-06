package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import dev.trellis.ui.LocalExtraColors
import kotlinx.coroutines.launch

/**
 * 知识库正文编辑器（marktext/Muya 的思路：**一个文档一个编辑面**）。
 *
 * 整篇 Markdown 塞进一个 `BasicTextField`，光标移动、跨行选区、全选、复制粘贴、上下键
 * 全部交给平台文本框——这正是按块切分、每块一个输入框做不到的事（跨行选中、空行上下
 * 移动、段落首尾回车都会在那里出问题）。显示层由 [projectDocument] 投影：
 * 光标不在的地方把 `**`/`#`/`> ` 这类标记藏起来、只留样式；光标进去时标记还原。
 *
 * 代价：代码块、表格、`:::ai` 块、`$$` 公式块只显示等宽样式化源码（它们不是纯文本
 * 样式，没法塞进同一个文本框渲染成小部件）；笔记页因此不设「预览」态，只有这一个编辑面。
 *
 * 参数与页面（[KnowledgeScreen]）对齐：`noteId` 变化时重置编辑状态，`body` 是外部正文
 * （自动保存/抽取的结果），`onBodyChange` 在每次编辑后回写。
 */
@Composable
internal fun MarkdownDocumentEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    conceptNames: List<String> = emptyList(),
    onDeleteAiLine: ((String) -> Unit)? = null,
    reveal: TextRange? = null,
    revealToken: Int = 0,
    onEditingFinished: (() -> Unit)? = null,
) {
    val paragraphStyle = MaterialTheme.typography.bodyLarge
    val scheme = MaterialTheme.colorScheme
    val conceptGreen = LocalExtraColors.current.accentText
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var field by remember(noteId) { mutableStateOf(TextFieldValue(body)) }
    var editLayout by remember(noteId) { mutableStateOf<TextLayoutResult?>(null) }
    var fieldWidth by remember(noteId) { mutableStateOf(0) }
    var hadFocus by remember(noteId) { mutableStateOf(false) }

    // 斜杠命令菜单：slashStart 是那个 `/` 的下标，slashQuery 是 `/` 之后打的过滤词。
    var slashStart by remember(noteId) { mutableStateOf<Int?>(null) }
    var slashQuery by remember(noteId) { mutableStateOf("") }
    var slashIndex by remember(noteId) { mutableStateOf(0) }
    val slashMatches = remember(slashQuery) { filterMarkdownCommands(slashQuery) }

    // `[[` 补全：光标在一条还没闭合的概念标注里时，弹概念名（含别名）候选。
    var completion by remember(noteId) { mutableStateOf<ConceptCompletion?>(null) }
    var completionIndex by remember(noteId) { mutableStateOf(0) }

    val projection = remember(field.text, field.selection, conceptNames) {
        projectDocument(field.text, field.selection, conceptNames)
    }

    fun closeSlash() {
        slashStart = null
        slashQuery = ""
        slashIndex = 0
    }

    fun closeCompletion() {
        completion = null
        completionIndex = 0
    }

    /** 按当前文本/光标刷新补全候选；斜杠菜单和补全不同时出现。 */
    fun refreshCompletion(updated: TextFieldValue) {
        val cursor = updated.selection.min
        val next = if (updated.selection.collapsed && conceptNames.isNotEmpty() &&
            !insideCodeFence(updated.text, cursor)
        ) {
            conceptCompletionAt(updated.text, cursor, conceptNames)
        } else {
            null
        }
        completion = next
        completionIndex = completionIndex.coerceIn(0, maxOf(0, (next?.candidates?.size ?: 1) - 1))
        if (next != null) closeSlash()
    }

    // 外部正文变了（切笔记、AI 补全、页面重新载入）：跟上，光标尽量留在原处。
    LaunchedEffect(body) {
        if (body == field.text) return@LaunchedEffect
        val selection = field.selection
        field = TextFieldValue(
            body,
            TextRange(
                selection.start.coerceIn(0, body.length),
                selection.end.coerceIn(0, body.length),
            ),
        )
    }

    // 概念「定位」：把命中范围选中并交回焦点。
    LaunchedEffect(revealToken) {
        if (revealToken == 0) return@LaunchedEffect
        val requested = reveal ?: return@LaunchedEffect
        val from = requested.min.coerceIn(0, field.text.length)
        val to = requested.max.coerceIn(from, field.text.length)
        field = field.copy(selection = TextRange(from, to))
        closeSlash()
        focus.requestFocusWhenReady()
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

    fun apply(updated: TextFieldValue) {
        val textChanged = updated.text != field.text
        field = updated
        refreshSlash(updated)
        refreshCompletion(updated)
        if (textChanged) onBodyChange(updated.text)
    }

    /** 用 [replacement] 替换当前选区，光标落在插入内容之后。 */
    fun replaceSelection(replacement: String) {
        val text = field.text
        val selection = field.selection
        val newText = text.substring(0, selection.min) + replacement + text.substring(selection.max)
        apply(TextFieldValue(newText, TextRange(selection.min + replacement.length)))
    }

    /**
     * 段落回车：在光标处拆出「一个空行」，空行在 Markdown 里就是段落边界。
     *
     * 三种落点分开处理，否则总有一种「回车没反应」：
     *  - 段的中间 → 交界处补到两个换行，光标落在后半段开头（新段落里）；
     *  - 行的开头（光标前正好是换行）→ 在当前行上方留出一个**完整空段落**：
     *    交界处补到 `上段\n\n新段\n\n本行`，光标落在两个空行之间，打出来的字自成一整段；
     *  - 整篇开头 → 光标留在最前面，打出来的字就是新段落。
     * 光标前只有一个换行（软换行）时多补一个换行，保证新打的字不会和上一段粘成一段。
     */
    fun breakParagraph() {
        apply(breakParagraphAt(field))
    }

    /** 在光标处插入软换行（Shift+Enter：同一段落内换行）。 */
    fun insertSoftBreak() {
        replaceSelection("\n")
    }

    /** 选中一个补全候选；`=>` 只插分隔符，概念名则替换输入并补 `]]`。 */
    fun applyCompletion(choice: String) {
        val current = completion ?: return
        val updated = applyConceptCompletion(field, current, choice)
        apply(updated)
        scope.launch { focus.requestFocusWhenReady() }
    }

    fun applySlash(command: MarkdownCommand) {
        val start = slashStart ?: return
        val result = applySlashCommand(field.text, start, field.selection.max, command)
        closeSlash()
        apply(TextFieldValue(result.text, result.selection))
        scope.launch { focus.requestFocusWhenReady() }
    }

    /** 列表项/引用行回车：按光标位置拆成两个同类行（marktext 的行为）。 */
    fun splitListLine(): Boolean {
        val updated = splitListLineAt(field, projection.lines) ?: return false
        apply(updated)
        return true
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key == Key.Escape) {
            if (completion != null) {
                closeCompletion()
            } else if (slashStart != null) {
                closeSlash()
            } else {
                focusManager.clearFocus(force = true)
            }
            return true
        }
        val active = completion
        if (active != null) {
            when (event.key) {
                Key.DirectionDown -> {
                    completionIndex = (completionIndex + 1) % active.candidates.size
                    return true
                }

                Key.DirectionUp -> {
                    completionIndex = (completionIndex - 1 + active.candidates.size) % active.candidates.size
                    return true
                }

                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    applyCompletion(active.candidates[completionIndex])
                    return true
                }

                else -> Unit
            }
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
        val commandPressed = event.isCtrlPressed || event.isMetaPressed
        if (commandPressed && event.key == Key.Enter) {
            focusManager.clearFocus(force = true)
            return true
        }
        if (commandPressed) {
            val binding = markdownBindingFor(event.key, event.isShiftPressed) ?: return false
            val command = markdownCommand(binding.commandId) ?: return false
            val result = command.apply(field.text, field.selection)
            apply(TextFieldValue(result.text, result.selection))
            return true
        }
        if (event.key == Key.Enter) {
            // Shift+Enter：同一段落内换行（软换行）。
            if (event.isShiftPressed) {
                insertSoftBreak()
                return true
            }
            // 代码块/表格/AI 块/公式块里回车只换行，不做段落拆分。
            if (projection.caretKind in rawTextLineKinds) {
                insertSoftBreak()
                return true
            }
            val continued = continueMarkdownList(field)
            if (continued != null) {
                apply(continued)
                return true
            }
            if (splitListLine()) return true
            breakParagraph()
            return true
        }
        return false
    }

    val transformation = remember(projection, paragraphStyle, scheme, conceptGreen) {
        VisualTransformation {
            TransformedText(
                buildDocumentEditText(projection, paragraphStyle, scheme, conceptGreen),
                projection.mapping,
            )
        }
    }

    // 左侧 gutter：只标光标所在段落（¶ / H1–H6 / • / ☰ / ▍ / { } / ▦ / ∑ / AI）。
    val gutterWidth = 34.dp
    val gutterWidthPx = with(density) { gutterWidth.toPx() }
    val markerMeasurer = rememberTextMeasurer()
    val markerStyle = remember(scheme) {
        TextStyle(
            color = scheme.onSurfaceVariant.copy(alpha = 0.72f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
    val darkTheme = scheme.background.luminance() < 0.5f
    val codeCardColor = remember(scheme) {
        if (darkTheme) {
            scheme.surfaceVariant.copy(alpha = 0.42f)
        } else {
            scheme.surfaceVariant.copy(alpha = 0.5f)
        }
    }
    val codeCardBorder = remember(scheme) { scheme.outlineVariant.copy(alpha = 0.55f) }
    val codeLabelMeasurer = rememberTextMeasurer()
    val codeLabelStyle = remember(scheme) {
        TextStyle(
            color = scheme.onSurfaceVariant.copy(alpha = 0.66f),
            fontSize = 10.sp,
            fontFamily = AppFonts.mono,
        )
    }

    // 斜杠菜单贴着光标下方浮起来（和整篇编辑器的命令表是同一份）。
    val menuOffset = remember(editLayout, field.selection, projection, fieldWidth, density) {
        val layout = editLayout
        if (layout == null) {
            IntOffset.Zero
        } else {
            val transformed = projection.mapping.originalToTransformed(field.selection.min)
            val caret = transformed.coerceIn(0, layout.layoutInput.text.length)
            val rect = layout.getCursorRect(caret)
            val menuWidth = with(density) { 300.dp.roundToPx() }
            val maxX = (fieldWidth - menuWidth).coerceAtLeast(0)
            IntOffset(
                x = (rect.left.roundToInt() + gutterWidthPx.roundToInt()).coerceIn(0, maxX),
                y = (rect.bottom.roundToInt() + with(density) { 4.dp.roundToPx() }).coerceAtLeast(0),
            )
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // gutter 画在文字左边（Canvas 先画 = 在最底层，文字不会盖住它）。
        Canvas(Modifier.matchParentSize()) {
            val layout = editLayout ?: return@Canvas
            val textLength = layout.layoutInput.text.length
            fun clampOffset(offset: Int): Int = offset.coerceIn(0, textLength)

            // 代码块：整块画一张圆角卡片（行间不留缝），光标不在块里时连围栏行也藏掉了，
            // 所以卡片范围就是可见文本里这一段。底色/描边跟随主题，右上角标语言。
            for (block in projection.codeBlocks) {
                if (block.end <= block.start) continue
                val fromLine = layout.getLineForOffset(clampOffset(block.start))
                val toLine = layout.getLineForOffset(clampOffset(block.end - 1))
                val top = layout.getLineTop(fromLine) - 8.dp.toPx()
                val bottom = layout.getLineBottom(toLine) + 8.dp.toPx()
                val left = gutterWidthPx - 14.dp.toPx()
                val right = size.width - 10.dp.toPx()
                val corner = CornerRadius(10.dp.toPx())
                val cardSize = Size((right - left).coerceAtLeast(0f), bottom - top)
                drawRoundRect(
                    color = codeCardColor,
                    topLeft = Offset(left, top),
                    size = cardSize,
                    cornerRadius = corner,
                )
                drawRoundRect(
                    color = codeCardBorder,
                    topLeft = Offset(left, top),
                    size = cardSize,
                    cornerRadius = corner,
                    style = Stroke(width = 1.dp.toPx()),
                )
                val label = block.language.label
                if (label.isNotEmpty()) {
                    val measured = codeLabelMeasurer.measure(AnnotatedString(label), codeLabelStyle)
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(
                            x = right - measured.size.width - 10.dp.toPx(),
                            y = top + 5.dp.toPx(),
                        ),
                    )
                }
            }

            // 引用块：左侧一条竖线，跨连续的 `>` 行（marktext 那种引用样式）。
            for (block in projection.quoteBlocks) {
                if (block.end <= block.start) continue
                val first = layout.getLineForOffset(clampOffset(block.start))
                val last = layout.getLineForOffset(clampOffset(block.end - 1))
                val x = gutterWidthPx - 8.dp.toPx()
                drawLine(
                    color = scheme.primary.copy(alpha = 0.55f),
                    start = Offset(x, layout.getLineTop(first) + 2.dp.toPx()),
                    end = Offset(x, layout.getLineBottom(last) - 2.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }

            val paragraph = projection.caretParagraph ?: return@Canvas
            val rawLines = projection.lines
            if (rawLines.isEmpty()) return@Canvas
            val firstRaw = rawLines[paragraph.firstLine.coerceIn(0, rawLines.lastIndex)]
            val lastRaw = rawLines[paragraph.lastLine.coerceIn(0, rawLines.lastIndex)]
            val firstVisible = projection.mapping.originalToTransformed(firstRaw.start)
                .coerceIn(0, layout.layoutInput.text.length)
            val lastVisible = projection.mapping.originalToTransformed(lastRaw.end)
                .coerceIn(0, layout.layoutInput.text.length)
            val firstLine = layout.getLineForOffset(firstVisible)
            val lastLine = layout.getLineForOffset(lastVisible)
            val top = layout.getLineTop(firstLine)
            val bottom = layout.getLineBottom(lastLine)
            drawLine(
                color = scheme.onSurfaceVariant.copy(alpha = 0.26f),
                start = Offset(gutterWidthPx - 3.dp.toPx(), top + 2.dp.toPx()),
                end = Offset(gutterWidthPx - 3.dp.toPx(), bottom - 2.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            val measured = markerMeasurer.measure(AnnotatedString(paragraph.marker), markerStyle)
            val lineHeight = layout.getLineBottom(firstLine) - layout.getLineTop(firstLine)
            drawText(
                textLayoutResult = measured,
                topLeft = Offset(
                    x = 0f,
                    y = top + ((lineHeight - measured.size.height) / 2f).coerceAtLeast(0f),
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = gutterWidth)
                .onSizeChanged { fieldWidth = it.width }
                .drawBehind {
                    val layout = editLayout ?: return@drawBehind
                    drawInlineCodeChips(layout, projection.chips, paragraphStyle)
                },
        ) {
            BasicTextField(
                value = field,
                onValueChange = { updated -> apply(updated) },
                modifier = Modifier
                    .fillMaxWidth()
                    // 空文档 / 正文末尾以下的空白也要能点进去写（不然点页面空白处没反应）。
                    .heightIn(min = 280.dp)
                    .pointerInput(field.text) {
                        awaitEachGesture {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val layout = editLayout
                            val contentBottom = if (layout != null && layout.lineCount > 0) {
                                layout.getLineBottom(layout.lineCount - 1)
                            } else {
                                0f
                            }
                            if (down.position.y > contentBottom + 8.dp.toPx()) {
                                down.consume()
                                apply(newParagraphAtEnd(field))
                                focus.requestFocus()
                            }
                        }
                    }
                    .focusRequester(focus)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            closeSlash()
                            closeCompletion()
                            // 焦点离开正文：算「这一轮写完了」，页面据此收尾（如 AI 整理）。
                            onEditingFinished?.invoke()
                        }
                    }
                    .onPreviewKeyEvent { event -> handleKey(event) },
                textStyle = paragraphStyle.merge(
                    TextStyle(
                        color = scheme.onSurface,
                        lineHeight = 26.sp,
                    ),
                ),
                visualTransformation = transformation,
                cursorBrush = SolidColor(scheme.primary),
                onTextLayout = { editLayout = it },
            )
            if (field.text.isEmpty()) {
                Text(
                    "开始写吧。敲 / 插入标题、列表；Ctrl/Cmd+/ 看菜单。",
                    style = paragraphStyle,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
        }
        if (slashStart != null) {
            SlashMenu(
                matches = slashMatches,
                query = slashQuery,
                selectedIndex = slashIndex,
                onPick = { applySlash(it) },
                modifier = Modifier.offset { menuOffset },
            )
        }
        completion?.let { active ->
            ConceptCompletionMenu(
                completion = active,
                selectedIndex = completionIndex,
                onPick = { applyCompletion(it) },
                modifier = Modifier.offset { menuOffset },
            )
        }
    }
    // 正文末尾以下的空白：点一下就另起一段（不用再把光标拖到底、按回车）。
    Box(
        Modifier
            .fillMaxWidth()
            .height(120.dp)
            .pointerInput(field.text) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    apply(newParagraphAtEnd(field))
                    focus.requestFocus()
                }
            },
    )
    }
}

/** 这些行的内容本身就是语法，回车只换行。 */
private val rawTextLineKinds = setOf(
    DocumentLineKind.CodeFence,
    DocumentLineKind.CodeContent,
    DocumentLineKind.Table,
    DocumentLineKind.AiMarker,
    DocumentLineKind.AiContent,
    DocumentLineKind.MathFence,
    DocumentLineKind.MathContent,
)

/**
 * 把投影结果变成 [AnnotatedString]：整行样式（标题/引用/代码/表格/AI/公式）+ 行内样式。
 *
 * 顺序有讲究：先铺整行的底色/字号，再铺「还原的标记」的暗色，最后铺行内样式——
 * 后加的在同属性上覆盖前面的，所以行内的加粗/等宽不会被整行样式吃掉。
 */
private fun buildDocumentEditText(
    projection: DocumentProjection,
    baseStyle: TextStyle,
    scheme: androidx.compose.material3.ColorScheme,
    conceptGreen: Color,
): AnnotatedString = buildAnnotatedString {
    if (projection.text.isEmpty()) return@buildAnnotatedString

    append(projection.text)
    fun add(range: IntRange, style: SpanStyle) {
        addStyle(style, range.first, range.last + 1)
    }

    for ((range, level) in projection.headings) {
        val heading = markdownHeadingStyle(level, baseStyle)
        add(
            range,
            SpanStyle(
                fontSize = heading.fontSize,
                fontWeight = heading.fontWeight ?: FontWeight.Bold,
            ),
        )
    }
    for (range in projection.quotes) {
        // 引用块用「灰字 + 左侧 gutter 的 ▍ 标记」表达层次；中文斜体是伪斜体，反而更糊。
        add(range, SpanStyle(color = scheme.onSurfaceVariant))
    }
    for (range in projection.codeLines) {
        // 底色由 gutter Canvas 画成一整块圆角卡片，这里只管等宽字体。
        add(range, SpanStyle(fontFamily = FontFamily.Monospace, color = scheme.onSurface))
    }
    for (range in projection.tables) {
        add(range, SpanStyle(fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant))
    }
    for (range in projection.aiLines) {
        add(range, SpanStyle(color = conceptGreen.copy(alpha = 0.62f)))
    }
    for (range in projection.mathLines) {
        add(range, SpanStyle(fontFamily = FontFamily.Monospace, fontStyle = FontStyle.Italic))
    }
    val syntaxColor = scheme.onSurfaceVariant.copy(alpha = 0.45f)
    for (range in projection.syntax) {
        add(range, SpanStyle(color = syntaxColor))
    }
    for (mark in projection.marks) {
        if (mark.start >= mark.end) continue
        val style = when (mark.kind) {
            InlineMarkKind.Bold -> markdownBoldStyle(scheme.onSurface)
            InlineMarkKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            InlineMarkKind.Code -> markdownCodeStyle()
            InlineMarkKind.Strike -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            InlineMarkKind.Link -> SpanStyle(
                color = scheme.primary,
                textDecoration = TextDecoration.Underline,
            )

            InlineMarkKind.Math -> SpanStyle(
                fontFamily = FontFamily.Monospace,
                fontStyle = FontStyle.Italic,
            )

            InlineMarkKind.Concept -> SpanStyle(
                color = conceptGreen.copy(alpha = 0.62f),
                textDecoration = TextDecoration.Underline,
            )
            // 普通出现的概念名/别名：只染色，和预览态一致（只有 [[…]] 标注才带下划线）。
            InlineMarkKind.ConceptPlain -> SpanStyle(color = conceptGreen.copy(alpha = 0.62f))
            // `{{…}}` 里的内容：绿字；两侧折叠时补的绿色大引号。
            InlineMarkKind.AiInline -> SpanStyle(color = conceptGreen.copy(alpha = 0.62f))
            InlineMarkKind.AiQuote -> SpanStyle(
                color = conceptGreen,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
            )
        }
        add(mark.start..(mark.end - 1), style)
    }
    // 代码块语法高亮：放在最后，覆盖上面统一的等宽字色（GitHub 固定配色）。
    val codeColors = codeSyntaxColors(scheme.background.luminance() < 0.5f)
    for (span in projection.codeHighlights) {
        if (span.start >= span.end) continue
        add(span.start..(span.end - 1), SpanStyle(color = codeColors.of(span.kind)))
    }
}

/** 斜杠命令菜单：贴着光标下方浮起来（和整篇编辑器的命令表是同一份）。 */
@Composable
private fun SlashMenu(
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
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        if (matches.isEmpty()) {
            Text(
                "没有匹配的命令",
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            LazyColumn(
                Modifier.heightIn(max = 232.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(matches, key = { it.id }) { command ->
                    SlashRow(
                        command = command,
                        selected = matches.indexOf(command) == selectedIndex,
                        onClick = { onPick(command) },
                    )
                }
            }
        }
    }
}

/**
 * `[[` 补全菜单：上游给候选，这里只负责画和把点击转成 [onPick]。
 *
 * `=>` 那一项会把左边已经输入的原文映射到右边概念名（`[[原文 => 概念]]`）。
 */
@Composable
private fun ConceptCompletionMenu(
    completion: ConceptCompletion,
    selectedIndex: Int,
    onPick: (String) -> Unit,
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
                if (completion.afterSeparator) "补全概念名" else "补全概念",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = AppFonts.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "↑↓ 选 · 回车补全 · Esc 关",
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Column(Modifier.heightIn(max = 232.dp).verticalScroll(rememberScrollState())) {
            completion.candidates.forEachIndexed { index, candidate ->
                val isSeparator = candidate == CONCEPT_COMPLETION_SEPARATOR
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
                                index == selectedIndex -> accent.copy(alpha = 0.14f)
                                hovered -> accent.copy(alpha = 0.07f)
                                else -> Color.Transparent
                            },
                        )
                        .clickable(interactionSource = interaction, indication = null) { onPick(candidate) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isSeparator) "=> 原文和概念名不一样" else candidate,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (isSeparator) {
                                "先保留左边原文，再选一个概念名映射过去"
                            } else {
                                "概念名 / 别名"
                            },
                            style = LocalTrellisTokens.current.type.meta,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlashRow(command: MarkdownCommand, selected: Boolean, onClick: () -> Unit) {
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
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                command.group.label + " · " + command.hint,
                style = LocalTrellisTokens.current.type.meta,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}
