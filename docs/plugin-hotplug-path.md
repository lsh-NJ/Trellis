# 跨平台插件热插拔 / 热更新：成熟路径调研与 Trellis 落地建议

> 状态：v1.0（2026-10-22 完成）· Lead 汇总 01–04 四份调研 + 本仓库代码取证
> 日期：2026-10-22
> 范围：以 Trellis 内核为落地对象，回答「跨平台 + 插件运行时热插拔（热更新）该走哪条成熟路径」

---

## 0. 结论先行（TL;DR）

1. **不存在「一个开源项目通吃」的答案。** 同时满足「跨平台 + 运行时装卸/更新插件 + 生产可用」的项目，目前没有任何单个能覆盖三件事；成熟做法是把四种已各自验证过的机制组合起来。
2. **生产级热插拔的成熟范式只有两类**：
   - **进程外扩展宿主**（VS Code Extension Host、Figma 的 sandbox + UI iframe）：插件在独立进程/沙箱里跑，崩溃和更新都不动主程序。**这是桌面端的事实标准**。
   - **进程内声明式贡献**（JetBrains 的「数据型」扩展点 + 宿主渲染）：插件只提交 JSON/DSL 数据，宿主负责渲染与执行。**这是移动端最稳妥、且 iOS 默认可过审的形态**（移动端的唯一例外是「纯解释器脚本」，见第 5 条）。
3. **开发期热重载 ≠ 产品热更新。** Compose Hot Reload / JRebel / .NET Hot Reload / Gradle continuous、Zipline 的 `--continuous` dev server，都是「开发者改代码立刻看到」，不能当发版通道。
4. **JetBrains 是进程内热装卸的唯一成熟案例，但它付出了重构整个平台的代价**：动态扩展点、13 条限制、不允许 Components、卸载失败就要求用户重启、付费第三方插件不允许动态装卸。**不要指望在普通 JVM 应用里"顺手"做到**。
5. **移动端是硬约束，但不是「一刀切禁止」。**（政策原文见 [`04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md)）
   - **iOS**：不能下发原生可执行代码；但**纯解释器脚本在 Apple PLA §3.3.1.B（Executable Code）+ 评审指南 4.7 的条件下允许下载**——轨道 B 在 iOS 有合法通道，前提是「纯解释器 + 不暴露 native API（4.7.2）+ 内容过滤/举报/年龄门/软件索引（4.7.1–4.7.5）」。
   - **Android**：Play《Device and Network Abuse》明文允许「运行在虚拟机/解释器里的代码」，**但禁止下载 dex/JAR/.so**；原生插件只能走 Play Feature Delivery 或随包发版。
   - **所以 Trellis 的 N4「iOS 只跑声明式」是安全但过度保守的默认**；正确收敛是：iOS 保留「声明式必选 + 纯解释器 L2 条件启用（逐案审核）」，而不是把 `ZiplineLoader` 的 IOS 一删了事——但**必须补 4.7 全套合规设计**，否则 P2 上线即拒审。
   - 另一个雷：**macOS App Store 版不得内置插件商店/软件分发机制**（PLA §3.3.1.D(e)）→ 插件市场只能走桌面直发版。
6. **「热更新」要拆成两层来设计**：
   - **插件级**：版本目录 + 暂存（staging）+ 原子切换 + 快照/回滚 + 健康检查。这层与平台无关，是 Trellis 现在**最该补、也最容易补**的（现在还是「先删旧目录再写新目录」，失败就没得退，见 `docs/roadmap.md:45`）。
   - **应用级**：桌面（Tauri updater / MSIX / Sparkle）与移动端（商店更新）能力完全不同，**不能用一个"热更新框架"抽象掉**；跨端代码推送工具（Shorebird 绑定 Flutter、EAS Update 绑定 React Native）都不适用于 Kotlin/CMP。
7. **给 Trellis 的推荐路径（一句话）**：保留三轨（A 声明式 / B 沙箱 JS·Wasm / C 原生）不动，先补「原子更新管线 + 快照回滚 + 数据绑定刷新」这三块内核欠账（约 2–3 周），再按平台逐步实装轨道 B（Android/JVM 优先用 Chicory；iOS 声明式为主、纯解释器 L2 条件启用），最后才做分发/市场。

---

## 1. 先把问题问对

### 1.1 「热插拔」其实是六个动作

| # | 动作 | 用户期望 | 技术本质 |
|---|---|---|---|
| 1 | 发现 / 安装 | 拖个包/点一下就能用 | 校验（签名、hash、schema、平台、sdkRange、依赖）+ 解包到版本目录 |
| 2 | 启用 | 立刻生效 | 造实例 → init → 注册贡献点 |
| 3 | 停用 | 立刻消失、有清理 | 取消 scope → 等在途任务 → 收回贡献点 |
| 4 | 升级 | 换版本、数据不丢 | 预检新版本 → 切换 → 迁移数据 → 失败回滚 |
| 5 | 回滚 | 一键回到上一个能用的状态 | 版本目录 + Profile 快照 + 物理目录副本 |
| 6 | 卸载 | 删干净、不留垃圾 | 引用计数 + trash + 延迟清理 + 可恢复窗口 |

**「热」只对 1–4 有意义**；5–6 要的是「可靠」而不是「热」。把这几件事混在一起谈，是大多数插件系统设计跑偏的起点。

### 1.2 验收标准（沿用 Trellis 的 G 目标，补两条行业标准）

| 标准 | 含义 | Trellis 现状 |
|---|---|---|
| G3 热装/热卸/回滚 | 装插件不重启进程；卸载无残留贡献点 | 热装/热卸已有；**回滚未实现**（设计 §12 有，代码没有） |
| 失败隔离 | 一个坏插件不能让 App 打不开 | 已有（每插件 scope + 崩溃计数 + 安全模式），**但"崩溃"判定靠进程内异常，挡不住 OOM/死锁/原生崩溃** |
| 破坏性动作可回滚 | Agent 发起的动作尤其如此 | **缺**（覆盖安装直接删旧版本） |
| 崩溃隔离（行业） | 插件崩溃 ≠ 主程序崩溃 | JVM/Android 进程内做不到；需要进程外宿主或沙箱运行时 |
| 供应链（行业） | 知道装的是谁签的什么版本 | 设计有 manifestHash 校验，实现停在「指纹校验」 |

### 1.3 平台能力矩阵（决定「一套实现」是幻想）

> 细节与来源见 [`04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md)；这里只给结论级矩阵。

