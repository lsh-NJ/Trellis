# 知识库讨论稿（插件化计划第 2 条）

> 对应 [pluginization-plan-2026-10-29.md](pluginization-plan-2026-10-29.md) 第 2 条「学习终端与知识体系」。
> 第 1 条「计划自己推进」的讨论稿是 [plan-rhythm-draft.md](plan-rhythm-draft.md)，两篇**解耦**，互不前置。
> 状态：讨论稿（2026-10-29）；P0 已落地（2026-10-30 两批 + 2026-10-31 第三至七批，见 §10）：
> 笔记 + Markdown 编辑器（行内编辑/行外预览 + 编辑态行内 WYSIWYG，§12）+ 概念抽取/点亮/熄灭/追溯/搜索 + 空位补全与 AI 内容标记（§11）+ 导出当前笔记为独立 Markdown。
> 未做：导入外部 Markdown、别名/合并、sdk-domain 契约与 `:features:knowledge` 拆模块（§9 记录了取舍）。

## 0. 一句话

知识库 = **可搜索的个人概念索引 + Markdown 笔记**。笔记里出现过的概念自动点亮，点能跳回原笔记。
不做掌握度、不做复习调度、不画全局知识图。

## 1. 为什么这样定义

- 用户不需要当硬盘：忘记是常态，知识库的价值是**随时可查、可追溯**，不是考试评分。
- 所以「点亮」不是成就，而是索引状态：这个名词我记过、记在哪。它由笔记派生，不靠勾选。
- 上一轮调研结论（[tech.md](research/tech.md)、[learning.md](research/learning.md)）：PKM 有图但没有掌握度，Khan/ALEKS 有掌握度但没有笔记，两边的整机都不适合照抄——**只借「概念 + 出处」这个数据形态**。
- 计划负责「做什么」，知识库负责「记过什么、在哪」；两者先各自跑通。

## 2. 用户模型：三个对象

| 对象 | 是什么 | 存哪 |
|---|---|---|
| 概念 Concept | 名词，可带别名、一句话定义 | 索引库 |
| 笔记 Note | Markdown 正文，**唯一文本真相** | 文件 |
| 引用 Mention | 概念出现在哪篇笔记的哪个片段 | 笔记 frontmatter + 索引库 |

- **点亮** = 概念至少有一条引用；删掉所有引用自动**熄灭**。
- **追溯** = 概念 → 引用（笔记 + 命中片段）→ 打开笔记并定位。这是知识库交付的主要价值。
- 概念只从笔记产生，不单独维护「空概念」；概念可跨多篇笔记出现。

## 3. 存储：文件是真相，库是索引

- 笔记文件：`<home>/knowledge/notes/<id>.md`，frontmatter 记 `title/created/updated/concepts`。纯 Markdown，可备份、可导出、可被外部工具读。
- 概念定义：`<home>/knowledge/concepts.json`（`{"concepts":[{"name","definition"}]}`），只存「用户可改的补充信息」，丢了不影响点亮。
- 索引：**不建 knowledge.db**，`KnowledgeStore.conceptIndex/conceptDetail/search` 每次从文件现算——个人知识库几百篇的量级，扫目录比维护一致性便宜，且「删库重建一致」自动成立。
- 一致性：概念名记在笔记 frontmatter 的 `concepts:` 里，删掉索引也能从这些文件重建，**不用重新调 LLM**；命中片段与位置由正文重算，概念定义/别名可再补。与 plan.db「事件是真相、投影可重建」同一思路。
- 时间戳走注入时钟（[time-conventions.md](time-conventions.md)）。

## 4. 概念从哪来（AI 抽取，错了可删）

1. **保存后抽取**（防抖、后台）：LLM 抽候选概念 + 别名 + 一句话定义；失败降级为「未抽取」，不阻塞保存。
2. **直接点亮、可删**：抽到的概念立即写引用（笔记 frontmatter + 索引；不逐步确认——点亮只是索引、不是断言，错了删掉成本低）；编辑器下方 chips 显示「本笔记涉及：RAG ×、向量检索 ×、HNSW ×」，可删、可改名、可手加。
3. **手动兜底**：选中文本 →「标为概念」；未配 LLM/离线时全走这条。
4. **纠错补全**：AI 给规范化名、别名、定义建议，**只建议不改原文**。
5. **串联**：相关概念按「同笔记共现」现算，v1 不落关系表；先修/上下位关系以后再说。
6. **导入外部笔记**：选目录把 `.md` 内容复制成 `knowledge/notes/<id>.md`（`source` 记原路径），走同一抽取通道；按「原路径 + 内容 hash」去重。

## 5. 交互：三件套，先不画图

