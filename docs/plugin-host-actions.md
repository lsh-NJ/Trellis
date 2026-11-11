# 插件可用的宿主动作（Host Actions）

> 脚本（`loader: "script"`）和声明式（`loader: "dsl"`）插件本身没有写文件、开页面的权限。
> 它们能做的「真操作」，是**点名宿主已经实现好的原子动作**（`KernelAction`），
> 由宿主执行、由宿主负责跳转与落盘。插件负责画卡片，宿主负责动手。
>
> 本文是这份动作目录的说明。加动作时先读 [`../kernel-design.md`](kernel-design.md) 的扩展点一节，
> 再看实现 `features/plan-core/.../PlanActions.kt`、`app/ui/.../KnowledgeNoteActions.kt`、
> `app/ui/.../HostActions.kt`。

> **先组合，再要能力**：造插件时第 2 步永远先 `list_actions`。下面这些动作是**通用原子动作**，
> 跳页 / 写笔记 / 通知 / 计时 / 任务这几类交互都能直接拼出来；只有「一整类需求反复出现、
> 现有动作确实拼不出来」才值得让宿主新增动作（见文末「缺口协议」）。

## 怎么在卡片上调用

脚本插件在按钮/行/片节点上写 `action` + `args`（`NodeBuilder` 会把它翻成 `UiTap.Host(ActionCall)`）：

```js
{
  type: 'button',
  label: '打开笔记',
  action: 'openKnowledgeNote',   // 宿主动作 id
  args: { title: name }          // 只收字符串/数字，数字会转成文本
}
```

- `on:` 是**脚本自己的**处理函数（返回 `{say, save, remove}` 交给宿主落盘）；
  `action:` 才是**宿主动作**。两者只能选一个，写了 `on` 就不再走宿主动作。
- 声明式插件在卡片的 `actions` 数组里写同样的 `{"label": …, "action": …, "args": {…}}`。
- `args` 的值只能是字符串或数字，不能传对象/数组；需要结构化数据就在插件里先拍平。
- **`args` 是每次渲染时现算的**：`render` 里可以按当前 store/选中项拼出标题、正文再塞进按钮
  （`args: { title: 当前书名, body: 已存内容 }`），所以「每本书各开一篇笔记」「把卡片上存下的
  内容写进笔记」都做得到——只有「一次点击里连做两个动作」需要拆成两个按钮。

## 浮窗输入：编辑类操作不要堆在卡片正文

卡片是「看数据」的地方。加一条、改一个数、记一笔这类操作，默认写成 **input**，宿主会把它渲染成浮窗：

- **默认就是浮窗**（`popup: true`）：卡片上只画一行可点的字（显示 `submitLabel`），点开才弹出输入框，
  `hint` 是浮窗里的占位提示；提交后仍走同一个 `onSubmit` 处理函数，`payload["value"]` 跟内联输入完全一致。
- 只有确实需要**常驻**输入时（比如卡片本身就是个快速记录条）才写 `popup: false`，回到内联输入框。

```js
// 默认：卡片上是一行「＋ 加一本」，点开是浮窗输入
{ type: 'input', hint: '书名', submitLabel: '＋ 加一本', on: 'addBook' }

// 少数需要常驻输入的场景
{ type: 'input', hint: '记一句', submitLabel: '记下', on: 'quickNote', popup: false }
```

什么时候用浮窗，而不是把控件堆在卡片里：

- 加一条记录、填一句备注、改一个数字 —— 用 input 浮窗；
- 卡片正文只放必要内容：标题 + 数据 + 动作。不要写「点这里加一本」这类操作说明句，
  同一个信息只出现一次（进度就写在书那一行，别再单独一行重复）；
- `Stepper`（`− 12 页 +`）仍然存在，但它是给「一眼就能数清、连续点几下」的确定性数值用的；
  要用户输入内容或不确定数值时，用浮窗 input，不要把一排 `−/+` 和输入框一起铺在正文里。

## 能力门（capability）

动作上的 `capability` 决定「插件能不能点」：

