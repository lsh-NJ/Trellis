# 待办与已知缺口

按优先级。做完一条划掉一条。

> **阅读顺序（2026-10-26 加）**：本文件按时间累积，**越往后越新**。先读 §0.2 / §0 看最新状态，
> 再回看 §1 / §2 —— 它们是 2026-10-19 的旧缺口清单，不少已经补上（旧条目保留，只为解释当时为什么那样排优先级）。
>
> **2026-11-07 补**：新增 §3「缺口总表（按卖点分组、带编号）」——之后重点补缺口按它排优先级，沟通时用编号引用（如「这轮补 G-B2」）。

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

---

## 3. 缺口总表（2026-11-07，按卖点分组）

> **2026-11-08 补（用户 5 点定稿）**：`plan_unit.kind`（学习/实践/复习，界面统一后缀）；`createShell` 空壳计划；
> `research_plan` 改为「阶段表 + 大纲文档 + 目标/里程碑 + 每日时长建议」；`plan_week` 工具（读大纲与上周完成，
> 排本周并铺到天、写本周文档）；知识库新增只读「计划文档」区（计划页只做跳转）。
> **还没做**：计划↔会话映射 `plans/sessions.json`、管家在周一/周日晚自动代跑 `plan_week`。
> 2026-11-08 补：`research_plan` 已接 DeepSeek 官方 Search/Fetch（`WebResearch.kt`，搜 3 组 query + 读 2 篇正文；失败降级）。

> 用法：`- [ ]` 勾掉即完成；编号供后续沟通引用。每条写清现状 / 影响 / 涉及文件 / 大致工作量 / 验证方式。
> P0–P3 是建议顺序，不是承诺。**已明确暂缓的见 §3.7，别再当缺口重复排。**
> 核对依据：`P0` 项多数是「卖点闭环缺的最后一环」；`P1` 是内容与体验；`P2` 是自动化治理；`P3` 是平台化。

### 3.1 由面到点（目标/项目 → 可执行点）

- [x] **G-A1 目标/项目实体（2026-11-07 完成）**
  - 现状：`plan.db` 增 `goal`（`id,title,kind goal|project,intent,success,deadline,status active|paused|archived,parent_id,created_at,updated_at`）与 `goal_milestone`（`id,goal_id,title,deadline,status open|done,sort_order`）；`process.goal_id` 列（旧库在 `PlanDatabase.migrate()` 里 PRAGMA 检测后 `ALTER TABLE`）。
  - 契约：`GoalView`/`MilestoneView`（含关联 `plans: List<LearningPlanInfo>`）、`GoalRef`/`MilestoneRef`；七个命令 `createGoal`/`updateGoal`/`addMilestone`/`completeMilestone`/`uncompleteMilestone`/`removeMilestone`/`linkPlanToGoal`（事件 `goal.created/updated`、`goal.milestone_added/completed/reopened/removed`、`plan.goal_linked`；完成与关联带 5 秒撤销）。
  - UI/工具：计划页头部「目标 · N」进目标二级页（`GoalScreen.kt`：进行中/已归档、验收、里程碑勾选、关联计划跳转、新建目标/加里程碑对话框）；`GoalTools.kt` 6 只（`list_goals`/`create_goal`/`add_milestone`/`complete_milestone`/`link_plan_to_goal`/`archive_goal`，写操作要 confirmed）；`create_learning_plan`/`research_plan` 建计划时可直接带 `goalId` 挂到目标。
  - 仍未做（并入 G-A4）：项目部件/交付物证据、目标进度百分比、目标页的时间线视图。
- [x] **G-A2 `research_plan` 工具（2026-11-07 已落地最小版）**
  - 现状：`app/ui/.../ResearchTools.kt` 提供 `research_plan(goal, dailyMinutes)`：模型产出结构化草案（title/units/周建议）→ 校验 → `createProcess/saveDraft/acceptDraft` 建计划 + 写计划文档（`PlanDocStore`）；缺 weeks 时按每天预算自动排；`ManagerRouter` 已登记（FindResource / PlanManage）。
  - 由面到点主链路已通：目标式说法（「两周复习完 X」）→ research_plan → 计划 + 计划文档 + **全局周表**（2026-11-07 口径：不设周数上限、先整体后每周局部；`deadline` 按截止周倒排，排不下留待安排；`dailyMinutes` 只是建议，不再追问/弹选项；bvid 全量导入同样一次排出全局周表）。
  - 仍缺：外部来源引用（B 站/书籍）与先修依赖；纯目标话术要靠提示词引导，模型仍可能先搜 B 站再走 bvid 路径（但 bvid 路径也会遵循 deadline 与全局周表）。
