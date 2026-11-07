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

### 5.1 自动采纳（2026-11-07 最小版，G-C1）

- 开关：设置页「管家 → 自动采纳」，落 `steward/settings.json` 的 `autoAdopt`（默认关）。
- 准入三道闸（`autoAdoptAllowed`）：开关打开、应用**不在前台**（`windowFocused=false`）、
  今天还没到 `autoAdoptDailyLimit`（默认 3）。计数在 `steward/checkpoints.json`
  （`autoAdoptDay`/`autoAdoptCount`，换天归零）。
- 白名单（`StewardProposal.isAutoAdoptable`）：整条建议的改动**全是 `Move`** 才自动执行；
  含 `Place`（补排欠账）的建议仍要用户点「一键采纳」——补排会动待安排池，风险比挪动大。
- 触发点：晨/晚仪式成功发出后（`runStewardCheck` 非 `Busy`）与窗口失焦
  （`onWindowLostFocus`）。执行完在管家会话里把建议行标 `Adopted`、追加一条状态行，
  并记一条 `Adopted` 记忆 + `app.log` 一行；计划页数据刷新，挪动仍可撤销。
- 不做：后台无人值守（G-C2）、自动补排、自动采纳后弹通知（G-C3）。

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
| `app/desktop/.../Notifier.kt` | 跨平台系统通知（失败静默） |
| `ai/.../steward/StewardPrompt.kt` | 人格提示词、事实块、晨/晚/合并模板 |
| `app/desktop/.../ChatState.kt` | `SessionKind`、`ChatLine.proposal`/`proposalState` |
| `app/desktop/.../ChatStore.kt` | kind / proposal / proposalState 的序列化（旧文件兼容） |
| `app/desktop/.../AppRuntime.kt` | 接线：focus/ticker、`appendStewardLine`、`sendChat` 人格分叉、采纳/拒绝/协商 |
| `app/desktop/.../ChatScreen.kt` | 管家置顶行、主动消息与普通会话同一渲染管线（`ChatBlock.Round.user` 可空）、建议卡（渐变+框内滚动） |
| `app/desktop/.../SettingsScreen.kt` | 管家设置区 |

测试：`StewardStoreTest`、`StewardRuntimeTest`、`StewardProposalsTest`、`NotifierTest`、
`ChatStoreTest`（kind/proposal 往返）、`ai` 的 `StewardPromptTest`。

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