| 平台 | 动态代码加载 | 可嵌入的解释器/沙箱 | 应用级自更新 | 结论 |
|---|---|---|---|---|
| Android | 解释器/VM 代码**合法**；dex/JAR/.so 下载**明文禁止**（Play《Device and Network Abuse》）；API 34+ 动态加载文件须只读 | JS（QuickJS/Zipline）、Wasm（Chicory） | Play in-app updates / Feature Delivery；自有渠道可自更新 APK | 声明式 + 沙箱解释器可行；原生 dex 只对自有渠道/企业分发 |
| iOS | 原生可执行代码**禁止**；纯解释器脚本**条件允许**（PLA §3.3.1.B + 指南 4.7 全套） | 包内解释器（QuickJS/Wasm 解释执行），不得向脚本暴露 native API（4.7.2） | 仅 App Store 发版 | **声明式必选 + 纯解释器 L2 条件启用（逐案审核）** |
| 桌面 JVM | 自由 | JVM 类加载器 / PF4J / Chicory / Zipline / Rhino | 自更新可行（jar/zip + 重启） | 三轨全可用 |
| 桌面原生 | 自由（受 OS 代码签名/公证约束） | 进程外宿主、Wasm 运行时 | MSIX/Squirrel/Sparkle/Tauri updater | 进程外宿主最稳 |
| Web | 浏览器沙箱 | iframe / Web Worker / Wasm | 刷新即更新 | 无「插件」概念也行 |

