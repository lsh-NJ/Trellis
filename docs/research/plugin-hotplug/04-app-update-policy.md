# 04 · 应用级热更新通道与商店政策红线：Trellis 五层更新的合法边界

- 调研日期：2026-10-22（所有"现行原文"以该日抓取为准）
- 目标：回答「跨平台应用里热更新的合法边界与成熟通道」，并把**插件热更新**与**App 本体热更新**分层
- 证据等级：`✅` 已抓取官方页面/PDF 原文并核对；`⚠️` 官方文档自述（本次未逐字核对）；`❓` 未核实（禁止当结论用）
- 范围：只讨论合法性与机制，不讨论 Trellis 插件内核实现（见 01/02/03）

---

## 0. 结论速览（先看这 10 条）

1. **五层更新能力完全不同**：App 本体与内核受商店政策强约束；轨道 A（声明式 JSON）本质是"数据"，自由度最大；轨道 B（沙箱脚本）受"解释型代码例外"保护但被附加条件；轨道 C（原生插件）在商店版基本只能随包发版。
2. **iOS 轨道 B 可行**：Apple 开发者协议（PLA）§3.3.1.B 原文允许 "Interpreted code may be downloaded to an Application"，条件是 (a) 不改变 App 主要用途、(b) 不绕过签名/沙箱/系统安全、(c) 在 App Store 上不构成商店。但必须同时满足评审指南 **4.7（mini apps / plug-ins）全套要件**，且**不能把原生能力暴露给下载的脚本**（4.7.2）。
3. **iOS 轨道 C 不可热更**：下载并执行原生可执行代码被 PLA §3.3.1.B 禁止；唯一例外是"编程教学环境"（80% 屏幕规则等），不适用于产品插件。
4. **Android 轨道 B 明文合法**：Play《Device and Network Abuse》原文「This restriction does not apply to code that runs in a virtual machine or an interpreter」（如 WebView 里的 JS）。**轨道 C 明文违法**：不得从 Google Play 之外下载 dex/JAR/.so。
5. **"Apple 3.3.2" 是历史编号，必须纠偏**：现行 App Store 评审指南（Last Updated: October 16, 2026）**没有 3.3 段**；现行对应条款在 **Apple Developer Program License Agreement §3.3.1.B**（PDF 版 LYL260，October 13, 2026）。现行 PLA §3.3.2 是 "Regulatory Compliance"，与热更无关。RN CodePush README 引的 "3.3.2" 是 2017 版 PLA 旧编号（其 README 自述版本 20170605）；Shorebird FAQ 引 "3.3.1b" 与现行 PLA 一致。
6. **macOS App Store 版还有一个雷**：PLA §3.3.1.D(e) 禁止 macOS App Store 应用"作为软件分发机制"或内置"应用内插件商店"（原文举例：音频应用不得内置音频滤镜插件商店）→ **Trellis 插件市场进 Mac App Store 版基本不可行**（除非仅以 IAP 售卖数字内容、且不构成软件分发机制——需法务逐案确认）。
7. **桌面直发是唯一全自由度通道**：Electron autoUpdater（仅 macOS/Windows）、Tauri v2 updater（macOS/Windows/Linux，强制签名）、Windows MSIX/App Installer、macOS Sparkle/Squirrel、Linux Flatpak/AppImage。可合法更新"App 本体 + 内核 + A/B/C 三轨道"。
8. **Web 不需要热更通道**：部署即最新（Shorebird FAQ 原文："Code Push isn't needed for Flutter web"）。
9. **开发期热重载 ≠ 生产热更新**：Compose Hot Reload 依赖 JetBrains Runtime 与 desktop target；Android Studio Live Edit 官方仍标注 experimental；Flutter 原文「Only Flutter apps in debug mode can be hot reloaded」。三者都没有签名、灰度、回滚、分发与合规审核能力。
10. **最大合规风险**：把轨道 C 做成"商店版可下载/可执行"→ iOS 触发 PLA 3.3.1.B + 评审指南 2.5.2 拒审/下架；Android 触发 Device and Network Abuse 下架。次高风险：iOS 上把原生 API 暴露给轨道 B 脚本（4.7.2），以及插件市场缺内容审核/索引/年龄门（4.7.1/4.7.4/4.7.5）。

---

## 1. 五层模型与"热更新"口径

