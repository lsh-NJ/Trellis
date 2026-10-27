# Trellis  内核设计文档

> 版本 v0.1 Draft ｜ 读者 实现者本人
>
> 标记约定：`[开工]` 可立即动工 ｜ `[拍板]` 需你决策 ｜ `[锁]` 不变量，破坏即架构失守

> **现状提示（2026-10-26）**：这是**设计稿**，不是实现说明书。文中部分章节仍未实现或已偏离代码：
> 挂载点实际渲染 6 个（`row.trailing` 故意不接）、插件已有第三条 `script` 轨道、已新增 `kernel.theme` 主题扩展点、
> 领域层已有 6 个动作；另有一批 2026-10-22 的能力面改动。**以代码和 [`../AGENTS.md`](../AGENTS.md) 为准**，
> 遇到矛盾时优先相信代码，并顺手在本文标注「已偏离」。

---

## 0. 怎么用这份文档

- **不要按章节顺序实现。** 15 给了实现顺序，按那个走。
- 2 建立词汇表，3 定模块边界。这两节看完就有全局。
- 4 是抽象定义（照着抄接口），59 是机制，每节末尾的「验收」都要变成自动化测试。
- 标 `[拍板]` 的地方我给了推荐选项和理由，你只需确认或推翻。

---

## 1. 设计目标与非目标

### 1.1 从需求到内核的映射

原始需求里的每一条，在内核里都对应一个机制。这张表是本文档的可追溯性：

| 原始需求 | 内核提供什么 | 章节 |
|---|---|---|
| 待办 / 日历 / 可选管理模式 | 内核不认识任务。提供扩展点 + Slot，业务落在 plan-core 插件 | 5 |
| 任务进行前的提醒 | Scheduler（幂等 key、可持久化）+ Capability: notify | 6 8 |
| 期间可选对时间追踪 | Capability: background；追踪逻辑在插件里 | 6 |
| 任务结束可选总结 | EventBus + Slot（总结卡片） | 5 8 |
| 简要数据统计 | Metric 扩展点；内核只做聚合与渲染挂载 | 5 |
| Agent 对程序权限的全面调用 | Capability + 审批流（自动审查 / 手动批准）+ 审计日志 | 6 12 |
| Agent 以创建插件的方式扩展程序 | Loader SPI（轨道 A/B/C）+ 安装流水线 + Manifest 校验 | 9 10 |
| 对程序修改有健全的运行保障 | 快照回滚 + 安全模式 + 失败隔离 + 契约测试 | 11 12 14 |
| Agent 需要 RAG 系统 | 内核不感知。RAG 是 ai.rag 插件，通过 PlanService 取数 | 1.3 N5 |

### 1.2 目标（可观测的判据）

| 编号 | 目标 | 判据 |
|---|---|---|
| G1 | 零插件可启动 | 插件目录为空时 boot() 返回 ACTIVE，UI 显示空状态 |
| G2 | 一切皆插件 | 任务看板、番茄钟、AI 拆解全部走同一条加载路径 |
| G3 | 热装 / 热卸 / 回滚 | 装插件不用重启进程；卸载后无残留贡献点 |
| G4 | 契约版本化 | 插件声明 sdkRange，不匹配时给可读错误而非崩溃 |
| G5 | 跨端一致 | :kernel 100% 在 commonMain，Android 与桌面共享全部内核逻辑 |
| G6 | 可测试 | 内核无 UI、无平台依赖，可注入 Fake 时钟与内存存储 |
| G7 | 可观测 | 每个插件的状态 / 耗时 / 错误在一个面板里看到 |

### 1.3 非目标（这一版明确不做）

- **N1 进程级沙箱。** v1 用进程内隔离 + 超时 + 配额，不做独立进程。
- **N2 内核热更新。** 内核随 App 版本走，永不变更。
- **N3 JPMS / OSGi。** 会和 Compose Desktop 的 jpackage 打包打架。
- **N4 iOS 原生插件。** iOS 只能跑声明式插件（轨道 A），这是平台限制而非设计缺陷。
- **N5 内核不认识 AI / RAG / 教育。** RAG 是插件，内核只提供存储、事件、调度、能力。

### 1.4 三条设计原则

1. **内核小、契约稳、插件多。** 内核不超过总代码量 15%。想加功能？加扩展点，不要加内核代码。
2. **一切能力默认拒绝。** 包括文件、网络、通知、后台、时间精度。
3. **所有破坏性动作都要可回滚。** Agent 发起的尤其如此。

---

## 2. 分层与名词表

### 2.1 分层

```
+-------------------------------------------------------------+
|  Agent（外部进程 / 服务）只有 SDK 视野，永远没有内核视野          |
+----------------------------+--------------------------------+
                             | 生成 / 调用
+----------------------------v--------------------------------+
|  L3 插件层                                                    |
|     轨道 A 声明式 ｜ 轨道 B 沙箱(JS/Wasm) ｜ 轨道 C 原生          |
+-------------------------------------------------------------+
|  L2 标准插件  plan-core / task-board / timer / ai.rag ...     |
|     你们发，但走与其他插件完全相同的加载路径                       |
+-------------------------------------------------------------+
|  L1 契约层  :sdk-api（容器契约）+ :sdk-domain（领域词汇）          |
|     只增不改 = 插件的「系统调用」                                |
+-------------------------------------------------------------+
|  L0 内核层  :kernel + :platform-android / :platform-desktop   |
|     [锁] 只读，Agent 的禁区                                     |
+-------------------------------------------------------------+
```

### 2.2 名词表

| 名词 | 定义 | 一句话记法 |
|---|---|---|
| Kernel 内核 | 零插件可启动的最小特权代码 | 关掉一切还能开机 |
| SDK | 插件能看见的唯一接口面 | 内核的实现  对外的契约 |
| Plugin 插件 | 一个带 manifest 的能力包 | 一切功能的最小单位 |
| Manifest | 插件的身份 / 依赖 / 权限 / 贡献声明，运行时不可变 | 插件的身份证 |
| Contribution | 插件向某个扩展点提交的一个实现 | 「我提供这个」 |
| ExtensionPoint 扩展点 | 内核定义的类型安全挂载位 | 「谁能插这里」 |
| Slot | 扩展点的 UI 特化（一段 Composable） | 界面上的插座 |
| Capability 能力 | 特权能力标识，默认拒绝 | 权限，但归内核管 |
| Loader | 把 manifest 变成 Plugin 实例的机制 | 轨道 A/B/C 都是它 |
| PluginScope | 插件独占的协程作用域 + DI 容器 | 插件的一生所系 |
| Profile | 当前启用插件集 + 版本 + 顺序 + 配置哈希 | 可回滚的软件形态 |
| Snapshot 快照 | Profile 的一次物理副本 | 后悔药 |
| Profile | 见上 |  |

---

## 3. 模块与依赖规则

### 3.1 模块清单 [开工]

```
:kernel                 纯 Kotlin，零平台依赖，KMP commonMain
:sdk-api                插件容器契约（Plugin / Manifest / Context / ExtensionPoint / Capability）
:sdk-domain             领域词汇：Plan / Task / Goal / Session / Tag + 服务接口
:ui-kernel              设计系统 + Slot 渲染器 + ErrorBoundary
:platform-android       Loader 实现、Capability 实现、OS 组件（通知、前台服务）
:platform-desktop       Loader 实现、Capability 实现、窗口 / 托盘
:features:plan-core     required 内置插件，实现 PlanService
:features:task-board    内置插件：待办 / 日历 / 管理模式
:features:timer         内置插件：时间追踪
:app:android
:app:desktop
```

### 3.2 依赖规则 [锁]

```
:app:*  ->  :platform-*  ->  :features:*  ->  :sdk-api  ->  :kernel
                                                 ^
                                           :sdk-domain
```

四条硬规则，用 Konsist 在 CI 里断言：

1. `:kernel` 不得依赖 `:sdk-domain`、任何 `:features:*`、任何 `:platform-*`。
2. 依赖方向单向，不允许反向或循环。
3. `:kernel` 源码中不得出现 `Plan`、`Task`、`Goal`、`AI`、`LLM`、`Education` 这些字样这是「内核不认识教育」的机器可验证形式。
4. 插件之间不得直接依赖彼此的模块，只能依赖 `:sdk-api` / `:sdk-domain`。

### 3.3 :kernel 包结构

```
kernel/
  api/          Kernel, KernelConfig, KernelState            公开门面
  plugin/       Plugin, PluginManifest, PluginContext, PluginState, PluginId, PluginScope
  registry/     PluginRegistry, DependencyResolver, BootSequence
  extension/    ExtensionPoint, ExtensionRegistry, Contribution
  slot/         SlotIds, SlotContribution                     （仅契约，渲染在 :ui-kernel）
  capability/   CapabilityId, CapabilityAccess, GrantStore, ApprovalGate
  storage/      KvStore, DbAccess, MigrationRunner
  event/        EventBus, KernelEvent
  scheduler/    KernelClock, Scheduler
  policy/       Quota, AuditLog
  diag/         Diagnostics, PluginReport, KernelError
  spi/          PluginLoader, PlatformBridge                   <- 平台实现
  internal/     其余全部，internal 可见性
```

**验收**：`./gradlew :kernel:check` 通过，Konsist 四条断言全绿。
---

## 4. 核心抽象

> 本节的接口定义可以直接抄进代码。它们是整个系统的形状。

### 4.1 身份与版本

```kotlin
@JvmInline value class PluginId(val value: String)          // 反向域名，如 ai.ebbinghaus

@JvmInline value class ExtensionPointId(val value: String)  // 如 plan.schedule.strategy

@JvmInline value class CapabilityId(val value: String)      // 如 write_task

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer>

class VersionRange private constructor(private val pred: (SemVer) -> Boolean) {
    operator fun contains(v: SemVer): Boolean = pred(v)
    companion object {
        fun parse(expr: String): VersionRange    // 支持 ">=1.0.0 <2.0.0"
    }
}
```

### 4.2 PluginManifest

```kotlin
data class PluginManifest(
    val id: PluginId,
    val version: SemVer,
    val name: String,
    val sdkRange: VersionRange,
    val loader: String,                                  // builtin / dsl / zipline / wasm / shadow / pf4j
    val dependsOn: List<PluginRequirement> = emptyList(),
    val capabilities: Set<CapabilityId> = emptySet(),
    val contributes: Map<ExtensionPointId, List<ContributionDecl>> = emptyMap(),
    val entry: String? = null,                           // dsl: 相对路径；code: FQN
    val migrations: List<MigrationDecl> = emptyList(),
    val builtin: Boolean = false,
    val required: Boolean = false,                       // required 不可卸载，缺失时内核进降级态
    val manifestHash: String = "",                      // [锁] 安装时落库冻结
)

data class PluginRequirement(
    val id: PluginId,
    val range: VersionRange,
    val optional: Boolean = false,
)

data class ContributionDecl(
    val id: String,
    val order: Int = 100,
    val payload: String? = null,
)

data class MigrationDecl(val version: Int, val sql: String)
```

### 4.3 Plugin 生命周期接口

```kotlin
interface Plugin {
    val manifest: PluginManifest

    /** 注册 contributions、准备资源。禁止在这里启动后台任务。 */
    suspend fun init(ctx: PluginContext)

    /** 开始干活：订阅事件、启动定时、申请能力。 */
    suspend fun start() {}

    /** 停止干活，保留资源（可能被重新 start）。 */
    suspend fun stop() {}

    /** 释放一切：取消协程、关句柄、注销贡献点。 */
    suspend fun dispose() {}
}
```

### 4.4 PluginContext  插件的「系统调用表」

> 这是整份文档**最重要**的接口。插件能碰到的世界，全部由它定义。
> 想让 Agent 有更多自由？往这里加方法，绝不放开别处。

