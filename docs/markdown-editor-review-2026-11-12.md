# Markdown 编辑器调优设计（评审《MarkDown编辑器文件检查》落地稿）

> 2026-11-12。来源：`~/Downloads/MarkDown编辑器文件检查.md` 的三条评审意见。
> 这份文档做两件事：**先把口语化的评审意见调成可验收的规格**（逐条注明原文歧义、现状、目标、边界），
> 再给**实现设计与自检计划**。调优后的评审稿（含原稿存档）另存为
> [`markdown-editor-check-refined.md`](markdown-editor-check-refined.md)。
> WebView 编辑器「借开源实现」的调研与 spike 见
> [`markdown-webview-opensource-survey.md`](markdown-webview-opensource-survey.md)（2026-11-14）。落地代码在 `:app:ui` 的 `MarkdownEdit.kt` /
> `MarkdownDocumentEditor.kt` / `MarkdownCommands.kt` / `MarkdownKeys.kt` / 设置页。

## 0. 一页结论

| # | 评审原文（摘要） | 判定 | 调优后的规格（可验收） | 落点 |
|---|---|---|---|---|
| 1 | 「Markdown 段落式设计错误」：shift+enter 只换行、enter 分段；段落之间不能以行为分界线，光标不能放在段落之间，段距略大于行距 | **成立**。默认 Compose 编辑器保留源文件空行，渲染成一整行空行（段距 = 2 倍行距），光标也能停在空行上。**实测证明 Compose 单文本框做不到 <1 行段距**（§3.2），所以「数据源用空行、显示成 1.3 倍段距」只在块级 DOM 的 WebView/Tiptap 里成立 | 段落模型见 §1.1 的 R1.1–R1.5：分段语义不变（Enter 分段 / Shift+Enter 软换行）；**段落式显示由 WebView/Tiptap 编辑器提供**，设置页可切；Compose 编辑器保留现状（2 倍段距）并在设置页注明原因 | 设置页「编辑器」+ `MarkdownEditorPlatform.jvm.kt`（模式解析）+ 既有 `MarkdownWebEditor` |
| 2 | 「Table 没有深度定制」：非代码块里 tab 抬层级（1. → 1.1），代码块里 tab/shift+tab 缩进当前行或选中行 | **成立**，但原文 **Table 应为 Tab**（示例给的是列表层级，不是表格）。当前编辑器里 Tab 只用于接受 AI 补全，没有任何缩进逻辑 | 列表/任务/引用行：Tab 缩进一级（2 空格）、Shift+Tab 降一级；代码块：Tab/Shift+Tab 对当前行或所有选中行加/删 4 空格；普通行：Tab 行首缩进 2 空格、Shift+Tab 删掉 | `MarkdownEdit.kt`（纯函数）+ `MarkdownDocumentEditor.kt`（按键分发） |
| 3 | 「补全目前所有菜单功能的快捷键并可以在设置中自定义」 | **成立**。17 个菜单命令只绑了 14 个（缺任务清单/代码块/图片），且键位写死在 `markdownKeyBindings`，设置页没有入口 | 17/17 全部有默认键位；设置页新增「Markdown 快捷键」区，可逐条改键/恢复默认；键位存 `<home>/markdown.json`；重复键与非法键位有校验；菜单清单显示实际生效的键位 | `MarkdownKeys.kt` + `MarkdownCommands.kt` + `SettingsScreen.kt` + `AppRuntime` |

自检口径见 §7：纯函数单测 + `:app:ui:jvmTest` + 桌面应用截图（Tab 缩进、菜单键位、设置页改键）。

## 1. 意见调优：把口语意见拆成规格

评审原文是三条口语记录，直接照着做会出现三种偏差：①把「现状」读成「要求」；②术语写错；③缺少边界与验收口径。逐条拆如下。

### 1.1 意见一：段落式显示

原文：

> Markdown 段落式设计错误：显示样式有别于传统文本编辑器分行设计，shift+enter 不分段仅换行，enter 进行分段；段落之间不以行为分界线（至少在显示上不行），且光标不能放置在段落之间，段落距离略大于段内行间距在视觉上区分段落

调优后拆成 5 条：

- **R1.1 分段语义**：Enter = 新段落（源文件里段与段之间空一行）；Shift+Enter = 段内软换行（源文件里一个换行）。**这两条现状已正确，不改源文件格式**——导出/预览/知识抽取/AI 整理都按「空行 = 段落」的 Markdown 口径工作。
- **R1.2 显示上不出现整行空行**：段落分隔不能渲染成「一整行空白」再靠它表达段距；正文看上去应像 Notion/marktext 那种连续段落。
- **R1.3 光标不停在段落之间**：常态下（点击/方向键）光标不能落在段落分隔处（块级编辑器里分隔处根本不是一行字符，天然满足）。
- **R1.4 段距略大于行距**：段内行距 24–26sp；段间视觉距离做到约 1.25–1.45 倍（Tiptap 版是 24px 行高 + 10px 段落 margin ≈ 1.42 倍）。
- **R1.5 不回归**：跨段选择、上下键、Backspace 合段、撤销重做、导出/预览都不受影响。**源文件里仍然是 `\n\n`**。

### 1.2 意见二：Tab / Shift+Tab 层级与缩进

原文：

> Table 没有进行深度定制，table 在非代码块中应起到「抬高」层级的作用，如：1. 层级1 / 2. 层级2（tab） -> 1. ... 1.1 ...（shift + tab）-> 1. ... 2. ... 而在代码块中，tab和shift+tab都要能够将所处行或者所有选中行进行空格填充

调优后：

- **术语更正**：原文 `Table` 是 `Tab` 的笔误；示例本身讲的是有序列表的层级（`1.` 下缩一级渲染成 `1.1`），评审并没有在说 Markdown 表格（表格的 Tab 跳格另列为后续，见 §6）。
- **R2.1 列表层级**：光标/选区在列表（无序、有序、任务）或引用行上时，Tab = 缩进一级（2 空格），Shift+Tab = 撤销一级；多行选区逐行处理，空行跳过；缩进不重写有序编号（编号由渲染按层级显示）。
- **R2.2 代码块缩进**：光标在围栏代码块里（围栏行或内容行）时，Tab = 当前行或所有选中行行首加 4 空格；Shift+Tab = 行首删掉最多 4 个空格（也兼容 Tab 字符）。**代码块里的空行也补缩进**（空行按 Tab 加缩进是代码编辑器常态；正文/列表里的空行不加，避免留下看不见的空白）。
- **R2.3 普通行**：Tab = 行首加 2 空格；Shift+Tab = 行首删掉最多 2 个空格。
- **R2.4 按键优先级**：AI 补全的 Tab 接受手势最优先（行尾有 `。。。` 时），其次是 `[[` 概念补全、斜杠菜单的 Tab 认领，最后才轮到缩进；菜单没开且没有补全建议时 Tab 一定做缩进（正文/列表空行按口径跳过，代码块空行也缩进），不再「按了没反应」。

### 1.3 意见三：快捷键补全 + 设置里自定义

原文：

> 补全目前所有菜单功能的快捷键并可以在设置中自定义

调优后：

