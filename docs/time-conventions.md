# 时间与时钟规范（Trellis）

> 性质：**规范（要遵守）**。适用范围：`kernel` / `sdk-domain` / `features/*` / `ai` / `app:desktop` / 插件运行时全部模块。
> 最后更新：2026-10-29。与代码冲突时以本文为准，并顺手把代码改对。
> 状态：本文先定规则；`TrellisClock` 的抽出与 P0 迁移是**下一步**（见 §9）。在落地前，存量违规按 §9 的批次改，**新代码从现在起按本文执行**。

---

## 0. 六条 TL;DR（其余章节都是它们的展开）

1. **业务时间只有一个来源**：注入的时钟。生产代码禁止直接 `LocalDate.now()` / `Instant.now()` / `System.currentTimeMillis()` 取业务时间。
2. **日期从「墙钟 + 时区」推导**，不直接截断 `Instant`；周一律 ISO-8601（周一起，`YYYY-Www`）。
3. **测量时长/超时/计时器只用单调时间**（`monotonicMs` / `System.nanoTime`），禁止墙钟相减。
4. **存储格式固定**：时间戳 = epoch 毫秒（UTC）；日期 = `YYYY-MM-DD`；周 = `YYYY-Www`；不用「本周/上周」这类相对词当 key。
5. **时间逻辑必须能用固定时钟测**：`Thread.sleep` 不算测时间；边界（跨天/跨周/跨月/跨年/跨时区）要覆盖。
6. **调试只跳「应用内日期」**：开发者模式 + `TRELLIS_TODAY`，不系统改时区，不做时间加速，不拿真实数据目录玩。

---

## 1. 两套时间，不能混

| | 墙钟（wall clock） | 单调钟（monotonic） |
|---|---|---|
| 用来回答 | 「今天几号 / 第几周 / 什么时候发生的」 | 「过了多久 / 是否超时」 |
| 来源 | 注入时钟的 `nowMs()` | `monotonicMs()`（底层 `System.nanoTime`） |
| 会被用户/系统调整影响 | 会 | 不会 |
| 典型用途 | 计划日期、ISO 周、事件时间戳、复盘归属、逾期判断、相对时间文案 | 计时器、请求超时、退避、看门狗、动画、耗时统计 |
| 禁止 | 用墙钟相减算时长 | 用单调钟存档/展示日期 |

> 反例：计时器用 `System.currentTimeMillis()` 相减——用户校时或夏令时会算出负数/几小时；活动汇总时间戳用单调钟——换个进程就对不上真实世界。

**时区（zone）**：业务日期按注入时钟的 zone 划分；一天 = `[00:00, 次日 00:00)` 本地时间，一周 = `[周一 00:00, 下周一 00:00)`。默认 `ZoneId.systemDefault()`；测试固定 `Asia/Shanghai`（或按被测场景显式给另一个 zone）。

---

## 2. 唯一时间源：`KernelClock` + `TrellisClock`

内核已有 `kernel/.../time/KernelClock.kt`：

```kotlin
interface KernelClock {
    fun nowMs(): Long          // 墙钟，Unix 毫秒
    fun monotonicMs(): Long    // 单调时钟，只用于测耗时
}
object SystemClock : KernelClock
class FakeClock(...) : KernelClock
```

应用层（`app:desktop`）统一用 **`TrellisClock`**（下一步新增，实现 `KernelClock`，并补 `TrellisClock` 类型与装配）：

```kotlin
interface TrellisClock : KernelClock {
    val zone: ZoneId
    fun today(): LocalDate
    fun isoWeek(): String
}

object SystemTrellisClock : TrellisClock                       // 生产
class FixedTrellisClock(date: LocalDate, zone: ZoneId) : TrellisClock  // 测试 / 开发者跳日期
```

装配规则：