```kotlin
interface PluginContext {
    val id: PluginId
    val scope: CoroutineScope          // SupervisorJob + 内核注册的异常处理
    val clock: KernelClock             // [锁] 禁止直接用 System.currentTimeMillis()
    val events: EventBus
    val kv: KvStore                    // 已按插件命名空间隔离
    val db: DbAccess                   // 已按插件前缀隔离
    val scheduler: Scheduler
    val capabilities: CapabilityAccess
    val logger: PluginLogger
    val extensions: ExtensionRegistrar // [锁] 仅在 INITIALIZED 阶段可用，之后调用抛异常
    val services: ServiceAccess        // 跨插件通信（见 5.4）
}
```

内核自身**不使用**这个接口，它是专为插件准备的窄门。

### 4.5 插件状态机

```
                    +------------+
                    | DISCOVERED |  manifest 已解析
                    +-----+------+
                          |  依赖解析 + sdkRange 校验通过
                    +-----v------+
                    |  RESOLVED  |
                    +-----+------+
                          |  loader.load() 成功
                    +-----v------+
                    |   LOADED   |
                    +-----+------+
                          |  init() 完成，贡献已登记但未生效
                    +-----v------+
                    |INITIALIZED |
                    +-----+------+
                          |  start() 完成
                    +-----v------+   start()
                    |   ACTIVE   |<------------+
                    +-----+------+             |
                          |  stop()      +-----+-----+
                    +-----v------+       |  STOPPED  |
                    |  DISPOSED  |<------+-----------+
                    +------------+  dispose()

  任意阶段异常        -> FAILED      -> DISPOSED
  依赖未就绪          -> SKIPPED     -> DISPOSED
  Loader 不支持本平台 -> UNAVAILABLE （不报错，UI 显示「此设备不可用」）
```

**合法迁移表**（内核在 KernelDispatcher 上串行执行；非法迁移抛 `IllegalStateTransition`）：

| 当前状态 | 允许迁移到 | 触发者 |
|---|---|---|
| DISCOVERED | RESOLVED / SKIPPED / FAILED | Resolver |
| RESOLVED | LOADED / UNAVAILABLE / FAILED | Loader |
| LOADED | INITIALIZED / FAILED | Kernel |
| INITIALIZED | ACTIVE / FAILED | Kernel |
| ACTIVE | STOPPED / FAILED | Kernel / 用户 |
| STOPPED | ACTIVE / DISPOSED | Kernel |
| 任意 | DISPOSED | 卸载流程 |

> 实现提示：把这张表写成 `Map<PluginState, Set<PluginState>>`，迁移前先查表，非法就抛。
> 这是内核里性价比最高的 20 行代码。

### 4.6 PluginRegistry 与 DependencyResolver

```kotlin
interface PluginRegistry {
    val states: StateFlow<Map<PluginId, PluginState>>
    suspend fun install(source: PluginSource): InstallResult
    suspend fun uninstall(id: PluginId): UninstallResult
    suspend fun enable(id: PluginId)
    suspend fun disable(id: PluginId)
    fun manifest(id: PluginId): PluginManifest?
}

object DependencyResolver {
    /** Kahn 拓扑排序 + 环检测 + 版本区间校验 */
    fun resolve(all: List<PluginManifest>, sdkVersion: SemVer): ResolveResult

    sealed interface ResolveResult {
        data class Ok(val order: List<PluginId>) : ResolveResult
        data class Failed(val errors: List<KernelError>) : ResolveResult
    }
}
```

**解析规则：**

1. 缺失的**非 optional** 依赖 -> 该插件 SKIPPED，并对其**依赖闭包**传播（上游也 SKIPPED）。落 `K002`。
2. 存在环 -> 环上所有插件 SKIPPED，错误信息里打印环路径。落 `K003`。
3. `sdkRange` 不满足 -> 不加载，明确报错。**不许静默忽略。** 落 `K001`。
4. 不依赖失败者的插件照常启动。`[锁]`
5. optional 依赖缺失 -> 正常加载，只是 `ctx.services.find()` 返回 null。

**验收**：Resolver 独立单测，覆盖菱形依赖、自环、二元环、版本不匹配、optional 缺失五种情况。

### 4.7 Kernel 门面

```kotlin
class Kernel private constructor(/* ... */) {
    companion object {
        suspend fun boot(config: KernelConfig): Kernel
    }

    val state: StateFlow<KernelState>
    val registry: PluginRegistry
    val extensions: ExtensionRegistry
    val diagnostics: Diagnostics

    suspend fun install(source: PluginSource): InstallResult
    suspend fun shutdown()
}

data class KernelConfig(
    val sdkVersion: SemVer,
    val pluginsDir: String,        // 插件目录
    val dataDir: String,
    val snapshotDir: String,
    val loaders: List<PluginLoader>,
    val clock: KernelClock = SystemClock,
    val bootMode: BootMode = BootMode.NORMAL,   // NORMAL / SAFE
)
```

### 4.8 内核不变量 [锁]

| 编号 | 不变量 |
|---|---|
| I1 | 零插件启动：插件目录为空时 `boot()` 仍返回 ACTIVE |
| I2 | 内核不认识领域：`:kernel` 编译期不依赖 `:sdk-domain` |
| I3 | 单线程变更：所有注册表变更在 KernelDispatcher 串行执行 |
| I4 | 无全局可变状态：插件只能经 `PluginContext` 拿东西 |
| I5 | 失败不传播：一个插件 FAILED 不影响不依赖它的插件 |
| I6 | 卸载无残留：dispose 后 contribution / slot / 协程 / 监听器全部消失 |
| I7 | 契约只增：`:sdk-api` 不允许破坏性变更，除非 kernel MAJOR +1 |
| I8 | 不可自我提权：运行时 manifest 不可变，capabilities 由 GrantStore 决定 |

---

## 5. 扩展点与 Slot

### 5.1 类型安全的扩展点

```kotlin
class ExtensionPoint<T : Any>(
    val id: ExtensionPointId,
    val contract: KClass<T>,
    val cardinality: Cardinality,   // ONE：只许一个实现，冲突即报错
)                                   // MANY：多个实现，按 order 排序
```

扩展点是**坐标，不是插件能随便发明的**。内核与 SDK 预先声明全部：

```kotlin
object KernelPoints {
    val Service = ExtensionPoint<Any>("kernel.service", Any::class, ONE)
    val Slot    = ExtensionPoint<SlotContribution>("kernel.slot", SlotContribution::class, MANY)
}

object PlanPoints {   // 定义在 :sdk-domain
    val ScheduleStrategy = ExtensionPoint<ScheduleStrategy>("plan.schedule.strategy", ..., MANY)
    val ReviewModel      = ExtensionPoint<ReviewModel>("plan.review.model", ..., MANY)
    val Card             = ExtensionPoint<CardRenderer>("plan.card", ..., MANY)
    val View             = ExtensionPoint<ViewDefinition>("plan.view", ..., MANY)
    val ImportSource     = ExtensionPoint<ImportSource>("plan.import.source", ..., MANY)
    val Metric           = ExtensionPoint<MetricProvider>("plan.metric", ..., MANY)
    val AgentTool        = ExtensionPoint<AgentTool>("agent.tool", ..., MANY)
}
```

插件侧注册：

```kotlin
override suspend fun init(ctx: PluginContext) {
    ctx.extensions.register(PlanPoints.ReviewModel, EbbinghausModel())
    ctx.extensions.register(KernelPoints.Slot, PlanDetailAsideCard())
}
```

内核侧消费：

```kotlin
val models: List<ReviewModel> = extensions.all(PlanPoints.ReviewModel)
val only: ReviewModel?        = extensions.first(PlanPoints.ReviewModel)
```

**三条铁律：**

1. `[锁]` 插件之间**不许互相 import 类**。要通信就走 `KernelPoints.Service`（见 5.4）。
2. ONE 基数的扩展点被两个插件同时注册 -> 报 `K007`，**不许静默覆盖**。
3. `all()` 返回渲染快照，渲染期间不加锁。

### 5.2 Slot  UI 上的插座

```kotlin
interface SlotContribution {
    val id: String
    val order: Int get() = 100
    @Composable fun Content(host: SlotHost)
}

data class SlotHost(
    val pointId: ExtensionPointId,
    val args: Map<String, Any?> = emptyMap(),
)
```

v1 挂载点清单：

```
home.header          home.section         home.empty
plan.detail.aside    plan.detail.tab      plan.detail.overlay
task.row.trailing    task.detail.panel
calendar.cell.longpress
settings.section     report.chart         app.overlay
```

使用方式：

```kotlin
Slot(pointId = SlotIds.PlanDetailAside, args = mapOf("planId" to plan.id))
```

**渲染器必须包 ErrorBoundary：**

```
Slot 渲染
  -> 遍历 contributions
     -> try { contribution.Content(host) }
        catch (t) {
            渲染降级卡片「此卡片暂时不可用」
            diagnostics.record(t)
            该 contribution 标记为异常，本次会话不再渲染
        }
```

一个插件的 UI 崩了，用户看到的是「此卡片暂时不可用」，而不是整个 App 白屏。`[锁]`

### 5.3 声明式注册 vs 运行时注册

| 方式 | 何时用 | 好处 |
|---|---|---|
| manifest 里声明 `contributes` | 默认，静态贡献点 | 不加载代码就能画出骨架；可静态校验；Agent 可预演；卸载更干净 |
| 运行时 `ctx.extensions.register` | 需要条件注册（能力不足时不注册） | 灵活 |

manifest 声明的贡献，内核在 `init()` 之前就知道「这个插件会让哪个 Slot 长出东西」；运行时注册只能在 `INITIALIZED` 阶段发生。两者产出完全一样，只是前者可预知。

### 5.4 跨插件通信的唯一通道

```kotlin
// plan-core 插件里
ctx.extensions.register(KernelPoints.Service, object : ServiceContribution {
    override val contract: KClass<*> = PlanService::class
    override val instance: Any = RealPlanService(ctx.db, ctx.clock)
})

// 别的插件里（自己的 dependsOn 里写了 core.plan）
val plan: PlanService = ctx.services.require(PlanService::class)   // 拿不到即抛，转 FAILED
val maybe: AiService? = ctx.services.find(AiService::class)        // 可选，可能为 null
```

`ctx.services` 由内核从 `KernelPoints.Service` 收集，按声明类型索引。`require` 失败时给可读错误，例如「你依赖了 core.plan，但它的版本不提供 PlanService」。

**验收**：写两个内置插件，A 通过 Service 向 B 提供能力，断言装卸 A 时 B 状态变化正确。
---

## 6. Capability 与审批

> 这一节直接对应原始需求里的「agent 对程序权限的全面调用（危险操作的自动审查、手动批准）」。

### 6.1 Capability 清单

```kotlin
object Capabilities {
    val ReadPlan     = CapabilityId("read_plan")
    val WriteTask    = CapabilityId("write_task")
    val DeleteData   = CapabilityId("delete_data")      // 不可逆
    val Notify       = CapabilityId("notify")
    val Background   = CapabilityId("background")       // 后台常驻 / 前台服务
    val FocusMode    = CapabilityId("focus_mode")       // 专注模式（Android 勿扰 / 无障碍）
    val Network      = CapabilityId("network")
    val Clipboard    = CapabilityId("clipboard")
    val AgentTool    = CapabilityId("agent_tool")       // 暴露给 Agent 作为可调用工具
    val PluginAdmin  = CapabilityId("plugin_admin")     // 装 / 卸 / 改插件集（极危）
}

interface CapabilityAccess {
    fun has(c: CapabilityId): Boolean
    fun require(c: CapabilityId)     // 未授权 -> 抛 CapabilityDeniedException -> FAILED
}
```

**四条规则：**

1. **默认拒绝。** manifest 里没声明 = 拿不到。
2. 安装时内核比对 manifest 声明与用户已授权集合，未授权的弹一次授权 UI，结果写入 `kernel_grant` 表。
   **授权记录不随 Profile 回滚**那是用户意图，不是软件状态。
3. `[锁]` **运行时 manifest 不可变。** 安装时把 `manifestHash` 落库，每次启动校验；改了 manifest 就必须重走安装审批。
   **这是「Agent 无法自我提权」的技术实现。**