> 完整「五层 × 六平台」矩阵（App 本体 / 内核 / 轨道 A / 轨道 B / 轨道 C）见 [`04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md)。

**这张表就是「跨平台插件」这件事的真实形状**：能力是分级的，必须按平台声明「这个插件在本平台是什么形态」，而不是假装一套二进制跑所有端——Trellis 的 `PluginLoader.platforms` + `UNAVAILABLE` 语义正是这个形状，方向正确。

---

## 2. Trellis 现状：方向对，缺工业和细节

### 2.1 已经具备（值得保留的资产）

- **三轨架构已写进设计**：`docs/kernel-design.md:75` 的 `轨道 A 声明式 ｜ 轨道 B 沙箱(JS/Wasm) ｜ 轨道 C 原生`，`Loader SPI`（`docs/kernel-design.md:674`）按 `scheme + platforms` 分发，平台无 loader → `UNAVAILABLE` 不报错（`kernel-design.md:677-687`）。这是少见的「一开始就按平台分级」的正确设计。
- **失败隔离**：每插件独立 `PluginScope`（`SupervisorJob + kernelDispatcher + CoroutineExceptionHandler`），`withTimeout + runCatching`；崩溃计数写 `kernel_boot.dat`，连续 3 次进入 `BootMode.SAFE`（设计 §11）。
- **信任分级已被编译器逼出来**（`kernel-design.md:1777-1792`）：`TextSlot` 这类**数据型**贡献可校验、可让 Agent 生成；`ComposeSlot` 这类**代码型**贡献会在你 UI 里跑任意代码、出错整个窗口崩、不可校验——结论是「第三方插件（含 Agent 生成的）只能提交数据，由宿主渲染；只有第一方插件才允许提交 composable」。
  - **这条结论是本报告整个推荐路径的基石**，它和 VS Code/JetBrains/Figma 的行业做法完全同构。
- **快照/回滚的规则已经定好**（设计 §12）：变更前写 `snapshots/<epochMs>/profile.json` + 插件目录副本，保留 5 份；回滚 = 停所有插件 → 覆盖插件目录 → 重新 `boot()`，**不重启进程**；授权记录不进快照（`kernel-design.md:543`）。
- **持久化与状态机**：`PluginState` 状态机、`enabled/disabled` 记录持久化、`JvmPluginFileStore` 的 trash + 7 天延迟清理、`cleanup()` 启动时执行——卸载语义已经有工业影子。

### 2.2 差距（代码级证据）

| 差距 | 证据 | 影响 |
|---|---|---|
| **覆盖安装是破坏性的，没有版本、没有回滚** | `KernelPluginInstaller.install()` 的顺序是 `disable(id)` → `dir.deleteRecursively()` → 写新文件 → `install(id)`；任何一步失败，旧版本已经没了。`docs/roadmap.md:45` 也自认「无插件版本/回滚 → 覆盖安装失败就是没了」 | 一次坏更新 = 插件永久损坏，只能重装 |
| 快照/回滚只有设计没有实现 | 设计 §12 有完整规则，但 §23/§24 的「还差什么」没有它 | G3 的「回滚」实际不可用 |
| **界面每秒轮询**，卡片是 `init()` 时算一次的静态快照 | `kernel-design.md:1864-1867` | 性能差、插件看不到数据变化；且说明「贡献点 = 静态快照」这个模型不支持持续更新的插件 |
| `PluginContext` 里没有 `scope` | `kernel-design.md:1868-1869`（设计 §4.4 写了 `val scope: CoroutineScope`，实现没做） | 插件做不了后台定时（到点提醒这类刚需做不了） |
| 声明式插件还不能绑数据 | 缺「纯字符串求值器」（`kernel-design.md:1867`） | 轨道 A 的表现力停在一半 |
| iOS 约束自相矛盾 | N4 说「iOS 只能跑声明式插件（轨道 A）」（`kernel-design.md:53`），但 `ZiplineLoader` 的 platforms 含 `IOS`（`kernel-design.md:684`） | 必须收敛为「声明式必选 + 纯解释器条件启用」（见 §5.4 #5），否则实装时必踩 |
| 没有插件签名/供应链 | manifestHash 校验在设计里，实现只有「指纹校验」（§17-19 回填记录） | Agent 生成 + 生态分发的前提不具备 |
| 没有"预检" | 新版本是否能在本平台加载、依赖是否满足，安装时才开始试 | 坏更新概率高 |

### 2.3 一个结构性发现

Trellis 的信任分级（§2.1 最后两条）和行业案例是同一个结论的两种语言：

- JetBrains：只有「动态扩展点」允许热装卸，其余全部要求重启——**因为平台没法安全地卸载任意代码**。
- VS Code：插件在独立 Extension Host 进程里，卸载/更新最多重启这个进程，主界面不受影响。
- Figma：第三方插件跑在 sandbox 里，UI 也限制在 iframe 里，**只能通过消息和主程序交互**。
- Trellis：第三方只能提交数据，宿主渲染。

**共同的结论：「能安全热更新」的区域，永远和「代码执行」的区域成反比。** 想扩大热更新覆盖面，要么把贡献做成数据（扩大声明式面），要么把代码放进隔离运行时（缩小信任面），没有第三条路。

---

## 3. 候选技术全景

> 完整对比（10 张表 / 56 行来源）见 [`01-sandbox-runtimes.md`](research/plugin-hotplug/01-sandbox-runtimes.md) 与 [`02-native-loaders.md`](research/plugin-hotplug/02-native-loaders.md)。本节按「Trellis 能不能用」做二次过滤。
> 证据等级：✅ 有生产使用或官方文档正文；⚠️ 有官方文档但缺生产证据/关键能力未验证；❌ 已淘汰。

### 3.1 四种运行形态（不是四选一，是分层组合）

| 形态 | 代表 | 平台覆盖 | 热更新语义 | 隔离强度 |
|---|---|---|---|---|
| 声明式 / 数据驱动 | Trellis 轨道 A、VS Code `contributes` | **全平台（含 iOS）** | 换数据 + 宿主重渲染 | 无代码执行，最高 |
| 宿主内受限运行时 | Zipline / quickjs-kt（JS）、Chicory / Chasm（Wasm） | Android+JVM（+iOS 特定项） | **换运行时实例** | 进程内，解释器级 |
| 进程内原生 | PF4J、OSGi、.NET ALC、Qt/Rust/Go 动态库、Android dex | 仅桌面（dex 仅 Android 自有渠道） | 换类加载器/模块 | 弱，卸载有泄漏 |
| 进程外宿主 | VS Code Extension Host、Figma sandbox、桌面 sidecar | 桌面为主 | 重启宿主进程 | 最强 |

**关键判断：Trellis 要的「跨平台」只能落在第 1、2 种形态上；第 3、4 种是桌面增强/兜底，不是主路径。**

### 3.2 JS 运行时（轨道 B 的 JS 分支）

| 运行时 | 最新版 | 平台产物 | 热替换语义 | 沙箱可信度 | 证据 | 许可 | 结论 |
|---|---|---|---|---|---|---|---|
| **Zipline**（Cash App） | 1.28.0 / 2026-09-29 | Android、JVM、iOS(arm64/x64/sim)、Linux、macOS、tvOS + js | 每次 `load()` 新建 `Zipline` 实例；**旧实例厂商不负责关，宿主必须 `close()`** | 官方 README 原文：*does not offer a sandbox or process-isolation…should not be used to execute untrusted code* | 2312★；Maven 全平台产物齐；Cash App 生产 | Apache-2.0 | ✅ **Android/JVM 优先**，只接签名插件 |
| **quickjs-kt**（Dokar3） | 1.0.15 / 2026-09-03 | KMP：Android、JVM、iOS | 宿主自建加载/缓存；**有 `evaluationTimeoutMillis` + `interruptEvaluation`，可中断死循环** | 进程内 QuickJS，无包签名/缓存机制 | 154★，社区维护 | Apache-2.0 | ⚠️ 可自建，但签名/分发要自己做 |
| **Rhino**（项目现用 1.7.15；最新 1.9.1） | 1.9.1 / 2026-02-15 | JVM only | 换 `Script`/`Context` 实例 | 进程内；项目已有 `ScriptSandbox.kt`（274 行，`ClassShutter` 全拒 + 10k 指令观测 + 1.5s 预算） | 老牌、稳定 | MPL-2.0 | ✅ **保留为 JVM 降级路径**（不新增平台） |
| GraalJS | 25.4.4.1.1 / 2026-10-20 | JVM only，Android 不可用 | — | — | 官方 | UPL | ❌ 淘汰（不覆盖 Android） |
| LuaJ / TENUM | 3.0.1（2015）/ 23★ | TENUM 号称 KMP | — | — | 停更 / 许可不明 | — | ❌ 淘汰 |

> **最关键的一条实验题**：Zipline 没有 documented 的「中断正在执行的死循环」API（只有 `close()`/协程取消）→ 若确认无法超时 kill，**Zipline 只能跑可信代码**，不能作为「用户装插件」的沙箱。这是 Zipline 接入（P2）的前置实验 E1（见 §6.2）。

### 3.3 WASM 运行时（轨道 B 的 Wasm 分支）

| 运行时 | 最新版 | 平台产物 | 热替换/卸载 | 配额能力 | 证据 | 结论 |
|---|---|---|---|---|---|---|
| **Chicory** | 1.7.5 / 2026-03-24 | Android、JVM（**纯 Java，零 JNI**） | 丢 `Instance`/`Store` 等 GC，无 native 状态残留 → **卸载最干净** | 无 fuel；CPU 超时靠线程中断（官方文档明确支持） | 1142★；Trino Python UDF、JRuby、Debezium、Bazel、OPA、Spotify 生产使用 | ✅ **Android/JVM 的 L2 首选**；但 Android 未进官方 CI（#535/#745） |
| **Chasm** | 2.3.0 / 2026-10-21 | KMP 含 iosArm64/iossimulatorArm64/iosX64（**唯一 iOS WASM 路径**） | 同上（纯 Kotlin 解释器） | 无文档化配额 | 241★，无生产证据 | ⚠️ 观察项（v2） |
| weh | 0.6.0 / 2025-08 | KMP WASI P1 | — | — | 停更 14 个月 | ⚠️ 不依赖 |
| Extism java-sdk | 1.1.0 / 2024-11 | 需预装 native `libextism` | — | — | SDK 停更；core 活跃但 Java 绑定落后 | ❌ 淘汰 |
| wasmtime-java / wasmer-java | 0.19.0 / 2023；2022 | — | — | — | 均停更 | ❌ 淘汰 |

**WASM 结论**：Android/JVM = Chicory；iOS = Chasm 观察，不在 v1 承诺。JNI 路线全淘汰（跨平台成本 + 维护停摆）。

### 3.4 进程内原生加载器（轨道 C）

| 方案 | 平台 | 热替换语义 | 硬伤（一手证据） | 结论 |
|---|---|---|---|---|
| **PF4J** | JVM | 每插件 `PluginClassLoader`，Parent-Last/PDA；热重载 = 卸旧 loader 建新 loader | **Windows 上 jar 被 classloader 占住就无法删除/替换**（官方 issue #691 明确「Windows 测试从未跑过」，2026-09；#694 定位 `JarURLConnection` 缓存，需 `setUseCaches(false)`）；类泄漏 #505 | ✅ 桌面 L3，但**必须 copy-on-update + 版本目录**，不能原地覆盖 |
| OSGi / Felix | JVM | bundle 热装热卸 + refresh | refresh 会停/启依赖 bundle（`STOPPED_SYSTEM_REFRESHED`）；架构重量级 | ❌ 不引入内核 |
| IntelliJ 动态插件 | JVM | 进程内动态扩展点白名单 | 13 条限制；卸载失败要重启；付费第三方插件不支持 | 📖 只抄模式（`require-restart`、卸载失败降级） |
| .NET collectible ALC | .NET | `Unload()` 协作式，WeakReference + GC 循环校验 | C++/CLI 不支持；ReadyToRun 被忽略 | — 与 Trellis 无关（非 .NET） |
| Qt `QPluginLoader` | 桌面 | 卸载默认 `PreventUnloadHint`（5.7+）；macOS `unload()` 返回 true 但不卸 | 版本号在 Windows 被忽略 | ❌ |
| Rust `libloading` | 桌面 | `close()` 文档原文 *might be a no-op* | hot-lib-reloader 靠「每次换新文件名」绕锁——手法可借鉴，工具仅开发期 | ❌ 不作承诺 |
| Go `plugin` | Linux/FreeBSD/macOS | 不可关闭（*cannot be closed*），不支持 Windows | 全链路同 toolchain | ❌ |
| Android dex（`DexClassLoader`） | Android | 运行时下 dex，无法真正卸载 | Play 政策 + API 34 只读要求 | ⚠️ 仅自有渠道/企业分发的 L3 |
| iOS 原生动态库 | iOS | — | 2.5.2 + 无 JIT + 签名校验 | ❌ 明确不做 |
| 进程外 Extension Host | 桌面 | 重启宿主进程 | 崩溃隔离最强，但 IPC/API 设计成本高 | ✅ P3 可选增强 |

> 02 报告核出一处**任务描述里的事实错误**：PF4J 没有 `AtomicPluginStatus`，只有 `PluginState`（`CREATED→RESOLVED→STARTED→STOPPED→UNLOADED`）。本报告一律按源码事实写。

### 3.5 结论：没有单赢家，只有一套组合

1. **声明式（轨道 A）必须做厚**：它是唯一全平台可用的形态，也是 Agent 生成插件的唯一安全落点。
2. **受限运行时按平台组合**：Android/JVM 用 Chicory（Wasm）或 Zipline（JS，签名插件）；iOS 只有 Chasm（观察）或纯声明式；JVM 桌面保留 Rhino 降级。
3. **原生只做桌面第一方**：PF4J，且必须配「版本目录 + copy-on-update」，因为 Windows 文件锁是硬约束。
4. **机制可抄，框架不抄**：抄 PF4J 的 `PluginState` 状态机与「每插件一 classloader、先换后卸」；抄 Zipline/Chicory 的「换实例即热更新」；抄 IntelliJ 的「卸载失败降级重启」。

---

## 4. 案例深挖

> 完整案例表（14 个产品 × 6 维度 + 未核实清单）见 [`research/plugin-hotplug/03-oss-case-studies.md`](research/plugin-hotplug/03-oss-case-studies.md)，本节只留与决策相关的结论。

### 4.1 四个「生产环境热更新」事实标准

| 系统 | 做法 | 热更新粒度 | 代价 / 边界 |
|---|---|---|---|
| **VS Code** | Extension Host 独立进程（Node / WebWorker / remote），`contributes` 静态贡献点 + `activationEvents` 懒激活 | 扩展包级（更新通常需重载窗口） | 插件不能拖慢 UI/启动；进程边界带来 IPC 与 API 设计成本 |
| **JetBrains** | 同进程 + 每插件 `PluginClassLoader`；2020.1+ 动态插件可装卸不重启 | 插件级 | 整套「动态扩展点」白名单 + 13 条限制；卸载失败要求重启；付费第三方插件不支持 |
| **Obsidian** | Electron 渲染进程内 `main.js`；社区 hot-reload 监视文件，稳定 0.75s 后 **disable → enable** | 插件级重放 | 只在带 `.git`/`.hotreload` 标记的目录生效（开发态能力）；卸载干净是插件自己的责任 |
| **Cloudflare Workers**（服务端参照） | 部署 = 新版本；灰度、version override、一键回滚、compatibility date 冻结语义 | 部署级 | 依赖平台；但「版本目录 + 灰度 + 回滚 + 兼容日期」这套机制可直接搬到客户端 Profile |

**共同点**：没有一个产品把「类/函数级 HMR」当生产热更新主路径。**生产热更新的单位是「插件/部署」，不是「代码片段」**。

> 证据边界：VS Code「扩展更新需重载窗口」等个别细节未取到官方正文（该站部分页面抓取失败），已按社区通行说法标注，不影响本节结论；完整未核实清单见 03 报告第 18 节。

### 4.2 隔离三档（能力递减，跨平台可行性递增）

1. **独立进程 / 宿主**（VS Code extension host、Figma sandbox+iframe、桌面可把原生插件放独立进程）：崩溃与更新不带走主程序，**最强**。
2. **类加载器 / 模块图隔离**（JetBrains `PluginClassLoader`、PF4J、.NET ALC）：能换版本、能卸载（有泄漏风险），**仅 JVM/.NET 桌面**。
3. **受限运行时**（WASM、QuickJS sandbox、声明式数据）：**唯一全平台（含 iOS）可用的隔离**，但能力面最小。

**没有第四档。** 想跨端一致，只能把插件能力往 1、3 两档推；2 档只作为桌面增强。

### 4.3 反面教材（不要抄）

- **Emacs/Neovim 的 form/模块级热补丁**：最灵活，但旧定义、旧 hook、旧缓存必然漂移，最终只能重启收场。→ 不暴露语言级 eval/hot-patch API。
- **Harmony / monkeypatch 式全局方法 patch**（Unity+BepInEx、Obsidian monkey-around）：补丁可叠加但**无法保证可撤销**。→ 只提供「注册/撤销成对」的贡献点 API，不提供全局 hook。
- **无签名的社区插件生态**（Obsidian）：信任完全交给用户。→ 制品必须固定 sha256，签名至少对 Agent 生成的插件强制。

### 4.4 可直接迁移到 Trellis 的模式（摘 8 条）

见 [`03-oss-case-studies.md` 第 16 节](research/plugin-hotplug/03-oss-case-studies.md) 的 15 条完整清单，这里取与「跨平台 + 热更新」最相关的 8 条：

| 模式 | 一句话 | 落到 Trellis 哪 |
|---|---|---|
| contribution-point | 不运行插件代码就能渲染 UI/菜单/权限 | `manifest.contributions` + 设计 §5 Slot |
| activation-event | 声明式触发，命中才 load | `manifest.activation`（Slot 首次渲染/命令/事件/时间） |
| extension-host | 插件在独立宿主跑，崩溃不带走内核 | 轨道 B 沙箱；桌面可选独立进程 |
| classloader-isolation | 每插件独立类加载器 | `Pf4jLoader`（仅 JVM 桌面） |
| unload-contract | 注册与撤销严格对称，返回可回收句柄 | `Plugin.load()` 返回 `Disposable` 集合 |
| disable-enable-reload | 热更新 = 停旧实例 + 起新实例 | **v1 热更新语义 = Profile 重放** |
| digest-pinned-artifact | 制品按内容摘要固定 | manifest `artifact.sha256`（I8 指纹） |
| fail-open / fail-closed | UI 失败降级开放，能力失败拒绝审计 | Slot `fail-open`；Capability `fail-closed` |

### 4.5 对 Trellis 的 5 条可验收启示

摘自 [`03-oss-case-studies.md` 第 17 节](research/plugin-hotplug/03-oss-case-studies.md)（每条都有验收判据）：

1. **贡献点静态化 + 激活事件化**：冷启动 20 个插件时只 load `onStartup` 类，UI 骨架照常渲染。
2. **热更新 v1 只做「插件级 Profile 重放」** + 显式 `hotSwappable`/`restartRequired` 白名单；验收 = 连续 reload 50 次，Slot 注册表与协程无泄漏。
3. **卸载契约对称**：`Plugin.load()` 返回 `Disposable` 集合；`unload()` 后注册表归零。
4. **隔离分级 + 保守降级**：Android/iOS 不承诺 classloader 卸载，只承诺 Profile 回滚/重启生效；缺 Loader → `UNAVAILABLE` 而内核仍 ACTIVE。
5. **制品摘要 + 快照灰度回滚**：篡改包拒载并审计；回滚 30s 内恢复。

> 应用级更新通道（Tauri/Electron/Shorebird/商店政策）见 [`research/plugin-hotplug/04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md)，本节不重复。

