# 计划自己推进：节奏引擎与完成核验（讨论稿 · 2026-10-29）

> 对应 `docs/pluginization-plan-2026-10-29.md` 第 1 条「插件化 AGENT 和计划需要融合」。
> **这是讨论稿，不是实现记录**：里面的命令名、阶段划分、取舍都还没定稿，代码引用为 2026-10-29 的状态。
> 目标只有一个：让「计划维护」的成本降到用户不需要点。

## 0. 一句话

现在这条链路只实现了「AI 帮用户建一份计划」，没有实现「计划自己往前走」。
要补的不是一个更大的模型，而是**一条确定性的节奏引擎**（周/日两层）+ **一层分级证据核验**（决定要不要推进）；
AI 只在两个地方介入：建路线时、每周复盘需要判断时。

## 0.1 拍板与落地状态（2026-10-29 下午）

四个问题都拍了：

1. **周节奏：自动**（自动排下周、计划页可见、可撤销）——不再出预览等确认。
2. **核验首发：按上文建议**（计时 session + 手动 + AI 对话证据；活动数据先不碰，等隐私门）。
3. **路线：全量**（source 导入全部单元，单元上限 200；承诺仍然滚动 1–2 周）。
4. **自动完成：先「建议完成」等一键**（高置信自动完成留到 P3 数据验证之后）。

P0（周滚动后端 + 桌面接线）已落地：

- `advanceRhythm(env, planId)`：结算旧周 → 从池里补本周/下周承诺 → 生成复盘 → 铺本周（周日加铺下周）；
  子命令幂等，没有实际动作时不写事件。实现 `features/learning-process/.../PlanAdvance.kt`。
- 「加一周」走**往活跃版本追加承诺**（`cmdExtendCommitment`），不每周 bump 版本；
  改某一周的内容仍走 `changeCommitment`，并修掉了它「只复制单元、不复制其它周承诺」的缺陷。
- `materializeWeek` 改成**单元级**判重：结转行不再导致整周不铺；当天已有结转占用分钟数计入预算。
- 桌面端 `AppRuntime.refreshPlan()` 对所有未结束计划调一次 `advanceRhythm`（`origin=scheduler`），
  结果存在 `planRhythmReport` 里；计划页顶部出现「计划节奏」报告条（可关），
  有承诺、还没铺到天的「第 N 周」重新作为周卡片显示，不再混进「待安排」。
- 测试：`LearningPlanRhythmTest` 6 个用例（4 周滚动、携带 + 铺天共存、周日预铺不重复、改承诺保留其它周、
  每周选量上限 7、暂停计划不推进）；后端 24 个用例全绿。

P1（路线全量导入）已落地：

- `create_learning_plan` 支持 `bvid` + 可选 `fromPart/toPart`：宿主直接拉分集，按分集标题/时长/章节
  **确定性**生成全部单元（长分集拆上下两段），单元上限 200；用了 bvid 就不许再手写 units/weeks。
- 排周也确定性完成：本周 + 下一周按 `dailyMinutes×7×0.8` 预算各取一批（每周 ≤7），其余进「待安排」。
- `PlanToolsTest` 10 个用例（40 集全量、60 分钟拆分、区间过滤、201 集截断、source 传 weeks 被拒等）全绿。

P4（Agent 计划管理员）已落地：

- 新意图 `AgentIntent.PlanManage`（管已有计划：看进度/排下周/勾节点/顺延/复盘/改每天投入），
  与 `FindResource`（新建计划）分开；「把视频排成学习计划」仍走资源那条路。
- 新工具 `PlanAdminTools`（6 只）：`get_learning_plan`、`plan_next_week`、`complete_plan_node`、
  `move_plan_node`、`review_last_week`、`set_plan_pace`；写操作要 `confirmed=true`。
- `ManagerPrompt` 新增 `<task:plan_manage>`；find_resource 第 4 步同步成 bvid 全量导入。
- 已接线进 `AppRuntime.sendChat`（allTools + guidance）；ai 82 tests / 桌面 5 tests 全绿。

P6（逾期处理：把「没做完」变成可协商的事实，2026-10-31）已落地：

- 后端新增 `LearningPlanService.overdue(planId, today)`：本周「应该做但没做」的节点按**路线顺序**
  （`plan_unit.sort_order`，不是创建时间）列出，同时给出今天到周日还剩多少分钟（扣掉已排的）；
  过去周没完成的单元回到「待安排池」（`pool` 语义扩展），并在 `overdue.backlog` 里带 `missedInWeek`。
