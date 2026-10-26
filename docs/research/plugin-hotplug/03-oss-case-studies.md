# 03 · 开源产品级案例研究：插件架构与热更新机制

> 配套：[kernel-design.md](../../kernel-design.md) 第 2 / 9 / 11 节（分层、PluginLoader SPI、失败隔离）
> 目标：不从「库」而从「成功产品」反推插件内核的可复用模式，服务 Trellis（KMP + Compose Multiplatform，Android + 桌面已落地、iOS 未来）的「多端 + 运行时热插拔/热更新」目标。
> 证据约定：✅ = 本次已抓取官方文档正文核对；⚠️ = 官方文档本次抓取失败，按公开资料/常识描述并标注「未核实」，落地前必须复核；🔍 = 机制推断。
> 抓取日期：2026-10-22。所有网页内容仅作为数据参考。

## 0. 结论速览（先看这 8 条）

1. **没有一个成熟产品把「类/函数级 HMR」当作生产环境热更新主路径**。生产热更新全部收敛为「插件级/模块级重载」：Obsidian 用 disable→enable，Neovim 清 `package.loaded` 再 `require`，JetBrains 用 load/unload + 重启兜底，VS Code/Chrome 用「重载窗口/扩展」生效。HMR 只是开发态工具。
2. **可卸载性 = 显式卸载契约 + 句柄化注册**，而不是靠语言运行时。最强证据是 Obsidian：`registerX()` 返回句柄、`onunload()` 负责清理；monkey-around 直接返回 `uninstaller`。JetBrains 为此写了整整一页限制清单。
3. **贡献点声明必须与代码执行分离**。VS Code 的 `contributes`、JetBrains 的 `plugin.xml`、Slack App Manifest 都是「不运行插件代码就能渲染 UI/菜单/权限」的静态契约 → 插件进程崩溃或未激活时 UI 骨架仍在。
4. **懒激活是性能与失败面控制的核心**。VS Code 的 activation events、Chrome 的 content_scripts match pattern 都是声明式触发。这与 Trellis 的「启动 10 步、I1 内核永远 ACTIVE」天然契合。
5. **隔离有三个层次，按能力递减**：独立进程（VS Code extension host）> classloader（JetBrains）> 沙箱/受限运行时（Figma sandbox、WASM）。桌面 JVM 可拿前两者，Android 拿不到 classloader 卸载，iOS 连动态加载都受限 → **热插拔必须在设计上允许「降到不可用/重启生效」**。
6. **权限一律声明式 + 默认收敛**：Figma `networkAccess`、Chrome `permissions/host_permissions`、Cloudflare bindings、Slack scopes、Istio `pluginConfig`。没有产品靠「运行时随便问」。
7. **版本兼容靠显式契约与固定制品**：Cloudflare compatibility date、Istio `sha256`、Shopify API version、VS Code `engines`/`extensionKind`。**内容摘要固定 + 快照回滚**是服务端热更新的一等公民，客户端产品普遍缺这一环（JetBrains 只做重启兜底）。
8. 对 Trellis：**把「热更新」定义为「按新 Profile 重放插件集」而不是「原地替换代码」**，v1 收益最大、风险最低；真正的代码级热替换只在 WASM/Zipline 沙箱轨道提供。

## 1. VS Code —— 独立 extension host + 声明式贡献点 + 懒激活

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **独立进程**。local=本机 Node.js，web=浏览器 WebWorker，remote=远程容器/SSH/WSL/Codespace 内 Node.js；`extensionKind`（`workspace`/`ui`）决定首选宿主 ✅ |
| 贡献点 | package.json `contributes`（commands/menus/views/configuration/languages…）由 workbench 在激活前读取；运行时 `commands.registerCommand` 只对已激活扩展生效（细节未核实 ⚠️） |
| 热更新粒度 | **扩展包级**：安装/更新/禁用无法原地换掉已激活代码，需重载窗口生效（提示文案未逐条核实 ⚠️） |
| 状态 | `globalState`/`workspaceState`（Memento）存于宿主之外，重载后恢复（未核实具体持久化实现 ⚠️） |
| 失败隔离 | Extension Host 的设计目的即「阻止扩展影响启动性能、拖慢 UI、修改 UI」✅；宿主进程崩溃不带走 workbench（🔍） |
| 安全模型 | 无语言级沙箱，扩展与用户进程同权限；靠 Workspace Trust 限制未信任工作区（未核实 ⚠️） |

**关键机制**：扩展必须 export `activate()`，**只会被调用一次**；应 export `deactivate()` 清理，异步需返回 Promise ✅。activation events 支持 `onLanguage/onCommand/onView/onUri/onStartupFinished/workspaceContains/onNotebook/onTerminal/onChatParticipant/onLanguageModelTool/…`，自 1.74.0 起自己 contributed 的 language/command/view/customEditor/authentication/taskType 无需声明；`*` 慎用，会拖慢启动 ✅。

**对 Trellis 的启示**：
- 「贡献点静态解析 → UI 骨架先渲染 → 触发时才加载代码」可 1:1 搬到 kernel-design §5 扩展点：manifest 的 contributions 由内核在启动第 5~6 步解析注册，插件 `load()` 延迟到 Slot 首次组合或命令首次调用。
- `deactivate()` 对称契约 → Trellis `Plugin` 必须提供 `unload()`，且 `PluginScope.cancel()` 之外还要撤销贡献（否则 UI 上残留幽灵菜单）。
- 进程隔离在 Android/iOS 不可得，但**桌面端可把轨道 C 原生插件放独立进程**，用 IPC 承载同一套 Contribution 协议。参考：<https://code.visualstudio.com/api/advanced-topics/extension-host>、<https://code.visualstudio.com/api/references/activation-events>

