# 手机端（v1.1）：最新版功能的移动端适配

> 对应提交：把桌面版（`:app:desktop` 单模块）重构成「共享运行时 + 两套壳」，
> 手机界面照第一版 APK 的设计重写，功能对齐桌面最新版（知识网络、管家、AI 自动完成、计时器…）。
> 上一版的设计记录见本文件末尾「与第一版的差异」。

## 1. 一句话结构

**同一套运行时，两套界面；平台差异只有一圈 `expect/actual`。**

```
:kernel / :sdk-domain / :features / :ai / :plugin-* / :ui-kernel / :net
        ↑ 全部 jvm + android 双端编译（能共用的都共用）
:app:ui       运行时 + 桌面界面 + 手机界面（jvmAndAndroidMain）
  ├── :app:desktop   桌面壳：一个 window + 打包配置（Main.kt 也在 :app:ui 的 jvmMain）
  ├── :app:mobile    安卓壳：Activity + 清单 + 图标 + edge-to-edge + 通知注入
  └── :app:mobile-preview  预览器：把 PhoneApp 按 390×844 / 834×1112 渲染成 PNG
```

`build-mobile.cmd` / `preview-mobile.cmd` 已在仓库根目录。

## 2. 手机界面（`dev.trellis.app.mobile`）

五个底部页签，一次一件事；内容列限宽 560dp（平板上居中，不拉成一条长线）。

| 页 | 内容 |
|---|---|
| 今日 | 页头（日期/剩余/连续天数）、模拟时间徽章、插件卡片（`slot.home.*`）、快捷添加、逾期/今天/之后/无日期/今天完成/更早完成、任务详情弹层（改标题、完成、开始计时、记投入、改日期、总结、删除） |
| 计划 | 周条（已排周 + 待安排）、逾期补做、计划节奏报告、七日历条、当日单元（完成/今天做/挪一天/跳过/排到指定的一天）、AI 自动完成账本（撤销/证据/解除屏蔽）、待安排列表、待办池、计划切换、每日投入设置 |
| 对话 | 会话列表（管家/未读标记）、过程折叠、执行清单、管家建议卡（采纳/调整/先这样）、撤销这一轮、选项、停止、未配置模型时的引导 |
| 知识 | 笔记 / 网络两个分段；笔记：搜索、新建、Markdown 阅读、编辑、保存并整理（概念 + 空位补全 + `[[标注]]`）、导出、删除；网络：结构树（可折叠）/ 总览画布（拖动缩放、点节点详情）/ AI 建议（确认/忽略）/ 诊断 |
| 我的 | 外观、主题包、每日投入、模型 API 导入、管家（早晚时间 + 通知）、笔记联动通知、活动数据源、时间调试（模拟今天 + 触发晨/晚总结）、插件/活动/诊断入口、关于 |

二级页：插件（状态/启停/能力授权/错误）、活动、诊断（后两者复用桌面单列页，套手机页头）。

### 设计基线（沿用第一版）

- 间距六档 `Gap.xs=4 / s=8 / m=12 / l=16 / xl=20 / xxl=28`；控件最小高度 `Touch=48dp`。
- 卡片 12dp 圆角、1dp 描边、无投影；按钮 10dp 圆角、48dp 高。
- 纸底 `#F6F3EB`、卡片 `#FDFBF5`、墨色 `#22221E`、松绿 `#3E6B56`；暗色一套对应值。
- 数字与小标用 IBM Plex Mono，大标题用 Barlow Condensed，正文系统字体。
- 复用组件在 `PhoneKit.kt`：`CardBox / SectionLabel / PhoneButton / PhoneField / BigStat / Pill / Segmented / ChipRow / SubPageBar / EmptyState / CheckCircle / InfoRow / DividerLine / SheetHeading`。**先在 PhoneKit 里找，找不到再新写。**

## 3. 平台差异（就这几处）