- **知识页**（一级页面）：左侧概念列表（搜索/最近更新/全部），右侧概念详情 = 定义（AI 补全、可改）+ 来源笔记（标题 + 命中片段，点击跳转定位）+ 相关概念（共现）。
- **笔记页**：笔记列表 + Markdown 编辑器（编辑/预览切换；预览复用 [MarkdownText.kt](../app/desktop/src/main/kotlin/dev/trellis/app/desktop/MarkdownText.kt) 的渲染）+ 底部概念 chips。
- **搜索**：概念名/别名/笔记全文，结果分「概念 / 笔记」两组。这是「随时来查」的主入口，优先级高于一切展示。
- **为什么不画图**：现有 UI 是 12 栏卡片 + 挂载点体系，力导向图塞不进去；查东西靠搜索/列表，不靠看图。真要做图，后续放在知识页内用宿主 Canvas 画「局部图」（当前概念 ± 1 跳），不进插件卡片词汇表。
- 导航入口已定（2026-10-30）：一级页「知识库」，导航第三项（任务 / 计划 / 知识库 / 对话 / 活动 / 设置）。

## 6. 和计划的关系（回答「计划闭环」）

- **不是创建时强制关联**。v1 不关联：点亮只由笔记决定；计划节点完成**不**点亮概念（「执行过」≠「记过」，避免把两件事混成一件）。
- 只预留字段 `plan_unit.concept_ids`（可空），等知识库跑顺再决定怎么用。
- 以后可选（P2，不是 v1）：创建计划时 AI 建议概念标签（可忽略）；从「久未查看 / 只出现过一次」的概念生成复习计划。
- 结论：闭环不是前置条件，第 1 条与第 2 条各自先跑通。

## 7. 明确不做

- 不做掌握度、正确率、遗忘曲线、复习调度（这些要证据链，本轮明确砍掉）。
- 不做全局知识图、白板、双链渲染；不做打卡/经验值/连续天数。
- 不做多用户、共享、云同步；本地单机，导入/导出文件即可。
- 不做完整 PKM：块引用、折叠、插件生态都不在 v1。
- 不做产品指标（用户明确不要）；只留工程测试：索引重建一致、抽取不阻塞保存、导入去重。

## 8. 分阶段与验收

**P0 最小可用**：笔记文件 + Markdown 编辑器（编辑/预览）+ 保存后抽取（默认直接点亮、可删）+ 点亮/熄灭 + 概念详情追溯 + 全文/概念搜索 + 知识页。

**P1**：导入外部 Markdown、概念别名/合并、相关概念（共现）、对话页「存为笔记」。（AI 补全定义已在 P0 第二批落地。）

**P2**：局部图实验、计划↔概念关联、插件扩展点（导入器/增强器/复习提供者）。

**P0 验收**：写一篇含 3 个概念的笔记 → 知识页亮 3 个、都能跳回命中处；删掉该笔记 → 3 个熄灭；重启/删索引 → 从文件重算一致（索引不落盘，没有「重建」命令）；概念详情能列出所有来源笔记并定位到命中片段。

**工程落点（P0）**：
- `sdk-domain/.../knowledge/Contract.kt`：`KnowledgeService`（概念/笔记/引用/搜索）+ 命令，风格照 [plan/Contract.kt](../sdk-domain/src/commonMain/kotlin/dev/trellis/domain/plan/Contract.kt)。
- `:features:knowledge`：JVM SQLite 实现 + Markdown 文件读写；不引第三方编辑器。
- required 插件 `core.knowledge`：注册服务/数据提供者/AI 动作（照 `features/plan-core`）。
- `app/desktop/KnowledgeScreen.kt`：宿主一级页面（不进 UiNode 卡片）。
- AI 抽取走现有 `LlmClient`，JSON 校验 + 重试（[tech.md](research/tech.md) §5.2）；补 `search_knowledge` / `save_note` 工具。

## 9. 已拍板（2026-10-30 实施时按建议默认执行）

1. **入口**：导航第三项「知识库」（任务 / 计划 / 知识库 / 对话 / 活动 / 设置）。
2. **抽取时机**：保存后自动（停手 2.5s 防抖）+ 结果默认直接点亮、chips 可删；失败不阻塞保存；没配模型则跳过，可手动添加。
3. **外部笔记导入**：复制进 `knowledge/`（可重建、原目录变动不影响）——**待做**，P1。
4. **概念定义**：AI 补全 + 用户可改（存 `concepts.json`）；AI 只填空定义、不覆盖用户改过的文本。
5. **别名/合并**：P1 再做；现在靠 `conceptKey`（大小写不敏感）去重，不做别名表。
6. **拆模块**：`sdk-domain` 契约 + `:features:knowledge` 是 P1 的重构项；P0 先放 `app/desktop`，好处是不碰内核、能快速验证交互，代价是 AI 工具/插件暂时拿不到知识库服务。

## 10. 落地进度

**已落地（2026-10-30，P0 第一批：先能写字、能找回）**

