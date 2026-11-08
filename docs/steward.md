# 管家会话（Steward）

> 一句话：一个常驻的「管家」会话，每天早晚各说两句、偶尔给一张能一键采纳的排期建议卡；
> 它和普通会话长得一样、用同一套工具和审批，只是多了主动发消息的入口和自己的人格。

实现记录：2026-11-03（P1）。旧的「计划检查」（`PlanCheckStore` / `plan-check` 会话）已被它取代并删除。

---

## 1. 它是什么

- 会话列表**置顶**的一行「管家」（`ChatSession.kind == SessionKind.Steward`），排在最前；
  副标题是「每天早晚问候 · N 轮」。
- 和普通会话共用一个容器、一套渲染、一套工具（`PlanAdminTools` 写操作仍要 `confirmed=true`，审批闸门不变）。
  区别只有三处：
  1. **人格提示词**：`StewardPrompt.systemPrompt()`，克制、不夸张、不说教；
  2. **主动入口**：应用回到前台 / 每分钟 tick 时自动检查晨/夜仪式（见 §3）；
  3. **建议卡**：模板化的一条排期改动 + 「一键采纳 / 调整一下 / 先这样」（见 §5）。
- 用户也可以直接跟管家对话：它和普通管理员会话一样能调工具、改计划，只是说话方式是管家。

## 2. 数据目录 `<home>/steward/`

| 文件 | 内容 |
|---|---|
| `settings.json` | 晨间/晚间时间（默认 `08:30` / `22:30`）、是否启用、桌面通知开关 |
| `checkpoints.json` | 仪式幂等标记：`lastMorningDay` / `lastEveningDay`（应用内日期字符串） |
| `memory.jsonl` | append-only 记忆：`at` / `day` / `kind` / `text` / `quote` / `topics` |
| `digests/<YYYY-MM-DD>.md` | 每日摘要（晨/夜消息发出时写），第二天早上拿来引用「昨晚：…」 |

- 所有读取失败/坏文件都退回默认值，不抛异常（与 `ChatStore` 的风格一致）。
- 启动时 `StewardStore.migrateLegacyPlanCheck()` 删掉旧的 `<home>/plan-check.json` 与
  `<home>/chat/plan-check.json`（幂等；用户拍板直接删，不迁移旧摘要）。

## 3. 晨/夜仪式规则

- **触发**：窗口重新获得焦点（`AppRuntime.onWindowFocused`）+ 后台每 60 秒 tick
  （`startStewardTicker`）。不是系统级闹钟，应用没开就不会发。
- **幂等**：checkpoints 记录最后发送日；同一天晨间/晚间各最多一次。
  `chatBusy`（正在跟 AI 对话）时直接跳过且**不消耗** checkpoint，下一分钟再试。
- **合并规则**：打开应用时已过晨间时间，且昨晚收尾也错过（`lastEveningDay != 昨天`）→
  把「昨晚收尾 + 今晨」写成**一条**消息（`MorningWithEveningCatchUp`），只弹一次通知。
- **内容生成**：先用 `StewardPrompt.template(...)` 拿确定性的计划事实拼一条兜底文案；
  配置了模型且未超时（8s）时用 LLM 润色到 ≤120 字；失败/超时直接发模板，不重试。
- **计划事实**（`StewardPlanFacts`）：今天计划几件/完成几件/多少分钟、本周逾期按天分组、
  更早欠下数量、本周剩余分钟。
- **通知**：只有「应用失焦 + 设置里桌面通知开」才发系统通知；前台只留会话里的未读红点。
  跨平台实现在 `Notifier.kt`（macOS `osascript` / Windows PowerShell Toast / Linux `notify-send`），
  命令失败静默（通知不是正确性的一部分）。
- **语气**：提示词要求克制：不夸张、不堆感叹号、不说教；一次只说一件最重要的事。

## 4. 记忆

- `memory.jsonl` 是 append-only 的：晨/夜发出的消息、用户回复（原样 quote）、
  建议被采纳/被拒绝各记一条。
- `kind`：`Morning` / `Evening` / `UserReply` / `Adopted` / `Rejected` / `Note`。
- **没有语义检索也能工作**：管家的读取窗口是三个确定性函数——
  `yesterdayDigest`、`recentDigests(days=7)`、`recentMemory(today, days=7, limit≈20)`，
  注入上限 <800 tokens。情景记忆的默认访问模式本来就是「时间 + 事件类型」，范围查询就够。