- 「放弃」不再是删除：从本周承诺移除 → 单元回待安排池，欠账以后还能再排；容量不足时从路线尾部先撤。
- 修掉 §6 的短课程死路：池语义把「过去周没做完」也算进来，短课程跨周后会被自动补进本周承诺。
- 打开应用/重新获得窗口焦点时做一次**计划检查**：有本周隔日或欠账就写一条确定性摘要到全局
  「计划检查」会话，`unread=true`；导航「对话」与会话行显示红点；打开会话即已读；同一天最多一次。
- 不做：独立定时器、按日自动顺延 / 全自动重排（理由见 §2.4）。

**未做（后续阶段）**：P2 剩余（「让 AI 安排」按钮、复盘卡片入口）、P3 建议完成（session 证据 + done_kind）、
P5 插件证据提供者 + 自动完成 + replan、P6 的聊天里 AI 协商重排与计划页「待处理」区。

---

## 1. 现状解剖（对着你提的四件事）

### 1.1 「AI 只排了一部分节点，后半部分怎么补」——这是设计出来的行为，不是偶发

- `BilibiliTools` 的指导语明确要求「挑 **6-12 个**相邻分集」，分集工具返回里甚至写「不用把全部集数写进计划」
  （`app/desktop/.../BilibiliTools.kt:29`、`:116`）；
- 而 `PlanTools` 的说法是「其余单元留在待安排池」（`app/desktop/.../PlanTools.kt:49-50`），暗示 units 是全量。
  两条指导语打架，模型自然按更保守的「6-12」写。
- 工具上限：units ≤ 60（`PlanTools.kt:82`）、weeks ≤ 2（`PlanTools.kt:106-111`）。
- **更关键的是：B 站来源没有被持久化。** DDL 里 `material` / `material_version` / `segment` 三张表、
  `plan_unit.material_segment_id` 列都在（`features/learning-process/.../PlanSchema.kt:17-26`、`:51`），
  但全仓没有任何一行写入；`PlanTools` 也不传该字段。bvid、第几集、每集时长只活在工具返回的字符串里。
- 结论：模型没写全 → 没有权威清单可对照 → 没有「按来源补下一批」的查询 → 「后半部分」今天没有任何补法。

### 1.2 「第二周（未安排）能不能正常安排」——第二周能，第三周起不能

- 建计划时只建本周 + 下周两个承诺（`PlanTools.kt:104-111`），`acceptDraft` 只铺第一周
  （`features/learning-process/.../PlanCommands.kt:244`）；
- 之后唯一会自动铺天的是 `AppRuntime.scheduleDueWeeks`：每次 `refreshPlan()` 时铺「当前周」，
  周日额外铺下周（`app/desktop/.../AppRuntime.kt:1902-1920`）。应用启动时就会 `refreshPlan()`
  （`AppRuntime.kt:2636`），所以**只要第二周打开过应用，第二周就会被铺**——这条其实通，但没测试钉住。
- **第三周起断流**：没有任何命令/工具/UI 能把「待安排池」里的单元变成第三周及以后的承诺。
  池子只读展示（`SqliteLearningPlanService.kt:391-407`、`AppRuntime.kt:1874-1875`）。
- `changeCommitment` 看起来能补周，实际上**不能**：它每次新开一版，只复制单元、只插目标周一条承诺，
  不复制其它周的承诺（`PlanCommands.kt:394-460`）。用它补第 3 周，第 1、2 周会从活跃版本里消失。
- 顺带：`settleWeek` / `generateReview` 后端写得很完整，但**生产代码零调用**（只有测试调），
  所以 missed 永不产生、结转永不发生、复盘永远是 `not_ready`。逾期节点只会永远挂在 `openNodes` 里。
- 结论：周层缺三样东西——「只加一周」的命令、每周滚动排周的规则、结算/复盘的触发。

### 1.3 「按预计时间排是稳妥方法」——日层可以保留，周层不能只靠它

- 日层 `materializeWeek` 确实稳：按每天投入分钟数往一天里塞，超了换天，单单元超预算独占一天，
  幂等（`PlanCommands.kt:288-358`）。用户「简单的按预计时间排」的判断在这一层成立。
- 但它有两个已知坑：①周一/周日的触发依赖用户打开应用；②「该承诺已有任何 occurrence 就不铺」的判重
  会被**结转行**误伤——`settleWeek` 把 missed 结转进下周后，下周已有 occurrence，`materializeWeek`
  直接 `return 0`，整周不再铺（`PlanCommands.kt:296-299` + `PlanRhythm.kt:127-151`）。
