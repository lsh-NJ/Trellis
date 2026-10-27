# 计划页（实现记录 · 2026-10-27）

> 一句话：**上面是整个计划，一周一格；下面是选中那周的选中一天，按待办的样子排。**
> 页面本身不算账，数字全部由 `:features:learning-process` 算好。

## 1. 为什么单独开一页

任务页回答「今天要做什么」，学生真实的问题是「这六周到底怎么走、这周该学什么」。
把它塞进首页卡片会变成第二张待办清单；它需要的是**周维度的总览**＋**落到某一天的安排**，
所以给它一个独立页签（左栏导航叫「计划」）。

## 2. 页面结构

```
┌ 计划 │ 计划  操作系统 · 一周一格，点开看那一天                    2 周 ┐
│ 所有计划 1 个 │ 总览 按周排                                               │
│ 未分类        │  [第 1 周 以后] [第 2 周 待安排] …（按住往左拖能看后面的）   │
│  · 操作系统…  │   已排的：章节 / 2–3 个单元 / 日期范围 / 件数            │
│               │   没排的：整张灰掉 + 「待安排」+ 「这一周还没排」          │
│               ├ 待安排 · N（还没落到某天的单元，最多列 4 条）             │
│               ├ 周一26 | 周二27 (今天•) | 周三28 | … | 周日1               │
│               ├ 今天一件事：中断与系统调用 · 30 分钟。                      │
│               │   ☐ 中断与系统调用   Ch.1 导论 · 30 分钟  最小版 12 分 待做 │
│               │   ☑ 已完成（删除线）                                       │
└───────────────┴──────────────────────────────────────────────────────────┘
```

- 左侧是计划列表：最上面「所有计划」，下面按大类分组（`plans/categories.json` 里没有的算「未分类」）。
  点一行只看那一个计划；再点「所有计划」回到全部。
- 周卡片标题是「第 N 周」（不把 ISO 周号摆到台面上），下面一行是这一周出现最多的章节名；
  再下面是单元名前三条；底部是 `rangeLabel` 与单元数。选中卡片描边加粗。
- **没排到具体某天的周**：`WeekView.scheduled=false` → 整张卡灰掉、右上角写「待安排」，
  正文换成「这一周还没排」。周一到周日那一条会自动排：当周、或周日晚上排下一周
  （`scheduleDueWeeks()`，actor=SYSTEM）；不排就永远不会出现在天条里。
- 天条七格等宽，今天的格子里有个点和「今天」的语义（`WeekDay.today`）；空格显示 `—`。
- 下方正文来自 `DayView`：`headline`（后端写好的整句）+ `occurrences`（待做，可勾）
  + `completed`（已完成，带删除线）。勾选直接调 `completeOccurrence`。
- 天条上写的「N 件」与正文口径一致：当天 planned 与当天完成的都算（`doneScheduledOn` 补的那部分）。

## 3. 数据从哪来

| 界面要什么 | 调哪个 | 来源 |
|---|---|---|
| 计划标题、当前周、周列表 | `activePlanId()` + `planState()` + `map()` | `:features:learning-process`（SQLite 事件库） |
| 每张周卡片 | `week(planId, week)` → `commitment.units` | 同上 |
| 七天的格子 | `week(...)` → `days` | 同上 |
| 某一天正文 | `today(planId, day)` → `DayView` | 同上 |
| 勾掉一条 | `completeOccurrence(envelope, occurrenceId)` | 命令层；写完重取整页 |

运行时侧只有这几个字段（`AppRuntime`）：`planTitle` / `planWeekCards` / `planDays` /
`planSelectedWeek` / `planSelectedDay` / `planDayView` / `planMessage`；
`refreshPlan()` 一次取齐，`selectPlanWeek` / `selectPlanDay` / `completePlanOccurrence` 三个动作。

**不在页面里做的事**：算完成率、算还剩几件、判断是否逾期、拼「今天一件事」这句话——
这些都属于后端（`plan-backend.md` 的硬规则：只有 `ReviewView` 带统计，别的视图只给文案）。

## 4. 库打不开时

`<trellis home>/plans/plan.db` 只在这一页第一次打开时建连（`LearningPlanStore.open`）。
打不开就在页面上直说（`planMessage`），不假装「今天没事」。没有计划时给一句
「在对话里说『我想学 xx，帮我排个计划』」，指向真正能造计划的地方（AI 管理员 + 计划工具）。