- [ ] **G-A3 拆解缺结构（P1，1–2 天）**
  - 无先修依赖、无难度/时长校准（单元分钟数由模型拍，后端 `CHECK` 只保证 10–45）、无拆解模板/参考资料库；同类目标重复建计划无模板复用。
- [ ] **G-A4 项目型计划未建模（P1，依赖 G-A1）**
  - 项目 = 部件/交付步骤 + 验收 + 产物证据；现在 unit 只有章节+标题+分钟。
- [ ] **G-A5 计划文档单向（P1，1 天）**
  - `sync_plan_week` 只把 doc 周条目追加成单元；计划状态不回写 doc，doc 的「待安排」与后端待安排池不联动（`PlanDocStore`）。
- [ ] **G-A6 按来源续排未做（P2，文档已记）**
  - `material/material_version/segment` 与 `plan_unit.material_segment_id` 回填未做；B 站课程「后半部分怎么补」仍靠全量导入。
- [ ] **G-A7 无「让 AI 安排 / 看复盘」入口（P2，文档已记）**
  - `docs/plan-rhythm-draft.md` §P2 剩余按钮式入口未做。

### 3.2 由点到面（知识点+目标 → 推荐练习/项目）

- [x] **G-B1 练习 attempt 记录（2026-11-07 已落地最小版）**
  - 现状：`features/exams/.../ExerciseAttempt.kt`（`ExerciseAttempt` + `ExerciseAttemptStore`）落 `<home>/exams/attempts.jsonl`；客观题揭示答案时按 `normalizedAnswerLabels()` 自动判分并记录，知识点/简答题在答案下方「记住了/没记住」自评（`selfReported=true`）；一次练习只记一条；重启后从文件恢复。
  - 仍缺：记录里没有 planId/unitId/耗时（当前正确性证据够用，跨模块归因待补）；计划完成仍无 `done_kind`/session 证据。
- [x] **G-B2 掌握度最小版（2026-11-07 已落地）**
  - 现状：`summarizeMastery(attempts)` / `masteryOf(attempts, subject)` 现算；练习页卡下显示「数据结构：近 N 题答对 M（X%）」，练习提议的摘要里也带该科正确率。
  - 仍缺：概念级掌握度、时间衰减权重、与计划完成度联动。
- [x] **G-B3 推荐器最小版（2026-11-07 已落地）**
  - 现状：`ExerciseBank.recommendFor(text, attempts, excludeIds)` — 学科/课程/标签命中后按「上次做错 > 没做过 > 做对」排序；`maybeProposePractice` 已改用它（原 `suggestFor` 保留给无历史场景）。单测覆盖「错题优先 → 新题 → 做对」。
  - 仍缺：概念邻接、目标路径、项目候选三类规则；仍是一次一道、无连练。
- [ ] **G-B4 目标推断缺失（P1，文档已记）**
  - 只有显式 `goal` 才有方向；从对话/笔记推断目标、不确定时主动问，未做（`docs/plan-rhythm-draft.md`）。
- [ ] **G-B5 题库覆盖与匹配质量（P1，2–3 天 + 抓取）**
  - 现状：505 题 / 5 学科（体育、现代邮政英语、毛概、数据结构、数字电路），计算机网络/Linux 等主计划科目 0 题；匹配是学科/课程/标签关键词级（实测「线性表」推 AVL 树知识点题）。
  - 涉及：`tools/exams/` 管线（fetch/extract/build_seed）、`ExerciseBank.suggestFor`、种子扩充。