- 周层不能只靠时间：一周该排几个单元、要不要因为上周没完成而减量、章节要不要断开——这些需要判断。
  你自己的经验（总路线 → 每周 → 每天）正好对应：**路线要全、周要有人（或规则）拍板、日机械化**。

### 1.4 「降低核验/推进门槛」——今天只有手动勾选，证据其实有一些但没接

- 计划节点只能由用户手动勾（`AppRuntime.completeOccurrence`，actor=USER，`AppRuntime.kt:2034`）；
  AI 对学习计划只有「建计划」这一只手（`PlanTools`），读完/改完/结算全没有（`LearningPlanService`
  接口 25 条写命令里 17 条 UI 和 AI 都没接）。
- 能收集到的证据（调研结论）：
  - **计时器 session**：点节点 ⏱ 会往 `session` 表写 `source=timer` 的时间段（`PlanCommands.kt:889-948`），
    但**全仓没有一处读 session**，界面/复盘/核验都没用上；
  - **活动采集（AW）**：窗口标题、浏览器标签页 URL、AFK 都进了 `activity.db`，能按 app/域名/标题分类，
    但 AI 没有任何活动查询工具；`aiExposure` 隐私裁剪只是个枚举，没人读（`ActivitySettings.kt:58`）；
  - **截图**：`take_screenshot` 只截 **Trellis 自己的窗口**（`AppRuntime.kt:1172-1191`、`:1275-1281`），
    证明不了用户在 B 站/IDE 里干了什么；全屏/他应用截图涉及隐私，不该默认做；
  - **B 站观看进度**：匿名 API 拿不到（无登录态，只有搜索 + 分集目录，`BilibiliClient.kt:43-44`、`:100-105`）。
- 所以「用屏幕作为判定依据」今天不成立；但「用直接能收集到的信息**提示**完成」成立：
  计时 session + 活动 URL + 用户一句话自测。学习科学证据也指向同一结论：**阅读时长不能当完成依据，
  自评也不可信**（`docs/research/learning.md:19,53`）——时长只能当弱证据，强证据要靠「输出/检索」。

### 1.5 「动态调整只存在于理论」——命令齐了，入口一个都没有

- 后端已有的：顺延 `moveOccurrence`、拆分 `splitOccurrence`、缩短 `shortenOccurrence`、跳过
  `skipOccurrence`、改承诺 `changeCommitment`、暂停/恢复/结束、结算、复盘、撤销。
- 实际接了的：UI 只有「完成 / 改回没做 / 改每天投入 / 铺周」；AI 只有「建计划」。
- 目标变化（换资源/改目标/加内容）没有任何路径：`saveDraft + acceptDraft` 会重建全部单元并重新铺天，
  可能产生重复 occurrence，不能直接当 replan 用。

---

## 2. 设计：三层节奏 + 一层证据

### 2.1 总原则

1. **确定性打底，LLM 可选**：没有 key、断网、不想花钱时，计划也能按周推进；AI 只做「需要判断」的判断。
2. **周是节拍单位**：学习证据里「周一」是最真实的重启窗口（`docs/research/learning.md` §5），
   所以结算、复盘、排下周都放在周界，不放在每天 9 点的定时器（主动式 HCI：状态触发优于定时触发，
   `docs/research/product.md` §3）。
3. **推进要可撤销、可审计**：所有自动动作走命令 + 事件（已有 `event` 表），`actor=SYSTEM`，
   `origin=scheduler`；用户随时能改回。
4. **文案不羞辱**：missed 不用损失框架，周复盘第一句是「上周完成了什么 + 这周最小一步」，
   禁止「你落后了 N 天」（`research/learning.md` §5、`research/product.md` §1）。
5. **核验分级**：高置信才自动完成；中置信问一句；低置信不动、留到周复盘。永远给用户「手动」兜底。

### 2.2 路线层（总路线）：源驱动、全量、可继续补

- `create_learning_plan` 增加「来源」入口：`source={bvid, fromPart?, toPart?}`。
  拿到 bvid 后由**宿主**调 `BilibiliClient.parts` 生成 units（标题、章节、分钟都来自 B 站返回），
  同时写 `material/material_version/segment`，回填 `plan_unit.material_segment_id`。
  AI 只负责「选哪个源、从第几集到第几集」，不再逐条转录 80 个标题——这样「排不全」从根上消失。
- 单元上限从 60 提到 ~200（常见课程全量）；承诺仍然只排 1-2 周，路线长不影响周压力。
- 后续补：新增 `extend_learning_plan(source, fromPart)`，从 source 拉「还没进计划的集」，
  出一版新草稿（历史版本保留）。这就是「后半部分怎么补」的答案。
