package dev.trellis.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange

/**
 * 安卓：只有 Compose 编辑器。
 *
 * 桌面的 WebView 路线依赖 JavaFX + Swing，安卓上两者都没有；手机上也不需要
 * 1.25 倍段间距这种精细排版 —— 触摸屏上更重要的是光标、选区手柄和输入法。
 */
@Composable
internal actual fun PlatformMarkdownEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier,
    conceptNames: List<String>,
    onDeleteAiLine: ((String) -> Unit)?,
    reveal: TextRange?,
    revealToken: Int,
    @Suppress("UNUSED_PARAMETER") onPageShortcut: ((String) -> Unit)?,
    onEditingFinished: (() -> Unit)?,
    onRequestAiFill: (suspend (String, Int) -> String?)?,
) {
    MarkdownDocumentEditor(
        noteId = noteId,
        body = body,
        onBodyChange = onBodyChange,
        modifier = modifier,
        conceptNames = conceptNames,
        onDeleteAiLine = onDeleteAiLine,
        reveal = reveal,
        revealToken = revealToken,
        onEditingFinished = onEditingFinished,
        onRequestAiFill = onRequestAiFill,
    )
}
