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
│ 计划        │ 总览 按周排                                              │
│ 未分类      │  [第 1 周 本周] [待安排 9 个节点] ← 半宽灰卡，点开看明细  │
│  · 操作系统…│   已排的：章节 / 2–3 个单元 / 日期范围 / 件数            │
│             ├ 待安排 · 9 个节点（合计 225 分钟 · 按每天 60 分钟约 4 天）│
│             │   第1章 计算机系统概述                                   │
│             │   ☐ P5 运行机制                        20 分钟           │
│             ├ 周三28 (今天•) | 周四29 | … | 周日1                       │
│             ├ 今天 2 件事，先做 P2 操作系统的概念、功能和目标。          │
│             │   ☐ P2 操作系统的概念、功能和目标  20 分钟   待做         │
└─────────────┴──────────────────────────────────────────────────────────┘
```

- 左侧是计划列表：按大类分组（`plans/categories.json` 里没有的算「未分类」）。
  点一行只看那一个计划。原来的「所有计划」汇总行已删除（既尴尬又点不动）。
- 周卡片标题是「第 N 周」（不把 ISO 周号摆到台面上），下面一行是这一周出现最多的章节名；
  再下面是单元名前三条；底部是 `rangeLabel` 与单元数。选中卡片描边加粗。
- **没排到具体某天的周不出周卡**：它的单元并入「待安排」集合，地图上只留**一张**半宽灰卡
  （`PendingWeekCard`，98dp = 周卡的一半，`surfaceVariant` 加 1dp 描边，顶部实心「待安排」胶囊），
  中间是待安排节点数，底部「点开看」。点开后在下方列明细（`PendingDetail`）：
  合计分钟 + 按每天投入算「约 N 天」+ 按章节分组的节点与各自预计时间。
- 天条七格等宽，今天的格子里有个点和「今天」的语义（`WeekDay.today`）；空格显示 `—`。
- 下方正文来自 `DayView`：`headline`（后端写好的整句）+ `occurrences`（待做，可勾）
  + `completed`（已完成，带删除线）。勾选直接调 `completeOccurrence`。
- 天条上写的「N 件」与正文口径一致：当天 planned 与当天完成的都算（`doneScheduledOn` 补的那部分）。
- 节点行不再显示「最小版 N 分 / 待做」，统一只显示「待做」。

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
3. **第一周落在过去**（2026-11-03 已删除，见 §14）：根因是模型把周次写成上一年（`2025-W01`），而工具没拦。
   - `PlanTools.create_learning_plan` 现在拒绝早于本周的 ISO 周，报错里直接给「从本周 Wxx 开始」；
   - 计划页检测到 `planStartsInPast` 时在顶上给一条横幅 + 「挪到本周」按钮：
     不改旧计划（`plan_version` 不可变、occurrence 身份不可改），而是建一份新计划把单元按原顺序
     搬过来、结束旧计划；旧计划保留在库里，历史不丢。
   - 覆盖测试：`LearningPlanServiceTest.aCompletedOccurrenceCanBeMarkedUndoneOnPurpose`（完成→改回）；
     `PlanRepairTest` 已于 2026-11-03 随横幅删除。

## 8. 2026-10-27 夜：挪到本周不再新建计划 + 计划从今天起排

1. **「挪到本周」改成原地修**（用户报：点一下多出一个新计划；2026-11-03 已删除，见 §14）
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
   - 测试：`PlanToolsTest.aPlanThatStartsNextWeekIsRejected`（新增；创建时校验保留）。`PlanRepairTest` 已随横幅删除。
3. **数据清理**：旧逻辑在真实 home 里留下的重复计划已整理为「一条 active、两条 ended」；
   原库备份在 `~/.trellis/plans/plan.db.bak-20261027-2240`。

## 9. 2026-10-28：待安排收成一张卡、按每天投入排期、计划设置

1. **待安排不再是一排灰周卡**
   - 没排到具体某天的周不出周卡：它们的单元并入「待安排」集合（和 `pool()` 里没分到周的单元去重合并）。
   - 总览末尾只有一张半宽卡：`PendingWeekCard` 98dp（周卡 196dp 的一半）、`surfaceVariant` 底 + 1dp 描边、
     顶部实心「待安排」胶囊、中间节点数、底部「点开看」。
   - 点开后下方换成「待安排 · N 个节点」：合计分钟 +「按每天 60 分钟算，约 N 天」+ 按章节分组的节点与预计时间。
   - 运行时字段：`AppRuntime.planPendingNodes`、`planPendingSelected`、`selectPlanPending()`。
2. **排期按每天投入分钟数摊开（参考值，不是上限）**
   - `PlanStateView.dailyMinutes`（= `process.weekly_capacity_minutes / 7`）。
   - `materializeWeek` 按每天参考值摊（20+25 同一天，超过参考值换下一天；整周到参考值后摊到当前分钟最少的那天）。
     2026-11-08 晚起不再因超出参考而拒绝或堆周日，见 §21。
   - 没设过投入时间的计划保持旧规矩「一天一个」，不猜参考值。
3. **计划设置（计划级属性，不是全局设置）**
   - 入口：计划页头部右侧「计划设置」文字按钮（有当前计划才出现）。
   - 弹窗：`PlanSettingsDialog` ——「只改当前这个计划（标题）；换一个计划就是另一套。」、
     −/＋ 调 15–240、预设 30′/45′/60′/90′/120′、说明「时间只做参考：排程会尽量按这个数往一天里摊开，
     不会因为超出就不排；已经排好的天不会自动重排。」、取消/保存。
   - 保存走 `setPlanDailyMinutes`（见 `plan-backend.md`），成功后关弹窗并 `refreshPlan()`。
   - 运行时：`planSettingsOpen`、`planDailyDraft`、`openPlanSettings/closePlanSettings/
     bumpPlanDailyDraft/choosePlanDailyDraft/savePlanDailyMinutes`。
4. **去「最小版」**：节点行不再显示「最小版 N 分」，统一「待做」。
5. **建计划工具**：`create_learning_plan` 新增 `dailyMinutes`（15–240，×7 写进 `process`），
   第一次只建本周 + 下周两个承诺，其余单元留在待安排池；guidance 写明预计时间取 B 站分集时长。
6. **验证**：`LearningPlanServiceTest.daysArePackedUpToTheDailyBudget`（20+25 同一天、30 次日、35 再次日）、
   `setPlanDailyMinutes` 的写入/越界/事件三例；全仓 `gradlew test` 绿。
   截图：`_shots/pending-1.png`（半宽待安排卡）、`_shots/pending-3.png`（点开明细）、
   `_shots/settings-plan-5.png`（设置弹窗）。
   DB 实证：选 90 保存后 `process.weekly_capacity_minutes = 630`，`event` 新增
   `plan.capacity_changed` `{"dailyMinutes":90,"weeklyCapacityMinutes":630}`。

## 10. 2026-10-29：周滚动 + 计划节奏报告（行为变化）

1. **有承诺、还没铺到天的未来周重新作为周卡片显示**（灰卡 + 「待安排」胶囊，卡片文案说明「到当周、或者周日晚上会自动排到每一天」），
   可以点开看这周承诺了哪几个单元；下方天条暂时是空的，等它变成当周/周日自动铺。
   以前这种周整周塞进「待安排」集合，滚动排周之后会出现「排好的下周又没了」的错觉。
2. **「待安排」集合现在只装池子单元**（没被任何一周承诺收走的那批）。未铺天周的单元不再重复收进来——
   否则同一批单元会在灰卡和周卡里各出现一次。`AppRuntime.planPendingNodes = service.pool(planId)`。
3. **计划页顶部新增「计划节奏」报告条**（`PlanRhythmCard`）：系统每次在 `refreshPlan()` 里推进节奏
   （结算旧周 / 补本周下周承诺 / 生成复盘 / 铺天）后，只要有实际动作就出现一句人话 + 「知道了」；
   没有动作时页面保持安静。数据来自 `AppRuntime.planRhythmReport`（后端 `RhythmResult`）。
4. **AI 也有了维护计划的手**：对话里的 `PlanManage` 意图 + `PlanAdminTools`（读状态/排下周/勾节点/顺延/复盘/改每天投入），
   见 `docs/plan-rhythm-draft.md`。计划页本身仍然只读 view model、不做四则运算。
5. **调试用「模拟时间」横幅**（`PlanSimulatedBanner`）：调试模式（设置页开关或 `TRELLIS_DEV=1`）拨了应用内日期后，
   计划页顶部出现「模拟时间 · 2026-11-02（真实 …）」横幅，带 `+1 天` / `+1 周` / `退出模拟` 快捷按钮；
   拨动后 `AppRuntime.applySimulatedToday(...)` 会立刻 `refreshPlan()`，让结算/补周/复盘按新日期跑一遍。
   边界与安全门槛见 `docs/time-conventions.md` §7（默认数据目录不允许，只有 `TRELLIS_HOME` 指向演示目录才生效）。

## 11. 2026-10-31：「待处理」卡片 + 协商重排的加时偏好

1. **计划页顶部新增「待处理」卡片**（`PlanOverdueCard`）：数据来自 `AppRuntime.planOverdue`
   （后端 `LearningPlanService.overdue(planId, today)`，只读投影，不写事件）。
   - 「本周到期没做完」逐条给三个动作：**今天做**（`moveOccurrence` 到今天）、**顺延一天**
     （`moveOccurrence` 到后一天；已到周日就提示等周结算，不硬塞下周）、**跳过**
     （`skipOccurrence`，只标这一天，本周承诺不释放）。
   - 「今天能多投入？」一排 `不加时 / +30 / +60 / +90`：把本周逾期项**全部**补进今天
     （2026-11-08 晚起时间只做参考，不再按「今天容量」过滤，见 §21）；答复会写进 `study.json` 的
     `catchUpMinutes`（0–120），下次默认带上，方便 AI 以后按这个推荐。
   - 更早欠账（`backlog`）只展示；容量够时自动被 `ensureCommitment` 收进本周/下一周。
2. **为什么没有「这周先不排 / 放弃」按钮**：活跃版本的承诺单元受 schema 触发器保护
   （`commitment_unit_no_rewrite` / `no_delete`，`PlanSchema.kt:141-147`），移出承诺要开新版本、
   换 unit id；那是「重排」不是「点一下」，留到 AI 协商重排一起做。周结算里「没做完」本来就会
   标 missed 并回欠账池，所以没有「永久放弃」。
3. **协商重排的形态（定稿，替代原来的「两套方案」）**：有未完成才问，只问一句
   「今天能不能多投入一些时间？」——够就今天补做、不顺延；不够就按路线往后几天推，
   可以每天多补一点，**优先在本周内消化**；加时偏好记进 `study.json` 供以后调整。
   AI 先读 `overdue` 真数据再问，不自己数数；聊天侧实现下一步做。
4. 手动入口 v1 只到「点按钮」；同日稍后改成拖拽（见 §12），「待处理」卡片已删。

## 12. 2026-10-31：「待处理」卡片改成拖拽重排（侧栏 → 日期）

用户反馈「点按钮」不符合手动排计划的直觉：要能把节点直接拖到想排的那一天。

- **入口**：计划标题旁「重排 · N」（N = 本周逾期 + 更早欠账）；直接拖起某天列表里的节点时侧栏自动滑开。
- **侧栏三组**（`PlanRearrange.kt` 的 `PlanRearrangeDrawer`）：本周到期没做（`planOverdue.thisWeek`，
  拖 = `moveOccurrence`）、更早欠下（`planOverdue.backlog`，拖 = `placeUnit`）、
  待安排（`planPool`，拖 = `placeUnit`）。
- **落点**：七个日期格（`DayChip`）或「当天安排」整块（= 当前选中的那天）；hover 的日期格放大 + 高亮，
  今天以前的日期变淡、不接收。
- **跨周**：一次只在同一周里挪——拖到别周的格提示「要跨周先把节点放到待安排池」；
  欠账/待安排的节点拖到下一周某天则走 `placeUnit`：往那一周的承诺里增量追加一条，再铺 occurrence。
- **排序**：当天列表按路线索引（`plan_unit.sort_order`）排，不按创建/拖动先后；同一 unit 拆分的
  occurrence 用 `created_at` 兜底。SQL 在 `PlanQueries.plannedOccurrences` / `dayItems`：
  `ORDER BY COALESCE(pu.sort_order, 2147483647), d.created_at`。
- **动效**：侧栏 `AnimatedVisibility`（滑入/淡入）；打开时内容整块让开 296dp（否则日期格被抽屉盖住，
  拖不过去）；拖起的 ghost 跟手（阴影 + 轻微放大）；列表行 `animateItem()`；落手结果提示留在侧栏底部
  （成功绿、失败红）。日期格 hover 用 `animateColorAsState` + `animateFloatAsState`。
- **提示字段**：`AppRuntime.planRearrangeHint` / `planRearrangeHintIsError`——不能复用 `planMessage`，
  因为每次 `refreshPlan()` 都会清空它；侧栏开合时清掉提示。
- 旧的 `PlanOverdueCard`（今天做/顺延一天/跳过 + 加时 chips）已删除；`planDoToday` / `planDeferOneDay` /
  `planSkipToday` / `planCatchUpToday` 仍留在 `AppRuntime`（AI 协商重排会复用同一批命令），
  `study.json` 的 `catchUpMinutes` 仍作为加时偏好保留。
- 验收：`PlanRearrangeRuntimeTest` 4 个用例（路线排序、逾期拖到某天、待安排池拖入、过去/跨周拒绝）+
  `LearningPlanPlaceUnitTest` 6 个后端用例；`features:learning-process:jvmTest` 34 个、
  `:app:desktop:test` 276 个全绿。

## 13. 2026-11-03：笔记 → 计划节点自动完成（「AI 自动完成」列表）

笔记里把某个计划节点讲清楚了，就不必再去计划页手动勾一下：知识库「保存并整理」成功后自动判一次，
满足条件的节点直接标记完成，并在计划页留一条可撤销的记录。

- **候选**：本周（`planState().week`）所有 `status=planned` 的节点，不限今天/逾期；未来周不参与。
  候选超过 20 个时先按「标题/章节里的二字片段在正文出现的次数」筛，只把最相关的送进请求。
- **触发**：`KnowledgeScreen.organizeNote` 成功分支末尾（正文 ≥400 字且有候选才发请求）；
  判定逻辑在 `PlanLink.kt`，提示词要求模型输出
  `{"completions":[{"occurrenceId","coverage","confidence","evidence","reason"}]}`。
- **自动完成条件**（全部满足）：`coverage=full`、`confidence ≥ 0.8`、`evidence` 写了判断依据（长度 ≥8）；
  证据可以是正文摘录，也可以是语义概括——**2026-11-09 晚起不再要求逐字出现在正文里**，是否「讲完了」
  完全交给模型按语义判断（原逐字校验已删：它与「笔记用自己的话复述」冲突）。节点不在屏蔽名单、同一篇笔记最多 3 个。
- **执行**：`AppRuntime.autoCompletePlanNodes` 逐条 `completeOccurrence`，`Actor.SYSTEM`、
  `origin = "knowledge.auto-complete"`；结果写账本 `<home>/plans/auto-completions.json`
  （`entries` + `blocked`，写前 `.bak`、坏文件改 `.bad-<ms>`，语义在 `AutoCompletionStore.kt`）。
  已完成或已有生效记录的节点会跳过（幂等）。
  2026-11-09 起还有一条**计时来源**：节点累计实际计时达到预估（至少 5 分钟）时，
  `stopTimerAndLog` 调 `autoCompleteNodeFromTimer`，`origin = "timer.auto-complete"`，
  账本里 `kind="time"`、带 `actualMinutes/plannedMinutes`、`noteId` 为空——同一张卡片、同样的撤销/屏蔽。见 §23。
- **UI**：计划页「当天安排」卡片下方多一张「AI 自动完成」卡片（有记录或屏蔽数 >0 时才出现）：
  每条 `✔ 节点标题｜撤销`，第二行 `计划日 · 来自《笔记名》`（点这一行跳回知识库并选中证据句，
  走 `pendingKnowledgeReveal`）+ 章节 + 「看证据」折叠。发生自动完成时 `planMessage` 提示
  「AI 根据《note》自动完成了 N 个节点，可在下方「AI 自动完成」里撤销」。
- **撤销 / 屏蔽**：卡片「撤销」= `uncompleteOccurrence`（改回 planned）+ 把节点写进 `blocked`，
  以后不再自动勾；在计划页手动把自动完成改回没做，同样会进 `blocked`。「解除屏蔽（N）」清空整批。
- **桌面通知**：`UiPrefs.notifyAutoCompletion`（默认关；设置页「笔记联动」→「弹桌面通知」），
  走 `Notifier`，只在本次真的自动完成了节点时发。
- **限制**：只在笔记整理成功时判一次，不后台轮询；模型判断错了要用户撤销（撤销后该节点永久屏蔽到解除）；
  跨周节点不参与；判定依赖正文证据，证据被改写后重判会自然失效。计时来源不经过模型（时长本身就是证据），
  同样是「够了就勾」、错了就撤销。
- 验收：`PlanLinkTest` 8 个 + `AutoCompletionStoreTest` 7 个；mock 端到端（`/tmp/mock_llm5.py`，8983：
  `kind=extract` + `kind=planlink candidates=14` → 账本写入 1 条、`plan.db` occurrence 变 `done`、
  计划页卡片刻 1 条；撤销后回 `planned` 且出现「解除屏蔽（1）」；解除后再整理会重新自动完成；
  点「来自笔记」跳回正文并选中证据）。

## 14. 2026-11-03 晚：删掉「第一周在过去 → 挪到本周」的修复机制

用户报：上周创建、本周还在进行的计划，首周必然落在过去，计划页会误弹「这份计划的第一周在过去」横幅
（这个机制原本是给「模型把周次写成上一年」兜底的，但 `planWeekCards` 会列出当前版本的全部周，含已经过去的周）。

- **删除**：`AppRuntime.planStartsInPast`、`AppRuntime.repairPastPlan()`（连同只给它用的
  `UnitDraft` / `CommitmentDraft` import）；`PlanScreen` 里的触发行与 `PlanStaleBanner`；测试 `PlanRepairTest`。
  `uiCommand` 保留（计时器开始/结束还在用），origin 从 `ui.plan.repair` 改为 `ui.plan`。
- **为什么可以删**：`advanceRhythm`（`features/learning-process/.../PlanAdvance.kt`）每周自动
  「补本周承诺 → 结算旧周（结转 ≤2 进本周）→ 补下周 → 铺天」；`LearningPlanRhythmTest` 已覆盖
  「两周没打开应用，本周照样从待安排池补齐」。正常周滚动不再需要用户手动点「挪到本周」。
- **保留**：`PlanTools.create_learning_plan` 的创建时校验——第一周必须等于本周、拒绝早于本周的 ISO 周；
  agent 新建计划排错周次时仍会直接报错（`PlanToolsTest.aPlanThatStartsNextWeekIsRejected`）。
- 验证：删后 `:app:desktop:test` 与全仓 `check` 全绿；grep 无 `planStartsInPast` / `repairPastPlan` / `PlanStaleBanner` 残留。

## 13 2026-11-08：计划文档只读、计划页只跳转

- 计划页右上「计划文档」不再弹编辑框：点击 = 跳到知识库并打开这份计划的文档（`AppRuntime.openPlanDocs`）。
- 知识库里新增只读「计划文档」区（`PlanDocPage.kt`）：左栏 计划 → 总览 / 各周，右栏 `MarkdownText` 预览；
  不可编辑，要改跟 AGENT 说。计划文档放在 `<home>/plans/docs/`，**不参与**笔记抽取、概念索引、知识网络与自动完成。
- 每周文档 `<planId>/<week>.md` 由 `plan_week` 写入（本周重点 + 每天条目 + 类型/分钟）。

## 14 2026-11-08：计划页重做（阶段条 / 周刻度 / 日折叠像素 / 资源展开 / 放弃 / 周日总结）

- **阶段**成为页面主结构：`plan_stage`（后端 `stages()`/`setStages()`）按周区间给名字和重点；
  页面头部一行「阶段 …」+ 一排阶段 chip（`PlanStageStrip`，名字 + 等宽 `done/total`），点击跳到该阶段第一周。
- **周**压成刻度条（`PlanWeekTicker`）：一条横轴 + 一个「本周」刻度，拖/点切换周；不再一天一个大卡片。
- **每天折叠**（`PlanDayAccordion`）：每天一行（日期、完成进度、件数），今天默认展开；
  完成进度是**像素方块**（`PlanPixelProgress`，每件一个 11dp 方块，超过 14 件显示 `+N`）；
  节点行保留方形圆角勾选框，点标题展开该节点的**学习资源**（`PlanResourceList`，`unit_resource` 表：
  `bilibili`/`web`/`doc`/`other` + 标题 + 可选定位串，点击用 `LocalUriHandler` 打开 URL）。
- **右侧阶段进度栏**（`PlanStageRail`）：按阶段显示「阶段 M/N + 名字 + 细条」，点击跳该阶段第一周；
  只在窗口宽度 ≥ `TrellisBreakpoints.medium`（1100dp）且有阶段时显示。
  ⚠️ 判据必须用**窗口宽度**（`LocalWindowInfo.current.containerSize.width`），不能用页面 `BoxWithConstraints.maxWidth`——
  2026-11-08 白天时计划页还被外壳包在 `widthIn(max = 1120.dp)` + 左右 20dp 里，页面 maxWidth 在 1440pt 窗口下只有 1080dp，
  永远小于 1100，右栏会恒不显示（`PlanPageParts.shouldShowStageRail` 注释里记了这坑）。
  **2026-11-08 晚改版后**计划页和对话/知识库一样是 flush 页（`AppScreen.flushPage` 含 `TAB_PLAN`），
  页面拿整幅宽度、正文列自己 `widthIn(max = 1120.dp)` 居中；判据仍看窗口宽度这条不变。
- **左栏贴边、可拖宽**（2026-11-08 晚）：计划列表和对话/知识库共用 `ListSidebar` + `ListSidebarSplitter`
  （贴导航栏、220–360dp 拖动调宽、三页共享同一个 `ListSidebarState.width`）。
  改之前整页被外壳居中限宽，1440 窗口里左栏距离导航栏还有 200dp+ 的空白，看着像表单页；现在左栏紧跟在导航栏后面。
- **周总结**：周日当天（`PlanStateView.reviewableWeek == 当前周`）页面底部出现「本周总结」+「生成本周总结」；
  生成后显示 `narrative` 与统计（完成单元 X/Y、投入时间）。周六及之前不出现；生成过的不再出现。
- **放弃计划**：计划设置弹窗底部「放弃这个计划」→ 二次确认；`abandonPlan` 把计划与 process 标 `ended`、
  写 `process.abandoned_at`、发 `plan.abandoned` 事件；已排节点保留、不再排期、不再自动排周；
  左栏行变灰「已放弃」、页头副标题「· 已放弃」，回看只读。
- **B 站目录清洗**（`PlanTools.cleanPartTitle`）：去掉「第N集 / P1 / 【】 / ()」等噪声，过滤片尾/预告/花絮/彩蛋/广告/关注/合集/简介，
  少于 3 个有效分集时退回原始目录并记警告；单元标题统一「第N章 · 清洗后名字」，
  同时把「第 N 集（mm:ss）」写成 `bilibili` 资源，不再把分集名直接当学习点。
- **首周安排**：`research_plan` 出完阶段大纲（+目标/里程碑/大纲文档）后，同回合 `planOneWeek` 先把第一周排出来，
  而不是等用户再催；`plan_week` 改成**先写周文档再排天**：周文档写失败就不排，`appendUnits` 失败则保留文档并报错。

## 15 2026-11-08 晚：周排布去重/容量收敛 + 顺延不回填 + 抽屉让位（质量验收修复）

真实应用四周模拟（12-06 前）暴露出「周一只堆 20 件、之后 6 天为空、同标题重复 11 次」。原因与修法：

- **`plan_week` 的预算语义错了**：`assignDays()` 里把「一周预算 `dailyMinutes*7*0.8`」当成「一天预算」用 →
  整周条目全落周一。现在按**天**算预算 `dailyMinutes`，并且先把本周已有的安排算进当天已用分钟（`existingMinutesByDay`），
  不再只看自己刚生成的条目。
- **容量上限**：本周从今天到周日**剩余容量** = Σ `max(0, dailyMinutes - 已有分钟)`。
  条目先去重（同标题 + 互相包含视为同一条），**所有去重后的条目都进待安排池**（`appendUnits`），
  只有放得下的才 `placeUnit` 到具体某天；放不下的留在池里，等下周或手动拖。
  周文档会多一行「本周还剩 N 条排在待安排池」。原实现把所有条目都塞进本周，再把周文档重写成被塞进去的那几条
  （上一版周文档里的 11 条因此从文档里消失）。
- **重复排周不再翻倍**：既有的本周标题（`plan.week()` 的 `commitment.units` + `days.items`）与批内标题都先过滤；
  整周都是重复时直接返回「这一周的条目已经在计划里了，没有重复排；要调整就直接拖节点。」
- **顺延后旧周回填**：`materializeWeek` 以前只查本承诺下的 occurrence，顺延/拖到下周后旧周以为「这个单元还没排」，
  又插回原来那天（现场：顺延 9 条后当天仍 20 件，且同一 unit 同时有旧日与次日两条 planned）。
  现在额外查 `movedAway`（`status='planned' AND moved_from IS NOT NULL AND day 不在本周`）并跳过。
- **跨周顺延的承诺归属**：`cmdMoveOccurrence` 跨周时把 occurrence 挂进**目标周**的 commitment（没有就建一个），
  事件的 `week` 记目标周、另带 `fromWeek`，不再是「日期在下一周、承诺还在上一周」的错位状态。
- **重排抽屉**：宽窗（≥1280dp）改为内容让开 296dp，而不是抽屉恒覆盖；抽屉打开时右侧阶段进度栏让位隐藏，
  避免 296（抽屉）+ 232（左栏）+ 196（右栏）把中间正文压成一列字。
- **计划设置**：保存失败时错误显示在弹窗内、弹窗不关；`planNotice` 页面级提示条用于「不会被 `refreshPlan` 清掉」的错误。
- **会话存储**：`ChatStore` 写会话改为临时文件 + 原子替换；坏文件改名 `.bad-<ts>` 留档，不再静默从列表消失。

## 16 2026-11-08 深夜：`plan_week` 改成「一周只排一次」（二轮 GUI 复验修复）

第二轮黑盒复验发现：同一句话「排一下本周的每日安排」发两次，`plan_week` 会追加两批共 15 个近义单元、
把当天从 60 分钟加到 130 分钟，并把周文档重写成部分条目（文档 1 条、库里 3 条）。

- **幂等**：`planOneWeek` 在调模型之前先读 `plan.week(planId, week)`；只要该周已经铺过天
  （`days.flatMap { it.items }` 非空）就直接返回
  「这一周已经排过了（N 条）；要调整就直接在计划页拖节点，或告诉我把哪几条改到哪天。」
  —— 不再调模型、不追加单元、不写文档。重排/微调交给拖拽与 `PlanAdminTools`。
- **去掉「第一条必放」例外**：容量为 0 时不再硬塞一条（现场回复「剩余容量只够 0 分钟」却仍加了 30 分钟）。
- **不再回「排好了：0 条」**：没有新条目时直接把上面的说明作为工具结果返回。

回归测试：`PlanWeekToolsTest.runningTheWeekTwiceDoesNotDuplicateItems` 断言第二次 `placed=0`、
note 含「已经排过了」、单元与 occurrence 数不增。

## 17 2026-11-08：放弃的计划可以删除（软删除）

- **入口**：计划设置弹窗。当前计划是「已放弃」时，危险区不再显示「放弃这个计划」，改成
  「删除这个计划」+ 二次确认；进行中 / 正常结束的计划看不到这个按钮。
- **语义**：软删除。后端 `process.deleted_at` 写时间戳、事件 `plan.deleted`，`plans()` 与
  `activePlanId()` 过滤掉它，左栏和计划页都不再出现；数据行与事件保留（事件溯源触发器禁止物理删），
  用于追溯。以后要恢复只需清掉 `deleted_at`。
- **失败处理**：只有 `abandoned_at` 非空的计划能删，否则返回 `E_STATE`，页面顶部用 `planNotice`
  显示原因（不会被 `refreshPlan()` 清掉）。
- **删后状态**：清空 `planFocusId` 并 `refreshPlan()`，自动落到下一个未删计划；没有就回空态。
- **测试**：`LearningPlanAbandonTest.onlyAnAbandonedPlanCanBeDeletedFromTheList` /
  `aNormallyEndedPlanCannotBeDeletedEither`；`PlanDeleteRuntimeTest`（删除后列表消失、
  焦点清空、进行中的计划走同一入口被拒）。

---

## 18 2026-11-08：删除计划连带目标 + 周刻度固定格高

### 18.1 删除已放弃计划时，目标不再留下

用户反馈「删除学习计划，学习目标没有删除」。原因：`deletePlan` 只软删了计划
（`process.deleted_at`），`process.goal_id` 指向的目标原样留在目标页。

现在的级联规则（在同一个 `deletePlan` 里完成，不新增公开命令）：

- 目标下**没有别的未删计划**（`process.goal_id = ? AND id <> 当前 AND deleted_at IS NULL`
  计数为 0）→ 目标跟着软删：`goal.deleted_at` + `updated_at`，事件 `goal.deleted`
  带 `goalId / planId / milestones / soft=true`；里程碑数据保留，只是随父目标从目标页消失。
- 目标还挂着别的计划（包括已放弃但未删除的）→ 只解绑：`UPDATE process SET goal_id = NULL`，
  事件 `plan.goal_unlinked`（payload `goalId`、`reason`）。共用目标不能因为删一个计划被删掉。
- 读侧：`goals()` / `goal()` / `goalRow()` 过滤 `deleted_at`，`plansOfGoal()` 过滤
  `pr.deleted_at IS NULL`；`AppRuntime.deleteAbandonedPlan()` 成功后补一次 `refreshGoals()`，
  目标页立刻跟手。

### 18.2 周刻度：固定格高，选中只换底色

之前选中格竖线 22dp、普通格 14dp，`LazyRow` 顶部对齐 → 看起来只有**下边界**被往下推，
上边界与相邻刻度不齐。现在 `PlanWeekTick` / `PlanPendingTick` 统一：

- 格子固定 `74×70dp`、内容垂直居中；竖线放在固定 `22dp` 轨道里，选中只变高变粗；
- 选中只改底色（`accentSoft` 0.35）、字重与颜色，**格子几何完全不变**；
- 一排刻度的上下边界始终对齐。

### 18.3 测试

- `LearningPlanAbandonTest.deletingTheOnlyPlanOfAGoalAlsoHidesTheGoal`（唯一计划 → 目标消失、
  `goal.deleted` 事件、里程碑保留）
- `LearningPlanAbandonTest.deletingAPlanLeavesAGoalThatIsStillUsedByAnotherPlan`
  （共用目标 → 只解绑、`plan.goal_unlinked`、目标仍可见）
- `PlanDeleteRuntimeTest.anAbandonedPlanDisappearsFromTheListAfterDeleting` 追加
  「删除后 `runtime.goals` 里也不再有这个目标」的断言。

---

## 19 2026-11-09：去掉重复的顶部阶段条，只保留右侧「阶段进度」

05:40 实拍：1440dp 窗口里计划页同时有顶部阶段条（3 个一行、两行）与右侧「阶段进度」栏，
两处列的是同一份阶段、同一套 `done/total`，内容重复且把周刻度往下挤。

- **删掉** `PlanStageStrip` / `PlanStageChip` / `STAGE_CHIPS_PER_ROW`（`PlanPageParts.kt`），
  计划页不再渲染顶部阶段条（`PlanScreen.kt` 的 `item { PlanStageStrip(runtime) }` 一并去掉）。
- **保留** 右侧 `PlanStageRail`：阶段名 + 进度（有单元时 `done/total`，还没单元时退化成
  `W48–W51` 周区间）+ 细进度条；点一行仍跳到该阶段第一周。
- 右栏判据不变：`shouldShowStageRail(windowWidthDp, stageCount)`——窗口 ≥ 1100dp 且
  该计划有阶段才显示；抽屉打开时让位隐藏。窄窗口暂时看不到阶段（大纲仍可在「计划文档」里看）。
- 回归：`PlanPageRuntimeTest.PlanStageRailVisibilityTest` 仍钉右栏显隐的窗口宽度断点；
  `:app:ui:compileKotlinJvm` 通过。

## 20 2026-11-09：节奏与本周安排都交给 AGENT（research_plan 只按参数做事）

问题：`research_plan` 建完空壳计划后自己用建议时长排了第一周，紧接着又用 `ask_choices` 问用户每天投入——用户选完只影响之后补承诺/铺天，第一周已排的不动，既冗余又看不出「先排后问」；工具回执还写着「下一步：必须 ask_choices…」这种替模型做决定的指令。

现在：宿主不替 AGENT 决定行为，`research_plan` 只按模型传的参数做事：

- 显式传 `dailyMinutes` 才写进节奏（`set_plan_daily_minutes`）；不传只给建议值，不写。
- ~~传 `scheduleFirstWeek=true` 才在同一轮把本周排出来~~（已废弃，见 §22：2026-11-09 起默认就排本周，只有显式 `scheduleFirstWeek=false` 才留空壳）。
- 当时不传 / `false` 就只建壳、大纲、阶段、里程碑（2026-11-09 起只有显式 `false` 才这样，见 §22）。
- 回执只报事实（建了什么、排没排、每天投入是建议值还是已写入），去掉「必须 / 下一步」这类指令式文案。

问不问时长、排不排本周全由 AGENT 判断，`<task:work>` 提示词同步改成中性描述。

测试（§22 之后的口径）：`ResearchToolsTest` 默认路径断言本周已排、周文档已写；`explicitDailyMinutesAndScheduleFirstWeekWriteThePaceAndScheduleTheWeek` 钉住写节奏 + 排周；`dailyMinutesAloneStillSchedulesThisWeekWithThatPace` 与 `scheduleFirstWeekWithoutDailyMinutesUsesTheSuggestionButDoesNotWriteIt` 分别钉住「只给时长也会排本周」「建议值不写节奏」；`scheduleFirstWeekFalseLeavesTheWeekEmpty` 钉住显式留空壳。

---

## 21 2026-11-08 晚：周时间只做参考（排程不再按容量截断）

用户定的口径：「时间存在的本意是让 AGENT 作为参考值决定大概安排多少周任务，因此更多的应该是参考作用；计划产生可适当进行更多时间投入的补做和整体延期，我的倾向是告诉他参考就够了，不做硬性限制。」后端侧细节见 `docs/plan-backend.md` §14。

- **`plan_week` 不再拒排**：删掉 `remainingCapacity`/`placeableIndices`/`overflow` 与「这一周没有剩余容量了…这些条目不硬塞」的早退；条目全部追加、全部铺天，回执在合计超过参考量时补一句「时间只做参考，超出也照排，觉得多可以整体延期」。
- **摊天**：`assignDays` 先按每天参考值往后摊，整周都到参考值后回环挑**当前分钟最少**的那天（不堆周日）。前置知识检查、去重、「一周只排一次 / `replan=true`」这些保护不变。
- **全局周表**：`create_learning_plan` 每周预算改成 `dailyMinutes × 7`（去掉 ×0.8），跨过参考值的那一条也带上；截止日仍截断，剩下的留待安排。
- **节奏引擎**：`pickPoolUnitsForWeek` 参考量 = `daily × 7`，跨参考的那条也带上、池子够时每周至少 3 条；每周 ≤7 条只是引擎步子上限。
- **补做**：「今天能多投入？」不再按今天容量过滤，逾期项全部挪到今天，回执报条数与参考分钟数。
- **文案统一成「参考」**：计划设置弹窗、待安排合计（「参考每天 D 分钟，约 K 天」）、移动端「每天投入」「×7 只是参考投入」、`set_plan_pace` 回执与工具描述。
- **只读事实保留**：逾期投影的 `remainingMinutes` 与管家上下文「本周还剩约 N 分钟可投入」仍是参考信息，不改语义。
- **测试**：`PlanWeekToolsTest.itemsBeyondTheDailyReferenceAreStillScheduled`（85 > 参考 60 也全排）、`PlanToolsTest.aDeadlineStopsTheGlobalWeekTableAndLeavesTheRestInThePool`（30×7=210/周、两周 22 个、池 18）、`LearningPlanRhythmTest.theWeeklyPickIsCappedAtSevenUnits`（7 条上限不变）。

---

## 22 2026-11-09：空壳计划不再沿用上一份计划的天数据；`plan_week` 不限本周

三条现场 bug（用户截图）与修法：

1. **空壳计划显示上一份计划的「周一 6 件」**。`AppRuntime.refreshPlan()` 选周的 fallback 是
   `planSelectedWeek ?: state.week ?: selectableWeeks.firstOrNull()`；空壳计划 `map.weeks` 为空 →
   `week == null`，而 `else` 分支只清了 `planReview`，`planDays / planSelectedWeek / planSelectedDay / planDayView`
   全部留着上一份计划的值——头部写「0 周」、天里还挂着别人的条目，点开又说「这一天还没有排内容」。
   **修法**：`else` 分支把这些字段全部清空；回归测试 `PlanPageRuntimeTest.switchingToAShellPlanClearsThePreviousPlansDays`。
2. **`plan_week` 排错计划**。它取 `plan.activePlanId()`，而后端实现是
   `SELECT … WHERE p.status='active' … ORDER BY p.created_at LIMIT 1`（**最早**创建的 active 计划）；
   「计划完成」是派生态（`isPlanComplete` 不改 status），旧的学完计划永远占着 activePlanId。
   于是新建计划的排周会落到旧计划上，被「已完成只读」拒绝（截图②）；
   周自动排周轮次里 `runWeeklyPlanRound` 已经 `selectPlan(planId)`，`plan_week` 却不看选中态（截图③）。
   **修法**：`plan_week` 增加可选 `planId`；不传时默认用「计划页当前选中的计划」（`PlanWeekTools(selectedPlanId = { planFocusId })`），
   最后才退回 `activePlanId()`；周自动轮的提示词里显式写 `plan_week(planId="pln_…")`。
3. **已完成计划不再跑节奏**：`advancePlanRhythm` 跳过 `completedPlanIds`（结算/补承诺/复盘都只为还在推进的计划准备）；
   自动排周名单原本就有 `!planIsComplete(...)` 过滤，保持。

顺带把安排工具从「只能排本周」放开：

- `plan_week` 的 `week` 可以指任何一周（本周 / 下周 / 某个 ISO 周），新增 `weeks=1..6` 一次排连续几周（逐周串行，前一周没排成就不再往后排）。
- **建计划至少排本周**：`research_plan` 建完空壳默认就排本周（`scheduleFirstWeek` 默认 true，显式 false 才留空壳）；
  `create_learning_plan` 本来就把全局周表铺到本周的天上。空壳只是排周失败时的短暂中间态，而且修好 1 之后不会再有假数据。
- 回归：`PlanWeekToolsTest.planWeekTargetsTheSelectedOrExplicitPlanInsteadOfTheOldestOne`（选中 / 显式 planId / 不存在的 planId 报错列候选）、
  `planWeekCanScheduleSeveralConsecutiveWeeksAtOnce`（weeks=2 两周都排、两份周文档）。

## 23. 2026-11-09：计时到点也自动完成（计时器来源）

用户要的「笔记**或任务时间**判定完成」里的第二个来源：一个计划节点累计计时达到预估时长，就自动勾上。
口径（用户定的）：自动勾 + 可撤销（与笔记自动完成同一套账本/撤销）；阈值 100% 且至少 5 分钟；
来源顺序上先做计时器，活动归因等归类信号升级后再接（见文末「暂不做」）。

- **后端事实**：`LearningPlanService.actualMinutesByOccurrence(planId, untilDay): Map<String, Int>`
  （默认实现返回空）。SQLite 实现在 `PlanQueries.kt` 的 `actualMinutesByOccurrenceOf`：
  只算 `ended_at IS NOT NULL` 的 session，分钟按起止自己算（与 `cmdEndSession` 同一口径），
  只看排期日 `<= untilDay` 的节点——未来的节点计时不该算成「做完了」。
- **纯规则**（`PlanLink.kt`，无模型调用）：`timeLinkCompletion(candidate, actualMinutes)`——
  实际 ≥ `max(预估分钟, PLAN_LINK_MIN_TIME_MINUTES=5)` 才算完成；
  证据写成事实句「计划 X 分钟，实际计时 Y 分钟（Z%）」/「实际计时 Y 分钟（节点没有预估时长）」，
  `confidence = 1.0`，`reason = "计时达到预估时长"`。
- **接线**：`AppRuntime.stopTimerAndLog` 关掉节点 session 后调 `autoCompleteNodeFromTimer`：
  跳过 `blocked` / 已有生效记录的节点；候选从该节点**所在计划**的周视图取；累计计时到点就
  `completeOccurrence`（`Actor.SYSTEM`、`origin = "timer.auto-complete"`）、写账本
  （`kind = AutoCompletionKind.Time`、`actualMinutes/plannedMinutes`、`noteId = ""`）、
  `refreshPlan`、按 `uiPrefs.notifyAutoCompletion` 发桌面通知；`planMessage` 追加
  「计时到点，已自动勾上，可在「AI 自动完成」里撤销」。写库失败只提示、不影响 session 记账。
- **账本**：`AutoCompletionEntry` 新增 `kind: AutoCompletionKind`（`note`/`time`，旧文件缺省按 `note`）、
  `actualMinutes`、`plannedMinutes`；编码时 `kind` 总是写、分钟数 >0 才写（旧账本读进来不报错）。
- **UI**：计划页「AI 自动完成」卡片——计时来源的行显示「来自计时 · 实际 N 分钟（预估 M 分钟）」，
  不可点（没有笔记可跳）；空态文案同时提到笔记与计时；撤销/屏蔽/解除屏蔽与笔记来源共用。
- **测试**：`PlanLinkTest`（到点 / 差一分钟不算 / 无预估要满 5 分钟 / 超时百分比）、
  `AutoCompletionStoreTest`（计时记录往返 + 旧账本缺省 note）、
  `LearningPlanServiceTest.actualMinutesByOccurrenceSumsClosedSessionsOfScheduledNodes`
  （两段已结束 session 累计、未结束不算、untilDay 之前的节点不参与）。
- **暂不做**：活动归因来源先不用。用户指出「现在的归因不够聪明」——候选只取当天、提示词不发 URL/域名、
  片段硬切 ≤20 分钟、`AttributionSource.RULE` 还没接、AI 提议全停在 `PROPOSED` 要逐条接受。
  等归因升级（URL/域名信号 + 规则记忆）后，把 `activityOccurrenceMinutes(day)` 的映射传给同一个
  `timeLinkCompletion` 即可，不需要再动账本与 UI。
- 验收：`./gradlew :app:ui:jvmTest :features:learning-process:jvmTest` 全绿
  （本机没有 Android SDK，`./gradlew check` / `:app:ui:test` 跑不了，用 `jvmTest` 代替）。

## 24. 2026-11-09 晚：资源优先（项目聚合资源）

用户定的口径（2026-11-09 晚）：

> 之前的决定是设计工作、生产项目做实践，像这种课内学习就要重资源。
> 目前学习的内容都尽量找到资源，这样才能起到项目聚合资源的设计理念。

所以**资源是计划的一部分**，不是可有可无的附件：

- **写进哪里**：总文档 `## 阶段` 下每个阶段尽量带 `- 资源：`（`- [名称](url) — 一句说明`）；
  写阶段（`update_plan` 的 insert/update stages）和 `schedule_week` 排完都会把阶段资源挂到节点（`source=plan_doc`）：
  标题对得上的资源每节点最多 3 条；**对不上任何节点的整份资源（一整本书、整个专栏）挂到该阶段覆盖的每个节点**，
  让用户自己从中找——「不是资源找节点，是节点找资源」。
  也可以 `update_plan` 的 `update units` 直接 `set.resources`（`source=agent`）。
