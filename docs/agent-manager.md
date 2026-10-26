# 插件管理员（Agent 常驻化）

> 2026-10-22。Agent 从「一次生成一个插件的弹窗」变成「内核之外的管理员」：一张常驻对话页，
> 能看、能动手、能改布局和样式，也能老实说哪件事它做不了。

## 1. 为什么不是第三个「生成插件」按钮

以前的链路是「用户说一句 → 模型写一个插件 → 装上 → 弹窗关掉」。它有两个结构性毛病：

1. **没有上下文**：用户问「这张卡为什么不出来」，它只能重新生成一个插件来回答；用户说「把三个插件排一排」，它只能解释自己做不到。
2. **没有过程**：`PluginAgent` 一次请求一次安装，失败三次就 `GaveUp`；用户看不到它卡在哪。

管理员的定位是：**内核之外的一层归它管** —— 解释现状、判断该不该动手、创建/修改/停用插件、调布局、调主题。

## 2. 分层提示词（保住前缀缓存）

消息顺序固定：

1. `system`：静态前缀（内核契约、节点词汇表、工具目录、管理守则）—— 一次注入，永不变；
2. `system`：`<current_state>`（已装插件、停用列表、待审批、当前页）—— 每轮变，但排在静态前缀之后；
3. `history`：最近 20 条；
4. `system`：`<task:...>`（按意图插入的任务模块）；
5. `user`：这一句。

理由：上下文缓存按**前缀**命中。把易变的当前状态夹在静态前缀前面，每轮都会把缓存打掉；任务模块放在历史之后，避免模型把「指令」误读成「上一轮发生的事」。

## 3. 意图路由

`KeywordIntentClassifier` 用确定性关键词判四类：`Chat` / `CreatePlugin` / `ManagePlugin` / `ArrangeUi`。

- 顺序是 arrange > manage > create —— 「把三个插件排一下」含「插件」，「删掉心情插件」也含「插件」，必须先判布局/管理。
- 不额外调模型判意图：省延迟和 token，可测试，错判还有静态守则兜底。
- 新词补在 `ai/src/commonMain/kotlin/dev/trellis/ai/manager/Intent.kt` 的 `arrangeWords` / `manageWords` / `createWords`。

## 4. 流式对话与轮次

- Chat 意图走 `LlmClient.stream`（SSE，逐片回调）；其它意图走 `ToolAgent` 的工具循环，过程用 `onLog` 实时显示。
- 流里一旦出现 `"tool":`，就认为模型想动手，立刻切到工具模式真执行（`ManagerAgent` 的 `looksLikeToolCall`）；执行结果替换掉那半截裸 JSON。
- 一轮跑在 `AppRuntime.chatScope`（不是 Composable 的 scope）—— 否则 `open_tab` 切走页面会把这轮掐死。
- 结束信号：`▍` 光标、「还在做，这一轮没结束」、可点「停止」、以及每轮末尾的 `Status` 界标（完成 / 已停止 / 出错）。

## 5. 工具集

| 工具 | 作用 |
|---|---|
| `list_plugins` / `list_events` / `list_actions` / `read_plugin` | 看现状 |
| `try_plugin` / `deploy` | 试跑与安装 |
| `disable_plugin` / `remove_plugin` | 停用与卸载 |
| `describe_screen` | 当前页每个挂载点有什么卡片、卡片文字、空挂载点 |
| `take_screenshot` | 截图存 `~/.trellis/diag/screenshots/`，并作为图片交给模型 |
| `open_tab` / `click` / `type_text` / `press_key` | 眼睛和手：切页、点、打字、按键 |
| `list_cards` / `arrange_cards` | 布局：按插件分行/列（`;` 分行、`\|` 分同行） |
| `list_ui_options` / `set_ui` / `set_plugin_card` | 整页外观、单个插件卡片外观 |
| `list_themes` / `set_theme` | 主题包 |

截屏/缩放/编码/Robot 全部走 `Dispatchers.IO`，不冻界面；`open_tab` 留在主线程改状态。

## 6. 安全与验收的边界

宿主只保留四类硬检查：