- **R3.1 覆盖完整**：斜杠菜单的 17 个命令（一级/二级/三级标题、正文、无序列表、有序列表、任务清单、引用、分割线、代码块、粗体、斜体、删除线、行内代码、链接、表格、图片）每个都有默认键位；新增任务清单、代码块、图片三条。
- **R3.2 可自定义**：设置页新增「Markdown 快捷键」区；每行「动作 · 当前键帽」，点「改键」进入捕获态（按任意组合键生效、Esc 取消）；可单条「恢复默认」、可全部恢复。
- **R3.3 校验**：只接受 `Ctrl/Cmd`（二选一按平台）、`Shift`、`Alt` + 字母/数字/少量符号；同一个键不能绑两个命令（拒绝并提示谁占了）；非法键位忽略并回退默认。
- **R3.4 生效面**：编辑器按键分发、页头「菜单」里的快捷键清单都读同一份生效键位。
- **R3.5 持久化**：键位存 `<home>/markdown.json`（`{"bindings": {"bold": "Ctrl/Cmd+B", ...}}`；空串 = 解绑）；坏文件/缺字段一律回退默认，不挡启动。

## 2. 现状（改动前）

| 话题 | 现状 | 代码 |
|---|---|---|
| 段落 | 源文件「空行 = 段落」；显示层**保留**真实空行，行高由 `textStyle.lineHeight = 26.sp` 统一决定 → 段距 = 2 倍行距；光标能停在空行 | `MarkdownDocumentEdit.kt`（`projectDocument` 注释「段落之间保留真实空行」）`MarkdownDocumentEditor.kt`（lineHeight） |
| 段落回车 | `breakParagraphAt` 在段落交界补到两个换行；行首回车在上方开一个空段；列表/引用回车拆成同类行 | `MarkdownDocumentEdit.kt` |
| Tab | 只有「AI 补全 Tab 接受」；其余 Tab 交给平台，没有缩进 | `MarkdownDocumentEditor.handleKey` |
| 快捷键 | `markdownKeyBindings` 14 条写死；`markdownShortcutHints` 固定清单；菜单命令 17 条 | `MarkdownCommands.kt` |
| 设置 | 设置页没有编辑器键位/编辑器形态入口；`<home>/ui.json` 管外观，不管键位 | `SettingsScreen.kt` / `UiPrefs.kt` |
| 已知限制 | 整篇单文本框没法只把空行压到半行（2026-11-04 结论）；WebView+Tiptap 路线就是为段距做的实验 | `docs/knowledge-base-draft.md` §12 / §13 |

## 3. 设计一：段落式显示（为什么是「切换编辑器形态」）

### 3.1 结论

「源文件空行分段 + 显示成 1.3 倍段距」要求显示层能逐行控制行高；Compose 的整篇单文本框做不到（下节实测），**块级 DOM 的 Tiptap 版本来就能做到**（`index.html` 里 `.ProseMirror p { margin: 0 0 10px }` + 24px 行高 = 1.42 倍段距，空行不在 DOM 里，光标不可能停在段落之间）。

所以本轮落地方式：

- **默认不变**（Compose 编辑器，功能最全），避免为了段距把代码块/表格/公式渲染、AI 补全等能力换成实验实现；
- 设置页「编辑器」新增二选一：
  - 「单文本（Compose，默认）」——现状；
  - 「段落式（WebView 实验）」——R1.2–R1.4 成立，但实验路径尚未对齐代码高亮/AI 补全/自定义键位（设置页就地注明）。
- 桌面生效；安卓没有 JavaFX，永远走 Compose（设置项在安卓上不出现）。
- `TRELLIS_EDITOR=webview` 环境变量仍保留，优先于设置项（演示/调试用）。

### 3.2 实测：Compose 单文本框做不到

用 `MultiParagraph` 直接布局做了 5 组探针（密度 2、16sp 正文、26sp 行高；结论可复跑）：

1. **不给行高、只靠字体**：中文行距 ≈ 22.5sp、ASCII ≈ 16sp，不统一，不能当基准。
2. **空行里塞零宽空格 + 小字号**（`SpanStyle(fontSize = 6.sp)`）：空行仍占 16sp——**行高有字体度量的下限**，压不下去。这和 2026-11-04 的既有结论一致。
3. **用 `ParagraphStyle` 逐行切段**（正文 26sp / 空行 8sp）：`AnnotatedString` 支持 `paragraphStyles`，也确实按 8sp 布局了空行，**但每个以换行结尾的自定义段落都会多出一行**（`lineCount` 从 3 变 5），段落边界出现重复行、行 top 不再单调，文本会抖动/重叠。
4. **只给空行挂 `ParagraphStyle`**：相邻默认段落被 `LineHeightStyle` 的 trim 规则切开，行 top 混乱（出现 0 高度行、重叠行）。
5. **改成「显示层删掉分隔换行」**：能删，但删完没有字符可承载段距，段距变成 0，反而失去分段感。

结论：R1.2/R1.3/R1.4 在 Compose 单文本框里**无法同时成立**；可行解只有「块级编辑器（Tiptap）」或「重写成逐块文本框（2026-11-02 已否决，跨块选区/空块焦点都会坏）」。本稿选前者，作为可切换形态。

### 3.3 JavaFX 启动：不能让它把界面拖死（2026-11-14 修）

段落式编辑器依赖 JavaFX WebView。JavaFX 的 toolkit 初始化失败时，`JFXPanel` 会在 EDT 上
开一个 `SecondaryLoop` 等初始化完成——初始化一失败，EDT 就出不来，表现是「窗口还在、整页点不动」
（用户实测切换段落式后假死）。常见失败源：`~/.openjfx/cache` 不可写（沙箱/只读家目录/旧缓存损坏）、
图形管线初始化失败。

修法（`MarkdownWebRuntime.kt` + `MarkdownEditorPlatform.jvm.kt`）：

1. **启动挪到后台线程**（`Dispatchers.IO`），带 20s 超时；EDT 不再等 toolkit。
2. **`javafx.cachedir` 指到系统临时目录**（可写性比 `~/.openjfx` 可靠），避免「原生库解压不了」这一大类失败。
3. **失败回退**：JavaFX 起不来就不挂 `JFXPanel`，改挂 Compose 编辑器并在正文上方给一行错误说明
   （`webViewStateFor(false) → Unavailable`）；启动中先显示「正在启动段落式编辑器…」占位。
4. 失败只打一行 stderr（`Trellis：段落式编辑器启动失败（原因），回退单文本编辑器`），方便排查。

### 3.4 边界

- 设置项只影响知识库正文编辑器；切换时正文/自动保存/概念/AI 整理都照旧（同一份 Markdown 源）。
- 段落式模式下 `[[概念]]` 补全、gutter、斜杠菜单已在该路线实现过（见 `knowledge-base-draft.md` §13）；未对齐项（代码高亮/表格/公式/AI 补全/自定义键位）在设置页注明「实验」。
- 不把 WebView 做成默认：默认编辑器承载了最多功能，切默认是功能回退。

## 4. 设计二：Tab / Shift+Tab

### 4.1 纯函数（`MarkdownEdit.kt`，可单测）

```kotlin
/** 缩进单位：列表/普通行 2 空格，代码块 4 空格。 */
internal const val LIST_INDENT = "  "
internal const val CODE_INDENT = "    "

/** Tab：给当前行或选区内所有行行首加 [unit]，空行跳过；折叠光标时不动光标相对行首的偏移。 */
internal fun indentMarkdownLines(text: String, selection: TextRange, unit: String): MarkdownEditResult

/** Shift+Tab：行首最多删掉一个 [unit]（不足一个单位就删掉已有空白；Tab 字符也算一个单位）。 */
internal fun outdentMarkdownLines(text: String, selection: TextRange, unit: String): MarkdownEditResult
```