- 导航第三项「知识库」：`app/desktop/src/main/kotlin/dev/trellis/app/desktop/KnowledgeScreen.kt`（笔记列表 + 标题 + 编辑器）；`TRELLIS_TAB=knowledge` 可直接进入。
- 存储：`<home>/knowledge/notes/<id>.md`，Markdown 正文 + frontmatter `title/created/updated`；`KnowledgeStore.kt`（读写/列表/删除）与 `KnowledgeNote.kt`（frontmatter 解析/格式化，未知字段原样保留）。
- Markdown 编辑器：`MarkdownEditor.kt`（工具栏 H1/H2/粗体/斜体/代码/删除线/列表/编号/引用/链接/代码块/表格、Ctrl/Cmd+B/I/K、回车续列表、编辑/预览切换，预览复用 `MarkdownText.kt`）。**工具栏已于 2026-10-31 撤掉，格式入口改成斜杠菜单 + 快捷键，见下文第三批。**
- 交互：新建/重命名（改标题字段）/删除（确认弹窗）/标题搜索/600ms 防抖自动保存 + 离开页面兜底保存；底部显示字符数与保存时间。
- 测试：`KnowledgeStoreTest.kt`（frontmatter 往返、列表排序、删除、无 frontmatter 导入、非法 id 防穿越）、`MarkdownEditTest.kt`（工具栏/快捷键纯函数）；`:app:desktop:test` 全绿。

**已落地（2026-10-30，P0 第二批：概念抽取/点亮/追溯/搜索）**

- 点亮存 frontmatter：`concepts:` 一行（`KnowledgeNote.kt` 的 `splitConceptList/joinConceptList`；概念名去重按 `conceptKey` 大小写不敏感）。
- 索引不落盘：`KnowledgeStore.conceptIndex()`（篇数/命中次数）、`conceptDetail()`（按最近更新排来源、正文命中偏移 + 行级片段、同笔记共现的相关概念 top 8）、`search()`（概念名 + 笔记标题/正文）每次从 `.md` 现算。
- 定义：`ConceptMetaStore.kt` → `<home>/knowledge/concepts.json`，编辑 600ms 防抖写入；AI 只填空定义（`fillMissing`），不覆盖用户改过的文本。
- 抽取：`KnowledgeExtraction.kt`（提示词 + 严格 JSON 解析 + 去重合并）+ `KnowledgeScreen.kt` 里保存后停手 2.5s 自动跑；跳过空正文/<40 字/正文没变/没配模型；失败只提示、不影响保存；结果默认点亮，chips 上 × 熄灭。
- 界面：`KnowledgePanels.kt`（笔记/概念两个 tab、概念列表、概念详情（定义编辑/来源定位/相关概念）、笔记底部概念 chips、搜索分组结果）；详情「定位」通过 `MarkdownEditor` 的 `reveal/revealToken` 选中命中片段。
- 测试：`KnowledgeConceptTest.kt`（13 个用例）；`:app:desktop:test` 240 tests 全绿。

**已落地（2026-10-31，P0 第三批：空位补全 + AI 内容标记）**

- 槽位：正文写 `标签：。。。`（行尾全角三个句号，可带 `- ` 前缀）表示「这里让 AI 补一句话」；只认行尾、代码块与 `:::ai` 块内不认、一篇最多 3 个。
- 一次调用同时拿概念与补全：保存后仍走 2.5s 防抖（`KnowledgeScreen.kt`），`KnowledgeExtraction.kt` 输出 `{"concepts":[…],"fills":[…]}`；旧版纯概念数组仍能解析。
- 写回：只替换 `。。。` 为 `{{补全内容}}`，其它字不动、幂等；补上的标签自动点亮并顺手填空白定义（`ConceptMetaStore.fillMissing`）。
- 渲染：`MarkdownText.kt` 把 `{{…}}` 画成一对绿色大引号（约正文 2 倍，主题 `accentText`/`successContainer`；不是字符），`:::ai` … `:::` 块画成带「AI 补充」小标的淡绿块，每行右侧 × 可删（`removeAiBlockLine`，块空了连标记一起删）。
- 概念高亮：预览里概念名统一走 `ConceptMatch.kt` 的 `conceptOccurrences/conceptHits`（ASCII 词边界，`RAG` 不命中 `GraphRAG`），与索引/详情同一条规则。
- 测试：`KnowledgeAiMarkTest.kt`（槽位识别/写回/幂等/删除/解析/渲染 23 个用例）+ `KnowledgeConceptTest` 扩充；`:app:desktop:test` 全绿。

**未落地（按 §8 继续）**：`sdk-domain` 契约与 `:features:knowledge`、AI 工具（`search_knowledge` / `save_note`）、导入外部 Markdown、概念别名/合并、对话页「存为笔记」。

**已落地（2026-10-31，P0 第四批：编辑器手感）**