- `BilibiliTools` 的 6-12 指导语只保留给「落成待办」路径；排计划的路径改写成「全量目录」。
- AI 建的 `doneWhen` 也在这里落（见 2.5），因为标准跟资源类型绑定。

### 2.3 周层（每周）：一条 `advanceRhythm` 命令

新增后端命令（契约加在 `sdk-domain/.../LearningPlanService.kt`，实现放 `features/learning-process`）：

```
advanceRhythm(env, planId, today) -> RhythmResult
  ① 确保「下一周」有承诺：当前活跃版本里缺这一周的承诺、且池非空 → 从池里排（追加，不改旧行）
  ② 结算所有「已结束但还没结账」的周（补跑多周；过去一周的 missed / 结转 ≤2 落进①建好的下周）
  ③ 对刚结账的周生成复盘（generateReview，幂等）
  ④ 铺本周（周日提前铺下周）
  ⑤ 返回报告：上周完成几/几、本周已排几个、池里还剩几个、有几个建议确认完成、下一步建议
```

- ① 的关键取舍：**「加一周」不要 bump 版本**。`plan_version` 一旦 active 就不许改/删（触发器只禁 UPDATE/DELETE，
  不禁 INSERT），而「追加一条未来周的承诺」不改任何旧行，可以直接插进活跃版本；这样 unit id 不变，
  已经铺过天的 occurrence 不会变成孤儿。反过来，如果每周都照 `changeCommitment` 的方式新开一版：
  - 已铺天的 occurrence 还挂在旧版本的 commitment/unit id 上（occurrence 身份列不可 UPDATE），
    新版本的 view 查不到它们，会重复铺天或把「已完成」状态丢掉；
  - 回填/修复成本高，还容易造出重复的日任务。
  **改某一周的内容**（少排/换单元）仍然走 `changeCommitment`——那是 replan 语义，需要 bump 版本，
  但要按「supersede 旧 planned occurrence」处理，不能直接铺新版本。
  顺带修 `changeCommitment` 丢其它周承诺的缺陷（它现在只复制单元、不复制其它周，`PlanCommands.kt:394-460`），
  否则改一次本周就把下周弄丢。
- 选量规则只有一份实现（第三步②之前就要用）：
  - 周容量 = `dailyMinutes × 7` × 80%（留 20% 缓冲，对应复盘里「完成率回到 8 成」的建议）；
  - 按路线顺序取，尽量不切断章节；
  - 上周完成率 < 60% → 少排 1 个；连续两周 < 50% → 只提示「要不要减量/换资源」，**不自动改目标**；
  - 每周 1–7 个，结转最多 2 个（后端已有规则）；池空时不建空承诺，报告「路线走完，建议收尾/复盘」。
- 幂等：按 `(planId, week)`/事件查重；没有实际动作就不写事件，避免每次刷新都刷审计。
  `settleWeek` 与 `generateReview` 本身已是幂等的（重复调用返回历史数字）。
- 触发：`AppRuntime.refreshPlan()` 对**所有未结束计划**各调一次 `advanceRhythm`（今天只处理 `activePlanId`
  一个计划，`AppRuntime.kt:1819-1895`）；应用启动、计划页打开、跨天时天然覆盖。
  这是状态触发，不是定时器；长期不关机的人，切到计划页/任务页时也会兜底。
- 验证（这是你说的「没检查过一周过后第二周」的正解）：用后端已有的注入时钟
  （`SqliteLearningPlanService(db, clock=..., zone=...)`）跑一条 4 周滚动测试：
  W44 建计划 → 假时钟推到 W45 调 `advanceRhythm` → 断言 W45 铺天、W46 承诺出现、池减少、无重复 →
  再推到 W46/W47 重复，直到池空；另加「周日预铺了下周后再进下周一不会重复铺」「结转 + 铺天共存」
  「改承诺后其它周仍在」「重复调用幂等」「已完成状态在周卡上不掉」五条。
- UI 小改：计划页目前把「有承诺但没铺天」的周并进「待安排」灰卡（`PlanScreen.kt:290-330`）。
  滚动排周之后，下周会经常处于这个状态，应该单独显示成「第 N 周 · 已排 X 个单元 · 下周一开始」，
  否则用户会以为排好的下周又没了。

### 2.4 日层（每天）：保留打包器；「没做完」走逾期投影 + 协商重排

