# 02 · 进程内原生代码插件：成熟做法与热替换的真实边界

> 调研目标：Trellis（KMP + Compose Multiplatform，自研 `:kernel`）桌面 JVM 端若要「进程内加载 jar / dll 插件」，业界成熟框架各自做到哪一步、热替换（覆盖更新、卸载、再加载）的真实边界在哪、哪些能力只在开发期成立。为轨道 C（PF4J + Shadow）选型提供外部证据。
>
> 证据等级标记：✅ = 本次抓到官方文档/源码原文；🟡 = 官方页面可达但正文未取全（仅据标题/摘要）；⚠️ = 未取到原文，仅列出待核实线索。
> 调研时间：2026-10-22。所有结论附来源 URL，未核实处明确标注。

---

## 1. 结论速览

1. **「进程内热替换」没有免费的跨平台解。** 各平台成熟框架全部收敛到同一组工程手法：**版本化目录 + 原子切换 + 旧版本延迟清理 + 卸载失败时降级为「重启生效」**。没有任何主流框架承诺「Windows 上直接覆盖正在使用的 jar/dll」。
2. **PF4J 的类隔离是生产可用的**：每个插件独立 `PluginClassLoader`，默认 Parent-Last；但它的「热重载」= unload 旧 `PluginClassLoader` + 新建一个，**一旦有外部引用泄漏，类就永远留在 JVM 里**（官方 issue #505）。
3. **Windows 文件锁是 PF4J 官方承认的长期问题**：2026-09 的 #691 承认 Windows 测试因「class loader 持有的 jar 无法删除」而从未真正跑过；#694 定位到 `JarURLConnection` 的缓存让 jar 在 `unloadPlugin()` 后仍被打开。**结论：不要在 Windows 上原地覆盖插件文件，必须换新文件名/新目录。**
4. **IntelliJ Platform 是「动态插件」最完整的生产样本**：安装/启用/禁用/卸载不重启已对大多数插件成立，但前提是一长串限制（不得用 Component、扩展点必须声明 dynamic、不得存 PSI 等）；**任何一个限制不满足，IDE 就同步等待卸载，失败则弹「重启」。** 插件用原生库时可用 `require-restart="true"` 显式放弃动态安装。
5. **OSGi/Felix/bnd 是唯一真正做到「热装热卸」的 JVM 方案**，代价是 bundle 状态机 + 依赖 wiring + `refresh` 语义（刷新会让依赖 bundle 停/启），因此只在 IDE、企业网关这类「宿主愿意为插件生命周期付出架构成本」的场景使用；Trellis 这种应用型产品引入 OSGi 属于过度设计。
6. **Java 原生 HotSwap/Instrumentation 只解决「改方法体」**：HotSwapAgent（依赖 DCEVM/增强 JVM）定位是开发期提效，不能作为产品热更新机制。
7. **.NET 的答案是 collectible `AssemblyLoadContext`**：`Unload()` 是协作式的，必须等「没有线程在该 ALC 的方法栈上、没有强引用」才真正卸载，官方示例用弱引用 + `GC.Collect()` 循环验证。`dotnet watch` 的 Hot Reload 明确是开发期体验，不支持的结构变更叫 rude edit。
8. **原生插件层面平台差异最大**：Qt `QPluginLoader` 默认带 `PreventUnloadHint`（Qt 5.7 起），`QLibrary::unload()` 在 macOS 上「返回 true 但库不卸载」；Rust `libloading::Library::close()` 文档原话是「might be a no-op」；Go `plugin` 包在 Windows 上不支持，且「一个 plugin 只初始化一次、无法关闭」，官方建议改用 IPC/RPC。
9. **Android 的官方「进程内代码扩展」路径是 Play Feature Delivery（安装时分发模块），不是运行时下载 dex 再 `DexClassLoader`**；后者既受 Play 政策约束，也受平台安全收紧影响（详见 §3.8，政策原文本次未取到，标 ⚠️）。
10. **iOS 在进程内动态加载原生代码这条路是封闭的**（App Store 政策 + 无 JIT + 代码签名/沙箱），Trellis 的 `Platform.IOS` 只应保留声明式/WASM 轨道，`PluginLoader` 侧应显式返回 UNAVAILABLE（设计文档 §9 已有此语义）。

---

## 2. 横向对比表

