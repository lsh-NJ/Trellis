# 文档索引（从这里开始读）

> 这个目录里既有**需求/机制**（要遵守），也有**调研结论**（可引用）、**历史记录**（只供追溯）。
> 先看下面的「文档地图」，再按「阅读路径」挑要读的；别从 `kernel-design.md` 第一行硬读到底——它是词典，不是教程。

## 文档地图

| 文件 | 讲什么 | 性质 | 什么时候读 |
|---|---|---|---|
| [`../AGENTS.md`](../AGENTS.md) | **工程入口**：模块 → 源码路径 → 改什么去哪 | 活文档（最该先读） | 任何改动之前 |
| [`../README.md`](../README.md) | 项目简介、构建命令、插件/权限/存储速览、14 条铁律 | 活文档 | 第一次接触项目 |
| [`kernel-design.md`](kernel-design.md) | 内核完整设计：分层、模块边界、抽象定义、9 个挂载点、启动序列、失败隔离、快照回滚、错误码、契约测试、里程碑、反模式 | **机制参考**（v0.1 Draft；部分章节未实现/已偏离，文首有现状提示，冲突时以代码为准） | 改内核 / 需要确认不变量 / 判断某个设计该不该做 |
| [`agent-manager.md`](agent-manager.md) | AI 管理员：分层提示词（缓存友好）、意图路由、工具链、界面即插件、岗位色、点阵与表格、会话不露协议壳、多步任务清单、B 站学习资源工具 | 活文档 | 改 AI 层、加工具、排查「AI 为什么不做/做错」 |
| [`agent-workflow-ui.md`](agent-workflow-ui.md) | Agent 工作流清单的**设计说明**：放哪、数据模型、工具契约、状态机、视觉规格、明确不做什么、已知缺口 | 实现记录（2026-10-26） | 改清单 UI / 讨论 `set_plan` 语义 / 加步骤状态 |
| [`plan-backend.md`](plan-backend.md) | 学习计划后端（事件溯源 + SQLite）：模块分工、与设计稿的差异、命令语义、测试、P3/P4 待接 | 实现记录（2026-10-26） | 改 `:features:learning-process` / 接 UI / 加 plan 工具 |
| [`plan-page.md`](plan-page.md) | 计划页：上半一周一格、下半选中周的那一天；数据全部来自 plan 后端的 view model | 实现记录（2026-10-27） | 改计划页 / 接视图 / 讨论「周视图该显示什么」 |
| [`conversation-redesign.md`](conversation-redesign.md) | 会话页五层信息、三态、三动作、撤销语义 | 实现记录（2026-10-24） | 改会话页 UI / 理解「过程与结论分离」 |
| [`roadmap.md`](roadmap.md) | 已知缺口与优先级（能力面、视觉、插件市场、端） | 活文档 | 动手前先查有没有人记过这个坑 |
| [`eval-prompts.md`](eval-prompts.md) | 验收题库（送检用，与提示词里的示例互为镜像） | 活文档（测试配套） | 调提示词后自测、验收 AI 能力 |
| [`examples/bento-style.md`](examples/bento-style.md) | 样片「Bento / BENTO SHIFT」怎么拆成三层落进 Trellis | 实现记录 | 做主题/看板类插件、理解「样式/数据/外壳」边界 |
| [`plugin-hotplug-path.md`](plugin-hotplug-path.md) | 跨平台 + 插件运行时热插拔的成熟路径调研结论（TL;DR、候选矩阵、推荐路径、落地计划、风险） | **结论冻结**（v1.0，2026-10-22） | 讨论 Android/iOS、热更新、插件市场前 |
| [`research/plugin-hotplug/01-sandbox-runtimes.md`](research/plugin-hotplug/01-sandbox-runtimes.md) | JS/Wasm 沙箱运行时调研（Zipline / quickjs-kt / Chicory / Chasm / Rhino / GraalJS…） | 调研证据 | 选运行时、评估平台矩阵 |
| [`research/plugin-hotplug/02-native-loaders.md`](research/plugin-hotplug/02-native-loaders.md) | 原生插件加载（PF4J / Shadow / ClassLoader、iOS 约束） | 调研证据 | 桌面/Android/iOS 原生插件方案 |
| [`research/plugin-hotplug/03-oss-case-studies.md`](research/plugin-hotplug/03-oss-case-studies.md) | 14 个开源案例（VS Code / JetBrains / Figma / Obsidian / Chrome / Blender / Neovim / Emacs / Godot / Unity / Slack / Cloudflare / Shopify / Istio） | 调研证据 | 找成熟模式（贡献点、激活事件、卸载契约…） |
| [`research/plugin-hotplug/04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md) | 应用级更新（桌面 MSIX/Sparkle、移动商店、Apple PLA、Google Play 政策） | 调研证据 | 做发布/更新/商店合规 |
| [`assets/icons/`](assets/icons/) | 自绘像素风图标 SVG（导航用） | 资产 | 改导航图标 |

## 阅读路径

- **第一次来，只想跑起来**：`../AGENTS.md` §1 → `../README.md` 构建与测试 → 跑 `:app:desktop:run`。
- **要改内核**：`kernel-design.md` §0（怎么用这份文档）→ §2 词汇表 → §3 模块边界 → §4 抽象 → §9 挂载点 → 再动代码；配合 `kernel/src/commonTest/.../api/*` 理解契约。
- **要改 AI 层**：`agent-manager.md` 全文 → `ai/.../manager/ManagerPrompt.kt` 与 `ManagerAgent.kt` → `eval-prompts.md` 自测。
- **要改工作流清单**：`agent-workflow-ui.md`（设计取舍与状态机）→ `WorkflowTools.kt` / `AppRuntime.beginPlan` / `ChatScreen.PlanChecklist`。
- **要改学习计划后端**：`plan-backend.md`（模块分工与语义）→ `sdk-domain/.../plan/Contract.kt` → `SqliteLearningPlanService.kt` + `PlanCommands.kt` → `LearningPlanServiceTest`。
- **要改计划页**：`plan-page.md`（结构、数据来源、已知缺口）→ `PlanScreen.kt` → `AppRuntime.refreshPlan`。
- **要改界面/主题/布局**：`../AGENTS.md` §3「按问题找文件」→ `examples/bento-style.md`（三层边界）→ `conversation-redesign.md`（若改会话页）。
- **要动跨端/热更新**：`plugin-hotplug-path.md` §0 TL;DR → 对应 research 分册；别从零再调研一遍。
- **要发布**：`research/plugin-hotplug/04-app-update-policy.md` + 主文档 §6「热更新两层」。

## 维护约定

1. **活文档**（README、AGENTS、roadmap、agent-manager、eval-prompts）：改代码时顺手改，不要求文采，要求准确。
2. **冻结文档**（plugin-hotplug-path、research/*）：只追加勘误，不改结论；结论变了要新开一份。
3. **实现记录**（conversation-redesign、bento-style）：写「为什么这么定」，不写 API 清单（API 看代码）。
4. 文档里的路径一律写**仓库相对路径**；跨目录引用用相对链接，保证点得开。
5. 大文档查起来慢：用关键词定位（如 `RENDERED_MOUNT_POINTS`、`KernelPoints`、`capability`），不要通读。