4. 缺能力 -> 插件 FAILED（不是静默降级），错误码 `K005`，诊断里写清缺哪一个。

### 6.2 审批门（自动审查 + 手动批准）

```kotlin
enum class RiskLevel { SAFE, NOTIFY, DANGEROUS }

enum class Actor { USER, AGENT, SYSTEM }

data class ActionRequest(
    val pluginId: PluginId,
    val actor: Actor,
    val capability: CapabilityId,
    val action: String,            // 语义化动作名，如 plan.delete / plugin.install
    val payloadPreview: String,    // 人类可读的 diff 预览
    val reversible: Boolean,
)

sealed interface ApprovalDecision {
    data object Auto : ApprovalDecision
    data object NeedsManual : ApprovalDecision
    data class Denied(val reason: String) : ApprovalDecision
}

interface ApprovalGate {
    suspend fun review(req: ActionRequest): ApprovalDecision
    suspend fun awaitManualApproval(req: ActionRequest): ApprovalDecision   // 挂起等 UI 决策
}
```

**风险判定表：**

| 风险级别 | 判据 | 处理 |
|---|---|---|
| SAFE | 只读能力 + 可逆 + 已授权 | 自动放行 |
| NOTIFY | 写操作但可逆（建任务、改计划） | 自动放行 + 审计 + 通知用户 |
| DANGEROUS | 不可逆（删除、清数据、外发网络、装插件、改插件集） | **必须人工批准**，展示 diff 预览 |

**[拍板] Agent 发起的动作一律升一级风险。** `Actor.AGENT` 时 NOTIFY -> DANGEROUS，SAFE -> NOTIFY。

> 理由：用户自己点「删除」是明确意图；Agent 输出「删除」可能只是幻觉。比赛演示时，这一条是「安全设计」的加分点你可以现场演示 Agent 想删数据，App 弹出确认框并展示它要删什么。

### 6.3 配额（防止插件跑飞）

| 项 | 默认值 | 超限行为 |
|---|---|---|
| `init()` 超时 | 2 s | FAILED，`K006` |
| `start()` 超时 | 5 s | FAILED，`K006` |
| 插件 scope 内活跃协程数 | 64 | 新协程创建失败 + 告警 |
| KV 单值大小 / 命名空间总量 | 1 MB / 32 MB | 写失败，`K010` |
| 调度任务数 | 32 | 注册失败 |
| 日志速率 | 100 条 / 分钟 | 丢弃 + 限流标记 |

配额值全部走 `KernelConfig`。比赛演示时可以临时调小来现场展示熔断。

---

## 7. 存储

```kotlin
interface KvStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun delete(key: String)
    suspend fun keys(prefix: String = ""): List<String>
}

interface DbAccess {
    suspend fun <T> transaction(block: suspend TxScope.() -> T): T
    suspend fun query(sql: String, vararg args: Any?): List<Row>
    suspend fun exec(sql: String, vararg args: Any?)
}
```

**规则：**

1. 底层一张 SQLite 库。KV 表为 `kv(ns, k, v, updated_at)`，`ns` 就是插件 id。
2. 插件建表**强制前缀** `p_<插件id规范化>_`，内核在 `DbAccess` 里校验，违规抛 `K010`。
   这样卸载时才能干净地删掉（默认保留数据，用户可勾选「同时清除」）。
3. 迁移：表 `plugin_migration(plugin_id, version, applied_at)`。内核在 `init()` **之前**执行迁移；
   失败 -> FAILED，`K008`，且**不进入 ACTIVE**。
4. **领域数据不直连 DB。** 第三方插件通过 `PlanService` 读写，走 `core.plan` 这个 required 插件。
   这样领域模型演进时，只有 plan-core 需要改。
5. 卸载时数据保留 7 天（记在 `pending_purge` 表），重装可恢复。
    演示「插件卸载重装不丢数据」很加分。

---

## 8. 时钟、调度、事件

```kotlin
interface KernelClock {
    fun now(): Instant
    fun monotonicMs(): Long
}
object SystemClock : KernelClock
class FakeClock(var current: Instant) : KernelClock   // 干跑 / 测试 / 演示快进

interface Scheduler {
    /** key 幂等：同一 key 重复注册只保留一个；进程重启后按 key 恢复 */
    suspend fun schedule(delay: Duration, key: String, block: suspend () -> Unit): Cancelable
    suspend fun scheduleAt(time: Instant, key: String, block: suspend () -> Unit): Cancelable
    fun cancel(key: String)
}

interface EventBus {
    fun <E : Any> emit(event: E)
    fun <E : Any> subscribe(type: KClass<E>, owner: PluginId? = null): Flow<E>
}
```

**规则：**

1. `[锁]` 内核与插件**一律禁止**使用 `System.currentTimeMillis()` / `Instant.now()`。
   连续天数 streak、过期判断、复习间隔全部走 `ctx.clock`否则测试和沙箱干跑都做不了。
2. 事件按类型分发，订阅返回 Flow；插件 scope 取消时自动退订。
3. 事件跨插件通信只用于**通知**，不用于请求-响应（那是 Service 的活）。
4. 调度默认持久化（`scheduler_task` 表），进程重启后仍然生效。

---

## 9. Loader SPI  三轨制落地成一行代码

```kotlin
interface PluginLoader {
    val scheme: String
    val platforms: Set<Platform>
    suspend fun discover(root: String): List<DiscoveredPlugin>
    suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin
}

enum class Platform { ANDROID, JVM, IOS, WASM }
```

| 实现 | scheme | 支持平台 | 用途 |
|---|---|---|---|
| BuiltinLoader | builtin | 全部 | 编译进包的 `features:*` |
| DslLoader | dsl | 全部 | **轨道 A**：JSON / DSL 声明式插件 |
| ZiplineLoader | zipline | ANDROID, JVM, IOS | **轨道 B**：JS 沙箱 |
| WasmLoader | wasm | ANDROID, JVM | **轨道 B**：Chicory 跑 Wasm |
| ShadowLoader | shadow | ANDROID | **轨道 C**：dex 插件 APK |
| Pf4jLoader | pf4j | JVM | **轨道 C**：桌面 jar 插件 |

**关键设计：** 如果 `manifest.loader` 在当前平台没有可用实现，插件状态置为 `UNAVAILABLE`，
UI 显示「此插件在你的设备上不可用」，**不报错、不崩溃、不阻塞启动**。

这是跨端一致性的落点：同一份插件目录在手机和桌面上都能被识别，只是能力不同。

**验收**：写一个只支持 `Platform.IOS` 的 `FakeLoader` 塞进内核，断言插件变 UNAVAILABLE 且内核仍为 ACTIVE。
---

## 10. 启动序列 [开工]

```kotlin
suspend fun Kernel.Companion.boot(config: KernelConfig): Kernel {
    //  1. 读崩溃计数（独立文件，不依赖 SQLite）        失败 -> 强制 SAFE 模式
    //  2. 打开存储，跑内核自身的迁移                    失败 -> 抛致命错（此时真的没救了）
    //  3. 创建 KernelDispatcher（单线程）、EventBus、Clock
    //  4. 加载 Profile（上次的启用集 + 版本 + 顺序）     缺失 -> 用默认 Profile
    //  5. 扫描插件目录，交给各 Loader.discover()        单个失败 -> 跳过该插件
    //  6. DependencyResolver.resolve()                失败 -> 记录 K001/K002/K003，继续
    //  7. 逐插件 LOADED -> INITIALIZED                失败 / 超时 -> FAILED，依赖闭包 SKIPPED
    //  8. 收集 contributions -> 注册到扩展点            冲突 -> K007
    //  9. 逐插件 ACTIVE（按依赖序）                    失败 / 超时 -> FAILED
    // 10. 崩溃计数归零，返回 Kernel(ACTIVE)
}
```

**每一步的失败语义必须明确。** 这是内核最重要的属性：

| 步骤 | 失败时 |
|---|---|
| 1 | 进 SAFE 模式（只加载 builtin=true 的插件） |
| 2 | 抛异常，App 显示「数据损坏 + 恢复选项」 |
| 3 | 同上 |
| 4 | 用默认 Profile（只启 required 插件） |
| 5 | 跳过该插件，记 K004 / K009 |
| 6 | 失败的插件 SKIPPED，其余照常 |
| 7 | 该插件 FAILED，其依赖闭包 SKIPPED |
| 8 | 冲突的 contribution 取 order 高者，记 K007 |
| 9 | 该插件 FAILED，依赖它的停止启动 |
| 10 |  |

`[锁]` **不变量 I1**：即使步骤 59 全部失败，内核仍返回 ACTIVE，UI 显示空状态 + 诊断入口。

---

## 11. 失败隔离与安全模式

### 11.1 隔离手段

```kotlin
class PluginScope(val id: PluginId, kernelDispatcher: CoroutineDispatcher) {
    val coroutineContext = SupervisorJob() +
        kernelDispatcher +                        // 插件崩不影响兄弟
        CoroutineName("plugin:" + id.value) +
        CoroutineExceptionHandler { _, t -> /* 记诊断 + 标记 FAILED */ }
}
```

- 每个插件一个 scope。`uninstall` = `scope.cancel()`，一步到位。
- 每次内核调用插件方法都包 `withTimeout` + `runCatching`。**插件永远拿不到让内核崩溃的机会。**
- 一个插件 FAILED -> 只有依赖它的插件变 SKIPPED，其余不受影响。`[锁]` **I5**

### 11.2 安全模式

- 崩溃计数器存在 `kernel_boot.dat`（纯文本数字，**不依赖 SQLite**DB 本身可能就是崩因）。
- 每次启动 +1，成功到达 ACTIVE 后清零。
- 连续 3 次未达 ACTIVE -> 下次以 `BootMode.SAFE` 启动：只加载 `builtin && required` 的插件。
- SAFE 模式 UI 顶部显示横幅：「检测到启动异常，已进入安全模式」，
  附【诊断】【回滚到上一个快照】【禁用所有第三方插件】三个入口。
- SAFE 模式启动的同时写入审计日志。

**验收**：注入一个每次 `init()` 都抛异常的插件，连续启动 4 次，断言第 4 次进入 SAFE 且 App 可用。

---

## 12. 快照与回滚

```kotlin
data class Profile(
    val plugins: List<PluginEntry>,
    val configHash: String,
    val createdAt: Instant,
)

data class PluginEntry(
    val id: PluginId,
    val version: SemVer,
    val loader: String,
    val order: Int,
    val enabled: Boolean,
)
```

**规则：**

1. 任何 Profile 变更（安装 / 卸载 / 升级 / 启停 / 调序 / Agent 应用插件）**之前**，
   先写一份物理快照：`snapshots/<epochMs>/profile.json` + 各插件目录副本。
2. 保留最近 5 份，超出按时间淘汰。
3. 回滚 = 停所有插件 -> 覆盖插件目录 -> 重新 `boot()`。**不需要重启进程。**
4. 授权记录（`kernel_grant`）**不进快照**，见 6.1。
5. 审计日志 `audit.jsonl` **只追加不修改**。每条形如：

```json
{
  "ts": "2025-01-20T10:22:31Z",
  "actor": "AGENT",
  "action": "plugin.install",
  "pluginId": "ai.ebbinghaus",
  "promptDigest": "用户想要按遗忘曲线安排复习",
  "model": "gemini-3-pro",
  "diff": { "added": ["plan.review.model:ebbinghaus"], "removed": [] },
  "result": "success"
}
```

`[锁]` 这是「Agent 写的插件可追溯」的物理依据。比赛里被问「你怎么保证 AI 不乱来」，就把它掏出来。

**验收**：装一个正常插件 -> 打快照 -> 换成会崩的版本 -> 回滚 -> 断言回到可用状态。

---

## 13. 观测与错误码

### 13.1 错误码表