| `capability` | 行为 |
|---|---|
| `null` | 用户一点就执行。只给「用户亲手点自己卡片、低风险、宿主已实现」的原子动作，理由写在动作实现的注释里 |
| 有值（如 `write_data`、`delete_data`） | 插件的 `manifest.json` 必须用 `capabilities` 声明同名能力，否则被拒；声明后点击仍会走审批门 |

被拒时返回一句人话（显示在按钮旁边），不是抛异常。`openKnowledgeNote` 是 `capability = null`，
所以 `capabilities: []` 的插件也能用；这不是漏写，是刻意的取舍（见动作源码注释）。

## 动作目录

| id | 参数 | 需要能力 | 做什么 |
|---|---|---|---|
| `openKnowledgeNote` | `title`（必填）、`body`（可选） | 无 | 打开或新建知识库笔记并跳过去 |
| `openPage` | `page`（必填）、`target`（可选） | 无 | 跳到任务 / 计划 / 知识库笔记 / 活动日期等页面 |
| `writeKnowledgeNote` | `title`、`body`（必填）、`mode`（可选，默认 `append`） | 无 | 新建或写知识库笔记（追加 / 覆盖 / 只新建） |
| `notify` | `title`（必填）、`body`（可选） | 无 | 发一条系统通知 |
| `startFocusTimer` | `minutes`（必填，整数）、`label`（可选） | 无 | 打开计时器并开始倒计时 |
| `addTask` | `title`、`dueDays`（可选，整数天数） | `write_data` | 新建任务 |
| `logTime` | `taskId`、`minutes` | `write_data` | 给任务记投入时间 |
| `completeTask` | `taskId` | `write_data` | 把任务标记为完成 |
| `renameTask` | `taskId`、`title` | `write_data` | 改任务标题 |
| `rescheduleTask` | `taskId`、`days` | `write_data` | 改截止时间（`days` 为整数天） |
| `deleteTask` | `taskId` | `delete_data` | 删除任务 |

> `taskId` 这一类参数除了任务 id，也接受选择器 `oldest`（最老的一条未完成任务）和
> `oldestOverdue`（最早逾期的一条）——给「没有查询能力」的插件留的窄门。

## openKnowledgeNote：打开或新建笔记并跳转

契约固定，脚本/DSL 都可以直接用：

- `title`：必填，按 `trim()` 后的**完全同名**匹配现有笔记；`body`：可选，只在**新建**时写进正文，
  已存在的笔记不会被覆盖。
- 同名笔记存在 → 打开它；不存在 → 新建再打开。两种情况都切到知识库页，
  并把「要看的笔记 id」交给知识库页消费（`AppRuntime.openKnowledgeReveal` → `pendingKnowledgeReveal`）。
- 返回值是一句给用户看的话（「已打开笔记「…」」/「新建并打开了笔记「…」」），不返回笔记 id；
  插件不需要、也拿不到知识库内部数据。

**为什么这条能力必须有**：像「读书记录」这种卡片，用户要的是「一键打开这本书的读书笔记」。
没有这个动作时，插件只能显示一行「请自己去知识库新建」——需求等于没做。有了它，
插件不需要写文件权限，也不需要知道笔记存在哪，按钮一行就完成「存在就跳、不存在就建」。

## openPage：跳到某个页面

给插件一个「点一下就去该去的地方」的窄门，它不需要知道页面怎么实现。

- `page`：必填。接受中文或英文：`任务`/`tasks`、`计划`/`plan`、`知识库`/`knowledge`/`kb`、
  `对话`/`chat`、`活动`/`activity`、`设置`/`settings`、`插件`/`plugins`、`诊断`/`diag`；
  未知值会返回一句人话并列出合法取值。
- `target`：可选，只在特定页有意义：
  - 知识库：按 `title.trim()` **精确匹配**现有笔记；存在就打开并跳转，不存在则返回
    「知识库里没有这篇笔记：xxx；要新建就用 openKnowledgeNote」。**openPage 不新建笔记**。
  - 计划：按计划 id 或名称（模糊）选中计划并切到计划页；找不到时返回现有计划名列表。
  - 活动：`YYYY-MM-DD`，跳到活动页的那一天。
  - 其它页：忽略 `target`，只切页。
- 它只跳转，不写数据；插件要「建/写」用 `writeKnowledgeNote` 或 `openKnowledgeNote`。