- 等记忆条目 >2000 或出现「上个月我说的那个办法」这类模糊回忆时，再在**同一份日志**上加
  FTS5 trigram（中文可用）作词法检索、必要时加向量索引；存储不用重写。
  调研结论见 `docs/research/tech.md` §1.1/§1.3/§1.5。

## 5. 建议卡（模板化改动 + 一键采纳）

- 模型：`StewardProposal(id, title, detail, changes)`；
  `StewardChange.Move(occurrenceId, day)`（挪已有的逾期节点）/
  `StewardChange.Place(unitId, day)`（把欠账单元排进某天）。
- 生成规则 `StewardProposals.build(...)`：
  - 逾期条目**最多 3 条**，按顺序挪到「明天..本周日」（周日则只有当天）；
  - 更早欠下的单元**最多 2 个**，`placeUnit` 进「明天..本周日」；
  - 已经在目标日的跳过；没有可动的不生成建议（消息照发，不带卡）。
- 渲染：`ChatScreen.StewardProposalCard`——紫罗兰渐变底板（`StewardViolet`）、「管家建议」chip、
  原话标题/说明、逐条建议小底板（左侧竖条 + title + 「挪到/排到 MM-dd」）；
  建议区最高 280dp，超出框内滚动，标题与按钮不跟着滚（采纳永远点得到）。
  未决定时显示「一键采纳 / 调整一下 / 先这样」。
- 采纳（`AppRuntime.adoptStewardProposal`）：**不再跑模型**，直接调后端已有命令
  （`moveOccurrence` / `placeUnit`，reason「管家建议」），逐条统计成功/失败，
  写一条状态行「—— 已采纳管家建议：挪 N 件。」并记 `Adopted` 记忆。
- 「先这样」记 `Rejected`；「调整一下」把「这条建议我想调整一下：」预填进输入框，用户补完再发。
- 状态落盘：`ChatLine.proposal` + `proposalState`（`Open` / `Adopted` / `Dismissed`），
  旧会话文件没有这两个字段时按无卡片处理。

### 5.1 自动采纳 / 无限重排（2026-11-07，G-C1 + G-C5）

- 开关：设置页「管家 → 自动采纳」，落 `steward/settings.json` 的 `autoAdopt`（2026-11-07 晚起默认**开**）。
- 准入三道闸（`autoAdoptAllowed`）：开关打开、应用**不在前台**（`windowFocused=false`）、
  今天还没到 `autoAdoptDailyLimit`。计数在 `steward/checkpoints.json`
  （`autoAdoptDay`/`autoAdoptCount`，换天归零）。
  - `autoAdoptDailyLimit = 0` 表示**不限条数**（2026-11-07 晚起默认 0）；其余值夹在 1–10。
- 白名单（`StewardProposal.isAutoAdoptable`）：**`Move` 和 `Place` 都自动执行**（用户给了无限重排权限）；
  跨周也允许——提案候选日从「明天到下周周日」。
- 触发点：晨/晚仪式成功发出后（`runStewardCheck` 非 `Busy`）与窗口失焦
  （`onWindowLostFocus`）。执行完在管家会话里把建议行标 `Adopted`、追加一条状态行
  「—— 自动采纳管家建议：挪 N 件、补排 M 个（应用不在前台，已自动重排）」，
  并记一条 `Adopted` 记忆 + `app.log` 一行；计划页数据刷新，改动仍可在计划页拖回/撤销。
- 不做：后台无人值守（G-C2）、自动采纳后弹通知（G-C3）、独立定时器/按日自动顺延（仍是建议驱动）。

## 6. 与普通会话的接线

- `sendChat`：`currentChatId` 的 kind 是 Steward 时，用
  `ManagerPrompt(base = StewardPrompt.systemPrompt() + 工具目录 + guidance)` +
  `ManagerRouter(prompt, allTools)`；普通会话走同一套（2026-11-07 起没有意图路由/稳定工具集开关）。
- 工具集与普通会话**完全一致**（全量工具，含计划写工具）；写操作仍受 `confirmed`/审批约束。
  后台节奏推进仍只由 `advanceRhythm` 做，管家不自动改计划。
- 用户在管家会话里说的话，原样记一条 `UserReply` 记忆（`rememberUserReply`）。
- **渲染完全同一套**：主动消息没有用户那一句，但分组后同样是 `ChatBlock.Round`（`user` 可空），
  回合头显示「管家」、过程折叠/耗时/元信息/撤销/复制照常；不再有「孤儿消息」的特殊样式。
  主动消息里如果发生工具调用，Step 行也按会话的过程行渲染。
