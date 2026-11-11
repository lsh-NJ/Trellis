# 示例：读书记录（`examples/plugins/ai.reading-log`）

一个脚本插件（`loader: "script"`，`capabilities: []`），复现用户点名要的读书范例：

> 「可在读书列表中添加书名，书名后面可调整进度（目前页数 ｜ 最大页数），点击书名可以创建或跳转对应读书笔记。」

卡片挂在首页 `slot.home.section`，`order: 900`（排在已有卡片下方）。按用户 2026-11-10 的要求，
卡片上**不留说明文字、不留输入框**：只剩「标题 + 每本书一行 + 两个浮窗触发器」。

```
读书                                         ← 标题（唯一一行文字）
假如给我三天光明        120 ｜ 300 页   打开笔记   ← 书名 + 唯一一处进度；整行可点
三体                        3 页   打开笔记        ← 没设总页数时只显示当前页
[加一本书] [记一页]                              ← 两个触发器，点开才是浮窗输入
```

## 每一行是什么、点哪里发生什么

| 位置 | 节点 | 行为 |
|---|---|---|
| 标题 | `text(style='title')` | 只有「读书」两个字。用户明确说不要用法说明行，所以这里不再有 caption |
| 书名行 | `listrow(title=书名, subtitle=进度, trailing='打开笔记')` | **整行可点**：`action: 'openKnowledgeNote'`，`args.title = '读书笔记：《书名》'`。同名笔记已存在就打开并跳到知识库页，不存在就新建再打开 |
| 进度 | 就是书名行的 subtitle | 设了总页数显示「12 ｜ 300 页」，没设只显示「12 页」。同一进度不在别处重复 |
| `加一本书` | `input(popup 默认 true, hint='加一本书：书名 可带总页数（如 三体 300）', submitLabel='加一本书', on='addBook')` | 卡片上只画触发器文字，点开是宿主浮窗：输入框 + 取消/提交；提交走同一个 `addBook` |
| `记一页` | `input(popup 默认 true, hint='记页数：书名 页数（如 三体 120）', submitLabel='记一页', on='setPage')` | 同上；提交走 `setPage` |

两个触发器放在同一行（`row(gap=8)`），避免卡片被两个入口撑高。

## 浮窗里写什么

「加一本书」（`addBook`）：

| 输入 | 效果 |
|---|---|
| `三体` | 只加一本书 |
| `三体 300` | 加书并设总页数 300；**书名已存在时只改总页数** |
| `三体 120/300` | 加书并同时给当前页 120、总页数 300 |

「记一页」（`setPage`，只改已经在列表里的书）：

| 输入 | 效果 |
|---|---|
| `三体 120` | 记到第 120 页（不超过该书总页数；没设总页数就不封顶） |
| `三体 120/300` | 记到第 120 页，同时把总页数设为 300 |
| 书名不在列表里 | 回一句「列表里没有《书名》」，不会静默加书 |

最多同时记 6 本：到上限后加书会回一句「最多同时记 6 本」，不会静默丢输入。

## 数据键（沿用 1.0，v2 起有 `tot:`）

插件数据在 `store.json` 的 `plugin/ai.reading-log` 命名空间：

| 键 | 值 |
|---|---|
| `books` | 书名列表，`\|\|` 分隔 |
| `p:<书名>` | 当前页 |
| `t:<书名>` | 上次记录页码的毫秒时间戳 |
| `tot:<书名>` | 总页数；旧数据没有这个键 = 未设置，只显示当前页 |

所以从 v1 升级的用户已有书名和页码不用迁移、不会丢。

## 为什么是这套结构

- **不在小输入框里写整本书的感悟**：笔记正文在知识库里写，卡片只负责「书 → 进度 → 笔记入口」。
- **输入改成浮窗**（用户原话：输入/修改/添加这类操作都做成浮窗）：卡片体积固定，不会随输入框变高；
  宿主 `UiNode.Input` 的 `popup` 默认就是 `true`，脚本只写 `{type:'input', hint, submitLabel, on}`。
- 点书名就打开/新建笔记，靠的是宿主动作 [`openKnowledgeNote`](../plugin-host-actions.md)，
  插件不需要申请写文件能力，也不需要知道笔记存在哪。
- v3 删掉了每本书的 −/+ Stepper：卡片上只留数据（书名行）与动作（两个浮窗入口 + 整行点开笔记）。

## 验收

端到端测试：
[`ReadingLogExampleTest`](../../app/ui/src/jvmTest/kotlin/dev/trellis/app/desktop/ReadingLogExampleTest.kt)；
版式截图在 [`ReadingLogExampleRenderTest`](../../app/ui/src/jvmTest/kotlin/dev/trellis/app/desktop/ReadingLogExampleRenderTest.kt)（离屏渲染，产出 `app/ui/build/ui-shot-reading-log.png` / `ui-shot-reading-log-dark.png`）。
它把示例目录拷进临时 home，预写 v1 格式的 store 数据，然后断言：

1. 卡片注册在首页，`id=reading`、`order=900`；
2. 卡片上的文字只有标题「读书」（说明行已删）；书名和进度各只出现一次；ListRow 副标题是 `3 页`（v1 数据没有总页数）；
   整行 tap 是 `UiTap.Host(ActionCall("openKnowledgeNote", args.title="读书笔记：《书名》"))`；
   没有 Stepper；两个 Input 的 `submitLabel` 是 `加一本书` / `记一页`、`popup=true`、`on` 分别是 `addBook` / `setPage`；
3. `kernel.performAction` 后笔记落盘、`pendingKnowledgeReveal` 指向它、当前页签是知识库；再点不重复建；
4. 浮窗提交（`DynamicSlot.perform`）真写回 `store.json`：`setPage` 改 `p:`/`t:`、受总页数封顶、找不到书给人话；
   `addBook` 加书/改总页数且不重复加。

跑法：

```bash
export JAVA_HOME="$HOME/.jdks/jdk-17.0.21+9/Contents/Home"; export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:ui:jvmTest --tests "*ReadingLogExample*" --console=plain
```

## 想自己装一份 / 换成自己的书

把 `examples/plugins/ai.reading-log/` 整个目录拷到 `<home>/plugins/ai.reading-log/`
（默认 `<home>` 是 `~/.trellis`，测试/演示时用 `TRELLIS_HOME` 指到临时目录），重启应用即可。
书存在插件自己的 KV 里，不受真实知识库笔记影响；点书名时按「读书笔记：《书名》」精确匹配已有笔记。