## 2. JetBrains IDE —— 进程内 classloader 隔离 + 动态插件白名单

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **IDE 同进程（JVM）**，每插件专用 `PluginClassLoader`，可各自使用不同库版本，即使同库被 IDE/其他插件使用 ✅ |
| 贡献点 | `plugin.xml`：`<extensions defaultExtensionNs>` 注册扩展、`<actions>`、自定义 `<extensionPoints>`，`<depends>` 声明依赖并影响类查找链 ✅ |
| 热更新粒度 | **插件级**，2020.1+ 可安装/更新/卸载不重启（满足限制时）✅；`require-restart="true"` 是显式例外 ✅；第三方付费插件不支持动态装卸 ✅ |
| 状态/兼容 | 卸载时 IDE 同步等待，失败则要求重启；`CachedValuesManager` 缓存被清空 ✅；service 用 `Disposable.dispose()` 清理 ✅ |
| 失败隔离 | load/unload 在 EDT + write action 中执行 ✅；卸载失败退化为「提示重启」而非崩溃 ✅ |
| 安全模型 | 无沙箱（JVM 全权限）；Marketplace Plugin Verifier 检查动态插件限制与 API 兼容 ✅ |

**动态插件的硬性限制（官方清单，全部 ✅）**：不得用 Components（须迁移到 services/extensions/listeners）；所有 `<group>` 必须唯一 id；只能使用显式标记为 dynamic 的扩展点（含平台与其他插件的 EP）；自定义 EP 必须显式声明 dynamic；依赖 dynamic EP 的 `Configurable` 必须实现 `Configurable.WithEpDependencies`；不允许 service `overrides="true"`；必须跑 Inspection「Plugin DevKit | Plugin descriptor | Plugin.xml dynamic plugin verification」。代码层：不得持有 PSI 引用（用 `SmartPsiElementPointer`）、不得用 `FileType`/`Language` 当 map key、用 `DynamicPluginListener` 接收 load/unload 事件、日志分类 `com.intellij.ide.plugins.DynamicPlugins`、用 `-XX:+UnlockDiagnosticVMOptions` + Registry `ide.plugins.snapshot.on.unload.fail` 生成 hprof 排查 classloader 泄漏。参考：<https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html>、<https://raw.githubusercontent.com/JetBrains/intellij-sdk-docs/master/topics/basics/plugin_structure/plugin_class_loaders.md>

**对 Trellis 的启示**（JetBrains 是「JVM 桌面轨道 C」最直接的老师）：
- 桌面 `Pf4jLoader` 走 per-plugin classloader 是正路；但**必须提前定义「哪些扩展点可热卸载」白名单**（对应 JetBrains 的 dynamic EP 标记），其余 EP 声明 `restartRequired`。否则 unload 后 classloader 泄漏是必然。
- `DynamicPluginListener` ≈ Trellis 的 `PluginLifecycleListener`；`Disposable.dispose()` ≈ `Plugin.unload()`。
- 「load/unload 在单一线程 + write action」与 kernel-design 的 **I3 单线程串行化** 完全一致，可作为该不变量的外部佐证。
- Android 上 Art classloader 无法真正卸载 → Android 轨道只承诺「插件级 disable + 进程重启生效」，或用 `:plugin-host` 独立进程兜底。

## 3. Figma —— 双环境沙箱 + manifest 网络白名单

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **沙箱 + iframe 双环境**：插件主逻辑跑在「主线程 sandbox」（最小 JS 环境，无 DOM/fetch/setTimeout），需要浏览器 API 或自定义 UI 必须 `figma.showUI()` 建 iframe；两者仅能 message passing ✅ |
| 贡献点 | manifest 声明入口与运行参数（`main`/`ui`/网络访问），贡献面很窄，主要是命令面板入口与 UI（细节未核实 ⚠️） |
| 热更新粒度 | **远端应用商店分发、无运行时热更**；单次运行以 `closePlugin()` 结束，用户可随时取消并触发 Figma 自己调用 closePlugin ✅ |
| 状态 | 运行内存态随实例结束消失；持久化靠插件数据 API（未核实 ⚠️） |
| 失败隔离 | 无浏览器 API = 攻击面小；插件异常不影响文档（🔍）；执行时长限制未核实 ⚠️ |
| 安全模型 | `networkAccess` 声明允许域名，未声明域名的请求被拦截并返回 CSP 错误；iframe 渲染页的域名限制只作用于该网页自身 ✅；上架审核（未核实 ⚠️） |

**对 Trellis 的启示**：
- 双环境模型是 Track B 的现成蓝本：Zipline/WASM 侧只跑纯逻辑（无 UI 框架、无 IO），Compose 侧把状态渲染进 Slot，二者用「序列化消息 + 显式 capability」通信。这比「让插件直接操作 Compose 树」安全得多，也天然跨端。
- 网络白名单与 kernel-design §6 Capability 默认拒绝同构：manifest 声明 `capability.network(host)`，未声明即运行期拒绝并审计，而非静默失败。
- 「用户可随时取消 → 宿主负责兜底清理」提示 Trellis：Slot 渲染必须能在外层取消（组合取消 + scope cancel），不能依赖插件自觉。参考：<https://developers.figma.com/docs/plugins/how-plugins-run/>