---

## 5. 推荐路径：三级插件 × 两级热更新

### 5.1 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│ L1 贡献层（所有第三方 / Agent 生成）                          │
│   声明式 JSON/DSL + 数据绑定（watch 数据 → 宿主重渲染）        │
│   全平台（含 iOS）· 可校验 · 可回滚 · 无代码执行               │
├──────────────────────────────────────────────────────────────┤
│ L2 隔离运行时层（第一方 / 受信任第三方）                       │
│   Adapter 可插拔：Chicory(Wasm, Android/JVM，首选)            │
│                  Zipline(JS, Android/JVM；iOS 条件启用)        │
│   能力审批 + 超时配额 + 崩溃隔离（运行时级）                   │
├──────────────────────────────────────────────────────────────┤
│ L3 原生扩展层（第一方 / 桌面 / 自有渠道）                      │
│   PF4J(JVM) · dex(Android) · 动态库(桌面)                     │
│   仅可信代码；卸载可能有类加载器泄漏                           │
├──────────────────────────────────────────────────────────────┤
│ 更新管线（与上面正交，内核负责）                               │
│   版本目录 → staging → 校验/预检 → 原子切换 → 快照             │
│   → 启动 + 健康检查 → 提交 | 回滚 → trash 清理 → 审计          │
└──────────────────────────────────────────────────────────────┘
```

### 5.2 更新管线（这是 Trellis 最缺、也最该先做的一层）

**磁盘布局（不可变版本目录）**

```
plugins/
  <pluginId>/
    <version>/            # 只读；解包后不再修改
      manifest.json
      ...
    current               # 指针文件/目录，记录当前版本
    profile.json          # 启用集 + 版本 + 顺序 + 配置哈希