1. **安全/预算**：能力门、节点数量与深度、文本长度、图片路径与魔数；
2. **引用完整性**：引用的插件/主题/挂载点必须存在；
3. **落盘一致性**：安装清单、主题 token、全量拒绝而不是排一半；
4. **事实核验**：声称「装好了/排好了」要去查内核状态。

质量类的事情（好不好看、文案、布局合不合理）交给 agent 用 `describe_screen` / `take_screenshot` 自检，
不再写成硬编码清单；收尾检查也按意图给：只有造插件才要求「试过/装上/cannotDo」，排版和改样式不挂那道闸。

## 7. 布局与样式也是数据

- **布局**：`arrange_cards` 把「同一挂载点里哪几个插件同一行」写进 `~/.trellis/layout.json`；没被点名的卡片不隐藏、各自一行排最后。
- **12 栏与跨栏**：每格可以写 `插件id:栏数`（1..12），例如 `ai.pushups:8|ai.mood-checkin:4` 是 8 栏 + 4 栏；不写栏数按等宽。宽度按比例分配，不要求加起来正好 12。
  - 为什么按比例而不是像素：主题、密度、窗口宽度都会变，只有比例是跨端稳定的。
  - 为什么不用 CSS/栅格 DSL：宿主只认这一种写法，模型和人都好写、好校验、好回滚。
- **L1 配色**：`palette=custom` + 色相/彩度，OKLCH 生成整套色，对比度在生成期保证（WCAG）。
- **L2 主题包**：插件可贡献 `kernel.theme`（语义 token，`dark` 可省，宿主派生并复验）；还能带 `shape`（圆角）与 `type`（label/title/display 字族、scale）。
- **外壳**：主题的 shape/type token 由宿主翻译成 `Shapes`/`Typography`；导航、卡片、按钮都跟着走。

## 8. 字体、图标、组件

- 随包字体：Barlow Condensed（大数字，`font: "condensed"`）+ IBM Plex Mono（等宽小标），OFL；
  `AppFonts.kt` 从 classpath 加载，经 `LocalAppFonts` 下发给插件卡片。
- 像素图标：`PixelIcons.kt` 五个 8×8 图标（导航/品牌），Canvas 画，颜色跟随内容色；SVG 导出在 `docs/assets/icons/`。
- 组件：`Chrome.kt` 的 `ChromeButton` / `ChromeTextField` 起步；**弃置的是 M3 默认外观，不是语义层**（ColorScheme/Typography/Shapes/Density 仍是唯一主题接口）。

