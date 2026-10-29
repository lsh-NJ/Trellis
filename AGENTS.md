# AGENTS.md — Trellis 工程地图（给大模型/新同学的入口）

> 这份文件只做一件事：**让你在 10 分钟内知道这个项目是什么、代码在哪、改一件事该动哪个文件。**
> 细节不在这一份里——每个主题都有对应文档，见文末索引。
> 最后更新：2026-10-29。若与代码冲突，以代码为准，并顺手把这份文件改对。

---

## 0. 一句话

**Trellis 是一个「插件化」的学习计划追踪桌面应用**：内核零业务概念，所有功能（任务、统计、界面卡片）都是插件；
三种插件轨道并存（内置 Kotlin / JSON 声明式 / JS 脚本）；一个 **AI 插件管理员**常驻对话页，替用户创建、修改、编排插件与界面。

- 真实运行形态：**桌面端（Compose Multiplatform, JVM）**，`:app:desktop`。
- Android 只在设计文档里有位置，代码还没落地——看到 `androidJvm`/KMP 配置不代表能跑。
- 移动端合规、跨平台热插拔的完整调研见 `docs/plugin-hotplug-path.md`。

---

## 1. 30 秒上手

本机（Windows）已装 JDK 17：`%USERPROFILE%\.jdks\jdk17`。Gradle 用 wrapper（配了国内镜像）。

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk17"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
Set-Location C:\Users\breakM\Desktop\work\Trellis