| 能力 | 桌面 | 安卓 |
|---|---|---|
| `HostPlatform.copyToClipboard` | AWT 剪贴板 | `ClipboardManager`（Context 由 `MainActivity` 注入） |
| `decodeImage` | Skia | `BitmapFactory` |
| `chooseExportFile` | 系统保存面板 | 应用 `exports/` 目录 |
| `captureScreen / clickScreen / pressKey / typeText` | AWT Robot 真做 | 返回「手机端不支持…（系统沙箱限制）」，不抛异常 |
| `resizePointerIcon` | 左右箭头 | 默认图标（手机没有 hover） |
| `AppFonts` | classpath `resources/fonts` | `res/font` + `Font(resId)` |
| `PlatformMarkdownEditor` | 可选 JavaFX WebView | 只有 Compose 编辑器 |
| 通知 `Notifier` | osascript / PowerShell / notify-send | `NotificationManager` + channel（`installAndroidNotifications` 注入） |
| `Notifier.platformFactory` | 不设，回落 `SystemNotifier` | 壳在 `onCreate` 里设成安卓实现 |

**AI 工具按平台裁剪**：手机上 `take_screenshot / click / press_key / type_text` 不进工具清单，
`describe_screen` 保留 —— 工具在清单里但能力不存在，模型会挑中它然后空跑一轮。

## 4. 构建 / 预览 / 验收

```powershell
.\build-mobile.cmd                 # 出 debug APK
.\build-mobile.cmd install         # 连上手机后 adb 安装
.\preview-mobile.cmd               # 电脑上开 390×844 窗口，能真的点
.\preview-mobile.cmd --seed        # 先灌演示数据（空界面看不出排版问题）
.\preview-mobile.cmd --shots       # 不出窗，渲染 8 页 × 手机/平板 PNG 到 build/preview
```

预览器环境变量：`TRELLIS_SEED=1`、`TRELLIS_SHOT_DIR=…`、`TRELLIS_PREVIEW_HOME=…`（默认
`build/preview-home`，**不碰 `~/.trellis`**）、`TRELLIS_APPEARANCE=dark`。

**验收顺序（缺一不算完成）**

1. `./gradlew :app:mobile:assembleDebug` 通过；
2. `./gradlew :app:ui:jvmTest` 通过（600+ 用例，含 `SharedSourceCompatibilityTest`）；
3. `preview-mobile.cmd --shots` 出图，逐张看：折行、裁切、点击区 <48dp、空态没文案；
4. 涉及真实数据的改动，`--seed` 后开窗手点一遍；
5. 交付写明：改了什么、验证结果、还剩什么。

## 5. 铁律（会被测试或架构断言打回）

1. **共享源集不许出现桌面专属 API**。`SharedSourceCompatibilityTest` 扫
   `app/ui/src/jvmAndAndroidMain` 与各模块的 `jvmAndAndroidMain`：禁 `java.net.http`、`java.awt`、
   `javax.swing`、`javafx`、`javax.imageio`、`javax.script`、`java.beans`、`java.rmi`、
   `javax.naming`、`java.lang.management`、`com.sun.`，以及 `Files.readString(` / `Files.writeString(`。
   需要平台实现就放进 `jvmMain` / `androidMain` 的 actual。
2. **内核不认识领域**：`verifyKernelPurity` 扫 `kernel` 源码，出现业务词就红。
3. **时间只有一个来源**：业务时间走注入的时钟（`ctx.clock` / `TrellisClock` / `LearningPlanStore.open(clock, zone)`）；
   手机界面里连「刚刚/几分钟前」也传 `runtime.clock.nowMs()`，否则模拟时间下文案自相矛盾。
4. **UI 只交数据**：第三方插件不能注入 Compose，只能吐 `UiNode`。
5. **失败必须隔离**：插件调用用 `guard { }`，禁止 `runCatching` 包住。
6. **中文源码/文档只用 read / edit / write 改**，别用 PowerShell 回写（UTF-8 读成 ANSI，中文全变问号）。
7. **应用运行时别编译**：`:app:desktop:run` 的 classpath 是 `build/classes/` 目录。
8. **动手前先划文件范围**：第一句就写清「这轮只动这几个文件」。
9. **提交只 add 具体文件**：不要 `git add -A`。

## 6. 手机端特有的坑（这次踩到的）

1. **`Modifier.fillMaxSize().widthIn(max=…)` 不会限宽**：`fillMaxSize` 先把约束顶满，
   `widthIn` 失效。顺序必须是 `widthIn(max).fillMaxWidth().fillMaxHeight()`。
2. **`ImageComposeScene` 只跑一帧**：`LaunchedEffect` 里的「首屏相机适配」可能根本没执行，
   画布停在 zoom=1、pan=0（内容贴左上角）。要在 `onSizeChanged` 里算。