- **全应用只组装一次**：`AppRuntime.create()` 里创建 `clock`，之后一律从它取；不要每个页面各造一个。
- 传内核：`KernelConfig(clock = appClock)`（内核/插件已经走 `ctx.clock`，不用改）。
- 传计划后端：`LearningPlanStore.open(file, clock = { appClock.nowMs() }, zone = appClock.zone)`。
- 传 AI 工具：`PlanTools` / `PlanAdminTools` 等取「今天/本周」的地方用同一个 clock，禁止自己 `LocalDate.now()`。
- 测试：`FixedTrellisClock` 或后端已有的 `clock: () -> Long` 手动推进；**不要**做可变全局单例时钟（并发测试会串）。
- 例外白名单（可以直接取真实墙钟，但要在代码里写理由）：
  1. `kernel` 的 expect/actual 平台原语实现（`platformCurrentTimeMillis` 等）；
  2. 诊断/审计日志（`app.log`、崩溃栈、`audit.log`）——它们要和系统/网络日志对时间；
  3. 测试 fixture 里的固定时间常量。
  其余生产代码一律注入。

**禁止新增**：

- `LocalDate.now()` / `LocalDateTime.now()` / `Instant.now()` / `System.currentTimeMillis()` 取业务时间；
- `Clock.systemDefaultZone()` 这类隐式系统时钟；
- 可变全局 `var clock`（调试入口除外，见 §7）。

---

## 3. 日期、周、时区

- 日期统一用 `LocalDate`；字符串用 ISO `YYYY-MM-DD`（`LocalDate.toString()`）。
- 周号唯一实现：`PlanTime.isoWeek(date)`（`sdk-domain`/`features:learning-process` 已有）。**不要**自己用 `Calendar`、`WeekFields` 或手写「一年第几周」。
- ISO 周是周一起算、跨年按 ISO 周年：`2026-12-31` 可能属于 `2027-W01`；含 53 周的年份（如 2026）`2026-W53` 合法。所有周号计算必须有跨年用例。
- 禁止把 `Instant` 直接 `.atZone(UTC).toLocalDate()` 当业务日期（在 Asia/Shanghai 会差 8 小时）。
- 长期存储/比较一律用「日期字符串/周字符串/epoch 毫秒」，不用「今天/本周/上周」相对词；相对词只出现在文案层。

---

## 4. 时长与超时

- 计时器、请求超时、重试退避、看门狗、动画、耗时统计：`monotonicMs()`（或 `System.nanoTime()`）；禁止墙钟相减。
- 协程内等待/超时用 `withTimeout` / `delay`（测试可用虚拟时间）；禁止 `Thread.sleep` 等业务时间。
- 计时器小窗的「倒计时/正计时」是单调时间的典型用户：墙钟跳变不应影响它（见 `docs/focus-timer.md`）。
- SSE/HTTP 的「多久没有新数据」看门狗也用单调时间（见 `HttpLlmClient` 的 `ResponseWatchdog`）。

---

## 5. 学习计划后端（`features:learning-process`）契约

- 时间必须由调用方注入：`LearningPlanStore.open(file, clock, zone)` / `inMemory(clock, zone)`；模块内部只允许 `nowMillis()` / `today()` 两个出口。
- 命令里凡会出现「今天/本周」的，一律由服务层从注入时钟计算，**不接受 UI 传字符串**；唯一例外是显式调试参数 `asOf`（只给 dev 工具用，且事件 payload 必须标记 `simulatedAsOf`）。
- 事件时间戳 = 注入 `clock()`；occurrence 的 `day` = 本地日期字符串（`YYYY-MM-DD`）。
- **禁止 SQL 时间函数**：`date('now')`、`strftime('%Y'...)`、`CURRENT_TIMESTAMP` 等会绕过注入时钟（2026-10-29 盘点：现有代码没有用，保持为零）。
- 周滚动/结算/铺天/结转/复盘等时间推移逻辑，必须能在 `LearningPlanRhythmTest` 的模式下用假时钟覆盖，不靠真机等一周。

---

## 6. 应用与界面（`app:desktop`）

- `AppRuntime` 持有唯一 `TrellisClock`；Composable **不自己算业务日期**：需要「今天/本周」时从 runtime 的状态/参数拿（例如 `runtime.today` / view model 字段）。
- 纯展示函数接受 `nowMs`/`today` 参数（仓库已有模式：`chatTimeLabel(ms, now)`、`usageHint(now, lastUsedMs, …)`、`DueDates` 的 `nowMs` 参数），不要在函数体里取系统时间。
- 计划链路必须与后端同源：`refreshPlan` 的「今天/本周」、`currentIsoWeek()`、`PlanTools` 的周号、`PlanAdminTools` 的今天、`PlanScreen` 的今天标记，全部走注入 clock。
- 迁移顺序见 §9（先计划链路，再显示类，再其它）。
- 允许保留系统时间的 UI/基础设施点：`app.log`/崩溃日志、截图文件名、窗口/动画计时、测试 fixture——这些不是业务时间。