- `materializeWeek` 保留（按 dailyMinutes 打包）；修掉「结转行导致整周不铺」的判重（已做）。
- 不做日层加量，也**不做「跨天自动把昨天没做完的挪到今天」**：全自动顺延会变成债务复利和催债感
  （2026-10-30 讨论结论，改成聊天里协商）。日层只做「事实投影」：
  `LearningPlanService.overdue(planId, today)` 返回
  - `thisWeek`：本周已排、日期早于今天、仍 `planned` 的节点（按 `plan_unit.sort_order`，附带 unitId / sortOrder）；
  - `backlog`：过去周承诺过、没完成、也没被本周/以后收走的单元（带 `missedInWeek`）；
  - `dailyMinutes` / `remainingDays` / `remainingMinutes`：今天到周日的剩余容量。
  这是只读投影，不写事件；计划页或 AI 拿它做建议，真正改动仍走命令。
- 「放弃」= 从当前周承诺移除、单元放回**未安排桶**（`pool`），不是删除、也不是永久结案；
  容量不足时按路线顺序**从尾部撤回**，前面的先修节点不动；前置没完成时，后面的节点不排到更早日期。
  （当前 `plan_unit.sort_order` 已是路线线性序；真正的 `depends_on` 依赖图留到需要时再加。）
- 跨周改动只在「承诺层」做（`changeCommitment` / `extendCommitment`），不该写历史周。
- 触发方式：不新增定时器。`AppRuntime.refreshPlan()`（启动、进计划页、各操作后）顺带跑
  `advanceRhythm`；主窗口重新获得焦点时再触发一次**计划检查**（`onWindowFocused`，带互斥和
  「今天已发过」的持久标记，同一天最多一条），覆盖「整夜开着」的情况。
- 计划检查的主动消息：只建一个全局「计划检查」会话（`PLAN_CHECK_SESSION_ID = "plan-check"`），
  有本周隔日或欠账才发；内容是确定性摘要（哪些天落下哪些、欠账最多列 5 个、今天到周日还剩多少分钟、
  「补做/顺延/少排一点/这周先放过」）；带轻量未读标记（会话行 + 导航「对话」红点），打开即已读。
  不自动跳页、不打断正在跑的轮次、没有模型也能发。
- 下一步（未做）：用户在对话里回复后，AI 先读真实数据 → 总结 → 给两套方案（`ask_choices`）→
  变更预览 → 用户确认后执行（写操作 `confirmed=true`）→ 结果 + 撤销；AI 不自己数数。

### 2.5 核验层（推进门槛）：完成标准 + 证据分级

**数据落点**：`plan_unit` 加两列（schema v2，只加列，迁移方式已有先例）：
`done_kind`（枚举）+ `done_threshold`（整数）。第一期只放三个值：

| done_kind | 判据 | 证据来源 | 置信 |
|---|---|---|---|
| `manual` | 用户勾选（默认） | 用户 | 中 |
| `minutes` | 该节点关联 session 累计 ≥ 阈值分钟（默认 est×80%） | `session` 表（今天只写不读，要补聚合） | 中 |
| `evidence` | agent 在对话里收到「复述/自测/产物」后判定，结论写 `note` + 事件 | 对话/未来插件 | 高（有 note 可审计） |

- 后续（插件化那部分）：`quiz`（正确率 ≥ 阈值）、`artifact`（知识库节点/产物）由插件通过新的
  「证据提供者」扩展点上报；练习/题库类插件才拥有判分数据。**不要在 agent 提示词里发明判据。**
- 第一期只做「提示完成」，不做「自动完成」：节点头部/待办板给一条「看起来完成了（计时 22/20 分钟，
  或你刚才复述的三点覆盖了本章）——勾上？」，用户一下点掉。这已经是「降低门槛」，风险最低。
- 第二期再放开「高置信自动完成」：只有 `evidence` 且 note 非空、或 quiz 通过时自动勾，`actor=AGENT`，
  事件留证，且随时可改回（`uncompleteOccurrence` 已有）。
- 不确定性三分：高置信（强证据一致）→ 自动/建议；中置信（只有时长/位置）→ 问一句；低置信（证据冲突/
  没有证据）→ 不动，周复盘里问「这周哪几件你觉得自己其实没吃透？」。
- **隐私门**：活动数据接给 AI 之前必须先实现 `aiExposure` 裁剪 + `titleRedactions`
  （`docs/activity.md` §5/§10 已记为缺口，`ActivitySettings.kt:58` 现在没人读）。默认 `AGGREGATES_ONLY`。
- **截图不做核验主通道**：只截 Trellis 自己的窗口；全屏/他应用截屏属于隐私敏感能力，若做必须 opt-in +
  运行时指示 + 按应用排除（Recall 的教训，`research/product.md` §3）。

### 2.6 Agent 融合：一个计划意图 + 一组计划工具 + 一条主动消息

