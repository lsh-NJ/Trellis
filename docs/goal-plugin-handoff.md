# 交接：目标达成规划插件（feat/goal-planning）

> 这份文档给接手的人或下一个会话用。先读「现状」，再读「没做完的四件事」，
> 最后一段是可直接复制的提示词。

## 现状

| 项 | 值 |
|---|---|
| 分支 | `feat/goal-planning`，基线 `origin/main` = `becf88a` |
| 提交 | `f1587b7` 目标达成规划插件：宿主扩展点 + 插件本体 + 三处界面 |
| 编译 | `:app:desktop:compileKotlin` 通过 |
| 测试 | `:features:goal-planning:jvmTest` 15 条全绿；`:ai:jvmTest` 的 `PromptSectionTest` 3 条；`:app:desktop:test` 的 `CapabilitySurfaceTest` 挂载点断言通过 |
| 未验证 | 三张卡的离屏渲染、两条演示路径、`required` 卸载拒绝的运行时断言 |

### 分支关系（容易搞错）

- `main` 是朋友的仓库分支，不是本人的。**不要直接往 main 推。**
- 本人的贡献分支是 `feat/ui-shell-and-model-import`。PR #4 合并时只拆入了
  「模型 API 一键导入」(`4998f04`)，**UI 外壳重构没有进 main**。
- main 现在还有两块别人的东西：yang 的视觉方案（暖纸+靛蓝、日期刊头、七格周条）、
  ActivityWatch 活动页（`features/activity` 已接线）。
- 所以本分支**不搬 UI 外壳**，只搬与插件相关的：宿主两个扩展点 + 插件模块 + 三处界面 + 注册。

## 已完成

### 宿主（两个通用扩展点，命名不含业务词）

| 位置 | 内容 |
|---|---|
| `kernel/.../slot/Slot.kt` | 新增 `slot.chat.aside`（对话页常驻侧条），`Slots.all` 同步 |
| `kernel/.../prompt/PromptSection.kt` | 新增 `ai.prompt.section` 扩展点；契约 `PromptSection { id, order, budgetChars, render() }` |
| `ai/.../manager/ManagerPrompt.kt` | `messages()` 接受 `sections`，包进 `<plugin_context>`，位置在 `<current_state>` 之后、`<task:...>` 之前 |
| `ai/.../manager/ManagerAgent.kt` | `respond()` 透传 `sections` |
| `app/.../AppRuntime.kt` | `RENDERED_MOUNT_POINTS` 6 → 7；新增 `promptSections()`（按 order 排、各自截预算、单段失败只跳过它自己）；`sendChat` 传 `sections` |
| `app/.../ChatScreen.kt` | 对话页右侧渲染 `SlotArea(ChatAside)`，没有贡献时整块不出现 |
| `app/.../CapabilitySurfaceTest.kt` | 挂载点断言 6 → 7 |

### 插件 `:features:goal-planning`（11 文件，约 1980 行）

| 文件 | 职责 |
|---|---|
| `GoalModel.kt` | `GoalContract`（原话不改写）、12 个 `FieldKey` 分通用/学业/求职三套必备字段、`confidence = Σ(w×score)/Σw`、模型微调 ±0.05 且必须写理由 |
| `DayPlan.kt` | `DayEntry` 五项必填、四态 `PAST/TODAY/TOMORROW/FUTURE`、ISO 日期纯函数、`DayWindow.select` 越界返回 `OutOfWindow`+原因 |
| `DayPlanValidator.kt` | minutes 5–240、合计 ≤ 每日投入 ×1.2、空洞产出词、模糊词、动词开头、每日 2–5 条 |
| `GoalCompressor.kt` | `DayDigest ≤60` / `WeekDigest ≤120` / `PhaseDigest ≤200`；上下文块硬限 800，超了先砍历史、再砍明天、最后砍今天 |
| `GoalPlanner.kt` | VAGUE/DIRECTED 只出通用层；未知分叉转成决策任务；CONCRETE 才允许实体（`EntityLexicon` 固定词表） |
| `GoalState.kt` | 六态状态机 + `GoalSettings`（复盘周期 3–30 天、推送时间） |
| `GoalEngine.kt` | 纯函数：start / answer / skip / toDraft / confirm / settle（幂等）/ pushDue（`lastPushedDate` 幂等）/ complete / reset / contextBlock |
| `GoalKit.kt` | `ctx.kv` 读写，单键存整份 `GoalMirror` |
| `GoalService.kt` | 缓存 + 结算 + 推送门面（`Mutex` 串行，避免三张卡读到三份快照） |
| `GoalSlots.kt` | 三个 `DynamicSlot`，只交 `UiNode`：`goal-bar` / `goal-today` / `goal-settings` |
| `GoalPlugin.kt` | manifest：`required=true`、`builtin=true`、`limitation` 原样写推送边界；`init()` 注册 4 处 |

