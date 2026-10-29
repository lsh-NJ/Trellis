# 活动分类：从 ActivityWatch 原始事件到分层分类与日汇总

> 实现记录（2026-10-29）。内核在 `features/activity/`；桌面端入口是导航第五项「活动」（也可 `TRELLIS_TAB=activity` 直接进）。
> 这份文档写「为什么这么定、边界在哪」；具体 API 看代码。

## 1. 一句话

ActivityWatch 只当**采集器**：Trellis 通过它的本地 HTTP API 取 `currentwindow` / `afkstatus` / `web.tab.current` 三类原始事件，
自己落 SQLite、自己维护分类树、自己算日汇总。**不读也不写** AW 的 `category_sets`，不调 AW 的 query API——
分类规则是 Trellis 的数据，AW 的 UI 与 Trellis 各管各的。

## 2. 数据流

```
aw-server (http://localhost:5600/api/0)
   │  ActivityWatchClient：拉 buckets / events，解析出 RawEvent
   ▼
ActivitySync.syncOnce()
   │  只同步 settings.watchedTypes = currentwindow / afkstatus / web.tab.current
   │  按天分块拉取；每个 bucket 两侧加 pad（=该 bucket 已知最长事件，最多 3 天），
   │  防止「跨查询边界的 AFK/长事件被截断」；幂等 upsert
   ▼
activity.db（raw_event / aw_bucket）
   │  ActivityStore
   ▼
ActivityAggregator.segments(day)
   │  窗口事件 → 片段；扣掉 AFK 交集、排除 excludedApps、合并碎片、去跨 bucket 重叠
   │  浏览器片段按时间重叠贴上标签页 URL
   ▼
ActivityClassifier（来自 ActivityTaxonomy 的规则树）
   ▼
DaySummary / appRanking
   ▼
（下一步）任务、计划的追溯与时间管理
```

要点：

- **原始层只存事实**，分类不落库——改规则后重算即可，不需要重新同步。
- `raw_event` 主键 `(bucket, aw_id)`，重复同步是幂等的；`ts_ms` 取 MIN、`dur_ms` 取 MAX，因为 aw-server 会把事件裁剪到查询区间
  （实测：一条 589 分钟的 AFK 事件，按 1 小时窗口查回来只有 3600 秒）。
- 浏览器 bucket 与窗口 bucket 是**两条独立事件流**：`web.tab.current` 只有 url/title、没有进程名，不能直接当作活动时长。

## 3. 分类模型：树 + 稳定 id + 颜色

分类不是「名字里的斜杠路径」，而是**显式的父子节点**：

```json
{
  "version": 1,
  "categories": [
    { "id": "work", "name": "工作", "color": "#4ADE80", "order": 0 },
    { "id": "work.coding", "name": "编程", "parentId": "work", "color": "#34D399", "order": 0,
      "rules": [
        { "pattern": "idea64|studio64|code\\.exe", "fields": ["APP"], "priority": 120 },
        { "pattern": "github\\.com|stackoverflow\\.com", "fields": ["DOMAIN"], "priority": 120 }
      ] }
  ]
}
```

- `id` 是稳定标识（重命名/移动分类不丢历史）；`parentId` 指父节点；展示路径由 `pathOf()` 现算。
- 每个节点都有颜色（`#RRGGBB`）。手写缺省/非法颜色时，`normalised()` 用 id 的稳定哈希从内置调色板补一个，
  保证「同一个分类颜色永远一样」；`uncategorized`（未分类）是保留 id，不能被用户规则顶掉。
- `order` 决定同级展示顺序，也是同优先级规则的 tie-break 依据。
- 分类规则挂在**节点**上；没有规则的节点就是纯文件夹（AW 的 `rule.type = none` 同义）。

## 4. 规则语义：与 AW 一致的部分，和故意不一致的部分

AW 参考（aw-webui `src/util/classes.ts` + 官方 categorization 文档）：

- `Category.name: string[]` 是路径，`Rule.type = regex | none`，`select_keys` 默认 `['app','title']`，逐 key 各自匹配；
- 排名 `rank = priority ?? depth * 10`，rank 最高的分类胜出（所以默认「越深越具体」赢）；并列时取声明顺序（lodash `maxBy` 稳定）；
- 空正则等于「没有规则」；坏正则 AW 侧会静默跳过；**AW 目前不匹配 URL**（官方文档：planned）。