| 方案 | 类/模块隔离 | 卸载后真释放 | Windows 覆盖更新 | 状态/版本管理 | 定位 | 结论 |
|---|---|---|---|---|---|---|
| PF4J（JVM） | ✅ 每插件 `PluginClassLoader` | ⚠️ 协作式，引用泄漏即留 | ❌ 官方承认锁文件 | ✅ `PluginState` 状态机 | 生产 | 类隔离好用，「原地热替换」不成立 |
| OSGi / Felix / bnd | ✅ bundle classloader + wiring | ✅ `refresh` 可重算 | 受 JVM 文件锁同样约束 | ✅ Bundle 状态机（INSTALLED/RESOLVED/ACTIVE…） | 生产（IDE/网关） | 真热装热卸，架构重量级 |
| IntelliJ 动态插件 | ✅ `PluginClassLoader`（platform 内） | ✅ 卸载成功则释放，失败转重启 | 允许（插件重新解包到新目录） | ✅ 插件仓库 + 启用/禁用状态 | 生产 | 最完整样本，限制多 |
| Eclipse p2 | ✅ bundle | 部分（安装后常提示重启） | 受 OSGi 约束 | ✅ p2 profile | 生产 | 「安装后重启」仍是常态 |
| Instrumentation / HotSwapAgent / DCEVM | ❌ 不隔离 | ❌ 只换方法体 | — | ❌ | **仅开发期** | 不做产品热更新 |
| .NET collectible ALC | ✅ 每 ALC 一世界 | ✅ 协作式，可验证 | ❌ 卸载前无法删程序集 | ✅ 应用自管 | 生产 | Windows 同样要 copy-on-update |
| .NET `dotnet watch` | — | — | — | — | **仅开发期** | 明确 rude edit 限制 |
| Qt `QPluginLoader` | 动态库即隔离边界 | ⚠️ 默认不卸，macOS 不能卸 | ❌ DLL 映射中不可删 | ❌ 框架不管 | 生产（加载） | 卸载不可依赖 |
| Rust `libloading` | 动态库即隔离边界 | ⚠️ `close()` 可能是 no-op | ❌ 同上 | ❌ 框架不管 | 生产（加载） | 同上 |
| `hot-lib-reloader` | — | 每次写成新文件名 | ✅ 靠影子文件绕锁 | ⚠️ 计数器 | **仅开发期** | 开发期热更手法可借鉴 |
| Go `plugin` | ✅ | ❌ 不能关闭 | ❌ 平台不支持 | ❌ | 不推荐 | 官方建议 IPC/RPC |
| Android `DexClassLoader` | ✅ 每 loader 一 dex | ⚠️ 依赖 GC | — | ❌ 自管 | 受限 | 政策 + 平台限制 |
| Play Feature Delivery | ✅ split 模块 | 由 Play 管理 | — | ✅ Play | 生产（官方） | 扩展代码的正确路径 |
| iOS 原生动态加载 | — | — | — | — | **禁止** | 政策 + 无 JIT + 签名 |

---

## 3. 逐项详解

### 3.1 JVM · PF4J：类隔离生产可用，「热替换」是协作式的

**机制（✅ 官方文档）**
- 插件可在应用运行期被 PF4J 加载/卸载；插件类继承 `org.pf4j.Plugin`，生命周期方法 `start()` / `stop()` / `delete()`。
  来源：https://pf4j.org/doc/plugins.html
- 元数据二选一：`META-INF/MANIFEST.MF` 的 `Plugin-Class` / `Plugin-Id` / `Plugin-Version` / `Plugin-Requires` / `Plugin-Dependencies` / `Plugin-Description` / `Plugin-Provider` / `Plugin-License`，或插件根部的 `plugin.properties`（`plugin.class=` 等）。版本遵循 semver；依赖可写 `pluginB@>=1.0.0 & <2.0.0`，可选依赖加 `?`（`pluginB?@1.0`）。
  来源：https://pf4j.org/doc/plugins.html
- 每个可用插件一个 `org.pf4j.PluginClassLoader`，默认 **Parent Last（PDA）**：`java.*` → 系统 loader；`org.pf4j` → 父 loader（`ApplicationClassLoader`）；再当前插件 loader；再依赖插件的 `PluginClassLoader`；最后父 loader。可用 `ClassLoadingStrategy.APD`（Application → Plugin → Dependencies）改成 parent-first。
  来源：https://pf4j.org/doc/class-loading.html
- 关键 API：`new PluginClassLoader(PluginManager, PluginDescriptor, ClassLoader[, ClassLoadingStrategy])`；`pluginManager.whichPlugin(MyClass.class)`；可替换 `PluginLoader.loadPlugin` / `DefaultPluginManager.createPluginLoader` / `DefaultPluginLoader.createClassLoader`。
- 状态机（✅ 源码）：`org.pf4j.PluginState` 枚举 = `CREATED → RESOLVED → STARTED → STOPPED → UNLOADED`，另有 `CREATED → DISABLED`、`CREATED → FAILED`；`UNLOADED` 注释写明「事件完成后插件的 `ClassLoader` 会被关闭」。
  来源：https://raw.githubusercontent.com/pf4j/pf4j/master/pf4j/src/main/java/org/pf4j/PluginState.java
- ⚠️ **任务描述里的 `AtomicPluginStatus` 在 PF4J 中不存在**：本仓库 `docs/` 全局 grep 无此名；PF4J 官方文档与源码中是 `PluginState`（枚举）与 `PluginWrapper`。报告按 `PluginState` 写；`AtomicPluginStatus` 视为未核实/疑似误记。