## 没做完的四件事

### 1. ActivityWatch 适配（用户明确要求）

现在插件完全不知道「实际投入」，`GoalService` 也拿不到活动数据。设计已定，照做即可：

- `AppRuntime.activityDay(dayMs: Long): DaySummary?`（约 2584 行）是唯一现成入口，
  `DaySummary.activeMs` 是扣掉 AFK 后的真实活跃时长。
- 给 `GoalPlugin` 增加第三个注入参数，**不硬绑**：
  ```kotlin
  // app 侧
  GoalPlugin(
      dailyMinutes = { runtime.studyPrefs.dailyMinutes },
      today = { java.time.LocalDate.now().toString() },
      onOpenChat = { runtime.currentTab = TAB_CHAT },
      // 没装 ActivityWatch / 没同步过 → null，界面就不显示这一行
      activityMinutes = { runtime.activityDay(todayStartMs())?.activeMs?.div(60_000L) },
  )
  ```
- `GoalTodaySlot` 里加一行对比：「计划 90 分钟 · 实际 62 分钟」；
  **只在 `activeMs > 0` 时显示**，`hasAfkData == false` 时加一句「这个数只统计了窗口，可能偏高」。
- `GoalEngine.contextBlock` 增加一行 `【投入】计划 90 分 · 实际 62 分`，
  但**不参与**次日排期计算 —— 排期是承诺，不是按昨天的偶然数据自动缩水。
- 注意：activity 是 app 层能力，不在 `ctx.services` 里。用 lambda 注入是**可选依赖**的正确写法，
  不要让 `:features:goal-planning` 依赖 `:features:activity`。

### 2. 三张卡的离屏渲染

`ShellShotsTest` 已有 `ImageComposeScene` 的写法。要补的是三个状态：
`EMPTY`（还没有目标）、`ACTIVE`（有目标+今日条目）、`REVIEWING`。
构造 `GoalMirror` 直接塞给 slot 比走真实 kv 快得多 —— `GoalService` 的构造需要 `KvStore`，
测试里用 `extraBuiltins` 注一个假 `GoalPlugin`，或给 `GoalService` 加一个 `seed` 构造参数。

### 3. 两条验收断言（8 条里的第 6、7 条）

- **不可卸载**：`kernel.remove("core.goal")` 应返回 `Rejected`，文案「必需插件不可卸载」。
  在 `:app:desktop` 的测试里用 `AppRuntime.create(tempHome)` 起真内核，再调 `remove`。
- **内核纯净**：`verifyKernelPurity` 已随 `check` 跑；新增的 `PromptSection` / `Slots.ChatAside`
  命名不含业务词，目前通过。改扩展点名字时别用 `goal` / `plan` 这类词。

### 4. 两条演示路径 + PR

```bash
./run-app.cmd                          # 或 gradlew :app:desktop:run
# 用户 A：对话里说「我想考研」→ 3 轮内收齐 → 出通用周计划 → 出现「查院校复试线」这类决策任务
# 用户 B：说「我要 6 月前拿到 XX 岗 offer」→ 直接 DRAFT → 日计划含针对性内容
```
演示完把截图放 `_shots/goal-planning/`，再开 PR 指向 `lsh-NJ/Trellis` 的 `main`。

## 硬约束（会被断言打回来）