- **工具栏撤掉**：格式不再摆一排按钮（十几种语法摆不下、也记不住）。入口只剩两个，都在 `MarkdownCommands.kt`：
  1. 斜杠命令菜单：行首或空格后敲 `/`，打几个字过滤（标签、id、拼音首字母都认），↑↓ 选、回车插入、Esc 关；16 条命令覆盖标题 1–3/正文/有序无序任务列表/引用/分割线/代码块/表格/粗斜体删除线行内代码/链接/图片。代码块里敲 `/` 不触发（写路径不会误弹）。
  2. 快捷键：`Ctrl/Cmd+B/I/K`、`Ctrl/Cmd+Shift+X/C/L/O/Q/R/T`、`Ctrl/Cmd+0/1/2/3`。键位只在 `markdownKeyBindings` 定义一次，键盘分发和「快捷键清单」都读它，不会出现「说明书和实际按键不一致」。
- **快捷键清单**（`Ctrl/Cmd+/` 或编辑器右上角「快捷键」）：编辑器 + 页面（`Ctrl/Cmd+S/N/E`）两段；`/`、↑↓、回车续列表、Esc 也写在里面。
- **右侧目录**（`MarkdownOutline.kt`）：按 `#`~`######` 生成树（围栏代码块里的不算），分级折叠、全部收起/展开、可收起；中间拖拽条改宽度，上下限 180–420dp（`MarkdownSidebarState`，切页回来宽度还在）。点标题跳转：编辑态把光标挪到标题行（输入框会把它滚进可视区），预览态按渲染块的实测坐标滚动（`MarkdownText(onBlockLayout)`）。
- **Markdown 渲染补齐**（`MarkdownText.kt`，会话页和笔记预览共用）：分割线 `---` / `***` / `___`（`- - -` 也认，但不会把 `- 列表项` 吃成横线）、任务清单 `- [ ]` / `- [x]`（方框自绘，避免 `☑` 落到彩色 emoji 字体）、图片 `![说明](url)`（不联网取图，显示成「［图］说明 (url)」）、`***粗斜体***`；标题改成 `#` 后必须真有文字，**目录和预览共用同一套标题规则**（否则第 N 条目录会跳到第 N+1 个标题）。
- **标题字号改成独立阶梯**：原来直接借 `MaterialTheme.typography` 的 `titleMedium`(16sp) / `titleSmall`(14sp) / `labelLarge`(14sp)，结果一级标题和正文一样大、二级标题比正文还小，**加粗正文反而压过标题**（2026-10-31 反馈）。现在 `markdownHeadingStyle(level, base)` 按正文字号乘系数算：1.50 / 1.31 / 1.16 / 1.10 / 1.04 / 1.00，保证「严格递减 + 任何一级不小于正文」，系数乘在传入的正文样式上，所以主题缩放和用户字号偏好都会跟着走；标题上方留白也按级别给（14/10/8/6dp）。
- 测试：`MarkdownCommandsTest`（14：触发/过滤/插入/键位唯一性/清单完整性）、`MarkdownOutlineTest`（7：层级/偏移/围栏/折叠）、`MarkdownEditTest`（+6：分割线独占一行、任务清单、正文去标题、图片）、`MarkdownTextTest`（+7：横线、`***`/`___`、任务清单、图片、空标题兜底、标题字号阶梯）；合入 main 的 AI 标记用例后 `:app:desktop:test` **298 个用例全绿**。

**已落地（2026-10-31，P0 第五批：行内编辑 / 菜单 / 侧栏）**