3. **画布字号必须 px→sp**：布局用 px 估算节点宽度，绘制若直接写 `12.sp`，density=2/3 的设备上
   变成 24/36px，同一个框只放得下两个字 —— 所有节点名都成了「拥…」。
4. **`Modifier.onPointerEvent` 只有桌面有**：滚轮缩放要自己开 `awaitPointerEventScope` 循环，
   否则安卓编译直接过不去。
5. **`java.net.http` / `javax.imageio` 在安卓不存在**：`BilibiliClient`、`ActivityWatchClient`、
   LLM 客户端、截图、图片解码全部要走平台出口；`HttpLlmClient` 的安卓版按新协议重写了一份
   （桌面端零改动，代价是 300 行重复）。
6. **`Files.readString/writeString` 是 API 34**：minSdk 26 上要用 `readAllBytes` / `write`。
7. **`internal` 跨模块不可见**：手机界面放在 `:app:ui` 的 `jvmAndAndroidMain` 里，和
   `KnowledgeGraphStore` / `StewardSettings` / `planRhythmReport` 同模块，才能直接用它们。
   这是对第一版 `:app:mobile-ui` 独立模块的一处有意调整（第一版只用到 `AppRuntime` 的公开方法，
   新版的大量新特性是 internal，硬拆要么加一层很宽的 facade，要么把内部状态全公开）。
8. **SQLite 驱动**：桌面 `sqlite-jdbc`、安卓 `sqldroid`（`jdbc:sqldroid:`），运行时按
   `java.vm.vendor` 判断；驱动必须 `Class.forName` 显式注册（安卓没有 `META-INF/services` 自动发现）。
9. **通知**：安卓要 channel + `POST_NOTIFICATIONS`（API 33+）；没授权时系统静默丢弃，
   不能让管家仪式失败。

## 7. 已知缺口

1. **计划页没有真实计划的演示数据**：预览器只灌任务与笔记，计划页显示空态。
   要覆盖它得跑一遍学习计划后端的 createProcess → acceptDraft 命令。
2. **知识网络的画布在手机上只是总览**：默认「结构」树才是主要形态；大图（数百概念）在
   canvas 上仍需缩放操作，且窄屏下标签会按 `zoom >= 0.75` 才显示。
3. **Markdown 编辑器**：手机用「阅读 + 编辑」两态（`PhoneField`），不是桌面的整篇投影编辑器；
   `{{…}}` 折叠、gutter 标记、斜杠菜单、`[[` 补全在手机上都没有。
4. **活动采集**：安卓上仍要靠局域网里的 ActivityWatch（设置 → 活动数据源），
   没有本机采集（那要前台服务与使用情况权限，是另一个量级的工程）。
5. **没有手机端自动化测试**：现在靠 `-jvmTest`（600+ 用例，覆盖共享运行时）+ 预览截图 +
   `SharedSourceCompatibilityTest`。`PhoneApp` 的导航、页签状态靠手点。
6. **只有 debug 包**：没有 signingConfig、`isMinifyEnabled = false`（内核与插件靠反射/序列化按名字找东西）。
7. **iOS 没有 target**：`HostPlatform` 已经是 expect/actual，扩 iOS 时按同一处补。

## 8. 与第一版（`mobile-debug.apk`）的差异

保留：五页签、单列、48dp、纸底墨色、卡片/按钮/分段控件那套视觉与 `PhoneKit` 的组件名。

新增（对齐桌面最新版）：

- 知识：从「整页嵌桌面知识库」改为**手机原生**的笔记列表/阅读/编辑 + 知识网络（结构树 + 总览画布 + AI 建议 + 诊断）；
- 计划：计划节奏报告、AI 自动完成账本（撤销/屏蔽）、待安排集合、按天排期、每日投入设置；
- 对话：管家会话与建议卡、一轮的撤销、未读小圆点、执行清单；
- 今日：模拟时间徽章；
- 我的：管家设置、笔记联动通知、活动数据源、时间调试（模拟今天 / 触发晨晚总结）；
- 计时器：模式（倒计时/正计时）、暂停/继续、结束并记入；
- 插件：能力授权（缺权限时一键补）；
- 通知：安卓 `NotificationManager`（第一版没有）。