Trellis 的规则：

| 维度 | Trellis | 与 AW 的关系 |
|---|---|---|
| 匹配字段 | `APP`（默认）/ `TITLE` / `URL`（整条 URL）/ `DOMAIN`（host，去 `www.`） | 是 AW `select_keys` 的超集；增加 URL/DOMAIN 实现「匹配标签页」 |
| 一条规则多字段 | 或关系（命中任一即算） | 与 AW 逐 key 匹配一致 |
| 大小写 | 默认忽略，规则可关（`ignoreCase=false`） | 同 AW `ignore_case` |
| 正则选项 | 始终 `MULTILINE` | 同 AW 的 `m` flag |
| 优先级 | `rank = priority ?? depth*10`，高者胜；并列按声明顺序 | 同 AW |
| 空正则 | 忽略 | 同 AW |
| 坏正则 | 收进 `classifier.problems`，不静默 | **比 AW 严格**：分类是后台任务，但规则错误要能看见 |
| 默认字段 | **只匹配 APP** | **故意偏离 AW**：AW 默认 app+title，标题里一个词就能把整段时间判给别的分类；标题/URL 必须显式声明 |

为什么要稳定 id 而不是 AW 的名字路径：名字路径既是标识又是展示，改个中文名历史统计就断了；id 不变，路径随便改。

## 5. 标签页 URL：怎么拿到、怎么贴到时间上

- 采集：`aw-watcher-web` 扩展写入 bucket `aw-watcher-web-<browser>_<hostname>`，类型 `web.tab.current`，
  事件 `data` 含 `url` / `title` / `audible` / `incognito` / `tabCount`（命名 profile 还带 `profile`）。
- 落库：`RawEvent.url` → `raw_event.url`（schema v2，`ALTER TABLE` 迁移；window bucket 重拉时用 `COALESCE` 保留已有 url）。
- 贴时间：`ActivityAggregator` 只对**浏览器进程**（`settings.browserApps` 里的正则片段，默认覆盖 chrome/edge/firefox/brave/opera/vivaldi/arc/360/qq 等）
  的窗口片段找时间重叠最大的标签页事件，把 url 带进 `Segment`；非浏览器片段不贴。
- 分类：`URL` 字段匹配整条 url，`DOMAIN` 字段匹配 host（已小写、去 `www.`）。
- 隐私：url/title 都是本地数据。`ActivitySettings.aiExposure`
  （`NONE` / `AGGREGATES_ONLY` / `AGGREGATES_WITH_APPS` / `AGGREGATES_WITH_TITLES`）决定将来给 AI 的粒度；
  **在接 AI 之前必须先过这一层裁剪**，别把标题/URL 直接塞进提示词。

## 6. 时间计算：数字要对得上

这些细节都是 2026-10-27 真实数据踩出来的（当天 787.8 分钟名义窗口）：

- **AFK 必须扣**：`QQ.exe` 有一条 106.9 分钟的事件，其中 106.8 分钟处于 AFK；只按窗口事件累加会凭空多出近两小时。
- **碎片必须并**：84.1% 的事件短于 5 秒，但合计只占 16.8 分钟（2%）——`minSegmentSeconds` 之下的片段并入前一段，避免时间轴全是碎屑。
- **重叠要去**：同一时刻可能有多条窗口事件（多屏/重复上报），按截断去重，不能重复计时。
- **日界可配**：`dayStartHour` 默认 0（AW 默认 4:00）；凌晨 2 点的「今天」算前一天还是当天，由这个开关决定。
- **web 事件不计活动时长**：标签页事件只提供 url 元数据，时长一律以窗口事件为准。

汇总口径：

- `CategorySlice.directMs` 是「直接判给该分类」的时长；`totalMs` 是「自己 + 全部子孙」的累计。
  父子分类同时出现在列表里，看总览用 `totalMs`，看「这个分类自己占多少」用 `directMs`。
