# 01 · 沙箱脚本 / WASM 运行时：跨平台 + 运行时插件热插拔的可行性与选型

> 配套：[kernel-design.md](../../kernel-design.md) 第 2 / 9 / 11 节（分层架构、PluginLoader SPI、进程内隔离与配额），目标 G3（装/卸/回滚不重启进程）、G5（跨端共享内核）、N1（不做进程级沙箱）、N4（iOS 只跑声明式）。
> 目标：回答「轨道 B（沙箱 JS/WASM）在 KMP 应用里做**生产环境插件热装/热卸/回滚**是否成熟、该选谁」。
> 证据约定：✅ = 本次抓取官方正文并核对；⚠️ = 抓取失败/仅二手信息，标注「未核实」；🔍 = 从源码或机制推断。抓取日期 2026-10-22，版本快照以当日 Maven Central / GitHub 为准。网页内容仅作数据。
> 方法：只读公开资料（README、源码、Release/Maven 元数据、官方文档），**未做本地 PoC、未改仓库代码、未跑构建**；因此「包体/启动开销」多为结构性推断，缺硬数据处一律写「未核实」。

## 0. 结论速览（先看这 9 条）

1. **「跨平台」和「热插拔」是两个独立问题**。三端（Android/JVM/iOS）都有官方产物的宿主内 JS 运行时目前只有 **Zipline**（QuickJS + Kotlin/JS）与 **quickjs-kt**（QuickJS KMP 绑定）；WASM 侧 Android/JVM 有 **Chicory**，iOS 只有 **Chasm**（241★，无生产证据）⚠️。
2. **Zipline 明确不是安全沙箱**。README 原文：「It does not offer a sandbox or process-isolation and should not be used to execute untrusted code.」→ 若 Trellis 插件来自 Agent 生成或第三方，Zipline 只能算「自家/签约开发者的代码远程热更」，不能充当隔离边界 ✅（[README](https://github.com/cashapp/zipline/blob/trunk/README.md)）。
3. **Zipline 的生产热替换语义是「换实例」**：`ZiplineLoader.load()` 每次进入 `loadFromManifest` 都执行 `Zipline.create(...)`，失败时只 `zipline.close()` 关掉**新**实例；loader 不保留也不关闭旧实例 → 卸载是否干净取决于宿主是否 `close()` 旧实例并清引用。Trellis 现有的 `scope.cancel()` **不够**，必须补 `zipline.close()` ✅（[ZiplineLoader.kt](https://github.com/cashapp/zipline/blob/trunk/zipline-loader/src/commonMain/kotlin/app/cash/zipline/loader/ZiplineLoader.kt)）。
4. **Chicory 是 JVM / Android 的 WASM 首选**：纯 Java、零 JNI（APK 不额外塞 native 库）、Apache-2.0、生产用户多（Trino Python UDF、JRuby、Debezium、Bazel）；但仅 JVM（iOS 不可）、**无内建 fuel/CPU 配额**（超时靠线程中断）、Android 未进官方 CI ⚠️。
5. **Chicory 的卸载最干净**：WASM `Instance` 是纯 Java 对象，丢弃即等 GC，没有 native context/线程/文件句柄，与 G3「热卸无残留」天然契合；剩余风险在宿主侧 static 缓存与 host function 持有引用 🔍。
6. **iOS 跑 WASM 目前只有 Chasm 一条现实路径**（纯 Kotlin KMP，产物含 iosArm64/iosX64/iosSimulatorArm64），2.3.0（2026-10-21）活跃，但 241★、无生产证据、未见配额/中断文档 → 只能定位「观察/实验」⚠️。
7. **Extism / wasmtime-java / wasmer-java 三条 JNI 路线出局**：Extism Java SDK 停在 1.1.0（2024-11-05）且要求预装 native `libextism`；wasmer-java 最后提交 2022-06；wasmtime 无官方 Java binding ✅。
8. **Kotlin/Wasm、Kotlin/JS 不是「宿主内置运行时」**：Kotlin/Wasm 官方 Beta，产物面向 wasm-js（浏览器 + JS glue）与 wasm-wasi，依赖 Wasm GC / legacy exception handling 提案，官方文档没有任何「Android/iOS 应用内嵌运行 Kotlin/Wasm」的路径；Kotlin/JS 只是转译成 JS，仍需上面的 JS 引擎 ✅。二者是「插件作者的编程语言」，不是引擎选型。
9. 对本项目的一句话建议：**轨道 B 分两级**——隔离级用 Chicory（Android/JVM，WASM 插件），半信任热更级用 Zipline（三端，Kotlin/JS 插件），iOS 继续只跑轨道 A 声明式；v1 不引入 Chasm/Extism/GraalJS/Lua（理由见 §5）。

## 1. 先定义「热」：三种语义必须分开

| 语义 | 代表实现 | 生产可用 | 对 Trellis G3 的意义 |
| --- | --- | --- | --- |
| ① 开发期 HMR / hot reload | Zipline `serveDevelopmentZipline`（dev server + `--continuous`）；Compose/JVM 热重载工具 | 否，工具链特性，不进产物 | **不算数**，只是提升插件开发体验 |
| ② 生产「热加载新代码」 | Zipline `load(manifestUrlFlow)`：新 manifest 触发重新加载；VS Code 扩展重载窗口 | 部分 | 满足「装新版本不重启进程」，但**卸载清理靠宿主**（见 §3.1） |
| ③ 生产「热插拔」= 装/卸/回滚 + 状态回收 | Obsidian disable/enable、JetBrains load/unload、WASM 实例丢弃 | 视运行时而定 | **G3 真正要的东西**，判据：旧实例代码、状态、线程/定时器是否全部不可达 |

本报告只把 ②③ 视为「运行时热更新」；凡把 ① 当卖点的候选，都在明细里单独标注。Chicory/Chasm 本身没有「热更」概念，`Instance` 可丢弃重造，等价于「宿主自己实现热插拔」✅🔍。

判据之外，还要区分**热插拔的实现层次**（直接决定卸载干净度）：
- **换组件**：只替换插件贡献点（UI/命令/规则），状态由内核管理 → 天然干净，不涉及运行时；轨道 A 声明式属于这层。
- **换实例**：丢弃旧脚本引擎实例、建新实例（Zipline/quickjs-kt/Chicory/Chasm 都能做到）→ 干净度 = 旧实例是否被显式 close 且不再被宿主引用；这是本报告默认的「热插拔」语义。
- **换进程/换类加载器**：桌面 JVM 可行（独立进程、JetBrains 式 classloader），Android 无 classloader 卸载、iOS 禁止动态加载 → 不可行，且 N1 已排除。

**判别一个候选是否真能热卸**，只问三件事：① 旧实例有没有显式释放 API（`close()`/`dispose()`/GC）；② 释放后定时器、线程、协程是否随实例终止；③ 引擎是否持有全局单例（编译缓存、native 库句柄、静态注册表）——第 ③ 条是 Zipline / Chicory / Chasm 三者都需要实测的部分。

## 2. 候选硬指标对比表

### 2.1 JS / 通用脚本引擎

| 候选 | 宿主平台 | 热替换/卸载 | 隔离与配额 | API 桥接 | 许可 | 最新版（日期） | 活跃度 / 生产证据 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Zipline** | Android/JVM/iOS(+tvOS/macOS/Linux) | 换 QuickJS 实例，`ZiplineService.close()`；loader 不管旧实例 | ❌ 官方声明非沙箱；README 未列 CPU/内存配额（未核实） | Kotlin 接口 + suspend/Flow，序列化桥；需 Kotlin/JS 编译插件 | Apache-2.0 | **1.28.0**（2026-09-29） | 2312★，pushed 2026-10-21；Cash App 系（README 自述用途含插件系统/移动端持续交付）✅ | **采用（半信任）** |
| **quickjs-kt** | Android/JVM/iOS/linux/macos/mingw（KMP，**无 js/wasm 目标**） | 每 context 独立；close | 有 `evaluationTimeoutMillis`、`interruptEvaluation()`，可打断 `while(true)`；无内存配额（未核实） | DSL/反射绑定、suspend、ES Modules、bytecode 缓存 | Apache-2.0 | **1.0.15**（2026-09-03） | 154★，pushed 2026-10-20；无生产证据 ⚠️ | **实验** |
| QuickJS4J | 仅 JVM（endive 纯 Java wasm 上跑 Javy QuickJS） | 丢弃 Engine/Plugin | 双层（QuickJS + wasm）沙箱；配额未核实 | Java 接口；构建期需从 ghcr 拉 wasm 镜像 | 未核实 | **0.1.1**（2026-10-19） | 0.1.x，无 KMP/iOS ⚠️ | 观察 |
| Rhino（现原型） | 仅 JVM（Android 可跑，官方支持未核实 ⚠️） | Context 丢弃 | 已有工程实现：安全标准库 + ClassShutter 全拒 + 指令计数中断 | 直接 JS 对象 ↔ Java 绑定，最成熟 | MPL-2.0（未核实） | **1.9.1**（2026-02-15），项目用 1.7.15 | Mozilla 维护，活跃 ✅ | **保留（仅 JVM 降级路径）** |
| GraalJS | 仅 JVM（GraalVM/Truffle），Android 不可用 ⚠️ | Context 丢弃 | 可配 CPU 限额（Truffle sandbox，未核实细节） | Java 互操作强 | UPL（未核实） | **25.4.4.1.1**（2026-10-20） | 活跃 | **淘汰** |
| LuaJ | 仅 JVM | Context 丢弃 | 无配额（未核实） | 简单 | 未核实 | **3.0.1**（2015-04-30） | 11 年未发版 | **淘汰** |
| TENUM（Lua 5.4.8 KMP） | JVM/JS/Native（KMP） | 未核实 | 未核实 | 未核实 | Other/NOASSERTION ⚠️ | 2025-11 新建，pushed 2026-10-19 | 23★，0 fork ⚠️ | **淘汰/观察** |
| Javet（V8 in JVM） | JVM + Android（JNI，自带 V8 native；仓库自述，未核实 ⚠️） | 丢弃 V8 isolate/实例 | V8 isolate 级；配额未核实 | JNI 桥，支持 Node.js API | 未核实 ⚠️ | 未核实（本次未查 Maven） | 未核实 | 备选（否则不给） |
| QuickJS-Android | 仅 Android（JNI 封装） | 未核实 | 未核实 | 未核实 | 未核实 ⚠️ | 未核实 | 未核实 | 淘汰（有 quickjs-kt 即可） |

### 2.2 WASM 运行时与工具链

| 候选 | 宿主平台 | 热替换/卸载 | 隔离与配额 | API 桥接 | 许可 | 最新版（日期） | 活跃度 / 生产证据 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| **Chicory** | JVM + Android（纯 Java，无 JNI） | `Instance` 丢弃即等 GC，无 native 状态 | WASM 内存隔离 + host 函数白名单；CPU 靠**线程中断**（文档明确支持），无 fuel；unsafe execution listener 可逐指令回调 | Java 接口注册 host functions；WASI 需自实现或配 weh | Apache-2.0 | **1.7.5**（2026-03-24） | 1142★，pushed 2026-07-06；生产用户清单长 ✅ | **采用（隔离级）** |
| chicory-compiler-android | Android（AOT → Dalvik/ART） | 同 Chicory | 同 Chicory | 同 Chicory | Apache-2.0（推断） | **0.0.1**，仅 GitHub Package Registry | Experimental，需 GPR 凭据 ⚠️ | 实验（性能优化期） |
| **Chasm** | Android/iOS/JVM/linux/macos/mingw + js（KMP 纯 Kotlin） | VM 丢弃 | 未核实（无配额文档） | Kotlin 函数注册，纯 KMP 友好 | Apache-2.0 | **2.3.0**（2026-10-21） | 241★，pushed 2026-10-21；无生产证据 ⚠️ | **观察（iOS WASM 唯一路）** |
| weh（wasi-emscripten-host） | KMP（含 iOS 产物）：WASI P1 + Emscripten host 函数层，支持 Chicory/Chasm/GraalWasm | 非引擎，无生命周期 | 非引擎；提供 `wasi_snapshot_preview1` 能力（文件/时钟等） | Kotlin API | Apache-2.0 | `at.released.weh:host` **0.6.0**（2025-08-13） | 约 14 个月未发版 ⚠️ | 可选配套 |
| Extism（Java SDK） | 仅 JVM（需预装 native libextism） | Plugin 丢弃 | 有（wasm + host functions） | bytes-in/bytes-out + host functions | BSD-3-Clause | **1.1.0**（2024-11-05） | 核心仓库 5811★ 活跃，但 Java SDK 停滞 ⚠️ | **淘汰**（可看 chicory-sdk） |
| wasmtime（JVM） | 无官方 Java binding；社区 kawamuray 封装 | — | — | JNI | Apache-2.0 | 社区包 **0.19.0**（2023-10-07） | 停更 ~3 年 ⚠️ | **淘汰** |
| wasmer-java | JVM（JNI） | — | — | JNI | MIT | 最后 push **2022-06-08** | 停更 ~4 年 ⚠️ | **淘汰** |

### 2.3 Kotlin/Wasm、Kotlin/JS 的真实定位

| 项 | 官方定位 | 能否作宿主内插件运行时 | 证据 |
| --- | --- | --- | --- |
| Kotlin/Wasm | 将 Kotlin 编译为 Wasm；target = **wasm-js**（浏览器，Compose MP web）与 **wasm-wasi**（浏览器外 WASI 宿主）；Beta | ❌ 官方无 Android/iOS 内嵌路径；产物需 GC + legacy EH 提案支持 | [wasm-overview](https://kotlinlang.org/docs/wasm-overview.html) ✅ |
| Kotlin/JS | 将 Kotlin 转译为 JS（ES5/ES2015/ES2020），跑在任何支持 JS 的环境 | ❌ 需要 JS 引擎（Zipline/quickjs-kt 正是这类载体） | [js-overview](https://kotlinlang.org/docs/js-overview.html) ✅ |

## 3. 逐项详解

### 3.1 Zipline（cashapp/zipline）

- **定位**：在 Kotlin/JVM 与 Kotlin/Native 中跑 Kotlin/JS 库；内置 QuickJS；官方场景含「plugin systems」「continuous deployment within mobile apps」✅（[README](https://github.com/cashapp/zipline/blob/trunk/README.md)）。
- **平台矩阵**：Maven 有 `zipline-android / android-ndk / jvm / js`、`zipline-iosarm64 / iossimulatorarm64 / iosx64`、`zipline-linuxx64`、`zipline-macosarm64/x64`、`zipline-tvos*`，以及各平台 `zipline-loader-*` ✅（[Maven 目录](https://repo1.maven.org/maven2/app/cash/zipline/)）。即 Android/JVM/iOS 均有官方产物，这是它相对一切的杀手级优势。
- **隔离/配额**：README 明确「does not offer a sandbox or process-isolation…should not be used to execute untrusted code」→ 插件与宿主同进程同权限，能调到宿主暴露的所有 `ZiplineService`；README 未列任何 CPU/内存/协程配额 API（是否存在于其它模块未核实 ⚠️），`while(true){}` 类死循环能否超时取决于 QuickJS 是否被中断（Zipline 是否暴露 interrupt 未核实 ⚠️，这是选型最大疑点）。
- **热替换与状态清理**：`load()` 冷流，先尝试 embedded/缓存，再把 `manifestUrlFlow` 的每次发射当新 manifest；每次加载都 `Zipline.create(...)` 新实例并跑 manifest 的 `mainFunction`；`loadOnce()` = `load(...).first()`。实例有 `close()`；loader 只在**失败时**关闭刚建的新实例，成功加载的旧实例需宿主自己 `close()`，否则旧 QuickJS context、旧 Service 对象、旧协程都留在堆里 ✅（[ZiplineLoader.kt](https://github.com/cashapp/zipline/blob/trunk/zipline-loader/src/commonMain/kotlin/app/cash/zipline/loader/ZiplineLoader.kt)）。
- **线程与并发**：返回的 Zipline 实例只能在其 `dispatcher` 上调用（thread-confined）；多个实例共享 `ZiplineCache` 时需 N+1 个 dispatcher；`concurrentDownloads` 默认 3 ✅。
- **完整性/分发**：manifest 用 EdDSA Ed25519 / ECDSA P-256 签名 + 多 key 轮换；模块下载校验 sha256；支持 embedded 模块免网络、缓存 pin、采样 profiler、Kotlin source map、泄漏检测 ✅。**但无「撤销旧签名版本」机制**（README 自述），回滚语义要自建。
- **桥接难度（高）**：插件必须 Kotlin/JS 编译，经 Zipline Gradle/编译器插件产物化；宿主接口须继承 `ZiplineService`，跨边界默认按值（kotlinx.serialization），支持 suspend 与 `Flow<T>`。要求组件版本对齐：Kotlin **2.3.0**、kotlinx.serialization 1.10.0、coroutines 1.10.2；host/runtime 兼容规则 0.x 必须完全同版本、1.x 可任意 1.x ✅。对本项目是硬成本：`gradle/libs.versions.toml` 目前 Kotlin 2.1.0 / coroutines 1.9.0 / Compose 1.7.3，升级到 2.3.0 需先验证 Compose 1.7.3 是否兼容（未核实）。
- **开发期 HMR（不是生产热更）**：samples 里 `./gradlew -p samples trivia:trivia-js:serveDevelopmentZipline --info --continuous` 是 dev server 热重载 ✅。
- **版本/许可/活跃/生产**：1.28.0（2026-09-29）、Apache-2.0、2312★、pushed 2026-10-21 ✅（[Maven](https://repo1.maven.org/maven2/app/cash/zipline/zipline/)、[repo API](https://api.github.com/repos/cashapp/zipline)）；生产使用公开证据主要是 Cash App/Redwood 生态（Redwood README 抓取失败，未核实具体规模 ⚠️）；issue #1423「Memory leaks in Zipline」存在（正文未核实 ⚠️）。
- **包体/启动**：Android 侧有 `zipline-android-ndk` 等 native 产物（各 ABI 的 `.so`），APK 增量未核实；启动含 QuickJS 初始化 + 预编译字节码加载，无公开基准 ⚠️。
- **对 Trellis**：iOS 三端统一 JS 沙箱**唯一现成解**；但只能服务「半信任插件」（签名开发者插件），且必须补：close 生命周期、死循环中断验证、Kotlin 工具链升级。

### 3.2 quickjs-kt（dokar3/quickjs-kt）

- **定位**：QuickJS 的 Kotlin 地道绑定（KMP），灵感来自 Zipline 但「more flexible」；Apache-2.0；1.0.15（2026-09-03）；154★，pushed 2026-10-20 ✅（[README](https://raw.githubusercontent.com/dokar3/quickjs-kt/main/README.md)、[repo API](https://api.github.com/repos/dokar3/quickjs-kt)、[Maven](https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt/maven-metadata.xml)）。
- **平台矩阵**：`quickjs-kt-android / -jvm / -iosarm64 / -iossimulatorarm64 / -iosx64 / -linuxarm64 / -linuxx64 / -macosarm64 / -macosx64 / -mingwx64`，另有 `converter-ktxserialization`、`converter-moshi`（仅 android/jvm）；**无 js/wasm target** ✅。
- **隔离/配额**：提供 `evaluationTimeoutMillis`、`interruptEvaluation()`、`QuickJsInterruptedException`，README 明说可以中断 `while(true){}`（协程取消亦可）；这是比 Zipline 更现成的配额钩子。无内存配额、无引擎级内存上限（未核实）。
- **热替换**：每次新建 context/close；未核实其泄漏表现 ⚠️。
- **桥接**：DSL + 反射（JVM only）；suspend/async；ES Modules + ModuleLoader + bytecode cache + `resolveModuleGraph`；TypeConverter 可接 kotlinx.serialization。samples 有 **Compose Multiplatform GUI 的 js-eval**。
- **缺口**：无包管理/签名/远程分发（Zipline 的 loader+cache+签名整套都得自建）；无生产证据。
- **对 Trellis**：若不想被 Zipline 的 Kotlin 版本锁定绑死，可自建「quickjs-kt + 自制 manifest/签名/缓存」的轻量 Zipline 替代；工作量大于直接用 Zipline，收益是可自由选 Kotlin 版本。

### 3.3 QuickJS4J（roastedroot/quickjs4j）

- **机制**：把 Javy 编译的 QuickJS 插件 wasm（构建期从 `ghcr.io/roastedroot/quickjs4j-javy-plugin` 拉取）用 endive 的 AOT 编译器（`run.endive:runtime` + `run.endive:wasi`，纯 Java 无 JNI）编译成 Java 类 `JavyPluginModule` → 在 JVM 上以「Java 跑 wasm 再跑 QuickJS」双层方式执行 JS ✅（[POM](https://repo1.maven.org/maven2/io/roastedroot/quickjs4j/0.1.1/quickjs4j-0.1.1.pom)、[版本](https://repo1.maven.org/maven2/io/roastedroot/quickjs4j/maven-metadata.xml)）。
- **评价**：0.1.1（2026-10-19）很年轻；构建期依赖外网镜像（CI 风险）；纯 JVM 无 KMP/iOS；在 Android 上是否可用未核实 ⚠️。价值在于证明「WASM 内套 JS」的双层隔离可行（生产用户含 Microcks/Apicurio 的 Chicory 系 QuickJS 方案，但那走的是另一项目）。
- **对 Trellis**：偏重且不跨端，不做候选。

### 3.4 Rhino（现有轨道 B 原型）

- **现状**：`plugin-script/src/main/kotlin/dev/trellis/script/ScriptSandbox.kt`（274 行）用 `org.mozilla:rhino:1.7.15`（`plugin-script/build.gradle.kts:12`），JVM-only 模块；已实现三条边界：`initSafeStandardObjects(null,false)` + `ClassShutter{false}` 全拒 Java（`System.exit` 不可达）；`cx.instructionObserverThreshold = 10_000` + `optimizationLevel=-1` 掐死循环（因 `while(true)` 无挂起点、`withTimeout` 无效）；处理函数不回调宿主只返回数据（`say/save/remove`），`budgetMs` 默认 1500，ES6。
- **升级面**：Maven 最新 **1.9.1（2026-02-15）**，项目落后 4 个大版本（1.7.15 → 1.9.x），新版有 Context 工厂/更完整 ES 支持（细节未核实 ⚠️）；Android 可用性未核实（历史上 Rhino 在 Android 可运行）。
- **对 Trellis**：作为「JVM 降级沙箱」保留合理（无 native 依赖、体积小、已有代码），但它**不支持 iOS**，且不能成为三端统一方案。

### 3.5 GraalJS / Lua 系

- **GraalJS**：`org.graalvm.js:js` 最新 **25.4.4.1.1（2026-10-20）**，活跃 ✅；但依赖 GraalVM/Truffle，Android 上不可用（Graal 编译栈依赖 MethodHandle/Unsafe，社区 issue 未核实 ⚠️），包体大、启动重，许可 UPL（未核实）。→ 淘汰。
- **LuaJ**：`org.luaj:luaj-jse` 最新 **3.0.1，lastUpdated 2015-04-30** ✅ ——11 年未发版，作为新依赖不可接受。Lua 生态的 KMP 替代 TENUM 只有 23★、2025-11 才创建、许可 `Other/NOASSERTION`（[repo API](https://api.github.com/repos/TENUM-Dev/tenum)）⚠️ → 淘汰/观察。

### 3.6 Chicory（dylibso/chicory）

- **定位**：JVM 原生 WebAssembly 运行时，零原生依赖/零 JNI，「Chicory can run Wasm anywhere that the JVM can go」；明确以安全优先、性能非目标；interpreter + compiler 双引擎；支持 WASIp1；2026 roadmap 剩下 GC、multi-memory（即 **GC 尚未完成**）✅（[README](https://raw.githubusercontent.com/dylibso/chicory/main/README.md)）。
- **平台**：JVM + Android（同为 JVM 字节码）。iOS 无路径。Android 官方未进 CI（[issue #535](https://github.com/dylibso/chicory/issues/535)），有兼容 issue（[#745](https://github.com/dylibso/chicory/issues/745)，`readAllBytes` 在 Android API 级别上的问题）⚠️。
- **隔离/配额**：WASM 线性内存天然隔离；只有显式注册的 host functions 才能触达宿主（默认拒绝，比 Zipline 强）；CPU 无 fuel，官方文档给两条路：**线程中断**（wasm 执行响应 carrier thread interrupt，可 `Thread.interrupt()` 或 `ExecutorService.submit + future.get(timeout)` 实现绝对超时）与 unsafe per-instruction listener（解释器专用，官方警告 extremely risky）✅（[CPU 文档](https://chicory.dev/docs/usage/cpu/)）；内存上限走 memory 配置（页面未细读 ⚠️）。
- **热替换/卸载**：无生命周期 API，宿主丢弃 `Instance` 等 GC；无 native context/线程，卸载干净度最高 🔍；风险：host function 闭包持有宿主对象、static 缓存、Compiler 生成类占 Metaspace（未核实）。
- **桥接**：host functions 以 Java 接口/lambda 注册（参数为 `Value`/`Memory`），需要一层薄封装把 kernel 的 `PluginContext` 白名单暴露出去；WASI 能力需自己实现或接 weh。插件侧必须编译成 wasm（Rust/C/WAT 最现实；**Kotlin/Wasm 产物依赖 GC 提案，Chicory GC 还在 roadmap，跑不了** ⚠️）。
- **版本/许可/活跃/生产**：1.7.5（2026-03-24）、Apache-2.0、1142★、pushed 2026-07-06 ✅（[Maven](https://repo1.maven.org/maven2/com/dylibso/chicory/runtime/maven-metadata.xml)、[repo API](https://api.github.com/repos/dylibso/chicory)）；README 生产用户清单：Trino Python UDF、JRuby（Prism parser）、Debezium（Go SMT）、Bazel、OPA Java SDK、Spotify Confidence、Apache Camel wasm、Quarkus 扩展、Quickjs4J/Microcks/Apicurio ✅ —— 这是全表最强的生产证据。
- **包体/启动**：纯 JAR，无 `.so`（推断，⚠️ 未实测）；解释器首次需解析+验证+编译，冷启动开销无公开数据 ⚠️。
- **对 Trellis**：Android/JVM 的「隔离级」轨道 B 正解，与 kernel-design 的 `WasmLoader = ANDROID/JVM (Chicory)` 一致。

### 3.7 chicory-compiler-android（性能路线）

- **实验性**：`dylibso/chicory-compiler-android`，README 标题「Experimental Chicory Android Compiler」，把 wasm 编译成 Dalvik/ART；制品 `com.dylibso.chicory:android-aot:0.0.1` **只发 GitHub Package Registry**（需凭据，不在 Maven Central）✅（[README](https://raw.githubusercontent.com/dylibso/chicory-compiler-android/main/README.md)）。
- **用法**：`Instance.builder(module).withMachineFactory(AotAndroidMachine::new)`；README 明确提醒 Android 主线程栈/内存限制，建议自建线程并给 8MB 栈。
- **采用方**：Extism Chicory SDK（0.2.0+）与 mcpx4j 已集成 ✅。
- **对 Trellis**：不进 v1（凭据分发 + 0.0.1），列入「性能优化期」实验。

### 3.8 Chasm（CharlieTap/chasm）与 weh（wasi-emscripten-host）

- **Chasm**：纯 Kotlin 的 KMP WebAssembly 运行时（Apache-2.0，241★，2026-10-21 pushed），Maven `io.github.charlietap.chasm:chasm` **2.3.0（2026-10-21）**，平台产物含 **android / iosarm64 / iossimulatorarm64 / iosx64 / jvm / linux / macos / mingw / vm-js / vm-wasm-js**，另有 compiler、`chasm-coroutines`、validator、wat、gradle-plugin ✅（[repo API](https://api.github.com/repos/CharlieTap/chasm)、[Maven 目录](https://repo1.maven.org/maven2/io/github/charlietap/chasm/)）。**这是 iOS 上跑 wasm 的唯一可确认路径**，但无生产证据、无配额/中断文档 ⚠️。
- **weh / wasi-emscripten-host**：不是引擎，是「WASI Preview 1 + Emscripten host 函数」的 KMP 实现层（Apache-2.0），面向 GraalWasm / Chicory / **Chasm**，产物含 iOS ✅（[README](https://raw.githubusercontent.com/illarionov/wasi-emscripten-host/master/README.md)、[Maven 目录](https://repo1.maven.org/maven2/at/released/weh/)）。
- **维护风险**：`at.released.weh:host` 最新 **0.6.0，lastUpdated 2025-08-13**（约 14 个月未发版）⚠️；其 bindings 依赖 `chasm 0.9.4`，与 chasm 2.3.0 有明显代差（最新绑定版本未核实）。
- **对 Trellis**：v1 不需要（iOS 锁定声明式）；若未来要给 Chicory/Chasm 插件提供文件/时钟等 WASI 能力，weh 是最省事的引入方式。

### 3.9 Extism / wasmtime / wasmer

- **Extism**：核心仓库活跃（5811★，BSD-3-Clause，pushed 2026-10-09），插件模型是 bytes-in/bytes-out + host functions + 可选 KV ✅；但 **Java SDK 1.1.0（2024-11-05）已停更**，且 README 要求先 `extism lib install` 装 native `libextism`（JNI）→ Android/iOS 分发要自建每个 ABI/静态链接，与本项目「零原生依赖」目标冲突 ✅（[README](https://raw.githubusercontent.com/extism/java-sdk/main/README.md)、[Maven](https://repo1.maven.org/maven2/org/extism/sdk/extism/maven-metadata.xml)）。若喜欢其模型，可看 `extism/chicory-sdk`（纯 JVM 重实现，支持 Android AOT 0.2.0+），但仍无 iOS。
- **wasmtime**：无官方 Java binding（社区 issue #8394「Provide official Java binding」，正文本次未抓 ⚠️）；社区 `io.github.kawamuray.wasmtime:wasmtime-java` 最新 **0.19.0（2023-10-07）** 停更 ✅。
- **wasmer-java**：MIT，650★，最后 push **2022-06-08**，停更约 4 年 ✅。
- **三者共同问题**：JNI + 每平台 native 库 + iOS 需自建 C interop，且维护状态差 → 淘汰。

### 3.10 Zipline 与 quickjs-kt 的选择对照（两者不是替代品）

| 维度 | Zipline 1.28.0 | quickjs-kt 1.0.15 |
| --- | --- | --- |
| 官方产物 | Android / JVM / iOS（含 tvOS/macOS/Linux）✅ | Android / JVM / iOS / linux / macos / mingw ✅ |
| 远程分发 | 内建：manifest 签名、sha256 校验、缓存 pin、embedded 免网络 ✅ | 无，全自建 |
| 插件形态 | 必须 Kotlin/JS 编译产物；Kotlin/工具链版本被锁（Kotlin 2.3.0 等）✅ | 任意 JS 源码字符串；Kotlin 版本自由 ✅ |
| 配额钩子 | 无内建；死循环能否中断**未核实**（最大风险）⚠️ | `evaluationTimeoutMillis` + `interruptEvaluation()` 现成 ✅ |
| 包体 | QuickJS native + loader/cache/签名组件 | QuickJS native，更薄；无网络/签名栈 |
| 生产证据 | 2312★、Cash App/Redwood 系 ✅⚠️ | 154★，无生产证据 ⚠️ |

**结论**：需要「三端 + 签名分发 + 远程热更」→ Zipline（但接受 Kotlin 版本锁定与安全免责）；需要「自由工具链 + 更小体量 + 自制分发」→ quickjs-kt（工作量更大）。二者都不应作「不可信代码沙箱」。

### 3.11 Kotlin/Wasm 作为插件格式的三条可能路径（v1 都不押注）

1. **插件作者写 Kotlin/Wasm（wasm-wasi）→ 宿主用 Chicory 执行**：卡在提案支持——Kotlin/Wasm 依赖 Wasm GC 与 legacy exception handling，而 Chicory 的 GC 支持还在 2026 roadmap（未完成），Chasm 对 GC 的支持未核实 ⚠️。
2. **wasm-js → 浏览器**：那是 Compose Multiplatform Web 的部署形态，与「Android/桌面内嵌插件」无关，不能解决 G3。
3. **插件作者写 Kotlin/JS → Zipline / quickjs-kt**：可行，正是 Zipline 的设计路线（但插件作者必须写 Kotlin，Agent 生成 Kotlin 再远程编译到与宿主精确匹配的工具链版本，不现实）⚠️。

**建议**：轨道 B 的 WASM 插件语言现阶段选 **Rust / C / WAT**（编译产物可控、无 GC 依赖）；把「Kotlin/Wasm 作为插件格式」列入 6-12 个月观察项，等 Chicory GC 落地后再评估。

### 3.12 iOS 侧的真实限制（比「iOS 受限」更具体）

- **无 JIT**：iOS 第三方 App 不能动态生成并执行机器码，JS 引擎只能走解释器。QuickJS 本身就是纯解释器，所以 Zipline/quickjs-kt 在 iOS 上技术上可运行 🔍；V8 系（Javet 等）依赖 JIT，不能直接搬上 iOS ⚠️。
- **不能动态装载 native 代码**：WASM 引擎必须是「解释/编译到自有 IR 再解释执行」（Chicory、Chasm 这类），不能 AOT 生成机器码再执行；`chicory-compiler-android` 只做 Android 也正是这个原因 🔍。
- **App Store 合规**：2.5.2 禁止下载可执行代码改变 App 功能，插件化热更在审核上存在解释空间。Cash App 生态（Zipline/Redwood）作为公开先例可作论据，但本次未核实其在 iOS 线上启用范围 ⚠️，需产品/法务确认。
- **结论**：iOS 上「解释型 JS 插件」技术可行但合规不确定，叠加 N4（iOS 只跑声明式）→ **v1 不在 iOS 做插件热更**；Zipline 的 iOS 产物先只用于技术验证。

### 3.13 其它 JVM JS 引擎（Javet / J2V8 / QuickJS-Android）

- **Javet**：把 V8/Node.js 通过 JNI 嵌进 JVM，仓库自述支持 Android；能力最强（V8 性能 + Node 兼容），但每平台 native 库、体积大、无 iOS；**版本/许可/维护状态本次未核实** ⚠️ → 只作「需要 V8 语义」时的备选，不作为 KMP 方案。
- **J2V8**：老牌 V8 JNI 封装，已长期停更（未核实具体状态 ⚠️）→ 不用。
- **QuickJS-Android**：Android 专用 JNI 封装（如 `taoweiji/quickjs-android`），能力约等于 QuickJS 原生；有了 KMP 的 quickjs-kt 后没有必要单独引入 ⚠️。

## 4. 对本项目的适用性判断（KMP + Compose MP；Android + 桌面；iOS 受限）

| 候选 | Android | 桌面 JVM | iOS | 卸载干净度 | 对「Agent 生成插件」可信 | 结论 |
| --- | --- | --- | --- | --- | --- | --- |
| Zipline | ✅ 官方产物 | ✅ | ✅ 官方产物 | 中（需宿主 close） | ❌ 官方非沙箱 | 采用（半信任） |
| Chicory | ✅（CI 未覆盖 ⚠️） | ✅ | ❌ | 高（GC） | ✅ WASM + host 白名单 | 采用（隔离级） |
| Chasm | ✅ | ✅ | ✅（唯一） | 未核实 | 未核实 | 观察 |
| quickjs-kt | ✅ | ✅ | ✅ | 中 | ❌ 同进程 JS | 实验 |
| Rhino | ⚠️ 未核实 | ✅（现有原型） | ❌ | 中 | ⚠️ 已有 ClassShutter 白名单 | 保留 JVM 降级 |
| GraalJS/LuaJ/TENUM/Extism/wasmer/wasmtime | 多数 ❌/停更 | 部分 | ❌ | — | — | 淘汰 |

**信任模型是本报告最重要的落地输入**（建议写入 kernel 设计）：

1. **内置/签名插件（半信任）**：Zipline 可用，但必须靠 manifest 签名（EdDSA/ECDSA）＋自建吊销/回滚策略；Zipline 插件跑在宿主进程、能调到宿主暴露的全部 `ZiplineService`，所以「暴露什么」就是权限边界，必须按 plugin 权限裁剪 bridge 接口。
2. **Agent 生成/第三方代码（不可信）**：只能用 WASM（Chicory；未来 iOS 用 Chasm）或轨道 A 声明式；WASM 的「只有显式 import 才能触达宿主」是强默认拒绝，且可用 host 函数白名单注入 kernel §6.3 配额（超时、KV 上限、调用次数）。
3. **iOS（N4）**：只跑声明式，不引入任何引擎；Chasm 只做技术预研。

**其它硬约束对照**：
- G3 回滚：Zipline 回滚 = 重新 `load(旧 manifest)`（需保证旧制品仍在缓存/embedded）＋宿主 close 新实例；Chicory 回滚 = 丢弃新 Instance，重装旧 wasm，天然无残留 ✅🔍。
- N1（不做进程级沙箱）：本文所有候选都在进程内，符合；代价是「不可信代码」只能靠 WASM 语义隔离，不能防宿主/引擎 0-day（应写进安全声明）。
- 内核不热更（N2）：两方案都不要求内核可替换，只要求宿主侧 loader/engine 版本随 app 发版。

## 5. 推荐组合与淘汰理由

**采用（v1 就做）**
1. **Chicory 1.7.5** 作为 `WasmLoader`（Android + JVM），插件格式 = wasm（Rust/C/WAT 编写，宿主提供受限 host functions），配额层：`ExecutorService + future.get(timeout)`（线程中断）＋ 内存上限 ＋ host 白名单 ＋ 调用次数计数；卸载 = 丢弃 `Instance`/`Store` 引用。配套可选 **weh 0.6.0** 提供 WASI P1 能力。理由：零 JNI、许可干净、生产证据最强、Java 对象卸载最契合 G3。
2. **Zipline 1.28.0** 作为 `ZiplineLoader`（Android/JVM/iOS），只服务「签名开发者插件」；必须补 `close()` 生命周期、死循环超时验证、manifest 吊销/回滚策略。理由：三端都有官方产物，是 iOS 跑 JS 的唯一现成方案；且这正是 kernel-design 已规划的 loader。
3. **保留 Rhino 1.7.15→1.9.x** 作为桌面/Android 的「轻量表达式/规则插件」降级路径（已有 `ScriptSandbox.kt`），不投入新功能。

**实验（1-2 个迭代内验证）**
- quickjs-kt 1.0.15：Zipline 的轻量替代（自由 Kotlin 版本、可中断），验证「自制 manifest/签名/缓存」成本。
- chicory-compiler-android 0.0.1：仅在 Chicory 解释器性能不达标时引入。

**观察（6-12 个月再看）**
- Chasm 2.3.0（+ weh bindings-chasm-wasip1）：若 iOS 未来要支持 WASM 插件，这是唯一路径；关注其配额/中断能力、是否进入大项目生产。

**淘汰及理由**
| 候选 | 淘汰理由 |
| --- | --- |
| Extism Java SDK | SDK 停滞 2 年 + 需 native libextism（每平台分发，违背零 JNI/包体目标） |
| wasmtime / wasmer JVM 绑定 | 无官方 binding / 停更 3-4 年 |
| GraalJS | Android 不可用、包体/启动重、只为 JVM 桌面不值得 |
| LuaJ / TENUM | 11 年停更 / 23★ 且许可不明 |
| QuickJS4J | 0.1.x、纯 JVM、无 KMP/iOS、构建期依赖外网镜像 |
| Kotlin/Wasm 作插件格式（当前） | 产物需 GC 提案，Chicory GC 未完成、Chasm 支持未核实；且官方无移动端内嵌路径 |
| Zipline 当「不可信代码沙箱」 | 官方明文禁止；只能作半信任热更 |

**包体 / 启动开销：本次没有硬数据，只能给结构性判断**（禁止编造数字；全部列入实验 #7/#8/#11）

| 候选 | 包体结构性影响 | 启动开销 |
| --- | --- | --- |
| Chicory | 纯 JAR，无 native 库（推断 🔍），增量应最小；未实测 | 解释器首次需解析+验证+编译 wasm，冷启动开销未核实 ⚠️ |
| Zipline | QuickJS native `.so`（按 ABI，`zipline-android-ndk`）+ loader/cache/签名组件，APK 增量应在 MB 级（未核实数值）⚠️ | QuickJS 初始化 + 预编译字节码加载，未核实 ⚠️ |
| quickjs-kt | QuickJS `.so` 按 ABI，无 loader/签名栈，应略小于 Zipline（推断 🔍） | 同 QuickJS，未核实 ⚠️ |
| Chasm | 纯 Kotlin klib/jar，无 native `.so`；KMP 各 target 分别编译 | 解释器冷启动，未核实 ⚠️ |
| weh | Kotlin 库，无 native | 无引擎启动成本 |
| Extism Java SDK | 额外每个平台一份 `libextism` native 库，最大 | 加载 native 库 + wasm 实例化，未核实 ⚠️ |
| Rhino | 纯 JAR，体积小；现有原型 `budgetMs=1500` 说明单次执行可控 | 最快（无 native 初始化）🔍 |

**落地节奏（建议）**

| 阶段 | 内容 | 依赖 | 出口标准 |
| --- | --- | --- | --- |
| v1（当前） | 轨道 A + Rhino JVM 降级；轨道 B 只做 `WasmLoader`（Chicory，Android/JVM） | kernel PluginLoader SPI 稳定 | 10 个 WASM 插件样本装/卸/回滚，无重启、无残留 |
| v1.5 | 引入 Zipline（Android/JVM 先，不上 iOS），只接签名插件 | Kotlin 2.3.0 工具链升级完成（实验 #3） | 一条生产热更 manifest 链路跑通，含 close/回滚/吊销 |
| v2（视需求） | iOS 评估 Zipline（含 App Store 合规确认）；WASM 插件如要 Kotlin 作者则等 GC 支持 | 实验 #1、#2、#9 通过 | iOS 可跑签名 JS 插件，或明确「iOS 永久只声明式」 |

**与 kernel-design.md 的偏差/建议**：现有设计表把 `ZiplineLoader` 标为 ANDROID/JVM/IOS、`WasmLoader` 标为 ANDROID/JVM(Chicory)，方向与本次调研一致；建议补三点：(a) 在 PluginManifest 增加 `trustLevel`（builtin / signed / untrusted），loader 按信任级准入，Zipline 只接受 builtin+signed；(b) `WasmLoader` 明确配额实现（线程中断超时、内存上限、host 白名单），不要只写「Chicory」；(c) 在设计中显式声明「Zipline 非安全沙箱」，避免后续把 Agent 生成代码塞进来。

## 6. 待验证实验清单（按优先级）

| # | 实验 | 方法 | 判定标准 | 风险 |
| --- | --- | --- | --- | --- |
| 1 | Zipline 死循环能否超时 | 插件内 `while(true){}`，宿主 `withTimeout(1500)` 调 plugin 函数 | 能抛出且进程可继续 = 可行；卡死 = 不可用于不可信代码 | 最高 |
| 2 | Zipline 热替换内存 | 同进程反复 load 新旧 manifest 50 次，每次 close 旧实例，堆 dump/LeakCanary | 内存稳定（±5%）、无旧 Service 引用 | 高 |
| 3 | Zipline 工具链升级 | Kotlin 2.1.0→2.3.0，验证 Compose 1.7.3/coroutines 1.10.2 兼容 | 编译通过 + 桌面/Android 冒烟 | 高 |
| 4 | Chicory Android 真机 | 1.7.5 在 minSdk 设备加载 wasm，含 issue #745 的 `readAllBytes` 路径 | 加载/调用成功、无 API 级别崩溃 | 高 |
| 5 | Chicory 卸载稳定性 | 新建/丢弃 Instance+Store 1000 次，观察堆与 Metaspace | 无单调增长、无 classloader 泄漏 | 中 |
| 6 | Chicory 超时 | infinite-loop wasm + `Thread.interrupt()`（文档示例） | 100% 可中断，UI/调度器不受影响 | 高 |
| 7 | Chicory 包体 | 对比 APK 加/不加 runtime 的体积 | 增量可接受（目标 <1MB，待定） | 中 |
| 8 | Zipline 包体 | Android APK 加 zipline-android/ndk 前后体积 | 增量与 ABI 数成正比 | 中 |
| 9 | quickjs-kt iOS | 用其 Compose MP `js-eval` 示例跑 iOS 模拟器 | 可构建+可执行脚本 | 中 |
| 10 | Chasm iOS | `bindings-chasm-wasip1` 在 iOS 模拟器跑 wasi hello world | 成功打印 = iOS WASM 可行 | 低（研究性） |
| 11 | 启动开销 | 空插件首次加载耗时（10 次中位数），三引擎对比 | 桌面 <100ms 可接受；Android 记录基线 | 中 |
| 12 | 插件签名/回滚 | Zipline 多 key 轮换 + 旧版本重放，验证无吊销机制下的回滚 | 回滚可成功、旧版本可被显式拒绝 | 中 |

## 7. 来源列表（本次实际抓取/核对）

**Zipline**
- <https://github.com/cashapp/zipline>（repo 主页；README 含安全免责句、用例、组件版本、签名说明）
- <https://raw.githubusercontent.com/cashapp/zipline/trunk/README.md>（README 原文）
- <https://api.github.com/repos/cashapp/zipline>（2312★、Apache-2.0、pushed 2026-10-21）
- <https://repo1.maven.org/maven2/app/cash/zipline/>（平台产物清单）
- <https://repo1.maven.org/maven2/app/cash/zipline/zipline/>（1.28.0 / 2026-09-29 等版本与日期）
- <https://github.com/cashapp/zipline/blob/trunk/zipline-loader/src/commonMain/kotlin/app/cash/zipline/loader/ZiplineLoader.kt>（load/loadOnce/loadFromManifest/EventListener/线程约束）
- <https://github.com/cashapp/zipline/issues/1423>（Memory leaks，正文未核实 ⚠️）
- <https://deepwiki.com/cashapp/zipline/2.4-application-loading>（抓取被 Vercel 429 拦截 ⚠️）

**Chicory / Android 编译**
- <https://github.com/dylibso/chicory>、<https://raw.githubusercontent.com/dylibso/chicory/main/README.md>（定位、roadmap、生产用户）
- <https://api.github.com/repos/dylibso/chicory>（1142★、Apache-2.0、pushed 2026-07-06）
- <https://repo1.maven.org/maven2/com/dylibso/chicory/runtime/maven-metadata.xml>（1.7.5 / 2026-03-24）
- <https://chicory.dev/docs/usage/cpu/>（CPU 限制：interrupts + unsafe listener）
- <https://chicory.dev/docs/usage/memory>、<https://chicory.dev/docs/usage/wasi>、<https://chicory.dev/docs/usage/host-functions>（未细读 ⚠️）
- <https://github.com/dylibso/chicory/issues/535>、<https://github.com/dylibso/chicory/issues/745>（Android CI/兼容问题）
- <https://raw.githubusercontent.com/dylibso/chicory-compiler-android/main/README.md>（Android AOT 0.0.1、GPR、8MB 栈、Extism 集成）

**KMP Wasm 引擎与 host 层**
- <https://github.com/CharlieTap/chasm>、<https://api.github.com/repos/CharlieTap/chasm>（241★、Apache-2.0、pushed 2026-10-21）
- <https://repo1.maven.org/maven2/io/github/charlietap/chasm/>（chasm 2.3.0 / 2026-10-21，iOS 产物）
- <https://github.com/illarionov/wasi-emscripten-host>、<https://raw.githubusercontent.com/illarionov/wasi-emscripten-host/master/README.md>（WASI P1/Emscripten host 函数层）
- <https://repo1.maven.org/maven2/at/released/weh/>、<https://repo1.maven.org/maven2/at/released/weh/host/maven-metadata.xml>（0.6.0 / 2025-08-13）

**QuickJS 系**
- <https://github.com/dokar3/quickjs-kt>、<https://raw.githubusercontent.com/dokar3/quickjs-kt/main/README.md>、<https://api.github.com/repos/dokar3/quickjs-kt>（154★、Apache-2.0、pushed 2026-10-20）
- <https://repo1.maven.org/maven2/io/github/dokar3/quickjs-kt/maven-metadata.xml>（1.0.15 / 2026-09-03）及目录下的 iOS/native 产物
- <https://repo1.maven.org/maven2/io/roastedroot/quickjs4j/maven-metadata.xml>、<https://repo1.maven.org/maven2/io/roastedroot/quickjs4j/0.1.1/quickjs4j-0.1.1.pom>（0.1.1 / 2026-10-19、endive 依赖）
- QuickJS-Android（Android 专用 JNI 封装，如 `taoweiji/quickjs-android`）：本次未核实版本/维护状态 ⚠️

**其它 JS / Lua / Graal**
- <https://repo1.maven.org/maven2/org/mozilla/rhino/maven-metadata.xml>（1.9.1 / 2026-02-15；项目现用 1.7.15 见 `plugin-script/build.gradle.kts:12`）
- <https://repo1.maven.org/maven2/org/graalvm/js/js/maven-metadata.xml>（25.4.4.1.1 / 2026-10-20）
- <https://repo1.maven.org/maven2/org/luaj/luaj-jse/maven-metadata.xml>（3.0.1 / 2015-04-30）
- <https://api.github.com/repos/TENUM-Dev/tenum>（23★、2025-11-10 创建、Other/NOASSERTION）

**Extism / wasmtime / wasmer**
- <https://github.com/extism/extism>、<https://api.github.com/repos/extism/extism>（5811★、BSD-3-Clause、pushed 2026-10-09）
- <https://github.com/extism/java-sdk>、<https://raw.githubusercontent.com/extism/java-sdk/main/README.md>、<https://api.github.com/repos/extism/java-sdk>（46★、pushed 2025-06-26、需 native libextism）
- <https://repo1.maven.org/maven2/org/extism/sdk/extism/maven-metadata.xml>（1.1.0 / 2024-11-05）
- <https://github.com/wasmerio/wasmer-java>、<https://api.github.com/repos/wasmerio/wasmer-java>（650★、MIT、pushed 2022-06-08）
- <https://repo1.maven.org/maven2/io/github/kawamuray/wasmtime/wasmtime-java/maven-metadata.xml>（0.19.0 / 2023-10-07）
- <https://github.com/bytecodealliance/wasmtime/issues/8394>（官方 Java binding 请求，正文未核实 ⚠️）

**Kotlin/Wasm、Kotlin/JS（官方文档）**
- <https://kotlinlang.org/docs/wasm-overview.html>（Beta、wasm-js / wasm-wasi、GC/EH 依赖）
- <https://kotlinlang.org/docs/js-overview.html>（转译为 JS、ES5/ES2015/ES2020、Node.js target）

**其它（备选引擎 / 平台合规）**
- <https://github.com/caoccao/Javet>（V8/Node.js in JVM，仓库自述支持 Android；版本/许可/维护状态本次未核实 ⚠️）
- <https://github.com/eclipsesource/J2V8>（老牌 V8 JNI 封装；停更状态未核实 ⚠️）
- <https://developer.apple.com/app-store/review/guidelines/>（2.5.2 自包含/下载代码限制；条文原文本次未逐字核对 ⚠️）

## 8. 附录：未核实清单与证据强度（评审前必读）

> 以下条目**未在本次抓取中核实**，落地设计时不得当作既定事实引用；建议由 PoC 或后续调研补证。

1. Zipline 是否暴露「中断正在执行的 QuickJS」的 API——决定能否超时 kill 死循环（本次只确认 `close()` 与协程取消语义）。
2. Zipline issue #1423（内存泄漏）的正文与影响面；`close()` 后旧 `ZiplineService`/协程是否真的可回收。
3. Zipline 在 Android/iOS 的包体增量与冷启动耗时；Cash App/Redwood 之外的生产使用证据（Redwood README 抓取失败）。
4. Zipline 在 iOS App Store 的合规先例（Redwood/Treehouse 是否在线上 iOS 版启用）。
5. Chicory 在 Android 真机的可用性（官方 CI 不含 Android；issue #745 `readAllBytes` 的 minSdk 影响）；内存上限配置的准确 API。
6. Chicory host functions / Linker / Store 的释放语义与 Metaspace 行为；反复装卸后的泄漏曲线。
7. `chicory-compiler-android:0.0.1` 的许可、Gradle 集成方式、真实性能收益（仅 GPR 分发）。
8. Chasm 2.3.0 的 CPU/内存配额、中断能力、GC 提案支持度、生产使用证据。
9. weh 0.6.0 的 bindings 是否已适配 chasm 2.x（现有 POM 仍依赖 chasm 0.9.4）。
10. quickjs-kt 的 iOS 稳定性、`close()` 后内存表现、是否有生产项目使用。
11. Rhino 在 Android 上的官方支持状态、1.9.x 的 ES 特性/性能变化、许可（本文按 MPL 记，未核实）。
12. GraalJS 许可（UPL?）与「Android 不可用」的准确依据（社区 issue 正文未读）。
13. Javet / J2V8 / QuickJS-Android 的版本、许可、维护状态（本次完全未抓）。
14. Kotlin/Wasm 产物在 Chicory / Chasm 上的实际可执行性（GC/EH 提案支持矩阵）。
15. 所有「包体/启动开销」均为结构性推断，无实测数据。

