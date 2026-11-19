package dev.trellis.app.desktop

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import javafx.application.Platform
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.stage.Stage
import javax.imageio.ImageIO

/**
 * H1：仓库内的 WebView 编辑器回归 harness（**当前 bundle 基线**）。
 *
 * 为什么要有它：笔记编辑器的 WebView 路线（[MarkdownWebEditor] / editor.js）把 Markdown 的
 * 解析、渲染、再序列化全交给了 Tiptap + turndown。改 bundle、改 Tiptap 版本、改自定义语法
 * （`{{…}}`、`[[…]]`、`:::ai`、`。。。`）时，行为很容易悄悄漂移，而 Compose 侧的
 * 单元测试碰不到 JS。所以这里用**和宿主一模一样的方式**把页面拼起来、真的在 JavaFX WebView
 * 里跑一遍，把 `setMarkdown → getMarkdown/getHTML` 的结果落成基线 JSON + 截图，
 * 供新源码实现做逐条对比。
 *
 * 跑法见 [WebEditorHarnessTest]：只有 `TRELLIS_WEB_HARNESS=1` 时才真正执行，
 * 避免默认 jvmTest 在无显示/无 JavaFX 的机器上挂掉。
 *
 * 页面拼法与宿主一致（MarkdownWebEditor.editorPage）：/editor/index.html 的 HTML
 * + 把 /editor/editor.js 内容内联到 `</body>` 前，再 loadContent。**不要**在这里改
 * editor.js/index.html，harness 只负责跑当前 bundle。
 */
internal object WebEditorHarness {

    private const val VIEW_WIDTH = 900.0
    private const val VIEW_HEIGHT = 1300.0
    private const val PAGE_READY_TIMEOUT_MS = 30_000L
    private const val MARKDOWN_STABLE_TIMEOUT_MS = 3_000L
    private const val POLL_INTERVAL_MS = 100L
    private const val SETTLE_BEFORE_SCREENSHOT_MS = 800L

    private const val FIXTURE_RESOURCE_DIR = "/web-editor/fixtures"

    data class Fixture(val name: String, val markdown: String, val expected: String? = null)

    data class FixtureResult(val input: String, val markdown: String, val html: String)

    /** 一次完整跑批的产出；null 表示环境（窗口/WebView）起不来，调用方按 SKIP 处理。 */
    data class Report(val fixtureCount: Int, val jsonFile: File, val pngFile: File)