## 5. 已知缺口

1. **只能看图，不能改计划**：调整承诺、顺延、拆分、缩短都还没有入口（后端已有命令）。
2. **没有周复盘**：`ReviewView` 与 `generateReview` 还没接；周卡片右上角可以加「复盘」入口。
3. **时区**：`today(planId, day)` 的 `day` 用系统本地日期；跨时区场景要按 `plan-ui-contract.md` 再定。
4. **勾选后的落点**：`completed` 按 `completed_at` 的日期归档，所以「28 号的事今天做完」不会留在
   28 号那一格里（会出现在今天）。这是后端的口径，界面暂时照它显示；如果嫌怪，改的是命令层而不是页面。

## 6. 验证

- `:app:desktop:compileKotlin` / `:app:desktop:test` / 全仓 `gradlew test` 均绿。
- 演示数据：`_shots/plan-home/plans/plan.db`（一次性造数脚本已删），
  启动 `TRELLIS_HOME=_shots/plan-home TRELLIS_TAB=plan` 截图核对周卡片、天条、勾选。

## 7. 2026-10-27 晚的三处修正

1. **删掉「所有计划」那一行**：它在只有几个计划时没有信息量，而且 `selectPlan(null)` 的选中态
   和「未分类」标题叠在一起看着像坏了。现在左侧只有：分类标题 + 计划行。
2. **完成可以改回没做**：已完成行现在可点（行尾提示「点一下可以改回没做」），
   走 `uncompleteOccurrence`：`done → planned` 并把 `completed_at` 清空
   （表 CHECK 要求两者一致），写 `occurrence.uncompleted` 事件。不依赖 5 秒撤销窗口。
3. **第一周落在过去**：根因是模型把周次写成上一年（`2025-W01`），而工具没拦。
   - `PlanTools.create_learning_plan` 现在拒绝早于本周的 ISO 周，报错里直接给「从本周 Wxx 开始」；
   - 计划页检测到 `planStartsInPast` 时在顶上给一条横幅 + 「挪到本周」按钮：
     不改旧计划（`plan_version` 不可变、occurrence 身份不可改），而是建一份新计划把单元按原顺序
     搬过来、结束旧计划；旧计划保留在库里，历史不丢。
   - 覆盖测试：`PlanRepairTest`（重排后首周=本周、单元不丢）与
     `LearningPlanServiceTest.aCompletedOccurrenceCanBeMarkedUndoneOnPurpose`（完成→改回）。

## 8. 2026-10-27 夜：挪到本周不再新建计划 + 计划从今天起排

1. **「挪到本周」改成原地修**（用户报：点一下多出一个新计划）
   - 旧做法：建一份「（重排）」新计划，再结束旧计划 → 左侧出现两条计划，完成记录留在旧计划里。
   - 新做法：`AppRuntime.repairPastPlan()` 在**原计划**里 `saveDraft → acceptDraft` 出一版周次平移后的版本：
     同一个 `planId`、同一个标题、同一批单元；旧版本作为历史留在库里，不删也不改。
   - 顺带修 `PlanQueries.buildMapView`：总览的周列表原来跨所有版本取 `DISTINCT c.week`，
     重排后 `2025-W01` 会和本周同时出现（并让「过去」横幅消不掉）；现在只认 `plan.current_version_id`。
   - 左侧列表只列未结束的计划，副标题改成「进行中 / 草稿」，不再原样显示 `active`。
2. **计划第一周必须是本周**（用户报：agent 排计划不以当天为准）
   - `PlanTools.create_learning_plan`：第一周必须等于今天所在的 ISO 周，否则报错并给出本周；
     `weeks` 还要求按时间递增、不重复。
   - 排期本身早就是「从今天铺」：`materializeWeek` 里 `day = if (today in 本周) today else 周一`。
     之前从下周开始，是模型把 `weeks[0]` 直接写成了下周，工具没拦。
   - 测试：`PlanToolsTest.aPlanThatStartsNextWeekIsRejected`（新增）、`PlanRepairTest` 断言计划数不变、标题不变。
3. **数据清理**：旧逻辑在真实 home 里留下的重复计划已整理为「一条 active、两条 ended」；
   原库备份在 `~/.trellis/plans/plan.db.bak-20261027-2240`。
