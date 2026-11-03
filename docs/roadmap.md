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