**热替换的真实边界（✅ 官方 issue 证据）**
- **Windows 文件锁是官方承认的**：#691（2026-09-06）「The build never runs on Windows」指出两个 `@EnabledOnOs(WINDOWS)` 测试从未执行过：`JarPluginManagerTest.releaseBrokenJarOnWindows`（覆盖 #389/#439：启动失败的插件 jar 会一直被锁到插件卸载）与 `LegacyExtensionFinderTest.shouldUnlockFileAfterReadingExtensionsFromPlugin`；作者原话「on Windows a jar held open by a class loader cannot be deleted, so the cleanup of a @TempDir fails」。修复 PR #695 才让 Windows 测试真正跑起来（324 tests / 0 failures），并说明回归由 #678 引入。
  来源：https://github.com/pf4j/pf4j/issues/691 、https://github.com/pf4j/pf4j/pull/695
- #694 定位到具体根因：`IndexedExtensionFinder.readPluginsStorages` 用 `url.openStream()` 读 `jar:file:...!/META-INF/extensions.idx`，JDK 的 `JarURLConnection` 会把 `JarFile` 放进缓存，于是 `AbstractPluginManager.unloadPlugin` 关闭了 classloader、jar 仍被打开；**「在 Windows 上，一旦读过插件扩展索引，该插件就无法再删除或替换」**。修复是 `connection.setUseCaches(false)`。
  来源：https://github.com/pf4j/pf4j/issues/694
- 更早的同类问题：#484（2022，`FileSystemException: 另一个程序正在使用此文件`，栈为 `BasePluginRepository.deletePluginPath` ← `CompoundPluginRepository.deletePluginPath` ← `AbstractPluginManager.deletePlugin`）；#389（2020，删除失败后锁持续到重启）；#387 PR（2020，「Introduce unloadPlugins method」专治 Windows 文件锁）；#322 / #355（2019，pf4j 3.0.1 仍复现）。
  来源：https://github.com/pf4j/pf4j/issues/484 、https://github.com/pf4j/pf4j/issues/389
- **类加载器泄漏**：#505（2022-09，19 条评论）「The plugin classloader is closed, but the classes in the JVM are not released」——Spring 侧持有插件里类的引用，类就永远不释放。
  来源：https://github.com/pf4j/pf4j/issues/505
- 另一类常见故障：同一扩展点/API 类同时出现在两个 ClassLoader → 「No Extensions Found」。官方要求把 API jar 从插件包中剔除。
  来源：https://pf4j.org/doc/troubleshooting.html

**判定**：PF4J 适合「安装一次、进程内长期运行、按需启用/禁用」；不适合「Windows 上覆盖同名 jar 完成更新」。可生产，但必须配 §4 的工程模式。

### 3.2 OSGi / Apache Felix / bnd：真正热装热卸，但重量级

- OSGi 的 bundle 有完整生命周期：INSTALLED → RESOLVED → STARTING → ACTIVE → STOPPING → UNINSTALLED；package wiring 在 `RESOLVED` 阶段确定。安装新版本、替换导出包后需要 `refresh` 相关 bundle，**refresh 会停止并重启受影响 bundle**——OSGi 规范里连框架自身被 refresh 都有专门状态 `STOPPED_SYSTEM_REFRESHED`（「This Framework object has been stopped because of a refresh operation on the system bundle…」）。
  来源（规范 PDF，检索命中该原文页）：https://docs.osgi.org/download/r8/osgi.core-8.0.0.pdf
- 重量级的根因是三个：①每个 bundle 独立 classloader + 解析后的 wiring 图；②服务注册表（`BundleContext.registerService`）与 bundle 生命周期耦合；③刷新/解析是全局动作，无法像 PF4J 那样「只动一个插件」。
- 社区侧的典型证据：Eclipse 论坛帖「Installed bundles not available until restart」、Eclipse bug 289583，都反映 p2 安装后 bundle 可见性/重启问题。
  来源：https://www.eclipse.org/forums/index.php/m/1725082/ 、https://bugs.eclipse.org/bugs/show_bug.cgi?id=289583
- Felix 是 OSGi framework 的实现，`bnd`/bndtools 是 bundle 构建与验证工具链。
  来源：https://felix.apache.org/ 、https://bnd.bndtools.org/

**判定**：Trellis 不引入 OSGi。可借鉴的只有它的状态机命名（`INSTALLED/RESOLVED/ACTIVE/UNINSTALLED`）与「替换后要 refresh 依赖」的谨慎，不需要其运行时。

### 3.3 IntelliJ Platform 动态插件：生产可用的完整样本