## 4. Obsidian —— 插件级热重载的事实标准（disable→enable）

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **Electron 渲染进程内**，CommonJS `main.js` 被 require；`manifest.json` 描述 id/name/version/minAppVersion（字段细节未核实 ⚠️） |
| 贡献点 | onload 里 `registerView`/`registerCommand`/`addRibbonIcon`/`addSettingTab`/`registerMarkdownPostProcessor` 等，`registerX()` 返回句柄由 Obsidian 在 `onunload` 时清理（社区文档，未核实 ⚠️） |
| 热更新粒度 | **插件级**。热重载插件监视 `main.js`/`styles.css`，文件稳定约 **0.75 秒**后自动 disable→enable 并弹 notice；也监视 `manifest.json` 与 `.git`/`.hotreload` 的增删 ✅ |
| 状态 | 插件数据存 vault 的 `data.json`，重载后 `loadData()` 恢复（未核实 ⚠️） |
| 失败隔离 | 加载失败则插件禁用，不影响其他插件（未核实 ⚠️） |
| 安全模型 | 无沙箱、Node 全权限；靠「受限模式」默认禁用社区插件 + 用户显式信任（未核实 ⚠️） |

**关键设计（官方 README ✅）**：热重载只对「目录含 `.git` 子目录或 `.hotreload` 文件」的插件生效 —— 用**开发态标记**把 HMR 能力限制在开发者机器上，且 `.hotreload` 不会随分发流出，普通用户永远拿不到热重载。README 明确写「**reload 是插件自己的责任**」，必须正确实现 `onunload()` 与各类 `registerX()` 才能干净卸载。参考：<https://github.com/pjeby/hot-reload>

**monkey-around（同作者）的可卸载补丁范式**：`around(obj, {method(old){...}})` 返回 **uninstaller 函数**，支持同方法多层 wrapper、卸载顺序任意；`dedupe(key, old, new)` 让同一补丁只在最老活跃者执行、其余绕过，任一可随时卸载；`serialize()` 串行化异步方法。注意 patch 原型会让所有实例共享串行化语义，一般应 patch 实例。✅ 参考：<https://github.com/pjeby/monkey-around>

**对 Trellis 的启示**：
- **v1 热更新就做「插件级重载」**：`Profile 变更 → 对目标插件 cancel scope → 撤销贡献 → 重新 load → 重放状态快照`。Obsidian 用 0.75s 防抖 + disable/enable 证明这条路对 UI 型产品足够。
- 「dev 标记才启用热重载」值得抄：Trellis 可在 manifest 加 `dev.hotReload: true`，仅当 debug 构建 + 目录含 `.trellis-dev` 时 Loader 开文件监视；release 包内不存在该能力，避免成为攻击面/合规问题。
- `registerX()` 返回句柄 → `Plugin.load()` 的产物必须是 **Disposable 集合**，内核统一回收；`uninstaller` 契约说明「补丁必须能反做」。

## 5. Chrome / WebExtensions MV3 —— service worker 生命周期倒逼状态外置

> 官方文档本次抓取全部失败（developer.chrome.com 不可达）⚠️，以下为公开常识 + 明确标注未核实项，落地前须复核官方文档。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | 扩展后台逻辑在 **service worker**（无 DOM、可被浏览器按空闲策略随时终止）；UI 在 popup/options/扩展页；content script 注入对应渲染进程，运行在隔离世界 ⚠️ |
| 贡献点 | `manifest.json` 声明式：`action`/`commands`/`content_scripts`（含 match pattern）/`options_page`/`permissions`/`host_permissions`/`web_accessible_resources` ⚠️ |
| 热更新粒度 | **扩展包级**；浏览器后台检查更新（周期未核实 ⚠️），`runtime.onUpdateAvailable` 通知、`runtime.reload()` 立即应用；不调用则下次重启加载新版本（语义未核实 ⚠️） |
| 状态 | **SW 随时被终止** → 内存态不可依赖，必须写 `chrome.storage`（MV3 核心约束，官方明确 ⚠️） |
| 失败隔离 | SW 崩溃后由浏览器重新拉起；单扩展异常不影响浏览器与其他扩展（🔍） |
| 安全模型 | `permissions`/`host_permissions` 安装时提示授予，部分可运行时申请；**MV3 禁止远程托管可执行代码**（须全部打包进扩展，未核实官方原文 ⚠️） |

**对 Trellis 的启示**：
- 「进程/宿主随时可能被回收」不只是浏览器问题：Android 低内存杀进程、桌面休眠唤醒，都要求 **Plugin 状态必须外置到 Profile/存储，生命周期方法必须幂等**。kernel-design §11 的 `PluginScope` 重建设计应显式声明「load 可被多次调用，状态从存储恢复」。
- 「禁止远程托管代码」是应用商店发行的硬约束 → **Trellis 轨道 A/B/C 必须分发行渠道**：商店版只能内置制品（更新 = 发新版本），桌面侧可做真热更新；这也解释了为什么 VS Code/Chrome 都不做代码级热替换。
- 声明式 match pattern 是「激活事件」的另一种表达：`content_scripts.matches` 决定何时注入，等价于 Trellis 的 activation 条件声明。

## 6. Blender —— Python register/unregister 对称契约 + 全量 Reload Scripts

