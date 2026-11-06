package dev.trellis.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange

/**
 * 正文编辑器在平台上的分派点。
 *
 * 桌面多一条实验路径：`TRELLIS_EDITOR=webview` 时用 JavaFX WebView 跑 Tiptap，
 * 为的是浏览器 CSS 的段间距（Compose 只能 1 倍 / 2 倍两档）。安卓没有 JavaFX，
 * 也不该为了一段文字引一个 WebView，所以只有 Compose 版。
 *
 * 参数语义见 [MarkdownDocumentEditor]。
 */
@Composable
internal expect fun PlatformMarkdownEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier,
    conceptNames: List<String>,
    onDeleteAiLine: ((String) -> Unit)?,
    reveal: TextRange?,
    revealToken: Int,
    onPageShortcut: ((String) -> Unit)?,
    onEditingFinished: (() -> Unit)?,
)