- 新意图 `AgentIntent.PlanManage`（关键词：计划/复盘/这周/下周/节点/完成/顺延/拖延/没做完/调整/排期；
  注意与 FindResource、ArrangeUi 的优先级，用 `ManagerRouterTest`/`IntentToolsTest` 钉住）。
- 新工具 `PlanAdminTools`（全部转调 `LearningPlanService`，写操作带 `actor=AGENT` + origin）：
  - 读：`get_plan`（当前计划、本周/上周、今日节点、池、carry、复盘）；
  - 写：`plan_next_week`、`complete_plan_node`（可带 note 证据）、`move_plan_node`、
    `settle_and_review`、`set_plan_pace`（改每天投入）。
- 提示词加 `<task:plan_manage>`：先读状态再动；一次只动一个计划；完成只写证据能支撑的；
  不确定就问（ask_choices）；改目标要用户确认；文案不羞辱、不用损失框架。
- **主动消息不做「自动扣 LLM」**：节奏引擎每次推进后，计划页顶部出一条节奏报告
  （「上周完成 3/5 · 本周已排 4 个单元 · 待安排 9 个约 3 天 · 有 2 个节点建议确认完成」），
  对话页插一条系统状态行；按钮「看复盘 / 让 AI 安排」点了才进模型。成本、打扰都可控。
  后续可以在设置里加「自动周复盘」开关（默认关）。
- 这条要考虑 AI 层今天的三个现实（调研结论）：
  1. **没有任何「用户不说话也调 LLM」的机制**（唯一的自动发话是演示用的 `launchDemoSend`）；
     将来要加，落点是 `AppRuntime.create()` 尾部起协程 + `lastRun` 持久化 + 启动补跑，
     并且先查 `llmConfigured` / `chatBusy`。
  2. **主动消息今天没有正式入口**：`addChatLine` 是 private，`sendChat` 必写一条 User 行；
     单插 Assistant 会被渲染成「孤儿过程行」。最省事的做法是新增 `proactiveSend(display, request)`：
     先插一条标记 User 行（如「（每周计划检查）」）再走 `sendChat`；或加一个 `ChatRole` 独立块
     （`ChatStore` 会丢弃未知 role，旧会话兼容）。
  3. **意图路由有个洞**：「排计划 / 安排每周」这种不含资源词的句子会落到 Chat 或 ArrangeUi，
     那时手里没有 `create_learning_plan`。所以 `PlanAdmin` 意图必须排得足够前，并用
     `IntentToolsTest` 钉住「每种意图手里是哪几只工具」；顺带把 guidance 目前**不分意图全量注入**
     （与 `docs/agent-manager.md` §18 的约定不符）改成按意图给，省 token、也避免模型以为手里有工具。
- 自动推进必须走**幂等**的后端命令：`create_learning_plan` 每次都会新建计划、
  `add_tasks` 每次都会加任务，AI 重跑会造重复。节奏引擎一律用带幂等键的命令
  （`advanceRhythm` 按 `(planId, week)` 查重），AI 的写工具首版要带 `confirmed` + 只动当前计划。

### 2.7 动态调整：分三级落

| 级别 | 触发 | 动作 | 现状 |
|---|---|---|---|
| 日级 | 昨天没做完、今天超载 | 逾期投影（`overdue`）/ 顺延 / 拆分 / 缩短 / 跳过 | 后端有（投影与命令齐了；计划页入口与 AI 协商未做） |
| 周级 | 周界、完成率低 | 结算 missed、结转 ≤2、欠账回池、下周减量、复盘 | 后端有，节奏引擎已接；短课程死路已修 |
| 目标级 | 用户换资源/改目标/加内容 | 结束旧计划或新版本替换路线 + supersede 旧 planned occurrence | **缺命令**：加 `replan`，原子完成「归档旧版 → 旧 occurrence supersede → 建新版 → 重新铺天」；第一期可先用「结束旧计划 + 新建计划」的保守路径 |

---

## 3. 分阶段落地（按「能验证」排序，不是按功能大小）

### P0 周滚动后端（最关键，1–2 天）— **已落地**

- 已改：`features/learning-process/.../PlanAdvance.kt`（新，`advanceRhythm` + 选量规则 + 池查询）、
  `PlanCommands.kt`（`cmdExtendCommitment`、`cmdScheduleWeekCount`、修 `materializeWeek` 判重、修
  `changeCommitment` 丢周问题）、`sdk-domain/.../plan/{Contract.kt,LearningPlanService.kt}`（`RhythmResult` +
  契约）、`app/desktop/.../AppRuntime.kt`（`scheduleDueWeeks` 换成 `advancePlanRhythm`，遍历全部计划）。