| 层 | 内容 | 典型更新对象 | 是否"代码" | 商店政策自由度 |
|---|---|---|---|---|
| L1 App 本体 | KMP 壳 + Compose UI + 内核可执行体 + 轨道 A 运行时 | 整个安装包 | 是（原生） | 最严：只能走商店更新或桌面自更新 |
| L2 内核 | manifest 解析、轨道 A JSON 引擎、轨道 B 解释器宿主、轨道 C loader、权限模型 | 内核二进制/类库 | 是（原生） | 严：商店版必须随包；桌面可从自有通道更新 |
| L3 轨道 A | 声明式 JSON 插件（manifest + 视图/贡献点声明） | 文本资源 | 否（数据） | 宽：可远程拉取；但不得靠数据"变出新功能" |
| L4 轨道 B | 沙箱脚本（JS/WASM，规划 Zipline/Chicory） | 解释器输入 | 是（解释型） | 中：iOS 4.7 + PLA 3.3.1.B；Android VM/解释器例外；桌面自由 |
| L5 轨道 C | 原生插件（桌面 jar / Android dex；iOS 无合法下载通道） | 原生二进制 | 是（原生） | 桌面自由；Android 仅 Play 通道；iOS 禁止 |

**本报告口径**：L1/L2 的更新叫"应用级热更新"（OTA App update）；L3/L4/L5 的更新叫"插件热更新"（plugin hot plug）。两者合规依据不同，不能混用同一套通道，也不能用同一套话术对商店解释。

> 与 03 号文档对齐：Trellis 应把"热更新"的**默认语义**定义为「按新 Profile 重放插件集」，而不是"原地替换代码"。本报告为其补上"哪一层允许用哪条通道"的法律边界。

---

## 2. 应用级热更新通道逐个拆

### 2.1 桌面三平台

**Electron autoUpdater**（`https://www.electronjs.org/docs/latest/api/auto-updater`）✅
- 平台：原文「Currently, only macOS and Windows are supported. There is no built-in support for auto-updater on Linux, so it is recommended to use the distribution's package manager.」
- macOS 实现为 Squirrel.Mac，原文「Your application must be signed for automatic updates on macOS.」；支持 sha256/size 校验、delta 差分、Range 断点续传。
- Linux：交给发行版包管理器（Flatpak/AppImage/apt 等）。
- 合规面：桌面直发不受商店政策约束（mac App Store 版除外）；风险主要是签名与供应链。

**electron-updater**（electron-builder 配套）：社区事实是跨 Win/macOS/Linux + generic/S3/GitHub 等 provider，但本次**未定位到其官方 auto-update 文档页**（`www.electron.build/auto-update` 404）→ `❓未核实`，报告中不给细节。

**Tauri v2 Updater**（`https://v2.tauri.app/plugin/updater/`）✅
- 原文「This plugin requires a Rust version of at least 1.90」；依赖 `tauri-plugin-updater` / `@tauri-apps/plugin-updater`。
- 平台由编译目标决定：官方 `cargo add` 示例的 target 为 `cfg(any(target_os = "macos", windows, target_os = "linux"))` → 桌面三平台，不含 iOS/Android（移动端更新仍走商店）。
- 强制签名：更新清单用私钥签名，公钥写入 `tauri.conf.json`，未签名/验签失败不接受（⚠️ 官方文档描述）。
- 更新源：静态 JSON 或动态更新服务器；Windows 可配 `installMode`（如 `passive` 静默安装带进度条）。

**Windows MSIX / App Installer**（`https://learn.microsoft.com/en-us/windows/msix/app-installer/app-installer-file-overview`）✅
- 原文「Later, when you have an update to the application, you only update the App Installer (.appinstaller) file. When you update the file, the new version of the application is pushed to the user… They just keep using the application as usual, and the update will be delivered to them.」
- 原文「App Installer file downloads and updates support https, http and smb protocols.」
- 关键坑：原文「The ms-appinstaller: URI protocol is disabled by default as of December 2023」→ 不能再做一键浏览器安装，需直接链到 `.appinstaller` 文件由用户双击（企业可用组策略开启）。

**macOS Sparkle**（`https://sparkle-project.org/documentation/`）✅（框架定位）/ `❓`（签名细节）
- 官方定位「open source software update framework for macOS」；直发（非 App Store）macOS 应用的行业标准，Electron 走 Squirrel.Mac 是同类机制。
- 本报告不引用其签名算法细节（EdDSA 等）→ `❓未核实`。

**Linux**
- Flatpak（`https://docs.flatpak.org/en/latest/repositories.html`）✅ 原文：仓库按版本保存差异，更新时「only the difference (or "delta") between two versions needs to be downloaded」，新版本进仓库后「immediately becomes available to users」。
- AppImage（`https://docs.appimage.org/packaging-guide/optional/updates.html`）✅：用 `appimagetool -u` 嵌入 update information + 生成 `.zsync`；可由外部工具（AppImageUpdate/appimageupdatetool）、内置更新器或 libappimageupdate 更新。原文警告「AppImages generated by electron-builder cannot be updated using the usual tools.」
- Snap：本次抓 `snapcraft.io/docs/refresh` 与 `/docs/auto-refresh` 均 404 → `❓未核实`（结论：可在发行版/商店生态内自动刷新，但不作为报告依据）。

**通道对比**