✅ 官方文档原文要点（https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html）：
- 「Installing, updating, and uninstalling plugins without restarting the IDE is available for all plugins following the restrictions listed below.」
- 「Note that any unloading problems in a production environment will ask the user to restart the IDE.」
- 若插件确实需要重启（例如使用原生库），在 `plugin.xml` 的 `<idea-plugin>` 根标签上写 `require-restart="true"`。
- **第三方付费插件不能免重启安装/更新/卸载**。
- 限制清单（全部满足才可动态）：不得使用 Component（须迁移到 service/extension/listener）；所有 `<group>` 必须声明唯一 `id`；只能使用被标记为 dynamic 的扩展点；自定义扩展点需显式声明为 dynamic；依赖动态扩展点的 `Configurable` 必须实现 `Configurable.WithEpDependencies`；服务不得 `overrides="true"`。
- 代码约束：加载/卸载发生在 **EDT + write action**；会清空 `CachedValuesManager` 的缓存；不得跨加载/卸载保存 PSI 引用（用 `SmartPsiElementPointer`）；不得用 `FileType`/`Language` 作 Map key；用 `DynamicPluginListener` 感知装卸；资源清理实现 `Disposable.dispose()`。
- 诊断：卸载失败会同步等待并弹重启；日志分类 `com.intellij.ide.plugins.DynamicPlugins`；注册表项 `ide.plugins.snapshot.on.unload.fail=true` + `-XX:+UnlockDiagnosticVMOptions` 可在卸载失败时生成 hprof，然后**沿 `PluginClassLoader` 的引用链找泄漏**（文档直接说「Every one of them is a memory leak」）。
- 内部类名：`com.intellij.ide.plugins.cl.PluginClassLoader`（✅ 文档正文出现）。

**判定**：这是「把进程内热替换做到产品级」的参照实现，但它用一整套平台约束（EDT、Disposable、动态扩展点）换来的；Trellis 若要类似能力，必须先把「插件只能通过内核接口接触宿主、且注册项可反注册」变成硬约束。

### 3.4 Eclipse p2

- p2 负责安装/更新 bundle，安装后通常提示重启；论坛与 bug 记录（见 §3.2 链接）表明「装完 bundle 不重启不可见」是长期行为。🟡（页面标题可证，正文未逐段抓取）
- 对 Trellis 的启示：桌面应用「安装插件 → 提示下次启动生效」是可接受的产品语义，不必强求零重启。

### 3.5 Java Instrumentation / HotSwapAgent / DCEVM —— 仅开发期

- HotSwapAgent 的 GitHub 描述原文：「Java unlimited redefinition of classes at runtime.」（✅ GitHub API：语言 Java，GPL-2.0，2.6k+ stars，最近 push 2026-10-19）。
  来源：https://github.com/HotswapProjects/HotswapAgent
- 它依赖增强类重定义能力（DCEVM/增强 JVM 或调试器 HotSwap 协议），**主要用途是开发期改代码即时生效**，不是产品内插件热替换（没有版本管理、没有隔离、无法跨进程会话持久）。
- 标准 JVM HotSwap 只允许替换方法体，不支持增删字段/方法（JEP 159 Enhanced Class Redefinition 长期未落地，⚠️ 本次未取原文，仅作背景）。
  来源（待核实）：https://openjdk.org/jeps/159
- DCEVM：修改版 JVM，放宽类重定义限制；同样定位开发期。🟡（未逐页抓取）
- JRebel：商业开发期热部署工具（同类定位，不做产品运行时）。

**判定**：Trellis 的产品热更新不依赖 Instrumentation；开发期可给 `:kernel` 加一个「dev reload」开关，但不要写进生产语义。

### 3.6 .NET：collectible ALC（生产）+ dotnet watch（开发）

✅ 官方文档（https://learn.microsoft.com/en-us/dotnet/standard/assembly/unloadability，页面标注 Last updated 2026-03-19）：
- .NET Framework 用 AppDomain 卸载；.NET Core 只有单个默认 AppDomain，改用 **collectible `AssemblyLoadContext`**。
- 卸载是协作式的：`AssemblyLoadContext.Unload()` 只是发起；真正完成需满足：①没有任何线程的栈上有该 ALC 内程序集的方法；②没有外部对其中类型/实例/程序集的强引用（`WeakReference` 可以，强/钉住 `GCHandle` 不行）。
- 验证卸载完成的官方写法：弱引用 + 循环 `GC.Collect(); GC.WaitForPendingFinalizers();`（示例上限 10 次），弱引用变 null 才算卸掉；调用方法需标 `[MethodImpl(MethodImplOptions.NoInlining)]`，否则栈槽会保活。
- 限制原文：C++/CLI 程序集不支持 collectible；ReadyToRun 生成的代码被忽略。
- 常见泄漏源：栈槽/寄存器引用、ALC 内运行的线程、ALC 内创建的非 collectible ALC、`RegisteredWaitHandle` 回调、自定义 ALC 子类的字段（卸载中 runtime 持强 GC handle，须显式清空）。调试用 WinDbg/LLDB + SOS：`!dumpheap -type LoaderAllocator`、`!gcroot`、`~*e !clrstack`。
- 文档明确把「想从磁盘删除已加载的程序集文件」列为等卸载完成的典型动机——与 Trellis 的「Windows 覆盖更新」同构。

- `dotnet watch`：Hot Reload 的官方定位是「streamlines the local development experience」；不支持的修改叫 **rude edit**，watch 会询问是否重启；`DOTNET_HOTRELOAD_NAMEDPIPE_NAME` 表明其实现依赖命名管道。→ **仅开发期**。
  来源：https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-watch
- .NET 插件框架：`weikio/PluginFramework`（MIT，C#，约 600 stars，2019-10 创建，2024-04 最后 push，描述「Everything is a Plugin in .NET」，含 Roslyn 相关 topic）——但它内部是否用 collectible ALC 未核实（⚠️）。
  来源：https://github.com/weikio/PluginFramework