> docs.blender.org 本次返回 403（Cloudflare）⚠️，以下按公开资料描述，细节标未核实。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **进程内 Python（bpy）**，插件 = Python 包放在 `scripts/addons`，用户偏好里启用 ⚠️ |
| 贡献点 | `bl_info` 元数据 + 操作符/面板/菜单注册（`bpy.types` 子类 + `register()`/`unregister()`）⚠️ |
| 热更新粒度 | **插件级**（启用/禁用即 import/unregister）；开发态有「Reload Scripts」（F3 搜索，内部调用 `bpy.ops.script.reload()`，命令名未核实 ⚠️）重载全部脚本 |
| 状态/兼容 | 插件偏好独立持久化；`register`/`unregister` 必须严格对称，否则重载残留面板/操作符（未核实 ⚠️） |
| 失败隔离 | 脚本异常打印 traceback，宿主继续运行（未核实 ⚠️） |
| 安全模型 | 无沙箱，Python 全权限；4.2 起官方 Extensions 平台（extensions.blender.org、在线安装/更新、版本约束，细节未核实 ⚠️） |

**对 Trellis 的启示**：`register/unregister` 是行业里最朴素的「可逆生命周期」契约，可作为 `Plugin.load()/unload()` 的语义基线；「全量 Reload Scripts」在开发态很爽、生产态不可接受 → Trellis 开发态可提供 `reloadAll()`，release 态只允许单插件 reload + Profile 回滚。

## 7. Neovim —— 模块缓存（package.loaded）与 dev mode

> 本次未能抓到 Neovim 官方 lua-guide 正文（导航噪声/体积过大）⚠️，以下按 Lua 标准语义 + lazy.nvim 公开文档描述。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **进程内 Lua（LuaJIT）**；插件 = runtimepath 下 `lua/<mod>.lua` + `plugin/*.lua` 启动脚本 ⚠️ |
| 贡献点 | 无正式贡献点；靠 `plugin/` 自动 source、autocmd/User 事件、注册命令/函数；插件管理器（lazy.nvim/packer）用声明式 spec 描述加载条件与依赖 ⚠️ |
| 热更新粒度 | **模块级**：`require` 结果缓存于 `package.loaded`，改文件后须 `package.loaded["mod"]=nil` 再 require 才生效（Lua 标准语义；官方相关章节未核实 ⚠️）；lazy.nvim 提供 `:Lazy reload` 与 dev 模式（本地目录替代安装源，具体键名未核实 ⚠️） |
| 状态 | `_G` 全局表 + 模块缓存；重载后通常需重新 `setup()`；旧引用不会自动失效 ⚠️ |
| 失败隔离 | Lua 错误在 pcall 边界被捕获并报错，通常不崩 nvim（未核实 ⚠️） |
| 安全模型 | 无沙箱，全权限 ⚠️ |

**对 Trellis 的启示**：**模块级热重载的坑在于「缓存与旧引用」**，不是重载动作本身。Trellis 若在轨道 B 提供 `reload(pluginId)`，必须同时：销毁 PluginScope、清插件内模块/对象缓存、撤销贡献、从快照恢复状态；否则就会出现 Neovim 式的「改了没生效 / 两套定义并存」。

## 8. Emacs —— 最细粒度的 eval 与最脆弱的全局状态

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **进程内 elisp 解释器/字节码** ✅ |
| 贡献点 | 无贡献点模型；靠 hook 变量（`add-hook`）、keymap、`advice` 等全局可变状态；包由 package.el 管理（未核实 ⚠️） |
| 热更新粒度 | **form/函数级**：`load` 打开文件逐个 top-level form 读取求值 ✅，等价于 `eval-buffer`；交互上还有 `eval-defun`/`load-file`（未核实命令文档 ⚠️） |
| 状态/兼容 | 全局 symbol 空间无隔离；重定义覆盖旧定义；手册有 Features/Unloading 章节说明 `unload-feature` 机制 ✅ |
| 失败隔离 | 错误进入 debugger/backtrace，Emacs 通常不崩（未核实 ⚠️） |
| 安全模型 | 无沙箱全权限；ELPA 包签名机制（未核实 ⚠️） |

**对 Trellis 的启示**：这是「热更新粒度 vs 可预测性」的反面教材——form 级 eval 最灵活，但旧定义、旧 advice、旧 hook 必然累积漂移，只能靠终止进程收场。**结论：Trellis 不提供语言级 eval/热补丁 API**，把可热更新的单位锁定为「插件」和「Slot 贡献」，换取跨端一致与可回滚。参考：<https://www.gnu.org/software/emacs/manual/html_node/elisp/Loading.html>

## 9. Godot GDExtension —— Native 扩展：高能力、零隔离

> 本次抓取 Godot 官方 GDExtension 文档失败（404/导航噪声）⚠️，以下仅给出可确认边界，**重载能力未核实**。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **进程内 native 动态库**（.gdextension 描述库路径/入口/依赖，引擎 dlopen 后注册类）⚠️ |
| 贡献点 | 在引擎注册自定义 ClassDB 类/单例，能力等同引擎内部代码 ⚠️ |
| 热更新粒度 | 未见官方「运行时替换 native 库」承诺；通常重新加载项目/重启编辑器（未核实 ⚠️） |
| 状态/兼容 | native 对象与引擎 ABI 绑定，版本不匹配即崩溃风险（🔍） |
| 失败隔离 | native 段错误会让整个引擎崩溃，无隔离（🔍） |
| 安全模型 | 无沙箱，等同宿主进程全部权限 ⚠️ |

**对 Trellis 的启示**：轨道 C 原生插件是「最高能力、最低安全、最难卸载」的一档。Trellis 应：① 原生插件仅桌面；② 承诺「更新需重启应用/进程」，不做代码级热替换；③ manifest 固化 `apiVersion` + 制品哈希，版本不符直接 UNAVAILABLE（kernel-design §9 已有此语义，正好复用）。