- 多行选区：逐行处理，选区按行首变化量整体平移；折叠光标：只处理当前行。
- 行首已有空白的退格边界：`outdent` 只删行首的空白（空格/Tab），删不到内容。
- 编号不重写：`1. ` 缩进后仍是 `1.`，层级由渲染按缩进显示（与 Tiptap/Notion 一致）。

### 4.2 按键分发（`MarkdownDocumentEditor.handleKey`）

顺序（前面的先抢）：

1. `Esc`（收 AI 补全 → 概念补全 → 斜杠菜单 → 失焦）
2. 行尾 `。。。` 空位存在时的 `Tab` → AI 补全（现状）
3. 概念补全开着的 `Tab` → 认领候选（现状）
4. 斜杠菜单开着的 `Tab` → 执行命令（现状）
5. **新增：`Tab` / `Shift+Tab`**
   - 光标所在行（或选区首行）类别为 `CodeFence/CodeContent` → 单位 `CODE_INDENT`；
   - 否则 → 单位 `LIST_INDENT`（列表/任务/引用/普通行都适用；普通行缩进两格是通用文本编辑器的直觉）。
6. `Ctrl/Cmd` 组合 → 命令快捷键（改为读生效键位，见 §5）
7. `Enter` 语义（现状）

代码块里 `Shift+Tab` 只删行首空白，不会删到内容；选中多行时逐行加减，行数不变。

## 5. 设计三：快捷键注册表 + 设置页自定义

### 5.1 模型（新文件 `MarkdownKeys.kt`）

```kotlin
internal data class MarkdownBinding(
    val commandId: String,
    val key: Key,
    val shift: Boolean,
    val alt: Boolean = false,
    /** 平台无关写法：Ctrl/Cmd+B、Ctrl/Cmd+Shift+X、Alt+1。 */
    val label: String,
)

/** 17 个菜单命令的默认键位。 */
internal val defaultMarkdownBindings: List<MarkdownBinding>

/** 叠加用户覆盖（commandId -> label；空串 = 解绑）后的生效键位；非法/重复的覆盖会被忽略并退回默认。 */
internal fun effectiveMarkdownBindings(overrides: Map<String, String>): List<MarkdownBinding>

internal fun markdownBindingFor(key: Key, shift: Boolean, alt: Boolean = false, bindings: List<MarkdownBinding>): MarkdownBinding?

/** 捕获键位 → 规范 label（不支持的键/没有修饰键返回 null）。 */
internal fun shortcutLabelForKey(key: Key, ctrlCmd: Boolean, shift: Boolean, alt: Boolean): String?

/** 校验/规整用户输入；非法返回 null。 */
internal fun normalizeMarkdownShortcut(label: String): String?

/** 页头菜单的快捷键清单（只列有键位的命令，动作在前、按键在后）。 */
internal fun markdownShortcutHints(bindings: List<MarkdownBinding>): List<ShortcutHint>
```

默认键位（沿用现有 14 条，补 3 条）：

| 命令 | 默认 | 命令 | 默认 |
|---|---|---|---|
| 一级/二级/三级标题 | Ctrl/Cmd+1/2/3 | 正文 | Ctrl/Cmd+0 |
| 粗体 / 斜体 | Ctrl/Cmd+B / I | 链接 | Ctrl/Cmd+K |
| 删除线 / 行内代码 | Ctrl/Cmd+Shift+X / C | 无序/有序列表 | Ctrl/Cmd+Shift+L / O |
| 引用 / 分割线 | Ctrl/Cmd+Shift+Q / R | 表格 | Ctrl/Cmd+Shift+T |
| **任务清单（新增）** | Ctrl/Cmd+Shift+K | **代码块（新增）** | Ctrl/Cmd+Shift+E |
| **图片（新增）** | Ctrl/Cmd+Shift+I | | |

`MarkdownCommands.kt` 里写死的 14 条常量删除；`markdownShortcutHints(bindings)` 改为按生效键位生成
（旧的 14 条键位语义由 [MarkdownKeys.kt] 的默认表保住）。

### 5.2 持久化

- 新文件 `<home>/markdown.json`；结构 `{"bindings": {"bold": "Ctrl/Cmd+B", ...}}`，只存**与默认不同**的项。
- 读写走 `MarkdownKeyStore`（`MarkdownKeyStore.kt`：`load`/`save`），坏 JSON / BOM / 未知命令 / 非法键位 → 该项回退默认，不影响其它项。
- **写入前验算**：`AppRuntime.commitMarkdownKeys` 逐条核对「存进去的覆盖 == 算出来的生效键位」，任何一条对不上就拒绝并说清被谁占着——否则会出现「设置页说改好了、按下去还是旧键」（把键位让给别的命令再改回来时容易命中）。
- 页面/窗口级保留键（`Ctrl/Cmd+S/N//Q`）不允许绑给命令；`markdownReservedShortcuts` 与 `KnowledgeScreen`/`Main.kt` 的快捷键保持一致。
- `AppRuntime` 增加 `markdownKeys: Map<String, String>` 状态与 `updateMarkdownBinding(commandId, label)`、`resetMarkdownBindings()`；设置页写、知识库页读，同一份状态。

### 5.3 设置页

设置页新增两个区段：

1. **「编辑器」**（对应评审意见一）：`单文本（Compose，默认）` / `段落式（WebView，实验）` 二选一 + 一行说明（实验路径未对齐代码高亮/AI 补全/自定义键位）。
2. **「Markdown 快捷键」**（对应评审意见三）：一行一个命令，左边动作名、右边键帽（`ShortcutKeyCap`）+「改键」「恢复」；点「改键」进入捕获态（`onPreviewKeyEvent` 抓第一个带 Ctrl/Cmd/Alt 的键，Esc 取消）；冲突提示「已被「粗体」占用」并拒绝；底部「全部恢复默认」。

### 5.4 生效面

- `KnowledgeScreen` 从 runtime 取 `effectiveMarkdownBindings(runtime.markdownKeys)`，传给 `MarkdownEditor` → `PlatformMarkdownEditor` → `MarkdownDocumentEditor`（新增参数 `bindings`，默认 `defaultMarkdownBindings`，安卓同一份代码生效）。
- 页头「菜单」的快捷键清单读同一份生效键位。
- WebView 实验路径的键位由打包好的 JS 管理，**自定义键位只对 Compose 编辑器生效**（设置页注明）。

## 6. 不做 / 后续

- 表格内 Tab 跳格（评审原文的示例不是表格，本轮按 Tab 键处理）：表格跳格需要另做单元格模型，记为后续。
- 把 WebView/Tiptap 设为默认：会让代码块/表格/公式渲染、AI 补全等回退，不做；保留为设置项。
- 段内软换行的视觉标记（`↵` 之类）：不做，段内换行与普通换行显示一致。
- 段落级拖拽/大纲折叠：不做。

## 7. 自检计划