- `DaySummary` 同时给 `observedMs`（名义窗口）、`afkMs`、`excludedMs`、`activeMs`、`segmentCount`，先自洽再谈分类占比。
- `appRanking(dayMs, categoryId)` 按「该分类及其子孙」过滤，不再用字符串前缀（旧 API `List<String>` 已删除）。

## 7. 桌面端活动页

导航第五项「活动」（`TAB_ACTIVITY = 6`，`AppScreen.NAV_TABS` 排在「对话」与「设置」之间；
`TRELLIS_TAB=activity` 可直达）。页面在 `app/desktop/src/main/kotlin/dev/trellis/app/desktop/ActivityScreen.kt`，
只摆数据：分类、颜色、时长都来自 `ActivityAggregator`，页面不重新匹配规则、不累计数字。

进入页面时懒开库（`AppRuntime.ensureActivityEngine()`）：读 `activity/settings.json` 与 `activity/taxonomy.json`
（缺失就用默认值落一份），打开 `activity.db`，然后在页面作用域里每 60 秒跑一次 `ActivitySync.syncOnce()`；
离开页面协程取消，同步就停。同步失败（AW 没开、库打不开）只写 `activityMessage`，页面照常显示库里已有的数据。

- **日视图**：日期导航（‹ / 日期 / › / 今天）+ 活跃 / AFK / 观察窗口 / 其他 / 片段数统计；
  24 小时时间轴用 Canvas 按 `Segment.color` 画彩色段（AFK 留空），点击按时间距离容差命中最近片段；
  分类树按 `totalMs` 排、子类缩进，显示「直接 X / 合计 Y」，点分类过滤下方应用榜；
  应用排行（app + 占时最多的分类 + 时长）；
  页面最底部「未分类」折叠块（默认收起）——未分类记录不占主视线，展开后点条目看详情。
- **范围总览**：起止日期（Material3 DatePicker）+「最近 7 天 / 最近 30 天」，分类与应用按时长倒序；
  数据来自 `ActivityAggregator.rangeSummary(fromMs, toMs)`（逐日累加，分类按 id 合并）。
- **片段详情浮层**：点时间轴片段或未分类条目，显示 app、窗口标题、标签页 URL、分类色点、时间段与时长，
  以及 `classifier.explain()` 的命中规则；底部「归到分类…」可以就地建一条规则（见下）。
- **分类规则页**（页头第三个按钮，`ActivityMode.Rules`）：在程序内管理分类树，不用再手改 JSON。
  页面在 `app/desktop/src/main/kotlin/dev/trellis/app/desktop/ActivityRules.kt`（`ActivityRulesView`）：
  - 分类树平铺展示（缩进、色点、路径、规则条数），「新建大类」/「加子类」/「删除」（删除时子类挂到上一级，规则一起删；
    `uncategorized` 是保留分类，不能删/不能改父级）；
  - 「编辑分类」对话框：名字、颜色（`#RRGGBB` 或 16 色色板，留空按 id 从调色板派生）、父分类下拉
    （候选里过滤自己、未分类和自己的后代，避免成环）、规则列表（正则 + 字段 + 优先级摘要，可增删改）；
  - 「编辑规则」对话框：正则、`APP/TITLE/URL/DOMAIN` 四个字段开关（或关系）、忽略大小写、优先级
    （留空 = 按分类层级 `depth*10`）；保存前校验空正则和 `Regex` 编译失败；
  - 保存走 `AppRuntime.saveActivityTaxonomy(next)`：`ActivityTaxonomy.validate()` + `ActivityClassifier.problems`
    合并去重，有问题就不落盘、原样显示；通过则 `ActivityTaxonomyStore.save()` 写 `taxonomy.json`，
    并**就地重建 `ActivityAggregator`**（同一个 store，不关库），页面 `reloadTick++` 后日视图/范围数字按新规则重算。
- **片段一键归类**（`QuickClassifyDialog`）：详情浮层点「归到分类…」弹窗，正则预填按片段自动生成——
  有域名就 `Regex.escape(domain)` + `DOMAIN`，否则 app + `APP`，再否则 title + `TITLE`；
  选一个分类后把这条规则追加到该分类（`withCategory`），保存即重新分类；以后同类片段不再进未分类。
