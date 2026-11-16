# 旧 WebView 编辑器 bundle 行为规范（逆向）

> 面向「重写实现的人」：把仓库里上一版 editor.js（385 KB 构建产物，仓库无源码）从字节码 + 黑盒运行反推成回归基线。
>
> 文档基线不是当前工作区文件：本文写作期间（2026-11-14 14:33）app/ui/src/jvmMain/resources/editor/editor.js 已被 Phase 0 重写产物替换（507,944 B）。本文只描述 git HEAD 的旧版：
>
> - git show HEAD:app/ui/src/jvmMain/resources/editor/editor.js → 385,480 B，md5 a0b5f6c59c7c89a7daa733e3c7bbc3bb
> - git show HEAD:app/ui/src/jvmMain/resources/editor/index.html → 5,184 B，md5 f470f7e10be8906180d413bcfecec2a3
> - 恢复基线：
>   ```bash
>   mkdir -p /tmp/trellis-editor-baseline
>   cd /Users/break_m/Trellis
>   git show HEAD:app/ui/src/jvmMain/resources/editor/editor.js  > /tmp/trellis-editor-baseline/editor.js
>   git show HEAD:app/ui/src/jvmMain/resources/editor/index.html > /tmp/trellis-editor-baseline/index.html
>   md5 /tmp/trellis-editor-baseline/editor.js   # 必须 a0b5f6c59c7c89a7daa733e3c7bbc3bb
>   ```
>
> 证据标记：【读码】= 直接读旧 bundle 字节码（附字节偏移 / 原文片段）；【harness】= 旧 index.html+editor.js 在 Chrome headless 实跑；【推断】= 由前两类推出；【未知】= 证据不足，建议 harness 黑盒验证，不要当事实。
>
> 所有 harness 结果是 Chrome（Blink），JavaFX 21 WebView 是 WebKit，键盘/焦点/IME 可能有差异，见 §11。

---

## 0. 一页结论（重写者先看这 10 条）