1. **纯函数单测**（新增/扩展）：
   - `MarkdownIndentTest`（新）：列表/代码/普通行的 Tab/Shift+Tab、多行选区、空行跳过、编号不重写、缩进后光标位置；
   - `MarkdownKeysTest`（新）：17 条命令全覆盖、默认键位无重复、覆盖合并/解绑/非法/冲突、捕获键位往返、清单与生效键位一致；
   - `MarkdownCommandsTest`（扩展）：17 个命令每个都有默认键位、清单与生效键位一致；
   - `MarkdownEditorPlatformTest`（新）：编辑器形态解析（设置项 / 环境变量 / 缺省）。
2. **编译与测试**：JDK 17 + Gradle 8.10.2；`./gradlew :app:ui:jvmTest --tests 'dev.trellis.app.desktop.Markdown*'`；键盘接线用 `MarkdownEditorKeyTest`（`ImageComposeScene` 灌键事件）。
3. **交互自检**（桌面应用，`TRELLIS_HOME` 指到临时目录）：列表里 Tab/Shift+Tab；代码块里选中多行 Tab；页头菜单看键位；设置页改键后回编辑器验证；恢复默认；切换「段落式」编辑器看段距。
4. **回归清单**：跨段拖选、全选、Backspace 合段、上下键跨段、AI 补全 Tab、斜杠菜单、`[[` 补全、导出、预览。安卓共用同一份代码（新增参数都有默认值）。

### 7.1 自检结果（2026-11-12 实跑）

- 环境：macOS arm64 + Azul Zulu JDK 17.0.13 + Gradle 8.10.2（wrapper 发行版走腾讯镜像）。测试 JVM 加 `-Dskiko.data.path=/tmp/skiko/`（离屏渲染解压 Skia 原生库要用；CI 上 `~/.skiko` 可写就不用）。
- **全量 `:app:ui:jvmTest`：116 个测试类 / 969 个用例，0 失败 0 错误（2 skipped），其中 Markdown 相关 14 类 / 203 个用例。**
  （第一次全量跑时 1 个与本次改动无关的活动归因 E2E —— `ActivityAutoCompleteE2ETest` —— 偶发等待超时，单独复跑通过；它依赖真实时间/协程调度，属仓库既有 flaky 面。）
- 新增/扩展单测：`MarkdownIndentTest`（12）、`MarkdownKeysTest`（23）、`MarkdownEditorPlatformTest`（6）、`MarkdownEditorKeyTest`（3，离屏键盘接线）、`MarkdownCommandsTest`（+3）。
- **键盘接线端到端**（`MarkdownEditorKeyTest`）：在 `ImageComposeScene` 里铺真的 `MarkdownDocumentEditor`、灌 Compose 键事件，实测 Tab 缩进列表行、Shift+Tab 撤销、代码块 4 空格、把粗体改成 `Ctrl/Cmd+Shift+B` 后 `Cmd+B` 不再生效而新键生效。
- **离屏 UI**：整页设置页渲染（「编辑器」「Markdown 快捷键」两段、17 行键帽）、点「改键」进捕获态的截图都人工看过；改键写入 runtime 的链路在离屏场景里验证过。
- 真实窗口：首轮用 Cua 取 Compose 窗口超时（Java 进程的 AX 树在 120s 内没返回），当时没有做真实窗口截图；2026-11-14 已补上——`MainKt` 进程现在能取到窗口并截图/模拟键鼠，见 §7.3。
- 段落式（WebView）启动实测（2026-11-14，用 `jps`+`jcmd Thread.print` 看真实进程）：
  - 正常路径：`javafx.cachedir` 指到临时目录后 JavaFX 线程正常起来（`JavaFX Application Thread`/`QuantumRenderer`），stderr 无异常，EDT 停在正常事件循环；
  - 失败路径：强制 `-Djavafx.cachedir=~/.openjfx/cache`（不可写）→ JavaFX 渲染管线初始化失败，日志出现
    `Trellis：段落式编辑器启动失败（No toolkit found），回退单文本编辑器`，EDT 仍在正常事件循环（没有 `SecondaryLoop` 死等），应用可继续交互。

### 7.2 段落式编辑器真机冒烟（2026-11-14 下午）

跑法：`TRELLIS_HOME=/tmp/trellis-webview-test TRELLIS_EDITOR=webview ./gradlew :app:desktop:run`，
用 Cua 截真实窗口 + 模拟键鼠。发现两个**只有真机才暴露**的问题，都已修：

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 打开笔记后 WebView 一片空白，只剩 gutter 的 ¶；左侧字数、右侧目录都正常 | `MarkdownWebEditor` 里 `applied`（已灌给页面的正文）初值取的是 `body`。首次组合时 body 就是打开的笔记，`LaunchedEffect(noteId, body)` 判定「一样」直接跳过，**从来没调过 `setMarkdown`**。harness 一直是显式调 `setMarkdown`，所以测不到 | `applied` 初值改成 `""`（表示还没灌过），首次组合必定下发；注释里写清为什么不能拿 body 当初值 |
| 2 | 首屏正文被上方标题栏裁掉约 60px，滚一下才完整 | 页面里滚动的是 `#editor`（`overflow-y: auto`），不是 window；`setMarkdown` 只做了 `window.scrollTo(0, 0)`，管不到它，ProseMirror 灌内容时留下的 scrollTop 一直留着 | `tools/web-editor/src/main.js` 加 `resetScroll()`：`element.scrollTop = 0` + 父级 + `document.scrollingElement` + window，并在 rAF、120ms 各补一次（WebKit 排版完成前设会被回滚） |

实测通过（真机截图：[webview-smoke-live.png](assets/webview-smoke-live.png)）：

- 首次打开就有正文；标题字号、段落间距、Shift+Enter 软换行、列表嵌套/有序列表、引用、行内代码/粗体/斜体/删除线、代码块、表格、`[[概念]]` 与 `{{AI}}` / `:::ai` 标记都在；
- 真键盘输入链路通：点正文 → Return 分段 → 输入 `abc`，字数 397 → 402、`已保存` 时间同步更新（JS 桥 `onUpdate` → Kotlin `onBodyChange` → 自动保存）；
- gutter（¶ / H1）跟着光标块走，且不占行高。

真机仍未对齐（下一阶段）：公式 `$$…$$` 还是普通文本、`。。。` 的 AI 补全浮窗没有、自定义键位没下发、代码块没语言角标/语法高亮、表格只有最基础渲染（无边框、列宽不能调）、页面内滚动与 Compose 页面滚动是两套（正文超过 560dp 出现内层滚动条）。

### 7.3 独立审查与修正（reviewer-agent，只读）

跑完初版实现后让一个独立代理只读审查全部 diff，它找出 5 条「应当修」，本轮已全部修掉并补了测试：

| # | 问题 | 修法 |
|---|---|---|
| 1 | 覆盖解析依赖 Map 迭代顺序：把键位让给别的命令再改回来，可能出现「设置页说改好了、按下去还是旧键」 | `effectiveMarkdownBindings` 改成「先给未被接管的命令占住默认键，再放覆盖，最后回退默认」（与 Map 顺序无关）；`AppRuntime.commitMarkdownKeys` **写入前逐条验算**，对不上就拒绝并说清被谁占着 |
| 2 | Shift+↓ 选到下一行行首时，Tab/Shift+Tab 会多改那一行 | 新增 `selectedLinesEnd`：选区终点落在行首时按上一行结尾算 |
| 3 | 代码块空行 Tab 是 no-op（与 R2.4 矛盾） | `indentMarkdownLines(..., indentEmptyLines)`，代码块传 true；正文/列表仍跳过空行 |
| 4 | Alt+Enter 从「分段」变成「失焦 + 保存 + 可能触发 AI 整理」 | 失焦只认 Ctrl/Cmd+Enter；Alt 只参与快捷键分发 |
| 5 | 可以把命令绑到页面/窗口快捷键（`Ctrl/Cmd+S/N//Q`），永远不生效甚至退应用 | 新增 `markdownReservedShortcuts`，写入时拒绝并说明原因 |

