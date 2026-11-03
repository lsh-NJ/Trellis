# 待办与已知缺口

按优先级。做完一条划掉一条。

> **阅读顺序（2026-10-26 加）**：本文件按时间累积，**越往后越新**。先读 §0.2 / §0 看最新状态，
> 再回看 §1 / §2 —— 它们是 2026-10-19 的旧缺口清单，不少已经补上（旧条目保留，只为解释当时为什么那样排优先级）。

---

## 1. 挂载点：9 个定义，界面上只渲染了 1 个（**暂不处理，2026-10-19 记**）

内核定义了 9 个挂载点，`RENDERED_MOUNT_POINTS` 里只放了 `slot.home.section`。
模型只能往看得见的地方挂，所以**实际能力面只有 1/9**。

### 白捡的（位置已经在屏幕上，只是被写死的内容占着）

| 挂载点 | 现在被什么占着 | 改哪儿 |
|---|---|---|
| `slot.home.header` | `Header(runtime)` 标题 + 统计栏 | `AppScreen.kt` 的 LazyColumn 第一条 item |
| `slot.row.trailing` | 「+25 分钟 / 删除」两个按钮 | `TaskRow` 末尾 |
| `slot.empty.state` | 一句写死的「还没有任务」 | `tasks.isEmpty()` 分支 |

接上之后 `RENDERED_MOUNT_POINTS` 从 1 变 4，**一天以内**。

### 要建页面的

| 挂载点 | 缺什么 |
|---|---|
| `slot.detail.aside` / `slot.detail.tab` | 没有详情页 |
| `slot.settings.section` | 没有设置页 |
| `slot.report.chart` | 没有报表页 |

### 要建容器的（**最值钱**）

| 挂载点 | 缺什么 | 为什么重要 |
|---|---|---|
| `slot.app.overlay` | 一个叠在最上层的浮层容器 | 没有它，AI 做不出任何「浮起来提醒你」的东西 —— 而那恰恰是「会做事」最容易被看见的形态 |

---

## 2. 其他已知缺口

- **数据字段只有 7 个，且首页全显示了** → AI 无论怎么退，退出来的都是重复。需要补 `streakDays`、`completionRate`、`oldestOpenTask` 这类首页没有的
- **动作只有 3 个**（addTask / logTime / completeTask）→ 没有 `notify`、`reschedule`、`deleteTask`
- **规则太弱**：单一事件、无 `when` 条件、无 `once`（现在每次触发都跑）
- **表达式缺列表和字符串拼接** → 做不了「列出今天到期的三项」
- **动作失败用户看不见** → 只记进诊断面板
- **无插件版本 / 回滚** → 覆盖安装失败就是没了
- **跨端**：只有桌面 JVM
- **系统性验收**：真机只跑过两次生成，没拿 10 个需求跑一遍

---

## 0. 2026-10-22 大改（汇总）

这一天之后，下面这些不再成立或已补上：

- **能力面**：挂载点已上屏 6 个（home.header/home.section/empty.state/app.overlay/report.chart/settings.section；row.trailing 故意不接，缺行上下文）；数据字段 10 个（补 streakDays/completionRate/oldestOpenTask）；动作 6 个（补 renameTask/rescheduleTask/deleteTask，支持 `oldest`/`oldestOverdue` 选择器）；`task.completed` payload 补 spentMinutes。
- **生成链路**：提示词从「禁止清单」改成「做满→退步阶梯」；脚本路线真正落地（提示词宣传的节点在 NodeBuilder 都有实现）；PluginAgent/AgentFlow 的脚本缺口已知（生产路径走 ToolAgent+PluginTools）。
- **UI 词汇表**：补 section / grid（columns 1..12，子节点可带 span）/ text（size/weight/font/tone）/ stat / badge / progress / spacer / bars / sparkline / ring / icon / image / table，共 20 余种语义节点；图由宿主 Canvas 画，插件只交数据。
- **布局与样式**：`arrange_cards`（按插件分行/列，持久化到 layout.json）；L1 参数化配色（OKLCH 生成 + WCAG 对比度生成期保证）；L2 主题包（`kernel.theme` 扩展点，脚本可贡献，宿主校验 token/对比度，暗色可派生）；外壳 shape/type token；随包字体（Barlow Condensed + IBM Plex Mono）；像素图标 5 个；`Chrome*` 组件开始替换 M3 默认外观。
- **Agent 管理员**：常驻对话页 + 流式输出 + 意图路由（Chat/CreatePlugin/ManagePlugin/ArrangeUi）+ 分层提示词保前缀缓存；工具集含 describe_screen / take_screenshot / open_tab / click / type_text / press_key / list_cards / arrange_cards / list_ui_options / set_ui / set_plugin_card / list_themes / set_theme；截图默认作为图片进模型上下文。
- **已知缺口**：跨端仍只有桌面 JVM；无插件版本/回滚；顶栏、Checkbox、分段按钮仍是 M3 默认外观；`docs/agent-manager.md` 2026-10-22 晚被一次编码事故覆盖（已重写为简版）。