- **隐私分层**：列表只显示 app 名 + 分类；窗口标题、标签页 URL 只在点开详情后才出现。
  将来接 AI 时仍要过 `aiExposure` 这一层（见 §5）。

## 8. 文件与配置

| 位置 | 内容 |
|---|---|
| `~/.trellis/activity/activity.db` | 原始事件（SQLite，WAL；schema v1 无 url，v2 加 `raw_event.url`，迁移自动跑） |
| `~/.trellis/activity/taxonomy.json` | 分类树（用户手写数据，可直接看/备份/改；读坏会改名成 `taxonomy.json.bad-<毫秒>` 留档再回默认） |

代码地图（`features/activity/src/main/kotlin/dev/trellis/activity/`）：

| 文件 | 职责 |
|---|---|
| `ActivityTaxonomy.kt` | 分类树模型、规则、默认分类、`ActivityClassifier`（`classify` / `explain`） |
| `ActivityTaxonomyStore.kt` | `taxonomy.json` 读写（临时文件 + 原子移动）、坏文件隔离 |
| `ActivitySettings.kt` | 同步/日界/碎片/排除项/浏览器进程/AI 暴露粒度 |
| `ActivityWatchClient.kt` | AW HTTP API 客户端（buckets / events） |
| `ActivitySync.kt` | 增量同步、按天分块、pad、幂等 upsert、保留期清理 |
| `ActivityStore.kt` | SQLite 读写与 schema 迁移 |
| `ActivityAggregator.kt` | 片段化、AFK/排除/去重、贴 url、分类、日汇总与排行 |

桌面端 UI（`app/desktop/src/main/kotlin/dev/trellis/app/desktop/`）：

| 文件 | 职责 |
|---|---|
| `ActivityScreen.kt` | 「活动」页外壳：日视图 / 范围总览 / 分类规则三个模式、时间轴、片段详情、一键归类入口 |
| `ActivityRules.kt` | 分类规则页：分类树管理、分类/规则编辑对话框、`QuickClassifyDialog` |
| `AppRuntime.kt`（活动段） | 懒开引擎、60 秒同步循环、日/范围查询、`saveActivityTaxonomy` 校验+落盘+重建聚合器 |

## 9. 测试

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk17"; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat :features:activity:test --console=plain
```

当前 62 个用例：聚合（AFK 交集、碎片、排除、日界、层级汇总、标签页 URL 取重叠最大者、双 bucket 去重、范围汇总）、
分类（默认分类归类、优先级压深度、并列按声明顺序、字段语义、坏正则/空正则、validate、path/descendant、domainOf、
序列化往返、`withCategory`/`withoutCategory`/`nextCategoryId`）、
taxonomy.json 读写（缺失/坏文件留档）、store（url、COALESCE、v1 库自动迁移）、client（web/window 解析）、sync（web bucket 同步）。
`LiveAwSmokeTest` 需要真实 aw-server，默认不跑（`TRELLIS_AW_LIVE` 环境变量控制）。

## 10. 已知缺口 / 下一步

1. **活动页还不能做的**：没有 CSV 导出、没有分类/规则的拖拽排序、没有「规则试跑」（输入 app/title/url 看命中哪条）、
   同步状态只在页头一行字；分类规则页只能逐条编辑，不能批量导入/导出 JSON。
2. **没接任务/计划**：追溯（「这段时间对应哪个计划节点」）需要把 `Segment` 与计划后端的时间轴对上，接口还没设计。
3. **分类编辑的边界**：规则编辑只覆盖分类树与规则本身；`activity/settings.json`（轮询间隔、排除进程、`titleRedactions`）
   仍要手改文件，页面上还不能编辑。
4. **`titleRedactions` 还没生效**：设置里有字段，聚合时尚未应用；接 AI 前要和 `aiExposure` 一起补上。
5. **正则方言差异**：Kotlin 用 `java.util.regex`，AW 是 Python `re` / Rust regex crate；默认规则只用了公共子集，用户写高级语法时要注意。
6. **URL 只来自浏览器扩展**：没装 `aw-watcher-web` 就没有标签页数据；没有浏览器历史 API 兜底。
7. **只看窗口事件**：没有鼠标键盘活跃度（AFK 只有 idle/not-afk 两态），细粒度「是否真的在看」还做不到。