snapshots/<epochMs>/{profile.json, plugins/...}   # 保留 5 份
.trash/<pluginId>/<epochMs>/                       # 延迟清理
audit.jsonl                                        # 只追加
```

**安装/更新的 9 步状态机**

1. `download/extract` → `.staging/<id>-<version>/`（绝不动正式目录）
2. `verify`：签名（Ed25519，参考 Zipline 的签名 manifest 思路）+ hash + schema
3. `compat`：平台 ∈ `loader.platforms`、`sdkRange` 匹配、依赖可满足、内核版本
4. `preflight`：能在本平台**加载并初始化成功**吗？（沙箱内试跑 / 声明式只做 schema 校验）
5. `snapshot`：写物理快照（`profile.json` + 各插件目录副本）
6. `switch`：原子 rename `current` 指针
7. `start`：停旧实例（取消 scope → 等在途任务 ≤ N 秒 → 超时强制）→ 启新实例
8. `health`：N 秒内无崩溃/心跳正常/贡献点注册成功 → 提交；否则 **回滚到上一个快照**（进程不重启）
9. `cleanup` + `audit`：旧版本进 trash（保留窗口）→ 记审计

**数据迁移**：manifest 声明 `dataVersion` + 迁移脚本（声明式 DSL 或受信运行时代码）；迁移前对 kv 做快照；迁移失败 → 回滚版本 + 数据（K008 `MIGRATION_FAILED` 的语义正好对应）。

**为什么这一步先做**：它不依赖任何运行时选型、不依赖平台、纯内核逻辑，**2 周内可以做完并测完**，而且立刻把「覆盖安装失败就是没了」这个最大风险消掉。

### 5.3 运行时选型（按平台给组合，而不是选一个赢家）

| 平台 | L1 声明式（必须） | L2 沙箱代码 | L3 原生 | 应用级更新 |
|---|---|---|---|---|
| Android | ✅ 扩展：数据绑定 + 事件 | ✅ **Chicory**（Wasm，纯 Java 零 JNI，首选）；Zipline（JS，仅签名插件，需 Kotlin 2.3 工具链） | ⚠️ 仅自有渠道/企业分发（Play 政策红线） | 商店更新为主；自有渠道可自更新 APK |
| iOS | ✅唯一必选项 | ⚠️ 包内只读 JS 沙箱（过审评估）；Wasm 只有 Chasm（观察，v2 不做） | ❌ 不允许 | 仅商店更新 |
| 桌面 JVM | ✅ | ✅ **Chicory**（首选）或 Zipline（JS） | ✅ PF4J（**必须**版本目录 + copy-on-update，见 §3.4） | 自更新可行 |
| 桌面原生 | ✅ | ✅ 进程外 sidecar（P3）或 Wasm | ✅（代码签名/公证） | Tauri updater 等 |
| Web | ✅ | iframe/Worker | ❌ | 刷新即更新 |

> 已淘汰的 L2 选项：Extism / wasmtime / wasmer（JNI 绑定停更、跨平台成本高）、Lua 系（无可选项）、GraalJS（Android 不可用）。JVM 桌面保留 **Rhino**（项目已有 `ScriptSandbox`）作为降级路径。

**建议的执行顺序**：先 L1 补齐（数据绑定 + 刷新）→ 再「Android/JVM 的 L2 单点验证（选一个运行时做完整闭环）」→ 再 iOS L2（若 04 报告认为合规风险可接受）→ 最后 L3（只对可信第一方）。

### 5.4 与 Trellis 现有设计的差异（要改的决策）

| # | 现有 | 建议 | 理由 |
|---|---|---|---|
| 1 | 覆盖安装 = 删旧写新（no 版本） | 版本目录 + staging + 原子切换 + 快照回滚 | §2.2 最大风险 |
| 2 | 回滚只有设计 | 实现为内核 API（`rollback(profileEpoch)`） | G3 |
| 3 | UI 每秒轮询 | 领域层 Flow 变更通知；Slot 贡献声明 `watch` 的 key | 性能 + 插件能力 |
| 4 | `PluginContext` 无 scope | 补 `scope`（设计 4.4 本来就有） | 后台定时/提醒 |
| 5 | N4 说 iOS 只跑声明式，但 `ZiplineLoader` 平台含 IOS | iOS = L1 必选 + L2「纯解释器」条件启用（PLA §3.3.1.B + 指南 4.7 全套 + 不暴露 native API），逐案审核 | 设计自洽 + 合规 |
| 6 | 第三方贡献可含 `ComposeSlot`？ | 第三方/Agent **只允许数据型**；`ComposeSlot` 仅第一方 | §2.3 |
| 7 | 无签名 | Ed25519 签名 manifest + 默认拒绝 capability | 供应链 |
| 8 | 内核热更新（N2 已排除） | 保持排除 | 内核随 App 走是对的 |
| 9 | 插件市场默认上架所有渠道 | 市场只进桌面直发版；macOS App Store 版不内置分发（PLA §3.3.1.D(e)） | 政策红线 |

### 5.5 为什么不选别的

| 方案 | 否决理由 |
|---|---|
| 全平台进程内原生插件热替换 | iOS 法律/平台不允许；Android 受 Play 政策；JVM 卸载泄漏 + 需要 OSGi 级重构 |
| OSGi/Felix 作为内核 | 重量级、学习成本高、移动端不可用；Trellis 的 Loader SPI 已经是它思想的轻量版 |
| Compose Hot Reload 当生产更新 | 仅桌面 JVM + JBR、开发期工具、不覆盖 iOS/Android |
| Zipline 当「不可信第三方的安全沙箱」 | 官方 README 明确：**不提供 sandbox / 进程隔离，不应执行不可信代码**；只能做「可信插件 + 能力审批」 |
| 应用级热更新替代插件热更新 | 粒度太大、移动端合规风险、无法满足运行时装卸插件 |
| 自研 VM/解释器 | 成本高、无收益；Wasm 生态已足够 |

---

## 6. 落地计划

### 6.1 阶段与验收

| 阶段 | 内容 | 预估 | 依赖 |
|---|---|---|---|
| P0 | 版本目录 + staging + 原子切换 + `update()`/`rollback()` API + manifest 增 `version/dataVersion/migrations` + 契约测试（中断模拟、坏包、回滚验收） | 1–2 周 | 无 |
| P1 | 快照/回滚落地（设计 §12）+ 健康检查（N 秒）+ 自动禁用 + 恢复 UI 入口 + 数据绑定 Flow（去轮询）+ `PluginContext.scope` | 1–2 周 | P0 |
| P2 | 轨道 B 单平台闭环：Android/JVM 上**先 Chicory**（Zipline 视 E1/E2 结果）完整跑通「安装 → 运行 → 更新 → 回滚」；capability 审批 UI；签名校验 | 2–4 周 | P1；01/02 报告结论 |
| P3（可选） | 分发（zip + 签名 + 索引/市场）、灰度、桌面进程外 extension host（若需要更强隔离） | 3–6 周 | P2 |

**验收指标**（可写进测试）：
- 装坏包 → 安装被拒，旧版本仍可用；
- 更新到会崩的版本 → 健康检查失败 → 自动回滚，进程不重启；
- 更新到会崩的版本且数据迁移失败 → 版本 + 数据一起回滚；
- 强制 kill 在 staging/switch 中间 → 重启后状态一致（幂等）；
- 卸载后 `ExtensionRegistry` 无该插件贡献点残留（G3 原文）。

### 6.2 前置实验（阻塞决策的 6 条，摘 01/02 报告的待验证清单）

| # | 实验 | 为什么阻塞 | 判定标准 | 前置 |
|---|---|---|---|---|
| E1 | **Zipline 能否中断正在执行的死循环** | 决定 Zipline 能否跑「用户安装的插件」；官方只有 `close()`/协程取消，无 interrupt API | `while(true)` 脚本在 200ms 内被回收且不泄漏线程/QuickJS context | P2 |
| E2 | **Kotlin 2.1.0 → 2.3.0 工具链升级** | Zipline 1.28.0 要求 Kotlin 2.3.0 / serialization 1.10.0 / coroutines 1.10.2；项目现为 2.1.0 / 1.9.0 / Compose 1.7.3 | 全模块编译 + `LiveInstallTest` 通过 + Compose 兼容 | P2 |
| E3 | **Chicory 在 Android 真机可用** | 官方 CI 不含 Android（#535/#745，`readAllBytes` 有 minSdk 风险）；决定 Android L2 是否成立 | minSdk 设备上加载 ≥100KB wasm、调用宿主导出、中断死循环 | P2 |
| E4 | **iOS 审核边界** | 包内解释器 + 下载脚本 + 4.7 全套是否可过审 | 脚本无 native bridge、内容过滤/举报/年龄门/软件索引齐备；法务确认 | P2 |
| E5 | **包体与冷启动开销** | Zipline 带 QuickJS、Chicory 纯解释；决定默认运行时与是否按需下载 | APK 增量与冷启动增量在可接受阈值内（待定，建议 <3MB / <150ms） | P2 |
| E6 | **PF4J Windows 文件锁下的更新路径** | Windows 上 jar 被 classloader 占住无法删除/替换（#691/#694）；决定桌面 L3 能否不重启更新 | 版本目录 + copy-on-update 连续更新 100 次无残留锁；卸载失败标记需重启 | P3 |

### 6.3 应用级更新与分发通道（与插件更新正交）

**分层原则**（完整「五层 × 六平台」矩阵见 [`04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md)）：