| 码 | 名称 | 用户可见文案 | 建议动作 |
|---|---|---|---|
| K001 | SDK_VERSION_MISMATCH | 此插件需要更新版本的 App | 升级 App / 联系作者 |
| K002 | MISSING_DEPENDENCY | 此插件缺少依赖：X | 安装 X |
| K003 | CIRCULAR_DEPENDENCY | 插件之间存在循环依赖 | 报告作者（附环路径） |
| K004 | MANIFEST_INVALID | 插件配置不合法 | 查看详情 |
| K005 | CAPABILITY_DENIED | 此插件需要【通知】权限 | 去授权 |
| K006 | INIT_TIMEOUT | 插件启动超时 | 禁用 / 回滚 |
| K007 | CONTRIBUTION_CONFLICT | 两个插件都想接管 X | 调整优先级 |
| K008 | MIGRATION_FAILED | 数据升级失败 | 回滚 / 清数据 |
| K009 | LOADER_UNAVAILABLE | 此插件在你的设备上不可用 | 无（平台限制） |
| K010 | QUOTA_EXCEEDED | 插件超出资源限额 | 禁用 |
| K011 | CAPABILITY_MANIFEST_TAMPERED | 插件被修改过，需要重新授权 | 重新安装 |
| K012 | SERVICE_NOT_FOUND | 依赖的服务不可用 | 检查依赖版本 |

每条错误携带：`code / pluginId / 内部详情 / 时间 / 是否可自动恢复`。

### 13.2 诊断面板（比赛加分项）

```kotlin
interface Diagnostics {
    fun report(): KernelReport
    fun pluginReport(id: PluginId): PluginReport
    fun export(): String          // JSON，可导出给评委看
}

data class PluginReport(
    val id: PluginId,
    val state: PluginState,
    val version: SemVer,
    val loader: String,
    val contributedCount: Int,
    val bootDurationMs: Long,
    val errorCount: Int,
    val lastError: KernelError?,
    val lastActiveAt: Instant?,
    val capabilities: Set<CapabilityId>,
)
```

UI 形态：每个插件一行，绿 / 黄 / 红状态点，展开看错误历史与贡献点列表。

**这个面板本身就是「插件化」这个技术点的展示窗口，评审时务必演示。**
---

## 14. 契约测试

### 14.1 内核侧（每条不变量一个测试） [锁]

| 编号 | 测试内容 |
|---|---|
| I1 | 空插件目录下 `boot()` -> ACTIVE |
| I2 | Konsist：`:kernel` 不依赖 `:sdk-domain` |
| I3 | 并发 10 次 install，注册表状态一致、无竞态 |
| I4 | 插件经 `ctx` 只能访问自己的命名空间 |
| I5 | 插件 A 崩溃，插件 B 正常 ACTIVE |
| I6 | install -> uninstall 后 contributions / slot / 协程数全部归零 |
| I7 | SDK 新增字段不破坏旧插件（二进制兼容测试） |
| I8 | 篡改 manifest 后启动 -> K011，插件不加载 |

### 14.2 插件侧（给插件作者的测试基类）

```kotlin
abstract class PluginContractTest {
    abstract fun createPlugin(): Plugin

    @Test fun manifestIsValid()
    @Test fun initIsIdempotent()                  // 连续调两次不炸
    @Test fun disposeIsClean()                    // dispose 后无活跃协程
    @Test fun contributesOnlyDeclaredPoints()     // 没在 manifest 声明的点不许注册
    @Test fun doesNotUseWallClock()               // 扫描字节码，禁止 System.currentTimeMillis
}
```

这个基类同时是 **Agent 生成插件后的自动校验入口**：
Agent 产出插件 -> 跑这套测试 -> 全绿才能进入安装审批。

---

## 15. 实现里程碑

| 里程碑 | 内容 | 交付物 | 验收 | 估时 |
|---|---|---|---|---|
| **M0** | 模块骨架 + `PluginManifest` + 空 `Kernel.boot()` | 10 个空模块 + 依赖规则 CI 断言 | `boot()` 返回 ACTIVE，I2 绿 | 0.5 天 |
| **M1** | Registry + Resolver + 状态机 | `DependencyResolver` 完整单测 | 环 / 缺依赖 / 版本不匹配 / 菱形全过 | 1.5 天 |
| **M2** | 扩展点 + Slot + 第一个内置插件 | `task-board` 插件渲染一张卡片 | 装 / 卸插件，UI 实时增减 | 1 天 |
| **M3** | 存储 + 迁移 + Capability + 授权 UI | `plan-core` 落地 | 第三方插件经 PlanService 建任务 | 2 天 |
| **M4** | 失败隔离 + 超时 + 安全模式 | 崩溃注入测试 | I5、安全模式验收通过 | 1 天 |
| **M5** | 快照 + 回滚 + 审计日志 | 回滚 UI | 回滚验收通过 | 1 天 |
| **M6** | DslLoader（轨道 A） | 一个纯 JSON 插件跑通全流程 | Agent 生成 manifest -> 安装 -> 生效 | 23 天 |
| **M7** | 诊断面板 | 插件健康度 UI | 演示可用 | 1 天 |

**时间不够就倒序砍**：M7 -> M6 -> M5。M0M4 是骨架，砍了就什么都没有了。

**M2 结束时你就有一个可以录屏的 demo**：热装一个插件，UI 上长出一张卡片。

---

## 16. 反模式（写了就凉）

1. `:kernel` import 任何一个 `:features:*`。
2. 插件之间直接 `import` 对方的类  必须走 `KernelPoints.Service`。
3. 全局单例 / 顶层 `object` 持有可变状态。一切经 `PluginContext`。
4. 插件直接用 `System.currentTimeMillis()`、`Thread`、`GlobalScope`。
5. 用 `expect/actual` 定义契约（那是平台原语的工具，不是契约工具）。
6. 插件直接持有 Application / Activity / Window 引用（内存泄漏 + 卸不干净）。
7. 扩展点静默覆盖（ONE 冲突必须报错）。
8. 插件里写 `catch (t: Throwable) {}` 吞掉一切  诊断面板会瞎。
9. 让 Agent 拥有内核目录的写权限。**一行都不行。**
10. 卸载插件时用 `System.exit` 或重建整个进程来「刷新」 那说明热卸载没做对。

---

## 附录 A：Manifest 完整示例

```json
{
  "id": "ai.ebbinghaus",
  "version": "1.2.0",
  "name": "艾宾浩斯复习",
  "description": "按遗忘曲线安排复习",
  "author": "agent:gemini-3-pro@2025-01-20",
  "sdkRange": ">=1.0.0 <2.0.0",
  "loader": "dsl",
  "builtin": false,
  "required": false,
  "dependsOn": [
    { "id": "core.plan", "range": ">=1.0.0" },
    { "id": "ai.provider", "range": ">=1.0.0", "optional": true }
  ],
  "capabilities": ["read_plan", "write_task", "notify"],
  "contributes": {
    "plan.review.model": [ { "id": "ebbinghaus", "order": 100 } ],
    "plan.slot":         [ { "id": "review-card", "order": 50, "payload": "card.json" } ],
    "agent.tool":        [ { "id": "plan_review", "payload": "tool.json" } ]
  },
  "entry": "main.dsl.json",
  "migrations": [
    { "version": 1, "sql": "CREATE TABLE p_ai_ebbinghaus_review(id TEXT PRIMARY KEY, due_at INTEGER)" }
  ]
}
```

---

## 附录 B：内核验收清单（评审前逐条打勾）

```
[ ] 删掉 plugins 整个目录，App 能启动并显示空状态
[ ] 装一个插件不用重启进程
[ ] 卸一个插件，UI 上所有相关元素消失，无内存泄漏
[ ] 让一个插件的 init() 死循环，App 照常可用，该插件标红
[ ] 让两个插件抢同一个 ONE 扩展点，出现可读错误
[ ] 篡改一个插件的 manifest 权限，启动被拒绝
[ ] 回滚到上一个快照，App 恢复
[ ] 连续 3 次启动失败后自动进安全模式
[ ] 同一个插件目录在 Android 和桌面都能被识别（一个 UNAVAILABLE，一个 ACTIVE）
[ ] Agent 想执行删除操作时，弹出人工批准并展示 diff
[ ] 诊断面板能导出 JSON
[ ] Konsist 四条依赖断言全绿
[ ] 在 :kernel 里搜不到 Plan / Task / AI 任何一个词
```

---

## 下一步

按 15 做 **M0**：建 10 个空模块 + 依赖规则断言 + `PluginManifest` + 一个什么都不做的 `Kernel.boot()`，
跑通「零插件启动」这一个测试。半天就能有第一个绿灯，而且它锁死整个架构的方向。

M0 + M1 的代码骨架（Gradle 模块结构、`PluginManifest`、状态机迁移表、`DependencyResolver` 含环检测与单测）
可以直接接着写。
---

## 17. 实现回填（v0.1 -> v0.2 偏差记录）

M0 + M1 落地后，有六处发现设计文档写得不合适。**设计文档不是圣经，实现才是真相。**

| # | 文档原写法 | 实际实现 | 为什么改 |
|---|---|---|---|
| 1 | 9 `PluginLoader` 同时负责 discover 和 load | 拆成 `PluginDiscovery`（平台无关）+ `PluginLoader`（平台相关） | 发现动作若绑在 loader 上，某平台上没有对应 loader 的插件**连被发现的机会都没有**，UNAVAILABLE 这个状态永远不可能出现，跨端一致性无从谈起 |
| 2 | 4.6 `sealed interface ResolveResult { Ok / Failed }` | `data class ResolveResult(order, rejected, errors)` | Ok/Failed 是「全成或全败」，与 4.6 自己写的「失败必须隔离」**直接矛盾**：一个插件挂了不该让其他插件丢掉启动资格 |
| 3 | 13 错误码 K001K012 | 扩到 K001K018 | 实现时发现三类失败被混在一起，诊断面板分不清：**K013** 依赖版本不匹配、**K014** 依赖被跳过（闭包传播）、**K015/16/17** 加载 / 初始化 / 启动失败、**K018** 必需插件未就绪 |
| 4 | 8 `KernelClock.now(): Instant` | `nowMs(): Long` + `monotonicMs(): Long` | 内核层不引入 kotlinx-datetime，保持零运行时依赖之外的干净；富时间类型留给 `:sdk-domain` |
| 5 | 10 启动序列未提超时细分 | `KernelConfig` 增加 `loadTimeoutMs / initTimeoutMs / startTimeoutMs / stopTimeoutMs` | 配额表 6.3 只写了 init 2s、start 5s，但没提 **load 阶段一样会卡住** |
| 6 | 3.2 「用 Konsist 断言四条依赖规则」 | 先用 Gradle 任务 `verifyKernelPurity` 落地 I2 | 少一个第三方依赖。四条里最关键的 I2 先变成构建时断言，其余等模块变多再上 Konsist |

### 17.1 一条文档里没写、但代码必须遵守的纪律

**不要用 `runCatching` 包插件调用。** 它会连 `CancellationException` 一起吞掉，破坏结构化并发 
表现为「协程被取消了，插件却以为一切正常继续跑」。

内核用的是 `guard { }`：捕获 `TimeoutCancellationException` 与普通异常，
但把**真正的协程取消原样抛出**：

```kotlin
private suspend fun <T> guard(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (timeout: TimeoutCancellationException) {
        Result.failure(timeout)      // 超时是我们自己要处理的事件
    } catch (cancel: CancellationException) {
        throw cancel                 // 真取消必须往上抛
    } catch (t: Throwable) {
        Result.failure(t)            // 插件自己炸，隔离掉
    }
```

> 实现提示：`TimeoutCancellationException` 是 `CancellationException` 的子类，
> **catch 顺序必须是先子后父**，写反了超时就永远不会被识别成超时。

### 17.2 M0 + M1 交付清单

`./gradlew check` 通过：**42 个测试 + 1 条架构断言**。