---

## 7. 调试：开发者模式与「跳日期」

**目标**：能在真机上验证「跨天/跨周/周日预铺/结算结转/复盘」这类行为，不改系统时钟、不等一周。

规则：

1. **入口一（最小）**：启动环境变量 `TRELLIS_TODAY=YYYY-MM-DD`。启动时构造 `FixedTrellisClock(date, zone = 默认 zone)`，作用于 §2 的注入链路；启动日志必须同时打印真实时间与模拟时间，例如：
   `[real 2026-10-29T21:30] 模拟时间：2026-11-02（真实 2026-10-29）`
2. **入口二（开发者模式，后续）**：设置页底部「开发者」区（默认隐藏，需 `TRELLIS_DEV=1` 或 `ui.json`/`dev.json` 开关）：显示「真实时间 / 模拟日期」、日期选择、`+1 天`、`+1 周`、退出模拟。模拟日期持久化到 `<home>/dev-clock.json`（`{"date":"2026-11-02","zone":"Asia/Shanghai"}`），重启不丢；删文件或点「退出模拟」回到真实时间。
3. **视觉提示**：开启模拟时必须让用户看得见——计划页/对话页头部显示「模拟时间 · 2026-11-02」徽章（不允许静默生效）。
4. **安全边界**：
   - 只允许在**非默认 home** 下使用（`TRELLIS_HOME`/`TRELLIS_DEV_HOME` 指向 demo/临时目录）；默认 `~/.trellis` 下拒绝启用（或至少强警告并要求二次确认）。
   - **不改系统时钟、不改系统时区**；不做「时间加速/倍速」（事件溯源的时间戳会混进真实语义，动画/计时器也会乱）。
   - 模拟时间只影响本进程的业务时钟；活动采集的原始事件仍按真实时间入库（所以模拟日期下活动页可能是空的，属预期）。
5. **可审计**：dev 跳日期触发的自动写事件，`origin` 标为 `scheduler.dev`（而不是 `scheduler`）；带 `asOf` 的命令在事件 payload 里记 `simulatedAsOf`。这样以后能区分「真到点」和「调试跳的」。
6. **跳完必须刷新**：跳日期后调用 `refreshPlan()`（或重启应用），让 `advanceRhythm` 与视图重算；不要只改内存里的显示变量。

---

## 8. 测试要求（时间逻辑的 Definition of Done）

任何涉及「今天/本周/跨周/时长/超时」的改动，合入前必须满足：

1. 用注入时钟（`FixedTrellisClock` 或后端 `clock` 参数），**不允许** `Thread.sleep` / 真等时间。
2. 必测边界（按相关性选，至少覆盖本次改动涉及的）：
   - 跨天：`23:59` → 次日 `00:00`（日切）；
   - 跨周：周日 → 周一（周切）；周日晚 = 预铺下周的触发点；
   - 跨月、跨年（ISO 周年）：`2026-12-31` 属于 `2027-W01` 这类；53 周年份；
   - 时区：默认 `Asia/Shanghai` 之外，至少一个 DST zone（如 `America/New_York`）验证日期边界；
   - 闰年：`2028-02-29`。
3. 时间推移逻辑优先写「逐日推进 + 不变量断言」：连续 N 天（28/60）每天调一次推进，断言：无重复 occurrence、无孤儿 commitment、待安排池单调递减、周号连续、完成/错过状态不丢、事件 seq 单调。
4. UI 的日期相关展示抽纯函数（周卡/灰卡/今天标记/相对时间文案），函数接受日期或 `now` 参数并单测。
5. 新增命令/接口若涉及「今天」，契约里不出现由 UI 传入的 `today` 字符串（调试 `asOf` 除外）。

---

## 9. 存量盘点与迁移（2026-10-29）

**已符合本规范**：