## 9. 测试与验证

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
cd C:\Users\breakM\Desktop\work\CreateandTrack
gradlew.bat --console=plain :ai:jvmTest :app:desktop:test :plugin-script:test
gradlew.bat :app:desktop:run
```

应用运行中**不要**编译：`gradlew :app:desktop:run` 的 classpath 是 `build/classes/`，
边跑边编译会让关闭回调抛 `NoClassDefFoundError`，窗口退不掉（Ctrl+Q 是兜底退路）。

## 10. 视觉细节：点阵与表格

bento 样片里两个「看起来需要绘图」的部件，最后都做成了语义节点：

- `dotmatrix`：5×7 点阵大数字，只认 `0-9`、`:`、`-`，最多 6 个字符。
  宿主自带字形表（`DynamicSlotView.kt` 的 `dotGlyph`），插件只交 `value` 与可选 `label`。
  不是「插件画 SVG」：字形是宿主资产，跨端一致、可跟随主题色。
- `table`：带斑马线的明细表。表头等宽大写、数字列自动右对齐（`^[0-9][0-9.,:/%+\-]*$`），
  奇偶行交替底色、行间 1px 淡分隔线。列宽等分，超过 6 列会被 `NodeBuilder` 截断。

两者都只交数据；颜色、字号、行高、斑马线全部由宿主决定，换主题/暗色自动跟随。

## 11. 岗位色：数据用靛、状态用砖红、青柠只做底

样片好看的另一半原因是「一种颜色只干一件事」。主题包可以再带一组 `extra`（岗位色），宿主校验后注入 `LocalExtraColors`：

- `data` / `dataMuted` / `dataTrack`：所有「量」的画法（bars 填充、sparkline 线、area 填充、columns、ring 弧、heat 格）都用这一族，**不再直接用 primary**；
- `danger` / `dangerContainer`：逾期、风险、删除这类「需要被追问」的状态；
- `warning` / `warningContainer`：等确认、预算吃紧；
- `success` / `successContainer`：已完成；
- `accentText`：画在青柠底上的文字（青柠上的白字只有 1.26:1，必须配深墨绿）。

规则：绘图只许取 `data` 族；青柠只做「底/选中/完成」，不做线；`accentText` 是强调色上文字的唯一合法颜色。宿主会把岗位色与各自的对照面色一起验（`extraProblems`），不合格整包拒绝——不会出现「数据色在白底上看不见」这种半残主题。

`extra` 里没写的岗位回落到配色方案，不会变透明；旧主题无需改也能继续用。

## 12. 步数：取消额度，只留防跑飞

以前 `ToolAgent` 有 8 步、管理员 10 步的固定额度。问题不是「偶尔不够」，而是**它把正常任务当成了异常**：查五个插件、逐个试跑、再改一版，十几步是常态，被腰斩时用户只看到「它在 10 步里没做完」，既不知道差在哪，也没法续。

现在不设额度（`maxSteps = null` 是默认），防跑飞靠三件事：

- **重复检测**：同一个工具 + 同一组参数连续 3 次 → 判定卡住，停下并把过程交出去（真的重复点两次不会误伤，连续三次同一件事才叫卡）；
- **保险丝**：200 步，只防跑飞，正常任务碰不到；
- 模型自己说 `done` / `cannotDo` 就结束。

`maxSteps` 参数还留着，只给「这一轮最多看三步」这种测试/受限场景用。

## 13. 会话：记录落盘，能翻旧账

- 每轮结束（完成、停止、出错）都把当前这段写到 `<home>/chat/<id>.json`；标题取第一句用户发言（截 24 字），列表按最近说话排序。
- 打开历史会话时，把其中**最近 20 条正式发言**接回模型上下文；过程行（Step）不喂给模型 —— 它是给人查的，喂回去只会占 token 并且让模型以为「上一步是用户说的」。
- 「新对话」只清当前视图，不删旧记录；删除是列表里单独的 ×。
- 坏文件、带 BOM 的文件都按「读不出来就跳过」处理：一段记录坏了不能让整个列表消失。

界面：左边会话列表（标题 + 时间 + 删除），右边气泡流 —— 用户靠右（primaryContainer）、管理员靠左（surfaceVariant）、过程行等宽小字、每轮末尾一条居中的「—— 本轮完成 / 已停止 / 出错」界标。

## 14. 会话里不露协议壳

工具模式下模型的原始输出是协议 JSON（`{"tool":…}` / `{"done":…}` / ```json 围栏）。这些东西**不是「它说了什么」，是「它在想什么」**：

- 流式回显时一看到协议标记就截断（`AppRuntime.visibleReply`），气泡只显示真正的答复；
- 这一轮结束时 `ManagerAgent` 负责剥壳（`unwrapReply`）：取出 `message` / `cannotDo`，JSON 字符串字面量也解开；
- 剥壳失败就原样显示 —— 宁可露出原文，也不要吞掉答复。

## 15. 宿主自己的界面也是插件卡片

任务页的「今日盘点」和「待办清单」原本直接写在 `AppScreen.kt` 里，结果是 agent 只能排别的卡片、改别的卡片样式，用户说「把今日盘点挪走」只能得到「做不到」。

现在它们由内置插件 `dev.planui` 贡献：

- `slot.home.header` 的 `plan-header`（今日盘点）；
- `slot.home.section` 的 `plan-board`（待办清单）。

于是 `list_cards` 能看到它们、`arrange_cards` 能把它们和插件卡片排进同一行（12 栏跨栏）、`set_plugin_card` 能单独改它们的卡片外观。宿主不再直接调用这两个 composable，改由 `SlotArea` 渲染；它们自己也因此必须走插件的挂载点契约（`SlotCard` 提供外壳，内容里 `framed = false`，不套两层壳）。

同理：**列表里所有插件的前端，都应该是 agent 能看见、能改的东西**，否则「插件化」只插件化了别人。