- **对话列宽度**：整列 `widthIn(max = 760.dp)` 在剩余空间里居中，左右各 24dp 对称留白；
  用户气泡占列宽 62%，agent 正文占满整列。
- 过程行里「它说完了」这一步已删（`ToolAgent` 不再 note）；旧会话里存过的
  「它说完了，但 …」仍会显示成「· 还要补：…」，清楚的历史不丢。

## 7. 设置页

设置 → 管家（`SettingsScreen.StewardSection`）：

- 晨间时间：07:30 / 08:00 / 08:30 / 09:00 / 10:00 + 「关」；默认 08:30。
- 晚间收尾：21:30 / 22:00 / 22:30 / 23:00 + 「关」；默认 22:30。
- 桌面通知：开 / 关；状态行说明「应用在后台时才弹系统通知；前台只留会话里的未读红点」。
- 「打开管家会话」跳转到对话页并选中管家。

## 8. 代码地图

| 位置 | 职责 |
|---|---|
| `app/desktop/.../StewardStore.kt` | 设置/checkpoints/记忆/摘要落盘，旧 plan-check 迁移 |
| `app/desktop/.../StewardRuntime.kt` | 仪式编排：check() 幂等与合并、forceRun() 调试触发、模板/润色、记忆写入、通知 |
| `app/desktop/.../StewardProposal.kt` | 建议模型、变更类型、模板生成规则 |
| `app/desktop/.../AutonomyTrigger.kt` | 完成事件纯函数：完成前后 `stages()` 快照对比 → `StageDone` / `PlanDone`（空壳阶段、待安排池判定） |
| `app/desktop/.../AutonomyTools.kt` | 自主轮 7 个只读/提议工具：`read_learning_overview` / `read_knowledge_overview` / `search_web` / `search_exercises` / `recommend_exercises` / `propose_practice` / `rest` |
| `ai/.../steward/ProactivePrompt.kt` | 自主轮提示词覆盖层（只读+只提议、rest、知识网络默认已掌握）+ `<how_to_expand>` guidance |
| `app/desktop/.../Notifier.kt` | 跨平台系统通知（失败静默） |
| `ai/.../steward/StewardPrompt.kt` | 人格提示词、事实块、晨/晚/合并模板 |
| `app/desktop/.../ChatState.kt` | `SessionKind`、`ChatLine.proposal`/`proposalState`、`ChatSession.unread` |
| `app/desktop/.../ChatStore.kt` | kind / proposal / proposalState 的序列化（旧文件兼容） |
| `app/desktop/.../AppRuntime.kt` | 接线：focus/ticker、`appendStewardLine`、`sendChat` 人格分叉、采纳/拒绝/协商；自主轮（完成事件检测、只读工具集、未读/通知） |
| `app/desktop/.../ChatScreen.kt` | 管家置顶行、主动消息与普通会话同一渲染管线（`ChatBlock.Round.user` 可空）、建议卡（渐变+框内滚动） |
| `app/desktop/.../SettingsScreen.kt` | 管家设置区 |

测试：`StewardStoreTest`、`StewardRuntimeTest`、`StewardProposalsTest`、`NotifierTest`、
`ChatStoreTest`（kind/proposal 往返）、`ai` 的 `StewardPromptTest`；
自主检查：`AutonomyToolsTest`、`AutonomyTriggerTest`、`ai` 的 `ProactivePromptTest`。

## 9. 已知边界 / 后续

- 周复盘、月度总结、按天分级的激励还没做（P2/P3）。
- 建议卡目前只覆盖「逾期挪到本周剩余天」「欠账排进本周」两类；加时、跳过、改每天投入还没上卡。
- 通知点击不能直达会话（系统通知 API 限制）；在应用里打开管家会话即已读。
- 语义检索未做（见 §4 的触发条件）。
- 用户回复目前只作为记忆文本，不驱动「协商」状态机：协商靠用户直接在对话里说，管家按全量工具处理。

## 10. 调试（开发者）

`TRELLIS_DEV=1` 时，设置页「时间调试（开发者）」卡片里除了拨模拟日期，还有两个按钮：

- **触发晨间总结 / 触发晚间总结**：立刻按该仪式发一条，不看时间、不看今天发过没有，
  也不写 `checkpoints.json`（同一天可以反复点）。消息、记忆、摘要、建议卡都走真实路径，
  所以用它验收的是真实链路。