    /**
     * 跑全部夹具并写 baseline。
     *
     * 返回 null 只有一种情况：JavaFX toolkit 已经就绪，但**窗口/WebView 创建不出来**
     * （典型是无显示环境）。这种算环境不可用，测试应 SKIP 而不是 FAIL。
     * 页面就绪超时、夹具读不到等属于 harness 自身问题，会直接抛异常让测试红。
     */
    fun run(): Report? {
        val fixtures = loadFixtures()
        check(fixtures.isNotEmpty()) { "找不到夹具：$FIXTURE_RESOURCE_DIR" }
        val outDir = outputDir()
        check(outDir.mkdirs() || outDir.isDirectory) { "建不出输出目录：$outDir" }
        val jsonFile = File(outDir, "baseline-current.json")
        val pngFile = File(outDir, "baseline-current.png")

        val host = try {
            bootPage()
        } catch (t: Throwable) {
            println("[web-editor-harness] SKIP：无法创建 JavaFX 窗口/WebView（${t.javaClass.name}: ${t.message}）")
            t.printStackTrace()
            return null
        }

        val results = LinkedHashMap<String, FixtureResult>()
        val mismatches = ArrayList<String>()
        var symbolFlags = ""
        var headingJson = ""
        var headingBlurJson = ""
        var linkJson = ""
        try {
            awaitEditorReady(host) || error("页面 30 秒内没有就绪（window.editorReady != true）")

            fixtures.forEachIndexed { index, fixture ->
                onFx { host.view.engine.executeScript("window.setMarkdown(${jsString(fixture.markdown)})") }
                val markdown = awaitStableMarkdown(host)
                    ?: error("夹具 ${fixture.name} 的 getMarkdown() 在 3 秒内没有稳定")
                val html = onFx { host.view.engine.executeScript("window.getHTML()") as? String ?: "" }
                results[fixture.name] = FixtureResult(fixture.markdown, markdown, html)
                println(summaryLine(fixture.name, fixture.markdown, markdown))
                if (fixture.expected != null && markdown != fixture.expected) {
                    mismatches += fixture.name
                    println("[web-editor-harness] " + fixture.name + " 与预期不一致（全角符号归一）")
                }
                if (index == 0) {
                    // 截图只取第一个夹具：留一点时间让 WebView 完成重排/字体落位。
                    Thread.sleep(SETTLE_BEFORE_SCREENSHOT_MS)
                    saveScreenshot(host, pngFile)
                }
            }
            // 「菜单」浮层也截一张：它必须在 WebView 里画得出来（Compose 的浮层会被重量级
            // JFXPanel 盖住，所以改成了 DOM 版）。
            runCatching {
                onFx {
                    host.view.engine.executeScript(
                        "window.showShortcutMenu([" +
                            "{title:'编辑器',items:[{action:'粗体',keys:'⌘B'},{action:'表格',keys:'⌘T'}]}," +
                            "{title:'页面',items:[{action:'保存',keys:'⌘S'}]}])",
                    )
                }
                Thread.sleep(SETTLE_BEFORE_SCREENSHOT_MS)
                saveScreenshot(host, File(outDir, "shortcut-menu.png"))
            }
            // 列表按键探针：三级列表末尾回车 → 退格一次（并回上一项、变软换行）→ 再退格一次（合回一行）。
            runCatching {
                val script = """
                    (function () {
                      const e = window.__trellisEditor;
                      window.setMarkdown("1. a\n   1. b\n      1. c\n");
                      e.commands.focus('end');
                      // 把光标放到最后一个列表项（1. c）的末尾：setMarkdown 后文档末尾会多一个空段落，
                      // 直接 focus('end') 会落到那个空段落里，列表按键就测不到了。
                      let target = null;
                      e.state.doc.descendants((node, pos) => {
                        if (node.type.name === 'paragraph' && node.textContent === 'c') target = pos + 1 + node.content.size;
                        return true;
                      });
                      if (target != null) e.commands.setTextSelection(target);
                      const key = (k) => window.__trellisKey(k);
                      const deletePrev = () => {
                        const pos = e.state.selection.from;
                        e.view.dispatch(e.state.tr.delete(pos - 1, pos));
                      };
                      const out = { start: window.getMarkdown() };
                      out.enterReturned = key('Enter');
                      out.afterEnter = window.getMarkdown();
                      out.htmlAfterEnter = window.getHTML();
                      out.backspace1Returned = key('Backspace');
                      out.afterBackspace1 = window.getMarkdown();
                      out.htmlAfterBackspace1 = window.getHTML();
                      out.backspace2Returned = key('Backspace');
                      if (!out.backspace2Returned) deletePrev();
                      out.afterBackspace2 = window.getMarkdown();
                      out.selection = e.state.selection.from;
                      out.depth = e.state.selection.${'$'}from.depth;
                      out.parent = e.state.selection.${'$'}from.parent.type.name;
                      out.ancestors = (() => { const names = []; const f = e.state.selection.${'$'}from; for (let d = 0; d <= f.depth; d += 1) names.push(f.node(d).type.name); return names; })();
                      out.html = window.getHTML();
                      out.docSize = e.state.doc.content.size;
                      return JSON.stringify(out);
                    })()
                """.trimIndent()
                val result = onFx { host.view.engine.executeScript(script) as? String ?: "" }
                println("[web-editor-harness] list-keys: " + result)
                Thread.sleep(400)
                saveScreenshot(host, File(outDir, "list-keys.png"))
            }
            // 全角符号输入探针：逐字符模拟输入（走 ProseMirror 的 handleTextInput，等同真实键盘），
            // 确认真实输入规则也吃全角：概念 ［［］］、＃ 标题、－ 列表、＞ 引用、１． 有序、｛｛｝｝、：：：ai、｀｀｀。
            val symbolScript = """
                (function () {
                  const e = window.__trellisEditor
                  const type = (text) => {
                    for (const ch of text) {
                      const sel = e.state.selection
                      const from = sel.from
                      const to = sel.to
                      const handled = e.view.someProp('handleTextInput', (fn) => fn(e.view, from, to, ch))
                      if (!handled) e.view.dispatch(e.state.tr.insertText(ch, from, to))
                    }
                  }
                  const reset = () => {
                    window.setMarkdown('')
                    e.commands.focus('end')
                  }
                  const flags = []
                  const check = (name, ok) => flags.push(name + '=' + (ok ? '1' : '0'))

                  reset()
                  type('［［线性代数］］')
                  check('concept', /class="concept"/.test(window.getHTML()) && window.getMarkdown().includes('[[线性代数]]'))

                  reset()
                  type('＃ 全角标题')
                  check('heading', /<h1[ >]/.test(window.getHTML()))

                  reset()
                  type('－ 列表项')
                  check('bullet', /<ul[ >]/.test(window.getHTML()))

                  reset()
                  type('＞ 引用')
                  check('quote', /<blockquote[ >]/.test(window.getHTML()))

                  reset()
                  type('１． 有序项')
                  check('ordered', /<ol[ >]/.test(window.getHTML()))

                  reset()
                  type('｛｛AI 内容｝｝')
                  check('aiInline', /class="ai"/.test(window.getHTML()))

                  reset()
                  type('：：：ai')
                  check('aiBlock', /ai-block/.test(window.getHTML()))

                  reset()
                  type('｀｀｀')
                  check('code', /<pre[ >]/.test(window.getHTML()))

                  return flags.join(' ')
                })()
            """.trimIndent()
            symbolFlags = onFx { host.view.engine.executeScript(symbolScript) as? String ?: "" }
            println("[web-editor-harness] symbols: " + symbolFlags)
            Thread.sleep(400)
            saveScreenshot(host, File(outDir, "fullwidth-symbols.png"))
            // 标题/正文交互探针（2026-11-19 用户反馈的三条）：
            //  ① 标题末尾回车 → 下面新起正文段（不再复制同级标题）；
            //  ② 标题开头退格 → 标题降级成正文（不用把内容删光）；
            //  ③ 光标进标题才显影 #（焦点离开全部消失），正文块在左边距画小圆点。
            val headingScript = """
                (function () {
                  const e = window.__trellisEditor
                  const key = (k) => window.__trellisKey(k)
                  const findTop = (type, text) => {
                    let found = null
                    e.state.doc.descendants((node, pos) => {
                      if (found !== null) return false
                      if (node.type.name !== type) return true
                      if (text != null && node.textContent !== text) return true
                      found = { pos: pos, node: node }
                      return false
                    })
                    return found
                  }
                  const checks = []
                  const check = (name, ok) => checks.push(name + '=' + (ok ? '1' : '0'))
                  const out = {}

                  // ① 标题末尾回车 → 新起正文段，再打字确认新行是正文
                  window.setMarkdown('# 一级标题')
                  e.commands.focus('start')
                  const h1 = findTop('heading', null)
                  e.commands.setTextSelection(h1.pos + 1 + h1.node.content.size)
                  out.enterReturned = key('Enter')
                  out.afterEnterHtml = window.getHTML()
                  e.view.dispatch(e.state.tr.insertText('正文', e.state.selection.from))
                  out.afterTypingHtml = window.getHTML()
                  out.afterEnterMarkdown = window.getMarkdown()
                  check('enterNewParagraph', out.afterTypingHtml.indexOf('<p>正文</p>') >= 0 &&
                    out.afterTypingHtml.indexOf('<h1>一级标题</h1>') >= 0)
                  check('enterSingleHeading', (out.afterTypingHtml.match(/<h[1-6][ >]/g) || []).length === 1)

                  // ② 标题开头退格 → 降级成正文（内容不丢）
                  window.setMarkdown('## 二级标题\n\n正文')
                  e.commands.focus('start')
                  const h2 = findTop('heading', '二级标题')
                  e.commands.setTextSelection(h2.pos + 1)
                  out.backspaceReturned = key('Backspace')
                  out.afterBackspaceHtml = window.getHTML()
                  out.afterBackspaceMarkdown = window.getMarkdown()
                  check('backspaceDemotes', out.afterBackspaceHtml.indexOf('<p>二级标题</p>') >= 0 &&
                    out.afterBackspaceHtml.indexOf('<h2') < 0)
                  check('backspaceKeepsText', out.afterBackspaceMarkdown.indexOf('二级标题') >= 0)

                  // ③ 标题中间回车 → 拆成两条同级标题
                  window.setMarkdown('# 标题')
                  e.commands.focus('start')
                  const h3 = findTop('heading', null)
                  e.commands.setTextSelection(h3.pos + 2)
                  out.splitReturned = key('Enter')
                  out.afterSplitHtml = window.getHTML()
                  out.afterSplitMarkdown = window.getMarkdown()
                  check('enterMidSplits', (out.afterSplitHtml.match(/<h1[ >]/g) || []).length === 2)

                  // ④ # 只在光标进入标题时出现；焦点离开全部消失；正文块标记是 paragraph
                  window.setMarkdown('## 标题\n\n正文')
                  e.commands.focus('start')
                  e.view.dispatch(e.state.tr)
                  const syntaxEl = document.querySelector('.heading-syntax')
                  out.syntaxText = syntaxEl ? syntaxEl.textContent : null
                  out.syntaxInHeading = document.querySelectorAll('.heading-syntax').length
                  const gutter1 = document.querySelector('.gutter-current')
                  out.gutterInHeading = gutter1 ? gutter1.getAttribute('data-marker') : null
                  e.commands.focus('end')
                  e.view.dispatch(e.state.tr)
                  out.syntaxInParagraph = document.querySelectorAll('.heading-syntax').length
                  const gutter2 = document.querySelector('.gutter-current')
                  out.gutterInParagraph = gutter2 ? gutter2.getAttribute('data-marker') : null
                  check('syntaxInHeading', out.syntaxInHeading === 1 && out.syntaxText === '##')
                  check('syntaxLeavesWithCaret', out.syntaxInParagraph === 0)
                  check('gutterDotForParagraph', out.gutterInParagraph === 'paragraph')
                  check('gutterHiddenInHeading', out.gutterInHeading === null)

                  out.checks = checks.join(' ')
                  return JSON.stringify(out)
                })()
            """.trimIndent()
            headingJson = onFx { host.view.engine.executeScript(headingScript) as? String ?: "" }
            println("[web-editor-harness] heading: " + headingJson)
            // 焦点离开编辑器：# 与 gutter 都应该消失（等待 blur 的 0ms 重绘，同步读不到）。
            onFx {
                host.view.engine.executeScript(
                    "window.setMarkdown(\"## 标题\\n\\n正文\"); " +
                        "const e = window.__trellisEditor; e.commands.focus('start'); e.view.dispatch(e.state.tr)",
                )
            }
            Thread.sleep(200)
            onFx { host.view.engine.executeScript("window.__trellisEditor.view.dom.blur()") }
            Thread.sleep(300)
            headingBlurJson = onFx {
                host.view.engine.executeScript(
                    "(function(){ return JSON.stringify({syntax: document.querySelectorAll('.heading-syntax').length, " +
                        "gutter: document.querySelectorAll('.gutter-current').length}) })()",
                ) as? String ?: ""
            }
            println("[web-editor-harness] heading-blur: " + headingBlurJson)

            // 链接探针（2026-11-19）：点击 → 过桥交给宿主打开默认浏览器；Option/Alt 点击只落光标；
            // 图标由 CSS ::before 的内联 SVG 画（不是 emoji）；非 http/https/mailto 不打开。
            val linkScript = """
                (function () {
                  const e = window.__trellisEditor
                  window.__openedLinks = []
                  window.trellisEditorBridge = {
                    onUpdate: () => {},
                    onBlur: () => {},
                    onFocus: () => {},
                    onLinkClick: (url) => window.__openedLinks.push(url),
                  }
                  const checks = []
                  const check = (name, ok) => checks.push(name + '=' + (ok ? '1' : '0'))
                  const out = {}
                  const clickEl = (el, extra) => {
                    const rect = el.getBoundingClientRect()
                    const opts = Object.assign(
                      { bubbles: true, cancelable: true, clientX: rect.left + 3, clientY: rect.top + 3 },
                      extra || {},
                    )
                    el.dispatchEvent(new MouseEvent('mousedown', opts))
                    el.dispatchEvent(new MouseEvent('mouseup', opts))
                    el.dispatchEvent(new MouseEvent('click', opts))
                  }

                  window.setMarkdown(
                    '## 参考链接\n\n[Trellis 仓库](https://github.com/lsh-NJ/Trellis) 与 [邮件](mailto:a@b.com)。\n\n危险链接：[坏](javascript:alert(1))。',
                  )
                  e.commands.focus('start')
                  const anchors = document.querySelectorAll('.ProseMirror a')
                  out.anchorCount = anchors.length
                  out.hrefs = Array.prototype.map.call(anchors, (a) => a.getAttribute('href')).join(' | ')
                  out.hasIcon = (() => {
                    const content = window.getComputedStyle(anchors[0], '::before').content || ''
                    // emoji 在 computed style 里可能是 "🌐" 也可能是转义 "\1F310"
                    return content.indexOf('\uD83C\uDF10') >= 0 || content.toLowerCase().indexOf('1f310') >= 0
                  })()
                  out.linkColor = window.getComputedStyle(anchors[0]).color

                  clickEl(anchors[0])
                  out.openedAfterFirst = window.__openedLinks.slice()
                  clickEl(anchors[0], { altKey: true })
                  out.openedAfterAlt = window.__openedLinks.slice()

                  // 光标已经在同一条链接里（等于键盘挪进去）→ 点击只落光标、不打开。
                  const linkPos = e.view.posAtDOM(anchors[0], 0)
                  e.commands.setTextSelection(linkPos + 1)
                  out.caretInLinkBeforeClick = e.isActive('link')
                  clickEl(anchors[0])
                  out.openedAfterCaretClick = window.__openedLinks.slice()

                  clickEl(anchors[1])
                  out.openedAfterMailto = window.__openedLinks.slice()

                  const bad = anchors.length > 2 ? anchors[2] : null
                  if (bad) clickEl(bad)
                  out.openedAfterBad = window.__openedLinks.slice()

                  check('linkRenders', out.anchorCount >= 2)
                  check('linkIcon', out.hasIcon)
                  check('linkBlue', out.linkColor === 'rgb(37, 99, 235)')
                  check('clickOpens', out.openedAfterFirst.length === 1 && out.openedAfterFirst[0].indexOf('github.com') >= 0)
                  check('altClickIgnored', out.openedAfterAlt.length === 1)
                  check('caretClickEdits', out.openedAfterCaretClick.length === 1 && out.caretInLinkBeforeClick === true)
                  check('mailtoOpens', out.openedAfterMailto.length === 2)
                  check('unsafeIgnored', out.openedAfterBad.length === 2)
                  out.checks = checks.join(' ')
                  return JSON.stringify(out)
                })()
            """.trimIndent()
            linkJson = onFx { host.view.engine.executeScript(linkScript) as? String ?: "" }
            println("[web-editor-harness] link: " + linkJson)

            // 深色主题下截两张图：标题语法显影、正文小圆点标记（和用户实际主题一致）。
            runCatching {
                // 前面的探针可能把「菜单」浮层留在页面上，截图前先关掉。
                onFx { host.view.engine.executeScript("window.hideShortcutMenu()") }
                onFx {
                    host.view.engine.executeScript(
                        "window.setTheme({background:'#1c1c1e',onBackground:'#ececec',onSurfaceVariant:'#8e8e93'," +
                            "accent:'#8fd14f',accentSoft:'rgba(143,209,79,.62)',codeBackground:'#2a2a2c',border:'#3a3a3c'})",
                    )
                }
                onFx {
                    host.view.engine.executeScript(
                        "window.setMarkdown(\"# 学习笔记\\n\\n第一段正文：光标停在正文里时，左边只留一个小圆点。" +
                            "\\n\\n参考：[Trellis 仓库](https://github.com/lsh-NJ/Trellis) 与直接粘贴的 https://example.com/doc 。" +
                            "\\n\\n## 二级标题\\n\\n第二段正文：这里放光标看标题语法。\")",
                    )
                }
                // 先把链接那块的光标挪开，单独截一张「链接渲染」。
                onFx { host.view.engine.executeScript(caretScript("第二段正文")) }
                Thread.sleep(600)
                saveScreenshot(host, File(outDir, "link-render.png"))
                onFx { host.view.engine.executeScript(caretScript("二级标题")) }
                Thread.sleep(600)
                saveScreenshot(host, File(outDir, "heading-syntax.png"))
                onFx { host.view.engine.executeScript(caretScript("第一段正文")) }
                Thread.sleep(600)
                saveScreenshot(host, File(outDir, "gutter-paragraph.png"))
            }
            // 滚动条截图：长文档滚到中间，确认是低调的胶囊而不是系统大滑条。
            runCatching {
                onFx { host.view.engine.executeScript("window.hideShortcutMenu()") }
                val longDoc = (1..40).joinToString("\n\n") { "第 " + it + " 段：滚动条样式检查。" }
                onFx { host.view.engine.executeScript("window.setMarkdown(${jsString(longDoc)})") }
                Thread.sleep(500)
                onFx { host.view.engine.executeScript("document.getElementById('editor').scrollTop = 300") }
                Thread.sleep(700)
                saveScreenshot(host, File(outDir, "scrollbar.png"))
            }
        } finally {
            runCatching {
                onFx {
                    host.view.engine.load(null)
                    host.stage.close()
                    Platform.exit()
                }
            }
        }

        writeJson(jsonFile, results)
        println("[web-editor-harness] 写出 ${results.size} 个夹具 → ${jsonFile.absolutePath}")
        if (pngFile.isFile) println("[web-editor-harness] 截图 → ${pngFile.absolutePath}")
        if (mismatches.isNotEmpty()) {
            error("全角归一结果与预期不一致：" + mismatches.joinToString())
        }
        for (name in listOf("concept", "heading", "bullet", "quote", "ordered", "aiInline", "aiBlock", "code")) {
            check(symbolFlags.contains(name + "=1")) {
                "全角输入规则没生效：" + name + "（" + symbolFlags + "）"
            }
        }
        for (name in listOf(
            "enterNewParagraph",
            "enterSingleHeading",
            "backspaceDemotes",
            "backspaceKeepsText",
            "enterMidSplits",
            "syntaxInHeading",
            "syntaxLeavesWithCaret",
            "gutterDotForParagraph",
            "gutterHiddenInHeading",
        )) {
            check(headingJson.contains(name + "=1")) {
                "标题/正文交互没生效：" + name + "（" + headingJson + "）"
            }
        }
        check(headingBlurJson.contains("\"syntax\":0") && headingBlurJson.contains("\"gutter\":0")) {
            "焦点离开编辑器后 # / gutter 没消失：" + headingBlurJson
        }
        for (name in listOf(
            "linkRenders",
            "linkIcon",
            "linkBlue",
            "clickOpens",
            "altClickIgnored",
            "caretClickEdits",
            "mailtoOpens",
            "unsafeIgnored",
        )) {
            check(linkJson.contains(name + "=1")) {
                "链接没有按预期工作：" + name + "（" + linkJson + "）"
            }
        }
        return Report(results.size, jsonFile, pngFile)
    }