- `kernel`：`KernelClock`（`nowMs` + `monotonicMs`），插件走 `ctx.clock`；
- `features:learning-process`：`LearningPlanStore.open/inMemory` 已注入 `clock`/`zone`；时间单测已有（`LearningPlanRhythmTest` 7 个）；
- SQL 中没有 `date('now')` 类函数（盘点为零）。

**待迁移**（`app/desktop` 生产代码中直取系统时间约 38 处，前端显示类为主）：

| 批次 | 范围 | 涉及文件（示例） |
|---|---|---|
| **P0 计划链路（先做）** | 「今天/本周」必须与后端同源 | `AppRuntime.kt`（`refreshPlan` 的 today、`currentIsoWeek`、`planStartsInPast`）、`PlanTools.kt`（`currentIsoWeek`/`nextIsoWeek`）、`PlanAdminTools.kt`（`planToday`）、`LearningPlanStore.open` 调用点、`PlanScreen.kt`（今天标记） |
| P1 显示类 | 相对时间/逾期/默认选中日 | `ChatStore.kt`（`chatTimeLabel`）、`PluginsScreen.kt`（`usageHint`/daysLeft）、`AppScreen.kt`（逾期、今天）、`ActivityScreen.kt`（`LocalDate.now()` 三处）、`DueDates.kt` 调用点 |
| P2 其它 | 与业务无关，保留真实时间即可 | 截图文件名、`app.log`/崩溃日志、`audit.log`、`plugin-usage` 时间戳（写入用真实时间，展示时用注入 now 计算相对量） |

**自检命令**（PR 前跑一遍，输出里除白名单都应改掉）：

```bash
grep -rn "LocalDate.now()\|LocalDateTime.now()\|Instant.now()\|System.currentTimeMillis()" \
  app/desktop/src/main/kotlin ai/src/commonMain features/*/src/main 2>/dev/null
```

**后续强制手段（Phase 2）**：在 `check` 里加一个 `TimeSourceAudit` 测试/脚本，按白名单扫描上述关键词，违规直接构建红（思路同 `verifyKernelPurity`）。

---

## 10. 评审/自检清单（10 条）

- [ ] 业务时间来自注入时钟；没有新增直取 `LocalDate.now()` / `Instant.now()` / `System.currentTimeMillis()`。
- [ ] 日期/周从 `clock.today()` / `isoWeek()` 推导；周号没有第二份实现。
- [ ] 时长/超时/计时器用单调时间，没有墙钟相减。
- [ ] 存储/接口格式符合 §3（`YYYY-MM-DD`、`YYYY-Www`、epoch 毫秒）。
- [ ] 后端命令没有让 UI 传「今天/本周」；调试 `asOf` 只在 dev 工具且已标记。
- [ ] SQL 里没有 `date('now')` / `CURRENT_TIMESTAMP` 之类时间函数。
- [ ] 新增时间逻辑有注入时钟的测试，且覆盖本次涉及的边界（跨天/跨周/跨年/时区）。
- [ ] UI 相对时间函数接受 `now` 参数，不在函数体里取系统时间。
- [ ] 若加了调试跳时间入口：仅 dev home、有「模拟时间」徽章、事件 origin 标 `scheduler.dev`、不改系统时间。
- [ ] 确有例外（诊断日志/平台原语/fixture）时，代码里有 `// 时间规范例外：<理由>`，PR 说明里登记。

---

## 11. 例外登记

| 日期 | 位置 | 理由 | 到期/复核 |
|---|---|---|---|
| 2026-10-29 | `Main.kt` / `AppRuntime.appendAppLog` / 崩溃日志 | 诊断日志需与系统/网络日志对齐 | 长期（§2 白名单） |
| 2026-10-29 | `kernel/.../time/KernelClock.kt` 的 expect/actual 实现 | 平台原语本身就是时钟来源 | 长期 |
| 2026-10-29 | 截图文件名、`audit.log`、`plugin-usage` 写入 | 与业务日期无关，真实时间更便于排查 | P2 复核 |

---

## 12. 一句话给改动的人

> 你这轮要不要碰时间？要碰就按本文：**取时间走注入的 clock，日期用 `today()`，周用 `isoWeek()`，时长用 `monotonicMs()`；新增业务取时间点不要直接 `LocalDate.now()`，也不要手动改系统时间。**