- 强制触发时**即使应用在前台也会尝试弹系统通知**（正常路径前台不弹），方便验证 `Notifier.kt`；
  通知开关仍然受设置页「桌面通知」控制。
- 触发后自动跳到管家会话；日志写在 `<home>/app.log` 的「管家调试：…」行。
  入口：`AppRuntime.debugStewardRitual(morning: Boolean)` → `StewardRuntime.forceRun(ritual)`。

## 5.2 每周自动排周计划（2026-11-08）

用户口径：每周开始时，在**这条计划自己的会话**里跑一轮「排本周」；管家可以代跑。

- 触发：管家 ticker（60s）与窗口回到前台时都会走 `runStewardCheck`，随后调 `AppRuntime.maybeRunWeeklyPlanWeek()`；
  判断规则在 `WeeklyPlan.kt` 的 `shouldRunWeeklyPlan`：默认开、**周日 18:00 后或周一**、同一 ISO 周只跑一次（`StewardCheckpoints.weeklyPlanWeek`）。
- 绑定：建计划时如果人在普通会话里，`AppRuntime.bindPlanSession` 把 `planId → chatId` 记进 `<home>/plans/sessions.json`；管家会话不绑定。
- 执行：每条未结束的计划优先进它绑定的会话；会话不存在/没绑定则由管家代跑（请求开头是「【代排】」）。
  这一轮用的是完整的工具与提示词（`buildChatRig`），和用户自己说话完全同一条链路；结果写回目标会话（用户和管家的消息都留痕，带「—— 本周已排 / 本周没排成」状态行）。
- 开关：设置页「管家 · 每周自动排周计划」，关掉后不会再自动跑；用户仍可在会话里说「排本周」。

## 5.3 自主检查（阶段/计划完成后，2026-11-08）

用户口径：**不用按钮、不用让用户点「检查一下」**——用户把计划推进到「一个阶段完成」或「一份计划完成」时，
管家静默跑一轮自主检查，看能帮用户做什么；没事就什么都不说。两条典型链路是「通识课学完 → 推一道练习」
和「一个就业向计划学完 → 给下一步方案」。

### 触发条件（`AutonomyTrigger.kt`）

- 完成一个节点时，`AppRuntime` 在命令前后各取一次 `service.stages(planId)` 快照，交给纯函数
  `detectAutonomyEvent(before, after, poolEmpty)` 对比；**只有两种情况触发**：
  1. **阶段完成（StageDone）**：同一个阶段 id 在 before 里没完成、after 里完成了（`total>0 && done>=total`）；
  2. **计划完成（PlanDone）**：after 里所有阶段都 `total>0 && done>=total`，且待安排池为空（`poolEmpty`）。
     计划完成优先于阶段完成（刚完成的是最后一个阶段时返回 PlanDone，并带上那个阶段）。
- **单节点完成不触发**：既不唤醒自主轮，也不标未读/发通知；单节点完成仍走原来的模板练习提议
  （`AppRuntime.maybeProposePractice`）。
- **练习卡由谁推**：阶段/计划完成、且配了模型时，练习由自主轮用 `propose_practice` 判断，
  模板提议跳过；没配模型时仍由模板兜底（行为同旧版）。
- **只对用户动作触发**：计划页勾节点（`origin = "ui.plan"`）与任务页勾节点（`origin = "ui.tasks.node"`）。
  笔记自动勾选（`knowledge.auto-complete`）、系统节奏结算（`scheduler`）、撤销/取消完成都不触发。
- 边界：before/after 为空、阶段 `total = 0`（空壳阶段）、计划已结束/已放弃、同一轮已有自主检查在跑
  （`chatBusy`）时——不重复触发；繁忙时只留一个待跑标记，这一轮结束后再补跑。

### 这一轮怎么跑

- **静默切到管家会话跑一轮**：不写用户发言、不切导航页；聊天忙时排队。产出作为管家的主动消息写进
  置顶管家会话（渲染与晨/夜消息同一套）。
- **未读**：用户当时不在对话页 → 会话标未读红点（打开即清）；人在对话页就直接看到。
- **通知**：只有「应用失焦 + 设置页桌面通知开」才发系统通知（复用 `Notifier.kt`，失败静默）。
- **rest 静默**：没有值得说的事就调 `rest` 收尾——不写可见消息、不标未读、不弹通知，也**不是**错误。
  语气要求「一次只说一件最重要的事」，不为说话而说话。