- [ ] **G-B6 练习闭环浅（P2，1–2 天）**
  - 一次只练一道、无错题本、无连练、无复盘、无间隔重复。
- [ ] **G-B7 知识体系沉淀不足（P2，2–3 天）**
  - 整理是单篇分段（`KnowledgeExtraction.extractionBodyChunks`），无跨笔记主题合并/知识体系视图；网络只有分类树+关系建议。

### 3.3 维护成本最低（自治 Agent）

- [x] **G-C1 建议仍需一键采纳（P0，1–2 天）— 2026-11-07 已接最小版**
  - 设置页「管家 → 自动采纳」开关（`StewardSettings.autoAdopt`，默认关）；开启后只在**应用不在前台**时执行，且只自动采纳 **纯挪动类**建议（`StewardProposal.isAutoAdoptable()`：整条建议的 changes 全是 `Move`；补排欠账 `Place` 仍留给人点）。
  - 每日上限 `autoAdoptDailyLimit`（默认 3，可在设置文件改 1–10）；计数落在 `steward/checkpoints.json`（`autoAdoptDay`/`autoAdoptCount`，换天归零）；`AppRuntime.autoAdoptStewardProposalIfAllowed()` 在晨/晚仪式成功（`runStewardCheck` 非 Busy）与窗口失焦时触发。
  - 执行完写入管家会话（建议行标 Adopted + 一条状态行「自动采纳管家建议：挪 N 件（应用不在前台，只动了「挪动逾期」这一类）」）、记忆一条 Adopted、刷新计划页、app.log 一行；用户回来能翻到、计划页改动可撤销（拖拽/移动仍走 `moveOccurrence` 的撤销 5 秒）。
  - 仍未做：白名单按「计划/星期几/单条改动」细化；自动采纳后给通知。
  - **2026-11-07 晚（按用户指令升级为「无限重排」）**：`autoAdopt` 默认开；`autoAdoptDailyLimit = 0` 表示**不限条数**（其余值夹在 1–10）；`isAutoAdoptable()` 放行 `Move` + `Place`；提案候选日从「本周日」扩到「下周周日」（可跨周）；状态行改「（应用不在前台，已自动重排）」。
- [ ] **G-C2 没有后台无人值守（P1）**
  - 无独立定时器/托盘/开机自启；ticker 只在应用开着时每 60s 跑一次（`startStewardTicker`）。
- [ ] **G-C3 通知没有闭环（P1，1 天）**
  - 桌面通知 fire-and-forget（`Notifier`：osascript / PowerShell Toast / notify-send），点击不能跳转/回复；只有未读红点。
- [x] **G-C4 周复盘 UI（P0，1–2 天）— 2026-11-07 已接最小版**
  - 计划页 `WeekDetail` 末尾加「周复盘」卡片：选中过去某周时显示 `ReviewView`（narrative / facts / question / suggestion）；引擎没自动补上时显示「$week 已经结束，复盘还没生成」+「生成复盘」按钮（`AppRuntime.generatePlanReview`）。
  - `PlanStateView.pendingReviewWeek` 给出欠复盘的具体周号（取「最近一个已结束的承诺周」，不再取最大承诺周——全局周表下最大周往往是未来）；`buildPlanState` 据此算 `pendingReview`。
  - 仍未做：「自动周复盘」开关（`docs/plan-rhythm-draft.md`）；复盘内容的 AI 建议目前走节奏引擎的模板，不做实时对话生成。
- [x] **G-C5 「全自动重排」口径定稿（P0，设计题）— 2026-11-07 已定并落地**
  - 用户口径：**给管家无限重排的权限**。边界：管家建议（挪逾期 + 补排欠账）在应用不在前台时可自动执行，**不限条数、不限类型、可跨周**；误操作兜底靠「设置页开关可关 + 计划页可拖回/撤销 + 逐条记账 + 只走计划后端已有命令」。
  - 不做：独立定时器/开机自启/托盘（G-C2），按日自动顺延（仍是建议驱动）；计划页「重排」侧栏与 AI 协商重排仍保留人工路径。
- [ ] **G-C6 AI 协商重排缺聊天侧实现（P1，文档已记）**
  - 拖拽已有；「聊天里说一句就重排（读真数据+确认执行+撤销）」未做；承诺层「这周先不排」未做。