- **行内编辑、行外预览**（`MarkdownBlockEditor.kt` 新增）：正文按块切分（段落 / 标题 / 列表 / 引用 / 代码围栏 / 表格 / `$$` 公式块 / `:::ai` 块），默认每块用 `MarkdownText` 渲染；hover 浮淡底、点一下该块变成 `BasicTextField` 显示原始 Markdown，Esc / Ctrl+Enter / 失焦退出——不是「整篇切编辑态」，代码、公式、表格这类特殊块只在光标进去时看到源码。切分可逆：块间空行只是间隔，`spliceBlockRange` 只替换被编辑的那块，不吞间隔字节；未闭合围栏到文末。公式：`$$…$$` 独占块、行内 `$…$` 等宽斜体（`MarkdownText.kt` 新增 `MdBlock.Math` 与行内 math 组）。
- **菜单**：原「快捷键」chip 改名「菜单」（`MarkdownEditor.kt` 的 `MarkdownMenuSheet` / `MarkdownMenuOverlay`）；每行**动作在前、按键在后**，按键按平台显示（macOS `⌘`/`⇧`，其它 `Ctrl`/`Shift`，`platformShortcutLabel`），快捷键本身用行级代码样式（mono + 浅底圆角 `ShortcutKeyCap`）；点浮层外任意处关闭（页面级半透明遮罩），不强制 Esc。
- **页面布局**（`KnowledgeScreen.kt`）：页头固定，右侧「编辑/预览」「目录」「菜单」三个 chip；「新建笔记」从黑色大按钮改成左栏顶部的描边按钮；左栏（264dp）可收起成 36dp 窄条（‹/› 切换），右栏可收起（`MarkdownSidebarState.visible`，目录 chip 或目录面板 × 控制）；主区是**单一纵向滚动**：标题输入框 + 正文块编辑器一起滚，页头与左右栏不动；底部固定状态行（字符数 / 已保存 / AI 整理中）。
- **两处概念分开**：右栏「本篇概念」（`KnowledgePanels.kt` 新增 `NoteConceptPanel`）= 当前笔记的 chips + 添加/删除，点 chip 打开概念详情；左栏「概念」tab 仍是全局概念列表（N 篇 · M 次 · 时间）。概念详情沿用「来源」列表点击跳回命中片段，「常一起出现」chips 点开相邻概念——这是当前版本的「关联网络」，不做全局知识图。
- **概念高亮与标题清洗**：概念名从「绿色文字」改成**浅绿下划线**（`conceptStyle`：`conceptGreen.copy(alpha = 0.62f)` + `TextDecoration.Underline`；Compose 没有独立下划线颜色，下划线跟随文字色，所以整段用浅色）；笔记列表和目录里的标题去掉行内标记（`KnowledgeNote.stripInlineMarkdown`：`**加粗**`、`[文字](url)`、`~~删除~~` 等），目录行按 Markdown 渲染行内语法（不再显示原始 `*`）。
- 测试：`MarkdownBlockEditorTest`（22：切块可逆 / 围栏 / 表格 / 公式 / 斜杠命令 / 定位块）+ `MarkdownCommandsTest`（平台化按键、动作在前）+ `MarkdownTextTest` / `KnowledgeAiMarkTest` 适配新样式；`:app:desktop:test` **337 个用例全绿**。

**已知缺口**：编辑区无撤销/重做快捷键；手动删掉的概念，在同一篇笔记下次内容变化触发重抽时可能被 AI 再加回（`extractedBodies` 只防同内容重复抽取）；概念「定位」是选中片段，不是高亮滚动；窗口很窄时（< ~700dp）左侧笔记列 + 目录会挤掉编辑区，需要手动收起目录；AI 补全会改正文——用户正在打字时（保存后 2.5s 内）才同步回编辑缓冲，否则只落盘不覆盖编辑区（见 `applyExtracted` 的注释）。

**已落地（2026-10-31，P0 第六批：导出 Markdown）**

- 页头「导出」chip（`KnowledgeScreen.kt`）：把当前笔记（含还没落盘的编辑缓冲）导出成独立 `.md`。导出文件不带 frontmatter；`{{…}}` 去掉花括号、`:::ai` / `:::` 围栏行删掉（块里的内容保留）、fenced code 里一字不动；正文第一个非空行已经是同名标题时不再重复加 `# 标题`。
- 纯函数在 `KnowledgeNote.kt`：`exportNoteMarkdown`（拼标题 + 清洗正文）、`cleanAiMarkersForExport`（逐行还原 AI 标记）；落盘与文件名在 `MarkdownExport.kt`：`markdownExportFileName`（清掉 `/:*?"<>|` 等非法字符、空标题用「无标题笔记」）、`chooseMarkdownExportFile`（AWT `FileDialog` 系统保存面板，没写扩展名自动补 `.md`）、`writeMarkdownExport`（UTF-8，无 BOM）。用户取消不报错；失败在页头消息区显示「导出失败：…」。
- 测试：`MarkdownExportTest`（8 个用例：标题只出现一次 / 去 frontmatter / `{{…}}` 还原 / `:::ai` 围栏去掉 / 代码块原样 / 文件名清理 / 正文保持 Markdown）。

**已落地（2026-10-31，P0 第七批：编辑态行内 WYSIWYG）**