- 内核不认识领域：`verifyKernelPurity` 扫 `kernel/`，出现 `Goal` / `TaskStore` 就红。
- 时间走 `ctx.clock`，插件里不许出现 `System.currentTimeMillis()`。
- 插件间只通过 `ctx.services` 通信，不许互相 import 具体类。
- 插件数据只走 `ctx.kv`（接口里没有 namespace 参数）。
- UI 只交 `UiNode`，不许注入 Compose。
- 中文源码用编辑器改，不要用 PowerShell 回写（UTF-8 读成 ANSI，中文全变问号）。
- 应用开着时不要编译；改完代码先关窗口。

## 可直接复制的提示词

```text
你是 Trellis 项目的资深工程师。仓库在 C:\Users\dxy20\Desktop\Trellis-main，
当前分支 feat/goal-planning（基线 origin/main = becf88a，提交 f1587b7）。
注意：main 是朋友的仓库分支，不要直接往 main 推；PR 走自己的分支。

【现状（已核实，不用重新调研）】
1. 目标达成规划插件已经完成：宿主两个扩展点（slot.chat.aside / ai.prompt.section）
   + :features:goal-planning 模块（11 文件，约 1980 行）+ 三处 DynamicSlot（UiNode 数据）
   + AppRuntime 注册（required=true，不可卸载）。
2. 编译通过：./gradlew :app:desktop:compileKotlin。
3. 测试通过：:features:goal-planning:jvmTest 15 条、:ai:jvmTest PromptSectionTest 3 条、
   :app:desktop:test CapabilitySurfaceTest 挂载点 7 断言。
4. 前情：feat/ui-shell-and-model-import 的 PR #4 只被拆入「模型 API 一键导入」(4998f04)，
   UI 外壳重构没有进 main；main 另有 yang 的视觉方案和 ActivityWatch 活动页。

【这轮要做的四件事，按顺序】
P0 ActivityWatch 适配（用户明确要求）
- 入口：AppRuntime.activityDay(dayMs: Long): DaySummary?，DaySummary.activeMs 是扣 AFK 后的活跃时长。
- 给 GoalPlugin 增加 activityMinutes: () -> Int? 注入（app 侧传 { runtime.activityDay(...)?.activeMs?.div(60000) }），
  :features:goal-planning 不许依赖 :features:activity，也不许进 ctx.services。
- GoalTodaySlot 增加一行「计划 90 分钟 · 实际 62 分钟」，仅在 activeMs > 0 时显示；
  hasAfkData == false 时补一句「只统计了窗口，可能偏高」。
- GoalEngine.contextBlock 增加一行【投入】，但不得参与次日排期计算（排期是承诺，不按昨天缩水）。

P1 三张卡的离屏渲染
- 仿 ShellShotsTest 的 ImageComposeScene 写法，渲染 EMPTY / ACTIVE / REVIEWING 三个状态，
  输出到 app/desktop/build/shots/ 并归档到 _shots/goal-planning/。

P2 两条验收断言
- 不可卸载：AppRuntime.create(tempHome) 后调 kernel.remove("core.goal")，断言 Rejected 且文案为「必需插件不可卸载」。
- 内核纯净：./gradlew check 里的 verifyKernelPurity 必须绿。

P3 两条演示路径 + PR
- 用户 A「我想考研」→ 3 轮内收齐 → 通用计划 + 决策任务；用户 B「6 月前拿到 XX 岗 offer」→ 直接 DRAFT。
- 截图进 _shots/goal-planning/，开 PR 指向 lsh-NJ/Trellis 的 main。

【铁律】
- 内核不认识领域；时间走 ctx.clock；插件数据只走 ctx.kv；UI 只交 UiNode。
- 每步先写「只动这几个文件」，改完 git diff --stat 核对，只 git add 具体文件。
- 中文源码用编辑器改，不用 PowerShell 回写。应用开着时不要编译。
- 每步结束都要能独立编译；不要一次性重写。

【交付】
每步给：改了哪些文件 / 编译与测试的实际输出 / 还差什么。
最后必须回答：ActivityWatch 数据是怎么接进来的（哪一行）、有没有硬绑模块依赖、
五条硬需求里有没有降级（降级原文写进 manifest 的哪个字段）。
```