顺带修了：设置页补「解绑」入口（之前只有代码/测试能解绑）、清除数据保留清单补 `markdown.json`、Tab 提示改成跨平台写法、多处注释/文档里过时的「14 条写死键位」「只有环境变量能开 WebView」说法。

### 7.4 打包版（.app）才暴露的问题（2026-11-15）

用户在正式包（`createDistributable` 打出的 `Trellis.app`）里看到：知识库正文一整块白板、按钮能高亮但点击不跳页。
根因不是「Compose 还是 WebView」的选择，而是 **jpackage/jlink 运行时缺 JavaFX 反射/JNI 依赖的 JDK 模块**，
四条都是开发机（完整 JDK）永远踩不到的：

| 缺的模块 | 打包后的表现 | 谁在用 |
|---|---|---|
| `jdk.unsupported.desktop` | 白板 + 整页鼠标/点击失灵；日志刷 `NoClassDefFoundError: jdk/swing/interop/SwingInterOpUtils`（JFXPanel 的 postEvent/setCursor 都在它上面） | `com.sun.javafx.embed.swing.JFXPanel` |
| `jdk.jsobject` | `executeScript` 时 WebKit 解 JSObject，JVM 直接 SIGSEGV（`hs_err_pid*.log`，不是可捕获异常） | `netscape.javascript.JSObject` / WebKit JS 桥 |
| `jdk.xml.dom` | javafx.web 的 module-info 里 requires，classpath 用法下 jlink 不拉 | JavaFX WebView |
| `jdk.unsupported` | 页面能加载但一 paint 就 `ClassNotFoundException: sun.misc.Unsafe`，编辑器一直是白板 | WebView 软件绘制路径 |

修法：`app/desktop/build.gradle.kts` 的 `nativeDistributions.modules(...)` 手写这四个模块（和 `java.sql`/`java.net.http` 同一个教训：
**jdeps 推不出来，必须手写**），并在 `MarkdownWebRuntime` 启动 JavaFX 之前先 `Class.forName` 探 `jdk.swing.interop`，缺了直接回退 Compose 而不是挂在一个半死的 JavaFX 上。
`MarkdownWebRuntimeModuleTest` 会读 build 脚本，少一个模块就失败。

顺带修的两个真机问题（和打包无关，但只有真机/真输入才暴露）：

- `JFXPanel` 的键盘事件走 AWT 焦点链：点了 WebView 只拿到 JavaFX 内部焦点，AWT 焦点还在 Compose 画布上 → 能点、能滚、打不了字。修法：AWT 全局鼠标监听里点到 `fxPanel` 时补 `requestFocusInWindow()`。
- Bridge 安装不能在 `loadWorker` 的 SUCCEEDED 回调里同步 `executeScript`（WebKit 还在派发 load 事件，重入会踩 JSObject 引用）；改成 `Platform.runLater` 里装一次（`installBridgeOnce()`）。

验证：重建后的 `Trellis.app` 用真实笔记 + 深色主题启动，正文正常渲染、导航可点、正文可输入并自动保存（截图见 `docs/assets/webview-packaged-ok.png`、`webview-packaged-input.png`）；全量 `:app:ui:jvmTest` 974 用例 0 失败。
### 7.5 默认切到段落式 + 往返保真（2026-11-15 晚）

用户拍板「不换内置浏览器、Compose 等 WebView 完善后再删；但现在默认切 WebView」，本轮落地：

- 默认形态：`UiPrefs.editorMode` 缺省 = `webview`；`resolveMarkdownEditorMode` 的兜底也改成 webview（配置写坏不再悄悄退回单文本）；设置页仍保留「单文本（Compose）」作为退路，并改掉「实验/默认单文本」的旧文案。
- 往返保真（`tools/web-editor/src/markdown.js`）：
  1. **缩进不足的嵌套列表**：中文笔记常见「父项 `2. `、子项只缩进 2 空格」，CommonMark 会解析成同级——一编辑就被拍平重编号。新增 `normalizeListIndent()`：按父项内容列把子项补到正确缩进（识别围栏/`:::ai` 边界），保证结构不被编辑器改掉。
  2. **过度转义**：turndown 按文本节点转义，行内代码后面的 `-` 会写成 `\-`、空格间的 `_` 写成 `\_`、LaTeX 的 `\int` 变成 `\\int`。新增 `tidyEscapes()`：行内（代码片段之外）清理 `\-`/`\_`/`\+` 与重复反斜杠，行首的语法转义保留。
- 回归：harness 17 个夹具里 **16 个字节级一致**；仅 `14-blank-lines` 仍有差异（连续空行归一成一个空行——WYSIWYG 的固有归一化，语义不变）。新增夹具 `16-nested-list-indent` 钉住「缩进不足的嵌套列表 + 行内代码后连字符 + 空格间下划线」。
- 用真实笔记（Mac 环境变量那篇）做往返：结构（嵌套列表）保留，剩余差异只有「续行缩进 2→3 空格」「列表内空行归一」「`\$`→`$`」这类语义等价的重排，不再拍平、不再新增转义。
- 全量 `:app:ui:jvmTest` 974 用例 0 失败；重建 `Trellis.app` 后用空 `TRELLIS_HOME` 启动，默认即段落式（无需环境变量）、正文可输入、导航可点。
### 7.6 AI 整理与保存脱钩 + 公式/代码高亮（2026-11-16）

用户定的两条：**保存和 AI 分析脱钩**（AI 只由按钮触发，方便测试和笔记基础功能开发）、**继续补渲染**。

- 保存：仍然自动（停手 600ms 落盘），`onEditingFinished` 只负责补一次 `persistNow()`。
- AI 整理：删掉「切笔记 / 新建 / 从网络打开笔记 / 失焦」四处自动 `organizeNote`，只保留页头 chip；
  chip 文案改为「AI 整理」（原「保存并整理」），点它 = 先落盘再 `organizeNote(id, force = true)`。
  实测：点按钮会提示「AI 没配置…」（证明走的是显式入口），写正文/失焦都不会再调模型。
- 公式：接 `@tiptap/extension-mathematics` + KaTeX；Markdown `$$…$$`（单行/多行）与行内 `$…$` 双向保留。
  KaTeX 的 CSS + 字体由 `tools/web-editor/scripts/embed-katex.mjs` 在构建时**内联成 data URI**
  （`loadContent` 没有 base URL，外链字体加载不到），Kotlin 侧 `editorPage()` 把它内联进 `<style>`。