| 模块 | 文件 | 内容 |
|---|---|---|
| core | Ids.kt / SemVer.kt | PluginId / CapabilityId / ExtensionPointId；SemVer + 版本区间解析 |
| plugin | PluginManifest.kt / Plugin.kt / PluginState.kt | 插件身份证；生命周期接口；状态机迁移表 |
| registry | DependencyResolver.kt | 版本区间 + 失败闭包传播 + Kahn 拓扑排序 + DFS 环路径提取 |
| spi | PluginDiscovery.kt / PluginLoader.kt / BuiltinLoader.kt | 发现 / 加载 SPI，内置插件加载器 |
| api | Kernel.kt | boot 五步序列、失败隔离、required 降级裁决、shutdown |
| time | KernelClock.kt | 可注入时钟 + FakeClock（expect/actual 唯一允许的用法） |
| diag | KernelError.kt | 18 个错误码 + 可诊断错误 |

**已变成机器断言的不变量**：I1（零插件启动）、I2（内核领域中立）、I5（失败不传播），
外加 I6 的一半（shutdown 全量 dispose 且幂等）。

**还没做的**：I3（并发 install）、I4（命名空间隔离）、I6 的另一半（贡献点归零）、
I7（二进制兼容）、I8（manifest 篡改） 它们分别依赖扩展点（M2）、存储（M3）、
快照（M5），属于后续里程碑。
---

## 18. M2 实现回填（v0.2 -> v0.3）

M2 落地：扩展点 + Slot + 热装热卸，**不含 Compose**。目标是把「贡献点能不能干净地装卸」用测试钉死。

### 18.1 与 5 的偏差

| # | 文档原写法 | 实际实现 | 为什么改 |
|---|---|---|---|
| 1 | `ExtensionPoint<T>(id, contract: KClass<T>, cardinality)` | 去掉 `KClass`，只有 `id / cardinality / contractName` | KMP 的 common 代码里没有可用的反射。类型安全由泛型在**调用点**保证，不做运行时类型校验 |
| 2 | `SlotContribution` 内含 `@Composable fun Content(host)` | 契约只有 `id + order`；具体形态是 `TextSlot` 这类数据类 | M2 要证明「不引入 Compose 也能验证装卸」。Compose 到位后新增 `ComposeSlot` 实现即可，**契约本身不用改** |
| 3 | Slot 是 `ExtensionPointId` 常量 | Slot 是 `ExtensionPoint<SlotContribution>` 对象（`Slots.HomeHeader`） | 类型安全 + IDE 可发现；manifest 里仍然用 id 字符串声明 |
| 4 | 挂载点命名如 `plan.detail.aside` | 改成 `slot.detail.aside` 这类**通用 UI 位置** | `plan` 是业务概念，而 `verifyKernelPurity` 会拦住 CamelCase 里的 `Plan`  这条纪律的副作用恰好是对的：内核的挂载点本就该是 UI 概念，不是业务概念 |

### 18.2 三个值得单独说的设计决策

**决策 1：裁决放在读取时计算，不在写入时定胜负。**

`MutableExtensionRegistry.add()` 只登记，不定谁赢；「谁是 ONE 扩展点的赢家」在 `effective()` 里现算。

代价很小（每次读多一次排序），收益很大：卸载赢家时**不需要任何状态修补**，第二名自动顶上。
如果写入时就锁死赢家，卸载后就得写一段「找第二名并提升」的逻辑  那段逻辑在并发场景下极易出错。

> 由 `removingTheWinnerPromotesTheRunnerUpWithoutAnyPatching` 直接验证。

**决策 2：注销贡献点由内核按属主强制完成，不依赖插件的 `dispose()`。**

如果依赖插件自觉注销，「卸载无残留」（不变量 I6）就永远只是个愿望 
一个写漏了 deregister 的插件就能把残留物永久留在注册表里。内核在卸载时无条件执行 `removeOwner(pluginId)`。

> 由 `uninstallRemovesContributionsEvenThoughDisposeDoesNothing` 验证：那个测试用的插件**根本没实现 dispose()**，依然摘得干净。

**决策 3：`init()` 返回的瞬间关闭 registrar。**

「只能在 init() 期间注册」不靠文档约定，靠对象生命周期：`PluginRegistrar.open` 在 `init()` 返回后立刻置 false。

> 由 `registeringOutsideInitIsRejected` 验证。

**附带一条**：未在 manifest 声明就注册  插件失败。注册被拒记 K019，**插件本身仍然 ACTIVE**。
理由是这两件事的性质不同：插件没声明就注册是契约违规（该记），但不该因为一次多余的注册就让整个插件停摆。

### 18.3 M2 交付

`./gradlew check`：**67 个测试 + 1 条架构断言**。另可 `./gradlew :demo:run` 看完整控制台演示。

| 新增 | 内容 |
|---|---|
| extension/ExtensionPoint.kt | Cardinality / ExtensionPoint / OrderedContribution / Contribution |
| extension/ExtensionRegistry.kt | 可写登记表 + 只读视图，读取时裁决 |
| extension/ExtensionRegistrar.kt | 插件唯一的写入口，带「声明过」与「init 期间」双重闸门 |
| slot/Slot.kt | SlotContribution / TextSlot / 9 个通用挂载点 |
| slot/SlotHost.kt | 渲染器 + ErrorBoundary（单卡片降级 + 本次会话隔离） |
| api/Kernel.kt | 新增 install / uninstall / extensions，抽出共用的 runLifecycle |
| diag | 新增 K019 CONTRIBUTION_REJECTED |

### 18.4 不变量清单（更新）

| 不变量 | 状态 |
|---|---|
| I1 零插件启动 | 已断言 |
| I2 内核领域中立 | 已断言（构建时，非测试） |
| I5 失败不传播 | 已断言 |
| I6 卸载无残留 | 贡献点归零部分**已断言**；协程/监听器部分待 M3 |
| I3 单线程变更 | 未做  install/uninstall 目前并非串行化，需要 KernelDispatcher |
| I4 命名空间隔离 | 待 M3 存储 |
| I7 契约只增 | 待 `:sdk-api` 独立成模块 |
| I8 不可自我提权 | 待 M5 快照与 manifestHash 校验 |

> 顺带记一笔：M2 期间我在 `shutdown()` 里顺手加了一句 `entries.clear()`，结果把 M1 的一条测试打红了。
> 那条测试是对的  **关机之后还应当能查到「刚才跑了什么、怎么结束的」**，清空是纯粹的信息损失。
> 已经撤回，并把这个理由写进了代码注释。
---

## 19. M5 实现回填（v0.3 -> v0.4） 轨道 A：声明式插件

目标：**让「写一份 JSON」和「写一个 Kotlin 插件」在内核眼里完全等价。**

达成判据（`./gradlew :demo:run` 可现场复现）：
往插件目录写两份 JSON -> `kernel.install(id)` -> 首页立刻多出一张卡片。没有编译，没有重启。

### 19.1 与 9 的偏差

| # | 文档原写法 | 实际实现 | 为什么改 |
|---|---|---|---|
| 1 | `PluginLoader` 上同时有 `discover()` 和 `load()` | `discover()` **删掉** | 它和 `PluginDiscovery` 重复，是 M0 拆分的遗留死代码。直到 `discover` 的返回类型变了才由编译器暴露出来 |
| 2 | `discover()` 返回 `List<DiscoveredPlugin>` | 返回 `DiscoveryResult(plugins, errors)` | 与 `ResolveResult` 同一个教训：**一个坏 manifest 不该让另外九个正常插件一起消失** |
| 3 | manifest 里手写 `contributes` | 声明式插件的 `contributes` **由 entry 文件推导**，manifest 里那个字段根本不读 | 见下面的决策 1 |

### 19.2 四个设计决策

**决策 1：`contributes` 不手写，由内容推导。**

作者只维护 `main.dsl.json` 一处真相，内核在 discovery 阶段算出 `contributes`。
这样 manifest 与内容**不可能漂移**  而「两份需要手动保持同步的真相」是这类系统里最经典的 bug 来源。

**决策 2：严格 JSON 解析（`ignoreUnknownKeys = false`）。**

Agent 把 `title` 写成 `tittle` 时必须炸出来。
静默渲染一张**空白卡片**是比报错糟糕得多的失败模式  因为没人会去看，而作者会以为是自己逻辑写错了。

**决策 3：一个 entry 文件里的所有问题一次报完。**

Agent 的修改循环是「生成 -> 校验 -> 修正」。一次只报一个错会让这个循环变成 N 倍长。
所以 slot 校验是收集式而不是 fail-fast（manifest 层面的致命错误仍然立即返回，因为那时候没法继续）。

**决策 4：未知挂载点的报错必须列出全部可用挂载点。**

实际输出：

```
[K004] ai.typo: .../main.dsl.json 的第 1 个 slot 指向了不存在的挂载点「slot.home.hedaer」；
       内核认识的是：slot.app.overlay, slot.detail.aside, slot.detail.tab, slot.empty.state,
       slot.home.header, slot.home.section, slot.report.chart, slot.row.trailing, slot.settings.section
```

这条对 Agent 尤其关键：**它拿不到源码，只拿得到报错文本。** 报错里有什么，它下一次就能改对什么。

### 19.3 门面原则：不要让依赖从 API 缝里漏出去

`DslLoader` 最初把 `Json` 放进了构造函数参数。结果 `:demo` 编译失败：`Cannot access class 'kotlinx.serialization.json.Json'`。

因为 `:plugin-dsl` 把 serialization 声明为 `implementation`  这是对的，但一旦公开签名里出现 `Json`，
它就成了事实上的 `api` 依赖，每个使用方都得被迫把 serialization 加进类路径。

修法是**把参数收进私有**，而不是把依赖改成 `api`：

```kotlin
class DslLoader(
    private val fileSystem: PluginFileSystem,
    private val clock: KernelClock = SystemClock,
) {
    private val json: Json = DEFAULT_JSON   // 实现细节，不外泄
}
```

顺带一提，严格模式**本来也不该是可调项**  它是正确性决定，不是偏好。

### 19.4 一个被编译器抓出来的设计债

`PluginLoader.discover()` 从 M0 拆出 `PluginDiscovery` 之后一直没删。它不影响编译，不影响测试，
就那么安静地躺了三轮迭代，直到 `discover` 的返回类型变了才冲突暴露。

教训：**接口做拆分时，旧方法必须当场删掉。** 留着「以防万一」的话，它不会自己消失，
只会在半年后以一次莫名其妙的编译错误回来找你  那时候你已经忘了它为什么会在那儿。

### 19.5 M5 交付

| 新增 | 内容 |
|---|---|
| 模块 `:plugin-dsl` | KMP 模块（当前只编 jvm），依赖 `:kernel` + kotlinx-serialization |
| `PluginFileSystem` | 4 方法文件抽象：`listDirectories / exists / readText / join` |
| `JvmPluginFileSystem` | 桌面 / Android 的真实实现 |
| `InMemoryFileSystem` | 测试用；让「目录里有个坏文件」这类场景随手可构造 |
| `DslSchema` | manifest / entry / slot 的 `@Serializable` 定义，即对外协议 |
| `DslLoader` | 轨道 A 加载器：发现、解析、校验、装载 |
| `Slots.byId / isKnown / knownIds` | 让「只有 id 字符串」的声明式插件能落到真实挂载点上 |

测试：`:kernel` 67 条 + `:plugin-dsl` 12 条 = **79 条**，外加构建时的 `verifyKernelPurity`。

### 19.6 不变量清单（更新）

| 不变量 | 状态 |
|---|---|
| I1 零插件启动 | 已断言 |
| I2 内核领域中立 | 已断言（构建时） |
| I5 失败不传播 | 已断言（现在包含「坏 manifest 不连累邻居」） |
| I6 卸载无残留 | 已断言 |
| I3 单线程变更 | 未做 |
| I4 命名空间隔离 | 待存储里程碑 |
| I7 契约只增 | 待 `:sdk-api` 独立成模块 |
| I8 不可自我提权 | 待快照与 manifestHash 校验 |

> 目前 `:plugin-dsl` 只编 JVM。iOS 侧要跑声明式插件时，只需要补一个基于 `NSFileManager` 的
> `PluginFileSystem` 实现  加载器本身一行都不用改。这就是把它抽出来的回报。
---

## 20. 内核欠账回填（v0.4 -> v0.5） Service 扩展点 + I3 串行化