- ⚠️ 任务里提到的 `WeihanLi.PluginFramework`：GitHub 仓库搜索返回 `total_count: 0`，未找到该公开仓库；不作为依据。

**判定**：.NET 生态与 JVM 同构（ALC ≈ ClassLoader），差别是 ALC 的卸载验证更明确（弱引用 + GC 循环）。可借鉴「卸载后必须验证」这条纪律。

### 3.7 原生：Qt / Rust / Go

**Qt（✅ Qt 6.12 文档）**
- `QPluginLoader` 加载后插件常驻内存，直到所有 `QPluginLoader` 实例都卸载或进程退出；只有全部实例 `unload()` 才真正卸载。
  来源：https://doc.qt.io/qt-6/qpluginloader.html
- **默认禁止卸载**：`QPluginLoader::loadHints` 文档原话「By default since Qt 5.7, `QLibrary::PreventUnloadHint` is set.」；`QLibrary::PreventUnloadHint = 0x08` 的含义是「调用 close() 时不从地址空间卸载；以后再 open() 也不会重新初始化静态变量」。
  来源：https://doc.qt.io/qt-6/qlibrary.html
- **macOS 明确不支持卸载**：`QLibrary::unload()` 文档原话「Note that on macOS, dynamic libraries cannot be unloaded. QLibrary::unload() will return true, but the library will remain loaded into the process.」
- `QLibrary::setFileNameAndVersion` 的版本号「currently, the version number is ignored on Windows」——Windows 没有 soname 版本机制。
- `QLibrary::isLibrary` 合法后缀：Windows `.dll`；Unix/Linux `.so`；macOS/iOS `.dylib/.bundle/.so`。

**Rust**
- `libloading::Library::new` 是 unsafe（会执行库初始化例程）；`Library::close` 文档原话：「Unload the library. **This method might be a no-op**, depending on the flags with which the Library was opened, what library was opened or other platform specifics.」
  来源：https://docs.rs/libloading/latest/libloading/struct.Library.html
- `hot-lib-reloader`（✅ README 原文）：自我定位「hot-lib-reloader is a **development tool** that allows you to reload functions of a running Rust program」；把可热更代码放进 dylib，靠 `libloading` 加载；限制：签名变更 → 崩溃；结构体/枚举布局变更 → UB；可热更函数不能泛型（依赖 `#[unsafe(no_mangle)]`）；全局状态不保留（`TypeId` 变化，ECS 类库尤其危险）；macOS 需 codesign。
- 它绕文件锁的手法值得借鉴：`loaded_lib_name_template` 默认 `{lib_name}-hot-{load_counter}`，**每次 reload 写一个新文件名**（可加 `{pid}`/`{uuid}`），从而绝不覆盖正在被映射的文件。
  来源：https://github.com/rksm/hot-lib-reloader-rs
- subsecond（Dioxus 生态热补丁）本次未取到一手文档 → ⚠️ 不列为依据。

**Go（✅ pkg.go.dev/plugin）**
- `plugin.Open(path)`、`(*Plugin).Lookup(symName)`；`go build -buildmode=plugin`。
- 关键限制原文：「A plugin is only initialized once, and cannot be closed.」；只支持 Linux、FreeBSD、macOS（Windows 不支持，官方称其「unsuitable for applications intended to be portable」）；应用与所有插件必须用**完全相同版本 toolchain、相同 build tags、相同 flags/env**，公共依赖必须同源码，否则运行时崩溃；官方结论是「the application and its plugins must all be built together by a single person or component of a system」，并建议改用 IPC/RPC。
  来源：https://pkg.go.dev/plugin

**判定（原生）**：加载容易、卸载难是操作系统层面的共性。Trellis 桌面端若真要支持 dll 插件，必须假设「卸不掉」：以进程为隔离单位（插件宿主进程）或接受「dll 常驻、只换逻辑（脚本/WASM）」。

### 3.8 Android：DexClassLoader 与 Play Feature Delivery

- `dalvik.system.DexClassLoader`：从含 `classes.dex` 的 jar/apk 加载类的 ClassLoader（继承 `BaseDexClassLoader`）；`PathClassLoader` 是系统用于已安装 apk 的实现。
  API 页面存在（✅ 检索确认页面 URL），但本次抓取被 devsite 导航 HTML 占满，**正文未取到**（🟡）：https://developer.android.com/reference/dalvik/system/DexClassLoader