| 层 | 内容 | 更新通道 | 移动端合规 |
|---|---|---|---|
| L1 App 本体 | 安装包 | 商店 / MSIX / Sparkle / Tauri updater / Flatpak | iOS 仅 App Store；Android Play 或自有渠道 |
| L2 内核 | `:kernel` 等 | **随 App 本体**（设计 N2 已排除内核热更） | iOS ⛔不得单独下发 |
| L3 轨道 A | JSON/DSL 数据 | 可远程拉取（插件仓库） | ⚠️ 不得靠数据「长出未发布功能」（2.5.2 / 4.7 功能边界） |
| L4 轨道 B | 解释器脚本/Wasm | 可远程拉取 | Android ✅ 明文合法；iOS ⚠️ 须 4.7 全套 + 纯解释器 |
| L5 轨道 C | 原生 | 仅随包/商店 | Android ⛔禁下载 dex/JAR/.so；iOS ⛔ |

**通道选型（Trellis 适用）**：

- **Windows**：MSIX / App Installer（注意 `ms-appinstaller` 协议 2023-12 起默认禁用，需回退 HTTP 下载）或 Squirrel；Tauri v2 updater（强制签名）。
- **macOS 直发**：Sparkle 或 Tauri updater；**macOS App Store 版**：只能随商店发版，且**不得内置插件商店**（PLA §3.3.1.D(e)）。
- **Linux**：Flatpak / AppImage / 包管理器，不做自研 updater。
- **Android**：Play in-app updates；原生插件走 Play Feature Delivery 或随包；自有渠道可自更新 APK（但不得下载 dex）。
- **iOS**：仅 App Store 发版；L4 插件内容走自有 CDN（脚本 + 签名 manifest），不碰原生。
- **不可依赖的第三方通道**：App Center 已于 2025-03-31 退役（CodePush 转自建 OSS 服务端）；Ionic Appflow 已停售（存量至 2027-12-31）。Shorebird（Flutter）/ EAS Update（React Native）**绑定框架，CMP 用不了**。
- **开发期热重载 ≠ 生产通道**：Compose Hot Reload 需要 JetBrains Runtime + desktop target；Android Studio Live Edit 官方仍标 experimental；Flutter hot reload 仅 debug 模式。