## 0.2 2026-10-24 评审落地（P0 + P1）

按 `trellis-review/修改建议.md` 做的一轮，记录取舍：

- **P0**：输入框 620dp 限宽 + 44dp 高 + 1px 线；日期入口合并成一个 chip；「添加」去掉投影；圆角统一 6dp；边框降到 1dp；`#8A8578` → `#6B665D`；文字对比度统一 ≥4.5:1。
- **P1-6 日期回显**：任务行显示「今天 / 明天 / 逾期 2 天 / 9月30日」，逾期用错误色只点一句，不喊；已完成显示「原本 … 到期」。
- **P1-7 行内操作**：悬停才出现 ⏱（+25 分钟）/ ⌫（删除），点击区 28dp；勾选框视觉 20dp、可点区 44dp。
- **P1-8 连续录入**：Enter 提交、Esc 清空，提交后焦点回输入框。
- **P1-9 分组**：逾期 / 今天 / 以后 / 没有日期；**「已完成」默认展开**，折叠只是手动收起 —— 默认折叠会让用户以为任务没了（这条被用户当场抓到过一次）。
- **P1-10 计数 stepper**：已做。`UiNode.Stepper(label / value / unit / min / max / step / on)` 由宿主画「− 数字 +」，插件只在 `on` 的 handler 里读 `payload.delta`（"1" / "-1"）并决定存什么；读书、俯卧撑两个插件已从输入框换成 stepper，量词由插件声明（个 / 页）。测试钉住：越界值夹回、缺 on 变可见错误、handler 收得到 delta。
- **删除撤销**：删完给 5 秒，卡片内出现「已删除「…」 撤销」条，条子自己消失；不弹确认框（删除是高频动作，确认框会让每次删除多一次决策）。

## 0.3 2026-10-28 设置页（补上 §1「要建页面的」里的一条）

- 新增**设置页**（`TAB_SETTINGS = 5`）：标签栏与左栏导航都有入口，`TRELLIS_TAB=settings` 也能直接进；代码在 `app/desktop/.../SettingsScreen.kt`。
- 这一页目前只有一项设置：**每日预计投入分钟数**（15–240，默认 60，步长 15），存在 `<home>/study.json`（`StudyPrefs`）。它不是个死数字——建学习计划时 ×7 写进 `process.weekly_capacity_minutes`（后端 `CHECK` 是 30–3000，所以上下限取 15 / 240 正好落在里面）。
- `slot.settings.section` 从插件页底部搬到设置页，成为这一页的「插件设置分组」。§1 里「`slot.settings.section` | 没有设置页」至此不再成立。
- 仍是缺口：`slot.detail.aside` / `slot.detail.tab`（没有详情页）、`slot.report.chart`（没有报表页）。
- **诊断页撤出导航**：它只在出事时才打开，常年占一格不值当。改成设置页底部一块「诊断」，点整块进入；停在诊断页时导航点亮「设置」（用户是从那里来的），页面头部是「← 诊断」（返回箭头在标题左侧）。导航项从 6 个回到 5 个（次日插件页也撤了，见下一条）：任务 / 计划 / 对话 / 插件 / 设置。
- **插件页也撤出导航**：装、停、删、授权这些事基本交给对话里的 agent，人只是偶尔来看一眼「它到底在不在跑」。改成设置页第二块卡片（显示插件总数、其中有几个报错），点整块进入；插件页头部同样是「← 插件」。导航回到 4 条：任务 / 计划 / 对话 / 设置。
- **二级页返回按钮统一**（2026-10-28 晚）：原来返回是正文上方一行 `labelSmall` 的「← 返回设置」，字号和说明文字一样、位置在正文流里，看着像脚注。现在抽成 `Chrome.kt` 的 `ChromeSecondaryHeader`：44dp 命中区的箭头贴在标题左侧、副标题在标题下方、页级动作走 `trailing`；诊断页与插件页都已换用。