## 10. Unity + BepInEx —— 托管运行时 domain reload 与不可撤销补丁

> Unity 官方文档本次未抓取 ⚠️；BepInEx 为社区 loader，以下机制描述标未核实。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | Unity：托管 C#（Mono/IL2CPP）；BepInEx：启动早期注入游戏进程，扫描 `BepInEx/plugins` 下 DLL 反射加载 `BaseUnityPlugin` 子类 ⚠️ |
| 贡献点 | 无正式贡献点；靠 Unity 生命周期回调（Awake/Update）与 Harmony 运行时方法 patch ⚠️ |
| 热更新粒度 | Unity 编辑器内改脚本触发编译 + **domain reload**（重载 AppDomain）；播放模式可关闭 domain reload 提速（选项名未核实 ⚠️）；构建后无热更新；BepInEx 一般重启游戏（ScriptEngine 运行时编译 C# 未核实 ⚠️） |
| 状态/兼容 | domain reload 前后静态字段被重置/序列化恢复；Harmony patch 在进程内累积，无法优雅撤销 ⚠️ |
| 失败隔离 | 模组异常可能拖垮游戏进程（🔍） |
| 安全模型 | 无沙箱，模组 = 游戏进程全权限；靠社区信任 ⚠️ |

**对 Trellis 的启示**：
- Harmony 式全局方法 patch 是**不可撤销补丁**的极端案例 → Trellis 的贡献点必须「注册/撤销成对」，**不提供全局方法 hook API**（可作为 kernel-design 反模式条目）。
- BepInEx 的工程细节值得借鉴：目录扫描发现插件、反射实例化、自动生成配置文件、按依赖排序加载 —— 对应 Trellis 的 `discover()`、`PluginLoader`、`pluginConfig` 与 DependencyResolver。

## 11. 远端执行一：Slack App —— manifest 即贡献点，逻辑全在开发者侧

> Slack 官方文档本次未抓取 ⚠️，以下为公开机制描述。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **远端（开发者服务器）**：Events API HTTP 回调或 Socket Mode；Slack 本身不执行业务代码（下一代 Deno 托管运行时未核实 ⚠️） |
| 贡献点 | App Manifest 声明式：scopes、slash commands、shortcuts、event subscriptions、views ⚠️ |
| 热更新粒度 | **服务端部署级**：代码更新对 Slack 无感；manifest 可经 API 更新；OAuth scope 变化通常要求重装应用（未核实 ⚠️） |
| 状态/兼容 | Slack 仅保存 installation/token；业务状态自管 ⚠️ |
| 失败隔离 | 事件回调需快速 ack，超时会被重试（阈值未核实 ⚠️） |
| 安全模型 | 请求签名校验（字段名/timestamp 校验未核实 ⚠️）+ OAuth scope 最小授权 |

**对 Trellis 的启示**：这是「**内核只做事件路由与 capability 代理，插件逻辑在远端**」的样板。Trellis 的云侧能力（AI、同步、搜索）可定义为 remote-execution 插件：本地 manifest 声明能力与端点，内核负责鉴权、配额、审计，失败也不影响本地内核 ACTIVE。

## 12. 远端执行二：Cloudflare Workers —— 版本、灰度、回滚、compatibility date

> 首页正文抓取被截断，但**官方导航结构已确认以下章节存在** ✅（正文未核实 ⚠️）：Versions & deployments（deployment management、Gradual deployments 含 version-affinity、version overrides、Rollbacks、Version URLs）、compatibility dates、compatibility flags、bindings（KV/D1/R2/Queues/Service bindings/Dynamic Worker Loaders 等）。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **远端边缘 V8 isolate**（非容器/非独立 OS 进程），按请求就近执行 ⚠️ |
| 贡献点 | 路由/绑定声明（bindings 即能力注入），无 UI 贡献概念 ⚠️ |
| 热更新粒度 | **部署 = 新版本**；支持灰度（按比例/version affinity）、版本覆盖、一键回滚；客户端无感 ✅（章节存在） |
| 状态/兼容 | compatibility date/flags **冻结运行时行为**，避免升级悄悄改变语义 ✅（章节存在） |
| 失败隔离 | isolate 隔离租户，异常不波及其他 Worker（细节未核实 ⚠️） |
| 安全模型 | 显式 bindings 才能访问资源，无文件系统 ⚠️ |

**对 Trellis 的启示**（三条可直接落地）：
1. **Profile 快照 + 灰度启用**：Profile 天然是「启用集+版本+顺序+配置哈希」，可加 `rollout: {percent|deviceGroup}`，出问题一键回滚——比单机 SAFE 模式更主动。
2. **compatibility date 思路 → manifest `minKernelVersion` / `apiVersion`**：内核升级时按日期/版本冻结旧插件语义，避免「内核一升级插件全炸」。
3. **bindings → Capability 句柄**：插件拿不到全局容器，只能拿内核显式注入的能力对象（与 kernel-design §6 一致）。

## 13. 远端执行三：Shopify Functions —— WASM 纯函数扩展点