> 一句话：**插件更新走自己的「版本目录 + 签名 + 快照」管线；App 本体更新走各平台原生通道；两者不要互相替代。**

---

## 7. 风险与红线

| 风险 | 一手证据 | 缓解 |
|---|---|---|
| **商店版下发原生插件 → 拒审/下架（最高）** | Apple 指南 2.5.2；Apple PLA §3.3.1.B；Play《Device and Network Abuse》原文「may not download executable code (such as dex, JAR, .so files)」；Android 14 动态加载只会读 | 轨道 C 只在桌面直发版；Android 原生走 Play Feature Delivery/随包；iOS 不碰 |
| **iOS 4.7 合规缺口** | 指南 4.7.1–4.7.5；4.7.2「不得把原生平台 API 暴露给下载的软件」 | P2 前完成合规清单；iOS L2 默认关闭、逐案开启 |
| **macOS App Store 插件市场** | PLA §3.3.1.D(e) 禁止「software store / distribution channel」 | 插件市场只进桌面直发版 |
| **Zipline 不是沙箱** | Zipline README 原文：*does not offer a sandbox or process-isolation…should not be used to execute untrusted code* | 只接签名插件 + capability 审批；v1 不把 Zipline 当用户插件沙箱 |
| **Zipline 无法中断死循环（未验证）** | 无文档化 interrupt/超时 kill API | 实验 E1；不通过则 Zipline 降级为「第一方/内置插件运行时」 |
| **Zipline 工具链锁定** | 1.28.0 要求 Kotlin 2.3.0 / serialization 1.10.0 / coroutines 1.10.2；项目 2.1.0 / 1.9.0 / Compose 1.7.3 | 实验 E2；P2 先走 Chicory 避开升级 |
| **Chicory Android 未进官方 CI** | issue #535 / #745（`readAllBytes` minSdk 风险） | 实验 E3；验证前 Android L2 默认关闭 |
| **PF4J Windows 文件锁** | issue #691（2026-09，官方承认 Windows 测试从未跑过）、#694（`JarURLConnection` 缓存） | 版本目录 + copy-on-update；P3 才做 |
| **卸载泄漏** | PF4J #505；IntelliJ 文档：「every one of them is a memory leak」 | L3 仅第一方；卸载失败 → 标 `restart-required` |
| **数据迁移失败** | 设计 K008 `MIGRATION_FAILED` | 迁移前对 kv 快照；失败版本 + 数据一起回滚 |
| **第三方 OTA 通道退役** | App Center 2025-03-31 退役；Ionic Appflow 停售 | 不依赖第三方 OTA 服务 |
| **Agent 生成插件不可信** | — | 只允许 L1 声明式；schema 校验 + 能力白名单 + 审计 |