- [ ] **G-C7 自动完成覆盖小（P2）**
  - 只有笔记联动触发（`PlanLink`，证据逐字校验、每条笔记 ≤3 个）；没有「完成后自动总结/沉淀」闭环。

### 3.4 插件化 + Agent 客制化

- [ ] **G-D1 无插件版本/回滚/原子更新（P2，长期缺口）**
  - 覆盖安装失败=插件没了；无热重载（`docs/roadmap.md` §1 已记）。
- [ ] **G-D2 无插件市场/分发（P3）**
  - 只有本地目录 + AI 帮忙写插件。
- [ ] **G-D3 移动端只有骨架（P3）**
  - `app/mobile`（`MainActivity.kt`）+ `app/mobile-preview`；本机无 Android SDK 无法验证；无跨端同步。
- [ ] **G-D4 能力面仍受限（P2）**
  - 9 个挂载点已上 6 个，`slot.row.trailing` 缺行上下文故意不接；数据字段 10/动作 6 表达力仍有限。
- [ ] **G-D5 脚本插件边界（P3）**
  - Rhino ES5、无 UI 逻辑、无沙箱性能预算。

### 3.5 笔记系统

- [ ] **G-E1 知识体系合并（= G-B7，合并跟踪）**
- [ ] **G-E2 无语义检索（P2）**
  - 知识库搜索关键词；记忆 `searchMemory` 词法匹配；FTS 按计划留到条目上千（`docs/steward.md` §4）。
- [ ] **G-E3 编辑器收敛（P2）**
  - `TRELLIS_EDITOR=webview` 实验版未定案；移动端编辑体验未做。
- [ ] **G-E4 笔记 → 练习/项目（P2）**
  - 没有「从笔记自动出题」；也没有「笔记成熟度 → 能力认证」。
- [ ] **G-E5 附件/图片资产（P3）**
  - 题库有 OCR 管线（`tools/exams/`），但笔记本身无图片/附件管理。

### 3.6 工程 / 环境 / 质量

- [ ] **G-F1 本机无 Android SDK（P1，环境）**
  - 全量 `./gradlew check` 跑不了（`:ai:testDebugUnitTest` / `:ai:lintReportDebug` 报 `SDK location not found`）；当前只能用 JVM 任务集。
- [ ] **G-F2 验收靠人工（P2）**
  - dev 验收基本是截图 + 手工点；无 UI 截图回归、无 e2e。
- [ ] **G-F3 性能/规模未压测（P2）**
  - 知识网络大图、题库上千、长会话、计划历史。
- [ ] **G-F4 备份/导出不统一（P3）**
  - `plan.db.bak`、`auto-completions` 有备份；没有「一键导出全部数据」。
- [ ] **G-F5 可观测性（P3）**
  - `app.log`/诊断页够用；无崩溃上报、无性能埋点。

### 3.7 明确暂缓（别当缺口重复排）

- 语义记忆检索（等条目 > 千再上 FTS/向量）。
- 无人值守自动调 LLM（主动消息只在应用打开/回到前台时发）。
- 独立定时器、按日自动顺延；全自动重排已由「管家建议 + 自动采纳」承担（边界见 G-C5）。
- 移动端正式落地（G-D3）、插件市场（G-D2）。

### 3.8 建议节奏

1. **P0 打通两个卖点的关键环**：G-A1 + G-A2（由面到点可追踪）、G-B1 + G-B2 + G-B3 最小版（由点到面有输入、有推荐）、G-C1 + G-C4（自动化治理与周复盘）、G-C5（口径定稿）。
2. **P1 内容与闭环**：G-B5（题库扩到主科目）、G-B6（错题/连练）、G-A4/A5、G-C3/C6、G-F1（Android SDK 环境）。
3. **P2 体验与沉淀**：G-B7/E1（知识体系）、G-A3/[G-D1/D4]、G-E2/E3/E4、G-C7、G-F2/F3。
4. **P3 平台化**：G-D2/D3/D5、G-E5、G-F4/F5。

