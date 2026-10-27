# Trellis

插件化的学习计划设定与追踪应用。内核零业务概念，功能全是插件；三种插件轨道并存（内置 Kotlin / JSON 声明式 / ES5 脚本）；AI 作为「插件管理员」常驻对话页。

- **工程入口（模块 → 源码路径 → 改什么去哪）**：[AGENTS.md](AGENTS.md)
- **文档索引（每篇文档讲什么、按什么顺序读）**：[docs/README.md](docs/README.md)
- **内核设计**：[docs/kernel-design.md](docs/kernel-design.md)
- **跨平台 + 热插拔调研结论**：[docs/plugin-hotplug-path.md](docs/plugin-hotplug-path.md)

## 现在能做什么

| 能力 | 状态 | 代码位置 |
|---|---|---|
| 模块化内核：插件生命周期、扩展点、能力审批、命名空间存储 | 完成 | `:kernel` |
| 三轨插件：内置 Kotlin / JSON DSL / ES5 脚本（Rhino 沙箱） | 完成 | `:kernel` `:plugin-dsl` `:plugin-script` |
| 任务领域与 `PlanService`（统计、事件、6 个动作） | 完成 | `:sdk-domain` `:features:plan-core` |
| 学习计划后端（事件溯源 + SQLite）：版本/承诺/occurrence、结算结转、复盘 | 后端完成，计划页已接 | `:features:learning-process` |
| UI 渲染层：9 个挂载点、12 栏布局、`UiNode` 词汇表（图表/表格/点阵等） | 完成 | `:ui-kernel` |
| AI 插件管理员：分层提示词、意图路由、多步工具、截图/点击 | 完成 | `:ai` `:app:desktop` |
| 会话系统：落盘恢复、Markdown、过程折叠、撤销 | 完成 | `:app:desktop` |
| 主题包（插件贡献配色、宿主校验对比度）+ 参数化配色 | 完成 | `:app:desktop` `:kernel` |
| 原子更新管线 + 快照回滚 / 数据绑定刷新 / 插件市场 / 移动端 | 未做 | 见 `docs/roadmap.md` |

## 模块

| 模块 | 职责 |
|---|---|
| `:kernel` | 纯 Kotlin 内核，零平台依赖，不认识任何业务概念 |
| `:sdk-domain` | 领域契约：Plan / Task / PlanSummary / PlanService / 排程扩展点 |
| `:features:plan-core` | `PlanService` 的实现（required 内置插件），数据存在 `ctx.kv` |
| `:features:learning-process` | 学习计划后端：事件溯源 + SQLite，事务型命令与视图（契约在 `:sdk-domain` 的 `dev.trellis.domain.plan`） |
| `:plugin-dsl` | 轨道 A：把目录里的 JSON 变成真正跑起来的插件 |
| `:plugin-script` | 轨道 B：ES5 脚本插件（Rhino 沙箱），可带状态、按钮、输入框 |
| `:ui-kernel` | Compose 渲染层：把插件贡献画到界面上 |
| `:ai` | AI 层：提示词、管理器、工具循环、模型客户端 |
| `:app:desktop` | 桌面应用（真实可用的那个）：装配、界面、AI 工具、会话、主题 |
| `:demo` | 控制台演示，跑 `.\gradlew.bat :demo:run` |

依赖方向：`:features:plan-core -> :sdk-domain -> :kernel`，而**内核绝不依赖领域**（`verifyKernelPurity` 守着）。
每个模块的源码根与关键入口文件见 [AGENTS.md](AGENTS.md) §2。

## 构建与测试

需要 JDK 17。Gradle 用 wrapper（已指向国内镜像）。`./run.sh` 会自动定位 JDK。

本机（Windows）JDK 在 `%USERPROFILE%\.jdks\jdk17`：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk17"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

