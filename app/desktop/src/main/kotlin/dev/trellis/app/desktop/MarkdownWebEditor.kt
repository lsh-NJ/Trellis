package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities
import netscape.javascript.JSObject

/**
 * WebView 版编辑器（`TRELLIS_EDITOR=webview` 时启用，默认仍用 [MarkdownDocumentEditor]）。
 *
 * 为什么需要它：Compose 的整篇单文本框里，行高由 `textStyle.lineHeight` 统一决定，
 * 段落之间的空行只能整行占——段间距只能在 1 倍 / 2 倍里选。marktext 这类编辑器之所以
 * 能做到 1.25–1.5 倍，是因为正文是浏览器里的块级 DOM，段间距就是 CSS `margin`。
 *
 * 这一版只做到「能用 + 默认不启用」：
 *  - 页面 = `resources/editor/index.html` + 打包好的 Tiptap bundle（见 editor.js 顶部说明）；
 *  - 正文改动通过 JS 桥 `trellisEditorBridge.onUpdate(markdown)` 回推，失焦回调 `onBlur`；
 *  - Compose 侧只负责铺一块 `SwingPanel`，不改页面的其它逻辑（自动保存、AI 整理都照旧）。
 *
 * 还没有对齐的能力（P2 做）：`[[概念]]` 补全、gutter 段落标记、斜杠菜单、快捷键、
 * 主题色随 Compose 主题走、AI 定位跳转。
 */
@Composable
internal fun MarkdownWebEditor(
    noteId: String,
    body: String,
    onBodyChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onEditingFinished: (() -> Unit)? = null,
) {
    // 只建一个宿主：切笔记时用 setMarkdown 换内容，重建 WebView 会留下空白/重复的面板。
    val host = remember { MarkdownWebHost() }
    val latestChange by rememberUpdatedState(onBodyChange)
    val latestFinished by rememberUpdatedState(onEditingFinished)
    // WebView 自己发上来的正文；记下来避免把它再灌回 WebView（否则每次击键都会 setContent）。
    var applied by remember { mutableStateOf(body) }

    SideEffect {
        host.onMarkdownChanged = { markdown ->
            if (markdown != applied) {
                applied = markdown
                latestChange(markdown)
            }
        }
        host.onEditingFinished = { latestFinished?.invoke() }
    }

    LaunchedEffect(noteId, body) {
        if (body != applied) {
            applied = body
            host.setMarkdown(body)
        }
    }
    DisposableEffect(host) { onDispose { host.dispose() } }

    // 页面把编辑器放在 verticalScroll 里（高度约束是无限的），所以这里显式给一个最小高度，
    // 让 SwingPanel 有实际尺寸可画；WebView 自己内部滚动。
    Box(modifier.fillMaxWidth().heightIn(min = 560.dp)) {
        SwingPanel(factory = { host.panel }, modifier = Modifier.fillMaxWidth().heightIn(min = 560.dp))
    }
}

/**
 * JavaFX WebView 的宿主：一个 JPanel 里放 JFXPanel，负责页面加载、Markdown 灌入、
 * JS → Kotlin 的回调。所有 JavaFX 操作都切到 FX 线程，所有 Compose 回调都切回 Swing 线程。
 */
private class MarkdownWebHost {
    val panel = JPanel(BorderLayout())

    var onMarkdownChanged: ((String) -> Unit)? = null
    var onEditingFinished: (() -> Unit)? = null

    private val fxPanel = JFXPanel()
    private var webView: WebView? = null
    private var bridgeInstalled = false
    private var bridge: MarkdownEditorBridge? = null
    private var pendingMarkdown: String? = null

    init {
        Platform.setImplicitExit(false)
        panel.add(fxPanel, BorderLayout.CENTER)
        Platform.runLater {
            val view = WebView()
            webView = view
            fxPanel.scene = Scene(view)
            view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) installBridge()
            }
            view.engine.loadContent(editorPage())
        }
    }

    fun setMarkdown(markdown: String) {
        Platform.runLater {
            if (!bridgeInstalled) {
                pendingMarkdown = markdown
                return@runLater
            }
            runCatching { webView?.engine?.executeScript("window.setMarkdown(${jsString(markdown)})") }
        }
    }

    fun dispose() {
        Platform.runLater {
            webView?.engine?.load(null)
            webView = null
            bridgeInstalled = false
        }
    }

    private fun installBridge() {
        val window = webView?.engine?.executeScript("window") as? JSObject ?: return
        val jxb = MarkdownEditorBridge().also { b ->
            b.markdownListener = { markdown -> SwingUtilities.invokeLater { onMarkdownChanged?.invoke(markdown) } }
            b.blurListener = { SwingUtilities.invokeLater { onEditingFinished?.invoke() } }
        }
        bridge = jxb
        window.setMember("trellisEditorBridge", jxb)
        bridgeInstalled = true
        pendingMarkdown?.let { markdown ->
            pendingMarkdown = null
            runCatching { webView?.engine?.executeScript("window.setMarkdown(${jsString(markdown)})") }
        }
    }

}

/**
 * JS 侧通过 `window.trellisEditorBridge.onUpdate(...)/onBlur()` 调到这里（FX 线程）。
 *
 * 必须是 **public 的方法**、并且宿主里要一直握着这个对象：JavaFX 的 JS 桥走反射，
 * 私有类/被 GC 掉的临时对象都会让 JS 侧调用静默失败。
 */
internal class MarkdownEditorBridge {
    var markdownListener: ((String) -> Unit)? = null
    var blurListener: (() -> Unit)? = null

    fun onUpdate(markdown: String) {
        markdownListener?.invoke(markdown)
    }

    fun onBlur() {
        blurListener?.invoke()
    }

    fun onFocus() = Unit
}

/** 把 index.html 和打包好的 editor.js 拼成一页，直接 loadContent（不走临时文件）。 */

private fun editorPage(): String {
    val html = readResource("/editor/index.html")
    val script = readResource("/editor/editor.js")
    return html.replace("</body>", "<script>$script</script></body>")
}

private fun readResource(path: String): String =
    MarkdownWebHost::class.java.getResourceAsStream(path)
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
        ?: error("缺少编辑器资源：$path")

/** 生成一段可安全塞进 `executeScript("window.setMarkdown(...)")` 的 JS 字符串字面量。 */
private fun jsString(value: String): String = buildString(value.length + 16) {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(ch)
        }
    }
    append('"')
}