- **谁负责找**：`research_plan` 的阶段表 JSON 新增 `resources:[{title,url,note}]`，提示词要求优先用调研里真实出现过的链接、
  课内学习每阶段至少 1 条、找不到就空数组（宁缺毋滥、**不编造 URL**）；大纲渲染成 `- 资源：`，第一周排完后按标题挂到节点。
  `ManagerPrompt` 的 `<goal_requests>` 与 `PlanEditTools` 的 guidance 第 5 条也写了同一条口径。
- **共用逻辑**：`app/ui/.../PlanStageResources.kt` 的
  `attachStageResourcesToWeek(service, docStore, planId, week, origin)`（单周）+ `attachStageResourcesToPlan(service, docStore, planId)`（整份计划、幂等）
  + `stageResourceScore` / `stageResourceKind`（标题精确 100 / 包含 60 / 有效词重叠 40；kind 由 URL 推断：bilibili / doc / web）。
  `PlanEditTools.scheduleWeek`、`PlanEditTools.writeStages`、`PlanWeekTools` 都走它。
- **阶段标题必须带周区间**：`- 阶段 N · 名称（2026-W46 – 2026-W47，约 N 周）：聚焦`，
  否则 `PlanDocStore.stageForWeek` 定位不到阶段、`schedule_week` 就挂不上资源。