- 工程事实（🟡 待核实原文）：`DexClassLoader` 需传入 dex 优化目录（API 26+ 通常忽略/由平台 ART 处理）；加载的 dex 会被 ART 编译缓存，删除/替换文件需先释放引用；Android 新版本对「从可写目录动态加载代码」持续收紧（⚠️ 本条未核实，需查 Android 14+ behavior changes 原文）。
- **官方推荐的生产路径是 Play Feature Delivery**：把功能拆成 dynamic feature module，按 install-time / fast-follow / on-demand 分发；on-demand 模块通过 Play Core 的 split 安装 API 下载安装。对用户而言就是「装了个模块」，不是「运行时下 dex」。🟡（正文未抓取）：https://developer.android.com/guide/playcore/feature-delivery
- **Play 政策**：Google Play「Device and Network Abuse」政策限制应用下载可执行代码（dex/JAR/.so）并在应用外执行/更新自身，例外是应用商店更新与受支持的运行时（如 JS/WASM 解释执行）。⚠️ 本次抓取 `support.google.com/googleplay/android-developer/answer/16559646` 超时，**未取到原文**，只能作为风险提示：任何「运行时下载 dex 并执行」的自研方案都要先过政策评审。
  来源（待核实）：https://support.google.com/googleplay/android-developer/answer/16559646

**判定**：Trellis Android 端的轨道 C 若做自研 dex 插件，风险主要在政策与后续 Android 版本收紧；更稳的是「插件 = 随 App 分发的 dynamic feature / 编译期模块」，运行时只用声明式或脚本层扩展。

### 3.9 iOS：为什么不能动态加载原生代码

- App Store 审核指南 2.5.2 限制「下载并执行会改变 App 功能的代码」（解释型脚本有例外）。⚠️ 本次抓取 `developer.apple.com/app-store/review/guidelines/` 只取到 HTML 外壳与导航，**2.5.2 原文未取到**；结论按行业共识陈述，引用页：https://developer.apple.com/app-store/review/guidelines/
- 平台机制层面：iOS 不允许 JIT（无 `MAP_JIT`/可执行内存权限给普通 App），所有代码必须随包签名；`dlopen` 加载未签名/非包内 dylib 会被系统拒绝；App 沙箱不允许从可写目录映射可执行段。🟡（机制为平台常识，官方文档本次未逐页抓取）
- **判定**：Trellis iOS 端不提供原生插件加载，`Platform.IOS` 只保留声明式（A）与 WASM/脚本沙箱（B）；`PluginLoader.platforms` 不含 IOS 的 loader 在 iOS 上应产出 UNAVAILABLE 状态（与设计文档 §9 语义一致）。

---

## 4. 工程模式：热替换的 6 个真实边界手法

这些手法在 PF4J / IntelliJ / .NET ALC / hot-lib-reloader 里反复出现，是「进程内插件」能上生产的真正原因。

1. **版本化目录 + 原子切换**：`plugins/<id>/<version>/`，另存一个 `current` 指针（文本文件或符号链接）。更新流程 = 写新目录 → fsync → 原子替换指针 → 新 ClassLoader 指向新目录；**旧目录不删，只标记为 orphan**。
   - 依据：PF4J 每个插件一个目录 + `PluginClassLoader` 按路径加载；hot-lib-reloader 每次 reload 换文件名；.NET 文档指出「卸载完成前无法删除程序集文件」。
2. **A/B 双目录 + 回滚**：保留上一代目录与上一代 `PluginState` 快照；启动失败/崩溃计数超标 → 指针回退到 previous（Trellis 设计文档 §11 的「回滚快照」「连续 3 次未达 ACTIVE → SAFE」正是此模式）。
3. **copy-on-update（绝不原地覆盖）**：更新即复制到新文件名（可带 `-<hash>`/`-<load_counter>` 后缀），旧文件由「延迟清理」回收；启动时扫描「没有任何 loader 引用」的旧版本目录再删。这是 PF4J #691/#694 与 Windows 锁的唯一可行解。
4. **状态外置**：插件的启用/禁用/版本/安装时间/失败计数不放在插件目录里，放在宿主数据目录（JSON/DB），由内核在启动时与磁盘目录对账。参照 `PluginState` 的枚举语义（CREATED/DISABLED/RESOLVED/STARTED/STOPPED/FAILED/UNLOADED），但持久化的是「期望状态」，实际状态每次启动重建。
5. **引用计数 + 延迟卸载**：`unload()` = ①停调度/取消协程（Trellis 用 `scope.cancel()`）→ ②反注册所有扩展点/监听器 → ③关闭 ClassLoader → ④**验证**（弱引用或 `whichPlugin` 计数）→ ⑤失败则标 `restart-required`，下次启动清理。IntelliJ 的「同步等待 + 失败弹重启」与 .NET 的「弱引用 + GC 循环」都是这一步的不同实现。
6. **崩溃隔离**：进程内无法真正隔离异常，只能做「边界 catch + 超时 + 计数器」；真正的强隔离必须换进程（.NET/Java 都只能做到「卸载失败就重启」）。IntelliJ 的做法是卸载失败降级为重启；Trellis 的 `SupervisorJob` + `withTimeout` + `runCatching` 是同一思路的轻量版，但**不能防 JVM 级崩溃**（如插件触发 OOM/StackOverflow）。

---

## 5. Trellis 可借鉴的最小机制清单

> 现状：`kernel/src/commonMain/kotlin/dev/trellis/kernel/spi/PluginLoader.kt` 已有
> `enum class Platform { ANDROID, JVM, IOS, WASM }`、`data class DiscoveredPlugin(...)`、
> `interface PluginLoader { val scheme: String; val platforms: Set<Platform>; suspend fun load(manifest, ctx): Plugin }`；
> 设计文档 `docs/kernel-design.md` §9 规划了 `Pf4jLoader/pf4j`、`ShadowLoader/shadow` 等 scheme。
> 以下机制全部映射到这套接口，不引入新框架语义。