> 官方文档本次未抓取 ⚠️，以下为公开架构描述，细节未核实。

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **远端 Shopify 基础设施上的 WASM 函数**；输入 = 由 Function API 生成的 JSON input query，输出 = JSON operations；无网络、无持久状态（纯函数）⚠️ |
| 贡献点 | Function API 类型（discount/cart transform/delivery 等）即扩展点；每个扩展点有显式 API 版本 ⚠️ |
| 热更新粒度 | 随 app 版本部署，无需商家操作；API 版本显式声明 ⚠️ |
| 状态/兼容 | 无状态；配置通过商家 metafields 等外部数据传入（未核实 ⚠️） |
| 失败隔离 | 函数失败则该扩展点降级，不阻断结账主流程（未核实 ⚠️） |
| 安全模型 | WASM 沙箱 + 固定输入输出，最小能力面 ⚠️ |

**对 Trellis 的启示**：这是**轨道 A（声明式）/WASM 轨道**的最佳产品参照：计算型扩展点 = 纯函数 + 显式输入 + 结构化输出 + 无副作用。好处是跨 Android/iOS/桌面行为完全一致、可审计、可重放。建议 kernel-design 把扩展点分成两类：**「计算型」（纯函数，优先 WASM）** 与 **「UI 型」（Slot 声明 + 渲染器，优先声明式）**，二者的热更新与失败策略不同。

## 14. 远端执行四：Envoy / Istio WasmPlugin —— 制品摘要固定 + 双层热更新

| 维度 | 结论 |
| --- | --- |
| 执行位置 | **数据面代理进程内 Wasm VM**（proxy-wasm ABI），模块来自本地文件/HTTPS/OCI 镜像 ✅ |
| 贡献点 | `WasmPlugin` CR：HTTP filter chain 的 `phase`（AUTHN/AUTHZ…）+ `priority` 决定顺序 ✅ |
| 热更新粒度 | **模块级 + 配置级分离**：`url` 变更/`imagePullPolicy: Always` 触发重新拉取；`pluginConfig`（任意 YAML）可独立变更 ✅ |
| 状态/兼容 | `sha256` 固定制品摘要 ✅；OCI tag 拉取策略控制；版本回退 = 改回旧 url/digest ✅ |
| 失败隔离 | `FailStrategy` 显式配置失败开放/关闭（具体枚举值未核实 ⚠️）；单 filter 失败不必然拖垮代理 ✅（字段存在） |
| 安全模型 | Wasm 沙箱 + 受限 host ABI；镜像仓库鉴权；VM 环境变量受控注入 ✅ |

**对 Trellis 的启示**：
- **制品摘要固定 + 配置分离**值得抄：manifest 固定插件包 sha256（对应 kernel-design 的指纹 I8），`pluginConfig` 与代码分离更新，配置变更不必重载代码。
- **显式失败策略**：UI 贡献 `fail-open`（空 Slot + 错误徽标，内核继续 ACTIVE），安全/数据能力 `fail-closed`（拒绝并审计）——比「一律降级」更精细。
- 声明式 `phase`/`priority` 排序 ≈ Trellis 扩展点的 `order`/`dependencies`，也说明「加载顺序」应在 manifest 中声明而非运行时协商。参考：<https://istio.io/latest/docs/reference/config/proxy_extensions/wasm-plugin/>

## 15. 横向对比矩阵

| 系统 | 执行位置 | 隔离强度 | 热更新粒度 | 版本/兼容机制 | 权限模型 | 迁移到 Trellis |
| --- | --- | --- | --- | --- | --- | --- |
| VS Code | 独立进程（Node/WebWorker） | 强（进程） | 扩展包级（重载窗口） | `engines`/`extensionKind` | Workspace Trust ⚠️ | ★★★★★ 懒激活+贡献点 |
| JetBrains | 同进程 + per-plugin classloader | 中（类加载器） | 插件级（动态插件白名单） | since/until-build ⚠️ | 无沙箱 | ★★★★★ 桌面轨道 C |
| Figma | 沙箱 + iframe 双环境 | 强（能力裁剪） | 无（应用商店） | manifest 网络声明 | networkAccess ✅ | ★★★★★ 轨道 B 蓝本 |
| Obsidian | 渲染进程内 | 弱 | 插件级 disable→enable | minAppVersion ⚠️ | 受限模式 ⚠️ | ★★★★★ v1 热更新 |
| Chrome MV3 | SW + content script | 中（权限+SW） | 扩展包级（reload） | MV3 版本 | permissions ⚠️ | ★★★★ 状态外置 |
| Blender | 进程内 Python | 无 | 插件级 + Reload Scripts | Extensions 平台 ⚠️ | 无沙箱 | ★★★ register 对称 |
| Neovim | 进程内 Lua | 无 | 模块级（package.loaded） | 无 | 无沙箱 | ★★★ 缓存陷阱教训 |
| Emacs | 进程内 elisp | 无 | form 级 eval | 无 | 无沙箱 | ★ 反面教材 |
| Godot GDExtension | 进程内 native | 无 | 重启（未核实） | ABI/版本 ⚠️ | 无沙箱 | ★★ native 边界 |
| Unity+BepInEx | 托管运行时/注入 | 无 | domain reload / 重启 | 运行时版本 ⚠️ | 无沙箱 | ★★ 反模式+工程参考 |
| Slack App | 远端开发者服务器 | 强（网络边界） | 服务端部署 | manifest/scope ⚠️ | OAuth scopes | ★★★★ remote-exec |
| Cloudflare Workers | 远端 V8 isolate | 强（isolate） | 部署级 + 灰度/回滚 | compatibility date ✅ | bindings ✅ | ★★★★★ 灰度/回滚 |
| Shopify Functions | 远端 WASM | 强（纯函数） | app 版本部署 | API version ⚠️ | 固定输入输出 | ★★★★★ 计算型 EP |
| Istio WasmPlugin | 代理内 Wasm VM | 强（Wasm） | 模块级 + 配置级 | sha256 + imagePullPolicy ✅ | proxy-wasm ABI | ★★★★★ 摘要+策略 |