- 代码高亮：`@tiptap/extension-code-block-lowlight` + lowlight（highlight.js common 语言集），
  围栏语言（```kotlin）双向保留；配色写在 `index.html` 的 `hljs-*` 规则里。
- 往返：harness 17 个夹具里 **16 个字节级一致**（新增 math 断言：`09-math` 从「往返有差异」变「原样」）；
  剩 `14-blank-lines`（连续空行归一）与 `16-nested-list-indent`（缩进补齐）属于语义等价重排。
- 真机：重建 `.app` 打开带公式/代码的笔记，行内公式、块级积分、Kotlin 高亮都正常；
  写一个字仍自动保存；点「AI 整理」走显式入口（无模型时提示没配置）。
### 7.7 「菜单」被压住 + 系统大滚动条（2026-11-16 晚）

用户实测截图反馈两处 UI：

1. 「菜单」（快捷键清单）被**压在 WebView 下面**、左右边缘被裁；
2. 正文滚动条是 WebKit 系统大滑条（带上下箭头），和整体风格不搭。

原因与修法：

- 菜单原来是 Compose 浮层（`MarkdownMenuOverlay`），而 WebView 是重量级 AWT 组件，
  永远画在 Compose 画布上面——所以浮层只能从边缘露一点，怎么调 z-index 都没用。
  现在改成 **WebView 页面内的 DOM 浮窗**：定位在光标/选区旁边（拿不到选区就落在编辑器左上），
  圆角卡片 + 阴影 + 半透明遮罩，点空白或 Esc 关闭；背景/边框直接用正文的 `--bg`/`--border`，颜色天然一致。
  打开：Compose 页头「菜单」→ `menuOpen` → `window.showShortcutMenu(json)`；关闭：页面内点遮罩/Esc
  → 桥回调 `onMenuClosed` → Compose 状态归零。Compose 编辑器（退路）仍用原浮层。
- **WebView 本体改为「顶天立地」**：之前编辑面固定 `heightIn(min=560dp)` 放在整页 `verticalScroll` 里，
  下方会留一大块空白。现在按平台形态分两条排版：段落式 = `Column(fillMaxSize)` + 标题 + `weight(1f)` 的编辑器
  （标题下 8dp、底栏上 10dp，高度吃满剩余空间，滚动交给 WebView 自己）；Compose 形态保持整页滚动。
  平台判定走新加的 `resolvePlatformEditorMode()`（桌面看 `TRELLIS_EDITOR` 覆盖，安卓恒为 Compose）。
- 滚动条：`#editor::-webkit-scrollbar` 改成 10px 透明轨道 + 圆角胶囊滑块（中性灰 `rgba(127,127,127,.30)`，
  hover 提亮到 .48），隐藏系统箭头按钮；深色/浅色主题都靠中性灰自适应，不需要额外变量。

回归：harness 现在额外产出两张截图 `shortcut-menu.png`（DOM 菜单）与 `scrollbar.png`（长文档滚动条），
跑一次就能看出样式有没有回退；本次两张都已人工看过（菜单贴右边满高、滚动条是细胶囊）。
### 7.8 输入 `[[概念]]` 后「本篇概念」不更新（2026-11-16 中午）

用户实测：在 WebView 里敲 `[[查看环境变量]]`，保存后右侧「本篇概念」仍是 0，反而多出「已删除 6」。

根因是**两端各缺一环**，叠加成「标注被吃掉」：

1. WebView 里直接敲出来的 `[[…]]` 是**普通文本**，不是概念 mark；turndown 按 Markdown 规则把 `[`/`]`
   转义，落盘就变成 `\[\[查看环境变量\]\]`。
2. Kotlin 侧 `parseConceptAnnotations` 只认字面 `[[…]]`，转义形式解析不到 → 重算「本文概念」得到空表；
   之前登记的旧概念因此被挪进 `removed-concepts:`，于是出现「0 个概念 + 已删除 6」。

现在（用户拍板）的语义是：**不做自动转义，但支持手动转义**。

- **输入 `[[名字]]` / `[[原文 => 名字]]`**：InputRule 立刻把它变成概念标注（绿色下划线），序列化走概念规则
  原样写回 `[[…]]`，不再产生 `\[\[…\]\]`；停手 600ms 自动保存后，「本篇概念」跟着更新。
- **输入 `\[\[这个名字\]\]`（手动转义）**：InputRule 把它变成**普通文本** `[[这个名字]]`（不加标注）；
  保存时按 Markdown 规则写回 `\[\[…\]\]`，文件语义保持「字面方括号」。解析器只认字面 `[[…]]`，
  转义形式不算标注、不进「本篇概念」。
- 因此去掉了中间那版「自动把转义还原成标注」和「解析器兼容转义」——那会和手动转义冲突。
- 保留的另一半：**打开笔记时按正文重算一次 frontmatter 缓存**（真的变了才写盘），旧笔记不编辑也能刷新概念。
- 回归：夹具 `17-escaped-concept` 要求**转义原样保留**（往返一致）；`KnowledgeAnnotationTest` 两条改成
  「转义是字面文本、不算标注、会被挪进墓碑」。
- 数据：用户那篇被旧 bug 转义的《Mac 环境变量》已做一次性迁移（`\[\[…\]\]`→`[[…]]`，原文件留 `.bak-*` 备份），
  之后打开会自动补上 `concepts:`。
### 7.9 列表里的 Enter / 退格语义（2026-11-16 下午）

用户反馈：列表里换行以后，Enter 和退格都在「退层级」，不符合直觉。要求的语义是：

- **Enter**：在当前层级新建一项（不要升降级）；
- **退格**（光标在空列表项开头）：先删列表头，把这一行并到**上一个列表项**末尾、变成软换行（相当于 Shift+Enter）；
  再按一次退格，删掉软换行、并回上一行；
- 非空列表项 / 列表项里还有子列表时，不接管，交回 Tiptap 默认的 join/lift。

实现（`tools/web-editor/src/extensions/keys.js`，priority 200，先于 StarterKit 的 listKeymap）：

- `enterInList()`：显式 `splitListItem('listItem')`，保证同层级新建；
- `backspaceInList()`：光标在空项开头 → 计算上一个兄弟项最后一个文本块的结尾位置，删掉当前项（列表只剩它时连包装列表一起删），
  在该位置插入 `hardBreak` 并把光标放到它后面 → 视觉上就是上一项里多了一行（对应截图二）；
  再按退格时这里返回 false，由 ProseMirror 默认删除这个 hardBreak（对应截图三）；
- 保留了「刚用 `1. ` 输入规则变成列表时，第一次退格先撤销输入规则」的 Tiptap 约定。

回归：harness 增加 `list-keys` 探针（截图 `list-keys.png`），直接驱动两个处理函数并打印 HTML 中间态：
`<p>c</p> → Enter → <li><p></p></li> → 退格 → <p>c<br></p> → 再退格 → <p>c</p>`，与用户三张截图一致。
（JavaFX 里合成的 KeyboardEvent 触发不了 ProseMirror keymap，所以探针走 `window.__trellisKey` 这个调试入口；
真实键盘事件仍然由 ProseMirror 分发给同一套函数。）
### 7.10 全角/半角符号一致 + 转义收敛（2026-11-16 晚）

用户要求（原话）：「我希望看起来相同的符号在中英文下都能保证一致性」；「程序不要自行加转义符，
用户要解决少见的符号解析问题自己加转义符」。

**问题**：中文输入法下敲 `[[概念]]` 得到的是 `［［概念］］`（U+FF3B/U+FF3D），看起来一样但不算语法；
`＃` 标题、`－` 列表、`＞` 引用、`：：：ai`、`／` 斜杠菜单同理。另一头，序列化沿用 turndown 默认转义，
正文里随便一个 `[` `*` `_` 都会被写成 `\[` `\*` `\_`，用户打开 .md 看到一堆不是自己敲的反斜杠。