### 只读 + 只提议（这一轮的工具边界）

- 这一轮工具集**只含只读工具 + 提议/收尾工具**：
  `read_learning_overview`、`read_knowledge_overview`、`get_learning_plan`、`read_plan_doc`、
  `list_goals`、`recall_memory`、`search_web`、`search_exercises`、`recommend_exercises`，
  以及 `propose_practice`、`ask_choices`、`rest`。
- **写工具本轮不可用**：建计划、调研建计划、排周、改/勾/挪节点、改节奏、建/改目标与里程碑、写计划文档、
  同步周文档、加待办——都不在工具集里。提示词同样禁止说「已经帮你建好/改好/排好了」。
- `propose_practice` 只往会话里推一张用户可接受/忽略的练习卡，不修改计划；
  `ask_choices` 只把 2–5 个选项摆到输入框上方，让用户拍板。
- **用户点选后，写操作在下一轮普通轮执行**（那时工具集恢复全量，写工具仍需 `confirmed=true`/审批）。
  所以「用户点一下确认」不是硬编码的剧本，而是设计里必须的一步：方向由用户拍板。

### 知识网络默认表示「已掌握」

- `read_knowledge_overview` 把知识网络里的**笔记/概念默认当作「用户已了解、基本掌握」**：
  它们是能力证据与已覆盖范围，不是待办。
- 推荐方向「向上/向外」：项目、进阶、整合、输出；**不建议重复学习**已经覆盖的内容；
  练习（`recommend_exercises` → `propose_practice`）用来巩固/检验，而不是从头再学。

### 两条典型产出

- **通识课阶段完成**（题库能匹配）：`recommend_exercises` 挑一道（优先没做过/做错过的），
  `propose_practice` 推练习卡；用户点「开始练习」进现有练习二级页，作答落 `<home>/exams/attempts.jsonl`。
- **计划完成（由点到面）**：`recall_memory` 看目标与偏好 → `read_learning_overview` /
  `read_knowledge_overview` / `list_goals` 看学习与知识现状 → 必要时 `search_web` 调研外部要求 →
  给出「下一步方案」（为什么、学什么、阶段与周次、第一周做什么、与已掌握内容的关系）+
  `ask_choices` 2–3 个方向；用户点选后，下一轮普通轮才真正**新开一份计划**（旧计划不动）。

### 计划完成之后：旧计划不动，拓展 = 新开计划（2026-11-08 晚补的口径）

- **完成态是算出来的**：所有阶段 `total>0 && done>=total`，且待安排池为空（`isPlanComplete(stages, poolEmpty)`，
  与 `PlanDone` 同一口径）。不改 `process.status`，也**不自动 `endPlan`**。
- **计划页**：完成态的计划从进行中/分类列表移到左侧「已完成」分组（可折叠、默认收起、标题带数量）。
  **用户侧不冻结**：仍可打开、查看、勾选/撤销、拖拽。
- **AGENT 侧只读**：`plan_week`、`write_plan_doc`、`sync_plan_week`、`plan_next_week`、`complete_plan_node`、
  `move_plan_node`、`set_plan_pace` 对已完成计划一律拒绝，提示「已完成，拓展请新开一份」；
  `get_learning_plan`、`read_plan_doc`、`review_last_week` 等只读工具照常。
- **每周自动排周计划跳过已完成计划**（同一完成态判定）。
- **拓展 = 新开一份计划**：用 `create_learning_plan` / `research_plan` 新建（可带 `goalId` 挂到同一目标），
  新内容进新计划；不在旧计划上追加阶段/单元/周次。自主轮里也只给「新计划」建议。

### 与既有机制的边界（本轮都不改）

- **晨/夜仪式（§3）**：触发、幂等 checkpoint、合并规则、模板/润色、摘要全不变；自主轮不消耗晨/晚
  checkpoint，也不写 `digests/`。
- **自动采纳（§5.1）**：仍只管 `StewardProposal` 的 `Move`/`Place`；自主轮不产 `StewardProposal`、
  不触发自动采纳，`autoAdopt` 开关对它无影响。
- **每周自动排周计划（§5.2）**：仍按「周日 18:00 后 / 周一 + 同一周一次」用完整工具链跑；
  自主轮不排周、不改节奏。两条线互不调用。
- 不新增按钮或手动入口：触发完全由完成事件驱动；演示/验收时把某阶段最后一个节点勾掉即可。