## writeKnowledgeNote：写一篇知识库笔记

- `title`、`body` 必填；`mode` 默认 `append`：
  - `append`：笔记不存在就新建；已存在就把 `body` 追加到正文末尾（中间空一行）。
  - `replace`：覆盖正文；笔记不存在就新建。
  - `createOnly`：只新建；已存在则不动，返回一句提示。
- 返回人话，带笔记标题与 note id。

## notify：发一条系统通知

- `title` 必填，`body` 可选。走宿主已有的通知通道（osascript / PowerShell Toast / notify-send）。
- 系统可能静默拦截，动作本身**不抛异常**：失败也返回「已发通知（系统可能拦截）」。
- 适合「打卡提醒」「到点看一眼」这类插件；不要用它替代页面内的反馈。

## startFocusTimer：打开计时器并开始倒计时

- `minutes` 必填，整数，钳到 1..240；`label` 可选，默认「专注」。
- 打开计时器小窗并立即开始倒计时，返回「计时器已开始：N 分钟（label）」。

## 为什么这些动作的 capability 都是 null

它们都是**用户亲手点自己卡片上的按钮**触发的、低风险、宿主已实现的原子动作，
和 `addTask` 同一级别：注册表里有 `capability = null`，`capabilities: []` 的插件也能点。
如果给它们加能力门，用户每点一次就弹一次审批，体验不可接受 —— 这不是漏写，是刻意的取舍。
真正会删数据/改他人数据的动作（`deleteTask` 等）仍然保留能力门。

## 示例

[`../examples/plugins/ai.reading-log`](../examples/plugins/ai.reading-log) 是一个完整用法：
加书 → 记页码 → 每本书一个「打开笔记」按钮（`action: 'openKnowledgeNote'`）。
说明与验收点见 [`examples/reading-log.md`](examples/reading-log.md)。

## 缺口协议：什么时候该新增宿主动作

动作面是宿主的第一方实现，**插件和模型都不能自己新增动作**。新增的门槛是「一类需求」，不是「一个插件」：

- **该新增**：同一类交互在不同插件、不同用户请求里**反复出现**，用现有通用动作组合不出来，
  或组合出来明显别扭（比如要跳一个宿主页面、写一个宿主数据集，而现有动作没有对应入口）。
  这时加一个**通用**原子动作，让后续所有插件都能组合使用。
- **不该新增**：只为了某一个插件的一次性需求加动作。先看能不能用 `openPage` /
  `writeKnowledgeNote` / `openKnowledgeNote` / `notify` / `startFocusTimer` / 任务类动作拼出来；
  能拼就拼，拼不出来再走上面的门槛。
- **新增流程**：实现 `KernelAction`（`id` / `params` / `capability` / `execute`），在内置插件的
  `init(ctx)` 里 `ctx.extensions.register(KernelPoints.Action, …)`，并在 `KernelPoints.Action`
  的贡献声明里登记 id；`capability` 的判定标准见上文「能力门」。测试参照
  [`HostActionsTest`](../app/ui/src/jvmTest/kotlin/dev/trellis/app/desktop/HostActionsTest.kt)
  与 [`ReadingLogExampleTest`](../app/ui/src/jvmTest/kotlin/dev/trellis/app/desktop/ReadingLogExampleTest.kt)
  用 `kernel.performAction` 端到端跑一遍。
- **模型侧遇到缺口怎么说**（写进提示词的工作流）：先 `list_actions` 对能力，把需求逐条标成
  「有现成动作 / 要新写 / 做不到」。组合不出来的**一整类缺口**，才明确告诉用户
  「这条需求需要宿主新增 X 类能力」——模型不能改宿主代码，只能告知；同时把这条写进
  `limitation`，说明少了什么、边界在哪。**不许静默降级**：降级版必须先说清差在哪、由用户点头。
  不要每做一个插件就要求宿主加一个动作。

## 怎么发现动作

- 运行时：AI 的 `list_actions` 工具、插件自检都读同一张 `KernelPoints.Action` 注册表。
- 不允许插件自己新增宿主动作：这是「插件能做事、做不了坏事」的边界。
