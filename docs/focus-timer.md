# 计时器小窗

单任务的专注计时器。点任务行右端的 ⏱ 打开一个独立小窗，窗口只有一件事——把这段时间数出来。

## 为什么是独立窗口

- 任务行里塞不下「大数字 + 进度条 + 暂停/延长」；挤进去会把待办行做成控件墙。
- 独立窗口可以置顶、可以拖到副屏，属于「放在旁边一直看着」的东西，而不是一块卡片。
- 关掉小窗不影响任务，也不写数据；只有「结束并记入」才把分钟数写进任务的已投入时间。

## 两种模式

| 模式 | 显示 | 进度条 | 用途 |
| --- | --- | --- | --- |
| 倒计时 | 剩余时间 `mm:ss`，超过 1 小时 `h:mm:ss` | 有，走完即止 | 番茄钟，默认 25 分钟，可 5–180 分钟，预设 15′/25′/45′ |
| 正计时 | 已用时间 | **没有** | 不确定要花多久的开会、读文献、调 bug |

正计时不画进度条是刻意的：没有总量的东西画进度条只会撒谎。界面上用一行小字说明这件事（「正计时 · 没有进度条，到停为止」），免得被当成渲染 bug。

## 交互

- 未开始：切模式、调番茄钟分钟数、`开始`；下面一行「不计时，直接记 25 分钟」——原来任务行上的「记 25 分钟」挪到了这里，免得同一行出现两个钟表图标。
- 运行中：`暂停/继续`、`+5 分钟`（仅倒计时）、`结束并记入`、`关闭`。
- 到点：显示「时间到 · 共 N 分钟」，可以「再来一轮」或「结束并记入」。
- `结束并记入` = 把已用秒数向上取整成分钟，加到任务的已投入时间上，然后关窗（0 分钟则不写）。

## 代码

| 位置 | 职责 |
| --- | --- |
| `app/desktop/src/main/kotlin/dev/trellis/app/desktop/FocusTimer.kt` | `FocusMode`、纯函数（`formatClock` / `timerProgress` / `advanceTimer` / `minutesToLog` / `clampTimerMinutes`）、`FocusTimerWindow` |
| `app/desktop/src/main/kotlin/dev/trellis/app/desktop/AppRuntime.kt` | 小窗状态（`timerVisible`、`timerTaskId`、`timerMinutes`、`timerElapsedSeconds`…）与动作（`openTimer` / `chooseTimerMode` / `startTimer` / `toggleTimerPause` / `extendTimer` / `stopTimerAndLog`） |
| `app/desktop/src/main/kotlin/dev/trellis/app/desktop/Main.kt` | 第二 `Window`（`alwaysOnTop`、右下角、380×400）与每秒一次的 `timerTick` 驱动 |
| `app/desktop/src/main/kotlin/dev/trellis/app/desktop/AppScreen.kt` | 任务行悬停区的 ⏱ 入口（只在未完成任务上出现） |

## 边界

- 不做后台提醒/通知：窗口关了计时就停了，没有系统通知。
- 不做多任务并行计时：同一时刻只有一个计时器，换任务会覆盖当前计时。
- 不改任务的其它字段：只写「已投入分钟数」，不自动勾完成。
- 计时状态不持久化：重启应用即丢（避免出现「重启后有个计时在偷偷跑」）。

## 验证

- `app/desktop/src/test/kotlin/dev/trellis/app/desktop/FocusTimerTest.kt`（7 例：时钟格式、正计时无进度、倒计时封顶、分钟向上取整、分钟夹取）。
- 手动：任务行悬停 → ⏱ → 倒计时（有进度条）/ 正计时（无进度条）→ 结束并记入 → 任务行的「已投入」增加。