- 起因：用户报「编辑时看到裸 `**`、好像没加粗」。实测渲染态一直正常（像素 ink 对照：加粗 ASCII +40%、CJK +12%），真正的问题是块编辑态直接显示原始 Markdown。
- 做法参考 marktext/Muya：**行内标记只在光标进入该 token 时显示**，离开即折叠、只留样式。保留块模型（代码/公式/表格/AI 块需要独立渲染），不重写成 contenteditable。
- 新增 `MarkdownInlineEdit.kt`（纯逻辑，`projectInlineEdit(raw, selection, kind): InlineEditProjection`）：隐藏 `**`/`*`/`` ` ``/`~~`/`$`/链接的 `[](url)`、标题的 `# ` 前缀、引用的 `> `、列表的 `-`（换成 `•`，1:1 保长度）；`InlineMarkKind` 给 Bold/Italic/Code/Strike/Link/Math；`headingLevel`/`isQuote` 决定整段字号与颜色；`InlineEditMapping` 给出 raw↔显示 的 `OffsetMapping`（隐藏区 offset 落到区段起点，光标不会跳进标记里）。`MarkdownText.kt` 的 `inlinePattern` 改为 `internal` 复用同一份正则。
- `MarkdownBlockEditor.kt` 接线：活动块 `BasicTextField` 加 `visualTransformation`，标记按样式渲染（Bold/Italic/Mono/LineThrough/Link 下划线/Math mono 斜体），光标进入的 token 还原成暗灰标记；折叠态 Backspace/Delete 在 token 边界按内容字符删，不吞隐藏的 `*`；标题前缀删掉后块自动退回段落。
- **回车分段**：段落/标题/列表/引用里按回车会在此处切开，新建一个同类型块（列表自动续 `- `），`startNewBlock` 只插一个空行；空块需要渲染占位编辑框，`renderBlocks` 按位置插影子块（不能追加到文末）。
- 测试：`MarkdownInlineEditTest`（16 用例：标记隐藏/揭示规则、选区揭示、紧挨 token 也揭示、每种行内样式、heading 前缀与级别、bullet 替换、task 保留、quote 前缀、特殊块恒等、未闭合标记、mapping 往返、光标不落进隐藏区）；`:app:desktop:test` **361 个用例全绿**。

**已落地（2026-11-01，P0 第七批补丁：粗体/行内代码/编辑块外观）**

- **中文粗体**：macOS 中文回退字体（PingFang SC）最粗只有 Semibold，`FontWeight.Bold` 只让笔画粗 ~12%，肉眼几乎看不出。改成「系统字重 + 同色、右偏 2.2px、不模糊的阴影」叠一层，笔画实际加宽约 1px：实测 CJK ink 从 378 提到 570（**+51%**，和 ASCII 的 +40% 接近）。样式在 `MarkdownText.kt` 的 `markdownBoldStyle(textColor)`，渲染态与编辑态共用；`inlineMarkdown` 新增 `textColor` 参数（默认 `Color.Unspecified` 不画阴影，纯函数单测不受影响），调用点按正文/引用/表格传主题色。
- **行内代码**：`markdownCodeStyle()` = 等宽 + 半透明灰底（`MarkdownCodeBackground = Color(0x2E9E9E9E)`，暗色下提亮、亮色下压暗），预览和编辑态一致；Compose 的 `SpanStyle` 背景没有圆角，先接受方角。
- **编辑块外观**：活动块去掉 1dp 边框，只留 `surfaceVariant` 10% 的淡底（保持 8/4 padding，进入编辑时文字不跳动），贴近 marktext 的「文字本身在编辑」的观感。
- **揭示规则放宽**：光标在 token 内**或紧挨着 token 前后一格**都显示源码（`selection.start in maxOf(0, token.start - 1)..token.end`），输入完 `**粗**` 光标还贴在闭标记后面时能继续改；离开一格即折叠。

**已落地（2026-11-01，P0 第七批补丁 2：点哪落哪 + 链接）**

- **点哪落哪**：点渲染态块时不光进入编辑，光标还会落在点击的那个字上。做法在 `MarkdownBlockEditor.kt` 的 `caretOffsetForTap`：用 `rememberTextMeasurer` 按预览样式把「隐藏标记后的文本」量一遍（标题用标题字号、引用用引用字号），`getOffsetForPosition` 得到显示文本偏移，再经 `InlineEditMapping.transformedToOriginal` 回到原始 Markdown 偏移；预览里的列表标记列（18dp）和引用竖线（10dp）、标题上方留白都从坐标里减掉。边界情况与限制：嵌套列表缩进、表格/代码/公式/AI 块这类非文本块的落点只做近似。
- **链接**：知识库预览只显示 label（`[示例链接](https://example.com)` → 「示例链接」，链接色）；点进块、光标进入/紧挨链接 token 时才展开完整 `[label](url)` 源码，和 marktext 一致。实现给 `inlineMarkdown`/`MarkdownText` 加了 `showLinkUrl` 参数（默认 true，聊天消息仍显示地址，模型给的链接才复制得走），知识库预览、块渲染、目录树都传 false。
- 测试：`MarkdownTextTest` 新增 `linksHideTheUrlWhenThePreviewOnlyWantsTheLabel`；`:app:desktop:test` 全绿（362 个用例）。

**已落地（2026-11-01，P0 第七批补丁 3：标题源码 / 粗体色 / 代码芯片 / `[[概念]]`）**