| 通道 | 平台 | 机制 | 签名/校验 | 合规面 | 成熟度 |
|---|---|---|---|---|---|
| Electron autoUpdater | Win/macOS | Squirrel（Mac）/Win 自动选择 | macOS 强制签名；sha256+size | 直发自由 | ✅成熟 |
| electron-updater | Win/macOS/Linux | provider + 差分 | ⚠️未核实 | 直发自由 | ⚠️需核实文档 |
| Tauri v2 updater | Win/macOS/Linux | 静态 JSON/动态服务器 | 强制签名+公钥 | 直发自由 | ✅成熟（v2） |
| MSIX/App Installer | Windows | `.appinstaller` 指向新包 | 包签名 | 直发/Microsoft Store 均可 | ✅成熟（注意 2023 协议禁用） |
| Sparkle | macOS | appcast + 包 | ⚠️未核实细节 | 直发自由 | ✅成熟 |
| Flatpak | Linux | OSTree 仓库 delta | GPG/仓库签名 | 发行生态合规 | ✅成熟 |
| AppImage | Linux | zsync update info | ⚠️视工具 | 发行生态合规 | ⚠️工具碎片化 |

### 2.2 移动端

**Shorebird（Flutter 代码推送）**（`https://docs.shorebird.dev/code-push/`、`/code-push/faq`）✅
- 机制原文：「Apps built with Shorebird include a modified Flutter engine, which checks for updates to your app's Dart code on startup. If an update is available, the engine downloads the update. The user will see the update on the next app restart」；后台线程检查，也可用 `package:shorebird_code_push` 主动触发。
- 可 patch 判定：纯 Dart 代码变更 → 可 patch；含原生代码（Java/Kotlin/Swift/ObjC）或 asset 变更 → 必须发商店新版本（工具默认阻断，`--allow-native-diffs`/`--allow-asset-diffs` 仅限边角场景）。
- 平台矩阵（FAQ "What platforms does Shorebird support?"）：Dart Code Patching (OTA) 在 Android/iOS/macOS/Windows/Linux 均为支持；Add-to-app 仅 Android/iOS；obfuscate、Patch Signing/KMS 全平台支持（⚠️ 以 FAQ 页面为准，未逐字抄表）。
- 合规立场原文：「Shorebird automatically checks for native code and asset changes before creating a patch. Ensuring the content of your Dart changes complies with App Store and Play Store behavioral policies remains your responsibility.」
- iOS 合规依据（FAQ 原文，编号标为 3.3.1b）：「interpreted code may be downloaded to an Application but only so long as such code: (a) does not change the primary purpose... (b) does not create a store or storefront... (c) does not bypass signing, sandbox, or other security features of the OS. "Shorebird uses a custom Dart interpreter to comply with the interpreter-only restriction for updates on iOS."

**React Native CodePush**（`https://github.com/microsoft/react-native-code-push`；退役说明 `https://learn.microsoft.com/en-us/appcenter/retirement`）✅
- 定位：只更新 JS bundle 与图片，不更新原生代码。README「Store Guideline Compliance」一节自述：Play 侧引用 Device and Network Abuse 的 VM/解释器例外；App Store 侧引用 **PLA 3.3.2（2015 年起，20170605 版）** 的解释型代码条款。
- 现状：App Center 原文「Visual Studio App Center is scheduled for retirement on March 31, 2025」；CodePush 已拆为独立开源（可脱离 App Center 运行）。→ **可用但需自建服务端，且文档对 Apple 编号的引用已过时**（见 §3.3）。

**Expo EAS Update**（`https://docs.expo.dev/eas-update/introduction/`）✅
- 原文：「This is a way of shipping quick fixes a snap in between app store submissions. It accomplishes this by enabling an app to update its own non-native pieces (such as JS, styling, and images) over-the-air. All apps that include the expo-updates library have the ability to receive updates.」
- 前提：先发一个包含 `expo-updates` 的新构建；之后 `eas update --channel production`。

**Capacitor / Ionic Appflow**（`https://ionic.io/appflow`）✅
- 已停止新售：官方博文原文「we have discontinued new customer sales for all Ionic commercial products and services—including Ionic Appflow…」；「Appflow users: You'll continue to have access through December 31, 2027.」
- → **不可作为新项目的生产依赖**，只能作为既有客户过渡方案。

**Android 官方通道**
- In-app updates（`https://developer.android.com/guide/playcore/in-app-updates`）⚠️：Play Core 库；immediate（全屏阻塞）/flexible（后台下载、可继续用）两种 UX；Android 5.0(API 21)+；**仅对 Google Play 分发的应用生效**。
- Play Feature Delivery（`https://developer.android.com/guide/playcore/feature-delivery`）⚠️：动态功能模块 delivery 类型 install-time / fast-follow / on-demand，由 Play Core 按需请求安装——**下载与校验仍由 Google Play 完成**，这是"合法地按需下发代码"的唯一商店通道。
- 自更新：Play 政策禁止（见 §3.4）。