**Top 5（按投入产出排序）**

1. **`PluginState` 语义 + 状态外置**（抄 PF4J 的状态机，不抄框架）
   - 在 commonMain 增加 `enum class PluginState { CREATED, DISABLED, RESOLVED, STARTED, STOPPED, FAILED, UNLOADED }`（与 `org.pf4j.PluginState` 同名同义，便于 JVM 端映射）。
   - 落盘只存「期望状态 + 版本 + 失败计数」，实际状态每次启动重建；JVM 端用 PF4J 的 `PluginState` 做映射（`PluginWrapper.getPluginState()`）。
2. **版本化插件目录 + `current` 指针（copy-on-update）**
   - 布局：`<appData>/plugins/<pluginId>/<version>/plugin.jar` + `<appData>/plugins/<id>/current`（内容为新目录名）。
   - 更新 = 复制到新版本目录 → 写 `current.tmp` → 原子 `rename`；**永不覆盖已有 jar**。启动时清理无引用的旧版本目录。
3. **每个插件一个 ClassLoader，且「先换后卸」**
   - JVM 端 `Pf4jPluginLoader` 直接复用 PF4J 的 `PluginClassLoader`；更新流程：先 `pluginManager.loadPlugin(newPath)` 得到新 `PluginWrapper` → 切换内核里的插件句柄 → 再 `unloadPlugin(oldId)`/`deletePlugin`。
   - 兜底：卸载后调用 `pluginManager.whichPlugin(...)`/弱引用检查；失败则把该插件标 `restart-required`（IntelliJ 同款降级），UI 提示「下次启动生效」。
4. **SPI/API 只由内核 ClassLoader 提供（parent-first for `dev.trellis.*`）**
   - 插件 jar 内不得打包 `:kernel`/`:sdk-api` 的类；JVM 端用 `ClassLoadingStrategy.APD`（或自定义 `loadClass` 前缀白名单），确保扩展点接口只有一份 ClassLoader 实例。
   - 依据：PF4J Troubleshooting「同一扩展点出现在两个 ClassLoader」即 No Extensions Found。
5. **Compose/UI 侧的 `PluginErrorBoundary` + 生命周期绑定**
   - 插件入口 composable 包一层 `runCatching` + `DisposableEffect { onDispose { ctx.dispose() } }`；插件只能通过 `PluginContext` 拿宿主能力，禁止持有 `CompositionLocal`/全局单例引用（IntelliJ 的「不得存 PSI」「不得用 FileType 作 key」是同类约束的 UI 版）。
   - 卸载时先 `scope.cancel()`（设计文档 §11 已有），再卸 UI 子树，最后关 ClassLoader。

**Top 5 之外仍建议做的**

6. **`Platform` 能力矩阵显式化**：`Pf4jPluginLoader.platforms = setOf(JVM)`；`ShadowLoader.platforms = setOf(ANDROID)`；iOS 无原生 loader → 若 manifest 指定 `pf4j`/`shadow` 而当前平台无实现，按设计文档 §9 产出 `UNAVAILABLE`，不抛异常。
7. **插件清单签名/校验**：下载/拷贝后校验 hash + 清单里的 `plugin.id/version` 与目录名一致，防「半截文件」被加载（PF4J #694 的教训是「读一次 extensions.idx 就会锁 jar」，所以校验要在切换前完成）。
8. **dev-only 热更开关**：开发期可挂 HotSwapAgent/DCEVM 或 `dotnet watch` 式体验，但代码路径不得进入生产语义；产品热更新只走「重启 / 换目录」。

**反模式（明确不要做）**
- ❌ 在 Windows 上删除/覆盖正在被 ClassLoader 加载的 jar（PF4J #691 原话）。
- ❌ 用「同一个 ClassLoader 加载所有插件」图省事（放弃隔离后，卸载一个插件必然影响其他插件）。
- ❌ 把插件目录当状态存储（崩溃后无法区分「未安装」与「安装了一半」）。
- ❌ 依赖 `unload()` 的返回值判断「已卸载」：Qt 在 macOS 返回 true 但没卸；PF4J 关闭 loader 后类仍可能被引用（#505）。

---

## 6. 风险清单