.\run-app.ps1                      # 跑桌面应用（等价的：.\run-app.cmd、双开目录） 
.\run-app.ps1 --check              # 只编译，验证环境
.\gradlew.bat :app:desktop:run     # 跑桌面应用
.\gradlew.bat test                 # 全模块测试（kernel / plan-core / dsl / script / ai / desktop）
.\gradlew.bat check                # 测试 + 架构断言 verifyKernelPurity
```

> `.sh` 在 Windows 上没有执行关联，敲 `run-app.sh` 或双击会交给默认程序打开（通常是编辑器），不是脚本坏了；
> 要跑 bash 版本请进 WSL 或在 Git Bash 里 `./run-app.sh`。Windows 用 `run-app.cmd` / `run-app.ps1`。

> **跑应用前先确认没有旧实例在跑**：`Get-Process java | Stop-Process -Force`。
> **应用开着的时候别编译**：`:app:desktop:run` 的 classpath 是 `build/classes/` 目录，编译会原地重写；
> 已启动的进程用到旧 lambda 时会抛 `NoClassDefFoundError`，窗口关不掉（详见 README 的「铁律」段）。

调试/演示用环境变量：

| 变量 | 作用 |
|---|---|
| `TRELLIS_HOME` | 数据根目录，默认 `~/.trellis`；做演示/截图时指到临时目录，别动真实数据 |
| `TRELLIS_TAB` | 启动落在哪一页：`tasks`（默认）/ `plan` / `chat` / `activity` / `plugins` / `diag` |
| `TRELLIS_LLM_KEY` / `TRELLIS_LLM_MODEL` / `TRELLIS_LLM_ENDPOINT` | 覆盖 LLM 配置（也可写 `~/.trellis/llm.json`） |

> 历史会话留下的 `.dev/` 临时脚本已于 2026-10-29 清理，仓库里没有 `.dev/` 目录；也别再从别处捡回这类脚本当入口。
>
> **多个会话可能同时在改这个仓库**：开工第一句先写清「这轮只动哪几个文件」再动手（见 §10）。靠分支解决不了这个问题——它隔离提交历史，不隔离磁盘上的文件。

---

## 2. 模块地图（模块 → 源码根 → 职责 → 入口文件）

| 模块 | 源码根 | 职责 | 先看这些文件 |
|---|---|---|---|
| `:kernel` | `kernel/src/commonMain/kotlin/dev/trellis/kernel/` | 插件内核：生命周期、扩展点、能力/审批、存储、规则、表达式、UI 节点词汇。**不认识任何业务概念** | `api/Kernel.kt`（单文件门面，59KB，先看类头注释）<br>`plugin/PluginManifest.kt`、`plugin/Plugin.kt`<br>`extension/ExtensionPoint.kt`、`extension/ExtensionRegistry.kt`、`extension/ServiceRegistry.kt`（`KernelPoints`）<br>`slot/Slot.kt`（9 个挂载点）、`slot/UiNode.kt`（界面词汇表）<br>`spi/PluginLoader.kt`、`spi/BuiltinLoader.kt` |
| `:sdk-domain` | `sdk-domain/src/commonMain/kotlin/dev/trellis/domain/` | 领域契约：`Plan`/`Task`/`PlanSummary`/`PlanService`/`TaskQuery` | `Model.kt`、`PlanService.kt`、`PlanPoints.kt` |
| `:features:plan-core` | `features/plan-core/src/commonMain/kotlin/dev/trellis/feature/plan/` | `PlanService` 的内置实现（required 插件 `core.plan`），数据存 `ctx.kv` | `PlanCorePlugin.kt`（manifest + 注册）<br>`KvPlanService.kt`（增删改查 / summary / 事件）<br>`PlanActions.kt`（给 AI 的 6 个动作）<br>`PlanSummaryDataProvider.kt`（`summary.*` 数据字段） |
| `:plugin-dsl` | `plugin-dsl/src/commonMain/kotlin/dev/trellis/dsl/` | 轨道 A：把目录里的 JSON（`manifest.json` + `main.dsl.json`）变成真插件 | `DslLoader.kt`、`DslSchema.kt`、`PluginFileSystem.kt` |
| `:plugin-script` | `plugin-script/src/main/kotlin/dev/trellis/script/` | 轨道 B：ES5 脚本插件（Rhino 沙箱），可带状态、按钮、输入框 | `ScriptLoader.kt`、`ScriptPlugin.kt`、`ScriptSandbox.kt`（`trellis.register` API）、`NodeBuilder.kt`（脚本对象 → `UiNode`） |
| `:ui-kernel` | `ui-kernel/src/commonMain/kotlin/dev/trellis/ui/` | Compose 渲染层：把插件贡献画成卡片/挂载点；**只渲染数据，不执行第三方 Compose** | `SlotArea.kt`（挂载点容器 + 12 栏布局 + 卡片外壳）<br>`DynamicSlotView.kt`（`UiNode` 全词汇渲染，36KB，改渲染先看它）<br>`CardStyle.kt` / `ExtraColors.kt` / `Fonts.kt`（宿主样式注入）<br>`ComposeSlot.kt`（内置插件的 Composable 贡献） |
| `:ai` | `ai/src/commonMain/kotlin/dev/trellis/ai/` | AI 层：提示词、意图路由、工具循环、会话无关的纯逻辑（**唯一的 AI 入口是对话页的管理员**；不再有一次性生成管线） | `manager/ManagerRouter.kt`（意图单选 + 按意图给工具 + 拼提示词）、`manager/ManagerAgent.kt`、`manager/ManagerPrompt.kt`、`manager/Intent.kt`<br>`PromptBuilder.kt`（创建插件用的系统提示词）<br>`tool/ToolAgent.kt`、`tool/AgentTool.kt`<br>`tool/DeepSeekMarkup.kt`（DSML 方言解析）<br>`PluginInstaller.kt`（安装器接口，`KernelPluginInstaller` 实现）<br>`LlmClient.kt`；JVM 实现 `ai/src/jvmMain/kotlin/dev/trellis/ai/HttpLlmClient.kt` |
| `:features:learning-process` | `features/learning-process/src/jvmMain/kotlin/dev/trellis/feature/learning/` | **学习计划后端**（事件溯源 + SQLite）：计划版本、承诺、occurrence、结算结转、复盘。契约在 `sdk-domain/.../plan/`，与任务型 `PlanService` 是两套，勿混用 | `SqliteLearningPlanService.kt`（命令外壳/撤销/幂等/事务）<br>`PlanCommands.kt`（20 条命令）<br>`PlanQueries.kt`（日/周/地图/复盘/待办视图）<br>`PlanRhythm.kt`（结算 + 复盘 + JSONL 导出）<br>`PlanSchema.kt`（内嵌 DDL）、`PlanDatabase.kt`<br>`LearningPlanStore.kt`（对外唯一入口）<br>测试 `src/jvmTest/.../LearningPlanServiceTest.kt` |
| `:features:activity` | `features/activity/src/main/kotlin/dev/trellis/activity/` | 活动采集整理：AW 原始事件（窗口/AFK/浏览器标签页）入库 → 分层分类（大类-小类、颜色、正则优先级、可匹配 URL）→ 日汇总。桌面端由导航第五项「活动」页承载（`TRELLIS_TAB=activity`） | `ActivityTaxonomy.kt`（分类树/规则/默认分类）、`ActivityAggregator.kt`（片段与汇总）、`ActivitySync.kt`、`ActivityStore.kt`、`ActivityTaxonomyStore.kt`；页面 `app/desktop/.../ActivityScreen.kt`；说明 `docs/activity.md` |
| `:app:desktop` | `app/desktop/src/main/kotlin/dev/trellis/app/desktop/` | 桌面应用：装配内核、界面、AI 工具、本地存储、会话 | `Main.kt`（窗口/主题/快捷键）<br>`AppRuntime.kt`（73KB，**装配与状态中心**）<br>`AppScreen.kt`（任务页外壳）<br>`ChatScreen.kt`（会话页 UI）、`ChatStore.kt`（会话落盘）<br>`PluginTools.kt`（AI 插件工具）、`LayoutTools.kt`（布局工具）、`UiStyleTools.kt`（样式/主题工具）、`ScreenTools.kt`（截图/点击/打字）<br>`ThemePacks.kt`（主题校验与应用）、`Palette.kt`（参数化配色）、`LayoutStore.kt`、`UiPrefs.kt`、`StudyPrefs.kt`（`study.json`：每天预计投入）、`SettingsScreen.kt`（设置页）<br>`PlanUiPlugin.kt`（**任务页自身也是插件**）<br>`PlanScreen.kt`（计划页：周卡片/天条/当天安排）、`PlanTools.kt`（AI 建计划工具：`create_learning_plan`）、`PlanPanel.kt`（右侧常驻执行清单）、`PlanCategoryStore.kt`（计划分类）<br>`WorkflowTools.kt`（`set_plan`/`step_done`/`ask_choices`）<br>`BilibiliClient.kt`/`BilibiliTools.kt`（B 站课程检索与分集）、`TaskTools.kt`（`add_tasks`）<br>`PluginUsageStore.kt`（插件页「上次用」）、`FileAuditLedger.kt`（`audit.log` 生命周期审计）<br>`MarkdownText.kt`（对话消息 Markdown 渲染） |
| `:demo` | `demo/src/main/kotlin/dev/trellis/demo/Main.kt` | 控制台演示（无界面，跑 `.\gradlew.bat :demo:run`） | `Main.kt` |

其它目录：

| 路径 | 是什么 |
|---|---|
| `docs/` | 设计与调研文档（入口见 `docs/README.md`） |
| `examples/plugins/` | 三个可直接丢进 `plugins/` 的示例插件（bento 主题 / 看板 / 复杂看板） |
| `trellis-review/`（工作区根，不在仓库里） | 外部 UI 评审稿与交互稿（`修改建议.md`、`配色指导.md`、`会话设计.md`、`*.html`） |
| `.dev/` | 历史临时脚本，忽略 |

---

## 3. 按问题找文件（直接查这张表）

| 我想改… | 去这里 |
|---|---|
| 插件清单字段、依赖、能力声明 | `kernel/src/commonMain/kotlin/dev/trellis/kernel/plugin/PluginManifest.kt` |
| 插件启动/停止/启用/停用/卸载 | `kernel/.../api/Kernel.kt`（`PluginController` + `Kernel.install/disable/enable/remove`） |
| 插件加载器注册（新增一种插件轨道） | `kernel/.../spi/PluginLoader.kt`；装配处 `app/desktop/.../AppRuntime.kt` 的 `create()`（`BuiltinLoader`/`DslLoader`/`ScriptLoader`） |
| 扩展点、贡献、排序、UI 挂载点 | `kernel/.../extension/ExtensionPoint.kt`、`ExtensionRegistry.kt`；挂载点清单 `kernel/.../slot/Slot.kt` |
| 跨插件服务（`ctx.services`） | `kernel/.../extension/ServiceRegistry.kt`（`KernelPoints.Service`）；示例实现 `features/plan-core/.../PlanCorePlugin.kt` |
| 数据提供者（`{{ summary.x }}` 能引用哪些字段） | `kernel/.../extension/DataProvider.kt`；实例 `features/plan-core/.../PlanSummaryDataProvider.kt` |
| 规则/事件/动作（声明式插件的行为） | `kernel/.../rule/Rule.kt`、`kernel/.../event/KernelEvent.kt`、`kernel/.../action/KernelAction.kt` |
| 表达式（`==`/`>`/条件、字符串函数） | `kernel/.../expr/Expression.kt`、`ExpressionParser.kt` |
| UI 节点词汇表（能画什么） | `kernel/.../slot/UiNode.kt`（`Text`/`Grid`/`Stat`/`Table`/`Bars`/`Sparkline`/`Ring`/`Heat`/`Stepper`/`DotMatrix`/`Icon`/`Image`…） |
| UI 节点怎么渲染、样式怎么定 | `ui-kernel/.../DynamicSlotView.kt` |
| 卡片外壳（边框/填充/硬阴影/圆角） | `ui-kernel/.../SlotArea.kt` + `ui-kernel/.../CardStyle.kt`；全局默认来自 `app/desktop/.../AppTheme.kt` |
| 12 栏布局、跨列、按行排列 | `app/desktop/.../LayoutStore.kt`（`layout.json` 读写）+ `ui-kernel/.../SlotArea.kt`（`SlotCell`/weight）+ `AppRuntime.arrangeCards` |
| 界面偏好（配色/密度/导航/字号/卡片） | `app/desktop/.../UiPrefs.kt`（`ui.json`）+ `AppTheme.kt` |
| 主题包（插件贡献一套配色） | `kernel/.../theme/ThemeContribution.kt` + `app/desktop/.../ThemePacks.kt`（校验/派生）+ `ScriptSandbox` 的 `trellis.theme{...}` |
| 参数化配色算法（WCAG 对比度保证） | `app/desktop/.../Palette.kt` |
| 插件资产（图片/图标路径校验） | `app/desktop/.../PluginAssets.kt` + `ui-kernel/.../DynamicSlotView.kt` 的 `LocalPluginImage` |
| AI 的「人设 + 守则」系统提示词 | `ai/.../manager/ManagerPrompt.kt`（`buildBase` / `stateBlock` / `taskModule`） |
| 创建插件用的详细提示词（JSON/DSL/脚本格式） | `ai/.../PromptBuilder.kt` |
| 意图路由（聊天 / 造插件 / 管插件 / 调布局） | `ai/.../manager/Intent.kt`（关键词分类器） |
| 工具循环（多步、重复检测、无步数上限） | `ai/.../tool/ToolAgent.kt` |
| 给 AI 的工具：装/读/停/删插件 | `app/desktop/.../PluginTools.kt` |
| 给 AI 的工具：排版/看卡片 | `app/desktop/.../LayoutTools.kt` |
| 给 AI 的工具：颜色/密度/主题/单卡样式 | `app/desktop/.../UiStyleTools.kt` |
| 给 AI 的工具：截图/描述界面/点击/打字/按键 | `app/desktop/.../ScreenTools.kt` |
| 给 AI 的工具：搜 B 站学习资源、拿分集目录、把分集建成待办 | `app/desktop/.../BilibiliTools.kt`；HTTP/WBI 签名在 `BilibiliClient.kt`（只读元数据，不下载不播放） |
| 会话记录读写 | `app/desktop/.../ChatStore.kt`（`<home>/chat/<id>.json`） |
| 会话页 UI（气泡/过程折叠/三动作/输入条） | `app/desktop/.../ChatScreen.kt` |
| 会话消息 Markdown 解析与渲染 | `app/desktop/.../MarkdownText.kt` |
| 任务数据模型与查询 | `sdk-domain/.../Model.kt`、`PlanService.kt`；实现 `features/plan-core/.../KvPlanService.kt` |
| 学习计划后端（版本/承诺/occurrence/结算/复盘） | `sdk-domain/.../plan/Contract.kt` → `features/learning-process/.../SqliteLearningPlanService.kt`、`PlanCommands.kt`、`PlanQueries.kt`、`PlanRhythm.kt`；语义见 `docs/plan-backend.md` |
| 活动分类规则（大类/小类/颜色/优先级/正则，可匹配标签页 URL） | `features/activity/.../ActivityTaxonomy.kt`（模型/默认分类/`classifier`）；`taxonomy.json` 读写 `ActivityTaxonomyStore.kt`；语义见 `docs/activity.md` |
| 活动原始数据（AW 同步 / 落库 / schema 迁移） | `features/activity/.../ActivitySync.kt`、`ActivityWatchClient.kt`、`ActivityStore.kt`、`ActivitySettings.kt` |
| 日活动汇总与按分类时长 | `features/activity/.../ActivityAggregator.kt`（AFK 扣除、碎片合并、标签页 URL 贴片、层级累计） |
| 活动页 UI（导航第五项「活动」：日视图 / 范围总览 / 片段详情） | `app/desktop/.../ActivityScreen.kt`；引擎与查询在 `AppRuntime`（`ensureActivityEngine` / `syncActivity` / `activityDay` / `activityRange`）；说明 `docs/activity.md` §7 |
| 任务页 UI（任务行/分组/新任务输入/撤销） | `app/desktop/.../AppScreen.kt` + `PlanUiPlugin.kt`（它们已是插件卡片） |
| 计划页 UI（周视图 / 天条 / 当天安排） | `app/desktop/.../PlanScreen.kt` + `AppRuntime.refreshPlan`（数据全来自 `LearningPlanService` 的 view model） |
| 计时器小窗（倒计时 / 正计时 / 结束记入） | `app/desktop/.../FocusTimer.kt`（模型 + 窗口）、`AppRuntime` 的 `timer*` 状态与 `openTimer/stopTimerAndLog`、`Main.kt` 的第二 `Window`；说明见 `docs/focus-timer.md` |
| 自然语言日期（「周三前」「3天后」） | `app/desktop/.../NaturalDates.kt` |
| 插件页 / 诊断页 UI（两页都不在导航里：从设置页进去） | `app/desktop/.../PluginsScreen.kt`、`DiagnosticsScreen.kt` |
| 设置页 UI（主题与导航 / 每日预计投入 / 插件设置分组） | `app/desktop/.../SettingsScreen.kt` + `UiPrefs.kt`（`ui.json`，主题与导航选择器走 `applyTheme` / `updateUiPrefs`）+ `StudyPrefs.kt`（`study.json`）；挂载点 `slot.settings.section` 原先挂在插件页底部，2026-10-28 移到这一页；同一天诊断页也撤出导航，改成这一页底部的「诊断」卡片，点进去，诊断页头部是「← 诊断」（返回箭头在标题左侧，二级页统一走 `Chrome.kt` 的 `ChromeSecondaryHeader`）；插件页也一样撤出导航，改成这一页的「插件」卡片（显示插件数与错误数），插件页头部同样是「← 插件」，导航最终是 任务 / 计划 / 对话 / 活动 / 设置 五条（活动页 2026-10-29 加入） |
| 存储后端（KV → JSON 文件） | `kernel/.../storage/*`；JVM 实现 `app/desktop/.../FileStorageBackend.kt` |
| 时间源 | `kernel/.../time/KernelClock.kt`（禁止直接 `System.currentTimeMillis()`） |
| 权限/审批 | `kernel/.../capability/*`、`kernel/.../approval/*`；桌面弹窗 `app/desktop/.../AskDialogs.kt` |
| 诊断错误码 K001… | `kernel/.../diag/KernelError.kt` |
| 插件自检（装之前跑一遍） | `app/desktop/.../PluginSelfTest.kt`、`ScriptSelfTest.kt` |
| 主题/配色/样式是否合法的测试 | `app/desktop/src/test/.../ThemePacksTest.kt`、`PaletteTest.kt`、`UiPrefsTest.kt` |
| 提示词钉住的测试 | `ai/src/commonTest/kotlin/dev/trellis/ai/PromptCatalogTest.kt`、`PromptLeakageTest.kt`、`manager/ManagerPromptTest.kt` |
| 插件内核契约测试 | `kernel/src/commonTest/kotlin/dev/trellis/kernel/api/*` |

---

## 4. 数据与配置（默认根 `~/.trellis`，可用 `TRELLIS_HOME` 覆盖）

| 路径 | 内容 |
|---|---|
| `store.json` | KV 存储：`plan:*`、`task:*` 以及各插件的 `m:` 命名空间 |
| `plugins/<pluginId>/` | 第三方插件目录（`manifest.json` + `main.dsl.json` 或 `main.js` + 资产） |
| `plugins/.trash/` | 卸载后的回收站（7 天后可彻底清） |
| `ui.json` | 界面偏好 `UiPrefs`（主题 key、配色、密度、导航、字号、卡片样式） |
| `study.json` | 学习节奏偏好 `StudyPrefs`：每天预计投入的分钟数（15–240，默认 60）；建学习计划时 ×7 写进 `process.weekly_capacity_minutes` |
| `layout.json` | 每个挂载点的行/跨栏布局 |
| `plugin-style.json` | 单插件卡片样式覆盖 |
| `chat/<id>.json` | 会话记录（每轮结束后落盘） |
| `llm.json` | LLM endpoint / apiKey / model |
| `app.log` | 运行日志（关窗卡死、插件异常都看这里） |
| `diag/screenshots/` | `take_screenshot` 存的截图 |
| `plans/plan.db` | 学习计划后端（SQLite）：计划版本、承诺、occurrence、事件；备份在 `plans/plan.db.bak-*` |
| `plans/categories.json` | 计划分类（左侧列表分组） |
| `activity/activity.db` | 活动原始事件（SQLite；AW 的窗口/AFK/浏览器标签页事件，schema v2 带 url） |
| `activity/taxonomy.json` | 活动分类树（大类/小类/颜色/正则规则/优先级；读坏会改名成 `.bad-*` 留档） |
| `audit.log` | 生命周期审计（JSONL，装/启用/停用/删/彻底删，保留 500 条；诊断页「最近操作」读它） |
| `plugin-usage.json` | 插件页「上次用 N 分钟前」的依据 |

> 文件都可能带 UTF-8 BOM（PowerShell 写的）。读配置的地方统一做了去 BOM；**你自己写 JSON 时别加 BOM**。

---

## 5. 插件三轨、生命周期与边界

| 轨道 | 目录形态 | 运行方式 | 能做什么 |
|---|---|---|---|
| 内置 `builtin` | 编译进 jar | Kotlin `Plugin` 实现，可贡献 Compose 卡片 | 全部能力（宿主信任它） |
| 声明式 `dsl` | `manifest.json` + `main.dsl.json` | `DslLoader`：JSON → `TemplateSlot`/`TextSlot`/规则 | 静态卡片 + 事件规则；不能写逻辑 |
| 脚本 `script` | `manifest.json` + `main.js` | `ScriptLoader`：Rhino 沙箱，ES5 | 有状态卡片（按钮/输入）、`trellis.register` 渲染 `UiNode`、可贡献主题 |

共同规则（内核强制）：

- `manifest.json` 必填 `id/version/name/loader/sdkRange`（`loader` 省略默认 `dsl`）；
- `contributes` 声明贡献点，注册时内核校验；**脚本的 contributes 由工具从脚本推导后写入**；
- `capabilities` 默认拒绝；危险动作走审批 `ctx.approvals.request(...)`；
- 插件间只通过 `ctx.services` 接口通信，不许互 import；
- 卸载 = 停插件 → 注销贡献 → 目录进回收站；**UI 层只接受数据**，不接受第三方 Composable。

未完成的路线（`docs/roadmap.md`）：原子更新管线 + 快照回滚、数据绑定刷新、插件市场、iOS/Android 落地、`slot.row.trailing` 因缺少行上下文故意不接。

---

## 6. AI 层（这是最近改得最多的部分）

### 6.1 生产路径

```
ChatScreen ──> AppRuntime.sendChat()
                 ├─ router = ManagerRouter(catalog, tools, guidance)
                 ├─ decision = router.decide(request)      ← 意图单选（Ambiguous 就先问，不调模型）
                 ├─ tools    = router.toolsForIntent(intent)  ← 工具单选：聊天给空集
                 ├─ prompt   = router.promptFor(intent, state, history)  ← 按意图拼，前缀缓存各一份
                 └─ ManagerAgent.respond(prompt, decision, request, onDelta, onLog)
                        ├─ Chat      → llm.stream(...) + 剥 DSML/协议壳 + Markdown
                        └─ 其它意图  → ToolAgent.run(...)（DSML 方言 + JSON 双解；无步数上限）
                                  └─ beforeFinishing 仅 CreatePlugin 挂「必须 try_plugin + deploy」
```

固定流程（用户定的，别乱改顺序）：**意图（单选）→ 工具（单选）→ 提示词 → 歧义先问 → 模型 → 工具/流程**。
分层提示词顺序：静态 system → 动态 `<current_state>` → 历史（最近 20 条）→ 按意图的 `<task:...>` → 用户这句话。

### 6.2 工具清单（改工具就改这几个文件）

- `PluginTools`：`list_plugins`、`list_events`、`list_actions`、`read_plugin`、`disable_plugin`、`remove_plugin`、`try_plugin`、`deploy`
- `LayoutTools`：`list_cards`、`arrange_cards`
- `UiStyleTools`：`list_ui_options`、`set_ui`、`set_plugin_card`、`list_themes`、`set_theme`
- `ScreenTools`：`describe_screen`、`take_screenshot`、`open_tab`、`click`、`type_text`、`press_key`
- `WorkflowTools`：`set_plan`（2–12 步）、`step_done`、`ask_choices`（2–5 个竖向选项，调完即停）
- `PlanTools`：`create_learning_plan`（ISO 周不得早于本周，超 12 周由 `PlanRhythm` 分批建）
- `BilibiliTools`：`search_bilibili`、`list_bilibili_parts`（WBI 签名在 `BilibiliClient.kt`，只读元数据；时长 `约 N 分钟` 直接当节点预估）
- `TaskTools`：`add_tasks`（2–12 条，把分集/章节变成用户能勾的待办）

### 6.3 会话

- 存储：`ChatStore` → `<home>/chat/<id>.json`；标题取第一句用户话；启动接回最近一段；
- 上下文：只把正式发言回灌（过程行不传回模型）；
- 流式阶段过滤协议壳（`{"tool"`/`{"done"`/```json 等不显示），结束时 `ManagerAgent.unwrapReply` 剥壳再显示。

---

## 7. 铁律（会被测试或架构断言打回来）

1. **内核不认识领域**：`verifyKernelPurity` 扫描 `kernel` 源码，出现 `TaskStore` 之类就红。
2. **失败必须隔离**：插件崩溃、manifest 写坏都不能影响其它插件。
3. **禁止 `runCatching` 包插件调用**：用 `guard { }`（区分超时与取消）。
4. **时间走 `ctx.clock`**。
5. **风险由能力决定**，不由调用方声明；`Actor.AGENT` 升一级。
6. **隔离做进类型**：接口里不出现 `namespace` 参数。
7. **给 AI 的报错必须能指导修改**（列出全部可用值）。
8. **UI 只交数据**：第三方不能注入 Compose；宿主词汇表封闭（`UiNode`）。
9. **提示词与解码器必须匹配**：提示词宣传的能力，对应 loader/工具真的支持（踩过两次：`script` 字段被丢、`notice` 节点没实现）。
10. **宿主自己的界面也是插件**：任务页 header/board 由 `PlanUiPlugin` 贡献，AI 才排得动、改得了样式。
11. **中文源码/文档只用 read/edit/write 工具改**，别用 PowerShell 回写（会把 UTF-8 读成 ANSI 写回，中文全变问号）。
12. **应用运行时别编译**（见 §1 警告）；改代码 → 关窗口 → 编译 → 再跑。
13. **动手前先划文件范围**：开工第一句写清「这轮只动哪几个文件」，只改列出来的；多会话并行时这是第一步（见 §10）。同时改同一个文件不会有冲突提示，后落盘的直接盖掉先落盘的。
14. **提交前先核对清单**：`git diff --stat` 逐条确认都是自己这轮改的，只 `git add` 具体文件；**不要 `git add -A`、不要 `git commit -a`**（工作区里随时有别人的半成品，`-A` 一定把它带走，见 §10.2）。

---

## 8. 文档索引

先看 `docs/README.md`（每篇文档讲什么、谁是权威、按什么顺序读）。最常用的三篇：

- `docs/kernel-design.md` — 内核设计（85KB，机制与不变量的权威来源）
- `docs/agent-manager.md` — AI 管理员与工具链（分层提示词、工具、界面即插件）
- `docs/roadmap.md` — 已知缺口与优先级（改东西前先看有没有人已经记过这个坑）

---

## 9. 常见任务配方

**加一个挂载点**：`kernel/.../slot/Slot.kt` 定义 → `AppRuntime.RENDERED_MOUNT_POINTS` 加入 → `AppScreen.kt` 对应位置 `SlotArea(...)` → `CapabilitySurfaceTest` 会钉住数量。

**加一个 `UiNode` 节点**：`kernel/.../slot/UiNode.kt` 加 data class → `plugin-script/.../NodeBuilder.kt` 解析 → `ui-kernel/.../DynamicSlotView.kt` 渲染 → `AppRuntime.toPreview()` 摊平 → `PromptBuilder` 词汇表加一行 → `ScriptSandboxTest` 加用例。

**加一个给 AI 的工具**：新建 `*Tools.kt` 实现 `AgentTool`（`name/description/parameters/call`）→ 在 `AppRuntime.sendChat` 里并入 `allTools` 和 `guidance` → 提示词相应 `<task>` 模块补一句。

**改任务数据字段/动作**：`sdk-domain/.../Model.kt` → `features/plan-core/.../KvPlanService.kt` → `PlanSummaryDataProvider.kt`（决定 AI 能否在卡片里引用）→ `PlanActions.kt`（决定 AI 能否改）→ `PlanCoreTest` + `CapabilitySurfaceTest`。

**加一条主题校验规则**：`app/desktop/.../ThemePacks.kt`（token 词表/对比度/派生）→ `ThemePacksTest` → 若是脚本主题，还要同步 `ScriptSandbox` 的 `trellis.theme` 解析。

**跑一次界面截图**：启动应用（`TRELLIS_HOME` 指到演示目录）→ 用 DPI-aware 的 `PrintWindow` 截图（`Add-Type -AssemblyName System.Drawing` → `Bitmap` + `Graphics.GetHdc` + `PrintWindow(hwnd, hdc, 2)`；窗口最小化时先 `ShowWindow(h, 3)`）。

---

## 10. 多会话协作（同一台机器常有多个会话在改这个仓库）

**先划范围，再动手。** 开工第一句就写清「这轮只改这几个文件」，列到具体路径；要越界先说明。这不是流程洁癖：两个会话同时改同一个文件，后落盘的那份会把先落盘的整个盖掉，**不报错、不冲突提示**——等发现时往往已经隔了好几轮。

为什么不能靠分支：分支隔离的是提交历史，**不是磁盘上的文件**。多个会话共用一个工作目录，A 会话 `git checkout` 换分支，B 会话正在编辑的文件会被原地换掉。真并行只能靠 `git worktree`（一个目录一个分支），代价见 §10.4。

### 10.1 开工前

1. **划范围**：写一句「这轮我改这几个文件」，只改列出来的。
2. **看别人干到哪了**：`git status --short` 有未提交改动 = 另一个会话正干到一半，别碰那些文件。
3. **认领大文件**：`AppRuntime.kt`（2000 行，装配与状态中心）、`ChatScreen.kt`、`AppScreen.kt`、`DynamicSlotView.kt` 是最容易被覆盖的四个，同一时刻只让一个会话改其中一个。
   「认领」的动作很轻：在开工说明里写出文件名（如「这轮我改 `AppRuntime.kt` + `SettingsScreen.kt`」），别人看到这句就不碰；没写出名字的文件默认不是你的。
4. **编译前确认没人在跑应用**：`Get-Process java`（见 §1 那条「应用开着别编译」）。

### 10.2 收尾

- **提交前先核对清单**：`git diff --stat` 一条条看，每条都得是自己这轮改的。裹进别人的改动不会报错、不会冲突，但历史会变成「他做的事挂在他的提交消息下」——2026-10-28 就真发生过：一条「计划页加计划设置」的提交里 13 个文件有 8 个是另一个会话的节点改动（`OpenNode.kt`、`PlanNodes.kt`、`PlanNodesTest.kt`、`PlanQueries.kt`、`SqliteLearningPlanService.kt`、`LearningPlanService.kt` 等），事后只能靠读 diff 才能还原归属。
- **只 add 具体文件**，别 `git add -A`、别 `git commit -a`。工作区里随时可能有别人的半成品（尤其 `.kotlin/` 这类构建副产物），`-A` 一律带走：
  ```powershell
  wsl.exe -e bash -lc "cd /mnt/c/Users/breakM/Desktop/work/Trellis && git add path/to/A.kt path/to/B.kt && git status --short"
  ```
- **一个能编译的完整步子提交一次**，别攒一整天。未提交的改动是并行会话最大的风险来源。
- 提交信息写「改了什么 + 为什么」，仓库历史一直是这个风格。
- 合回 `main` 前先 `git rebase main`，冲突在分支里解决，别带到 `main`。

### 10.3 git 只在 WSL 里

这台机器 Windows 侧**没有** `git.exe`（`where.exe git` 是空的），只有 WSL Ubuntu 里的 2.53.0：

```powershell
wsl.exe -e bash -lc "cd /mnt/c/Users/breakM/Desktop/work/Trellis && git status --short"
wsl.exe -e bash -lc "cd /mnt/c/Users/breakM/Desktop/work/Trellis && git add -A && git commit -m '...'"
```

别用 PowerShell 读 `.git/logs/HEAD` 或 `.git/COMMIT_EDITMSG`：那是 UTF-8，PowerShell 默认按 GBK 解，中文提交信息会变成「璁╂ā鍨嬭嚜宸」。要读就走 `wsl git log`。

> 装 Git for Windows（`winget install --id Git.Git`）能让 IDE 的 git 面板和 hook 正常工作；不装也能干活，就是每次多套一层 `wsl.exe`。

### 10.4 真并行时才开 worktree

```powershell
wsl.exe -e bash -lc "cd /mnt/c/Users/breakM/Desktop/work/Trellis && git worktree add ../Trellis-aw -b feat/activity-watch"

# 新目录里开发。必须换 home：两个实例默认都读写 ~/.trellis，会抢同一份
# store.json / plugins/ / chat/*.json / audit.log，互相看到对方装的插件。
$env:TRELLIS_HOME = "C:\Users\breakM\Desktop\work\.trellis-aw"

# 合回 main 后拆掉
wsl.exe -e bash -lc "cd /mnt/c/Users/breakM/Desktop/work/Trellis && git merge feat/activity-watch && git worktree remove ../Trellis-aw"
```

代价：两份 `build/`（各编译一遍）、两个 Gradle daemon 抢内存（`gradle.properties` 只有 `-Xmx2048m`）、合并冲突自己解。40 个提交、4MB 的仓库用不着天天开 worktree。

### 10.5 分工怎么划

按**目录**划比按功能划稳：目录不重叠，文件就不会撞。现成的天然边界：

| 边界 | 里面是什么 | 谁常动 |
| --- | --- | --- |
| `kernel/` | 扩展点、能力、审批、存储接口、错误码 | 改机制才动，平时别碰 |
| `sdk-domain/` + `features/` | 领域契约与适配器（计划、学习进程、活动采集） | 动数据模型和 SQL 的人 |
| `app/desktop/` 的界面外壳 | `AppScreen.kt`、`SettingsScreen.kt`、`PluginsScreen.kt`、`Chrome.kt`、`PixelIcons.kt` | 动导航/页面结构的人 |
| `app/desktop/AppRuntime.kt` | **装配与状态中心，谁都可能碰** | 动之前必须在开工说明里点名，别人看到就等 |
| `ai/` | 提示词、工具、管理员流程 | 动 agent 行为的人 |
| `docs/`、`AGENTS.md`、`README.md` | 文档 | 跟随代码一起提交，别留到最后补 |

两条硬规矩：

1. **同一个文件同一轮只让一个人动**。两个人都要改，就轮流：A 改完提交、B 再动，中间不并行。
2. **跨边界的功能拆不开就别拆**。「计时器接计划节点」要同时动 `app/desktop` 和 `features/learning-process`，这种一次只交给一个会话，别按层拆给两个人——按层拆的结果就是两边都改到 `AppRuntime.kt` 和 `PlanQueries.kt`，最后谁也说不清哪个 hunk 是谁的。
