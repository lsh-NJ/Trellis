# tools/web-editor —— 知识库 WebView 编辑器源码

> 2026-11-14 起，`app/ui/src/jvmMain/resources/editor/editor.js` 不再是来历不明的构建产物：
> 源码在这个目录，构建脚本把产物写回 resources。

## 为什么有这个项目

评审意见一要求「段落式显示」（段落之间没有整行空行、段距约 1.4 倍、光标不落分隔处），
Compose 的整篇单文本框做不到，只能走 WebView + 块级编辑器。原来的 WebView 路线是一个
打包好的 Tiptap bundle，**仓库和 git 历史里都没有源码**，没法加代码高亮/表格/公式/AI 补全，
只能给压缩产物打补丁。这个目录把它源码化，并顺手修掉旧 bundle 的几个往返 bug。

## 技术栈

- Tiptap 3.34（ProseMirror） + marked（Markdown → HTML） + turndown（HTML → Markdown）
- 构建：esbuild 打成单文件 IIFE（目标 es2017），产物约 1MB（含 Tiptap + lowlight + KaTeX）；另生成 `katex.css`（KaTeX 样式 + 字体内联 data URI，约 1MB）
- 运行：JavaFX WebView 21（WebKit）；页面由 Kotlin 侧把 `index.html` 与本产物内联后 `loadContent`

## 目录

```
src/
  main.js                 入口：建 Editor、挂扩展、暴露 window API、页面级快捷键
  markdown.js             Markdown ↔ HTML/编辑器 的桥（自定义语法的 parse/serialize 规则）
  theme.js                window.setTheme → CSS 变量
  extensions/
    concept.js            [[原文 => 概念名]] 的 mark
    ai.js                 {{…}} 行内 AI 内容的 mark
    ai-block.js           :::ai … ::: 块级 AI 内容
    gutter.js             左侧段落标记（node decoration + data-marker）
    keys.js               代码块 Tab/Shift+Tab 缩进（列表层级交给 StarterKit listKeymap）
  ui/
    slash-commands.js     斜杠菜单命令表（与 Compose 版动作对齐）
    overlays.js           斜杠菜单 / [[ 概念补全浮层
docs/
  baseline-old-bundle.json  旧 bundle 在 16 个夹具上的基线（对比用，2026-11-14 采集）
  baseline-new-bundle.json  新实现同一批夹具的结果
  current-bundle-behavior.md 旧 bundle 的逆向行为规范（API 契约/schema/序列化规则/已知缺陷，539 行）
```

## 构建

```bash
cd tools/web-editor
# 本机用 DSH 自带 node/pnpm；你自己机器上用任意 node 20+ / pnpm 9+
node <pnpm.mjs> install          # 或 pnpm install
node <pnpm.mjs> run build        # 先跑 scripts/embed-katex.mjs 生成 katex.css，再写回 editor.js（压缩）
node <pnpm.mjs> run build:spike  # 只写到 build/editor.js（不压缩，调试用）
```

改完源码**必须重新 build**，然后重启应用（Java 侧资源是打包进 jar/classpath 的）。

## 与 Kotlin 的契约（别随便改）

页面里必须提供（MarkdownWebEditor.kt 调用）：

| JS API | 作用 |
|---|---|
| `window.setMarkdown(md)` | 整篇灌入（不触发 onUpdate 回声） |
| `window.getMarkdown()` | 当前正文序列化成 Markdown |
| `window.getHTML()` | 调试/测试用 |
| `window.setTheme({...})` | 7 个颜色字段 → CSS 变量 |
| `window.setConcepts([...])` | `[[` 补全候选（名字/别名） |
| `window.revealRange(start, end)` | Markdown 偏移 → 选区 + 滚动 |
| `window.focusEditor()` | 聚焦正文 |
| `window.editorReady = true` | 页面初始化完成 |

页面回调 Kotlin（JS 调 `window.trellisEditorBridge.*`）：

- `onUpdate(markdown)`：正文变化（200ms 防抖）
- `onBlur()` / `onFocus()`
- `onShortcut(name)`：`save` / `newNote` / `menu`（页面级快捷键由 JS capture 后转发）

## Markdown 方言（和 Compose 版一致）

- 空行 = 段落；单个换行 = 段内软换行（marked 开 `breaks: true`，序列化回 `\n`）
- `[[概念]]` / `[[原文 => 概念名]]`：显示原文、`data-target` 存概念名
- `{{AI 补全内容}}`：绿色 + 两侧引号（CSS 伪元素）
- `:::ai` 换行写内容、`:::` 收尾：块级 AI 内容
- `。。。` 槽位：只是普通文本（AI 补全交互在 Compose/Kotlin 侧，WebView 侧尚未接浮窗）
- 代码围栏、表格、任务清单：由 marked(gfm) → Tiptap 节点，turndown(gfm) 写回

## 测试

仓库内的 WebView 回归 harness（JavaFX WebView + 16 个夹）：

```bash
export TRELLIS_WEB_HARNESS=1
./gradlew :app:ui:jvmTest --tests 'dev.trellis.app.desktop.WebEditorHarnessTest' --console=plain
# 产物：app/ui/build/web-editor/baseline-current.json + .png
```

不设 `TRELLIS_WEB_HARNESS=1` 时测试自动跳过（无显示环境不会挂）。
换 bundle 做对比时，把 json 拷到 `docs/baseline-old-bundle.json` / `baseline-new-bundle.json` 再 diff。

## 当前状态（2026-11-14，Phase 0）

已做到（相对旧 bundle 的修复）：

- 代码围栏 + 语言保留（旧 bundle 会退化成 4 空格缩进代码块）
- 表格保留（旧 bundle 会拍平成一段文字）
- 任务清单 checkbox 保留（旧 bundle 丢 checkbox）
- `[[原文 => 概念名]]` 往返正常（旧 bundle 的 `>` 被转义导致映射丢失）
- `:::ai` 块识别并往返（旧 bundle 当普通段落）
- 段内软换行按换行保留（旧 bundle 合并成空格）
- 空文档 `getMarkdown()` 返回空串（旧 bundle 返回 `\n`）
- 切换笔记后清空撤销历史（旧 bundle 的 `setMarkdown` 会进 history，Cmd+Z 有把上一篇内容写进当前笔记的风险；见 `docs/current-bundle-behavior.md` §0）
- gutter、斜杠菜单、`[[` 补全、主题、页面级快捷键桥已迁移；`window.setMarkdown/setTheme/setConcepts/revealRange` 返回值对齐旧契约（`true`），并暴露 `window.__trellisEditor` 供 harness/调试用

还没做（按计划顺序）：

1. 代码块语法高亮（`@tiptap/extension-code-block-lowlight` + highlight.js 主题）
2. 公式：`@tiptap/extension-mathematics` + KaTeX（现在 `$$…$$` 仍是普通文本）
3. `。。。` 的 AI 补全浮窗（借 Novel 的 ai-highlight 思路，接 Kotlin `onRequestAiFill`）
4. 自定义键位下发（Kotlin `window.setKeymap` → Tiptap keymap）
5. 表格编辑辅助（现在只能靠 markdown 源码/斜杠菜单插入空表）
