package dev.trellis.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange

/** 桌面：默认 Compose 编辑器；`TRELLIS_EDITOR=webview` 时换成 JavaFX 那套。 */
private fun useWebViewEditor(): Boolean =
    System.getenv("TRELLIS_EDITOR")?.equals("webview", ignoreCase = true) == true

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
    onEditingFinished: (() -> Unit)?,
) {
    if (useWebViewEditor()) {
        MarkdownWebEditor(
            noteId = noteId,
            body = body,
            onBodyChange = onBodyChange,
            modifier = modifier,
            conceptNames = conceptNames,
            onEditingFinished = onEditingFinished,
        )
    } else {
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
        )
    }
}
