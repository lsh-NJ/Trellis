# 学习计划后端（`:features:learning-process`）

> 实现记录（2026-10-26）。规格来源是工作区 `design/plan-system-backend.md` + `design/plan_schema.sql` +
> `design/plan-ui-contract.md`；本篇只记「照规格怎么落的、哪里做了不同选择、下一步接什么」。
> API 看代码，别在这里抄签名。

## 1. 它是什么、不是什么

- **是**：一个事件溯源的学习计划后端。所有写操作 = 一条 `event` + 若干实体表变化，同一个事务里提交；
  undo = 用内存里记的反向闭包再写一条 `event(undo_of=...)`，**不改历史**。
- **不是**：任务页那套 `PlanService`（`sdk-domain/.../Model.kt`，KV 存 JSON，给卡片和 AI 用的）。
  两套契约并存、互不依赖：
  - 任务型：`dev.trellis.domain.PlanService`（`:features:plan-core`）
  - 学习计划：`dev.trellis.domain.plan.LearningPlanService`（本模块）
- 数据落 SQLite（`org.xerial:sqlite-jdbc`），文件路径由调用方给；桌面端还没接，默认 `~/.trellis/learning-plan.db` 是 P4 的事。

## 2. 文件分工

| 文件 | 职责 |
|---|---|
| `sdk-domain/.../plan/Contract.kt` | 命令信封、错误码、`CommandResult`、`PlanDiff`、`UndoInfo`、各种 Ref |
| `sdk-domain/.../plan/Views.kt` | 所有视图 DTO（日/周/地图/复盘/待办/计划状态） |
| `sdk-domain/.../plan/LearningPlanService.kt` | 查询 7 个 + 命令 20 个（接口即契约，UI 只依赖它） |
| `features/learning-process/.../PlanSchema.kt` | 内嵌 DDL（逐条执行）+ 触发器/视图/`user_version` |
| `.../PlanDatabase.kt` | 连接、PRAGMA（`foreign_keys`、WAL、busy_timeout）、建表、`schema_migration` |
| `.../Ids.kt` | Crockford base32 ULID（`t_` 前缀） |
| `.../PlanTime.kt` | 周/日标签、周起止、ISO 时间（时区由构造参数注入） |
| `.../SqliteLearningPlanService.kt` | 命令外壳（事务/幂等/冲突/撤销注册）+ 行映射 |
| `.../PlanCommands.kt` | 20 个命令的实现 |
| `.../PlanQueries.kt` | 7 个视图的组装（headline 文案也在这里） |
| `.../PlanRhythm.kt` | `settleWeek`（错过 + 结转）、`generateReview`（固化数字）、`exportJsonl` |
| `.../LearningPlanStore.kt` | 对外唯一入口：`open(file)` / `inMemory()` |

## 3. 相比设计稿改了什么（都是 SQLite 落地必需）

| 设计稿 | 实现 | 为什么 |
|---|---|---|
| `occurrence.unit_id / commitment_id NOT NULL` | 都可空，新增 `todo_id`，CHECK `unit_id IS NOT NULL OR todo_id IS NOT NULL` | `scheduleTodo` 要能建「待办型 occurrence」，一条表装两类来源 |
| `event` 无 `plan_id` | 加 `plan_id` + `idx_event_plan` | 按计划查历史、`latestSeq`、复盘/as-of 统计都靠它 |
| 视图列 | `v_occurrence_detail` 补 `created_at`，`todo_title`、`unit_title` 走 LEFT JOIN | 排序和「待办型」显示需要 |

其余表、触发器、CHECK 与 `design/plan_schema.sql` 一致。三个不变量都在触发器里，测试逐条钉住：
`event_no_update/delete`、`plan_version_no_rewrite`（只 draft 可整体替换）、`occurrence_no_remap`（身份列不可 UPDATE）。

## 4. 语义要点（写代码前必须知道）