    /** 把光标放到「包含 [text] 的文本块」末尾，并强制重算一次 decorations（截图用）。 */
    private fun caretScript(text: String): String =
        "(function(){ const e = window.__trellisEditor; let target = null; " +
            "e.state.doc.descendants((node,pos)=>{ if (target == null && node.isTextblock && " +
            "node.textContent.indexOf(" + jsString(text) + ") >= 0) target = pos + 1 + node.content.size; return true }); " +
            "if (target != null) { e.commands.focus(); e.commands.setTextSelection(target); e.view.dispatch(e.state.tr) } " +
            "return target != null })()"

    // ------------------------------------------------------------------ 环境/夹具

    private class Host(val stage: Stage, val view: WebView)

    /** 在 FX 线程建 WebView + 显示 Stage，再 loadContent；与宿主 MarkdownWebHost 的启动顺序一致。 */
    private fun bootPage(): Host = onFx {
        Platform.setImplicitExit(false)
        val view = WebView().apply {
            prefWidth = VIEW_WIDTH
            prefHeight = VIEW_HEIGHT
            isContextMenuEnabled = false
        }
        val stage = Stage().apply {
            title = "Trellis WebView Editor Harness"
            scene = Scene(view, VIEW_WIDTH, VIEW_HEIGHT)
            // 必须真的 show：不显示的 Scene 不会持续渲染，snapshot 会拿到空白。
            show()
        }
        Host(stage, view).also { it.view.engine.loadContent(editorPage()) }
    }

