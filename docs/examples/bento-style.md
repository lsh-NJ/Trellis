# 风格样片「Bento / BENTO SHIFT」怎么落进 Trellis

样片：`dashboard-set/a-shift-bento.html`（跑调音乐节运营看板）。
它是一张 neo-brutalist 编辑风的数据看板：纸感米白底、墨黑正文、荧光青柠高亮、蓝作次色；
等宽小标、大号数字、硬边小圆角卡片、12 栏 bento 排布。

在 Trellis 里，它被拆成**三层**，每层都有明确的边界：

| 样片里的东西 | 落点 | 谁控制 |
|---|---|---|
| 纸白 / 青柠 / 墨黑 / 蓝的整套配色 | 主题插件 `dev.bento.shift/bento`（`examples/plugins/a-shift-bento`） | 插件交 token，宿主验对比度 |
| 3px 方角（按钮/输入框/卡片一起变） | 主题插件里的 `shape.cardRadius` / `shape.controlRadius` + 界面偏好 `card = bento` | 宿主画 |
| 等宽小标、编辑风字体 | 主题插件里的 `type.labelFamily = mono`、`type.titleFamily`、`type.scale`；卡片内用 `text(font: "mono")` | 宿主换字族与字号 |
| 硬边小圆角、右下角贴纸阴影 | 界面偏好 `card = bento` | 宿主画 |
| 88px 左栏导航 + 荧光选中项 | 界面偏好 `nav = left`、`nav_tone`；左栏由宿主自绘（不再是 M3 的胶囊选中态） | 宿主画 |
| 大号等宽数字、小号 mono 小标 | `text(size: "huge"|"l"|"small", font: "mono", weight: "bold")`、`stat` | 插件交数据，宿主画 |
| 折线 / 横条 / 环 / 表格 | `sparkline` / `bars` / `ring` / `table` 节点 | 插件只交数字，宿主 Canvas 或 Compose 画 |
| 12 栏 bento 拼版 | `grid(columns: 2..4)` + `row`；卡片之间的横排用布局工具 `arrange_cards` | 宿主 |
| 像素画布、任意 SVG、自定义 CSS/颜色 | **不做** | 封闭词表；开放任意绘制会同时失去审查、降级、主题一致 |

## 装上之后怎么用

1. 重启应用（脚本插件在启动时加载）。
2. 对话里说一句就行，agent 有对应工具：

   > 把主题换成 dev.bento.shift/bento，卡片样式改成 bento，导航放左侧；然后按这个风格做一个「这周练习量」的看板卡片。

3. 手工路径：插件页能看到 `BENTO SHIFT`；界面偏好里主题选 `dev.bento.shift/bento`、卡片选 `bento`、导航选 `左侧边栏`。

## 为什么数据图不用插件「绘图」

`bars` / `sparkline` / `ring` / `table` 都是语义节点：插件只交数字和文字，宿主决定怎么画。
这样换主题、换端（桌面 / Android / iOS）、暗色、无障碍都只需要宿主改一次；插件不用带渲染代码，
也不会出现「某张卡片在某个主题下瞎眼」这种事。想要更自由的图形，走的是后续的「受约束画布」
（归一化的 line/rect/circle/arc + `draw` 能力 + 审批），而不是让插件自带 HTML/SVG。