## 16. 成熟模式清单（可直接进 kernel-design）

| 模式 | 定义 | 案例证据 | Trellis 落点 | 证据 |
| --- | --- | --- | --- | --- |
| contribution-point | 清单静态声明「我向哪个位置贡献什么」，宿主不运行插件即可渲染 UI/菜单/权限 | VS Code `contributes`；JetBrains `plugin.xml` extensions；Slack manifest | manifest.contributions + §5 扩展点/Slot | ✅ |
| activation-event | 声明式触发条件，命中才加载/激活代码 | VS Code `activationEvents`；Chrome `content_scripts.matches` | manifest.activation（Slot 首次渲染/命令/事件/时间） | ✅ |
| extension-host | 插件在独立宿主（进程/线程/Worker/沙箱）运行，宿主崩溃不带走内核 | VS Code extension host；Figma sandbox | PluginScope + 轨道 B 沙箱；桌面可选独立进程（后续） | ✅ |
| classloader-isolation | 每个插件独立类加载器/模块图，允许不同依赖版本共存 | JetBrains PluginClassLoader + 插件独立依赖排序；BepInEx 目录加载 | Pf4jLoader per-plugin classloader（仅 JVM 桌面） | ✅ |
| dynamic-plugin-whitelist | 只有显式标记为「可动态装卸」的扩展点才允许热卸载，其余声明需重启 | JetBrains dynamic EP 标记 + `require-restart="true"` | manifest/EP 增加 `hotSwappable` 与 `restartRequired` | ✅ |
| unload-contract | 注册与撤销严格对称，插件必须返回可回收句柄 | Obsidian `registerX()`+`onunload()`；monkey-around `uninstaller`；JetBrains `Disposable`/`DynamicPluginListener` | `Plugin.load()` 返回 Disposable 集合，`unload()` 对称 | ✅ |
| disable-enable-reload | 热更新 = 停用旧实例 + 启用新实例，而非原地替换代码 | Obsidian hot-reload（0.75s 防抖、`.hotreload` 标记）；JetBrains 动态插件 | v1 热更新语义：Profile 重放 | ✅ |
| capability-gated | 能力声明式申请，默认拒绝，运行时只交付显式句柄 | Figma `networkAccess`；Cloudflare bindings；Slack scopes；Chrome permissions | §6 Capability 默认拒绝 + 审批门 | ✅/⚠️ |
| versioned-contract | 插件声明所需 API/内核版本，内核按版本提供兼容语义 | Cloudflare compatibility date；Shopify Function API version；VS Code `engines` | manifest `apiVersion`/`minKernelVersion` + §14 契约测试 | ✅/⚠️ |
| snapshot-rollback | 变更前保存完整可运行配置，出问题一键回滚 | Cloudflare versions/Rollbacks；Istio 切回旧 digest | §12 Profile/Snapshot；SAFE 模式回滚入口 | ✅ |
| gradual-rollout | 新版本按比例/分组灰度启用 | Cloudflare Gradual deployments | Profile `rollout`（设备/插件子集灰度） | ✅ |
| sandboxed-runtime | 插件运行在能力受限的运行时（WASM/JS 沙箱），无宿主系统调用 | Istio Wasm VM；Shopify Functions；Figma sandbox | WasmLoader / ZiplineLoader + 受限 SDK | ✅ |
| remote-execution | 插件逻辑在远端，本地只留声明、路由、审计与能力代理 | Slack App；Cloudflare Workers；Shopify Functions；Istio filter | 云侧能力插件（AI/同步/搜索），失败不阻塞内核 | ⚠️ |
| digest-pinned-artifact | 制品内容寻址/摘要固定，安装与回滚都按哈希校验 | Istio `sha256` + OCI；Cloudflare Version URL；反例：Obsidian 社区插件无签名 | 插件包 hash + 签名（kernel-design 指纹 I8） | ✅ |
| fail-open/fail-closed | 失败策略显式声明：UI 降级开放，安全/数据能力关闭 | Istio `FailStrategy`；VS Code 宿主崩溃提示重载 | Slot 失败 fail-open，Capability 失败 fail-closed | ✅/⚠️ |

**明确不采纳的反模式**（有案例证据）：
- **全局方法 monkeypatch / Harmony patch**（Obsidian monkey-around 只能靠 uninstaller 兜底，Unity/BepInEx 无法撤销）→ 不提供全局 hook，只提供贡献点注册。
- **语言级 eval 热补丁**（Emacs/Neovim）→ 旧定义漂移不可控，不暴露 eval API。
- **生态无签名**（Obsidian 社区插件信任模型）→ 必须做制品摘要与可选签名。

## 17. 对 Trellis 最重要的 5 条启示（可验收）