| 通道 | 平台 | 能更新什么 | 合规依据 | 成熟度 |
|---|---|---|---|---|
| Shorebird | Android/iOS/macOS/Win/Linux（Dart） | Dart 代码（无原生/asset） | iOS PLA 3.3.1.B + 4.7；Android 解释器例外 | ✅商用成熟 |
| CodePush（OSS） | Android/iOS | JS bundle + 图片 | 同上（文档编号过时） | ⚠️可用但需自建 |
| EAS Update | Android/iOS | JS/样式/图片 | 同 CodePush 逻辑 | ✅成熟 |
| Ionic Appflow | Android/iOS | Web 资源 | 同 CodePush 逻辑 | ⛔停售（存量至 2027-12-31） |
| Play in-app updates | Android | 整包（Play 下发） | Play 更新机制 | ✅成熟 |
| Play Feature Delivery | Android | 动态模块（Play 下发） | Play 更新机制 | ✅成熟 |

---

## 3. 商店政策红线：原文 + 对 Trellis 的逐条含义

### 3.1 Apple App Store Review Guidelines 2.5.2（代码与自包含）

来源：`https://developer.apple.com/app-store/review/guidelines/`（页面自标 Last Updated: October 16, 2026）✅

> **2.5.2** Apps should be self-contained in their bundles, and may not read or write data outside the designated container area, nor may they download, install, or execute code which introduces or changes features or functionality of the app, including other apps. Educational apps designed to teach, develop, or allow students to test executable code may, in limited circumstances, download code provided that such code is not used for other purposes. Such apps must make the source code provided by the app completely viewable and editable by the user.

对 Trellis 的含义：
- **L1 App 本体**：必须自包含；不能"下载一个新版 UI/业务逻辑包然后替换"。iOS 的应用级热更只能在"解释型代码"框架内做（见 3.3）。
- **L2 内核**：视为 App 的一部分，不能下载安装；升级内核 = 发新版本。
- **L3 轨道 A**：JSON 属数据，可远程拉取。但若某份 JSON 能让 App "变出"未随包发布的功能/界面，Apple 可判定为"changes features or functionality"→ 谨慎：轨道 A 的 schema 只允许组合**已发布的 capability**，且新 capability 必须随 App 版本发布。
- **L4 轨道 B**：脚本本身是 code，不能靠 2.5.2 洗白；它依赖的是 PLA 3.3.1.B 的"解释型代码"例外 + 4.7 的 mini apps/plug-ins 例外。**必须纯解释执行，不引入新原生能力**。
- **L5 轨道 C**：iOS 上不可下载/不可执行 → 只能随包内置，或干脆不提供（见 §5 矩阵）。
- 教育例外（编程教学环境）与 Trellis 无关，不要试图套用。

### 3.2 Apple 4.7：mini apps / plug-ins 例外（轨道 B 的正面依据）

来源同上 ✅（原文）

> **4.7** Mini apps, mini games, streaming games, chatbots, plug-ins, and game emulators — Apps may offer certain software that is not embedded in the binary, specifically HTML5 and JavaScript mini apps and mini games, streaming games, chatbots, and plug-ins. Additionally, retro game console and PC emulator apps can offer to download games. You are responsible for all such software offered in your app, including ensuring that such software complies with these Guidelines and all applicable laws. Software that does not comply … will lead to the rejection of your app. You must also ensure that the software adheres to the additional rules that follow in 4.7.1 through 4.7.5.

> **4.7.1**（摘录，原文为连续条文）… (a) follow all privacy guidelines … 5.1 …; (b) include a method for filtering objectionable material, a mechanism to report content and timely responses, and the ability to block abusive users; (c) follow Guideline 3.1 in order to offer digital goods or services to end users.

> **4.7.2** Your app may not extend or expose native platform APIs or technologies to the software without prior permission from Apple.

> **4.7.3** Your app may not share data or privacy permissions to any individual software offered in your app without explicit user consent in each instance.

> **4.7.4** You must provide an index of software and metadata available in your app. It must include universal links that lead to all of the software offered in your app.

> **4.7.5** Your app must provide a way for users to identify software that exceeds the app's age rating, and use an age restriction mechanism based on verified or declared age to limit access by underage users.

逐条落到 Trellis（iOS）：
| 条款 | 对 Trellis 的产品要求 |
|---|---|
| 4.7 主体 | iOS 版可提供**下载的** HTML5/JS 插件（轨道 B）与插件市场；但 Trellis 对每个插件内容负全责，违规插件 → 整个 App 被拒/下架 |
| 4.7.1(a) | 插件不得违反隐私规则（不得偷偷采集、不得绕过 ATT/隐私清单） |
| 4.7.1(b) | 必须有：不良内容过滤、举报入口、及时响应、拉黑滥用用户 |
| 4.7.1(c) | 插件内售卖数字商品必须走 IAP |
| 4.7.2 | **轨道 B 脚本只能调用 Trellis 自有沙箱 API**；不得把 iOS 原生 API（文件、网络原生接口、通知、蓝牙等）以 bridge 形式暴露给下载脚本；若要暴露须事先取得 Apple 许可 |
| 4.7.3 | 脚本使用用户数据/权限必须逐次显式同意 |
| 4.7.4 | 插件市场必须提供"索引 + 元数据 + universal links"（每个插件都要有可跳转的链接） |
| 4.7.5 | 插件内容若超出 App 年龄分级，必须可识别并做年龄门 |