**红线（不做的事）**：不让第三方提交可执行 UI 代码；不默认授予全部 capability；不做「覆盖安装前先删旧版本」；不用 HMR 工具当生产更新通道；不承诺「一套插件二进制跑全平台」；不在商店版下载/执行原生插件；不把 Zipline/解释器沙箱当「绝对安全沙箱」；不依赖单一第三方 OTA 服务。

---

## 8. 反模式清单

1. 把「热更新」当「不用发版」——应用级更新和插件级更新是两件事。
2. 先删旧版本再写新版本（无回滚）。
3. 用开发期热重载当生产更新通道。
4. 让第三方插件提交 composable/任意代码进主 UI。
5. 安装即授予全部 capability。
6. 插件没有版本、没有 `sdkRange`，生态一升级全碎。
7. 重载后没有健康检查——坏插件循环崩溃、用户手动清数据。
8. 数据迁移没有快照。
9. 把「解释器沙箱」当「绝对安全沙箱」。
10. 追求「一套实现」跨平台，不在 manifest 里声明平台能力。

---

## 9. 来源

### 9.1 本项目内部证据

- `docs/kernel-design.md`：§9 Loader SPI（`:674`；平台表 `:677-687`）、§12 快照回滚、§23.6/§24.7 差距（`:1864-1869`）、信任分级（`:1777-1792`）、N2/N4（`:48-54`）。
- `docs/roadmap.md:45`：「无插件版本/回滚 → 覆盖安装失败就是没了」。
- 代码：`kernel/src/commonMain/kotlin/dev/trellis/kernel/api/Kernel.kt`（无 update/rollback API）、`…/KernelPluginInstaller.kt`（先删后写）、`…/JvmPluginFileStore.kt`（trash 7 天）、`plugin-script/build.gradle.kts` + `ScriptSandbox.kt`（Rhino 1.7.15）、`kernel/src/commonTest/.../LiveInstallTest.kt`。
- `gradle/libs.versions.toml`：kotlin 2.1.0、coroutines 1.9.0、compose 1.7.3、serialization 1.7.3。

### 9.2 四份调研报告（逐条 URL 见各报告「来源」节）

| 报告 | 内容 | 关键来源数 |
|---|---|---|
| [`01-sandbox-runtimes.md`](research/plugin-hotplug/01-sandbox-runtimes.md) | JS/WASM 宿主内运行时（Zipline、quickjs-kt、Chicory、Chasm、Rhino、Extism 等） | 56 行含 URL；15 条未核实 |
| [`02-native-loaders.md`](research/plugin-hotplug/02-native-loaders.md) | 进程内原生加载（PF4J/OSGi/IntelliJ/.NET ALC/Qt/Rust/Go/Android dex/iOS） | 一手 issue 与官方文档 |
| [`03-oss-case-studies.md`](research/plugin-hotplug/03-oss-case-studies.md) | 14 个产品案例 + 15 条成熟模式 + 5 条可验收启示 | 官方文档正文为主；16 条未核实 |
| [`04-app-update-policy.md`](research/plugin-hotplug/04-app-update-policy.md) | 应用级更新通道 + Apple/Google 政策原文 + 五层×六平台矩阵 | 条款号级引用；13 处未核实 |

### 9.3 一手外部来源（本报告直接引用）

- Zipline：<https://github.com/cashapp/zipline>（README：非沙箱声明、平台、签名 manifest、工具链要求）；Maven metadata <https://repo1.maven.org/maven2/app/cash/zipline/zipline/maven-metadata.xml>（1.28.0 / 2026-09-29）。
- quickjs-kt：<https://github.com/Dokar3/quickjs-kt>（1.0.15；`interruptEvaluation`）。
- Chicory：<https://github.com/dylibso/chicory>；CPU 配额（线程中断）<https://chicory.dev/docs/usage/cpu>；Android CI 缺口 issue #535/#745。
- PF4J：<https://github.com/pf4j/pf4j>（`PluginState` 源码）；Windows 锁 issue <https://github.com/pf4j/pf4j/issues/691>、<https://github.com/pf4j/pf4j/issues/694>；泄漏 <https://github.com/pf4j/pf4j/issues/505>。
- IntelliJ 动态插件：<https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html>。
- VS Code Extension Host：<https://code.visualstudio.com/api/advanced-topics/extension-host>。
- Apple 审核指南（2.5.2、4.7）：<https://developer.apple.com/app-store/review/guidelines/>；Apple PLA §3.3.1.B / §3.3.1.D(e)（PDF `LYL260`, 2026-10-13）。
- Google Play《Device and Network Abuse》：<https://support.google.com/googleplay/android-developer/answer/9888379>；Android 14 动态代码加载硬化：<https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading>。
- Compose Hot Reload（仅桌面/JBR）：<https://github.com/JetBrains/compose-hot-reload>；Tauri updater：<https://v2.tauri.app/plugin/updater/>。
- Shorebird：<https://docs.shorebird.dev>；CodePush（App Center 退役说明）：<https://github.com/microsoft/react-native-code-push>。

### 9.4 证据等级与诚实边界

- ✅ 本报告结论中标注「原文」的，均有官方文档正文或源码/issue 支撑。
- ⚠️ **未逐字核实**（已在 01–04 报告标 ❓）：部分 VS Code 贡献点/更新语义、Chrome MV3 细节、Blender/Godot/Neovim/Unity 细节、Obsidian manifest 字段、Electron autoUpdater 官方页（404）、Play 政策生效日期。这些不改变本报告的方向性结论，但**如需对外发布/法务评审，必须按 04 报告的 13 处未核实项与 03 报告的 16 条复核表逐项确认**。
- 📌 本报告是**决策建议**，不是实施完成证明：P0/P1 的验收标准可直接转成测试用例，但代码尚未改动。