- **编辑态标题能看到几级**：`## ` 不再按「光标是否进前缀」折叠，编辑标题块时前缀始终以暗灰源码显示（引用块的 `> ` 同理）。字号仍按级别放大，所以「几级」和「内容」一眼可分。
- **粗体不改字色**：`markdownBoldStyle` 的阴影原来固定用 `textColor`；概念绿字上如果文字色被传成主题色，粗体就会发黑。现在 `appendStyledRange` 里的 `boldShadowColor` 会检查粗体范围是否与概念命中重叠，重叠时用 `conceptColor(conceptGreen)`（同色，alpha 0.62），预览截图实测粗体 RAG 与普通概念名的绿色像素都是 `(130,145,89)` 一类，没有黑色描边。
- **行内代码改成圆角芯片**：`SpanStyle` 的 background 画不了圆角，改成自己画：`buildInlineMarkdown` 记录 `codeChips: List<IntRange>`（显示文本坐标），渲染用 `DrawScope.drawInlineCodeChips(layout, ranges, …)` 按行画 `RoundRect`（半径 4dp、左右各 3dp、上沿基线 −0.80em、下沿基线 +0.24em）；预览与编辑态（`BasicTextField.onTextLayout`）共用同一份绘制。`markdownCodeStyle()` 只留等宽字体，不再设背景。
- **概念改成语法**：正文里写 `[[概念名]]`，预览只渲染名字（浅绿 + 下划线），编辑态与 `**`/`` ` `` 一样折叠标记、光标进入/紧挨才显示 `[[ ]]`。`inlinePattern` 增加 `concept` 命名组，`InlineMarkKind` 增加 `Concept`，`MarkdownInlineEdit` 出对应 token；`inlineMarkdown` 的概念高亮（按概念表匹配）保留，两者可以同时工作。
- **AI 抽取提高门槛并自动打标**：`KnowledgeExtraction` 的提示词改成「只抽作者确实解释过、或与空位补全直接相关的概念；名字必须逐字出现在正文里；最多 6 个、宁缺勿滥；定义 ≤40 字」（`MAX_EXTRACTED_CONCEPTS` 8 → 6）。抽取写回正文前先跑 `KnowledgeNote.markConceptMentions(body, names)`：跳过代码围栏/`:::ai` 块、跳过已在 `[[…]]`/`{{…}}` 里的位置，把每个概念名第一次出现包成 `[[name]]`；找不到就跳过。
- **点块「按下即落点」**：`BlockPreview` 的手势从 `detectTapGestures`（等抬起）改成 `awaitEachGesture + awaitFirstDown`：按下就把光标放到点击位置。等 tap 的写法在「一块已在编辑、去点另一块」时会因为焦点/重组把点击取消，表现为第一下没反应；按下即落点后单击即可换块。
- 测试：`KnowledgeAiMarkTest`（自动标记的首次命中/跳过代码与 AI 块/不嵌套、`[[…]]` 只渲染名字、抽取提示词门槛）、`MarkdownInlineEditTest`（概念标记折叠与揭示）、`MarkdownTextTest`（`codeChips` 记录、粗体阴影跟随文字色）；`:app:desktop:test` **371 个用例全绿**。

## 11. AI 补全与 AI 内容标记（2026-10-31 定稿）

三种标记都写在正文里、明文可见、可编辑、可删除；渲染只在预览层发生，**不把大引号字符写进正文**（普通 `“ ”` 照常渲染，两者在颜色/字号上就能区分）。

| 用途 | 用户/模型写法 | 预览渲染 | 删除方式 |
|---|---|---|---|
| 留空位 | 整行 `标签：。。。` | 仍是明文（等待补全） | 删掉这一行 |
| 行内 AI 内容 | `{{一句话内容}}` | 绿色大引号包住内容 | 编辑态删掉 `{{ }}` 那段 |
| 块级 AI 内容 | `:::ai` 换行写多行、再 `:::` | 淡绿块 +「AI 补充」+ 大绿引号 +「×」 | 预览里点行尾 ×（`removeAiBlockLine`） |

约定细节：

- **只建议不改原文**：槽位补全只替换行尾那一个 `。。。`；AI 内容永远包在标记里，用户随时能删。删除后不会自动写回（没有 `。。。` 就不会再补）。
- **失败保留原状**：模型没返回/解析失败时，槽位原样留着并提示「AI 补全失败，空位原样保留」；概念抽取失败同理，不影响保存。
- **上限**：一次最多 3 个槽位（`MAX_AI_FILL_SLOTS`）、单条 ≤80 字（`MAX_AI_FILL_CHARS`）、概念最多 6 个（`MAX_EXTRACTED_CONCEPTS`，宁缺勿滥）。防止「一整屏空位」让模型自由发挥。
- **谁的定义归谁**：`concepts.json` 是索引（概念详情用），正文 `{{…}}` 是内容；同一概念的补全内容会顺手填空定义，但正文不被定义覆盖。
- 概念语法 `[[概念名]]`：抽取后自动把正文里第一次出现的名字包起来（`markConceptMentions`），也可以手写；预览只显示名字，编辑态折叠标记，见 §12。
- 暂不做：行内 `{{…}}` 的 ×（v1 在编辑态删）；块级 `:::ai` 的多段结构化展示。


## 12. 编辑器交互（2026-10-31 定稿）

目标：写笔记时**不用切模式**。光标在哪一块，哪一块就是源码；其余块保持渲染。页面只留一个滚动区，标题跟着正文走。

| 交互 | 行为 | 落点 |
|---|---|---|
| 点正文块 | 该块进入编辑态，光标落在点击位置（marktext 式「点哪落哪」）；行内 WYSIWYG：`**`/`~~`/反引号等标记收起，只显示样式 | `MarkdownBlockEditor.kt` / `MarkdownInlineEdit.kt` |
| 光标进入/紧挨行内标记 | 该 token 的原始标记还原显示（暗灰），可编辑；光标离开一格即折叠（`[[概念]]`、`[链接](url)` 同规则） | 同上 |
| 编辑标题/引用块 | `## ` / `> ` 前缀始终以暗灰源码显示（能看出几级标题），字号按级别放大 | `MarkdownInlineEdit.kt` |
| 链接 | 渲染态只显示 label（不显示地址）；光标进入/紧挨链接时展开 `[label](url)` 源码 | `MarkdownText.kt` / `MarkdownInlineEdit.kt` |
| 回车 | 在当前块内切开，新起一个同类型块（列表自动续 `- `；Shift+Enter 是块内软换行；代码/公式/表格/AI 块里回车仍是换行） | `MarkdownBlockEditor.kt` |
| Esc / Ctrl+Enter / 失焦 | 退出当前块，回到渲染态 | 同上 |
| 块内 `/` | 行首或空白后唤起斜杠命令菜单（代码块里不触发） | `MarkdownCommands.kt` |
| 块间快捷键 | `Ctrl/Cmd+B/I/K`、`Shift+X/C/L/O/Q/R/T`、`0–3` 只作用当前块 | 同上 |
| 编辑 / 预览 | 页头 chip 整体切只读渲染（预览态点块不进入编辑） | `KnowledgeScreen.kt` |
| 目录 | 右栏标题树，点标题把命中块切到编辑态并选中；可全部收起/展开、拖宽、收起 | `MarkdownOutline.kt` |
| 菜单 | 页头「菜单」chip：动作在前、按键在后、按键按平台显示；点空白处关闭 | `MarkdownEditor.kt` |
| 本篇概念 | 右栏当前笔记的 chips：点开概念详情、× 熄灭、输入框回车添加 | `KnowledgePanels.kt` |
| 全局概念 | 左栏「概念」tab：所有概念与来源；点进详情看「来源」和「常一起出现」 | `KnowledgePanels.kt` |
| 导出 | 页头「导出」chip：把当前笔记存成独立 `.md`（去 frontmatter、`{{…}}` 还原成文字、`:::ai` 围栏去掉；正文已有同名标题就不重复加标题）；选位置用系统原生保存面板 | `KnowledgeScreen.kt` / `MarkdownExport.kt` / `KnowledgeNote.kt` |

