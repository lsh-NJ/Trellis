package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import dev.trellis.ui.LocalExtraColors
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.JPanel
import javax.swing.SwingUtilities
import netscape.javascript.JSObject

/**
 * WebView 版编辑器（设置页「编辑器 → 段落式」或 `TRELLIS_EDITOR=webview` 时启用，默认仍用 [MarkdownDocumentEditor]）。
 *
 * 为什么需要它：Compose 的整篇单文本框里，行高由 `textStyle.lineHeight` 统一决定，
 * 段落之间的空行只能整行占——段间距只能在 1 倍 / 2 倍里选。marktext 这类编辑器之所以
 * 能做到 1.25–1.5 倍，是因为正文是浏览器里的块级 DOM，段间距就是 CSS `margin`。
 *
 * 这一版只做到「能用 + 默认不启用」：
 *  - 页面 = `resources/editor/index.html` + Tiptap bundle（源码在 `tools/web-editor/`，`pnpm run build` 产出；见该目录 README）；
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
    conceptNames: List<String> = emptyList(),
    reveal: TextRange? = null,
    revealToken: Int = 0,
    onPageShortcut: ((String) -> Unit)? = null,
    onEditingFinished: (() -> Unit)? = null,
    bindings: List<MarkdownBinding> = defaultMarkdownBindings,
    menuOpen: Boolean = false,
    onMenuClosed: () -> Unit = {},
) {
    // 只建一个宿主：切笔记时用 setMarkdown 换内容，重建 WebView 会留下空白/重复的面板。
    val host = remember { MarkdownWebHost() }
    val latestChange by rememberUpdatedState(onBodyChange)
    val latestFinished by rememberUpdatedState(onEditingFinished)
    val latestShortcut by rememberUpdatedState(onPageShortcut)
    val latestMenuClosed by rememberUpdatedState(onMenuClosed)
    val scheme = MaterialTheme.colorScheme
    val conceptGreen = LocalExtraColors.current.accentText
    val theme = remember(scheme.background, scheme.onBackground, scheme.onSurfaceVariant, conceptGreen) {
        WebTheme(
            background = scheme.background.toCss(),
            onBackground = scheme.onBackground.toCss(),
            onSurfaceVariant = scheme.onSurfaceVariant.toCss(),
            accent = conceptGreen.toCss(),
            accentSoft = conceptGreen.copy(alpha = 0.62f).toCss(),
            codeBackground = Color(0x2E9E9E9E).toCss(),
            border = scheme.onSurfaceVariant.copy(alpha = 0.32f).toCss(),
        )
    }
    LaunchedEffect(theme) { host.setTheme(theme) }
    // `[[` 补全用的概念名（来自本文已有标注 + 概念库），变化时重灌一次。
    LaunchedEffect(conceptNames) { host.setConcepts(conceptNames) }
    // AI 补全 / 搜索命中 / 概念反查的定位跳转（Compose 侧传的是 Markdown 原文偏移）。
    LaunchedEffect(revealToken) { reveal?.let { host.reveal(it.min, it.max) } }
    // WebView 自己发上来的正文；记下来避免把它再刷回 WebView（否则每次击键都会 setContent）。
    // 初值必须是「还没灌过」而不是当前 body：首次组合时 body 就已经是打开的笔记，
    // 若把 applied 初始化成 body，下面的 LaunchedEffect 会判定「一样」而不下发，
    // 页面就永远停在空文档（表现为编辑器一片空白，只剩 gutter 的 ¶）。
    var applied by remember { mutableStateOf("") }

    SideEffect {
        host.onMarkdownChanged = { markdown ->
            if (markdown != applied) {
                applied = markdown
                latestChange(markdown)
            }
        }
        host.onEditingFinished = { latestFinished?.invoke() }
        host.onShortcut = { latestShortcut?.invoke(it) }
        host.onMenuClosed = { latestMenuClosed() }
        // 笔记里的链接：永远用系统默认浏览器打开，不用 WebView 自己导航（见 openExternalLink）。
        host.onLinkClick = { url -> openExternalLink(url) }
    }

    LaunchedEffect(noteId, body) {
        if (body != applied) {
            applied = body
            host.setMarkdown(body)
        }
    }
    // 「菜单」在页面里画（Compose 的浮层会被重量级 JFXPanel 盖住）：打开时把清单推给页面，
    // 关闭由页面内的遮罩/Esc 触发，通过桥回调把状态同步回 Compose。
    LaunchedEffect(menuOpen, bindings) {
        if (menuOpen) host.showShortcutMenu(markdownMenuJson(bindings)) else host.hideShortcutMenu()
    }
    DisposableEffect(host) { onDispose { host.dispose() } }

    // WebView 自己内部滚动：页面（KnowledgeScreen）在段落式模式下会把这里的 modifier 设成
    // weight(1f)，所以直接吃满剩余高度；再给一个最小高度兜底（例如权限/布局异常时）。
    Box(modifier.fillMaxWidth().heightIn(min = 320.dp)) {
        SwingPanel(factory = { host.panel }, modifier = Modifier.fillMaxSize())
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
    var onShortcut: ((String) -> Unit)? = null
    var onMenuClosed: (() -> Unit)? = null
    var onLinkClick: ((String) -> Unit)? = null

    private val fxPanel = JFXPanel()
    private var webView: WebView? = null
    private var bridgeInstalled = false
    private var bridge: MarkdownEditorBridge? = null
    private var pendingTheme: WebTheme? = null
    private var pendingConcepts: List<String>? = null
    private var pendingMarkdown: String? = null
    private var pendingReveal: Pair<Int, Int>? = null
    private var pendingMenu: String? = null

    /** 上一次鼠标按下时，指针是不是落在 WebView 里（用来判断「点到编辑器外面了」）。 */
    private var pointerWasInside = false

    /**
     * Compose 的点击不会让 WebView 的 DOM 失焦（两套焦点系统互不知道），所以「点别处 = 写完了」
     * 只能在 AWT 层听鼠标：按下点不在 JFXPanel 里，就当编辑器失焦，通知页面跑 AI 整理。
     */
    private val globalClickWatcher = AWTEventListener { event ->
        val mouse = event as? MouseEvent ?: return@AWTEventListener
        if (mouse.id != MouseEvent.MOUSE_PRESSED) return@AWTEventListener
        val source = mouse.component ?: return@AWTEventListener
        val inside = source === fxPanel || SwingUtilities.isDescendingFrom(source, fxPanel)
        if (inside) {
            pointerWasInside = true
            // 键盘事件走的是 AWT 焦点链：只在 JavaFX 场景里落光标不够，Compose 的画布
            // 还占着 AWT 焦点，页面能点、能滚动，但一个键都打不进去（打包版实测）。
            if (!fxPanel.isFocusOwner) fxPanel.requestFocusInWindow()
        } else if (pointerWasInside) {
            pointerWasInside = false
            SwingUtilities.invokeLater { onEditingFinished?.invoke() }
        }
    }.also { Toolkit.getDefaultToolkit().addAWTEventListener(it, AWTEvent.MOUSE_EVENT_MASK) }

    init {
        Platform.setImplicitExit(false)
        panel.add(fxPanel, BorderLayout.CENTER)
        Platform.runLater {
            val view = WebView()
            webView = view
            fxPanel.scene = Scene(view)
            view.engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) {
                    // 不要在这个回调里直接 executeScript：WebKit 还在派发 load 事件，
                    // 重入它的 JS 绑定（JSObject / get_method_id）会踩到已释放的引用，
                    // 实测在 jpackage 出来的运行时里直接 SIGSEGV（gradle run 只是没撞上）。
                    // 推到下一个 FX pulse 再装桥，顺便用 runLater 天然去重。
                    Platform.runLater { installBridgeOnce() }
                }
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

    fun setTheme(theme: WebTheme) {
        Platform.runLater {
            if (!bridgeInstalled) {
                pendingTheme = theme
                return@runLater
            }
            webView?.engine?.executeScript("window.setTheme(${theme.toJsObject()})")
        }
    }

    /** 打开页面里的「菜单」浮层（快捷键清单）；json 是 markdownMenuJson() 的产物。 */
    fun showShortcutMenu(json: String) {
        Platform.runLater {
            if (!bridgeInstalled) {
                pendingMenu = json
                return@runLater
            }
            runCatching { webView?.engine?.executeScript("window.showShortcutMenu($json)") }
        }
    }

    fun hideShortcutMenu() {
        pendingMenu = null
        Platform.runLater {
            if (!bridgeInstalled) return@runLater
            runCatching { webView?.engine?.executeScript("window.hideShortcutMenu()") }
        }
    }

    fun setConcepts(names: List<String>) {
        Platform.runLater {
            if (!bridgeInstalled) {
                pendingConcepts = names
                return@runLater
            }
            webView?.engine?.executeScript("window.setConcepts(${jsStringArray(names)})")
        }
    }

    fun reveal(start: Int, end: Int) {
        Platform.runLater {
            if (!bridgeInstalled) {
                pendingReveal = start to end
                return@runLater
            }
            runCatching { webView?.engine?.executeScript("window.revealRange($start, $end)") }
        }
    }

    fun dispose() {
        runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(globalClickWatcher) }
        Platform.runLater {
            webView?.engine?.load(null)
            webView = null
            bridgeInstalled = false
        }
    }

    /** 只装一次：加载完成可能被多个状态回调/重试触发。 */
    private fun installBridgeOnce() {
        if (bridgeInstalled) return
        installBridge()
    }

    private fun installBridge() {
        val window = webView?.engine?.executeScript("window") as? JSObject ?: return
        val jxb = MarkdownEditorBridge().also { b ->
            b.markdownListener = { markdown -> SwingUtilities.invokeLater { onMarkdownChanged?.invoke(markdown) } }
            b.blurListener = { SwingUtilities.invokeLater { onEditingFinished?.invoke() } }
            b.shortcutListener = { name -> SwingUtilities.invokeLater { onShortcut?.invoke(name) } }
            b.menuClosedListener = { SwingUtilities.invokeLater { onMenuClosed?.invoke() } }
            b.linkListener = { url -> SwingUtilities.invokeLater { onLinkClick?.invoke(url) } }
        }
        bridge = jxb
        window.setMember("trellisEditorBridge", jxb)
        bridgeInstalled = true
        pendingTheme?.let { theme ->
            pendingTheme = null
            webView?.engine?.executeScript("window.setTheme(${theme.toJsObject()})")
        }
        pendingConcepts?.let { names ->
            pendingConcepts = null
            webView?.engine?.executeScript("window.setConcepts(${jsStringArray(names)})")
        }
        pendingMarkdown?.let { markdown ->
            pendingMarkdown = null
            runCatching { webView?.engine?.executeScript("window.setMarkdown(${jsString(markdown)})") }
        }
        pendingReveal?.let { (start, end) ->
            pendingReveal = null
            runCatching { webView?.engine?.executeScript("window.revealRange($start, $end)") }
        }
        pendingMenu?.let { json ->
            pendingMenu = null
            runCatching { webView?.engine?.executeScript("window.showShortcutMenu($json)") }
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
    var shortcutListener: ((String) -> Unit)? = null
    var menuClosedListener: (() -> Unit)? = null
    var linkListener: ((String) -> Unit)? = null

    fun onUpdate(markdown: String) {
        markdownListener?.invoke(markdown)
    }

    fun onBlur() {
        blurListener?.invoke()
    }

    fun onFocus() = Unit

    fun onShortcut(name: String) {
        shortcutListener?.invoke(name)
    }

    /** 页面里的「菜单」被关掉（点遮罩 / Esc）。 */
    fun onMenuClosed() {
        menuClosedListener?.invoke()
    }

    /** 笔记里的链接被点击：交给宿主的默认浏览器打开。 */
    fun onLinkClick(url: String) {
        linkListener?.invoke(url)
    }
}

/**
 * 用系统默认浏览器打开笔记里的链接。
 *
 * 只放行 http / https / mailto：笔记可能来自外部导入，`file:`、`javascript:`、`data:`
 * 这类协议一律不交给系统（安全边界），失败也只写一行日志、不弹窗打扰。
 * browse 是系统调用，可能卡住，所以丢到后台线程，不占 FX / Swing 线程。
 */
internal fun openExternalLink(raw: String) {
    val uri = externalLinkUri(raw) ?: return
    if (!Desktop.isDesktopSupported()) return
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) return
    Thread({
        runCatching { desktop.browse(uri) }
            .onFailure { println("Trellis: 打开链接失败（" + uri.scheme + "）：" + it.message) }
    }, "trellis-open-link").apply {
        isDaemon = true
        start()
    }
}