## 0.4 2026-11-03 知识网络 G0（分类树 + 聚焦关系）

按 `docs/knowledge-graph-design.md` 的 G0 落地：知识库页加第三个 tab「网络」，默认是分类树（概念是叶子、父节点是所属分类），默认只画归属箭头；点一个节点才在画布上临时画它的直接关联（前置/应用/相关），右栏给定义、所属路径、来源片段与「在笔记中定位」。

- **代码**：`app/desktop/.../KnowledgeGraph{Model,Store,Taxonomy,Build,Layout,Canvas,Panels,Screen,ViewState}.kt` 九个新文件；只改 `KnowledgeScreen.kt`（三 tab）与 `KnowledgeStore.kt`（暴露 `home`）。不碰 kernel / sdk-domain / features / ui-kernel / AppRuntime / 主界面外壳；网络视图不是插件，不新增挂载点。
- **数据**：`knowledge/graph.user.json`（用户分类/归属/关系，权威）+ `knowledge/graph.cache.json`（AI 与布局缓存，可删）；笔记文件仍是唯一真相，图每次现算，删两个 json 只退化成「全未分类」。
- **已完成**：分类 CRUD/移动/合并/删除（含墓碑与 `mergedInto`）、概念归属、派生关系边（同段/相邻段共现、`[[…]]` 相邻标注）、确定性分区布局（同输入同坐标；2000 概念冷布局 ~5.7ms）、折叠/展开/深度、聚焦关系、搜索定位跳笔记、深浅色离屏截图。`./gradlew check` 绿，`:app:desktop` 510 条测试 0 失败。
- **G0 已知缺口**：画布内折叠箭头未做命中（点标题两次折叠/展开；工具条有展开全部/折叠全部）；左栏「领域筛选」字段留了但没接；AI 归类/关系建议（G1）未接；别名合并/计划接入/AI 工具（G2）未做。
- **下一步**：G1 按设计 §9.2 接 AI 归类建议与关系建议（确认门 + 墓碑 + 缓存失效 + mock LLM e2e）。

## 0.5 2026-11-03 知识网络 v1.2（虚实树 + 聚焦浮窗）

同一天第二次落地（设计 §1.5 / §4.9 / §5.11）：按用户反馈把「分区画布 + 右栏详情」简化成「节点 + 父子连线 + 聚焦小浮窗」。

- **画布**：只剩节点和父子连线；分类=虚节点（空心虚线框）、概念=实节点（实心框）；没有计数/徽标/定义。每棵根分类一棵树，未分类最后。`KnowledgeGraphLayout.kt` 重写为森林式 tidy tree（确定性、折叠、warm 只复用根序）。
- **聚焦**：点击节点 → 其它节点淡出、选中节点 spring 放大、相关连线高亮，并在节点旁弹 300dp 小浮窗（所属路径 / 定义编辑 / 来源前 3 条可定位 / 直接关联 / 移动到分类；分类则改名/新建子分类/删除）。右栏 320dp 详情面板删除。
- **深度不预制**：删掉「分类深度 1–5 / 分类默认折叠 / 聚焦显示关联 / 显示共现 / 领域筛选」等开关；分类深度由 G1 的 AI 归类决定，`MAX_CATEGORY_DEPTH` 降为 32 层纯安全上限。
- **数据不变**：仍是 `graph.user.json`（权威）+ `graph.cache.json`（可删）；笔记文件仍是唯一真相，图每次现算。
- **验证**：`:app:desktop` 509 条测试 0 失败（图相关 98 条：Store 11 + Taxonomy 36 + Build 25 + Layout 23 + Canvas 2 + Screen 1）；`./gradlew check` 绿；明/暗色离屏截图人工核对。
- **下一步**：G1 接 AI 归类（含分类深度/层级建议）与关系建议（确认门 + 墓碑 + 缓存失效 + mock LLM e2e）。

## 0.6 2026-11-03 知识网络 v1.3（虚节点 + 拖动链接）

同一天第三次落地（设计 §1.6 / §4.9.1 / §5.5）：用户指出「实节点不一定有上级节点」，于是把「建类」变成直接在画布上搭树。