- **幂等**：同一个 `commandId` 第二次调用返回 `ok + warnings`，不会双写；跨重启也能识别（查 `event.command_id`）。
- **并发**：`expectedLastSeq` 与 `latestSeq(planId)` 不等 → `E_CONFLICT`，并在 `error.latestSeq` 回填最新序号，UI 用它刷新。
- **dryRun**：照常跑完整段逻辑，最后 `rollback`，返回同一份 diff/结果。
- **撤销窗口**：5 秒（`changeCommitment` 1 小时）；闭包在内存，进程重启后 `E_UNDO_EXPIRED`。UI 的撤销按钮就是这条。
- **一天一个**：`materializeWeek` 从今天起一天放一个单元，放不下叠在最后一天；已有 occurrence 的周不重复生成。
- **结转**：`settleWeek` 幂等（重复调用返回历史数字）；错过标 `missed`，最多结转 2 个到下周，剩下记 `carry.pending`。
- **复盘**：`generateReview` 只读事件、写一条 `review.generated` 快照；之后数字不再变（幂等，二次调用返回原 `reviewId`）。
- **as-of**：`asOfCompletedCount(planId, week, maxSeq)` 只看 `seq <= maxSeq` 的事件，给「回放/时间线」用。
- **日层无加量命令**：加量只能改周承诺（`changeCommitment`），日视图只做完成/顺延/拆分/缩短/跳过。
- **错误文案**：一律「没改成 + 原因 + 下一步」，不要把 SQL 错误直接抛给用户。

## 5. 测试

`features/learning-process/src/jvmTest/.../LearningPlanServiceTest.kt`（15 个用例，`gradlew.bat test` 全绿）：

- P0 垂直切片：建计划 → 草稿 → 接受 → 今天看到 → 完成 → 撤销；
- P1：顺延（新行 + `moved_from`）、拆分（总和校验）、缩短（低于最小版本 → `minimal`）、撤销；
- P2：结算幂等 + 结转上限 2 + `carry.pending`、复盘固化与幂等、as-of 统计、改承诺换版本并保留旧版本；
- 并发/不变量：旧 `expectedLastSeq` → `E_CONFLICT`、`dryRun` 不留数据、同 `commandId` 不双写；
- 触发器：事件 UPDATE/DELETE、active 版本 `plan_unit` UPDATE、occurrence 改 day 全部抛 `SQLException`。

## 6. 每天投入与容量打包（2026-10-28）

- 计划可以带一个「每天投入多少分钟」，存在 `process.weekly_capacity_minutes = 每天 × 7`
  （`PlanCommands.cmdCreateProcess` 校验 30–3000）。页面通过 `PlanStateView.dailyMinutes`
  读回来（`PlanQueries.buildPlanState` 里 `/7`，不足 15 按 15 算）。
- `materializeWeek(planId, versionId, week)` 铺天时的规则：
  - **设过投入时间**：`planDailyMinutes(planId)` 拿到每天预算；按顺序往当天塞，
    `usedToday` 累加，「再放就超预算」就换下一天；单个单元本身超预算则独占一天。
  - **没设过**：保持老规矩「一天只排一个单元」——不猜容量，免得把没设置的计划塞成一天两三个。
- 测试：`LearningPlanServiceTest.daysArePackedUpToTheDailyBudget`
  （20+25 同一天、30 顺到第二天、35 顺到第三天）。
- `PlanTools.create_learning_plan` 新增 `dailyMinutes`（15–240）：写进 `process` 的同时，
  把「第一次只排本周 + 下一周，其余留待安排」这条也写进工具提示与校验。

## 6.1 改计划每天投入：`setPlanDailyMinutes`（2026-10-28）

计划属性，不是全局设置——弹窗改的是**当前这个计划**，换一个计划是另一套。

- 契约：`sdk-domain/.../plan/LearningPlanService.kt`
  `fun setPlanDailyMinutes(env: CommandEnvelope, planId: String, dailyMinutes: Int): CommandResult<Unit>`
- 实现：`PlanCommands.cmdSetPlanDailyMinutes` — `dailyMinutes !in 15..240` 直接
  `E_INVALID`（文案「每天投入要在 15–240 分钟之间（写 15 到 240 的数字）。」）；
  计划不存在 `E_NOT_FOUND`；成功后

  ```sql
  UPDATE process SET weekly_capacity_minutes = ?
  WHERE id = (SELECT process_id FROM plan WHERE id = ?)   -- 参数：dailyMinutes * 7, planId
  ```

  并追加事件 `plan.capacity_changed`，payload `{"dailyMinutes":N,"weeklyCapacityMinutes":N*7}`、
  `actor` 取命令里的 actor（UI 是 `user`）。
