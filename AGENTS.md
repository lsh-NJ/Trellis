# AGENTS.md — Trellis 工程地图（给大模型/新同学的入口）

> 这份文件只做一件事：**让你在 10 分钟内知道这个项目是什么、代码在哪、改一件事该动哪个文件。**
> 细节不在这一份里——每个主题都有对应文档，见文末索引。
> 最后更新：2026-10-26。若与代码冲突，以代码为准，并顺手把这份文件改对。

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
Set-Location C:\Users\breakM\Desktop\work\CreateandTrack

.\gradlew.bat :app:desktop:run     # 跑桌面应用
.\gradlew.bat test                 # 全模块测试（kernel / plan-core / dsl / script / ai / desktop）
.\gradlew.bat check                # 测试 + 架构断言 verifyKernelPurity
```

> **跑应用前先确认没有旧实例在跑**：`Get-Process java | Stop-Process -Force`。
> **应用开着的时候别编译**：`:app:desktop:run` 的 classpath 是 `build/classes/` 目录，编译会原地重写；
> 已启动的进程用到旧 lambda 时会抛 `NoClassDefFoundError`，窗口关不掉（详见 README 的「铁律」段）。

调试/演示用环境变量：

| 变量 | 作用 |
|---|---|
| `TRELLIS_HOME` | 数据根目录，默认 `~/.trellis`；做演示/截图时指到临时目录，别动真实数据 |
| `TRELLIS_TAB` | 启动落在哪一页：`chat` / `plugins` / `diag` |
| `TRELLIS_LLM_KEY` / `TRELLIS_LLM_MODEL` / `TRELLIS_LLM_ENDPOINT` | 覆盖 LLM 配置（也可写 `~/.trellis/llm.json`） |

> 仓库里的 `.dev/` 是历史会话留下的几百个临时脚本，**不是**构建脚本，别当入口读。

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
| `:ai` | `ai/src/commonMain/kotlin/dev/trellis/ai/` | AI 层：提示词、管理器、工具循环、会话无关的纯逻辑 | `manager/ManagerAgent.kt`、`manager/ManagerPrompt.kt`、`manager/Intent.kt`<br>`PromptBuilder.kt`（30KB，创建插件用的系统提示词）<br>`tool/ToolAgent.kt`、`tool/AgentTool.kt`<br>`flow/AgentFlow.kt`（draft→check→ship 备用链路）<br>`PluginAgent.kt`（单发生成，DSL 专用）<br>`LlmClient.kt`；JVM 实现 `ai/src/jvmMain/kotlin/dev/trellis/ai/HttpLlmClient.kt` |
| `:app:desktop` | `app/desktop/src/main/kotlin/dev/trellis/app/desktop/` | 桌面应用：装配内核、界面、AI 工具、本地存储、会话 | `Main.kt`（窗口/主题/快捷键）<br>`AppRuntime.kt`（73KB，**装配与状态中心**）<br>`AppScreen.kt`（任务页外壳）<br>`ChatScreen.kt`（会话页 UI）、`ChatStore.kt`（会话落盘）<br>`PluginTools.kt`（AI 插件工具）、`LayoutTools.kt`（布局工具）、`UiStyleTools.kt`（样式/主题工具）、`ScreenTools.kt`（截图/点击/打字）<br>`ThemePacks.kt`（主题校验与应用）、`Palette.kt`（参数化配色）、`LayoutStore.kt`、`UiPrefs.kt`<br>`PlanUiPlugin.kt`（**任务页自身也是插件**）<br>`MarkdownText.kt`（对话消息 Markdown 渲染） |
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
| 会话记录读写 | `app/desktop/.../ChatStore.kt`（`<home>/chat/<id>.json`） |
| 会话页 UI（气泡/过程折叠/三动作/输入条） | `app/desktop/.../ChatScreen.kt` |
| 会话消息 Markdown 解析与渲染 | `app/desktop/.../MarkdownText.kt` |
| 任务数据模型与查询 | `sdk-domain/.../Model.kt`、`PlanService.kt`；实现 `features/plan-core/.../KvPlanService.kt` |
| 任务页 UI（任务行/分组/新任务输入/撤销） | `app/desktop/.../AppScreen.kt` + `PlanUiPlugin.kt`（它们已是插件卡片） |
| 自然语言日期（「周三前」「3天后」） | `app/desktop/.../NaturalDates.kt` |
| 插件页 / 诊断页 UI | `app/desktop/.../PluginsScreen.kt`、`DiagnosticsScreen.kt` |
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
| `layout.json` | 每个挂载点的行/跨栏布局 |
| `plugin-style.json` | 单插件卡片样式覆盖 |
| `chat/<id>.json` | 会话记录（每轮结束后落盘） |
| `llm.json` | LLM endpoint / apiKey / model |
| `app.log` | 运行日志（关窗卡死、插件异常都看这里） |
| `diag/screenshots/` | `take_screenshot` 存的截图 |

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
                 ├─ llm = HttpLlmClient(llm.json)   （stream 走 SSE）
                 ├─ tools = PluginTools + LayoutTools + UiStyleTools + ScreenTools
                 ├─ ManagerPrompt.buildBase(catalog, allTools, guidance)   ← 静态前缀（缓存命中）
                 └─ ManagerAgent.respond(state, request, history, onDelta, onLog)
                        ├─ intent = KeywordIntentClassifier.classify(request)
                        ├─ Chat          → llm.stream(...) + 剥协议壳 + Markdown
                        └─ 其它意图      → ToolAgent.run(messages, onLog)（无步数上限，重复检测兜底）
                                  └─ beforeFinishing 仅 CreatePlugin 挂「必须 try_plugin + deploy」
```

分层提示词顺序（**别乱动**，动了前缀缓存就没了）：静态 system → 动态 `<current_state>` → 历史（最近 20 条）→ 按意图的 `<task:...>` → 用户这句话。

### 6.2 工具清单（改工具就改这几个文件）

- `PluginTools`：`list_plugins`、`list_events`、`list_actions`、`read_plugin`、`disable_plugin`、`remove_plugin`、`try_plugin`、`deploy`
- `LayoutTools`：`list_cards`、`arrange_cards`
- `UiStyleTools`：`list_ui_options`、`set_ui`、`set_plugin_card`、`list_themes`、`set_theme`
- `ScreenTools`：`describe_screen`、`take_screenshot`、`open_tab`、`click`、`type_text`、`press_key`

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

**跑一次界面截图**：启动应用（`TRELLIS_HOME` 指到演示目录）→ 用 DPI-aware 的 `PrintWindow` 截图；本项目历史上做过，脚本思路在 `.dev/shot2.sh`（仅供参考，不要直接依赖）。