**设计三原则**：

1. **只在语法位置归一**：全角符号只有落在「标记位」（`［［…］］`、行首 `＃ `、`：：：ai` 整行等）才算语法；
   正文里的单个全角标点（「（见［1］）」）一个字符都不动。
2. **写回永远是半角**：同一篇笔记不会因为输入法不同出现两套符号；文件里只有一套用户看得懂的写法。
3. **不主动加转义**：序列化只保留「最小必要转义」——自定义方言 `[[`/`]]`/`{{`/`}}`（不转义下次载入会变成语法），
   以及行首会被当成列表/标题/引用的块级标记；用户手写的 `\[\[…\]\]` 原样保留（那是用户自己敲的转义）。

**落地分层**：

| 层 | 文件 | 做了什么 |
|---|---|---|
| Kotlin 载入 | `KnowledgeNote.kt` | 新增 `normalizeSyntaxSymbols()`（概念/AI 行内、`：：：ai`、`＃`/`－＊＋`/`＞`/`１．`/全角围栏；围栏代码块、`:::ai` 块、行内代码里一字不动），`parseNoteMarkdown` 解析完 frontmatter 后调用；因此编辑缓冲、概念 chips、Compose 退路都拿到同一套半角写法 |
| JS 载入 | `markdown.js` | `normalizeSyntaxLines()` 归一全角行首标记/围栏/`：：：ai`；概念与 AI 行内正则改成宽字符版；新增 `escapeMarkdownText()` 替换 turndown 默认 escape；`tidyEscapes()` 只清行内过度转义 |
| JS 输入 | `extensions/concept.js` / `ai.js` / `ai-block.js` / `fullwidth.js`（新）/ `ui/overlays.js` | 敲 `［［…］］` 直接变概念标注（含 `＝＞` 映射）、`｛｛…｝｝` 变 AI 绿字、行首 `：：：ai` 变 AI 块、`＃`/`－`/`＞`/`１．`/`｀｀｀` 走输入规则；`／` 开斜杠菜单、全角 `［［` 开概念补全 |
| 序列化 | `markdown.js` | 概念/AI 标记永远写半角；普通正文不再被转义；`[[…]]` 手动转义保留 |

**自检**：

- harness 夹具 18 `18-fullwidth-syntax` + 同名 `.expected.md` 做字节级断言（现在 19 个夹具；老夹具 00–13、15、17 的往返结果**一个字节都没变**，14/16 仍是已知归一）；
- harness 新增 `symbols` 探针：逐字符走 ProseMirror 的 `handleTextInput`（等同真实键盘），8 项全过——
  `concept` / `heading` / `bullet` / `quote` / `ordered` / `aiInline` / `aiBlock` / `code`；
- 新增 `KnowledgeSymbolNormalizationTest`（10 例：全角概念/混写/全角 AI/AI 块/块级标记/正文标点不动/围栏与行内代码不动/幂等/载入时归一）；
- `:app:ui:jvmTest` 全量通过。

**取舍 / 未做**：

- Compose 退路编辑器里**直接敲**全角不会当场归一，要等下次载入（默认编辑器是 WebView，输入规则已覆盖）；
- 全角行内强调 `＊粗＊`、全角链接括号/表格竖线仍不认（Markdown 标准保持 ASCII，只把自定义方言和块级标记做到一致）；
- 手动转义仍只认 ASCII 反斜杠写法（`\[\[…\]\]`），全角括号的手动转义没有额外支持；
- 程序仍会在「不转义就会变语法」的地方写反斜杠（`\[\[`、行首 `\#` 等）——这是保住字面意思的必要成本，不是给普通符号加转义。

### 7.11 标题语法显影 / 标题回车退格 / 正文标记重画（2026-11-19）

用户反馈三条：

1. **标题必须把内容删光才能变正文**——光标贴着隐藏的 `#` 时看不到它，也就没法「删掉它」；
   设计口径是「光标在语法旁边/块内能看到原始 Markdown，光标不在、焦点不在就是纯渲染态」；
2. **从标题行按回车，下一行还是标题**；
3. **正文块左边距那个 `¶` 太丑**，要画个好看的。

**落地**：

| 位置 | 改动 |
|---|---|
| `tools/web-editor/src/extensions/gutter.js` | 光标所在标题前挂一个**不可编辑的 widget** 显示 `#`×级别（`span.heading-syntax`，`contenteditable=false`、`pointer-events:none`）；焦点不在编辑器时 **gutter 和 `#` 一律不挂**（`editor.isFocused` 早退 + focus/blur 时手动踢一次事务重算 decorations）；标题不再画 `H1`/`H2` 徽标（`#` 已经说明级别）；正文块的 `data-marker` 从 `¶` 改成 `paragraph`，由 CSS 画小圆点 |
| `tools/web-editor/src/extensions/keys.js` | 新增 `backspaceInHeading`（光标在标题开头 → `setNode('paragraph')`，标题降级成正文、内容不动）；`enterInHeading`（标题末尾回车 → 在标题后插一个**空正文段落**并把光标放进去；标题中间回车 → `splitBlock` 拆成两条同级标题；空标题回车 → 直接变正文）；抽出 `handleEnter`/`handleBackspace` 两个入口，**键盘与 `window.__trellisKey` 走同一套**，避免调试钩子和真实按键漂移 |
| `tools/web-editor/src/main.js` | `window.__trellisKey` 改调 `handleEnter`/`handleBackspace` |
| `app/ui/src/jvmMain/resources/editor/index.html` | 新增 `.heading-syntax` 样式（等宽、0.58em、`#8e8e93` 50% 透明度、与标题拉开了 0.42em）；正文标记改成 6px 小圆点（`border-radius:50%`、`--fg-soft` 34% 透明度、对齐首行光学中心） |
| `app/ui/src/jvmTest/.../WebEditorHarness.kt` | 新增 `heading` 探针（10 项断言：末尾回车出新正文段 / 只留一条标题 / 开头退格降级 / 文本不丢 / 中间回车拆两条 / `#` 显影 / 光标离开消失 / 正文标记是 paragraph / 标题不挂 gutter 徽标）+ `heading-blur` 阶段（失焦后 `#` 与 gutter 都归零）；深色主题截 `heading-syntax.png` / `gutter-paragraph.png` |

**自检**（隔离 worktree `~/trellis-editor-verify`，避开另一个会话正在写的构建目录）：

- harness 19 个夹具 + 全角探针 + 列表按键探针 + 标题探针全过；`heading-blur: {"syntax":0,"gutter":0}`；
- 深色主题截图目检：标题前 `##` 是低调等宽灰、正文左侧是小圆点（不是 `¶`）。

**取舍**：

- 标题的 `#` 是**装饰**（widget），删它是通过「开头退格 → 降级成正文」实现的，不是真的删字符；
  这样文件里永远只有 `# 标题` 这一种写法，不存在「正文里出现裸 `#`」的中间态。
- 标题中间回车仍然拆成两条同级标题（和 Typora/大多数编辑器一致）；只有**末尾**回车才是新正文段。
- 列表项里的块不参与标题逻辑（schema 里标题不会出现在列表内）。