- **虚节点入口**：工具条「＋ 虚节点」/左栏「＋ 添加虚节点（拖到节点上成为父分类）」新建空分类（名字去重，可改名），不预设位置。
- **拖动链接**：从分类节点按下拖动，拖到概念上松手→该概念被收为子节点（`assignConcept`）；拖到分类上松手→整棵子树挂过去（`moveCategory`）；拖到空白/非法落点（自身、后代、祖先）不生效。手势改成「按下点判定命中 + touchSlop 起拖」，拖动中画虚线与落点高亮。
- **AI 整理重新定义**（G1 口径，仅文档）：AI 的产物是图操作「建节点 / 命名 / 链接」，与手动操作共用同一套 Taxonomy 纯函数，确认后才写 `graph.user.json`。
- **验证**：`:app:desktop` 510 条测试 0 失败（图相关 99，新增拖动回归测试 1 条）；`./gradlew check` 绿；`docs/assets/knowledge-graph/impl-v1.3-*.png` 明/暗/分类浮窗截图。
- **下一步**：G1 接真正的 AI 归类（按图操作产出建议 + 批量确认）与关系建议。


- 2026-11-03 补丁：节点文字截断（布局尺寸按 density 缩放）、画布溢出左栏（`clipToBounds`）、图例移到画布右上角浮层。

## 0.7 2026-11-03 知识网络 G1（AI 归类 + 关系建议）

按设计 §9.2 接入 AI：AI 只产出建议，用户确认后才写分类树。

- **笔记内（搭便车，零额外调用）**：`organizeNote` 的同一次请求里多输出 `categories`（≤12 条，路径 ≤2 层）与 `relations`（≤6 条；有向关系必须给正文逐字 evidence）；解析校验后写 `graph.cache.json` 的 `noteSuggestions[noteId]`（按 bodyHash 失效）。
- **全图整理（手动触发）**：左栏「✦ AI 整理分类」→ 确认弹窗（概念数 / 批数 / 成本 / 不直接改树）→ 未归类概念 ≤40 个/批、≤20 批串行调用；失败批记 `failed` 并跳过；结果按 `conceptSetHash` 合并进缓存。
- **建议面板**：画布右侧浮层，归类建议「确认/忽略」、关系建议「确认/反向/忽略」、`全部确认/全部忽略`、来源（笔记内/全图）与 reason/evidence；左栏「查看 AI 建议（N 条）」。
- **确认门与墓碑**：确认写 `graph.user.json`（归类走 `applyCategorySuggestions`，关系写 `relations`）；忽略写 `removed.suggestions`/`removed.relations` 墓碑，AI 不再提同一条；处理后从缓存移除。
- **代码**：新增 `KnowledgeGraphAi.kt`（纯逻辑 + `runGlobalCategorySuggestions`，可注入假 LLM）；`KnowledgeExtraction.kt` 提示词加 categories/relations；`KnowledgeScreen.kt` 整理后写缓存；`KnowledgeGraphScreen.kt` 接全图整理/确认流程；`KnowledgeGraphPanels.kt` 建议面板；`KnowledgeGraphViewState.kt` 加 `suggestionsOpen`。
- **验证**：`:app:desktop` 共 533 条测试 0 失败（graph 122：Store 11 + Taxonomy 36 + Build 25 + Layout 23 + Ai 23 + Canvas 3 + Screen 1）；`./gradlew check` 绿；假 LLM 端到端覆盖「2 批 2 次调用 / 确认前 user.json 不变 / 失败跳过 / 墓碑过滤」；截图 `docs/assets/knowledge-graph/impl-v1.3-g1-suggestions.png`。
- **没做（后续）**：真实 key 的本机手测；已忽略建议的恢复列表 UI；自动应用开关（默认关）；关系证据 hover 高亮。
- **下一步**：G2（合并两个已存在概念 → 体系视图可选只读 → 计划接入 → AI 工具 `KnowledgeTools`）；或先按用户实际试用反馈磨 G0/G1。

- 2026-11-03 补丁：网络视图顶部常驻「笔记 / 概念 / 网络」三 tab（此前进入图后无路可退），并加点击回归测试；`:app:desktop` 534 条测试 0 失败
## 0.8 2026-11-03 知识网络补丁 4（建议不失效 + 笔记页保存并整理）

- 修「确认一条 AI 建议后其余全消失」：`conceptSetHash` 改为只含概念集，分类树变化不再让全图建议失效。
- 笔记页页头加「保存并整理」：先落盘再强制整理；整理时把笔记标题结构送进 prompt，AI 优先用大标题当父分类名（可概括、不必照抄），同一标题下概念尽量同父。
- 验证：`./gradlew :app:desktop:test` 536 条 0 失败（graph 124）、`check` 绿；设计见 `knowledge-graph-design.md` §1.7/§6.1/附录 E.6。
- 下一步：G2（别名合并、可选依赖体系视图、计划 unit_concept、AI 工具 KnowledgeTools）。