### 3.3 Apple PLA §3.3.1.B（原"3.3.2"）：解释型代码的真实法律依据

**编号纠偏（重要，报告不要沿用错误编号）**
- 现行 App Store 评审指南（2026-10-16 版）**没有 3.3 段**：3.x 只有 3.1 Payments（含 3.1.1/3.1.2/3.1.3/3.1.5）、3.2 Other Business Model Issues（3.2.1/3.2.2），随后进入 4.x。✅（本次全文抓取核对）
- 任务中点名的 **"3.3.2"** 实际是 **Apple Developer Program License Agreement（PLA）** 的历史编号；现行 PLA 中该内容位于 **§3.3.1.B "Executable Code"**；现行 PLA 的 **§3.3.2 是 "Regulatory Compliance"**（FDA/FAA 等），与热更新无关。✅（PLA PDF 全文核对）
- 佐证：RN CodePush README 写「Paragraph 3.3.2, since back in 2015's Apple Developer Program License Agreement… and in its latest version (20170605)」——即它引用的是 2017 版 PLA 旧编号；Shorebird FAQ 引「3.3.1b」与现行 PLA 一致。

PLA 下载：`https://developer.apple.com/support/terms/` → `https://developer.apple.com/support/downloads/terms/apple-developer-program/Apple-Developer-Program-License-Agreement-English.pdf`（PDF 页脚 LYL260，October 13, 2026）✅

> **§3.3.1.B Executable Code**（原文；教育环境条款 (i)–(iv) 为节选）Except as set forth in the next paragraph, an Application may not download or install executable code. Interpreted code may be downloaded to an Application but only so long as such code: (a) does not change the primary purpose of the Application by providing features or functionality that are inconsistent with the intended and advertised purpose of the Application (b) does not bypass signing, sandbox, or other security features of the OS; and (c) for Applications distributed on the App Store, does not create a store or storefront for other Applications.
>
> An Application that is a programming environment intended for use in learning how to program may download and run executable code so long as: (i) no more than 80 percent of the Application's viewing area or screen may be taken over with executable code…; (ii) …conspicuous indicator…programming environment; (iii) …must not create a store or storefront…; (iv) the source code provided by the Application must be completely viewable and editable by the user…

> **§3.3.1.D(e)（macOS App Store）** …such Applications may not function as a distribution mechanism for software and may not include features or functionality that create or enable a software store, distribution channel or other mechanism for software delivery within such Applications (e.g., an audio application may not include an audio filter plug-in store within the Application).

对 Trellis 的含义：
- **iOS 轨道 B 合法性来源就是这个 (a)(b)(c)**：脚本必须纯解释执行；不得改变 App 主要用途；不得绕过签名/沙箱；不得变成"插件商店里的商店"。
- **轨道 C 仍禁**："download or install executable code" 的禁令只对 interpreted code 开口子，原生二进制不在例外内。
- **macOS App Store 版的插件市场高度受限**（3.3.1.D(e) 的 "audio filter plug-in store" 例子几乎点名插件商店；该句开头以 IAP 条款作例外，即"卖数字内容"与"软件分发机制"仍是两回事，需法务确认边界）；macOS 直发版不受此约束。
- App Store 提审时，Trellis 的"可下载插件"必须在审核备注中明确：解释器类型、能力白名单、审核流程、索引与年龄门。

### 3.4 Google Play《Device and Network Abuse》：动态代码加载

来源：`https://support.google.com/googleplay/android-developer/answer/9888379` ✅（页面未标注生效日期 → `❓`）

> An app distributed via Google Play may not modify, replace, or update itself using any method other than Google Play's update mechanism. Likewise, an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser).

> Apps or third-party code, like SDKs, with interpreted languages (JavaScript, Python, Lua, etc.) loaded at run time (for example, not packaged with the app) must not allow potential violations of Google Play policies.

违规示例（同页）：「Apps or third party code (for example, SDKs) that download executable code, such as dex files or native code, from a source other than Google Play.」；Don't 清单：「Don't use third-party SDKs in your app that download executable code (like dex or .so files) from outside Google Play (except in VMs/interpreters).」

技术侧硬化：Android 14（API 34+）行为变更页（`https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading`）✅
> If your app targets Android 14 (API level 34) or higher and uses Dynamic Code Loading (DCL), all dynamically-loaded files must be marked as read-only. Otherwise, the system throws an exception. We recommend that apps avoid dynamically loading code whenever possible, as doing so greatly increases the risk that an app can be compromised by code injection or code tampering.