## 8. 落地清单（本轮改了哪些文件）

| 文件 | 改了什么 |
|---|---|
| `app/ui/.../MarkdownEdit.kt` | 新增 `LIST_INDENT`/`CODE_INDENT` + `indentMarkdownLines`/`outdentMarkdownLines`（评审意见二） |
| `app/ui/.../MarkdownDocumentEditor.kt` | 新增 `bindings` 参数；Tab/Shift+Tab 缩进分发（AI/概念/斜杠菜单的 Tab 认领优先）；快捷键分发改读生效键位（支持 Alt） |
| `app/ui/.../MarkdownKeys.kt`（新） | 17 条默认键位注册表、覆盖合成、解析/规整/捕获、菜单清单 |
| `app/ui/.../MarkdownKeyStore.kt`（新） | `<home>/markdown.json` 读写与容错 |
| `app/ui/.../MarkdownCommands.kt` | 删掉写死的 `markdownKeyBindings`，菜单快捷键清单改为按生效键位生成 |
| `app/ui/.../MarkdownEditor.kt` / `MarkdownEditorPlatform.kt` / `MarkdownEditorPlatform.jvm.kt` / `MarkdownEditorPlatform.android.kt` | 把 `editorMode`/`bindings` 透传到平台分派；桌面 `resolveMarkdownEditorMode` 钉优先级（环境变量 > 设置 > 默认） |
| `app/ui/src/jvmMain/.../MarkdownWebRuntime.kt`（新） | JavaFX 启动挪后台线程 + 超时 + `javafx.cachedir` + 失败态；`MarkdownEditorPlatform.jvm.kt` 据它回退 Compose（修「切段落式后假死」） |
| `app/ui/.../UiPrefs.kt` | 新增 `editorMode`（`ui.json` 落盘、`describe()` 带上） |
| `app/ui/.../AppRuntime.kt` | `markdownKeys`/`markdownBindings` 状态与 `updateMarkdownBinding`/`resetMarkdownBinding(s)`/`updateEditorMode` |
| `app/ui/.../KnowledgeScreen.kt` | 把 `runtime.uiPrefs.editorMode` 和 `runtime.markdownBindings` 传给编辑器与菜单 |
| `app/ui/.../SettingsScreen.kt` | 新增「编辑器」「Markdown 快捷键」两个区段（改键捕获、单条/全部恢复） |
| `app/ui/.../DataWipe.kt` | `markdown.json` 列入「清除数据时保留的配置」 |
| 测试 | `MarkdownIndentTest`、`MarkdownKeysTest`、`MarkdownEditorPlatformTest`、`MarkdownEditorKeyTest` 新增；`MarkdownCommandsTest` 扩展 |
| `app/ui/.../MarkdownWebEditor.kt` | 注释指向新的编辑器源码工程；修「首次打开不下发正文」（`applied` 初值改成 `""`，见 §7.3） |
| `tools/web-editor/src/main.js` | `setMarkdown` 后 `resetScroll()` 归零 `#editor` 的 scrollTop（修首屏正文被裁，见 §7.3） |
| `app/ui/build.gradle.kts` | jvmTest 增加 JavaFX 依赖（回归 harness 要在测试里真的 new WebView） |
| `tools/web-editor/`（新） | **WebView 编辑器源码工程**：Tiptap 3 + marked + turndown + esbuild；`pnpm run build` 写回 `resources/editor/editor.js`；含与 Kotlin 的 window API/bridge 契约、Markdown 方言、构建说明 |
| `app/ui/src/jvmTest/.../WebEditorHarness*.kt` + `resources/web-editor/fixtures/`（新） | JavaFX WebView 回归 harness：16 个 markdown 夹具 → 序列化/HTML/截图；`TRELLIS_WEB_HARNESS=1` 才跑 |
| `app/ui/src/jvmMain/resources/editor/editor.js` | 换成新源码工程的产物（旧 385KB 黑盒 → 新 ~496KB 源码构建）；修掉代码围栏/表格/任务清单/概念映射/AI 块/软换行等往返 bug |
| `app/ui/src/jvmMain/resources/editor/index.html` | 补表格、任务清单、AI 块样式 |
| `app/ui/.../UiPrefs.kt` / `MarkdownEditorPlatform.jvm.kt` / `SettingsScreen.kt` | 默认编辑器改成段落式（WebView）；兜底/文案同步（Compose 仍可在设置里选） |
| `tools/web-editor/src/markdown.js` | 新增 `normalizeListIndent()`（缩进不足的嵌套列表不被拍平）与 `tidyEscapes()`（清理 `\-`/`\_`/重复反斜杠等过度转义） |
| `app/ui/src/jvmTest/resources/web-editor/fixtures/16-nested-list-indent.md`（新） | 往返夹具：2 空格嵌套列表 + 行内代码后连字符 + 空格间下划线 |
| 文档 | 本文；`docs/README.md` 索引一行；`docs/knowledge-base-draft.md` §12/§13 补编辑器形态与 Tab/快捷键 |
| `tools/web-editor/src/markdown.js` | 全角行首标记/围栏/`：：：ai` 归一（`normalizeSyntaxLines`）；概念/AI 行内正则宽字符化；`escapeMarkdownText` 取代 turndown 默认转义（不再给 `[草稿]`/`2*3` 加反斜杠，只保留最小必要转义） |
| `tools/web-editor/src/extensions/concept.js` / `ai.js` / `ai-block.js` / `fullwidth.js`（新） / `ui/overlays.js` | 输入侧全角等价：全角概念/AI/标题/列表/引用/有序/围栏输入规则，全角 `／` 斜杠菜单、`［［` 补全、`＝＞` 分隔符 |
| `app/ui/.../KnowledgeNote.kt` | 新增 `normalizeSyntaxSymbols()`，`parseNoteMarkdown` 载入时把语法位置的全角符号归一半角 |
| `app/ui/src/jvmTest/.../KnowledgeSymbolNormalizationTest.kt`（新） | 10 例符号一致性测试（全角/混写/围栏与行内代码不动/幂等/载入归一） |
| `app/ui/src/jvmTest/.../WebEditorHarness.kt` + `resources/web-editor/fixtures/18-fullwidth-syntax(.expected).md`（新） | harness 支持 `*.expected.md` 字节级断言 + `symbols` 逐字符输入探针（8 项） |
| `app/ui/src/jvmMain/resources/editor/editor.js` | 重新构建（全角输入规则 + 新转义策略） |
| 文档 | 本文 §7.10；`tools/web-editor/README.md` 补「全角/半角一致」「转义策略」两节；`docs/knowledge-base-draft.md` §12 补一句 |
| `tools/web-editor/src/extensions/gutter.js` / `keys.js` / `main.js` | 标题语法显影（`#` widget，仅焦点在编辑器且光标在标题内）、标题开头退格降级成正文、标题末尾回车新起正文段落、失焦后 gutter 与 `#` 全消失；`__trellisKey` 与键盘共用 `handleEnter/handleBackspace` |
| `app/ui/src/jvmMain/resources/editor/index.html` | `.heading-syntax` 样式；正文块标记从 `¶` 改成 CSS 小圆点 |
| `app/ui/src/jvmTest/.../WebEditorHarness.kt` | `heading` 探针（10 项断言）+ `heading-blur` 阶段 + 深色主题截图 `heading-syntax.png`/`gutter-paragraph.png` |