| # | 风险 | 证据 | 缓解 |
|---|---|---|---|
| R1 | Windows 上 jar 被 ClassLoader 持有 → 无法删除/覆盖 | PF4J #691、#694、#484、#389（✅） | copy-on-update + 版本目录 + 延迟清理 |
| R2 | 卸载后类不释放（ClassLoader 泄漏） | PF4J #505（✅）；IntelliJ 文档「Every one of them is a memory leak」（✅） | 插件只能通过 `PluginContext` 引用宿主；卸载后弱引用验证；失败标 restart-required |
| R3 | 同一 API/扩展点出现两份 → 扩展点找不到 | PF4J Troubleshooting（✅） | SPI 不打包进插件；parent-first 白名单 |
| R4 | Compose 第三方代码异常/泄漏拖垮 Composable 树 | 设计文档 §11 已知痛点；IntelliJ 的 UI 约束是同类证据（✅） | `PluginErrorBoundary` + `DisposableEffect`；插件 UI 生命周期与插件 scope 绑定 |
| R5 | 原生 dll 卸载不可靠（Windows 锁、macOS 不允许卸） | Qt 文档（✅：macOS 明确不卸、Qt5.7 起默认 PreventUnloadHint） | 假设「卸不掉」；真隔离用子进程 |
| R6 | Android 运行时下载 dex 触发 Play 政策 | Play「Device and Network Abuse」页（⚠️ 未取原文） | 优先 Play Feature Delivery；自研方案先做政策评审 |
| R7 | iOS 完全不允许 | App Store 2.5.2（⚠️ 未取原文）+ 无 JIT/签名机制 | iOS 只走声明式/WASM |
| R8 | 插件启动失败留下半安装状态 | PF4J `PluginState.FAILED`/#389（✅） | 安装即事务：临时目录 → 校验 → 原子切换；失败回滚指针 |
| R9 | 崩溃隔离缺失（JVM/进程级错误无法 catch） | .NET/IntelliJ 均接受「卸载失败就重启」 | 安全模式 + 崩溃计数（设计文档 §11 已有 `kernel_boot.dat`） |

**未核实 / 本次没抓到的原文（报告中已标注，不得当作已证事实）**
- PF4J `AtomicPluginStatus`：不存在（PF4J 是 `PluginState`）；任务描述疑似误记。
- `WeihanLi.PluginFramework`：GitHub 仓库搜索 0 结果，未找到。
- Apple 审核指南 2.5.2 原文：页面只抓到 HTML 外壳。
- Google Play「Device and Network Abuse」原文：抓取超时。
- `DexClassLoader` 官方正文、Play Feature Delivery 正文：devsite 只返回导航 HTML。
- Android 14+「可写目录动态加载代码」限制：未核实。
- JEP 159（Enhanced Class Redefinition）原文、DCEVM/JRebel 官方页：未逐页抓取。
- Rust `subsecond`：未调研到一手材料。
- OSGi/Felix/bnd 的具体版本与文档页：只用了 OSGi 规范 PDF 的检索片段与社区页面。

---

## 7. 来源

**官方文档 / 源码**
- PF4J Plugins：https://pf4j.org/doc/plugins.html
- PF4J Class loading：https://pf4j.org/doc/class-loading.html
- PF4J Troubleshooting：https://pf4j.org/doc/troubleshooting.html
- PF4J `PluginState` 源码：https://raw.githubusercontent.com/pf4j/pf4j/master/pf4j/src/main/java/org/pf4j/PluginState.java
- IntelliJ Platform · Dynamic Plugins：https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html
- .NET Assembly unloadability：https://learn.microsoft.com/en-us/dotnet/standard/assembly/unloadability
- .NET `dotnet watch`：https://learn.microsoft.com/en-us/dotnet/core/tools/dotnet-watch
- Qt `QPluginLoader`：https://doc.qt.io/qt-6/qpluginloader.html
- Qt `QLibrary`：https://doc.qt.io/qt-6/qlibrary.html
- Rust `libloading::Library`：https://docs.rs/libloading/latest/libloading/struct.Library.html
- Go `plugin`：https://pkg.go.dev/plugin
- OSGi Core R8 规范：https://docs.osgi.org/download/r8/osgi.core-8.0.0.pdf
- Apache Felix：https://felix.apache.org/ ；bnd：https://bnd.bndtools.org/

**GitHub（issue / 仓库 / README）**
- PF4J #691：https://github.com/pf4j/pf4j/issues/691
- PF4J #694：https://github.com/pf4j/pf4j/issues/694
- PF4J #695（Windows CI PR）：https://github.com/pf4j/pf4j/pull/695
- PF4J #484：https://github.com/pf4j/pf4j/issues/484
- PF4J #389：https://github.com/pf4j/pf4j/issues/389
- PF4J #505：https://github.com/pf4j/pf4j/issues/505
- HotSwapAgent：https://github.com/HotswapProjects/HotswapAgent
- hot-lib-reloader：https://github.com/rksm/hot-lib-reloader-rs
- weikio/PluginFramework：https://github.com/weikio/PluginFramework

**政策 / 待核实页面**
- App Store Review Guidelines：https://developer.apple.com/app-store/review/guidelines/
- Google Play Device and Network Abuse：https://support.google.com/googleplay/android-developer/answer/16559646
- Android `DexClassLoader`：https://developer.android.com/reference/dalvik/system/DexClassLoader
- Play Feature Delivery：https://developer.android.com/guide/playcore/feature-delivery
- JEP 159（背景）：https://openjdk.org/jeps/159
- Eclipse 论坛「Installed bundles not available until restart」：https://www.eclipse.org/forums/index.php/m/1725082/
- Eclipse bug 289583：https://bugs.eclipse.org/bugs/show_bug.cgi?id=289583