这一轮**刻意不碰任何插件功能**，只补两处内核欠账。
理由是：插件是测试夹具，不是产品；夹具够用就行，内核的洞才是真的洞。

### 20.1 5.4 Service 扩展点

**新增**

| 类型 | 作用 |
|---|---|
| `ServiceBinding(contract: KClass<*>, instance: Any, owner: PluginId)` | 一条服务登记 |
| `ServiceAccess`（只读） | `find` / `require` / `contracts`，挂在 `ctx.services` |
| `ExtensionRegistrar.publish()`（只写） | 与 `register` 同一个闸门 |
| `KernelPoints.Service` | 声明位，id 为 `kernel.service` |

**四个决策**

**决策 1：`KClass` 只当键用，从不调 `isInstance`。**
`KClass.isInstance` 在 JVM 上要 kotlin-reflect，而 KMP 的 common 里没有。用 `KClass` 作 map key（JVM / JS / Native 上语义一致），类型安全交给泛型在**调用点**保证。
于是既保住了 `require(PlanService::class)` 这个自然写法，又完全没有引入反射。

**决策 2：发布服务要过和 `register` 同一道闸门。**
manifest 的 `contributes` 里必须出现 `kernel.service`，否则拒绝并记 K019。
这样「这个插件会对外提供哪些能力」在**安装之前**就能被静态看到，而不是等它跑起来才知道  这是让 Agent 生成的插件可审查的前提。

**决策 3：同一契约被多个插件实现 -> 留一个 + K007。**
按属主名取第一个（确定性的，不是随机的）。
卸载赢家时第二名自动顶上  这里**一行新逻辑都没写**，直接复用了 M2 那个「裁决放在读取时计算」的性质。

**决策 4：服务表与扩展点表分开，但共用 `removeOwner`。**
两者语义不同（有序列表 vs 按键取值），硬塞进一个结构会让两边都别扭。
但**卸载清理必须共用**，否则 I6（卸载无残留）会在服务这里漏一个口子。

### 20.2 I3 串行化

用 `Mutex` 而不是文档原写的「单线程 dispatcher」：KMP 的 common 里没有通用的单线程调度器，
而 Mutex 给出同样的串行化保证，且不引入额外线程。

有个**看起来危险其实安全**的地方：`install` / `uninstall` 会在**持锁期间**等待插件的 `init()` / `start()`（最长 5 秒超时）。

为什么不会死锁？因为插件拿不到 `Kernel` 引用  `PluginContext` 里根本没有这个东西 
所以它**在结构上就不可能重入**。这一点是 4.4「窄门」设计白送的：
当初把 `PluginContext` 收窄到只有 id / clock / log 时，没人想到它会在三个里程碑之后变成防死锁的依据。

### 20.3 怎么确认并发测试不是摆设

并发测试有个通病：**写完就绿，把锁拆掉也绿，于是它什么都没守护。**

所以我把锁临时拆掉，两组数据都跑了出来：

| 测试 | 有锁 | 无锁 |
|---|---|---|
| `concurrentInstallsOfTheSamePluginAdmitExactlyOne` | 通过 | **失败：实际成功 8 次**（应为 1） |
| `concurrentUninstallOfTheSamePluginSucceedsExactlyOnce` | 通过 | **失败：实际卸载 6 次**（应为 1） |
| 其余 3 条 | 通过 | 也通过 |

结论要诚实：**5 条并发测试里只有 2 条是真正 load-bearing 的**，
另外 3 条是烟雾测试，覆盖的是「不丢贡献点」「id 不重复」这类更粗的破坏。
把这件事写下来，比让它们假装都是守护测试要好。

**补齐的关键手法**：想让一个宽 1 个挂起点的竞态窗口被测试命中，**临界区里必须有挂起点**。
所以夹具加了 `initDelayMs` / `stopDelayMs`  没有它们，单线程调度器根本不会切换，
窗口是关着的，测试永远绿。这不是「加点 sleep 试试」，是让测试能看见那个窗口。

### 20.4 交付

`:kernel` 测试 67 -> 80，`:plugin-dsl` 12 条不变，合计 **92 条**，外加构建时的 `verifyKernelPurity`。
`./gradlew :demo:run` 回归通过  Service 的改动没有弄坏轨道 A。

### 20.5 不变量清单（更新）

| 不变量 | 状态 |
|---|---|
| I1 零插件启动 | 已断言 |
| I2 内核领域中立 | 已断言（构建时） |
| **I3 单线程变更** | **已断言，且验证过「去掉锁会红」** |
| I5 失败不传播 | 已断言 |
| I6 卸载无残留 | 已断言（贡献点 + 服务两张表） |
| I4 命名空间隔离 | 待存储里程碑 |
| I7 契约只增 | 待 `:sdk-api` 独立成模块 |
| I8 不可自我提权 | 待快照与 manifestHash 校验 |

### 20.6 5.4 之后，「数据绑定」只差一步

Service 扩展点一落地，之前讨论的数据绑定就不再是「三个机制」了，而是**两个**：

- ~~类型化 Service 扩展点~~  已完成
- 极简求值器（`{{ key }}` -> 值）
- 求值的失败隔离

而第 3 条也已经有现成的地方可挂：`SlotHost` 的 ErrorBoundary。
所以真正剩下的新代码只有那个求值器，而且是纯字符串处理，不涉及任何架构判断。
---

## 21. 6 Capability + 审批门（v0.5 -> v0.6）

对应原始需求里「agent 对程序权限的全面调用（危险操作的自动审查、手动批准）」
那是需求文档里唯一还没动过的一条。

### 21.1 五处与设计文档的偏差

| # | 文档原写法 | 实际实现 | 为什么改 |
|---|---|---|---|
| 1 | `Capabilities.ReadPlan` 是个 `CapabilityId` | 是个 `Capability(id, title, kind)` | 授权界面要能对用户说人话（「读取你的计划与记录」），光有 id 做不到 |
| 2 | 风险由「是否可逆」判定，作为请求参数传入 | 风险由**能力的危险等级**推导，不可传参 | 让插件自己上报「我这个操作很安全」，它当然会说安全。这种自证没有意义 |
| 3 | 「缺能力 -> 插件 FAILED」（6.1 规则 4） | **声明 != 必需**：缺能力**不**让插件失败，由插件自己决定 | 见下文 21.2，这条偏差最重要 |
| 4 | `ApprovalDecision { Auto / NeedsManual / Denied }` | 去掉 `Denied` | 拒绝是一种**结果**，不是一种**判断**。合在一起会写出「策略层直接拒绝」这种绕过用户的操作 |
| 5 | 能力命名如 `read_plan` / `write_task` | `read_data` / `write_data` / `tool_exposure` | 内核必须领域中立。`verifyKernelPurity` 会盯这条  而它是对的 |

### 21.2 最重要的一处偏差：声明 != 必需

文档说「缺能力 -> 插件 FAILED，不是静默降级」。实现时我没有照做，理由有两条：

**第一，装机时用户还没回答授权框。** 如果缺能力就让插件失败，那么在用户点「允许」之前，
这个插件每一次启动都是失败的  这显然不对。

**第二，只有插件自己知道哪个能力是「没有它我别跑了」。** 一个声明了 `notify` 的插件，
用户拒绝了通知，它大可以照常工作、只是不推送。内核替它决定「你必须失败」是越权。

所以内核提供两个动词，把选择权交回去：

```kotlin
ctx.capabilities.has(Notify)      // 拿不到返回 false  用它做优雅降级
ctx.capabilities.require(Notify)  // 拿不到抛异常  用它声明「这是我的硬依赖」
```

`require` 抛出的 `CapabilityDeniedException` 会被 `runLifecycle` 的守卫接住，
插件转 `FAILED` 并记 **K005**。文档想要的效果一点没丢，只是从「内核替插件决定」
挪到了「插件自己声明」 **内核的职责是执行，不是揣测。**

### 21.3 审批门的三个决定

**决定 1：风险来自能力，不来自调用方。**

```
READ_ONLY   -> SAFE        -> 自动放行
MUTATING    -> NOTIFY      -> 自动放行 + 写审计
DESTRUCTIVE -> DANGEROUS   -> 必须人工点头
```

再叠一条：**`Actor.AGENT` 发起的动作升一级**。
用户点删除是明确意图，智能体说删除可能只是幻觉  所以同一个 `Notify` 动作，
用户走自动放行、智能体走必须人工。这条有测试直接对比两种结果。

**决定 2：没有批准界面  默认批准。**

这是安全默认值，和「默认拒绝」是同一条原则。没有注册 UI 处理器时，
危险动作被拒绝并记入审计，理由是「需要你亲自确认，但当前没有可用的确认界面」。

**决定 3：`Denied` 不是一种判断。**

`ApprovalDecision` 只有 `Auto` / `NeedsManual` 两个分支。
拒绝只可能来自两个地方：用户点了「否」，或者没有界面可问。两者都是**结果**（`ApprovalOutcome.Denied`）。
把「判断」和「结果」混进一个类型，迟早会写出「策略层直接拒绝」，那就绕过了用户。

### 21.4 一个被测试逼出来的顺序问题

第一版里，我把 manifest 指纹校验放在了「逐插件装载」循环里（步骤 4a.5），
而依赖解析在步骤 3。测试 `addingADependencyIsAlsoCaught` 立刻红了，报的却是：

```
[K002] dev.a: 缺少依赖 dev.something *
```

**被篡改的插件，以「配置错误」的样子被拒绝了。** K011 根本没机会出现。
一次安全拦截伪装成一次配置错误  这在真实事故里是最坏的情况，因为它不会引起警觉。

改成**指纹校验提前到依赖解析之前**（步骤 2b），被篡改者立刻从解析输入里剔除。
这样它才会以 K011 的本来面目出现，而且依赖它的人会诚实地变成 K014。

### 21.5 一条注释里写不下的教训：报错要同时给人看和给程序看

第一版的能力错误信息长这样：

```
需要授权：删除数据（不可恢复）；当前没有可用的授权界面，按默认拒绝处理
```

人看得懂，但**读这段文本的可能是一个 Agent**  它拿不到源码，只拿得到这段文本，
而它需要的是 `delete_data` 这个机器可读的 id。所以改成：

```
需要授权：删除数据（不可恢复）(delete_data)；当前没有可用的授权界面，按默认拒绝处理
```

**标题给人看，id 给程序看，两边都得给。** 这条对所有面向 Agent 的错误信息都成立。

### 21.6 指纹（不变量 I8）

`manifestFingerprint` 只覆盖**能改变插件权力或加载行为的字段**：
`capabilities` / `dependsOn` / `sdkRange` / `loader` / `required` / `builtin`。

刻意**不含** `name` / `description`：改个显示名不该触发「插件被篡改」（有测试守着这条）。
也**不含** `version`：升版本走的是正常安装流程，那是另一条路径。

首次见到就记下指纹，之后每次启动重新算一遍比对  **不靠约定，靠哈希**。

### 21.7 一个已知缺口，写在代码注释里

`ActionRequest.actor` **目前由调用方声明**。内核还没有工具调用上下文来强制它，
所以一个插件可以谎报自己是 `USER` 来规避风险升级。

谎报会被原样写进审计日志（那是内核写的，插件改不了），但内核此刻拦不住它。
**这是已知缺口，留到工具调用框架落地时，用「上下文里的 actor」替掉「参数里的 actor」。**
把它写进注释和文档，比假装它不存在要好。

### 21.8 交付

`:kernel` 测试 80 -> 103，加 `:plugin-dsl` 12 条，合计 **115 条**。

| 新增 | 内容 |
|---|---|
| `capability/` | Capability 目录（10 项）、CapabilityAccess、GrantStore、GrantDialog |
| `approval/` | RiskPolicy、ApprovalGate、DefaultApprovalGate、ApprovalBroker、ManualApprovalHandler |
| `audit/` | Actor、AuditOutcome、AuditEntry、AuditLedger（只追加） |
| `security/` | ManifestLedger + FNV-1a 64 位指纹 |
| `diag` | 新增 K020 |