几条约定：

- **块是渲染与编辑的单位，不是存储单位**：标记仍写在正文里（`{{…}}`、`:::ai`、`。。。`），块切分只用于显示和替换；每次编辑只 splice 当前块，其它字节不动。
- **行内标记只在光标进入或紧挨时出现**（marktext 式）：折叠态看到的是渲染样式；标记被隐藏后，Backspace/Delete 在 token 边界按内容字符删，不会删到看不见的 `*`。标题的 `# `、引用的 `> ` 例外——一直显示，用来提示层级。
- **中文粗体是「系统字重 + 同色阴影」叠出来的**：不依赖平台字体有没有真正的 Bold 字面，跨平台观感一致；阴影颜色跟随文字色（概念绿字加粗仍是绿的）。行内代码是「等宽 + 自绘圆角芯片」，上下沿按基线算出、不靠 padding。
- **链接默认只显示 label**（marktext 式）：地址藏在源码里，点进块才看得到；聊天消息仍显示地址，避免模型给的链接丢了。
- **已知差异**：编辑块只剩极淡底色、没有边框；嵌套列表缩进还没参与「点哪落哪」的坐标换算，表格/代码/公式/AI 块的落点是近似的；若以后要「整篇一个字段连续编辑」，需要把整篇做成单字段，代价是编辑时表格/公式/AI 块只能显示源码。
- **空行是块间间隔**，不归任何块；删空一块后仍保留一个空编辑框，避免「删没了就点不回来」。
- **标题也是块**（`# …`），所以标题里的行内语法同样在预览里渲染；目录与笔记列表的标题走同一套 `stripInlineMarkdown`。
- **概念有显式语法** `[[概念名]]`：预览只显示名字（浅绿下划线），和隐式「按概念表匹配」共存；AI 抽取会把首次出现自动包成这个写法。
- **不做**：全局知识图、块拖拽排序、多光标；撤销/重做仍靠输入框自身。