```bash
./run.sh check                      # 全模块测试 + 架构断言 verifyKernelPurity
./run.sh :demo:run                  # 控制台演示
./run.sh verifyKernelPurity         # 只跑「内核不认识领域」断言
```

## 写一个声明式插件

```
plugins/
  ai.review/
    manifest.json      身份、依赖、权限
    main.dsl.json      内容
```

`manifest.json`：

```json
{
  "id": "ai.review",
  "version": "1.0.0",
  "name": "艾宾浩斯复习",
  "capabilities": ["read_data", "notify"]
}
```

`main.dsl.json`：

```json
{
  "slots": [
    { "point": "slot.home.header", "id": "due", "order": 100,
      "title": "待复习", "body": "今天有 12 张卡片到期" }
  ]
}
```

`contributes` **不用手写**  内核从 `main.dsl.json` 推导，所以清单与内容不可能漂移。

## 权限模型

能力**默认拒绝**：没在 `manifest.json` 的 `capabilities` 里声明，就永远拿不到。

插件用两个动词表达自己的意图：

```kotlin
ctx.capabilities.has(Notify)      // 拿不到返回 false  优雅降级
ctx.capabilities.require(Notify)  // 拿不到抛异常  声明硬依赖
```

危险动作走审批门：

```kotlin
val outcome = ctx.approvals.request(DeleteData, "data.delete", "删除 3 条今日记录")
when (outcome) {
    is ApprovalOutcome.Approved -> doIt()
    is ApprovalOutcome.Denied   -> return
}
```

风险由**能力的危险等级**决定，不由调用方声明；`Actor.AGENT` 发起的动作再升一级。
没有可用的确认界面时，危险动作**默认被拒绝**。

## 存储

插件的数据抽屉是 `ctx.kv`，它已经绑死了本插件的命名空间  **接口里没有命名空间参数，
所以「读别人的数据」在类型层面就写不出来**，而不是靠字符串前缀。

```kotlin
ctx.kv.put("draft", "...")
ctx.kv.get("draft")
ctx.kv.keys("note:")
```

卸载时数据**默认保留**（重装可恢复），过了 7 天由 `purgeExpiredData()` 回收；
想立刻清除就 `uninstall(id, purgeData = true)`。

## 领域层

第三方插件**不直接读存储**，只通过 `PlanService` 读写：

```kotlin
val plan = ctx.services.require(PlanService::class)
val due = plan.tasks(TaskQuery(dueBefore = tomorrow))
val summary = plan.summary(now, dayEnd)
```

所以将来把底层的 KV 换成 SQLite，所有调用方一行都不用改。

## 跑桌面应用

入口脚本都在**仓库根目录**（和 `gradlew`、`README.md` 同级）。选哪个取决于你在哪个 shell 里：

| 你在哪 | 命令 | 说明 |
|---|---|---|
| Windows PowerShell | `.\run-app.cmd` | 推荐。编译并启动；也可以直接双击 `run-app.cmd` |
| Windows PowerShell（纯 ps1） | `.\run-app.ps1` | 同上，PowerShell 原生写法；`.\run-app.ps1 --check` 只编译 |
| Windows cmd | `run-app.cmd` | 同上 |
| Git Bash / WSL bash | `./run-app.sh` | bash 写法；`./run-app.sh --check` 只编译 |
| 任意 shell | `.\gradlew.bat :app:desktop:run` | 已经配好 `JAVA_HOME` 时最直接 |

**`.sh` 不要交给 Windows 直接执行。** 在 PowerShell 里敲 `run-app.sh`（或双击它），

Windows 会按文件关联用编辑器打开它（你机器上是 VSCode）——这不是脚本坏了，
是 `.sh` 没有「可执行」的关联。要执行它，得先有个 bash：

```powershell
bash run-app.sh        # Git Bash
wsl ./run-app.sh       # WSL（需要 WSLg）
```