- **找不到资源时**：回执明说「这次没找到靠谱的阶段资源，大纲里留空（不编造链接）；你把资料发我，我再补上」。
- **B 站是一条重要途径**（2026-11-09 用户口径）：找资源时先 `search_bilibili` 搜一轮，搜不到就不用提，但不能连看都没看；
  `create_learning_plan` 支持 `bvids=[…]` 把互补的 2–4 门课导进同一份计划（各自按分集生成节点、按标题去重、
  每个节点的分集资源用**它自己那门课**的 bvid 拼链接），只在「两门课互补、节点不重复冲突」时才多门合并。
- 测试：`ResearchToolsTest.stageResourcesAreWrittenToTheOutlineAndAttachedToThisWeeksUnits`、
  `PlanEditToolsTest.aWholeBookResourceLandsOnEveryUnitOfItsStage`、
  `PlanToolsTest.complementaryCoursesShareOnePlanAndKeepTheirOwnResources` / `bvidAndBvidsAreMutuallyExclusive`；
  全量 `./gradlew :app:ui:jvmTest :ai:jvmTest` 853 / 117 全绿。

## 25. 2026-11-09 晚：卡片行写清「学习 · 具体网页/窗口标签」

用户提的（截图指出「来自屏幕使用 · 实际 50 分钟」这一行）：