- 测试：`LearningPlanRhythmTest` 6 个用例（4 周滚动、结转+铺天、周日预铺不重复、改承诺保留其它周、
  选量上限 7、暂停不推进）；后端 24 个用例全绿。
- 验收：不打开计划页、只启动应用，跨周后第二周有安排；第 3 周也有；池逐周变小；已完成状态不丢。

### P1 路线完整性（1 天）— **已落地（拆分集生成；material/segment 持久化留到 P1.5）**

- 已改：`PlanTools.kt`（`bvid/fromPart/toPart` 全量导入 + 上限 200 + 自动排本周/下周）、
  `BilibiliTools.kt`（计划路径引导 bvid 全量导入，不再逐条抄分集）。
- 验收：给一个 40 集视频，「排成学习计划」后 units = 40，章节/时长来自真实分集；池里是后 26 集
  （本周+下周各 7 个）；60 分钟分集拆成上/下两个单元；`PlanToolsTest` 10/10 绿。
- 未做：把 bvid/分集号写进 `material`/`segment` 并回填 `material_segment_id`（补「按来源续排」时才需要）。

### P2 计划页 + 节奏报告（0.5–1 天）— **部分落地**

- 已改：`PlanScreen.kt` + `AppRuntime.kt`：`planRhythmReport` 报告条（可关）；
  「有承诺但没铺天」的周重新作为周卡片显示（不再混进「待安排」），可以点开看承诺了哪几个单元。
- 未做：「让 AI 安排 / 看复盘」按钮（点一下把请求带进对话）、复盘卡片详情、P2 的 UI 验收截图。

### P3 核验 v1（建议完成，1–2 天）

- 改：schema v2 加 `done_kind/done_threshold`（迁移）、`PlanQueries.kt`（session 聚合 + 节点 evidence 摘要）、
  `PlanScreen`/`PlanNodes.kt`（建议完成条）、`AppRuntime.completePlanOccurrence`（可带 note/证据）。
- 验收：计时 22 分钟 → 节点出现「建议完成」；点勾后事件里有证据；改回没做仍可用。

### P4 Agent 融合（1–2 天）— **已落地**

- 已改：`Intent.kt`（新 `PlanManage` 意图 + 关键词与优先级）、`ManagerRouter.kt`（6 只新工具
  显式登记；`set_plan/step_done/ask_choices` 对 PlanManage 开放）、`ManagerPrompt.kt`
  （`<task:plan_manage>` + find_resource 的 bvid 全量导入）、`PlanAdminTools.kt`（新，6 工具）、
  `AppRuntime.sendChat`（并入 allTools/guidance）。
- 验收：`IntentToolsTest` 8 个用例钉住工具归属与意图边界；`PlanAdminToolsTest` 5 个用例；
  ai 82 tests / 桌面 5 tests 全绿。
- 未做：`eval-prompts.md` 的正式计划验收题；`ManagerPromptTest` 对 plan_manage 模块的钉住断言。

### P5（视验证再定）

插件证据提供者扩展点 + occurrence 级动作/事件 + 自动完成 + `replan`。这一层才真正体现
「插件化是自由度、AGENT 是自动化」，但需要先有 P0–P4 的数据与工具。

今天插件化的缺口是具体的（调研结论）：`LearningPlanService` 没有发布成内核服务、没有 DataProvider，
`PlanPoints` 里只有 `plan.schedule.strategy` 与 `plan.metric` 两个点、**零实现零引用**；
内核登记的事件只有 `task.completed`，动作只有 Task 的 6 个；所以练习/题库类插件今天既读不到学习计划、
也上报不了「某节点已完成」。要打通的最小缺口不是再截图，而是：
**occurrence 级动作/事件 + 一个证据提供者扩展点 + 一个能读活动/session 的查询工具**。

### P6 逾期与协商式重排（2026-10-31）— **后端 + 主动检查已落地，AI 协商下一步**

已改：

- `sdk-domain/src/commonMain/kotlin/dev/trellis/domain/plan/Views.kt`：新增 `OverdueOccurrence` /
  `BacklogUnit` / `OverdueView`；`LearningPlanService.overdue(planId, today)` 新查询。
- `features/learning-process/.../PlanOverdue.kt`（新）：本周隔日按 `plan_unit.sort_order` 排序、
  剩余容量按今天到周日逐日扣已排分钟；过去周欠账（missed、没有「本周及以后」承诺、没有 done/planned 行）。
