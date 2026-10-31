# 知识库讨论稿（插件化计划第 2 条）

> 对应 [pluginization-plan-2026-10-29.md](pluginization-plan-2026-10-29.md) 第 2 条「学习终端与知识体系」。
> 第 1 条「计划自己推进」的讨论稿是 [plan-rhythm-draft.md](plan-rhythm-draft.md)，两篇**解耦**，互不前置。
> 状态：讨论稿（2026-10-29）；P0 已落地（2026-10-30，见 §10）：笔记 + Markdown 编辑器 + 概念抽取/点亮/熄灭/追溯/搜索。
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

**P1**：导入外部 Markdown、概念别名/合并、AI 补全定义、相关概念（共现）、对话页「存为笔记」。

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

**未落地（按 §8 继续）**：`sdk-domain` 契约与 `:features:knowledge`、AI 工具（`search_knowledge` / `save_note`）、导入外部 Markdown、概念别名/合并、对话页「存为笔记」。

**已落地（2026-10-31，P0 第三批：编辑器手感）**

- **工具栏撤掉**：格式不再摆一排按钮（十几种语法摆不下、也记不住）。入口只剩两个，都在 `MarkdownCommands.kt`：
  1. 斜杠命令菜单：行首或空格后敲 `/`，打几个字过滤（标签、id、拼音首字母都认），↑↓ 选、回车插入、Esc 关；16 条命令覆盖标题 1–3/正文/有序无序任务列表/引用/分割线/代码块/表格/粗斜体删除线行内代码/链接/图片。代码块里敲 `/` 不触发（写路径不会误弹）。
  2. 快捷键：`Ctrl/Cmd+B/I/K`、`Ctrl/Cmd+Shift+X/C/L/O/Q/R/T`、`Ctrl/Cmd+0/1/2/3`。键位只在 `markdownKeyBindings` 定义一次，键盘分发和「快捷键清单」都读它，不会出现「说明书和实际按键不一致」。
- **快捷键清单**（`Ctrl/Cmd+/` 或编辑器右上角「快捷键」）：编辑器 + 页面（`Ctrl/Cmd+S/N/E`）两段；`/`、↑↓、回车续列表、Esc 也写在里面。
- **右侧目录**（`MarkdownOutline.kt`）：按 `#`~`######` 生成树（围栏代码块里的不算），分级折叠、全部收起/展开、可收起；中间拖拽条改宽度，上下限 180–420dp（`MarkdownSidebarState`，切页回来宽度还在）。点标题跳转：编辑态把光标挪到标题行（输入框会把它滚进可视区），预览态按渲染块的实测坐标滚动（`MarkdownText(onBlockLayout)`）。
- **Markdown 渲染补齐**（`MarkdownText.kt`，会话页和笔记预览共用）：分割线 `---` / `***` / `___`（`- - -` 也认，但不会把 `- 列表项` 吃成横线）、任务清单 `- [ ]` / `- [x]`（方框自绘，避免 `☑` 落到彩色 emoji 字体）、图片 `![说明](url)`（不联网取图，显示成「［图］说明 (url)」）、`***粗斜体***`；标题改成 `#` 后必须真有文字，**目录和预览共用同一套标题规则**（否则第 N 条目录会跳到第 N+1 个标题）。
- **标题字号改成独立阶梯**：原来直接借 `MaterialTheme.typography` 的 `titleMedium`(16sp) / `titleSmall`(14sp) / `labelLarge`(14sp)，结果一级标题和正文一样大、二级标题比正文还小，**加粗正文反而压过标题**（2026-10-31 反馈）。现在 `markdownHeadingStyle(level, base)` 按正文字号乘系数算：1.50 / 1.31 / 1.16 / 1.10 / 1.04 / 1.00，保证「严格递减 + 任何一级不小于正文」，系数乘在传入的正文样式上，所以主题缩放和用户字号偏好都会跟着走；标题上方留白也按级别给（14/10/8/6dp）。
- 测试：`MarkdownCommandsTest`（14：触发/过滤/插入/键位唯一性/清单完整性）、`MarkdownOutlineTest`（7：层级/偏移/围栏/折叠）、`MarkdownEditTest`（+6：分割线独占一行、任务清单、正文去标题、图片）、`MarkdownTextTest`（+5：横线、`***`/`___`、任务清单、图片、空标题兜底）；`:app:desktop:test` 272 个用例全绿。

**已知缺口**：编辑区无撤销/重做快捷键；手动删掉的概念，在同一篇笔记下次内容变化触发重抽时可能被 AI 再加回（`extractedBodies` 只防同内容重复抽取）；概念「定位」是选中片段，不是高亮滚动；窗口很窄时（< ~700dp）左侧笔记列 + 目录会挤掉编辑区，需要手动收起目录。