同理，bash 里不要敲 `run-app.cmd`：那是 Windows 的批次档。`run.sh` 是给 CI/本地检查用的
Gradle 包装（`./run.sh check`），不启动窗口。

三个启动脚本干的事一样：找到 JDK（`JAVA_HOME` → `%USERPROFILE%\.jdks\jdk17`／
`~/.dev/tools/jdk-*` → PATH 里的 `java`），切到仓库根，再调
`:app:desktop:run`。`run-app.sh` 额外处理 WSLg：没有硬件 OpenGL，会把
`SKIKO_RENDER_API` 设成 `SOFTWARE_FAST`，并把 `DISPLAY`/`WAYLAND_DISPLAY`/`XDG_RUNTIME_DIR`
补齐。

Windows 本机（JDK 在 `%USERPROFILE%\.jdks\jdk17`），不想要脚本也可以：

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk17"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :app:desktop:run
```

数据落在 `~/.trellis/store.json`，插件目录是 `~/.trellis/plugins/` ——
往里面丢插件（`manifest.json` + `main.dsl.json`，或脚本插件的 `manifest.json` + `main.js`），重启应用就能看到新卡片。

> WSL 下需要 WSLg（`C:\Users\<你>\.wslconfig` 里 `guiApplications=true`）+
> `SKIKO_RENDER_API=SOFTWARE_FAST`（`run-app.sh` 会自动设）。
> 没有图形环境时，`./run.sh :app:desktop:test` 里的 `UiRenderTest` 会把界面离屏渲染成 PNG。

> **应用开着的时候别重新编译。** `./gradlew :app:desktop:run` 的 classpath 是
> `build/classes/` 目录，编译会原地重写它；已经跑起来的进程要到第一次用到某个类
> （典型是点关闭时的 lambda）才会去加载，这时可能抛
> `NoClassDefFoundError: MainKt$main$1$1$1$1`，于是窗口关不掉、进程也退不出，
> `~/.trellis/app.log` 里会留着 `ClassNotFoundException`。要改代码就先关窗口再编译。
> `Main.kt` 已经把这个坑兜住了（关闭回调不会因为异常阻止退出，另外 `Ctrl+Q` 永远能退），
> 但「边跑边编译」还是会让你看到旧行为、白debug一轮。

## 铁律

1. **内核不认识领域。** `verifyKernelPurity` 在构建时挡住 `TaskStore` 这类东西（扫代码、跳过注释）。
2. **失败必须隔离。** 一个插件崩溃、一个 manifest 写坏，都不影响其他插件。
3. **禁止 `runCatching` 包插件调用。** 用 `guard { }`，它区分「超时」与「协程被取消」。
4. **时间必须走 `ctx.clock`。** 禁止 `System.currentTimeMillis()`。
5. **贡献点的注销由内核强制完成。** 插件怎么写都不影响「卸载无残留」。
6. **报错要能指导修改。** 未知挂载点要列出全部可用值；能力要同时给标题和机器可读 id  Agent 只拿得到报错文本。
7. **接口拆分时旧方法当场删。** 留着的死代码只会在半年后变成一次莫名其妙的编译错误。
8. **插件之间只通过接口交换实现。** 用 `ctx.services.publish` / `require`，不许互相 import 具体的类。
9. **并发测试必须验证过「去掉保护会红」。** 否则它只是长相像测试的注释。
10. **风险由能力决定，不由调用方声明。** 让插件自证「我很安全」是没有意义的。
11. **隔离要做进类型，不能做在字符串上。** 插件的接口里出现 `namespace` 参数，隔离就已经漏了。
12. **发现和启用是两个维度。** 混在一起，「装一个还没启用的插件」就永远做不到。
13. **同一个库可以配出相反的策略。** 判断依据是失败模式：作者写错要炸，版本错位要忍。
14. **不要给第三方插件 composable 的注入权。** Compose 装不下第三方代码的异常边界 
    第三方只能提交**数据**，由宿主渲染。
