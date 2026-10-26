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