对 Trellis 的含义：
- **Android 轨道 B 合法**：JS/脚本在解释器或 VM 中运行、只通过 Trellis 自有 API 间接访问 Android API → 政策明文豁免。
- **Android 轨道 C 违法**（Play 版）：不得自 Google Play 之外下载 dex/JAR/.so；即"插件市场下载原生插件"在 Play 版不可行。
- 合法替代：①把原生插件做成 **Play Feature Delivery 动态模块**（Play 下发 + Play 校验）；②直接随包发版；③仅对非 Play 渠道（自建 APK 分发）提供轨道 C 下载。
- 解释型语言段要求"不得让脚本造成政策违规"→ 轨道 B 必须有：能力白名单、内容审核、举报/下架、版本回滚（对应 Apple 4.7.1）。
- Android 14 的"动态加载文件必须只读"是技术约束（对轨道 C 的自研 loader 同样生效），且 Google 明确"建议尽量避免 DCL"。

### 3.5 iOS / Android 红线对照

| 问题 | iOS | Android（Play 分发） |
|---|---|---|
| 能否运行轨道 B 沙箱脚本？ | ✅ 可以（PLA 3.3.1.B + 4.7）；必须纯解释器、不暴露 native API、内容合规 | ✅ 可以（VM/解释器例外）；脚本内容不得造成政策违规 |
| 能否下载插件？ | ✅ 可以（4.7 明确 HTML5/JS mini apps 与 plug-ins）；需索引+universal links+审核+年龄门+IAP 规则 | ✅ 数据/脚本插件可以；原生插件不行 |
| 能否热更 dex / 原生代码？ | ⛔ 禁止（含轨道 C 与"下载内核"） | ⛔ 禁止（dex/JAR/.so 只能来自 Play） |
| 能否热更 App 本体？ | ⛔ 只能发版；解释型代码内容可 OTA | ⛔ 只能走 Play 更新机制（in-app updates / Feature Delivery） |
| 桌面端 | 直发版限制最小；Mac App Store 版受 3.3.1.D(e) 禁插件商店 | 不适用 |

---

## 4. 开发期热重载：只解决开发循环，不能当生产通道

**Compose Hot Reload（JetBrains）** `https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-hot-reload.html` ✅
- 机制原文：「Compose Hot Reload runs your application on the JetBrains Runtime and intelligently reloads your code whenever it is changed.」
- 前提原文：「The bundled Compose Hot Reload Gradle plugin requires Kotlin 2.1.20+ and a JVM target compatible with Java 21 or earlier.」
- 平台限制原文：「Starting with Compose Multiplatform 1.10.0, the Compose Hot Reload plugin is bundled and enabled by default for all projects that include a desktop target.」「While we explore adding support for other targets, you can already use the desktop app as your sandbox…」
- 工具形态：Gradle 插件 + IDE 插件（IntelliJ IDEA 2025.2.2+ / Android Studio Otter 2025.2.1+），另有 `hotMcpServer` 供 agent 调用 reload/screenshot/logs。
- **结论**：这是"桌面 target + JetBrains Runtime + IDE/Gradle 调试通道"的开发者工具，**没有任何发布/签名/灰度/回滚/商店审核语义**，不能作为生产插件热更方案，也不能替代 §2 通道。

**Android Studio Live Edit / Apply Changes** `https://developer.android.com/studio/run` ✅（抓取其镜像页）
- 原文：「Live Edit is an experimental feature in the Android Studio that lets you update composables in emulators and physical devices in real time.」
- 原文：「By default, Android Studio builds the debug version of your app, which is intended for use only during development, when you click Run.」（⚠️ 截至抓取日页面仍写 experimental）
- **结论**：调试构建 + IDE 连接，不可发布。

**Flutter hot reload** `https://docs.flutter.dev/tools/hot-reload` ✅
- 原文：「Only Flutter apps in debug mode can be hot reloaded or hot restarted.」
- 原文：「If you've changed native code (such as Kotlin, Java, Swift, or Objective-C), you must perform a full restart (stop and restart the app) to see the changes take effect.」
- **结论**：debug/JIT 专用；生产对应物是 Shorebird（§2.2），且只覆盖 Dart。

**React Native Fast Refresh / Expo**：开发期热重载；生产对应物是 EAS Update/CodePush（§2.2）。

**四条"不能当生产"的硬理由（通用）**：
1. 只对**调试构建/连接中的设备**生效，release 包不加载；
2. 无**签名与来源校验**（IDE 通道 = 开发者本机直连），无法证明制品来源；
3. 无**灰度、回滚、kill switch、目标人群**能力；
4. 不满足商店要求的**内容审核/索引/年龄门/隐私**（4.7.1–4.7.5）与"不得静默改功能"（2.5.2）。

---

## 5. 五层 × 六平台「合规 × 可行热更新通道」矩阵

