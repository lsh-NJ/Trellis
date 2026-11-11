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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.graphics.compositeOver
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.roundToInt
import dev.trellis.ui.LocalExtraColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    /** 编辑器里的「AI 补全」：传入当前正文与槽位行号，返回补全后的整篇正文；null 表示不提供。 */
    onRequestAiFill: (suspend (String, Int) -> String?)? = null,
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
    // 行内补全建议只在正文有焦点时出现（像代码编辑器的 inline suggestion）。
    var editorFocused by remember(noteId) { mutableStateOf(false) }

    // 斜杠命令菜单：slashStart 是那个 `/` 的下标，slashQuery 是 `/` 之后打的过滤词。
    var slashStart by remember(noteId) { mutableStateOf<Int?>(null) }
    var slashQuery by remember(noteId) { mutableStateOf("") }
    var slashIndex by remember(noteId) { mutableStateOf(0) }
    val slashMatches = remember(slashQuery) { filterMarkdownCommands(slashQuery) }

    // `[[` 补全：光标在一条还没闭合的概念标注里时，弹概念名（含别名）候选。
    var completion by remember(noteId) { mutableStateOf<ConceptCompletion?>(null) }
    var completionIndex by remember(noteId) { mutableStateOf(0) }

    // 「AI 补全」小浮窗：光标所在行以 `。。。` 结尾时，在光标下一行浮出补全弹窗
    // 「AI 补全 Tab」，点它或按 Tab 只补这一行（替换行尾那三个句号）。
    // 行号是「按 \n 切分的原始行号」，和 KnowledgeScreen 里 findAiFillSlotAt 用的是同一套。
    var aiFillDismissedLine by remember(noteId) { mutableStateOf<Int?>(null) }
    var aiFillBusy by remember(noteId) { mutableStateOf(false) }
    var aiFillError by remember(noteId) { mutableStateOf<String?>(null) }
    var aiFillJob by remember(noteId) { mutableStateOf<Job?>(null) }
    // 切走再切回同一篇笔记时，旧的补全协程不能把结果写回新一次的编辑状态。
    val editorInstance = remember(noteId) { Any() }
    val liveEditor = rememberUpdatedState(editorInstance)
    val liveRequestAiFill = rememberUpdatedState(onRequestAiFill)
    val aiFillPrompt = if (liveRequestAiFill.value == null) {
        null
    } else {
        aiFillPromptLine(field.text, field.selection.min)
    }
    val aiFillLine = aiFillPrompt?.takeIf { it != aiFillDismissedLine }

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

    // AI 补全条出现时，斜杠菜单和 `[[` 补全让位：同一块地方只留一个浮层。
    LaunchedEffect(aiFillLine) {
        if (aiFillLine != null) {
            closeSlash()
            closeCompletion()
        }
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
        // 用户还在等补全结果时又改了字：这次补全作废，别把旧结果盖到他刚打的字上。
        if (textChanged && aiFillBusy) {
            aiFillJob?.cancel()
            aiFillJob = null
            aiFillBusy = false
        }
        field = updated
        // 真改了字就重新允许建议：Esc 只是「先别烦我」，不是永久隐藏这一行；
        // 失败提示也一起清掉，别让「重试」两个字跟着新打的字跑。
        if (textChanged) {
            aiFillDismissedLine = null
            aiFillError = null
        }
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

    /**
     * 启动一次「补全这一行」：Tab、点补全小浮窗都走这里。
     *
     * 只把结果写回编辑器；落盘/整理仍在 [onRequestAiFill]（KnowledgeScreen.fillSingleSlot）里，
     * 和之前提示条按钮走的是同一条流程。
     */
    fun requestAiFill() {
        val line = aiFillLine ?: return
        if (aiFillBusy) return
        val draft = field.text
        val editor = editorInstance
        val request = onRequestAiFill ?: return
        aiFillError = null
        aiFillBusy = true
        aiFillJob?.cancel()
        aiFillJob = scope.launch {
            val result = try {
                request(draft, line)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: Exception) {
                null
            }
            // 等模型的时候用户切走了、或切回来重进了这篇：结果已经落盘，别再写缓冲。
            if (liveEditor.value !== editor) return@launch
            aiFillJob = null
            aiFillBusy = false
            if (result == null) {
                aiFillError = AI_FILL_ERROR_TEXT
                return@launch
            }
            // 等模型的时候用户改了字：以用户现在的文本为准，这次结果丢弃。
            if (field.text != draft) return@launch
            val caret = lineEndOffset(result, line)
            apply(TextFieldValue(result, TextRange(caret)))
            focus.requestFocusWhenReady()
        }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.key == Key.Escape) {
            if (aiFillLine != null) {
                // AI 补全建议优先级最高：Esc 先收它（光标和空位都留着，可以再点/再按 Tab）。
                aiFillDismissedLine = aiFillLine
                aiFillJob?.cancel()
                aiFillJob = null
                aiFillBusy = false
                aiFillError = null
            } else if (completion != null) {
                closeCompletion()
            } else if (slashStart != null) {
                closeSlash()
            } else {
                focusManager.clearFocus(force = true)
            }
            return true
        }
        // 代码编辑器的接受手势：Tab 直接接受补全建议（斜杠/概念补全没开着时才抢）。
        if (
            event.key == Key.Tab &&
            aiFillLine != null &&
            field.selection.collapsed &&
            completion == null &&
            slashStart == null
        ) {
            requestAiFill()
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

    // 光标下方的「AI 补全」小浮窗（代码编辑器的补全弹窗样式）：
    // 行尾有 `。。。` 空位、正文有焦点时，气泡贴在光标下一行浮起来，点它或按 Tab 只补这一行。
    // 位置用可见文本的 TextLayoutResult 现算，宽度按内容量出来（错误文案更长就跟着变宽）。
    val aiFillMeasurer = rememberTextMeasurer()
    val aiFillLabelStyle = remember(scheme) { aiFillLabelTextStyle(scheme.onSurface) }
    val aiFillKeyStyle = remember(scheme) { aiFillKeyTextStyle(scheme.primary.copy(alpha = 0.82f)) }
    val aiFillText = aiFillSuggestionText(busy = aiFillBusy, error = aiFillError)
    val aiFillLabelLayout = remember(aiFillText, aiFillLabelStyle, aiFillMeasurer) {
        aiFillMeasurer.measure(AnnotatedString(aiFillText), aiFillLabelStyle)
    }
    val aiFillKeyLayout = remember(aiFillKeyStyle, aiFillMeasurer) {
        aiFillMeasurer.measure(AnnotatedString("Tab"), aiFillKeyStyle)
    }
    // 选中一段文字时不弹：那时候用户是在选/拖，不是在等补全。
    val showAiFillPopup = editorFocused && aiFillLine != null && field.selection.collapsed
    val aiFillPopup = remember(
        showAiFillPopup,
        editLayout,
        projection,
        field.selection,
        field.text,
        fieldWidth,
        density,
        aiFillLabelLayout,
        aiFillKeyLayout,
        aiFillBusy,
        aiFillError,
    ) {
        val layout = editLayout
        if (!showAiFillPopup || layout == null || fieldWidth <= 0) {
            null
        } else {
            val caretRaw = field.selection.min.coerceIn(0, field.text.length)
            val caret = projection.mapping.originalToTransformed(caretRaw)
                .coerceIn(0, layout.layoutInput.text.length)
            val line = layout.getLineForOffset(caret)
            val caretX = layout.getHorizontalPosition(caret, usePrimaryDirection = true)
            val showTab = !aiFillBusy && aiFillError == null
            val padH = with(density) { 10.dp.toPx() }
            val padV = with(density) { 6.dp.toPx() }
            val accentW = with(density) { 2.dp.toPx() }
            val accentGap = with(density) { 8.dp.toPx() }
            val keyGap = if (showTab) with(density) { 12.dp.toPx() } else 0f
            val keyPadH = if (showTab) with(density) { 8.dp.toPx() } else 0f
            val contentWidth = accentW + accentGap + aiFillLabelLayout.size.width +
                (if (showTab) keyGap + aiFillKeyLayout.size.width + keyPadH else 0f)
            val popupWidth = ceil(contentWidth + padH * 2)
            // 外层 Box 的坐标系是「gutter + 正文」，所以光标 x 要补上 gutter 宽度。
            val minX = gutterWidthPx
            val maxX = (gutterWidthPx + fieldWidth - popupWidth - with(density) { 4.dp.toPx() })
                .coerceAtLeast(minX)
            val x = (caretX + gutterWidthPx).coerceIn(minX, maxX)
            val y = layout.getLineBottom(line) + with(density) { 6.dp.toPx() }
            AiFillPopupPlacement(
                offset = IntOffset(x.roundToInt(), y.roundToInt()),
                width = with(density) { popupWidth.toDp() },
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
                        editorFocused = state.isFocused
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
        // 「AI 补全」小浮窗：贴在光标下一行，和上面两个补全菜单同一层（画在正文之上）。
        aiFillPopup?.let { placement ->
            AiFillSuggestionPopup(
                text = aiFillText,
                showTab = !aiFillBusy && aiFillError == null,
                busy = aiFillBusy,
                error = aiFillError,
                width = placement.width,
                onAccept = { requestAiFill() },
                modifier = Modifier.offset { placement.offset },
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

/**
 * 光标这一行是不是「AI 补全」槽位：是就返回行号，否则 null。
 *
 * 规则：行尾（trimEnd 后）是 `。。。`，光标必须在三颗句号之后（不能停在标记中间），
 * 且这一行不在代码块 / `:::ai` 块里。行号是按 `\n` 切分的原始行号，直接交给
 * [KnowledgeScreen.fillSingleSlot] 用，和 [findAiFillSlotAt] 是同一套编号。
 */
internal fun aiFillPromptLine(text: String, cursor: Int): Int? {
    if (text.isEmpty()) return null
    val offset = cursor.coerceIn(0, text.length)
    val lineIndex = text.take(offset).count { it == '\n' }
    val lineStart = if (offset <= 0) 0 else text.lastIndexOf('\n', offset - 1) + 1
    val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
    val trimmed = text.substring(lineStart, lineEnd).trimEnd()
    if (!trimmed.endsWith(AI_SLOT_MARKER)) return null
    // 光标可以在标记之后（比如行尾还有空格），但不能停在标记里面。
    if (offset < lineStart + trimmed.length) return null
    return if (findAiFillSlotAt(text, lineIndex) != null) lineIndex else null
}

/** 第 [lineIndex] 行的行尾偏移（不含换行符）；补全后把光标放到这一行末尾。 */
private fun lineEndOffset(text: String, lineIndex: Int): Int {
    if (lineIndex <= 0) {
        return text.indexOf('\n').let { if (it < 0) text.length else it }
    }
    var start = 0
    repeat(lineIndex) {
        val next = text.indexOf('\n', start)
        if (next < 0) return text.length
        start = next + 1
    }
    return text.indexOf('\n', start).let { if (it < 0) text.length else it }
}

/** 行内建议失败时的文案：点它重试。 */
internal const val AI_FILL_ERROR_TEXT: String = "这次没补上 · 点这里重试"

/**
 * 行内补全建议上显示的字。
 *
 * 正常是「AI 补全」，等模型时改成「AI 补全中…」，失败时给可点的重试提示——
 * 三种状态都挂在光标后方同一个位置，不额外弹条。
 */
internal fun aiFillSuggestionText(busy: Boolean, error: String?): String = when {
    error != null -> error
    busy -> "AI 补全中…"
    else -> "AI 补全"
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

/** 「AI 补全」浮窗里那行字的样式（量宽度和真正画出来必须用同一个，不然 Tab 键帽会折行）。 */
private fun aiFillLabelTextStyle(color: Color): TextStyle = TextStyle(
    color = color,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
)

/** 「AI 补全」浮窗里 `Tab` 键帽的样式，同样用在测量和绘制两处。 */
private fun aiFillKeyTextStyle(color: Color): TextStyle = TextStyle(
    color = color,
    fontSize = 10.sp,
    fontFamily = AppFonts.mono,
    fontWeight = FontWeight.SemiBold,
)

/** 光标下方「AI 补全」小浮窗的位置：外层 Box 坐标 + 浮窗该多宽。 */
private data class AiFillPopupPlacement(val offset: IntOffset, val width: Dp)

/**
 * 「AI 补全」小浮窗：代码编辑器补全弹窗的样式，只有一行。
 *
 * 光标行尾有 `。。。` 空位时贴在光标下一行浮起来，整块可点（点一下补这一行）。
 * 正常态带 `Tab` 键帽提示；等模型时显示「AI 补全中…」且暂时不可点；
 * 失败时换成失败文案（点它就是重试），左侧强调条转成错误色。
 */
@Composable
private fun AiFillSuggestionPopup(
    text: String,
    showTab: Boolean,
    busy: Boolean,
    error: String?,
    width: Dp,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = when {
        error != null -> scheme.error
        busy -> scheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> scheme.primary
    }
    Row(
        modifier = modifier
            .width(width)
            .shadow(elevation = 10.dp, shape = shape)
            .clip(shape)
            .background(
                if (hovered && !busy) {
                    scheme.primary.copy(alpha = 0.06f).compositeOver(scheme.surface)
                } else {
                    scheme.surface
                },
            )
            .border(
                width = 1.dp,
                color = if (hovered && !busy) {
                    accent.copy(alpha = 0.45f)
                } else {
                    scheme.outlineVariant.copy(alpha = 0.9f)
                },
                shape = shape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !busy,
                onClick = onAccept,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧强调条：像补全弹窗里「当前项」的标记，也把建议和正文分开一点。
        Box(
            Modifier
                .width(2.dp)
                .height(15.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = aiFillLabelTextStyle(
                when {
                    error != null -> scheme.error
                    busy -> scheme.onSurfaceVariant
                    else -> scheme.onSurface
                },
            ),
            maxLines = 1,
            softWrap = false,
        )
        if (showTab) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = "Tab",
                style = aiFillKeyTextStyle(scheme.primary.copy(alpha = 0.82f)),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.primary.copy(alpha = 0.10f))
                    .border(
                        width = 1.dp,
                        color = scheme.primary.copy(alpha = 0.30f),
                        shape = RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
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