## 0.9 2026-11-03 知识网络 v1.3 补丁 5（网络视图 UI 与笔记页统一）

- 用户反馈「网络与笔记/概念页明显割裂，解释性文字不需要」：左栏改用与笔记页同一套 `ListSidebar`（标题「网络」+概念数、可收起、搜索框、「笔记/概念/网络」三 tab 在左栏内），页头改为右对齐 `EditorChip` 工具条（展开/折叠/适应窗口/＋虚节点）。
- 删掉左栏解释性长文字（未分类说明、点节点/双击/拖动说明、默认 AI 长说明）与统计卡，只留开关 + 一行统计 + AI 入口。
- 验证：编译绿、渲染测试 2 绿（截图归档 4 张）、全量测试与 `check` 见提交说明；设计见 `knowledge-graph-design.md` §5.1 补丁 5 / 附录 E.7。
。
## 0.10 2026-11-03 知识网络补丁 6（概念并入网络 + 一键采纳 + 左栏分类树）

- 用户反馈「AI 建议来一个一键采纳」「网络功能可以替代概念功能了」「把每张图的根节点放在左栏空缺处，依然以 UI 统一为原则」。
- `KnowledgeTab` 收敛为 `{Notes, Graph}`：概念 tab 下线，概念列表页/详情主区分支删除；搜索结果、本篇概念等概念入口统一 `openConcept` → 切网络 + `selectedKey`/`pendingCenterKey` 选中并居中节点，概念详情只在网络浮窗看。
- AI 建议加「一键采纳」：左栏「查看 AI 建议」旁，一次确认全部归类+关系建议（`confirmCategorySuggestions` + `confirmRelationSuggestions` 串联，写 `graph.user.json` 并清缓存建议），逐条确认/全部确认/全部忽略仍在建议面板。
- 左栏空搜索态下半部分列「分类树」：每棵树根分类 + 「未分类」行（复用 `GraphHitRow`），点击选中并把相机居中到该树。
- 验证：`:app:desktop` 544 条测试 0 失败（graph 124）、`./gradlew check` 绿；渲染测试 2 绿并重生成 4 张 Density(2f) 截图；设计见 `knowledge-graph-design.md` 附录 D/E.8。

## 0.11 2026-11-03 知识网络补丁 7（画布边界圆角）

- 用户反馈「把图边界改为圆角」：网络视图画布外层加 4/12/2/12dp 内边距露出底色，内层 12dp 圆角裁剪 + 1dp `outlineVariant` 半透明描边 + `surface` 背景；点阵、节点、浮窗、图例都裁在圆角内，与左栏/页头/浮窗的圆角语言统一。
- 验证：编译绿、渲染测试 2 绿并重生成 4 张 Density(2f) 截图、`:app:desktop` 550 条测试 0 失败、`check` 绿；设计见 `knowledge-graph-design.md` 附录 E.9。

## 0.12 2026-11-03 设置页清理（统一 UI + 去说明文案）

- 用户反馈「把设置页面也清理一下，删掉描述，并使用统一的 UI」：设置页从 6 块 Material3 `Card`（每块标题 + 解释性长文案 + 控件）改成单列扁平段——「区段标题（`GraphSectionLabel`）+ 1dp 发丝线 + 扁平行」，与知识库/网络页同一套语言。
- 动作收进页头：模型 API 的「导入/重新导入」用 `EditorChip`；选项行统一成 7dp 方块 + 名称 + 右侧「当前」（同 `GraphHitRow`）；插件/诊断改成整行可点（状态 + ›）；每日投入压成「− 60 分钟/天 +」；时间调试压成一行日期 + chips（+1 天/ +1 周/退出模拟）。
- 保留功能状态与安全提示：模型 model@host（环境变量来源）、插件数/错误数、诊断问题数、当前模拟日期、可调范围 15–240、模拟时间会写计划数据的提醒；`slot.settings.section` 插件分组不变。
- 验证：`:app:desktop` 550 条测试 0 失败、`./gradlew check` 绿；`UiRenderTest` 新增设置页明/暗两张离屏截图（`build/ui-shot-settings{,-dark}.png`）人工核对。
