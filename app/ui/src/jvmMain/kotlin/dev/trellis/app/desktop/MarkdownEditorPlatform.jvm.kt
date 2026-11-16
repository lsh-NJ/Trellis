package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「该用哪个编辑器」的解析：设置项是常态来源，环境变量是给演示/调试的强制开关。
 *
 * 优先级（高 → 低）：
 * 1. `TRELLIS_EDITOR=webview|compose`：调试/演示时直接钉死，不受用户设置影响；
 * 2. 设置项 `UiPrefs.editorMode`：用户在设置页选的那个；
 * 3. 缺省 = [MARKDOWN_EDITOR_WEBVIEW]。2026-11-15 起段落式是默认编辑器；设置页仍保留
 *    「单文本（Compose）」作为退路，等 WebView 功能补齐后再删。
 *
 * 单独抽成纯函数是为了能在单测里钉住优先级（见 MarkdownEditorPlatformTest）：
 * 这里搞错会表现成「设置里选了段落式，重启又变回单文本」。
 */
internal actual fun resolvePlatformEditorMode(preference: String): String =
    resolveMarkdownEditorMode(preference, System.getenv("TRELLIS_EDITOR"))

internal fun resolveMarkdownEditorMode(preference: String, env: String?): String {
    val forced = env?.trim()?.lowercase()
    return when (forced) {
        MARKDOWN_EDITOR_WEBVIEW -> MARKDOWN_EDITOR_WEBVIEW
        MARKDOWN_EDITOR_COMPOSE -> MARKDOWN_EDITOR_COMPOSE
        else -> preference.takeIf { it == MARKDOWN_EDITOR_COMPOSE } ?: MARKDOWN_EDITOR_WEBVIEW
    }
}

/** 段落式编辑器的启动态：启动中先占位，起不来就回退单文本。 */
internal enum class WebViewState { Booting, Ready, Unavailable }

/**
 * 纯函数：JavaFX 启动结果 → 该挂什么。
 *
 * 最重要的一条是「失败必须回退」：JavaFX 初始化失败时如果还硬挂 `JFXPanel`，
 * EDT 会在 `SecondaryLoop` 里等一个永远不来的初始化，窗口就假死了。
 */
internal fun webViewStateFor(javaFxReady: Boolean): WebViewState =
    if (javaFxReady) WebViewState.Ready else WebViewState.Unavailable

/**
 * 桌面：默认段落式（WebView）；设置页选「单文本」或 `TRELLIS_EDITOR=compose` 时用 Compose 那套。
 *
 * JavaFX 的启动被挪到后台线程并带超时（[MarkdownWebRuntime]）：失败时**回退到 Compose 编辑器**
 * 并给一行说明，绝不让整页点不动。
 *
 * 参数语义见 [MarkdownDocumentEditor]。
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
    onPageShortcut: ((String) -> Unit)?,
    onEditingFinished: (() -> Unit)?,
    onRequestAiFill: (suspend (String, Int) -> String?)?,
    editorMode: String,
    bindings: List<MarkdownBinding>,
    menuOpen: Boolean,
    onMenuClosed: () -> Unit,
) {
    val mode = resolveMarkdownEditorMode(editorMode, System.getenv("TRELLIS_EDITOR"))
    if (mode != MARKDOWN_EDITOR_WEBVIEW) {
        Box(modifier) {
        ComposeMarkdownEditor(
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
            bindings = bindings,
        )
        if (menuOpen) {
            MarkdownMenuOverlay(onClose = onMenuClosed, bindings = bindings)
        }
        }
        return
    }

    // null = 还没启动完。JavaFX toolkit 起得慢（首次解压原生库），主线程不能等。
    val state by produceState<WebViewState?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            webViewStateFor(MarkdownWebRuntime.ensureReadyBlocking())
        }
    }
    when (state) {
        null -> WebViewBooting(modifier)
        WebViewState.Unavailable -> Column(modifier) {
            WebViewFallbackNotice(MarkdownWebRuntime.failureMessage())
            ComposeMarkdownEditor(
                noteId = noteId,
                body = body,
                onBodyChange = onBodyChange,
                modifier = Modifier.fillMaxWidth(),
                conceptNames = conceptNames,
                onDeleteAiLine = onDeleteAiLine,
                reveal = reveal,
                revealToken = revealToken,
                onEditingFinished = onEditingFinished,
                onRequestAiFill = onRequestAiFill,
                bindings = bindings,
            )
            if (menuOpen) {
                MarkdownMenuOverlay(onClose = onMenuClosed, bindings = bindings)
            }
        }

        WebViewState.Ready, WebViewState.Booting -> MarkdownWebEditor(
            noteId = noteId,
            body = body,
            onBodyChange = onBodyChange,
            modifier = modifier,
            conceptNames = conceptNames,
            reveal = reveal,
            revealToken = revealToken,
            onPageShortcut = onPageShortcut,
            onEditingFinished = onEditingFinished,
            bindings = bindings,
            menuOpen = menuOpen,
            onMenuClosed = onMenuClosed,
        )
    }
}

@Composable
private fun ComposeMarkdownEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier,
    conceptNames: List<String>,
    onDeleteAiLine: ((String) -> Unit)?,
    reveal: TextRange?,
    revealToken: Int,
    onEditingFinished: (() -> Unit)?,
    onRequestAiFill: (suspend (String, Int) -> String?)?,
    bindings: List<MarkdownBinding>,
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
        bindings = bindings,
    )
}

/** 启动占位：给和正文编辑区一样高的空白，避免布局跳一下。 */
@Composable
private fun WebViewBooting(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 560.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "正在启动段落式编辑器…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 回退提示：告诉用户「为什么不是段落式」，以及现在用的是哪个编辑器。 */
@Composable
private fun WebViewFallbackNotice(reason: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "段落式编辑器没有启动起来" + (reason?.let { "（" + it + "）" } ?: "") +
                "，已回退到单文本编辑器；可以在设置 → 编辑器里切回单文本。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