### 21.9 不变量清单（更新）

| 不变量 | 状态 |
|---|---|
| I1 零插件启动 | 已断言 |
| I2 内核领域中立 | 已断言（构建时） |
| I3 单线程变更 | 已断言，且验证过「去掉锁会红」 |
| I5 失败不传播 | 已断言 |
| I6 卸载无残留 | 已断言（扩展点 + 服务两张表） |
| **I8 不可自我提权** | **已断言**（指纹校验 + 默认拒绝 + 授权记录不随快照回滚） |
| I4 命名空间隔离 | 待存储里程碑 |
| I7 契约只增 | 待 `:sdk-api` 独立成模块 |

---

## 附：一次工具链事故（值得记下来）

这一轮我在编辑 `Kernel.kt` 时把文件写坏了。原因是 PowerShell here-string 的
**结束标记 `'@` 必须位于行首**，而我把它写在了内容行尾：

```powershell
Rep 'old' @'
新内容'@ 'label'      # 错：'@ 不在行首
```

于是 PowerShell 没在那里结束字符串，继续吞掉了后面的命令  结果 `Rep` 的**源码本身**
被写进了 Kotlin 文件，同时 `KernelConfig` 的尾部被拼接进了 `SimplePluginContext`。

更值得记的是**为什么没立刻发现**：我用「关键标记是否出现」来验证替换，
而那些垃圾文本里恰好含有我要找的标记，于是验证全绿。**验证方法本身被骗了。**

后来的修法是：改用 Python 做替换（精确、可断言），并且
**每个替换都断言锚点出现的次数恰好为 1**。数量断言比存在性断言可靠得多 
存在性只能证明「某处有」，数量能证明「只有一处有」。

教训：
1. 用「字符串存在」验证编辑，会被污染的文件骗过；要用**结构断言**（括号平衡、声明数量、锚点唯一性）。
2. 一次只写一个文件、一次只用一个 here-string。批量操作看着省事，出事时省下的时间全吐回去。

---

## 22. 7 存储与命名空间隔离（v0.6 -> v0.7）

补齐最后一个还悬着的正确性不变量 I4。

### 22.1 最重要的一处接口设计：命名空间不能出现在插件的接口里

设计文档 7 描述的是一张 `kv(ns, k, v, updated_at)` 表。照着写的话，很容易做成这样：

```kotlin
interface KvStore {
    suspend fun get(namespace: String, key: String): String?   // 反例  别这么写
}
```

问题在于：**插件可以传一个 "dev.b" 进来。** 就算你再加一句「namespace 必须等于自己的 id」，
那也只是运行期断言  而任何「先拼字符串再校验」的隔离，都是纸做的。

实际实现把命名空间**从插件可见的接口里彻底拿掉**：

```kotlin
interface KvStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun delete(key: String)
    suspend fun keys(prefix: String = ""): List<String>
    suspend fun clear()
}
```

内核给每个插件一个绑定死命名空间的实例：

```
StorageBackend（内核侧，命名空间是显式参数）
        |
        +-- NamespacedKvStore(namespace = "plugin/dev.a")   <- 插件只拿得到这一层
```

于是「读别人的数据」这件事**在类型层面就写不出来**  不是被拦住了，是根本无从表达。

测试 `noKeyShapeCanReachAnotherNamespace` 直接验证这一点。插件 A 尝试了七种伪造键：

```
secret                          普通键名
dev.b:secret                    假装带命名空间前缀
plugin/dev.b/secret             假装是完整路径
../dev.b/secret                 路径穿越
/plugin/dev.b/secret            绝对路径
kernel:purge_deadline:dev.b     内核自己的记账键
__kernel__/secret               猜内核命名空间
```

**七种全部读不到 B 的数据**，而且它们不是被静默丢弃  确实成了 A 自己命名空间里的普通键
（测试里紧接着 `a.put("dev.b:secret", ...)` 再读回来，验证这一点）。

### 22.2 同一个存储，两套接口，两个侧面

| | `StorageBackend` | `KvStore` |
|---|---|---|
| 谁拿得到 | 只有内核 | 插件（`ctx.kv`） |
| 命名空间 | 显式参数 | **绑死在实例上** |
| 回答的问题 | 「所有格子里都有些什么」 | 「我这个格子里有什么」 |

内核需要前者做卸载清理与过期回收；插件只需要后者。
**把这两件事分成两个接口，而不是给一个接口加权限判断**  这是 I4 能成立的结构性原因。

### 22.3 刻意没做的东西：SQL 与迁移

设计文档 7 的规则 1、2、3 都围绕 SQL 展开（一张 SQLite 库、表名前缀 `p_<id>_`、
`plugin_migration` 表在 `init()` 前跑迁移、失败记 K008）。**这些我没做。**

不是偷懒，是**迁移机制和存储引擎的选择强耦合**：

- 如果后端是 SQLDelight，迁移就是 `.sqm` 文件 + `Schema.migrate`
- 如果后端是 KV，迁移就是插件自己读一个 `schemaVersion` 键然后手写升级
- 两者的失败语义、回滚能力、原子性保证完全不同

现在做，只会做出一个两边都不合身的抽象，等真实后端落地时还得推翻重写。
所以这一轮只钉死**不依赖引擎的那部分性质**：隔离、配额、保留与回收。
等存储引擎选定（那是「把产品建起来」的阶段，不是「把机制钉死」的阶段），迁移再补。

### 22.4 卸载数据策略

设计文档 7 规则 5 要求「卸载时保留数据 7 天，重装可恢复」。实现：

- `uninstall(id, purgeData = false)`  默认保留，往内核自己的命名空间写一条回收期限
- 重装成功时**取消**回收计划（数据还在，插件回来就该能接着用）
- `purgeExpiredData()` 由调用方（通常是一次启动或一个后台任务）触发回收
- `pendingPurges()` 让 UI 能显示「这些数据将在 X 后清除」

这条性质有测试直接验证：`uninstallingRetainsDataSoTheNextInstallFindsItAgain`。

顺带一个实现细节：回收计划存在 `StorageBackend` 的 `kernel` 命名空间里，
**而插件永远碰不到那个命名空间**  22.1 的隔离顺手把内核自己的记账也保护了。

### 22.5 一个容易写错的配额实现

配额表（6.3）要求「KV 单值 1 MB / 命名空间总量 32 MB」。第一版直觉写法是：

```kotlin
if (backend.sizeOf(namespace) + value.size > limit) throw ...
```

**这样反复改写同一个键会被误判为超限**  因为算的是「旧值 + 新值」，而不是「旧值被新值替换」。

正确写法要算增量：

```kotlin
val previous = backend.read(namespace, key)?.encodeToByteArray()?.size ?: 0
val projected = backend.sizeOf(namespace) + (valueBytes - previous)
```

测试 `overwritingTheSameKeyDoesNotFalselyTriggerTheTotalQuota` 反复覆盖同一个键五次，
在 300 字节限额下用一个 200 字节的值  直觉写法第五次就会炸。

### 22.6 交付

`:kernel` 测试 103 -> 114，加 `:plugin-dsl` 12 条，合计 **126 条**。

| 新增 | 内容 |
|---|---|
| `storage/KvStore.kt` | 插件侧接口，**没有命名空间参数** |
| `storage/StorageBackend.kt` | 内核侧 SPI + StorageQuota + InMemoryStorageBackend |
| `storage/NamespacedKvStore.kt` | 绑死命名空间的视图 + 配额检查 |
| `PluginContext.kv` | 插件的数据入口 |
| `Kernel` | `uninstall(id, purgeData)` / `pendingPurges()` / `purgeExpiredData()` / `dataNamespaces()` |

### 22.7 不变量清单（更新）

| 不变量 | 状态 |
|---|---|
| I1 零插件启动 | 已断言 |
| I2 内核领域中立 | 已断言（构建时） |
| I3 单线程变更 | 已断言，且验证过「去掉锁会红」 |
| **I4 命名空间隔离** | **已断言**（7 种伪造键 + 前缀 + 清理互不影响） |
| I5 失败不传播 | 已断言 |
| I6 卸载无残留 | 已断言（扩展点 + 服务 + 数据三处） |
| I8 不可自我提权 | 已断言 |
| I7 契约只增 | 唯一剩下的一条，待 `:sdk-api` 独立成模块 |

> **八个不变量里七个已经是机器断言。** 剩下的 I7 与其说是工程问题，
> 不如说是流程问题：它要求把契约从内核里拆出去、给它独立的版本号和兼容承诺，
> 而这只有在有第二个消费者（真实的第三方插件作者）时才值得做。

### 22.8 一个可以立刻跑起来看的实验

存储落地之后，「Agent 写插件」那个演示终于可以不写死数字了。
下一步只要给 DSL 加一个 `bindings` 字段，把 `{{ taskStats.dueToday }}` 接到 `ctx.kv` 上即可 
而 5.4 的 Service 扩展点已经就位，插件之间交换数据的通道是通的。

**三个机制里两个已经完成，只剩那个纯字符串的求值器。**

---

## 23. 从内核到产品：第一个真正的消费者（v0.7 -> v0.8）

前面七个里程碑一直在做「插座」插件能不能装上、出错会不会崩、会不会越权、会不会偷数据。
这一节开始做**插上电用的东西**。

### 23.1 新增两个模块

```
:sdk-domain           领域契约：Plan / Task / PlanService / PlanPoints
:features:plan-core   PlanService 的 KV 实现，required 内置插件
```

依赖方向仍然是干净的：

```
:features:plan-core  ->  :sdk-domain  ->  :kernel
```

`:sdk-domain` 之所以依赖 `:kernel`，是因为它要用 `ExtensionPoint` 来声明自己的业务扩展点
（排程策略、统计指标）。反过来内核**绝不**依赖领域  不变量 I2 由 `verifyKernelPurity` 守着。

### 23.2 领域数据不直连存储（设计文档 7 规则 4 的第一次兑现）

`:features:plan-core` 用 `ctx.kv` 存任务，但第三方插件**碰不到那些键** 
它们只能通过 `PlanService` 读写：

```kotlin
val plan = ctx.services.require(PlanService::class)
val due = plan.tasks(TaskQuery(dueBefore = tomorrow))
```

有一个**故意保留的低效**：列表查询是「列出所有 `task:` 键 -> 逐个读取」。
任务量大时会慢。等真实的 SQL 后端落地时，这一层会换成一条带索引的查询 
而因为上层只依赖 `PlanService` 接口，**所有调用方一行都不用改**。

这是当初「契约与实现分离」的第一个真实回报，不再是纸面上的好处。

### 23.3 同一个 JSON 库，两个相反的策略

| | 插件清单（`:plugin-dsl`） | 存储数据（`:features:plan-core`） |
|---|---|---|
| 谁写的 | 人或 Agent | 程序自己 |
| 策略 | **严格**：未知字段报错 | **宽容**：未知字段忽略 |
| 为什么 | `tittle` 拼错必须当场炸出来 | 未知字段只可能来自版本升级，丢数据比报错严重 |
| 失败模式 | 作者写错 | 版本错位 |

同一个库、两个相反的配置，因为**失败模式不同**。这类判断没法从「最佳实践」里抄，
只能从「这里出错会是什么样」倒推。

### 23.4 一个被演示逼出来的设计缺口：发现 vs 启用

`install(id)` 的工作方式是**重新扫描插件目录**，找到对应 manifest 再装载。

而我在演示里为了让 `dev.summary` 「已安装但未启用」，让加载器只发现已启用的插件。
结果：

```
install() -> 被拒绝：[K004] dev.summary: 在插件目录里没有找到 dev.summary
```

**它的确"没找到"因为扫描结果已经被启用集过滤过了。而「装一个还没启用的插件」
恰恰是 install 最主要的用途。**

修法是补上 `KernelConfig.enabledPlugins`（null = 全部启用），把两件事彻底分开：

- **发现** = 「目录里已经装了什么」（磁盘事实）
- **启用** = 「这次启动跑哪些」（用户意图）