/** 解析出可以交给系统浏览器的链接；协议不在白名单里/解析不了就返回 null。 */
internal fun externalLinkUri(raw: String): URI? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in OPENABLE_LINK_SCHEMES) return null
    return uri
}

/** 允许交给系统浏览器的协议：只放行 web 与邮件。 */
internal val OPENABLE_LINK_SCHEMES: Set<String> = setOf("http", "https", "mailto")

/** 把 index.html 和打包好的 editor.js 拼成一页，直接 loadContent（不走临时文件）。 */

private fun editorPage(): String {
    val html = readResource("/editor/index.html")
    val script = readResource("/editor/editor.js")
    // KaTeX 的样式（连字体 base64 一起）由 tools/web-editor/scripts/embed-katex.mjs 生成：
    // loadContent 没有 base URL，外链 CSS/字体都加载不到，只能整段内联。
    val katex = runCatching { readResource("/editor/katex.css") }.getOrNull()
    val withStyle = if (katex.isNullOrBlank()) html else html.replace("</head>", "<style>$katex</style></head>")
    return withStyle.replace("</body>", "<script>$script</script></body>")
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

/** 把字符串列表转成 JS 数组字面量（给 `window.setConcepts([...])` 用）。 */
private fun jsStringArray(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]", separator = ",") { jsString(it) }

/** 从 Compose 主题带进 WebView 的颜色（CSS 变量）。 */
private data class WebTheme(
    val background: String,
    val onBackground: String,
    val onSurfaceVariant: String,
    val accent: String,
    val accentSoft: String,
    val codeBackground: String,
    val border: String,
) {
    fun toJsObject(): String = buildString {
        append('{')
        append("background:").append(jsString(background)).append(',')
        append("onBackground:").append(jsString(onBackground)).append(',')
        append("onSurfaceVariant:").append(jsString(onSurfaceVariant)).append(',')
        append("accent:").append(jsString(accent)).append(',')
        append("accentSoft:").append(jsString(accentSoft)).append(',')
        append("codeBackground:").append(jsString(codeBackground)).append(',')
        append("border:").append(jsString(border))
        append('}')
    }
}

/** Compose 颜色转 CSS：不透明给 #rrggbb，半透明给 rgba(...)。 */
private fun Color.toCss(): String {
    val argb = toArgb()
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    return if (a >= 0xFF) {
        "#%02x%02x%02x".format(r, g, b)
    } else {
        "rgba($r, $g, $b, ${"%.3f".format(a / 255f)})"
    }
}