    /** 与 MarkdownWebEditor.editorPage() 相同的拼页方式：HTML + 内联 katex.css + editor.js。 */
    private fun editorPage(): String {
        val html = readResource("/editor/index.html")
        val script = readResource("/editor/editor.js")
        val katex = runCatching { readResource("/editor/katex.css") }.getOrNull()
        val withStyle = if (katex.isNullOrBlank()) html else html.replace("</head>", "<style>$katex</style></head>")
        return withStyle.replace("</body>", "<script>$script</script></body>")
    }

    private fun readResource(path: String): String =
        WebEditorHarness::class.java.getResourceAsStream(path)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("缺少编辑器资源：$path")

    private fun loadFixtures(): List<Fixture> {
        val dir = fixtureDir()
        return (dir.listFiles { f -> f.isFile && f.name.endsWith(".md") && !f.name.endsWith(".expected.md") } ?: emptyArray())
            .sortedBy { it.name }
            .map { file ->
                val expected = dir.resolve(file.nameWithoutExtension + ".expected.md")
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                Fixture(file.nameWithoutExtension, file.readText(Charsets.UTF_8), expected)
            }
    }

    private fun fixtureDir(): File {
        val resource = WebEditorHarness::class.java.getResource(FIXTURE_RESOURCE_DIR)
        if (resource != null) {
            runCatching {
                val dir = File(resource.toURI())
                if (dir.isDirectory) return dir
            }
        }
        // 兜底：Gradle 测试的工作目录是模块目录（app/ui），直接读源目录。
        val workingDir = File(System.getProperty("user.dir")).absoluteFile
        val candidates = listOf(
            File(workingDir, "src/jvmTest/resources/web-editor/fixtures"),
            File(workingDir, "app/ui/src/jvmTest/resources/web-editor/fixtures"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到夹具目录（$FIXTURE_RESOURCE_DIR）：$workingDir")
    }

    /** 输出到模块构建目录：app/ui/build/web-editor/。 */
    private fun outputDir(): File {
        val workingDir = File(System.getProperty("user.dir")).absoluteFile
        for (dir in listOf(workingDir) + generateSequence(workingDir.parentFile) { it.parentFile }.take(4)) {
            if (File(dir, "build.gradle.kts").isFile) return File(dir, "build/web-editor")
        }
        return File(workingDir, "build/web-editor")
    }

    // ------------------------------------------------------------------ 页面交互

    private fun awaitEditorReady(host: Host, timeoutMillis: Long = PAGE_READY_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val ready = runCatching {
                onFx { host.view.engine.executeScript("String(window.editorReady)") as? String }
            }.getOrNull()
            if (ready == "true") return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** 连续两次采样相同即认为稳定；超时返回 null。Tiptap 的 setContent 是同步的，通常一次就稳。 */
    private fun awaitStableMarkdown(host: Host, timeoutMillis: Long = MARKDOWN_STABLE_TIMEOUT_MS): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var previous: String? = null
        while (System.currentTimeMillis() < deadline) {
            val sample = runCatching {
                onFx { host.view.engine.executeScript("window.getMarkdown()") as? String }
            }.getOrNull()
            if (sample != null && sample == previous) return sample
            previous = sample
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return previous
    }

    private fun saveScreenshot(host: Host, file: File) {
        onFx {
            val image = host.view.snapshot(null, null)
            file.parentFile?.mkdirs()
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file)
        }
    }

    /** 在 FX 线程上执行并等结果；FX 线程内调用则直接跑（避免自等死锁）。 */
    private fun <T> onFx(block: () -> T): T {
        if (Platform.isFxApplicationThread()) return block()
        val future = CompletableFuture<T>()
        Platform.runLater {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return try {
            future.get(120, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    // ------------------------------------------------------------------ 落盘

    private fun summaryLine(name: String, input: String, markdown: String): String {
        val firstLine = markdown.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val head = if (firstLine.length > 60) firstLine.take(60) + "…" else firstLine
        val changed = if (input == markdown) "原样" else "往返有差异"
        return "[web-editor-harness] $name | 输入 ${input.length} 字符 → 输出 ${markdown.length} 字符（$changed） | 首行：${head.ifBlank { "（空文档）" }}"
    }

    private fun writeJson(file: File, results: LinkedHashMap<String, FixtureResult>) {
        val sb = StringBuilder()
        sb.append("{\n")
        var first = true
        for ((name, result) in results) {
            if (!first) sb.append(",\n")
            first = false
            sb.append("  ").append(jsonString(name)).append(": {\n")
            sb.append("    \"input\": ").append(jsonString(result.input)).append(",\n")
            sb.append("    \"markdown\": ").append(jsonString(result.markdown)).append(",\n")
            sb.append("    \"html\": ").append(jsonString(result.html)).append("\n")
            sb.append("  }")
        }
        sb.append("\n}\n")
        file.writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun jsonString(value: String): String = buildString(value.length + 16) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    /** 生成可安全塞进 executeScript 的 JS 字符串字面量（与 MarkdownWebEditor.jsString 同规则）。 */
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
}
