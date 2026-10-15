# Trellis

多端（Android + 桌面）、插件化的学习计划设定与追踪应用。

- 需求：见 `DESIGN.MD`
- 架构：见 `docs/kernel-design.md`（内核设计文档 v0.9）

## 当前进度

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 | 模块骨架 + PluginManifest + 空 boot | 完成 |
| M1 | Registry + Resolver + 状态机 | 完成 |
| M2 | 扩展点 + Slot + 热装热卸 | 完成 |
| M3 | 轨道 A：声明式插件（纯 JSON 插件） | 完成 |
| M4 | Service 扩展点（跨插件通信）+ I3 串行化 | 完成 |
| M5 | **Capability + 审批门 + manifest 指纹** | **完成** |
| M6 | **存储 + 命名空间隔离（I4）** | **完成** |
| M7 | **领域层 + core.plan（第一个真实消费者）** | **完成** |
| M8 | 快照 + 回滚 | 未开始 |
| M8 | **接入 Compose Multiplatform（第一次有窗口）** | **完成** |
| M9 | `:sdk-api` 独立成模块（I7） | 未开始 |
| M10 | 领域变更通知（替掉轮询） | 未开始 |
| M11 | 声明式插件的数据绑定 | 未开始 |
| M12 | 诊断面板 | 未开始 |

## 模块

```
:kernel             纯 Kotlin 内核，零平台依赖（KMP commonMain），不认识任何业务概念
:sdk-domain         领域契约：Plan / Task / PlanService / 排程策略扩展点
:features:plan-core PlanService 的实现（required 内置插件），数据存在 ctx.kv 里
:plugin-dsl         轨道 A：把目录里的 JSON 变成真正跑起来的插件
:ui-kernel          Compose 渲染层：把插件贡献画到界面上
:app:desktop        桌面应用（真实可用的那个）
:demo               控制台演示，跑 `./gradlew :demo:run`
```

依赖方向：`:features:plan-core -> :sdk-domain -> :kernel`，而**内核绝不依赖领域**。

后续：`:sdk-api` / `:ui-kernel` / `:platform-*` / `:app:*`

## 构建与测试

需要 JDK 17。Gradle 用 wrapper（已指向国内镜像）。`./run.sh` 会自动定位 JDK。

```bash
./run.sh check                      # 148 个测试 + 架构断言
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

```bash
./run-app.sh
```

数据落在 `~/.trellis/store.json`，插件目录是 `~/.trellis/plugins/` 
往里丢两份 JSON（`manifest.json` + `main.dsl.json`），重启应用就能看到新卡片。

> WSL 下需要 WSLg（`C:\Users\<你>\.wslconfig` 里 `guiApplications=true`）+
> `SKIKO_RENDER_API=SOFTWARE_FAST`（`run-app.sh` 会自动设）。
> 没有图形环境时，`./run.sh :app:desktop:test` 里的 `UiRenderTest` 会把界面离屏渲染成 PNG。

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
