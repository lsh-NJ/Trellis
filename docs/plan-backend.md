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

## 7. 下一步（按优先级）

1. **P3 agent 工具**：`design/plan-agent-tools.md` 里的工具（日视图/完成/顺延/结算…）包一层 `AgentTool`，
   接进 `AppRuntime.sendChat` 的 `allTools`，并把清单（`set_plan`）里的步骤和这里的命令对齐。
2. **P4 桌面接线**：`LearningPlanStore.open(<home>/learning-plan.db)` 在 `AppRuntime.create()` 里装配；
   任务页换成 `PlanUiPlugin` 的新卡片（或新增挂载点），按 `design/plan-ui-contract.md` 的 view model 渲染。
3. **迁移与备份**：`schema_migration` 现在只写 v1；加升级脚本时保持「只加表/加列」，触发器不动。
4. **导出**：`exportJsonl` 已有，导入（`importJsonl`）在契约里但实现返回 `E_UNSUPPORTED`，等确定冲突策略再做。