1. 这一版是 Tiptap/ProseMirror + marked + turndown 的最小组合：schema 只有 StarterKit（heading/paragraph/列表/引用/代码块/分割线/hardBreak/历史）+ link + 两个自定义 mark（aiSpan=span.ai、conceptSpan=span.concept）+ placeholder + trellisKeys。没有表格、公式、任务清单、图片、下划线、代码高亮、:::ai 块、。。。槽位。
2. markdown → doc 是 marked.parse(md) 得到 HTML 字符串，再对整串 HTML 做两次正则替换（{{…}}、[[…]]），最后 setContent(html, false)。因为替换发生在 HTML 字符串上，代码块/行内代码里的 {{…}}/[[…]] 也会被替换，导致括号/花括号丢失（实测 `[[x]]` → <code>x</code>）。
3. [[原文 => 概念名]] 这一版是坏的：marked 把 > 转义成 &gt;，按 => 切分的正则匹配不到，结果整串「原文 => 概念名」都变成概念文本，data-target 为空。只有 [[原文 → 概念名]]（U+2192）能正确写 data-target；而序列化又统一写 =>，所以 → 版本存一次再读就退化成普通概念文本。
4. doc → markdown 是 turndown(editor.getHTML())（turndown 默认 codeBlockStyle: "indented"！）+ 三条自定义规则（aiSpan→{{}}、conceptSpan→[[…]]）再过一个 Vf 规范化函数。代码围栏会变成 4 空格缩进块并丢语言；更糟的是 Vf 最后对整篇 .trim()，文档第一块是代码块时缩进被削掉，代码块直接变段落。
5. Vf 的规范化：列表标记后 \s{2,}→单空格、删同类型相邻列表项之间空行、逐行删行尾空白、\n{3,}→\n\n、整篇 trim 后强制补一个 \n。所以空文档序列化是 "\n"，任何文档都被改写为恰好一个结尾换行。
6. window.setMarkdown(...) 走 setContent(html, false)：不触发 update 事件（false = emitUpdate），因此不回推 Kotlin；但会进入 undo 历史（实测撤销能退回初始内容「初始化中…」）——切换笔记后 Cmd+Z 有把上一篇/初始内容写进当前笔记的风险【harness+推断，需 JavaFX 验证】。
7. 每次用户编辑（docChanged）都会全量 turndown(getHTML()) + Vf 一遍，通过 trellisEditorBridge.onUpdate(markdown) 回推 Kotlin，没有防抖。Kotlin 用 applied 缓存避免回声。由于第 4/5 条的规范化，用户在 WebView 里敲一个字，整篇笔记的 Markdown 格式就会被重写（表格被拍平、代码围栏变缩进、硬换行丢失等，见 §4.4）。
8. gutter 用 node decoration 给光标所在顶层块挂 class="gutter-current" data-marker="…"：¶ / H1–H6 / • / 1. / ▍ / { } / —。列表 marker 挂在整只 <ul>/<ol> 上（不是当前 <li>）；节点装饰不参与 getHTML()。
9. [[ 补全：候选来自 window.setConcepts([...])，按「光标前 40 字符内以 [[ + ≤20 字符结尾」触发，子串（includes）大小写不敏感过滤、最多 6 条；点选/Enter/Tab 用 _f(name) 把 [[query 替换成纯概念名文本 + conceptSpan mark（不插方括号），序列化回 [[名字]]。↑↓ 高亮不影响 Enter/Tab 的认领结果（认领时按 query 重新取完全匹配→前缀匹配→第一条，不看高亮）。
10. 页面级快捷键 Cmd/Ctrl+S/N// 由 window 上 capture 的 keydown 处理：preventDefault() + notifyBridge('onShortcut','save'|'newNote'|'menu')；macOS 上 event.key 为空串时用 keyCode 兜底（65–90→字母、48–57→数字、191/111→/）。Esc 不会失焦：末尾那段 v.on("keydown", … onBlur) 是死代码（bundle 里没有任何 emit("keydown")），与 docs/knowledge-base-draft.md §13 的「Esc 在 JS 里主动 notifyBridge('onBlur')」不符【读码+harness】。

---

## 1. 宿主契约：Kotlin ↔ JS

### 1.1 页面怎么加载【读码】

MarkdownWebEditor.kt 的 editorPage() 把两个资源拼成一页，用 WebView.engine.loadContent(...) 直接灌进内存（不走临时文件）：

```kotlin
private fun editorPage(): String {
    val html = readResource("/editor/index.html")
    val script = readResource("/editor/editor.js")
    return html.replace("</body>", "<script>$script</script></body>")
}
```

页面 DOM 只有 <div id="editor"></div>，editor.js 是 IIFE，加载即执行、同步建 Editor。

### 1.2 启动时序【读码+推断】

1. loadContent → 页面脚本同步执行：建 Editor → 注册所有 window.* API → 最后 window.editorReady = true。
2. loadWorker.state == SUCCEEDED → Kotlin installBridge()：window.setMember("trellisEditorBridge", MarkdownEditorBridge())，然后按 theme → concepts → markdown → reveal 的顺序补执行挂起的 pending。
3. 之后 Compose 状态变化才直接 executeScript 调用 JS。
4. 初始正文硬编码 content:"<p>初始化中…</p>"【读码 editor.js@376732】；首次 setMarkdown 之前用户能看到「初始化中…」。

### 1.3 JS → Kotlin（桥对象 window.trellisEditorBridge）

桥是 JavaFX 注入的 Java 对象，不是 JS 定义的；bundle 里只有 2 处引用（editor.js@379426、@379454），统一走【读码 editor.js@379398】：

```js
function dt(n,e){try{window.trellisEditorBridge&&window.trellisEditorBridge[n](e)}catch{}}
```

| 桥方法 | JS 何时调用 | 参数 | Kotlin 侧行为【读码 MarkdownWebEditor.kt】 |
|---|---|---|---|
| onUpdate(markdown) | editor.on("update")（docChanged 且非 preventUpdate） | 全量 Markdown（Vf(turndown(getHTML()))) | if (markdown != applied) { applied = markdown; onBodyChange(markdown) }，切到 Swing 线程 |
| onBlur() | editor.on("blur") | 无 | onEditingFinished（先落盘，再可能触发 AI 整理） |
| onFocus() | editor.on("focus") | 无 | 空实现（fun onFocus() = Unit） |
| onShortcut(name) | 页面级快捷键 capture | "save" / "newNote" / "menu" | KnowledgeScreen when 分发：保存 / 新建笔记 / 开关菜单 |

注意：MarkdownEditorBridge 方法必须 public、宿主必须一直握着对象，否则 JavaFX 反射调用会静默失败（源码注释已写明）。

另一条失焦路径：Compose 点击不会让 WebView DOM 失焦，所以 MarkdownWebHost 在 AWT 层挂全局 MOUSE_PRESSED 监听：按下点不在 JFXPanel 里 → onEditingFinished。它和 JS onBlur 是两条并行信号（可能重复触发）。

### 1.4 Kotlin → JS（window 上的 API）

旧 bundle 的 window.* 赋值只有这些（无 readOnly / setPlaceholder / setKeymap / destroy）【读码】：

| API | 签名 | 返回 | 行为 / 调用时机 |
|---|---|---|---|
| setMarkdown(md) | string | true | setContent(Ak(md), false)：markdown→HTML→PM，不 emit update（@377697） |
| getMarkdown() | — | string | Vf(turndown(getHTML()))，永远以单个 \n 结尾（@379246） |
| getHTML() | — | string | editor.getHTML()，ProseMirror DOM 序列化，不含 gutter 装饰（@379300） |
| focusEditor() | — | truthy | editor.commands.focus("end")：光标到文末并聚焦（@379331） |
| editorReady | boolean | — | 脚本末尾置 true（@379376）；Kotlin 不读它，靠 loadWorker 判断 |
| setTheme(obj) | 7 字段对象 | true | 写 7 个 CSS 变量（@385098） |
| setConcepts(names) | string[] | true | 存 window.__trellisConcepts（缺省 []），概念补全候选（@380169） |
| revealRange(start,end) | int,int | true | 把「文档纯文本偏移」转 PM 位置并选中滚动（@377756 起） |
| __trellisHandleKey(event) | KeyboardEvent 或 {key,preventDefault()} | boolean | 菜单/补全键盘路由，true=已消费（@384604） |

Kotlin 不调用 getMarkdown/getHTML；正文只靠 onUpdate 推送。Kotlin 的 setMarkdown 用 runCatching 吞异常。

---

## 2. ProseMirror schema（权威清单）

由 bundle 直接读出（不是按 Tiptap 文档猜）【读码】。

### 2.1 node 类型（全量）

doc, paragraph, text, heading, blockquote, bulletList, orderedList, listItem, codeBlock, horizontalRule, hardBreak（StarterKit @295808；addExtensions 里没有 link/underline/listKeymap/trailingNode）。

- heading：levels [1..6]，rendered:false 的 level attr，渲染 <h1>..<h6>（@279932）。
- codeBlock：content:"text*"、marks:""、code:true、defining:true、languageClassPrefix:"language-"、exitOnTripleEnter:true、exitOnArrowDown:true；渲染 <pre class="code-block"><code class="language-xx">…</code></pre>（class 来自 app 的 configure；节点 @269167）。language=null 时 <code> 无 class。
- orderedList 有 start（渲染 <ol start="3">，实测）；bulletList 渲染 <ul><li><p>…（每 li 里套 paragraph）。
- horizontalRule 渲染裸 <hr>；hardBreak 渲染 <br>。
- 没有：table/tableRow/tableCell、math、taskList/taskItem、image、underline、textAlign、color、subscript/superscript、codeBlockLowlight（所以代码高亮/表格/公式在旧 WebView 编辑器里都不存在）。

### 2.2 mark 类型（全量）

bold(<strong>)、italic(<em>)、strike(<s>，parse 认 s/del/strike)、code(<code>)、link(<a>)、自定义 aiSpan/conceptSpan。没有 underline/highlight。

自定义 mark【读码 editor.js@375234–@375675】：

```js
// @375234
var Mk=ae.create({name:"aiSpan",
  parseHTML(){return[{tag:"span.ai"}]},
  renderHTML({HTMLAttributes:n}){return["span",P(n,{class:"ai"}),0]}}),
// @375386
Tk=ae.create({name:"conceptSpan",
  addAttributes(){return{target:{default:null,
    parseHTML:n=>n.getAttribute("data-target"),
    renderHTML:n=>n.target?{"data-target":n.target}:{}}}},
  parseHTML(){return[{tag:"span.concept"}]},
  renderHTML({HTMLAttributes:n}){return["span",P(n,{class:"concept"}),0]}})
```

DOM 特征（可被 harness 断言）：

| 语法 | getHTML 形态 |
|---|---|
| {{内容}} | <span class="ai">内容</span>（span 内是普通文本/其它 mark，可嵌套如 <strong><span class="ai">粗</span></strong>） |
| [[名字]] | <span class="concept">名字</span> |
| [[原文 → 概念名]]（只有 → 有效） | <span data-target="概念名" class="concept">原文</span> |
| 链接 | <a target="_blank" rel="noopener noreferrer nofollow" href="…">label</a> |

index.html 的 .ai::before/::after（大引号）和 .concept 下划线都是 CSS 伪元素/样式，不写进 DOM 文本，也不进 getHTML/getMarkdown【读码 index.html@47–52】。

### 2.3 其它扩展

- link（@319667）：priority:1000、keepOnSplit:false、exitable:true；app 只配 openOnClick:false，即 autolink:true、linkOnPaste:true、defaultProtocol:"http"、默认 protocols:[]（全放行）；attributes href/target/rel/class。
- placeholder（@322803）：emptyEditorClass:"is-editor-empty"、emptyNodeClass:"is-empty"、placeholder:"开始写吧。"、showOnlyWhenEditable:true、showOnlyCurrent:true、includeChildren:false；用 node decoration 给空节点挂 class + data-placeholder，CSS 只在 .ProseMirror p.is-editor-empty:first-child::before 显示（index.html@125）。
- history（@290088）：depth:100、newGroupDelay:500；快捷键 Mod-z / Shift-Mod-z / Mod-y。
- trellisKeys（@375675）：纯键盘扩展，把 Enter/Tab/↑/↓/Esc 转给 window.__trellisHandleKey，并注册格式快捷键（§6.4）。
- Tiptap 具体版本【未知】：bundle 无版本横幅（esbuild 去注释）。StarterKit 里还用 history 且没有 link/underline/listKeymap/trailingNode，更像 Tiptap 2.x；但 Editor 选项里出现 enableContentCheck/emitContentError 这类较新特性，故只能写「2.x，具体版本未知」；重写按本表行为来，不要按版本号猜。

---

## 3. markdown → doc（解析链）

### 3.1 实际实现【读码 editor.js@376873】

```js
function Ak(n){
  let e = $.parse(n,{async:!1});                                  // marked
  e = e.replace(/\{\{([^\n{}]*?)\}\}/g,'<span class="ai">$1</span>');
  e = e.replace(/\[\[([^\[\]\n]*?)\]\]/g,(t,r)=>{
    let i=/^([\s\S]*?)\s*(?:=>|\u2192)\s*([\s\S]+)$/.exec(r),
        s=i?i[1].trim():r, o=i?i[2].trim():"";
    return '<span class="concept"'+(o?' data-target="'+o.replace(/&/g,"&amp;").replace(/"/g,"&quot;")+'"':"")+">"+s+"</span>"
  });
  return e
}
window.setMarkdown = n => (v.commands.setContent(Ak(n),!1), !0);
```

- $ = marked（function $(n,e){return Pt.parse(n,e)} @361829），默认选项：{async:false, breaks:false, gfm:true, pedantic:false, hooks:null, renderer:null, silent:false, tokenizer:null, walkTokens:null}（@323729）。
- 顺序：先 marked 出 HTML 字符串 → 在 HTML 上正则替换 {{…}} → 再替换 [[…]] → 交给 setContent 用 ProseMirror 的 HTML 解析器建 doc。
- 正则都不允许换行：{{a\nb}}、[[a\nb]] 不匹配，按字面文本处理。
- [[…]] 的 target 切分同时认 => 和 →（\u2192）。

### 3.2 三个必须知道的后果（都有 harness 证据）

1. => 形式解析失败：marked 把 > 转义成 &gt;，正则要匹配裸 =>，匹配不到 → 输入 [[原文 => 概念名]]，getHTML = <p><span class="concept">原文 =&gt; 概念名</span></p>（没有 data-target），getMarkdown 仍是 [[原文 => 概念名]]。只有 [[原文 → 概念名]] 得到 <span data-target="概念名" class="concept">原文</span>。而序列化统一写 =>（§4.3），所以 → 版本存盘再打开后 target 就丢了。
2. 替换在 HTML 串上做，代码内容也被替换：`[[x]]` → marked 先出 <code>[[x]]</code> → 正则替换成 <code><span class="concept">x</span></code> → ProseMirror 的 code mark 不接收 conceptSpan → 最终 <code>x</code>，方括号被吃掉（{{…}} 同理）。围栏代码块也一样：[[y]] 在 <pre><code> 里被替换成 span，PM 只留文本 y。
3. 没有 schema 的 HTML 会被拍平/丢弃：表格 → 只剩文本 ab12（一个段落）；<div>hi</div> → <p>hi</p>；![alt](url) → 空段落（图片节点不存在）；- [ ] todo → 普通列表项，复选框文本消失；:::ai / $$…$$ 原样当普通文本。

---

## 4. doc → markdown（序列化链）

### 4.1 实现【读码 editor.js@377252 起】

```js
var er = new Yn({headingStyle:"atx", bulletListMarker:"-", emDelimiter:"*", strongDelimiter:"**"});
er.use(Ff);                                   // turndown-plugin-gfm：highlightedCodeBlock/strikethrough/table*/taskListItems
er.addRule("aiSpan",{filter:n=>n.nodeName==="SPAN"&&n.classList&&n.classList.contains("ai"),
                     replacement:n=>"{{"+n+"}}"});
er.addRule("conceptSpan",{filter:n=>n.nodeName==="SPAN"&&n.classList&&n.classList.contains("concept"),
                     replacement:(n,e)=>{let t=e.getAttribute&&e.getAttribute("data-target");
                       return t?"[["+n+" => "+t+"]]":"[["+n+"]]"}});
window.getMarkdown = () => Vf(er.turndown(v.getHTML()));
v.on("update",()=>{dt("onUpdate",Vf(er.turndown(v.getHTML())))});
```

- Yn = TurndownService（@371435），默认选项关键项：codeBlockStyle:"indented"（没改）、fence:"```"、br:"  "、linkStyle:"inlined"、hr:"* * *"、headingStyle:"setext"（被覆盖为 atx）、bulletListMarker:"*"（覆盖为 -）、emDelimiter:"_"（覆盖为 *）。
- 转义用 turndown 默认 escape 规则表 Zy（@363635），会转义 \ * - + = # ` ~~~ [ ] > _ 以及行首 1. 。
- gfm 的 strikethrough 规则输出单波浪线：replacement: n => "~"+n+"~"（@373436 附近）；table* 规则在本 schema 下用不到。

### 4.2 Vf 规范化（@378719，完整逻辑）

```js
function Vf(n){
  let e = n.replace(/^([ \t]*)([-*+])\s{2,}/gm,"$1$2 ")
           .replace(/^([ \t]*)(\d+[.)])\s{2,}/gm,"$1$2 "),
      t = e.split("\n"), r = s => { let o=/^[ \t]*(?:([-*+])|(\d+)[.)])\s/.exec(s);
                                    return o ? (o[1]?"bullet":"ordered") : "" }, i=[];
  for (let s=0;s<t.length;s++){
    let o=t[s];
    if(o.trim()===""){
      let l=i.length?i[i.length-1]:"", a=s+1;
      while(a<t.length&&t[a].trim()==="")a++;
      let c=a<t.length?t[a]:"", u=r(l), f=r(c);
      if(u&&u===f) continue;            // 同类型列表项之间的空行 → 删掉
      i.push(""); continue
    }
    i.push(o.replace(/[ \t]+$/,""))    // 行尾空白（含 hard break 的两个空格）→ 删掉
  }
  return i.join("\n").replace(/\n{3,}/g,"\n\n").trim()+"\n"
}
```

逐条影响（每条都能被 harness 断言）：

1. -   item（turndown 列表输出是 marker + 3 空格）→ - item；1.  item → 1. item。只处理行首：引用里的列表 > -   a 不改（实测输出 > -   a）。
2. 松散列表（项之间有空行）压成紧凑列表。
3. 行尾空白全删 → hardBreak 的两个空格被删，硬换行退化为普通换行；再解析时 breaks:false → 变成空格（两轮实测 a  \nb → pass1 a\nb → pass2 a b）。
4. 连续 3+ 换行压成 2。
5. 整篇 .trim()：开头是缩进代码块时第一行的 4 个空格被削 → 代码块失效（实测 "    code first" → getMarkdown "code first\n"）。
6. 末尾强制 + "\n"：空文档 → "\n"，任何文档结尾恰好 1 个换行。

### 4.3 语法 → markdown 映射总表

| doc 内容 | getMarkdown 输出 | 备注 |
|---|---|---|
| paragraph | 文本 + \n\n | 段间 1 个空行 |
| heading level n | #×n + 空格 + 文本 | 空标题 → ""（标题丢失，实测 "# " → "\n"；getHTML <h1></h1>） |
| bulletList | - item（嵌套 4 空格） | turndown marker 占 4 字符 → 子列表缩进 4 空格 |
| orderedList | 1. item；遵守 start | 实测 3. c/4. d 往返保持；0. 也保持 |
| blockquote | > 前缀 | 引用里的列表：> -   a\n>\n> -   b\n>（不规整但两轮稳定） |
| codeBlock | 4 空格缩进代码块，语言丢失 | 见 §8 风险 |
| horizontalRule | * * * | --- 输入 → getMarkdown * * * |
| hardBreak | \n（两个空格被 Vf 删） | 丢硬换行 |
| bold/italic/code/strike | **x** / *x* / `x` / ~x~ | strike 是单波浪线 |
| link | [label](href)（有 title 时补 "title"） | DOM 见 §2.2 |
| aiSpan | {{内容}} | 可嵌套，如 **{{粗}}** |
| conceptSpan 无 target | [[名字]] | 概念补全插入的就是这种 |
| conceptSpan 有 target | [[原文 => target]] | target 只可能来自 → 输入 |
| 普通文本 | turndown escape | 字面 [[ 会被转义成 \[\[（实测） |

### 4.4 harness 实测：输入 → getHTML → getMarkdown（A 组回归基线）

| # | 输入 setMarkdown | 期望 getHTML | 期望 getMarkdown |
|---|---|---|---|
| A1 | ""（空串） | <p></p> | "\n" |
| A2 | # 标题 | <h1>标题</h1> | "# 标题\n" |
| A3 | 段落一\n\n段落二 | <p>段落一</p><p>段落二</p> | "段落一\n\n段落二\n" |
| A4 | - a\n- b | <ul><li><p>a</p></li><li><p>b</p></li></ul> | "- a\n- b\n" |
| A5 | 3. c\n4. d | <ol start="3"><li><p>c</p></li><li><p>d</p></li></ol> | "3. c\n4. d\n" |
| A6 | > 引用 | <blockquote><p>引用</p></blockquote> | "> 引用\n" |
| A7 | ```js\nconst x = 1;\n``` | <pre class="code-block"><code class="language-js">const x = 1;\n</code></pre> | "const x = 1;\n"（代码块+语言丢失！） |
| A8 | ```\nplain\n\nblank\n``` | <pre class="code-block"><code>plain\n\nblank\n</code></pre> | "plain\n\n    blank\n" |
| A9 | **粗** *斜* `码` ~~删~~ | <p><strong>粗</strong> <em>斜</em> <code>码</code> <s>删</s></p> | "**粗** *斜* `码` ~删~\n" |
| A10 | [链接](https://example.com) | <p><a target="_blank" rel="noopener noreferrer nofollow" href="https://example.com">链接</a></p> | "[链接](https://example.com)\n" |
| A11 | a  \nb（hard break） | <p>a<br>b</p> | "a\nb\n"（两个空格被删） |
| A12 | a\nb（soft break） | <p>a b</p>（换行变空格） | "a b\n" |
| A13 | [[RAG]] | <p><span class="concept">RAG</span></p> | "[[RAG]]\n" |
| A14 | [[原文 => 概念名]] | <p><span class="concept">原文 =&gt; 概念名</span></p>（无 data-target） | "[[原文 => 概念名]]\n" |
| A15 | [[原文 → 概念名]] | <p><span data-target="概念名" class="concept">原文</span></p> | "[[原文 => 概念名]]\n" |
| A16 | 前{{AI 补全}}后 | <p>前<span class="ai">AI 补全</span>后</p> | "前{{AI 补全}}后\n" |
| A17 | :::ai\nblock\n::: | <p>:::ai block :::</p> | ":::ai block :::\n"（当普通文本） |
| A18 | $$x^2$$ | <p>$$x^2$$</p> | "$$x^2$$\n" |
| A19 | 表格 a/b/1/2 | <p>ab12</p> | "ab12\n"（表格被拍平） |
| A20 | - [ ] todo\n- [x] done | <ul><li><p>todo</p></li><li><p>done</p></li></ul> | "- todo\n- done\n"（复选框丢失） |
| A21 | `[[x]]` 与 ```\n[[y]]\n``` | <p><code>x</code> 与</p><pre class="code-block"><code>y</code></pre> | "`x` 与\n\n    y\n"（方括号被吃掉） |
| A22 | ![alt](https://x/y.png) | <p></p> | "\n"（图片节点不存在→整段丢） |
| A23 | --- | <hr> | "* * *\n" |
| A24 | - item1\n\n- item2 | 紧凑 <ul> | "- item1\n- item2\n"（空行被删） |
| A25 | - a\n  - b | 嵌套 <ul> | "- a\n    - b\n"（4 空格缩进） |
| A26 | "    code first"（首块=代码） | <pre class="code-block"><code>code first\n</code></pre> | "code first\n"（代码块被 trim 成段落） |
| A27 | 两轮 ~~删~~ | pass1 <s>删</s>，pass2 仍 <s>删</s> | 均为 "~删~\n"（marked 接受单波浪线） |
| A28 | 两轮 a  \nb | pass1 <p>a<br>b</p>，pass2 <p>a b</p> | pass1 "a\nb\n" → pass2 "a b\n" |
| A29 | 两轮 [[原文 → 概念名]] | pass1 有 data-target；pass2 无（整串概念文本） | 两轮都 "[[原文 => 概念名]]\n" |
| A30 | 两轮 中间代码块 文字 + ```js…``` | pass1 有 language-js；pass2 无（变 indented code） | 两轮都 "文字\n\n    code\n\n结尾\n" |

其它 B 组（读码依据，建议 JavaFX 复核）：B1 setMarkdown 不触发 update（harness：updates=0）；B2 用户输入/undo 后 onUpdate 是全量 md 且末尾恰好一个 \n；B3 getHTML 不含 gutter-current/is-editor-empty；B4 focusEditor 光标到文末、.ProseMirror 有 tabindex=0；B5 gutter marker 集合与挂载元素（见 §5）；B6 空文档首个 p 有 is-empty is-editor-empty + data-placeholder="开始写吧。"；B7 setTheme 后 7 个变量全部生效；B8 setConcepts 非数组→[]、菜单最多 6 条、hint 固定「概念」；B9 概念认领后 DOM 是 span.concept、md 是 [[名]]；B10 斜杠菜单 8 项、代码块里不弹、空行「标题 1」→ <h1></h1> + md "\n"；B11 Cmd/Ctrl+S/N// capture + onShortcut；B12 Mod-Enter 失焦不插 <br>、Shift-Enter 插 <br>；B13 Esc 不失焦；B14 macOS Cmd+Shift+字母依赖 keyCode 兜底。

---

## 5. gutter（段落标记）

【读码 editor.js@379615–@379790】：

```js
var vk = n => { let e=n.type.name;
  return e==="heading" ? "H"+(n.attrs.level||1)
       : e==="bulletList" ? "•" : e==="orderedList" ? "1."
       : e==="blockquote" ? "▍" : e==="codeBlock" ? "{ }"
       : e==="horizontalRule" ? "—" : "¶" },
Rk = new Plugin({key:new PluginKey("gutter"), props:{decorations(n){
  let {$from:e}=n.selection, t=e.depth;
  for(;t>1;) t-=1;                       // 一律回到顶层块
  if(t<1) return null;
  let r=e.node(t);
  if(!r||r.type.name==="doc") return null;
  let i=e.before(t), s=i+r.nodeSize;
  return DecorationSet.create(n.doc,[Decoration.node(i,s,{class:"gutter-current","data-marker":vk(r)})])
}}});
v.registerPlugin(Rk);
```

- 只给光标所在顶层块挂 decoration（class="gutter-current" + data-marker），CSS ::before 画在 left:-36px（index.html@57–74）。
- 列表 marker 挂在整只 <ul>/<ol> 上，不是当前 <li>；引用挂 blockquote；代码块挂 <pre class="code-block">（实测 class = "code-block gutter-current"）。
- 节点装饰不进 getHTML（实测无 gutter class）。
- harness 实测 marker：H1（h1）、¶（p）、▍（blockquote）、•（ul）、1.（ol）、{ }（pre）。
- 没有 AI / ∑ / ▦ / ☰ 这些 marker（那是 Compose 编辑器词汇）。
- —（horizontalRule）【未知】：hr 是叶子，普通文本选区落不上去，需要 gap cursor 才可能触发，未复现。

---

## 6. 斜杠菜单 / 概念补全 / 键盘

### 6.1 斜杠菜单（/）

命令表【读码 editor.js@382048，共 8 条】：

| label | hint | keywords（过滤，含拼音） | 动作 |
|---|---|---|---|
| 标题 1 | 一级标题 | h1 biaoti 标题 大标题 | toggleHeading(level:1) |
| 标题 2 | 二级标题 | h2 biaoti 标题 | level 2 |
| 标题 3 | 三级标题 | h3 biaoti 标题 | level 3 |
| 无序列表 | - 列表 | ul bullet list 列表 无序 | toggleBulletList |
| 有序列表 | 1. 列表 | ol ordered list 列表 有序 | toggleOrderedList |
| 引用 | > 引用 | quote blockquote 引用 | toggleBlockquote |
| 代码块 | ``` 代码块 | code 代码 代码块 | toggleCodeBlock |
| 分隔线 | --- | hr rule 分隔线 分割线 | setHorizontalRule |

触发规则（@383459 bl）：

- update/selectionUpdate 时算；当前祖先是 codeBlock 就直接关菜单（实测代码块里打 / 不弹）。
- 取「当前文本块起点 → 光标」文本（blockSeparator \n），正则 /(^|\s)\/([^\s\/]{0,20})$/：/ 前必须行首或空白，query ≤20 个非空白非 / 字符。
- 过滤 (label+" "+keywords).toLowerCase().includes(query.toLowerCase())；无结果关菜单。
- 菜单 DOM：div.slash-menu 挂 document.body，position:fixed，每项 div.slash-item[data-index]（span.slash-label + span.slash-hint），选中项 .selected；位置贴光标 coordsAtPos，top=bottom+6px。
- 执行 jf(cmd)（@383334）：关菜单 → 记住 / 位置 → deleteRange({from:slashPos,to:光标}) → cmd.run()。所以命令作用在删掉 /query 后的文本块；空行执行「标题 1」得到空 <h1>（实测 getHTML <h1></h1>，getMarkdown "\n"）。
- 鼠标：项上 mousedown（preventDefault）直接执行；点菜单外任意处关闭。

### 6.2 [[ 概念补全

状态【读码 @380169–@381700】：me(菜单元素)、Ze(候选)、ln(高亮索引)、Xn([[ 起点)；候选存 window.__trellisConcepts。

触发（kl，update/selectionUpdate 都跑）：

- textBetween(max(0,caret-40), caret, "\n", "\n")，正则 /\[\[([^\[\]\n]{0,20})$/（光标前正好 [[ + ≤20 个非括号非换行字符）。
- 过滤 concept.toLowerCase().includes(query.toLowerCase())，slice(0,6)；无候选关菜单。
- 菜单 DOM：div.concept-menu → div.concept-item[data-index]（span.concept-name + span.concept-hint 固定「概念」），选中 .selected。

认领（_f，@380404）：

```js
_f=n=>{ if(Xn<0)return;
  let e=Xn, t=v.state.selection.from, r=v.schema.marks.conceptSpan.create({}),
      i=v.state.tr.insertText(n,e,t);          // 用概念名替换 "[[query"
  i.addMark(e,e+n.length,r);                    // 给新文本加 conceptSpan mark（无 target）
  v.view.dispatch(i); Zn(); v.view.focus() }
```

- 插入的是纯文本概念名 + mark，不再有方括号；序列化时 conceptSpan 规则补回 [[名字]]。没有 =>/data-target。
- 点选（mousedown）和 Enter/Tab 都走 _f。
- Enter/Tab 的认领目标与 ↑↓ 高亮无关（Dk @381614）：重新解析 [[query，按完全匹配→startsWith→第一条选，不看 ln。harness 实测：3 个候选 ↓↓ 高亮第 3 条，按 Tab 插入仍是第 1 条（RAG）。
- ↑↓ 只改高亮（Ik）；Esc 关菜单且不消费文本（留下的 [[r 是字面文本，序列化为 \[\[r）。
- setConcepts([]) → 菜单永不出现；传非数组 → []。

### 6.3 菜单键盘路由 window.__trellisHandleKey

两条入口：

1. editorProps.handleKeyDown（ProseMirror 在 keymap 之前调用）——传真实 KeyboardEvent；
2. trellisKeys 扩展 addKeyboardShortcuts（Enter/Tab/↑/↓/Esc）——传伪事件 {key, preventDefault(){}}（@375675）。

逻辑（@384604）：

```js
if (slashMenu open) {
  ArrowDown/Up → 改高亮 + 重绘, true;
  Enter/Tab    → 执行当前高亮项, true;
  Escape       → 关菜单, true;
  // 其他键返回 false
}
if (key==="Enter" || key==="Tab") { if (conceptAutoClaim()) { preventDefault(); return true } }
if (conceptMenu open) {
  ArrowDown/Up → 改高亮, true;
  Escape       → 关菜单, true;
}
return false;
```

陷阱：Esc 在这条路径上只关菜单，不 blur；菜单没开时返回 false。

### 6.4 格式快捷键（keymap 实际注册表）

trellisKeys【读码 @375675】显式覆盖：Mod-b 粗体、Mod-i 斜体、Mod-Shift-x 删除线、Mod-Shift-c 行内代码、Mod-1/2/3 标题 1/2/3、Mod-0 正文、Mod-Shift-l 无序列表、Mod-Shift-o 有序列表、Mod-Shift-q 引用、Mod-Shift-r 代码块、Mod-Shift-t 分割线、Mod-Enter 失焦（dt("onBlur")+commands.blur()）、Enter/Tab/↑/↓/Esc→__trellisHandleKey。

StarterKit/link 自带（也会生效）：Mod-b/Mod-i、Mod-Shift-s(strike)、Mod-e(code)、Mod-Alt-c(codeBlock)、Mod-Alt-1..6(heading)、Mod-Alt-0(paragraph)、Mod-Shift-7/8(ol/ul)、Mod-Shift-b(blockquote)、Mod-z/Shift-Mod-z/Mod-y(undo/redo)、Shift-Enter(hardBreak)、Mod-Enter(hardBreak，但被 trellisKeys 的失焦覆盖：trellisKeys 最后加入、keymap 更靠前，实测 Mod-Enter 只失焦不插 <br>)。没有 Mod-k（link 无快捷键），没有 Tab 缩进逻辑（Tab 只被菜单/补全消费，否则交还浏览器）。

macOS keymap 兜底【读码 @190264】：Ac(event) 在 Mac && metaKey && shiftKey 时优先用事件 keyCode 查表（Rr/Ue）再回退 event.key，这就是「Cmd 组合键 key 为空串」的兜底；所以用合成事件测 Cmd+Shift+字母时给 keyCode 才准（Chrome headless 合成事件 keyCode=0 会漏测）。

### 6.5 页面级快捷键（Cmd/Ctrl+S/N//）

【读码 @378303】在 window 上注册 capture 阶段 keydown：

```js
window.addEventListener("keydown", n=>{
  if(!(n.metaKey||n.ctrlKey)) return;
  let t=(n.key||"").toLowerCase();
  if(!t){ let r=n.keyCode||0;
    if(r>=65&&r<=90) t=String.fromCharCode(r).toLowerCase();
    else if(r>=48&&r<=57) t=String.fromCharCode(r);
    else if(r===191||r===111) t="/" }
  if(t==="s"){ n.preventDefault(); dt("onShortcut","save") }
  else if(t==="n"){ n.preventDefault(); dt("onShortcut","newNote") }
  else if(t==="/"){ n.preventDefault(); dt("onShortcut","menu") }
}, true);
```

- 只要焦点在 WebView 页内（甚至 body），capture 都会先拿到；harness 实测 Cmd+S 被 preventDefault 且桥收到 "save"；普通字母不拦。
- 注意 Cmd+N 也会被吃掉（阻止新窗口），命名是 newNote。

### 6.6 焦点/失焦/撤销

- editor.on("blur") → dt("onBlur")（@379553）。harness 实测：编辑器聚焦后把焦点切到页面上的 <input>，桥收到 onBlur。
- Esc 不失焦：末尾 v.on("keydown", n=> n.key==="Escape" ? (dt("onBlur"), v.commands.blur(), true) : false)（@385397）是死代码——bundle 里没有任何 emit("keydown")（Tiptap 的 on 允许注册任意事件名，不报错）。harness 实测：聚焦、无菜单时按 Esc，onBlur 计数不变、activeElement 仍是 ProseMirror。
- Mod-Enter 会失焦并通知 onBlur（harness 实测 blurs+1，且没插 <br>）。
- window.setMarkdown 会产生 ProseMirror 事务，进 undo 历史（实测：加载后输入、Cmd+Z 能一路退到初始内容「初始化中…」；连续 setMarkdown + 快速输入还可能被 history 的 500ms grouping 合成一个撤销组）。【推断】切换笔记后 Cmd+Z 可能把上一篇/初始内容写回当前笔记——必须在 JavaFX harness/真机验证。
- 撤销/重做会触发 onUpdate（docChanged），Kotlin 会收到回退后的正文并保存。

---

## 7. 主题与 CSS

window.setTheme【读码 @385098】：

```js
window.setTheme = n => { if(!n) return true;
  let t={bg:n.background, fg:n.onBackground, "fg-soft":n.onSurfaceVariant,
         accent:n.accent, "accent-soft":n.accentSoft,
         "code-bg":n.codeBackground, border:n.border};
  for(let [r,i] of Object.entries(t)) i && document.documentElement.style.setProperty("--"+r,i);
  return true }
```

- 只写非空值；没有 removeProperty；传 null 返回 true。字段名是 onBackground / onSurfaceVariant；codeBackground 映射到 --code-bg。
- Kotlin WebTheme 的 7 个字段（MarkdownWebEditor.kt @71–80）：background / onBackground / onSurfaceVariant / accent(概念绿) / accentSoft(conceptGreen α=0.62) / codeBackground(Color(0x2E9E9E9E)) / border(onSurfaceVariant α=0.32)。
- 初始默认（index.html :root @16–24）：--bg:#faf6ec; --fg:#2b2b2b; --fg-soft:#6b6b6b; --accent:#4f6a12; --accent-soft:rgba(79,106,18,0.62); --code-bg:#eee9dd; --border:#d8d2c4。
- harness 实测 setTheme 后 documentElement.style 里 7 个变量全部生效。
- 排版写死：字体 -apple-system, sans-serif（不要写具体字体名，JavaFX WebView 会乱码）、16px/1.5、段落 margin:0 0 10px、h1 24px/h2 21px/h3 19px/h4–h6 16px、#editor padding:20px 32px 120px 40px; overflow-y:auto；主题不控制字号/边距。

---

## 8. 已知坑 / 怪行为（按风险排序）

1. 代码块会被不可逆改写：围栏→4 空格缩进、语言丢失（turndown codeBlockStyle:"indented"）；文档第一块是代码块时缩进被 .trim() 削掉 → 代码块变普通段落（A7/A8/A26/A30）。重写必须显式 codeBlockStyle:"fenced" 且不能整篇 trim。
2. [[原文 => 概念名]] 读不进来：marked 把 > 转义成 &gt;，只有 → 能写 data-target；而写出永远用 =>。Compose 侧写的标注在 WebView 里会显示成「原文 => 概念名」整串概念文本；在 WebView 里用 → 写的标注存盘后 target 丢失。重写必须让 parse 端正确处理 &gt;（解析前解码，或先把标注抽出来再 parse）。
3. 代码里的 {{…}}/[[…]] 被吃掉：替换比 schema 解析早且作用在整串 HTML 上。A21 是可断言回归项。
4. 整篇格式化被重写：Vf 的 trim/列表归一/空行压缩让「敲一个字」变成「重排整篇 md」；表格、图片、硬换行、任务清单、:::ai、$$ 都会在第一次编辑时被永久破坏（这些语法旧 bundle 根本不支持，但文件里可能有——Compose 编辑器/历史笔记会产生）。
5. undo 历史污染：setMarkdown 走 setContent 进撤销栈；切换笔记后 Cmd+Z 可能把上一篇或初始占位内容写回当前笔记（C1）。
6. 每次击键全量序列化：onUpdate 里 turndown(getHTML())+Vf，无防抖；正文长时 O(n)。重写应 debounce 或增量。
7. Esc 不失焦（死代码），文档 §13 描述与实现不符；Mod-Enter 才是失焦键。
8. 单波浪线 strike：序列化出 ~x~；Compose 的 MarkdownText.inlinePattern 只认 ~~…~~（MarkdownText.kt@284），跨编辑器会显示成字面波浪线。
9. 空文档 = "\n"：任何空笔记经 WebView 编辑后正文至少一个换行。
10. 没有只读 API / placeholder setter / destroy：重写若需要预览态、只读，必须新增 window.* 接口并同步改 Kotlin。
11. 旧 bundle 的 Kotlin 头注释过时：MarkdownWebEditor.kt 顶部写「P2 还没做：概念补全/gutter/斜杠/快捷键/主题/定位」，实际上这一版 bundle 全都实现了，Kotlin 也在传 setTheme/setConcepts/reveal/onPageShortcut。重写以代码/本文为准。
12. setMarkdown 失败静默：Kotlin runCatching 吞异常；JS 里 Ak 若 marked 抛错不会通知宿主。
13. getMarkdown 的 .trim() 会削掉开头缩进：不只是代码块，任何以空白开头的文档都会被改。
14. 引用里的列表序列化不规整：> -   a\n>\n> -   b\n>（3 空格 + 空引用行）；两轮稳定但难看。
15. 初始内容「初始化中…」：首次 setMarkdown 前可见；harness 早抓会抓到（getHTML === "<p>初始化中…</p>"）。

---

## 9. 与重写目标（survey/评审）的差异清单

docs/markdown-webview-opensource-survey.md §1 列的「必须保留」自定义能力里，旧 bundle 只实现了：[[…]] 标注（conceptSpan mark）、{{…}}、gutter、斜杠菜单、[[ 补全、AI 定位（近似）、正文回推/失焦、页面快捷键桥、主题变量。

旧 bundle 没有实现：:::ai 块、。。。槽位 + AI 补全浮窗、代码高亮、表格、公式、自定义键位、preview/readonly、图片、任务清单、Tab 缩进。

所以重写时「与旧 bundle 对齐」和「达到目标能力」是两件事：先让 §4.4 的 A 组全绿（回归基线），再新加目标能力。把旧 bundle 当目标能力清单会误以为 :::ai/。。。/代码高亮已经在跑——它们没有。

---

## 10. 复现黑盒结果的方法（建议 harness 固化）

1. 取基线（见文首 git show），把 index.html 的 </body> 前注入 editor.js + 探针脚本（写到 /tmp，不动仓库）。
2. 探针要点（本会话已验证可用）：
   ```js
   window.trellisEditorBridge = {
     onUpdate: md => push({updates: md}),
     onBlur: () => push({blur: 1}),
     onFocus: () => push({focus: 1}),
     onShortcut: n => push({shortcut: n}),
   };
   // 等 ~500ms 让 Tiptap 的 setTimeout(0) 初始化完成
   window.setMarkdown(md); const html = window.getHTML(); const out = window.getMarkdown();
   // 键盘：document.querySelector('.ProseMirror').focus(); 再 dispatch KeyboardEvent(keydown)
   // 概念：window.setConcepts([...])；document.execCommand('insertText', false, '[[')
   ```
3. Chrome headless 取结果（无 node 也能跑）：
   ```bash
   '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
     --headless=new --disable-gpu --no-sandbox --disable-dev-shm-usage \
     --user-data-dir=/tmp/chrome-prof --allow-file-access-from-files \
     --virtual-time-budget=20000 --dump-dom file:///tmp/harness.html
   # 探针把 JSON 写进 <pre id="test-output">，从 --dump-dom 里正则抽出
   ```
4. JavaFX WebView 必须另跑一遍（Chrome 只能证明 JS 逻辑；字体/焦点/keyCode/IME 是 WebKit 行为）。可复用 survey §3.1 的 JavaFX snapshot harness，把「截图」换成 executeScript 读 window.getHTML()/getMarkdown()。
5. 注意 Chrome 合成 KeyboardEvent 的 keyCode 默认 0：测 Cmd+Shift+字母时要么 Object.defineProperty(ev,'keyCode',{value:...})，要么按 §6.4 的 Ac 兜底逻辑理解「没匹配」可能是 keyCode 缺失而不是功能没有。

---

## 11. 未解 / 建议由 harness 验证（诚实清单）

| 项 | 状态 | 建议验证方式 |
|---|---|---|
| Tiptap / marked / turndown 精确版本 | 【未知】bundle 无横幅 | 对比 npm 包 hash / 行为差异（turndown、marked 默认值已知） |
| JavaFX WebKit 是否与 Chrome 同结果（A 组全部） | 【未知】 | JavaFX harness 跑 A1–A30 |
| hr 的 gutter marker — | 【未知】需 gap cursor | 用 ArrowDown 进入 hr 前后 / 点击 hr 上下空白 |
| Cmd+Shift+字母 在 JavaFX 的真实触发表 | 【未知】（Chrome 合成事件 keyCode=0 导致漏测） | JavaFX harness 灌真键 / 补 keyCode |
| 切换笔记后 undo 是否污染 | 【推断】 | C1 |
| IME composition 时 onUpdate 频率/内容 | 【未知】 | C4 |
| 粘贴（HTML/纯文本/图片）行为 | 【未知】 | ProseMirror 默认 paste 逻辑，未自定义 transformPasted |
| 超长文档性能 | 【未知】 | C5 |
| setMarkdown 连续调用丢最后一次？ | 【未知】 | C7（Kotlin pending 只存一个值，理论上有丢失可能） |
| revealRange 偏移语义 | 【读码+部分 harness】参数是文档纯文本偏移（文本节点串接、每个 textblock 加 1），不是 Markdown 源偏移；Compose 传的是 Markdown 偏移 → 目标前面有 # / ** / 列表标记就会选偏。harness 实测 - 项目一\n- 项目二 + reveal(2,4) 选中 "一\n\n" 而非 "项目"。重写必须定义清楚用哪套坐标（建议 markdown 源偏移 + 自建映射，或先 parse 再定位） | 需 JavaFX 真机确认 Compose 侧「搜索命中/概念反查」跳转的实际偏移来源 |
| Esc 在 JavaFX WebView 是否触发别的默认行为 | 【未知】 | C6 |