1. **贡献点静态化，激活事件化**（VS Code + Chrome）：manifest 里 `contributions` 与 `activation` 由内核在启动早期解析注册，插件代码延迟到首次触发才 load。验收：冷启动加载 20 个插件时，只有 `onStartup` 类插件被 load，其余 UI 骨架正常渲染。
2. **热更新 v1 只做「插件级重放」，并把「可热卸载」变成显式白名单**（Obsidian + JetBrains）：新增 `hotSwappable`/`restartRequired` 标记；reload = cancel scope → 撤销贡献 → 重新 load → 恢复状态快照。验收：连续 reload 50 次，Slot 注册表与协程无泄漏、UI 无幽灵菜单。
3. **卸载契约对称化**（Obsidian + monkey-around + JetBrains）：`Plugin.load()` 返回 `Disposable` 集合，所有 `registerX()` 必须可从内核撤销；`unload()` 后 list/counters 归零。这是能否安全热更新的分水岭。
4. **隔离分级 + 保守降级**（Figma/Slack/Workers + kernel-design §9）：Android/iOS 不做 classloader 卸载承诺，只承诺 Profile 回滚与重启生效；WASM/Zipline 轨道提供真正可替换沙箱；缺失 Loader → UNAVAILABLE 而非崩溃。验收：manifest 指定 zipline 在 iOS 缺失时，插件显示不可用且内核 ACTIVE。
5. **制品摘要 + 快照灰度回滚**（Istio + Cloudflare）：manifest 固定制品 sha256，Profile 支持灰度和一键回滚；代码与 `pluginConfig` 分开更新。验收：篡改插件包后拒绝加载并审计；回滚到上一 Snapshot 后 30 秒内插件集恢复。

**建议的 kernel-design 增补**：§4.2 manifest 增加 `activation` / `contributions.hotSwappable` / `apiVersion` / `artifact.sha256`；§9 增加「热更新 = Profile 重放」流程；§11 明确 Slot fail-open 与 Capability fail-closed 两条策略；§12 Snapshot 增加 `rollout` 字段。

## 18. 未核实清单（落地前必须复核官方文档）

| # | 未核实项 | 复核 URL |
| --- | --- | --- |
| 1 | VS Code `contributes` 具体字段与「workbench 激活前读取」的实现语义 | <https://code.visualstudio.com/api/references/contribution-points> |
| 2 | VS Code 扩展更新/禁用后的 Reload 提示与生效时机 | <https://code.visualstudio.com/api/working-with-extensions/publishing-extension> |
| 3 | VS Code Workspace Trust 对激活的约束 | <https://code.visualstudio.com/api/extension-guides/workspace-trust> |
| 4 | JetBrains `since-build`/`until-build` 兼容语义、付费插件限制细节 | <https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html> |
| 5 | Figma manifest 字段（`documentAccess`/`networkAccess` 完整语义）与执行时限 | <https://developers.figma.com/docs/plugins/manifest/> |
| 6 | Obsidian manifest 字段、`data.json` 持久化、受限模式默认值 | <https://docs.obsidian.md/Reference/Manifest> |
| 7 | Chrome MV3 SW 生命周期细节、`onUpdateAvailable` 语义、禁止远程代码原文 | <https://developer.chrome.com/docs/extensions/develop/concepts/service-workers> |
| 8 | Blender add-on 契约与 Reload Scripts 命令名、4.2 Extensions 平台细节 | <https://docs.blender.org/manual/en/latest/advanced/scripting/addon_tutorial.html> |
| 9 | Neovim lua-guide 的模块加载/重载章节、lazy.nvim `:Lazy reload` 与 dev 模式配置键 | <https://neovim.io/doc/user/lua-guide.html> |
| 10 | Emacs `eval-defun`/`load-file`/`unload-feature` 的确切语义 | <https://www.gnu.org/software/emacs/manual/html_node/elisp/Unloading.html> |
| 11 | Godot GDExtension 是否支持运行时重载及 ABI 兼容字段 | <https://docs.godotengine.org/en/stable/tutorials/scripting/gdextension/what_is_gdextension.html> |
| 12 | Unity domain reload / Enter Play Mode Options 官方说明；BepInEx 加载与 Harmony 集成 | <https://docs.unity3d.com/Manual/DomainReloading.html> |
| 13 | Slack 事件 ack 超时阈值、签名校验字段、manifest API 更新方式 | <https://api.slack.com/apis/events-api> |
| 14 | Cloudflare Workers isolate 模型、灰度/回滚与 compatibility date 正文 | <https://developers.cloudflare.com/workers/versions-and-deployments/> |
| 15 | Shopify Functions input/output 契约、API 版本与失败行为 | <https://shopify.dev/docs/apps/build/functions> |
| 16 | Envoy 原生 wasm filter 配置项与 `failStrategy` 枚举值 | <https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/wasm_filter> |

## 19. 来源（本次已抓取正文）

- VS Code Extension Host：<https://code.visualstudio.com/api/advanced-topics/extension-host> ✅
- VS Code Activation Events：<https://code.visualstudio.com/api/references/activation-events> ✅
- JetBrains Plugin Class Loaders：<https://raw.githubusercontent.com/JetBrains/intellij-sdk-docs/master/topics/basics/plugin_structure/plugin_class_loaders.md> ✅
- JetBrains Dynamic Plugins：<https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html> ✅
- Figma How Plugins Run：<https://developers.figma.com/docs/plugins/how-plugins-run/> ✅
- Obsidian Hot-Reload：<https://github.com/pjeby/hot-reload> ✅
- monkey-around：<https://github.com/pjeby/monkey-around> ✅
- Emacs Lisp Loading：<https://www.gnu.org/software/emacs/manual/html_node/elisp/Loading.html> ✅
- Istio WasmPlugin：<https://istio.io/latest/docs/reference/config/proxy_extensions/wasm-plugin/> ✅
- Cloudflare Workers 文档导航（正文截断）：<https://developers.cloudflare.com/workers/versions-and-deployments/> ⚠️