必需插件（`builtin && required`）不受启用集限制，永远启动。

> **值得记的一点**：140 个测试里没有一个能发现这个缺口。
> 因为它们全都是「先 boot、再 install」的顺序，而演示是「目录里有一个被禁用的插件」。
> **当一个功能在演示里第一次被真正使用时，才会暴露出单元测试看不见的缺口** 
> 这也是为什么「把东西建出来」不能永远往后推。

### 23.5 演示现在的样子

```
第一幕：真实数据（不再是写死的字符串）
  计划： 6 周通过软考中级
    [ ]   逾期  第 1 章 计算机组成
    [ ]         第 2 章 操作系统
    [ ]         第 3 章 网络基础
    [x]   逾期  整理错题  (已投入 45 分钟)

第二幕：另一个插件读到这些数据（消费者出现了）
  install() -> 成功，状态 ACTIVE
    [summary] 今日概览 | 2 项待办  1 项逾期  已投入 45 分钟
    [timer]   专注计时 | 还剩 18:20

第三幕：现场写一份 JSON，新插件立刻生效
    [summary] 今日概览 | 2 项待办  1 项逾期  已投入 45 分钟
    [streak]  连续 7 天 | 今天还没打卡
    [timer]   专注计时 | 还剩 18:20
```

三种插件跑在同一条生命周期上：

| 插件 | 是什么 | 数字从哪来 |
|---|---|---|
| `core.plan` | 必需内置插件，KV 实现 `PlanService` |  |
| `dev.summary` | 普通插件，**只通过 Service 读数据，不碰存储** | **真的，从存储里算出来的** |
| `dev.focus` / `ai.streak` | 磁盘上的两份 JSON | 写死的（声明式插件还没有数据绑定） |

`dev.summary` 声明了 `dependsOn = [core.plan]`，所以它 `init()` 时 `PlanService` 一定已就绪 
**依赖顺序不是约定，是内核按拓扑排序执行的结果**。

### 23.6 还差什么

1. **卡片是静态快照。** `dev.summary` 的数字在 `init()` 时算一次就定住了。
   做成随数据变化刷新属于 UI 层的职责（Compose 里用 Flow 订阅），不该塞进内核的 Slot 契约。
2. **声明式插件还没有数据绑定。** 三个机制里 Service 和存储都完成了，只剩那个纯字符串的求值器。
3. **没有界面。** 现在还是控制台。

### 23.7 交付

测试：`:kernel` 114 + `:plugin-dsl` 12 + `:features:plan-core` 10 = **140 条**，
外加上一轮补的 `EnabledPluginsTest` 4 条已计入 kernel。
`./gradlew check` 全绿，`./gradlew :demo:run` 可直接看上面那段输出。

---

## 24. 接上 Compose（v0.8 -> v0.9）：第一次有窗口

### 24.1 新增模块

```
:ui-kernel       Compose 渲染层：把插件贡献画到界面上
:app:desktop     桌面应用：真实的任务列表 + 插件卡片 + 本地持久化
```

`:ui-kernel` 只做一件事：把 `ExtensionRegistry` 里的贡献渲染出来。
它**不认识 Plan 和 Task**，也不认识任何业务概念  和内核一样守领域中立。

### 24.2 一个被编译器逼出来的架构结论

在控制台渲染器 `SlotHost` 里，单张卡片出错只降级它自己，因为有 `try/catch` 兜着。
到 Compose 里我照做，编译器直接拒绝：

```
Try catch is not supported around composable function invocations.
```

这不是能绕过的小事，它把插件贡献**分成了两个信任级别**：

| | `TextSlot` 这类数据型 | `ComposeSlot` 这类代码型 |
|---|---|---|
| 本质 | 只是数据 | 在你的 UI 里跑任意代码 |
| 出错后果 | 顶多显示得难看 | **整个窗口一起崩** |
| 能不能校验 | 能 | 不能 |
| 能不能让 Agent 生成 | 能 | 不能 |
| 谁可以用 | 任何人 | **只应该是第一方插件** |

**结论：第三方插件（含 Agent 生成的）只能提交数据，由宿主渲染；只有第一方插件才允许提交 composable。**

这条限制反过来印证了最初的设计判断  设计文档里写过「UI 用 Schema 描述，由内核渲染器渲染」，
当时只是觉得那样更容易热更新。现在看，它同时是**唯一能被任意第三方安全注入的形态**。

一个能被任意插件代码注入的 UI，本来就不该存在。

### 24.3 WSLg 的四个坑（工程记录，换台机器都会遇到别的）

1. `C:\Users\<你>\.wslconfig` 里写着 `guiApplications=false`  **WSLg 根本没启动**，
   `/mnt/wslg` 是个空目录，没有 X socket，没有 weston 进程。改成 `true` + `wsl --shutdown` 才起来。
2. WSLg **没有硬件 OpenGL**。Skiko 默认去建 GL 上下文，报
   `RenderException: Cannot create Linux GL context`。要 `SKIKO_RENDER_API=SOFTWARE_FAST`。
3. WSLg 的虚拟屏只有 **640480**，写死 980780 的窗口会被裁掉一半  窗口尺寸必须按屏幕算。
4. WSLg 用 **XWayland**，X 根窗口没有合成画面，**截根窗口只能得到全黑**。
   这不是应用的问题，是截图方法的问题  于是有了 24.4。

### 24.4 用离屏渲染验证界面

界面能不能画出来，纯逻辑测试验证不到。但 Compose Desktop 的 `ImageComposeScene`
可以把整个界面**离屏**渲染成 PNG  **不需要显示器**。

它同时是测试和产出物：

```
尺寸        : 900 x 700
不同颜色数  : 473     （空白图会是 1～2 种）
非白像素占比: 100.00%
```

测出来的图在 `app/desktop/build/ui-shot.png`。

这条技巧值得记住：**在没有图形环境的地方做 UI 开发，「能不能渲染」和「渲染成什么样」
是两个可以分开验证的问题**，而后者不一定需要那块屏幕。

### 24.5 应用现在的样子

```

  Trellis                                          
  目标：我的第一个计划                                     
                                                          
  待办 2   今天到期 0   逾期 0   已投入 45 分钟            

    
   [ 加一个番茄钟 ]  [ 复习昨天 ]       插件贡献的卡片   
    

  待办                                                     
     第 1 章 计算机组成        [+25 分钟]  [删除]         
     整理错题  已投入 45 分钟   [+25 分钟]  [删除]         
                                                          
   [ 新增一条待办                        ]  [ 添加 ]      

  内核 ACTIVE  插件 3 个  数据 ~/.trellis/store.json

```

数据是真的：关掉应用再打开，任务、完成状态、投入时间都还在
（`FileStorageBackend` 落在 `~/.trellis/store.json`）。
往 `~/.trellis/plugins/` 里丢两份 JSON，卡片就会出现  不用重启，更不用编译。

### 24.6 应用层测试覆盖了什么

7 条测试，覆盖了界面的**全部输入**：

- 内核启动、初始计划自动创建
- 增删任务、勾选完成、统计联动
- **数据活过重启**（关掉再打开，任务和投入时间都在）
- 第一方插件贡献的 `ComposeSlot` 出现在挂载点上
- **往目录里丢两份 JSON，插件被加载**
- **一个坏插件不会让 App 打不开**

界面本身只剩「画出来」这一件事  而那件事由 24.4 的离屏渲染守着。

### 24.7 还差什么

1. **界面不会自动刷新插件的改动。** 现在是每秒轮询一次。
   正确做法是领域层发变更通知（Flow），界面订阅  那是 `PlanService` 要新增的一项能力。
2. **卡片是静态快照。** `dev.summary` 那种「init 时算一次」的数字不会自己更新。
3. **声明式插件还不能绑数据。** 只有 Service 和存储完成了，缺那个纯字符串的求值器。
4. **`PluginContext` 里没有 `scope`。** 设计文档 4.4 写了它，一直没实现 
   插件想做后台定时（比如到点提醒）目前做不到。

---

## 25. 插件页的「上次用」（v0.9）

插件列表原来只能回答两个问题：**这是谁、它活着没**。回答不了第三个：
**我上次用它是什么时候**。主题最明显：它不产出卡片，只在插件页占一行名字，
用户分不清自己是不是还开着它、上次切换是不是刚才。

### 25.1 只记动作，不猜状态

`app/desktop/.../PluginUsageStore.kt`：`plugin-usage.json` 里一张
`插件 id -> 毫秒时间戳` 的表。只在两处写：

- `AppRuntime.applyTheme()` 成功换到某个插件主题时，记它的 owner；
- `AppRuntime.enablePlugin()` 把停用的插件启用回来时，记它自己。

启动时如果 `ui.json` 里已经选着某个插件主题，也会记一次 —— 那一刻它确实在用。
**不做推断**（比如「主题的表里出现了这个 id 就算用过」）：动作是一次性的、可解释的。

### 25.2 显示规则

悬停折叠行时，名字右边多一句，`PluginsScreen.usageHint()`：

| 情况 | 文案 |
| --- | --- |
| 正在用这个插件主题 | `正在使用 · 3 分钟前` |
| 有主题但没用 | `上次用：昨天 18:02` |
| 提供了主题、从没用过 | `还没用过` |
| 普通插件从没用过 | 不显示（悬停不该让布局跳） |

时间写法：一分钟内「刚刚」、一小时内「N 分钟前」、当天「今天 HH:mm」、
前一天「昨天 HH:mm」、更早「MM-dd HH:mm」。

### 25.3 为什么不塞进 `store.json`

`store.json` 是插件的 KV 数据；「上次用」是**界面层的习惯**。
插件删了、数据清了，这条记录也不该跟着丢（用户还想知道它上次是什么时候用的）。

测试：`PluginUsageTest` 钉住时间写法、未来时间、坏文件、BOM、重启后还在，
以及四种悬停文案。

## 26. 生命周期审计（v0.9）

删插件、装插件、停用、启用、清回收站——这些动作以前只在界面上发生，
事后没人知道是谁、在什么时候、删了什么。`PluginUsageStore` 记的是「用过没」，
不是「谁动过插件」。

### 26.1 一条流水：`~/.trellis/audit.log`

JSONL，一行一个动作：

```json
{"at":1792989999000,"actor":"user","action":"remove","plugin":"ai.mood-checkin",
 "detail":"删除了：文件进了回收站，数据 7 天后清掉","outcome":"completed"}
```

| 字段 | 说明 |
| --- | --- |
| `at` | 毫秒时间戳 |
| `actor` | `user`（界面点的）/ `agent`（插件管理员）/ `system`（定时清理） |
| `action` | `install` `enable` `disable` `remove` `purge` `purge_expired` |
| `plugin` | 插件 id（没有就空） |
| `detail` | 一句人话，成功与失败都写清原因 |
| `outcome` | `completed` / `failed` |

保留最近 500 条，超了丢最老的。写文件用「先写 tmp 再 rename」，
写到一半断电也不会留半行 JSON：读的时候坏行直接跳过。

### 26.2 谁在写

- `Kernel` 的 `install/disable/remove/enable/purgeNow/purgeExpiredData` 各写一条；
  `enable()` 内部复用 `installLocked(..., announce = false)`，避免一次启用记两条。
- 界面来的动作 `actor = USER`；插件管理员工具来的 `actor = AGENT`（`KernelPluginInstaller`）。
- 定时清回收站 `actor = SYSTEM`，action = `purge_expired`。

### 26.3 在哪看

诊断页上半「最近操作」：最近 20 条，按时间倒序，显示
「你/管理员/系统 · 动作 · 插件 · 时间 · detail」，失败的行标红。
下半是原来的「内核错误」。

### 26.4 它不是

不是撤销、不是版本历史、不是数据备份。审计只回答「发生过什么」，
不负责把东西变回去；撤销仍在各动作自己的 5 秒窗口里。

测试：`kernel/.../LifecycleAuditTest`（三种 actor、成功/失败文案、找不到插件）
与 `app/desktop/.../FileAuditLedgerTest`（重启后读回、容量截断、坏行跳过）。