图例：`✅` 成熟可行 / `⚠️` 可行但需谨慎（条件合规） / `⛔` 禁止或不可行 / `—` 不适用

| 层 | Windows | macOS（直发） | macOS（App Store） | Linux | Android（Play） | iOS | Web |
|---|---|---|---|---|---|---|---|
| L1 App 本体 | ✅ MSIX/Electron/Tauri | ✅ Sparkle/Squirrel/Tauri | ⚠️ 仅 App Store 发版 | ✅ Flatpak/AppImage/包管理器 | ⚠️ 仅 Play 更新（in-app updates） | ⚠️ 仅 App Store 发版 | ✅ 部署即最新 |
| L2 内核 | ✅ 随本体自更新 | ✅ 随本体自更新 | ⚠️ 随本体（App Store 审核） | ✅ 随本体 | ⚠️ 随 APK（Play 更新） | ⛔ 不得下载；必须随包 | ✅ 随部署 |
| L3 轨道 A（JSON） | ✅ 远程拉取 | ✅ | ⚠️ 数据可远程；不得借此"新增功能" | ✅ | ⚠️ 同左（数据≠代码，但功能边界受审） | ⚠️ 同左 | ✅ |
| L4 轨道 B（脚本） | ✅ 自建签名通道 | ✅ | ⚠️ 4.7 + PLA 3.3.1.B 全套条件 | ✅ | ✅ 解释器例外（脚本内容合规责任） | ⚠️ 4.7 全套条件 + 纯解释器 | ✅ |
| L5 轨道 C（原生） | ✅ jar 热插拔（进程隔离） | ✅ | ⚠️ 仅内置随包（不得内置插件商店） | ✅ | ⛔ 禁下载 dex；只能 Feature Delivery/内置 | ⛔ 禁止 | — |

**每平台推荐主通道**

| 平台 | App 本体 | 内核 | 轨道 A | 轨道 B | 轨道 C |
|---|---|---|---|---|---|
| Windows | MSIX/App Installer 或 Tauri updater | 同一通道（签名校验） | 自有 CDN + 签名清单 | 自有 CDN + 签名脚本包 | 内置 loader + 进程隔离 |
| macOS 直发 | Sparkle/Squirrel/Tauri | 同左 | 同左 | 同左 | 同左（公证+签名） |
| macOS App Store | App Store | 同 L1 | 数据远程（保守） | 4.7 合规后才可下载 | 仅随包内置 |
| Linux | Flatpak/AppImage | 同左 | 同左 | 同左 | 同左 |
| Android | Play in-app updates | 随 APK | 远程 JSON | 内置解释器 + 远程脚本 | Play Feature Delivery 或随包 |
| iOS | App Store | 随包 | 远程 JSON（保守） | 内置解释器 + 4.7 合规 + 审核后下发 | 随包（不可下载） |
| Web | 常规发布 | 同左 | 同左 | 同源脚本（CSP 约束） | — |

**一句话总结**：桌面直发 = 全层可热更；Android = L1 走 Play、L2 随包、L3 自由、L4 自由（解释器）、L5 只能 Play 下发；iOS = L1/L2 随包、L3 谨慎、L4 条件合规、L5 禁止；Web = 全层靠发布。

---

## 6. 对 Trellis 的落地建议（按优先级）

1. **把"热更新"拆成两个词**：`app update`（L1/L2，走商店/桌面更新器）与 `plugin update`（L3/L4/L5，走 Trellis 自有通道）。工程与法务口径必须分开，不要用同一份"OTA"文案。
2. **内核契约先行**：L2 与 L3/L4/L5 之间定义版本化 capability contract；插件 manifest 声明 `requiresCore`，内核拒绝加载不满足的插件（这是"不改变 App 功能"的工程保证，也是 2.5.2/4.7 的举证材料）。
3. **轨道 A 的 schema 只允许组合已发布 capability**；新增 capability 必须随 App 版本发布，禁止"JSON 里写个新视图类型就长出功能"。
4. **轨道 B 的沙箱边界**：iOS 只暴露 Trellis 自有 API；不把原生 API 透传给脚本（4.7.2）；解释器纯软件实现（不用 JIT）；脚本包必须签名 + 可回滚。
5. **iOS 上架配置**：插件市场 = 索引页 + universal links + 元数据（4.7.4）+ 举报/拉黑/过滤（4.7.1b）+ 年龄门（4.7.5）+ IAP（4.7.1c）；审核备注写清解释器与能力白名单。
6. **Android 双轨**：Play 版只开放 L3/L4 + Feature Delivery；"轨道 C 原生插件下载"仅对非 Play 渠道（自建分发）开放，并在代码里按构建 flavor 关闭。
7. **桌面优先落地**：Windows（MSIX 或 Tauri updater）+ macOS 直发（Sparkle/Squirrel）+ Linux（Flatpak/AppImage）三通道都支持"App 本体 + 内核 + 三轨道"全量更新，是 Trellis v1 唯一能"真热更"的地方。
8. **签名与回滚基础设施**：更新清单签名（Ed25519/EdDSA 类）、sha256、灰度百分比、服务端 kill switch、原子切换与降级。Tauri 的"公钥进配置、强制验签"与 MSIX 的"只改 .appinstaller"是最省事的两条路。
9. **合规证据链**：每次插件/脚本发布留审核记录、内容哈希、发布人、影响面；用户端可查看已安装插件清单（同时满足 4.7.4 索引与信任需求）。
10. **风险登记**：①iOS 审核对"可下载插件"的自由裁量（4.7 主体"you are responsible"）→ 预留提审周期；②Android 政策对"解释型代码"的边界解释权 → 脚本能力白名单化；③Mac App Store 版若需插件市场 → 只能改走直发版；④CodePush/Appflow 类第三方通道的存续风险（App Center 退役、Ionic 停售）→ 不把关键路径绑在单一商业服务上。

