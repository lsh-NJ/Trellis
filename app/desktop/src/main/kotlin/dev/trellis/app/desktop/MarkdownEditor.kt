package dev.trellis.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 知识库的 Markdown 编辑器。
 *
 * 设计取舍（这一轮先做「好用的编辑器」，不做概念抽取）：
 *
 * - 纯本地：文本只经过 [onBodyChange] 回到页面状态，页面负责落盘；
 * - 工具栏和快捷键都走 [MarkdownEditResult] 纯函数，选区/光标可测试；
 * - 编辑 / 预览切换，预览复用会话页的 [MarkdownText]，不另造渲染器；
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
    /** 概念详情点「定位」时选中正文里的命中处；null = 不定位。 */
    reveal: TextRange? = null,
    /** 每次点「定位」+1：同一个范围再点一次也能重新选中（range 相同时靠它触发）。 */
    revealToken: Int = 0,
) {
    // 每篇笔记一个编辑缓冲；切笔记时重置。光标/选区留在内部，页面只关心文本。
    var field by remember(noteId) { mutableStateOf(TextFieldValue(body)) }
    val focus = remember { FocusRequester() }

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
        focus.requestFocus()
    }

    fun command(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
        event.isCtrlPressed || event.isMetaPressed

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 工具栏：编辑时才出现；预览切换按钮常驻在右侧。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!preview) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MdToolbarButton("H1") { apply(setHeadingLevel(field.text, field.selection, 1)) }
                    MdToolbarButton("H2") { apply(setHeadingLevel(field.text, field.selection, 2)) }
                    Spacer(Modifier.width(6.dp))
                    MdToolbarButton("B") { apply(toggleInlineMarkdown(field.text, field.selection, "**", "粗体")) }
                    MdToolbarButton("I") { apply(toggleInlineMarkdown(field.text, field.selection, "*", "斜体")) }
                    MdToolbarButton("代码") { apply(toggleInlineMarkdown(field.text, field.selection, "`", "代码")) }
                    MdToolbarButton("删除线") { apply(toggleInlineMarkdown(field.text, field.selection, "~~", "删除线")) }
                    Spacer(Modifier.width(6.dp))
                    MdToolbarButton("列表") { apply(toggleLineMarkdown(field.text, field.selection, "- ")) }
                    MdToolbarButton("编号") { apply(toggleNumberedList(field.text, field.selection)) }
                    MdToolbarButton("引用") { apply(toggleLineMarkdown(field.text, field.selection, "> ")) }
                    Spacer(Modifier.width(6.dp))
                    MdToolbarButton("链接") { apply(insertMarkdownLink(field.text, field.selection)) }
                    MdToolbarButton("代码块") { apply(insertCodeBlock(field.text, field.selection)) }
                    MdToolbarButton("表格") { apply(insertMarkdownTable(field.text, field.selection)) }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            ChromeButton(
                label = if (preview) "编辑" else "预览",
                onClick = onTogglePreview,
                filled = false,
                mono = true,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(ControlShape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ControlShape)
                .background(MaterialTheme.colorScheme.surface),
        ) {
            if (preview) {
                MarkdownPreview(body)
            } else {
                BasicTextField(
                    value = field,
                    onValueChange = { updated ->
                        field = updated
                        if (updated.text != body) onBodyChange(updated.text)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else if (command(event) && event.key == Key.B) {
                                apply(toggleInlineMarkdown(field.text, field.selection, "**", "粗体")); true
                            } else if (command(event) && event.key == Key.I) {
                                apply(toggleInlineMarkdown(field.text, field.selection, "*", "斜体")); true
                            } else if (command(event) && event.key == Key.K) {
                                apply(insertMarkdownLink(field.text, field.selection)); true
                            } else if (event.key == Key.Enter && !event.isShiftPressed && !command(event)) {
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
                                    "开始写吧。Markdown 支持标题、列表、引用、代码块、表格；工具栏和 Cmd/Ctrl+B 都能用。",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                body.length.toString() + " 字符",
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

@Composable
private fun MarkdownPreview(body: String) {
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
    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            MarkdownText(body, bodyStyle = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** 工具栏的小按钮：比 [ChromeButton] 矮一档，一行放得下十几个。 */
@Composable
private fun MdToolbarButton(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(ControlShape)
            .background(if (hovered) accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = AppFonts.mono,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