> 直接这样写：学习+具体网页标签

- **账本**：`AutoCompletionEntry` 新增 `sourceTitle: String = ""`（旧账本缺省空串，不报错）。
  屏幕使用来源时，取该节点归因片段里**停留最久**的窗口/标签标题，按 `segment.durationMs` 加权；
  没有标题时回退 `app` 名，再回退空串。实现是 `AppRuntime.activityOccurrenceTitles(day)`（只读、只用于展示）。
- **卡片（2026-11-09 晚再改，用户定）**：`kind = time && source = activity` → 只写
  「学习 · <标题>」（**不写分钟数**），没有标题时写「来自屏幕使用」；这一行右侧有两个入口：
  「详情」→ `AppRuntime.openActivityDay(entry.day)` 切到活动页并落在归因那天
  （`pendingActivityDay`，`ActivityScreen` 首次组合时消费并清空），「看证据」原样展开时长依据。
  计时来源仍是「来自计时 · 实际 N 分钟（预估 M 分钟）」。
- **回执**：`planMessage` 同时带上「学习《<标题>》」并保留「屏幕使用」字样，方便复盘时区分来源。
- **为什么**：只写分钟数分不清是刷视频还是读文档；写清具体网页/窗口标签，「项目聚合资源」的账才算对上。
- 测试：`ActivityAutoCompleteE2ETest` 断言 `sourceTitle` 等于注入的窗口标题
  （「【高等数学】第 1 章 第 1 节 - 哔哩哔哩」）；`AutoCompletionStoreTest` 断言 `sourceTitle` 落盘往返。