---

## 7. 未核实与待办（不要当结论引用）

- `❓` electron-builder / electron-updater 官方 auto-update 文档页未定位（`www.electron.build/auto-update*` 均 404）；其跨平台能力需另找官方页。
- `❓` Snap 的自动刷新官方专页（`snapcraft.io/docs/refresh`、`/docs/auto-refresh` 均 404）；本报告不引用 Snap 结论。
- `❓` Tauri updater 的 "Supported Platforms" 表格文本未逐字取得；仅据官方 `cargo add` target `cfg(macos, windows, linux)` 与移动端 `#[cfg(desktop)]` 示例判断为桌面三平台。
- `❓` Android Studio Live Edit 是否已从 experimental 转正：抓取日页面仍写 "experimental"（2026-10-22）。
- `❓` Google Play 政策页未标注"最后更新/生效日期"。
- `❓` 2017 版 Apple 评审指南/PLA 中 "3.3.2" 的历史原文未直接取得（archive.org 返回 429 限流）；本报告改用"现行 PLA PDF + 两家厂商文档转引"交叉验证。
- `❓` macOS Sparkle 的签名算法与最低系统版本未逐字核实（只核实"是官方文档站的 macOS 更新框架"）。
- 待办：若团队要写对商店的"插件审核说明"，应把 4.7.1–4.7.5 与 Play 的"interpreted languages must not allow potential violations"逐条映射成产品需求单。

---

## 8. 参考链接（全部为本次抓取/核对的官方来源）

**Apple**
- App Store Review Guidelines（Last Updated: October 16, 2026）：https://developer.apple.com/app-store/review/guidelines/
- Apple Developer Program License Agreement（英文 PDF，LYL260 / October 13, 2026）：https://developer.apple.com/support/downloads/terms/apple-developer-program/Apple-Developer-Program-License-Agreement-English.pdf
- Apple 协议与指南索引（Last update: August 27, 2026）：https://developer.apple.com/support/terms/

**Google / Android**
- Device and Network Abuse：https://support.google.com/googleplay/android-developer/answer/9888379
- In-app updates：https://developer.android.com/guide/playcore/in-app-updates
- Play Feature Delivery：https://developer.android.com/guide/playcore/feature-delivery
- Android 14 behavior changes / Safer dynamic code loading：https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading

**桌面通道**
- Electron autoUpdater：https://www.electronjs.org/docs/latest/api/auto-updater
- Tauri v2 Updater：https://v2.tauri.app/plugin/updater/
- Windows MSIX App Installer file overview：https://learn.microsoft.com/en-us/windows/msix/app-installer/app-installer-file-overview
- Sparkle（macOS）：https://sparkle-project.org/documentation/
- Flatpak Repositories：https://docs.flatpak.org/en/latest/repositories.html
- AppImage making AppImages updateable：https://docs.appimage.org/packaging-guide/optional/updates.html

**移动端代码推送**
- Shorebird Code Push：https://docs.shorebird.dev/code-push/
- Shorebird FAQ（Store compliance / What can't Code Push be used for / Platforms）：https://docs.shorebird.dev/code-push/faq
- React Native CodePush（含 Store Guideline Compliance）：https://github.com/microsoft/react-native-code-push
- App Center 退役说明：https://learn.microsoft.com/en-us/appcenter/retirement
- Expo EAS Update：https://docs.expo.dev/eas-update/introduction/
- Ionic Appflow 与商业产品停售公告：https://ionic.io/appflow 、https://ionic.io/blog/important-announcement-the-future-of-ionics-commercial-products

**开发期热重载**
- Compose Hot Reload（KMP 文档）：https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-hot-reload.html
- Compose Hot Reload（GitHub）：https://github.com/JetBrains/compose-hot-reload
- Flutter hot reload：https://docs.flutter.dev/tools/hot-reload
- Android Studio Run / Apply Changes / Live Edit：https://developer.android.com/studio/run
