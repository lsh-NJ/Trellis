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

/** 编辑器形态：整篇单文本框（默认，功能最全）。 */
internal const val MARKDOWN_EDITOR_COMPOSE: String = "compose"

/** 编辑器形态：段落式（WebView + Tiptap，实验；段距/空行语义见 docs/markdown-editor-review-2026-11-12.md）。 */
internal const val MARKDOWN_EDITOR_WEBVIEW: String = "webview"

/**
 * 当前平台实际会用哪个编辑器（页面据此决定排版：WebView 要撑满剩余高度，Compose 跟着页面滚）。
 *
 * 桌面看 `TRELLIS_EDITOR` 覆盖再落到设置项；安卓只有 Compose。
 */
internal expect fun resolvePlatformEditorMode(preference: String): String

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
    /** 编辑器里的「AI 补全」：传入当前正文与槽位行号，返回补全后的整篇正文。 */
    onRequestAiFill: (suspend (String, Int) -> String?)?,
    /** 编辑器形态：[MARKDOWN_EDITOR_COMPOSE] / [MARKDOWN_EDITOR_WEBVIEW]。 */
    editorMode: String,
    /** 生效的快捷键（用户改过键位后从 AppRuntime 传进来）。 */
    bindings: List<MarkdownBinding>,
    /** 「菜单」（快捷键清单）是否打开；桌面 WebView 版在页面里画、Compose 版用浮层。 */
    menuOpen: Boolean,
    /** 页面里的菜单被关掉（点遮罩 / Esc）时回调，页面要把状态同步回去。 */
    onMenuClosed: () -> Unit,
)