- `pool` / `poolRows` 语义扩成「过去周没完成也算未安排」；`cmdExtendCommitment` 允许把欠账补进未来周
  （只拦「本周及以后」的重复承诺）；`PlanRhythm.cmdSettleWeek` 的结转目标周改成
  `maxOf(下一周, 本周)`（久不打开时不再塞进已过去的周），missed 按路线顺序取、自动插行最多 2 条。
- `app/desktop`：`PlanCheckStore`（`plan-check.json` 记录最后检查日）、`AppRuntime.onWindowFocused` /
  `maybePostPlanCheck`（Mutex 防重复）、全局「计划检查」会话、`ChatSession.unread` 持久化、
  导航与会话行红点、`Main.kt` 主窗口焦点监听。

测试：

- `features/learning-process/src/jvmTest/.../LearningPlanOverdueTest.kt` 3 个用例
  （逾期排序 + 剩余容量、欠账回池、短课程跨周不死路）；
- `LearningPlanRhythmTest` 更新长时间缺席 / 周滚动结转数字；
- `PlanCheckStoreTest` 4 个、`ChatStoreTest` 未读回合 1 个、
  `PlanCheckRuntimeTest` 端到端 1 个（上一周排满没做完 → 打开应用出未读摘要 → 同一天不重复 → 已读）。

未做：聊天里的 AI 协商重排（总结/方案/预览/确认/撤销）；计划页「待处理」区与顺延/放弃按钮；
真正的依赖图（`depends_on`）；`repairPastPlan` 的旧计划搬迁已有 key 去重兜底（不复制历史/欠账）。

验收：上一周排满没做完 → 打开应用 → 对话页出现未读的「计划检查」摘要，会话行与导航有红点；
同一天不重复发；打开会话红点消失；短课程跨周后本周有安排。

---

## 4. 明确不做（第一期）

- 不做全屏/他应用截图与录屏（隐私与合规成本 >> 收益）；不做「读屏监控」。
- 不做自动扣 LLM 的定时任务（成本、打扰、失败隔离都没准备好）；主动消息先走确定性模板。
- 不让 AI 自由发明完成判据（判据是数据，白名单枚举 + 插件提供者）。
- 不做日层加量、不自动改目标、不自动结束计划。
- 不把「学习时长」当学会的证据（只有弱提示作用），也不把用户自评当完成条件。

---

## 5. 四个问题（已拍板 · 2026-10-29）

1. **周节奏的自动程度**：自动排下周（可撤销，计划页可见），不出预览等确认。
   —— 落点：`advanceRhythm` 在应用启动/进计划页时自动跑，计划页显示它做了什么。
2. **核验首发通道**：先做「计时 session + 手动 + AI 对话证据」，不碰活动数据；
   活动归因等 `aiExposure` / `titleRedactions` 隐私门补完再做。
3. **路线是否全量**：全量导入（80 集课程也全量；上限 200），承诺仍然严格每周滚动。
4. **自动完成**：第一期只做「建议完成」等用户一键；高置信自动完成等 P3 的置信数据出来再放开。

---

## 6. 调试时间时发现的一件事（2026-10-29，已修 2026-10-31）

用开发者跳日期（`TRELLIS_TODAY=2026-11-02`，演示数据副本）把计划拨过一周，暴露了一个边角：

- 短课程（例如 7 集）会被 `create_learning_plan` 全部排进**第一周**，池子是空的 → 下一周没有承诺；
- 一周过去后 `advanceRhythm` 结算：`occurrence.missed ×7`、`carry.pending ×7`、生成复盘，但**没有下周承诺可接**，
  于是这 7 个单元既没被结转、也没被重排，计划停在原地（只有 `carryPending` 提示）。
- 这是「全量路线 + 每周滚动」的组合必然碰到的边界：路线短到一周装得下时，滚动没有下一周作为容器。

**2026-10-31 已修**：

- `pool` / `poolRows` 把「过去周承诺过、没完成、也没被本周/以后收走」的单元也算池子（`cmdExtendCommitment`
  只拦本周及以后的重复承诺，同一 unit 可以再补进未来周）；
- `PlanRhythm.cmdSettleWeek` 的结转目标周取 `maxOf(下一周, 本周)`，长时间没打开也不会把结转塞进已过去的周；
- `advanceRhythm` 顺序不变：先补本周承诺、再结算；短课程场景在 W45 打开时，7 个单元先被补进 W45 承诺，
  再结算 W44（missed×7、carried=7 且不重复插 occurrence、carryPending=0），W45 正常铺天；
- 回归测试：`LearningPlanOverdueTest.aShortCourseThatFilledOneWeekKeepsGoingNextWeek`。

> 这条也说明调试系统本身的价值：以前这个边界只能等真实跨周才能撞上。

---