- 语义：只影响**之后铺的天**；已经 materialize 的 occurrence 不动（`materializeWeek` 只在
  `commitment` 尚无 occurrence 时插入）。
- 验证：`LearningPlanServiceTest.aDailyBudgetPacksTheFirstDay` 覆盖「写入后 `planState().dailyMinutes` 回读」与
  「没设节奏的计划仍一天一个」；`daysArePackedUpToTheDailyBudget` 覆盖 20+25 同一天；越界（600）返回
  `E_INVALID`。桌面侧用真实 DB 验过「选 90 → `weekly_capacity_minutes = 630`，
  `event` 里 `payload_json={"dailyMinutes":90,"weeklyCapacityMinutes":630}`」。

## 7. 下一步（按优先级）

1. **P3 agent 工具**：`design/plan-agent-tools.md` 里的工具（日视图/完成/顺延/结算…）包一层 `AgentTool`，
   接进 `AppRuntime.sendChat` 的 `allTools`，并把清单（`set_plan`）里的步骤和这里的命令对齐。
   → **2026-10-29 已落地一部分**：`PlanAdminTools`（`get_learning_plan` / `plan_next_week` / `complete_plan_node` /
   `move_plan_node` / `review_last_week` / `set_plan_pace`），见 §8 与 `docs/plan-rhythm-draft.md`；
   待办（todo）类命令与「建议完成（证据核验）」仍未接。
2. **P4 桌面接线**：`LearningPlanStore.open(<home>/learning-plan.db)` 在 `AppRuntime.create()` 里装配；
   任务页换成 `PlanUiPlugin` 的新卡片（或新增挂载点），按 `design/plan-ui-contract.md` 的 view model 渲染。
3. **迁移与备份**：`schema_migration` 现在只写 v1；加升级脚本时保持「只加表/加列」，触发器不动。
4. **导出**：`exportJsonl` 已有，导入（`importJsonl`）在契约里但实现返回 `E_UNSUPPORTED`，等确定冲突策略再做。

## 8. 2026-10-29：周滚动 `advanceRhythm`（计划自己往前走）

> 设计背景与取舍见 `docs/plan-rhythm-draft.md`；这里只记落地后的契约与不变量。

- 契约新增 `fun advanceRhythm(env, planId): CommandResult<RhythmResult>`（`Contract.kt` 里新增 `RhythmResult`）。
  一次做四件事：①结算所有「已结束但还没结账」的周（`settleWeek`，幂等）；②从待安排池给**本周 + 下一周**
  补承诺；③为刚结账的周生成复盘（`generateReview`，幂等）；④铺本周（周日加铺下周）。
  没有实际动作时不写库、不写事件；`paused`/`ended` 直接返回一句 note。
- **补承诺不开新版本**：`cmdExtendCommitment` 往**当前活跃版本** INSERT 一条未来周的 `commitment`，
  不改任何旧行。理由：`plan_unit`/`commitment` 身份按版本隔离，新开版本会换 unit id，
  已铺天的 occurrence 还挂在旧 id 上（身份列不可 UPDATE），会出现重复铺天/完成状态丢失。
  触发器只禁 UPDATE/DELETE，不禁 INSERT，所以这条路径与「版本不可变」不冲突。
- `materializeWeek` 判重从「这张承诺有没有任何 occurrence」改成**单元级**：结转/顺延/拆分留下的行
  不再让整周不铺；当天已被结转占用的分钟数算进每日预算。
- `changeCommitment` 修掉「只复制单元、不复制其它周承诺」的缺陷：改某一周时，其它周承诺原样复制到新版本
  （以前改一次本周会把下周从活跃版本里弄丢）。
- 选量规则（唯一实现，`PlanAdvance.kt`）：周容量 = `dailyMinutes×7×80%`；按路线顺序、尽量不切章节；
  最近一段过去的周完成率 < 60% 再缩 15%；每周 ≤7 个；池空不建空承诺。
- 触发：`AppRuntime.refreshPlan()` 对所有未结束计划调一次（`origin=scheduler`），
  应用启动/进计划页/跨天时天然覆盖；失败只跳过那一份节奏。
- 测试：`LearningPlanRhythmTest`（4 周滚动、结转 + 铺天共存、周日预铺不重复、改承诺保留其它周、
  选量上限 7、暂停不推进）与既有 18 个用例全绿。
