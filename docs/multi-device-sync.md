# 多端数据互通（同步）方案

> 状态：**方案 Draft v0.2（2026-11-19）** —— P1 文件域垂直切片已落地（`:sync` 模块 + 桌面接线），详见 §12。
> 适用：Trellis 单用户、多设备（桌面 macOS/Windows + Android，iOS 未来）、本地优先、离线可用。
> 定位：先定「数据怎么分域、怎么合、怎么传、先做哪一步」，实现细节以代码为准。

---

## 0. 结论先行

**目标**：同一份学习数据在 2–3 台设备间最终一致。用户在哪台设备上改，另一台在下次打开/回到前台时看到；没有网也能照常用，联网后补同步。

**不做**：实时协同编辑、CRDT/OT、多用户共享、活动原始事件跨端合并。

**三条铁律**（违反任何一条都会得到「修不好的脏数据」）：

1. **SQLite 不进云盘。** `plans/plan.db`、`activity/activity.db` 连同 `-wal`/`-shm` 直接放 iCloud/Dropbox/坚果云 = 迟早坏库（云盘会并发复制、改 mtime、留冲突副本）。SQLite 域只同步**逻辑快照**，或者干脆不同步。
2. **每台设备只写自己的命名空间。** 远端目录里 `heads/<domain>/<deviceId>.json`、`logs/<domain>/<deviceId>/*.jsonl`、`devices/<deviceId>.json` 只由该设备写；其它对象（`blobs/<sha256>`）不可变。这样哪怕传输层是「只会同步文件夹」的云盘，协议文件也永远不会产生「冲突副本」。
3. **冲突不静默合并正文。** 小记录用 LWW（最后写入者赢）；笔记/计划文档冲突时留**两份**让用户选；`plan.db` 冲突时**先自动备份再二选一**。宁可多一个文件，也不默默丢用户写的东西。

**推荐路线**：

- 传输优先 **WebDAV**（坚果云 / Nextcloud / Alist 都行；桌面和 Android 都能用同一套 HTTP，不用挂载云盘目录）。
- 桌面↔桌面的本机同步盘（iCloud/Dropbox/Syncthing）作为第二种传输，也用作测试替身。
- 自建同步服务（CAS/推送/租约更严格）放到 P3，不是第一版必需；官网与 Agent 云数据源的服务端路线见 [cloud-roadmap.md](cloud-roadmap.md)。

**分期**：

| 阶段 | 内容 | 估算 |
|---|---|---|
| P0 | 全量导出/导入 zip（含 plan.db 逻辑快照），解决换机搬家与回滚 | 0.5–1 天 |
| P1 | `:sync` 骨架 + WebDAV/文件夹传输 + 笔记/计划文档/JSON 记录/追加日志/任务 KV + 设置页 + 冲突面板 | 1.5–2 周 |
| P2 | plan.db 逻辑快照 + 单写租约 + `PlanBackend` 连接收敛 + 冲突二选一 + 历史恢复 | 1–1.5 周 |
| P3（可选） | 记录级合并器、聊天可选同步、E2E 加密、自建服务/局域网直连 | 按需 |

---

## 1. 场景与约束

### 1.1 真实使用形态（决定方案取舍）

- **单用户、2–3 台设备**：桌面（macOS/Windows）是主力，Android 是随手记 / 打卡 / 看今日。
- **几乎不会同时改**：通常是「这台用一阵、那台用一阵」；偶尔会在两台都打开。
- **离线是常态**：手机在地铁上、桌面在公司内网，不能假设「永远在线」。
- **没有账号体系**：现在没有登录、没有服务端；不能让方案强依赖「注册一个 Trellis 账号」。
- **AI 也在写数据**：自动完成、计划文档、笔记整理、管家记忆都会产生后台写入。同步冲突提示必须能说明「这是哪台设备、是 AI 还是用户改的」。

### 1.2 数据现状（已经存在的复杂度）

- 数据不是「一个数据库」，而是 **Markdown 文件 + JSON 文件 + 2 个 SQLite（`plan.db` / `activity.db`）+ 追加日志**，每个 store 自己负责读写。
- 大量文件是「整文件重写」（`graph.user.json`、`auto-completions.json`、`ui.json`……），没有统一的 `updatedAt`/`version` 字段。
- 笔记是**文件真相**（`knowledge/notes/<id>.md`），概念索引每次从文件现算——这其实对同步很友好。
- 计划后端是**事件溯源 + 命令外壳**（`event` 表 append-only、`command_id` 幂等），但对「跨设备重放」还缺确定性 ID/全序，详见 §4.4。
- `plan.db` 当前在进程里有 **3 个连接**：主服务、`PlanDocStore`、`PlanWorkflowStore`（`AppRuntime.kt:7081/7112/7113`），而且 `AppRuntime.shutdown()` 目前没关它们。换库必须先解决这一点。

### 1.3 方案约束

- 不重写所有 store；尽量把同步做在「文件系统 + 域适配器」层。
- 不要求设备同时在线；不要求有公网 IP。
- 冲突可以麻烦一次，但不能静默丢数据。
- 传输层（WebDAV/云盘）不一定支持原子 CAS、ETag、事务——协议不能依赖它们（租约是优化，不是正确性边界）。

### 1.4 先排除的路线（避免重复讨论）

| 路线 | 为什么不做 |
|---|---|
| `~/.trellis` 整个目录放 iCloud/Dropbox/坚果云 | SQLite + `-wal`/`-shm`、`.tmp`/`.bak`/`.bad-*`、多个连接同时写；云盘会并发复制/改 mtime/留冲突副本，坏库是时间问题。Android 也没有「挂载云盘目录」这条路。 |
| 自建账号 + 云数据库，服务端为唯一真相 | 要在线、要运维、要处理断网；等于重写所有 store 和本地优先的产品定位。适合多人/团队，不适合单用户。 |
| CRDT / OT / 自动三方合并 | 成本高、调试难；收益只在「真的同时改同一份内容」。本项目并发≈0，先解决「有没有同步」比「怎么合并并发」重要。 |
| git 同步数据 | 二进制 DB 不适合；移动端 git 体验差；AI 后台写入会产生大量自动提交噪音。 |
| 手机只读、桌面单写 | 手机能编辑（勾节点、记笔记）是产品价值；而且离线时手机就没用了。 |
| 数据库级复制（LiteFS / 文件系统快照） | 依赖运维环境，Android 不可用；本项目的部署形态是「用户自己的电脑 + 手机」，不是服务器集群。 |

---

## 2. 数据盘点与同步口径

图例：**✓** 默认同步；**△** 可选/默认关；**✗** 不同步。「策略」里的 LWW = last-write-wins（按 HLC 比较），细节见 §3、§5。

| 域 / 路径 | 写入方式 | 真相 or 派生 | 默认 | 策略 | 备注 |
|---|---|---|---|---|---|
| `knowledge/notes/<id>.md` | 整篇覆盖（tmp+rename） | 真相 | ✓ | 文件 LWW + 冲突副本 | 笔记 id 时间+随机，跨设备不撞；frontmatter 有 `updated` |
| `knowledge/plan-docs/<planId>/*.md` | 整篇覆盖 | 真相 | ✓ | 文件 LWW + 冲突副本 | 只读展示；改由 AGENT；计划删除时随计划清 |
| `knowledge/concepts.json` | 整文件重写 | 真相（用户可改） | ✓ | 整文件 LWW（P3 按概念名合并） | 定义/别名是用户资产，别丢 |
| `knowledge/graph.user.json` | 整文件重写（带 `.bak`） | 真相 | ✓ | 整文件 LWW + 冲突副本（P3 按节点合并） | 分类/归属/关系/墓碑都在里面 |
| `knowledge/graph.cache.json` | 整文件 | 派生缓存 | ✗ | —— | 可删、可由 AI 重算 |
| `plans/plan.db` | SQLite 事务 | 真相 | ✓ | **逻辑快照 + 单写租约** | 绝不传文件本体；见 §4.4 |
| `plans/plan.db.bak-*` | SQLite 备份 | 派生 | ✗ | —— | 本地容灾 |
| `plans/sessions.json` | 整文件重写 | 绑定关系 | △ | key→LWW | 丢了只是自动排周计划找不到会话 |
| `plans/categories.json` | 整文件重写 | 真相（小） | ✓ | 整文件 LWW | |
| `plans/auto-completions.json` | 整文件重写 | 真相 | ✓ | 整文件 LWW（P3 按 entry id 并集） | 丢了会重复自动完成/丢撤销记录 |
| `steward/memory.jsonl` | append | 真相 | ✓ | **行并集 + 去重** | 新行加 `id`；旧行用行 hash 去重 |
| `steward/settings.json` | 整文件重写 | 偏好 | △ | 字段级 LWW | 晨/夜时间、通知开关；通知本身设备本地 |
| `steward/checkpoints.json` | 整文件重写 | **设备本地状态** | ✗ | —— | 每台设备各自跑晨/夜仪式，共享会互相吞 |
| `steward/digests/<day>.md` | 覆盖 | 派生（AI 摘要） | △ | 文件 LWW | 可选 |
| `chat/<id>.json` | tmp+rename 整文件 | 真相 | ✗（可开） | 整文件 LWW + 冲突副本 | AI 上下文/过程是设备相关的；见 §4.6 |
| `exams/attempts.jsonl` | append | 真相 | ✓ | 行并集 + hash 去重 | 新行加 `id`（旧行退化为行 hash） |
| `exams/*.jsonl`（题库） | 只读加载 | 用户资产 | △ | 文件 LWW / 只增 | 题库通常大且静态 |
| `activity/activity.db` | SQLite | **设备事实** | ✗ | —— | 本机窗口/网页采集；不同设备天数不可合并 |
| `activity/taxonomy.json` | 整文件 | 真相（规则） | △ | 整文件 LWW | P3 可选 |
| `activity/settings.json` | 整文件 | 设备本地 | ✗ | —— | |
| `ui.json` `layout.json` `plugin-style.json` `markdown.json` `dev.json` `dev-clock.json` | 整文件 | 设备本地 | ✗ | —— | 屏幕尺寸/键位/调试态，跨端同步只会互相打架 |
| `study.json` | 整文件 | 偏好 | △ | 字段级 LWW | 每天预计投入可以共享 |
| `store.json`（内核 KV） | 整文件重写 | 混合（**任务数据**/插件状态） | ✓（任务） | **以 key 为对象**的 LWW，不整文件覆盖 | 任务页（`KvPlanService`）的数据在这里，换设备必须能看到；插件命名空间可单独关 |
| `plugins/**` | 目录 | 代码/资产 | ✗ | —— | 平台兼容性 + 版本；P3 再做插件包同步 |
| `llm.json` | 整文件 | **密钥** | ✗ 绝不 | —— | 要换机手动导入 |
| `app.log` `audit.log` `plugin-usage.json` `diag/**` | 追加/本地 | 日志/设备 | ✗ | —— | 审计就该是本机的 |

> 为什么笔记/计划文档用「文件 LWW + 冲突副本」而不是「自动合并正文」：用户和 AI 都会整篇重写，按行合并没有可靠语义；留两份让用户选，成本最低、也最不会丢内容。

---

## 3. 同步模型

### 3.1 术语

- **域（domain）**：一组同策略的数据，如 `notes`、`plan-docs`、`records`、`logs`、`plan`。
- **对象（object）**：域内最小同步单元，`key` + `bytes`。文件域 key = 相对路径；记录域 key = 记录 id。
- **blob**：按内容寻址的不可变对象，`blobs/<domain>/<sha256>`。
- **head**：某台设备对「某域每个对象的最新版本」的声明：`{hlc, sha256, size, deleted}`。
- **log segment**：追加日志的不可变段文件，一行一条带唯一 `id` 的记录。
- **cursor**：本机已经读到的「其它设备 / 某域 / 某段」的位置。
- **lease**：单写域的写租约，避免两台设备同时改 `plan.db`。

### 3.2 逻辑时钟（HLC）

所有同步元数据用 **Hybrid Logical Clock**：

```
hlc = "<48-bit 毫秒>-<16-bit 计数器>-<deviceId>"     例：1763538000123-0-dev_01J...
比较：先比毫秒，再比计数器，最后按 deviceId 字典序（保证全序、可复现）
```

- 每台设备本地单调：收到远端更大的 HLC 时把自己的时钟推进（`max(local.wall, remote.wall)`）。
- 设备墙钟偏差 > 5 分钟时，设置页提示「设备时间不准，冲突判定可能偏向一边」。
- **重要**：HLC 用**真实墙钟**，不走 `TrellisClock` 的模拟时间（调试拨日期时不能让同步元数据乱序）。这是 `docs/time-conventions.md` 「禁止直接取系统时间」的**基础设施例外**，在代码里建一个 `SyncClock` 单独封装，避免和业务时间混淆。
- 文件系统 mtime **不能**作为 LWW 依据（云盘同步会改 mtime），只用于「可能变了」的快速扫描。

### 3.3 远端布局（每设备只写自己的命名空间）

```
trellis/v1/
  devices/<deviceId>.json                 # {deviceId, name, platform, appVersion, lastSeen}
  heads/<domain>/<deviceId>.json          # {"schema":1,"objects":{"<key>":{"hlc":"…","sha":"…","size":123,"deleted":false}}}
  blobs/<domain>/<sha256>                 # 不可变内容（文件字节 / 快照 / 日志段）
  logs/<domain>/<deviceId>/<seq>.jsonl    # 追加日志段（不可变；按 1MB 或 7 天切）
  lease/<domain>.json                     # 单写域租约 {deviceId, expiresAt, hlc, forced}
```

要点：

- **文件数量可控**：head 每域每设备 1 个（几百个笔记 ≈ 几十 KB）；blob 只在内容变化时新增；日志段按大小/时间切。
- **读取端自己合并**：拉取所有设备的 head，对每个 key 取 `(hlc, deleted)` 最大者 → 得到「远端视图」。不需要谁来维护「全局 head」。
- **删除 = 墓碑**：head 里 `deleted=true`（不物理删远端对象）。墓碑长期保留（很小）；压缩需要「所有设备都见过」的 ack，MVP 不做。
- **对象不可变**：同一个 blob 路径内容永不改，因此重传/重试幂等，也不会有「半截文件」被当成新版本（读端校验 sha）。
- **head 只由自己覆盖，但仍要防半截**：写入后回读校验 JSON；读端遇到坏 head 时退回本机缓存的上一版 head 并重试，不能把「读不出来」当成空头（否则会误判远端删除）。更稳的做法是 head 也按 HLC 版本化（`heads/<domain>/<device>/<hlc>.json`），代价是文件数，P3 按需切换。

### 3.4 本地布局

```
<home>/sync/
  config.json        # 启用域、transport 配置、WebDAV 地址/用户名（密码不写在这里：P1 另存 600 权限文件 / Android 私有目录，P3 接系统安全存储）
  device.json        # deviceId、设备名（首次启动生成）
  state.json         # cursors、baseline（key → {hlc,sha}）、pending、lastSyncAt、lastError
  sync.log           # 每次同步的结果流水（给人排查：时间/上下行数/冲突/错误）
  .lock              # 同一 home 多开时防止两个实例同时同步
  conflicts.jsonl    # 冲突记录（时间、域、key、本机/远端 hlc、处理方式）
  conflicts/         # 冲突副本落盘处（不放进 notes/，避免被当成笔记列表）
    notes/<noteId>.<hlc>.md
    plan-docs/<planId>/<file>.<hlc>.md
```

- `sync/` 自身**不参与同步**。
- `baseline` 是「上一次同步后双方都认可的版本」，冲突判定全靠它：本地 sha ≠ baseline.sha 且 远端 head.sha ≠ baseline.sha 且两者不相等 → 冲突。

### 3.5 一次同步（pull → 合并 → push）

1. **单飞**：同一时刻只跑一个 sync（进程内 mutex）。同一台机器上把同一个 `TRELLIS_HOME` 开了两个实例时，用 `sync/.lock` 文件锁；拿不到锁的实例只读本地、不跑同步（避免两个进程互相覆盖 state/head）。
2. **拉**：list `devices/`、读各设备 `heads/<domain>/<deviceId>.json`，按 cursor 增量读 `logs/`；下载未缓存的 blob（校验 sha）。
3. **合并**：对每个对象与本地 baseline/本地当前内容比较：
   - 远端新、本地未改 → 应用远端（写文件 / 换库 / 更新记录）；
   - 本地新、远端与 baseline 相同 → 留下待推；
   - 两边都变 → 按域策略处理（冲突副本 / LWW + 记冲突 / plan 让用户选）；
   - 远端删除 vs 本地改 → 默认保留本地为冲突副本，并记录「远端已删」。
4. **推**：本地变更 → 算 sha → 若 blob 不存在则上传 → 更新自己的 `heads/<domain>/<deviceId>.json`；追加日志走 `logs/`。
5. **落 state**：更新 cursors/baseline/lastSyncAt，然后回调 `onApplied(domains)` 让运行时刷新界面。
6. **失败**：任何一步失败可重试；因为「自己写自己、内容寻址、幂等」，不需要事务。错误写 `sync.log` + 设置页红字。

**域应用顺序**：`plan` → `records`（auto-completion/concepts/graph）→ `notes` / `plan-docs` → `logs`。原因：自动完成账本、计划文档引用的是计划节点 id，计划库先到位，后面的引用才有意义；同一轮同步内按这个顺序 apply，最后统一回调刷新。

> 顺序选择「先拉后推」：先知道远端有没有新东西，避免把本地旧版本推上去覆盖对方。若拉下来的东西和本地未推改动冲突，第 3 步已处理，不会静默覆盖。

### 3.6 触发时机

| 时机 | 动作 | 说明 |
|---|---|---|
| 启动装配完成 | 后台 pull（不阻塞首帧） | 有配置才启用；拉完刷新一次 |
| 应用/窗口回到前台 | pull | 用 `onWindowFocused`（桌面 AWT / Android Activity 已有回调） |
| 本地写入后 | debounce 2–4s 的 push | MVP 不挂每个 store 的钩子，用「定时扫描 + 哈希比对」代替 |
| 前台每 60–120s | pull + 扫描 push | 兜底；网络差时退避 |
| 手动「立即同步」 | 全量一次 | 设置页按钮 |
| 退出前 | 尽力 push（不保证） | `shutdown` 里 runBlocking，超时即放弃（下次启动补） |

> MVP 用「轮询扫描」而不是「每个 store 写回调」：笔记几百篇算 sha 是毫秒级，不值得为 100 多个写入点挂钩子。等 P3 要「写完 1 秒内同步」再考虑加钩子。

---

## 4. 分域策略细节

### 4.1 文本文件（笔记 / 计划文档 / 题库）

- 变化检测：`sha256(file)` 与 baseline 比较；目录扫描白名单 `knowledge/notes/*.md`、`knowledge/plan-docs/**/*.md`、`exams/*.jsonl`。
- 无冲突：按 HLC 取新版本，原子写（tmp + rename），再让页面 reload（笔记页每次读文件，天然支持）。
- 冲突：远端版本落到 `sync/conflicts/<domain>/…`，**原文件保留本地版本**；`conflicts.jsonl` 记一条，设置页/笔记页横幅提示「这篇笔记在另一台设备上也改过，旧版本已保留」。
  - 冲突副本不放在 `knowledge/notes/`，因为 `KnowledgeStore.list()` 会把所有 `.md` 当笔记；放 `sync/conflicts/` 不会被列表污染。
  - 冲突副本可以按「保留 N 天」清理，但默认保留，让用户自己删。
- 正在编辑的笔记：编辑器有未保存内容时**不应用**该篇远端版本（只更新列表），避免"正在打字突然被替换"；保存后如果远端也变了 → 走冲突副本。
- 计划文档与 `plan.db` 的耦合：文档是文件、节点在库里，分别同步。极端情况下会出现「文档先到、节点后到」，页面以库为准即可，不需要事务。

### 4.2 追加日志（memory.jsonl / attempts.jsonl / audit.log）

- 新增行加唯一 `id`：`<deviceId>-<hlc>`（写新行时加）。旧行没有 id → `sha1(整行)` 作为去重键。
- **只把自己产生的行写进自己的日志段**：合并进来的远端行只落本机文件，不再推到 `logs/`，否则 A→B→A 会重复增长。判定方式：行 id 前缀设备号 == 本机，或维护 `pushedLineIds`。
- 合并：读远端日志段 → 逐行按 id/hash 去重 → 追加到本地文件；本地文件用 tmp+rename 重写一次（崩溃安全）。
- **顺序问题**：`StewardStore.recentMemory()` 依赖文件顺序（`takeLast`）。远端旧行被追加到文件尾会破坏「越近越靠后」的假设。两种修法任选：
  - 最小改：`readMemory()` 读完按 `(day, at)` 排序再过滤（推荐，顺手）；
  - 或合并时重排整个文件（写放大，不推荐）。
- `audit.log`：本机审计，不同步（表中已标 ✗）。
- 行级冲突不存在（append-only），同一条重复出现由 id/hash 去重。

### 4.3 结构化 JSON（记录域）

P1 先做「整文件 LWW + 冲突副本」，简单可靠；唯一例外是 `store.json`（任务数据必须按 key 同步，不能整文件覆盖）。P3 再对高频、值得合并的域写小合并器。

| 文件 | P1 | P3 合并规则（建议） |
|---|---|---|
| `concepts.json` | 整文件 LWW | 按概念身份键并集；定义非空优先；别名并集；不删别人新加的 |
| `graph.user.json` | 整文件 LWW + 冲突副本 | 按分类/归属/关系节点 id 做 LWW；`removed`/`mergedInto` 墓碑并集；树结构改动仍建议整文件冲突 |
| `auto-completions.json` | 整文件 LWW | entries 按 id 并集（status 取较新、`undoneAt` 保留）；`blocked` 并集；同一 occurrence 两条 auto 记录时按 at 取新 |
| `plans/categories.json` `sessions.json` | 整文件 LWW（P3 按 key） | 按 key（分类 id / planId）LWW |
| `steward/settings.json` `study.json` | 整文件 LWW（P3 字段级） | 按字段 LWW（字段少、合并简单） |
| `store.json` | **按 key 同步（P1）**，不整文件覆盖 | 按 namespace/key LWW；插件状态与插件目录绑定，跨端价值低，可单独关 |

- 整文件 LWW 的冲突也要留副本（JSON 文件落 `sync/conflicts/records/`），不能只记一行日志。
- 这些文件都带 `.bak` / `.bad-*` 习惯，写回时沿用「tmp + rename + 写前备份」即可。

### 4.4 plan.db（核心难点）

#### 为什么不能直接传文件

- SQLite 有 `-wal`/`-shm`；运行中复制/覆盖会拿到不一致状态；
- 云盘并发写会产生「冲突副本」，SQLite 打开就报错；
- `plan.db` 里有触发器/视图/自增序号，文件级 merge 不可行。

#### 方案：逻辑快照 + 单写租约

**快照格式**（JSONL，一行一条，便于流式校验）：

```json
{"format":"trellis.plan.snapshot/1","schemaVersion":1,"appVersion":"1.1.0","deviceId":"dev_01J…","hlc":"1763…-0-dev_01J…","createdAt":"2026-11-19T05:00:00Z","counts":{"plan_unit":123,"occurrence":456}}
{"table":"goal","row":{…}}
{"table":"plan","row":{…}}
…
```

- **必须规范化后再算 sha**：每张表按主键（`event` 按 `seq`）排序，JSON 字段顺序固定，导出格式稳定。否则每次导出的 sha 都不一样，会永远判 dirty、反复上传。
- **sha 算的是逻辑快照的 sha，不是 `plan.db` 文件字节的 sha**：桌面 `sqlite-jdbc` 与 Android SQLDroid/系统 SQLite 的页布局、freelist、journal 都不同，同一个逻辑状态文件字节必然不同。
- **版本兼容**：快照头带 `schemaVersion` / `appVersion`；低版本客户端**拒绝导入**并提示升级（不要用旧 schema 去写新数据）；高版本导入低版本快照时按列名匹配、缺列用默认值，未知表/列忽略并保留。

- **导出**：在 `BEGIN IMMEDIATE` 读事务里按表导出，保证多表一致；表按 FK 依赖顺序输出。
- **导入**：新建**临时 DB 文件** → 按 schema 建表 → `PRAGMA foreign_keys = OFF` → 按 FK 顺序 INSERT（触发器只拦 UPDATE/DELETE，INSERT 不受影响；`occurrence.superseded_by` 这类自引用也不会被顺序卡住）→ `PRAGMA foreign_keys = ON` + `PRAGMA foreign_key_check` 校验 + 行数校验（这两个 PRAGMA 要在事务外切） → 关闭所有连接 → **同目录原子 rename 覆盖** `plan.db` → 重开连接。
- **不要导出**：
  - `job` / `job_step`（计划工作流进行中的过程态，设备本地）；
  - 未结束的 `session`（计时中；结束后下一次再传）；
  - `schema_migration`（新库自己建）；
  - 旧 `plan_doc` 表（2026-11-17 起文档已是文件，只做一次性迁移）。
- **要导出**：`goal`、`goal_milestone`、`process`、`plan`、`plan_version`、`plan_unit`、`unit_resource`、`plan_resource`、`plan_stage`、`plan_stage_topic`、`commitment`、`commitment_unit`、`occurrence`、已结束的 `session`、`todo`、`todo_occurrence`、`event`（审计/复盘依赖）、`material`/`material_version`/`segment`（有数据时）。
- **导入不能丢本机 ephemeral 行**：换库是「临时库 → 覆盖」，本机的 `job`/`job_step`（进行中的计划工作流）、未结束 `session`（计时器在跑）如果不带过去就丢了。做法：导入前先从旧库读出这些行，导入后写回新库；更彻底的是 P2 把 `job`/`job_step` 拆到独立的 `plans/workflow.db`，`plan.db` 只装可同步的真相。
- **本机有未结束 session / 计划页有对话框或拖拽时不要立刻换库**：延后到空闲；否则用户正在计时的那次学习会凭空消失。
- **计划工作流进行中不跨设备接管**：`job`/`job_step` 是设备本地的流程态，另一台看到的是「已经落库的计划 + 文档」，不会看到「某段还在等确认」。这是有意的：半成品流程跨端漂移比看不到更糟。
- **大小**：单用户几百到几 MB；一次全量导出 + 上传在几十 ms–几百 ms 量级，可以接受。用 sha 去重：没变就不上传。
- **和现有导出的关系**：`LearningPlanService.exportJsonl(planId)` 现在只导**事件**（`PlanRhythm.exportEvents`，可审计），`importJsonl` 还是 `E_UNSUPPORTED`（`SqliteLearningPlanService.kt:627-630`）。P0 的全量快照**不要复用**这两个接口，另做 `PlanBackend.exportSnapshot()`（导表）和 `importSnapshot()`（建临时库 → 校验 → 换文件）。

**单写租约**（避免「两端同时改」导致快照二选一）：

1. 进入计划页 / 计划写操作前，尝试获取 `lease/plan.json`：
   - 读当前租约；空、过期、或持有者是自己 → 写入 `{deviceId, expiresAt: now+5min, hlc}`；
   - 等 1s 再读一次确认（防两端同时抢）；仍被别的有效租约占着 → 退避，最多重试 3 次。
2. 持租约期间：正常本地命令；续租每 60s；快照 push 可以 debounce 到 10s 或离开计划页时。
3. 没有租约的设备：计划页进入**只读态**，顶部提示「另一台设备正在编辑计划」+「接管」。接管 = 等对方过期（≤5 分钟）或强制写更高 HLC 的租约；对方下次续租发现被抢 → 停止写入、转只读，并提示「已被另一台设备接管」。
4. **离线/无网**：允许本地写（不阻塞用户），标记 dirty；联网后走下面的冲突流程。
5. 租约只是**优化**：真正的安全网是「push 前比对远端 head 与本地 baseline」。即使租约被抢/时钟错乱，也只会落到冲突面板，不会静默覆盖。
6. **不想做租约的退化版**：至少做到「进入计划页 / 每次写命令前先拉一次远端 head」——远端比自己新就先换库再让用户操作，冲突仍走二选一。租约只是把「两边同时改」的概率再降一档，可以放到 P2 后半段或 P3。

**冲突处理（plan.db）**：

| 本地 | 远端 | 处理 |
|---|---|---|
| 未变（sha = baseline） | 新 | 直接换库 + `reload`，刷新页面 |
| 变了（dirty） | = baseline | push 本地快照 |
| 变了 | 也变了 | **不自动合并**：弹「计划在两台设备上都改过」，三个选项：①保留本机并覆盖云端；②采用云端（本机先备份）；③先导出两边快照（JSONL）再决定 |
| 本地删除计划 / 远端还在改 | —— | 以快照整体为准：删除是软删（`process.deleted_at`），会随快照传播；远端改同一计划 → 落到冲突面板 |

- 覆盖前自动写 `plans/plan.db.bak-<hlc>`，并把旧快照作为历史版本保留（blob），设置页可回滚。
- **要接受一个事实**：二选一 = 丢掉被覆盖那一方「最后一次改动」（不是全部历史，旧快照还在 blob 里）。这是不引入命令日志的代价；用备份 + 历史恢复兜底，而不是假装能自动合并。
- 冲突记录里要写清「哪台设备、什么时间、用户还是 AI（事件 `actor`）」——从快照的 `event` 表能读出最后几条改动类型，用于提示文案。

**何时同步**：

- 启动装配完成后 pull 一次（若拉下新库，reload 后刷新首页/计划页）；
- 进入计划页时弱检查一次（拉 head 比对，不同就换库）；
- 计划写后 dirty；60s tick 或离开计划页时 push；退出时尽力 push。
- 快速判 dirty：先看 `event` 的 `MAX(seq)` 与各表行数有没有变，变了再全量导出算 sha（省掉每 60s 一次全量 JSON 化）。

**前置重构（不做这个，快照换库会踩坑）**：

- 把 `plan.db` 的 3 个连接收敛为 **`PlanBackend`**：持有 `LearningPlanService` + `PlanDocStore` + `PlanWorkflowStore`，提供 `close()` / `reopen()` / `exportSnapshot()` / `importSnapshot()` / `snapshotHash()`。
- `AppRuntime.shutdown()` 补上 `planBackend.close()`。
- 换库 / reload 时：若计划页正在显示对话框、拖拽、或计时器正在跑（session 打开），**延后到空闲**再 reload；UI 状态用 `refreshPlan()` / `refreshGoals()` 重建。
- `PlanDocStore` 构造时自己 `open(plan.db)` 跑迁移（`PlanDocStore.kt:286`）；`PlanWorkflowStore` 同样（`PlanWorkflowStore.kt:17`）——都改成用 `PlanBackend` 的连接。

**P2 备选（暂不做）：命令日志**

学习计划后端已经是「事件溯源 + `command_id` 幂等」，理论上可以同步**命令**而不是快照：

- 需要解决：命令内部用 `Ids.next()` 生成 ID（`PlanCommands.kt` 20+ 处），跨设备重放会生成不同 ID → 要么改成「命令作用域内确定性 ID」，要么维护 ID 映射表；
- 需要解决：两台设备的命令「全序」由谁定；本地先执行、后按服务端顺序重放会出现顺序不一致（命令不一定可交换）；
- 收益是运行中增量同步、事件级审计；代价是命令注册表（40+ 方法）+ ID 改造 + 大量兼容测试。

> 结论：**P2 用快照 + 租约**。等真的出现「需要边用边同步计划」的需求，再评估命令日志；那时也已经有快照兜底。

### 4.5 活动数据

- `activity.db` 是**每台设备的屏幕事实**，不同设备采集的不是同一批事件，不能并集也不能 LWW——**默认不同步**。
- ActivityWatch 本身有 `aw-sync`（它自己的事件级同步）；如果用户需要跨设备活动，走 AW 自己的方案，Trellis 不掺和。
- `activity/taxonomy.json`（分类规则）是用户的「知识」，可 P3 可选同步（整文件 LWW）。
- 归因（`activity_attribution`）依赖本机片段，跨端意义有限，不同步。计划页「屏幕使用」徽标按设备各算各的，同步计划后一边有归因一边没有是**预期行为**。

### 4.6 会话（chat）

- 默认**不同步**：会话里包含本机模型配置、工具过程、思考、失败重试；跨端同步会出现「另一台设备的运行记录」，且 ChatScreen 的未读/展开状态是设备本地的。
- 可选开关（P3）：
  - 只同步「用户消息 + 最终回答」，不同步过程/思考/工具结果（需要给 `ChatLine` 加同步标记，改动不小）；
  - 或整文件 LWW + 冲突副本（简单，但会出现两套对话文件）。
- MVP 建议：设置页写清「会话记录默认只在本机」，避免用户误以为"换手机聊天记录也在"。

### 4.7 设置、密钥、插件

- `ui.json` / `layout.json` / `plugin-style.json` / `markdown.json` / `dev.json` / `dev-clock.json` / `plugin-usage.json`：**设备本地**（屏幕、键盘、调试态天然不同）。
- `study.json` / `steward/settings.json`：可选同步（字段级 LWW）。
- `llm.json`：**绝不同步**（API Key）。换机用现有的「一键导入」或手动填。
- `plugins/**`：P1 不同步（平台相关 + 版本管理 + 插件市场是另一条线）。插件自己的状态在 `store.json`，默认也不同步。
- 设置页需要一块「数据与同步」：总开关、传输配置、域勾选、上次同步时间/错误、立即同步、冲突列表、设备列表、重置本机同步。

---

## 5. 冲突与一致性

### 5.1 决策矩阵（用户视角）

| 场景 | 系统行为 | 用户看到 |
|---|---|---|
| A 改笔记 1，B 改笔记 2 | 各自推送，合并没有冲突 | 无感 |
| A、B 同时改同一篇笔记 | 远端版本进 `sync/conflicts/`，原文件保留本地 | 笔记页横幅：旧版本已保留 → 打开/删除 |
| A 改计划，B 只读浏览 | 租约让 B 只读；A push 后 B 下次刷新可见 | B 顶部提示「另一台设备正在编辑计划」 |
| A 改计划，B 离线也改计划 | 两边都 dirty → 冲突面板 | 二选一（覆盖前自动备份） |
| A 删笔记，B 正在改该笔记 | 保留 B 版本 + 记冲突 | 冲突列表：远端已删除 |
| 时钟差 2 小时 | HLC 仍可比较；设置页提示校准 | 提示「设备时间不准」 |
| 网络断了 | 本地照常写，队列等待 | 同步状态「等待网络」 |
| 云端被清空/换账号 | 本机 baseline 与远端不匹配 | 提示「远端数据不在了」，可重新上传本机或停止同步 |

### 5.2 不变量

1. **冲突不静默丢数据**：覆盖前备份、冲突副本、plan 二选一前备份。
2. **同步是幂等的**：重复跑同一套 pull/push 结果不变；blob 内容寻址；head 只写自己的。
3. **删除可传播、可恢复**：墓碑不物理删；恢复 = 用历史 blob 回滚（至少 plan 支持）。
4. **失败可见**：每次同步结果（时间、上传/下载数、冲突数、错误）写 `sync.log` + 设置页。
5. **协议向前兼容**：未知域/对象/字段忽略并保留，不因为版本不同删数据；`schema` 升版时旧客户端只读。

### 5.3 墓碑与清理

- 笔记/文档删除：远端 head `deleted=true`，本机文件删除（放 `sync/trash/` 保留 30 天可选）。
- 墓碑长期保留（每个 key 一条 JSON，量很小）；压缩要等「所有已登记设备都见过了」，MVP 不做，设置页提供手动「清理 90 天前的墓碑」并提示风险。
- blob/历史：默认保留最近 30 天 + 每个对象最近 5 个版本；设置页显示占用/可清理。

---

## 6. 传输层

### 6.1 接口

```kotlin
interface SyncTransport {
    suspend fun get(path: String): ByteArray?                 // 不存在返回 null
    suspend fun put(path: String, bytes: ByteArray)           // 覆盖或创建（上层保证只写自己的）
    suspend fun list(prefix: String): List<RemoteFile>        // path, size, lastModified, etag?
    suspend fun delete(path: String)
    val capabilities: TransportCapabilities                   // conditionalPut / etag / move
}
```

- 协议**不依赖** CAS/ETag/事务；`capabilities` 只用来做租约优化。
- 所有路径都是相对 `trellis/v1/` 的 POSIX 风格字符串。

### 6.2 WebDAV（推荐）

- 坚果云 / Nextcloud / Alist / 群晖 WebDAV 均可；桌面与 Android 用同一套 HTTP。
- 方法：`PROPFIND Depth: 1` 列表、`GET`/`PUT`/`MKCOL`/`DELETE`；Basic Auth（App Password，不用主密码）。
- 平台出口：
  - 桌面 `:net` 用 `java.net.http.HttpClient`，支持任意 method；
  - **Android 的 `HttpURLConnection` 对自定义 method（PROPFIND）可能拒绝**（需要实测；如果不行，Android 侧引入 OkHttp，或者退化成「自建 HTTP 服务只走 GET/PUT/LIST」）。
- 注意：坚果云有流量/请求频率限制，同步要 debounce、增量、别每次拉全量 blob；`PROPFIND` 列表缓存到内存。

### 6.3 本地文件夹（桌面 + 测试）

- `FolderTransport`：直接读写一个目录（iCloud Drive/Dropbox/Syncthing/本地测试目录）。
- 单机测试用 `LoopbackTransport`（内存 map）或两个临时目录模拟两台设备。
- 只建议桌面↔桌面；Android 访问云盘目录需要 SAF/文件权限，不作为首选（WebDAV 更稳）。

### 6.4 自建 HTTP 服务（P3，可选）

- 一个极薄的服务：`GET/PUT/LIST` + 条件写（CAS）+ 租约 + 可选推送（SSE/WebSocket）。
- 什么时候值得做、和「官网 / Agent 云数据源」共用哪些基础设施，见 [cloud-roadmap.md](cloud-roadmap.md) §3（S2）。
- 好处：真正原子、无需客户端 sleep 1s 抢租约、可做「在线设备」提示；还可以顺带做**局域网直连**（桌面跑服务，手机同一 Wi-Fi 直接同步，不经过公网）。
- 坏处：要部署/运维/证书/备份；不是第一版必需。

### 6.5 安全与隐私

- WebDAV 用 HTTPS + App Password，**不要用网盘主密码**。密码不参与同步：P1 先放 `sync/config.json`（桌面文件权限 600、Android 应用私有目录；Android 的 `filesDir` 本来就只有本应用能读）；P3 再接桌面 Keychain / Android EncryptedSharedPreferences。
- 默认不同步 `llm.json`、`activity.db`、日志；笔记内容在网盘服务商侧是明文。
- 可选 E2E（P3）：设备间共享口令派生密钥，blob 加密后再上传；代价是服务端不可索引/不可恢复（口令丢了数据就没了），且 head 里的 sha 需要改成 HMAC。先把「明文 + 可信 WebDAV」跑通，再决定要不要 E2E。
- 设备撤销：设置页「移除设备」= 删除 `devices/<id>.json` 并把该设备 head 标记忽略（本机 `state.json` 加 `ignoredDevices`），避免离职/丢手机后旧设备继续写。

---

## 7. 与运行时集成（代码落点）

### 7.1 新模块 `:sync`

像 `:net` 一样按 `commonMain` + `jvmMain` + `androidMain` 拆；P1 可以先把实现放 `jvmAndAndroidMain`（Java IO 两端都有）：

```
sync/src/commonMain/kotlin/dev/trellis/sync/
  SyncEngine.kt          # 调度：pull/merge/push/冲突记录
  SyncTransport.kt       # 接口 + RemoteFile + capabilities
  Hlc.kt                 # 生成/比较/推进
  SyncState.kt           # cursors/baseline/pending 的 JSON 读写
  SyncObject.kt          # head/object/blob 元数据
  SyncDomain.kt          # 域适配器接口
sync/src/jvmAndAndroidMain/kotlin/dev/trellis/sync/
  WebDavTransport.kt     # 走 :net（桌面 java.net.http / 安卓 OkHttp 或 HttpURLConnection）
  FolderTransport.kt
  SyncStore.kt           # <home>/sync 读写
```

域适配器（依赖具体 store，放在 `:app:ui` 或各自 feature）：

```kotlin
interface SyncDomain {
    val id: String                 // "notes" / "plan-docs" / "records" / "logs" / "plan"
    suspend fun scanLocal(): List<LocalObject>       // 扫描 → key + bytes + 本地 hlc
    suspend fun applyRemote(change: RemoteChange): ApplyResult   // 写文件/换库/reload
    suspend fun onConflicts(conflicts: List<SyncConflict>)       // 可选：页内提示
}
```

### 7.2 AppRuntime 接线

- `create()` 末尾：读 `sync/config.json`，有配置则构造 `SyncEngine` 并后台跑一次 pull；没有配置不启动（**演示/临时 `TRELLIS_HOME` 默认不启用**）。
- 新方法：`syncNow()`、`syncStatus`、`syncConflicts`、`resolveConflict(id, action)`、`applyRemoteDomains(domains)`。
- `onWindowFocused()`（桌面 `Main.kt` 的 AWT 监听 / Android `MainActivity.onResume`）触发 pull。
- `shutdown()`：`planBackend.close()` + 尽力 push（带超时）。
- 设置页新增 `SyncSection`；首屏/页头可放一个小状态（同步中/上次同步/冲突数），不做常驻打扰。
- 刷新钩子：笔记 → 重新列文件；计划 → `refreshPlan()`/`refreshGoals()`；偏好 → 重读 store；冲突 → 打开冲突面板。

### 7.3 现有 store 的小改动（尽量少）

| 改动 | 原因 | 风险 |
|---|---|---|
| `StewardStore.readMemory()` 读后按 `(day, at)` 排序 | 合并后文件顺序不再严格 | 低，行为等价 |
| `ExerciseAttemptStore` 新行加 `id`（旧行兼容） | 日志去重 | 低，解析忽略未知字段即可 |
| `StewardStore.appendMemory` 新行加 `id` | 同上 | 低 |
| `AppRuntime` 增加 `reloadPlanBackend()` / `PlanBackend` | 快照换库 | 中，需要处理打开中的 UI |
| `KnowledgeScreen` 冲突横幅 | 冲突可见 | 低 |
| 设置页 `SyncSection` | 配置/状态 | 低 |
| **`DataWipe` 清数据前先停同步、清 `sync/state.json`** | ①不要留同步凭据；②否则「本机清空」会被当成删除推给其它设备，把云端/另一台也清掉 | **高，必须做** |
| `FileStorageBackend` 加 `applyRemote(ns,key,value)` / `reloadFromDisk()` | `store.json` 按 key 同步：远端写入必须进内存 map，否则下一次本地写会把远端值覆盖 | 中 |

> 其余 store 不需要改：文件域靠扫描，JSON 域靠整文件覆盖，读取永远从文件来。
> `store.json` 是例外：它是「内存 map + 每次整文件落盘」，必须加一个受控的「应用远端 key」入口，不能直接改文件。

---

## 8. 落地计划

### P0（0.5–1 天）：全量导出/导入 zip

- 新增「导出全部数据」：`<home>` 白名单 + `plan.db` 逻辑快照（JSONL）打包成 zip；「导入」前先备份现有数据，导入后重建 plan.db。
- 用途：换电脑/换手机搬家、给同步做测试夹具、出问题回滚。
- 验收：导出 → 清空临时 home → 导入 → 计划/笔记/记忆/错题都在；`gradlew test` 绿。

### P1（1.5–2 周）：文件 + 日志 + JSON 记录 + 任务 KV

- `:sync` 骨架、HLC、head/merge、FolderTransport、WebDavTransport、域适配器（notes/plan-docs、logs、records、store KV）。
- 设置页 SyncSection、冲突面板、启动/前台/定时/手动触发、错误提示。
- 验收（两台设备/两个临时 home）：
  1. A 改笔记、B 改另一篇 → 两次同步后两边都有；
  2. 同一篇同时改 → 两边各保留一份 + 冲突提示；
  3. 记忆/错题分别追加 → 两次同步后行数正确、无重复；
  4. 断网改 → 恢复网络后自动补传；
  5. 时钟改 2 小时 → 仍能收敛，只有提示不崩；
  6. 任务页新建的任务在另一台设备同步后能看到（`store.json` 按 key 同步）；
  7. 本机「清除数据」不会把删除推到另一台（同步先停、state 清空，重新启用按新设备拉取）。

### P2（1–1.5 周）：plan.db

- `PlanBackend` 收敛 + `exportSnapshot`/`importSnapshot`/`snapshotHash` + 单写租约 + 冲突二选一 + 历史快照恢复 + 启动/进计划页/退出同步。
- 验收：
  1. 手机勾节点 → 桌面启动/刷新后看到；
  2. 桌面改计划、手机只读并提示 → 接管后同步一致；
  3. 两边都改 → 二选一，被覆盖侧有 `.bak` + 历史快照；
  4. 快照导入后 `LearningPlanServiceTest` 全绿、`foreign_key_check` 干净；
  5. 运行中换库不丢 UI 状态（对话框/计时器场景延后）。

### P3（按需）：记录级合并、聊天、E2E、自建服务

- 合并器：concepts / graph.user / auto-completions；
- 聊天可选同步（只同步用户消息+最终回答）；
- E2E 加密、局域网直连/推送；
- 历史清理与容量管理。

---

## 9. 测试

### 9.1 单元/集成

- HLC：单调、收到远端后推进、比较全序；
- head 合并：多设备/墓碑/删除 vs 编辑；
- 文件域：无冲突 LWW、冲突副本、编辑中不应用；
- 日志域：并集去重、旧行 hash、排序修复；
- 记录域：整文件 LWW、冲突副本、写坏文件（`.bad-*`）；
- plan 快照：导出→导入 round-trip（随机生成计划库）、触发器不误伤、FK 校验、ephemeral 表不进快照、事件表 seq 保留；
- 租约：获取/续租/过期/抢占/离线；
- 幂等：连续两次同步结果相同；中途失败重试（blob 上传一半、head 未更新）。

### 9.2 双设备模拟（重点）

用 `LoopbackTransport` 或两个临时目录 + 两个 `SyncEngine` 实例，在 JVM 测试里跑场景表：

```
A 改 / B 改 / A、B 同时改 / A 删 B 改 / 离线 / 时钟偏差 / 部分失败 / 新设备首次全量 / 设备撤销
```

断言：无冲突时最终文件内容一致；有冲突时「两份都在 + conflicts 记录」；重复同步不增长。

### 9.3 手工矩阵

- macOS ↔ Android（坚果云 WebDAV）：真机、弱网、飞行模式；
- 桌面 ↔ 桌面（iCloud/Dropbox 文件夹 / Syncthing）；
- 删笔记、删计划、改设置、AI 自动完成、AI 写计划文档后同步；
- 设备时间手动改 ±2h / +1 天；
- 同步过程中杀进程/断网。

---

## 10. 风险、限制与待拍板

### 10.1 已知风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| WebDAV 服务商差异（方法/ETag/频率/大小） | 同步失败或很慢 | 先用坚果云/Nextcloud 实测；协议不依赖 CAS；列表缓存 + 增量 |
| Android 不支持 PROPFIND | WebDAV 传输不可用 | 引入 OkHttp for Android，或走自建 HTTP 服务 |
| plan.db 全量替换期间 UI 正在用 | 崩溃/状态错乱 | `PlanBackend` + 空闲检查 + 延后 reload |
| AI 后台写入造成「我没改但冲突了」 | 用户困惑 | 冲突/同步记录标注来源（事件 actor、设备名、时间）；设置页可关 AI 自动完成同步 |
| 云盘/服务商看见明文笔记 | 隐私 | 可信服务商 + 可选 E2E（P3） |
| blob/历史无限增长 | 占用空间 | 保留策略 + 设置页容量/清理 |
| 多用户/共享需求 | 方案不适用 | 明确「单用户」边界，别被带偏 |

### 10.2 明确不做

- 实时协同（多人/多端同时编辑同一篇）
- CRDT / OT / 自动三方合并正文
- 活动原始事件跨端合并（AW 的 `aw-sync` 自己解决）
- 多用户共享/团队协作
- 把 `~/.trellis` 整个目录丢进云盘（SQLite/WAL/临时文件/冲突副本会烂）
- 用 git 同步数据库/二进制

### 10.3 需要拍板

1. **传输首选**：WebDAV（坚果云/Nextcloud）？还是自建小服务？还是「桌面+手机都装 Syncthing」？（推荐 WebDAV）
2. **聊天记录**要不要跨端？默认不同步，若需要，接受「只同步用户消息+最终回答」还是「整文件 LWW」？
3. **活动数据**确认不跨端？（推荐不同步）
4. **计划单写租约**的交互接受吗：另一台在编辑时本机只读 + 「接管」。
5. **笔记冲突**留两份、用户手动选，接受吗？（推荐接受；自动合并风险大）
6. **E2E 加密**现在要不要？会牺牲服务端可读性、忘记口令不可恢复。
7. 首批同步域是否就是「笔记、计划文档、概念、图谱、自动完成、记忆、错题、计划分类、计划库、任务（`store.json`）」？

---

## 11. 附：和现有设计的衔接

- **时间规范**：HLC 用真实墙钟，业务时间仍走 `TrellisClock`（`docs/time-conventions.md`）；`TRELLIS_TODAY` 模拟日期不影响同步顺序。
- **计划后端**：快照只搬数据，不改命令语义；`event` 表随快照走，复盘/as-of 仍成立；`command_id` 幂等继续在本机生效。
- **知识库**：笔记是文件真相，同步天然契合；概念索引每次现算，不需要额外同步索引。
- **内核/插件**：同步不碰内核；插件目录与 `store.json` 默认不同步，保持「插件是设备安装态」的现状。
- **隐私/安全**：默认不传密钥/活动/日志；WebDAV 凭据 P1 放受权限保护的文件（桌面 600 / Android 应用私有目录），P3 再接系统安全存储。

---

## 12. 实现进度（2026-11-19）

P1 的「文件域」垂直切片已落地：`:sync` 模块 + 桌面端接线；传输支持**本地文件夹**和**HTTP 服务**（可放在 token 网关/临时隧道后面）；
` :sync-server ` 这个薄服务端也已加上。记录域、plan.db 快照、WebDAV 还没接。

### 12.1 已完成

- 新模块 `:sync`（Kotlin Multiplatform：jvm + android，主体在 `jvmAndAndroidMain`）：
  - `Hlc.kt`：HLC 生成/比较/observe；`HlcClock` 走真实墙钟。
  - `SyncModel.kt`：`ObjectHead` / `DeviceHeads` / sha256 / head 与冲突记录的 JSON。
  - `SyncTransport.kt` + `FolderTransport.kt`：本地/挂载目录传输；路径越界防护；tmp+rename 原子写。
  - `HttpSyncTransport.kt`：GET/PUT/LIST 走 HTTP；`X-Token` 头；404/401/5xx 区分；URL 编码。
  - `SyncStore.kt`：`<home>/sync/`（device/state/conflicts/sync.log），baseline 持久化。
  - `SyncEngine.kt`：pull → merge → push；墓碑删除；两端都改留冲突副本；重复同步幂等。
  - `SyncDomain.kt` + `FileTreeDomain.kt`：文件域（相对路径 key、可配置扩展名）。
  - `SyncConfig.kt` + `TrellisSyncService.kt`：`<home>/sync/config.json`（transport / remotePath / baseUrl / token）与面向宿主的入口。
- 新模块 `:sync-server`（JVM，JDK 自带 `HttpServer`，无数据库）：
  - `GET /health`、`GET|PUT /v1/object?path=...`、`GET /v1/list?prefix=...`；
  - 账号接口 `/v1/auth/register|login|me|logout`、`/v1/users`（见 [user-system.md](user-system.md)）；
  - 对象接口必须带用户 token（`X-Trellis-Auth: Bearer ...`），按账号写到 `<root>/users/<userId>/`；
  - 路径越界防护、tmp+rename、请求体上限；首个注册用户自动 ADMIN，之后注册要 `--invite-code`。
- 桌面接线：
  - `AppRuntime` 启动时读配置并启动后台同步；回到前台补一次；每 60 秒一轮；
  - 设置页「多端同步」：选「文件夹 / HTTP 服务」、填地址与口令、立即同步、显示上次结果/错误；
  - `shutdown` 取消同步任务。
- 账号：新模块 `:user`（用户/密码哈希/会话/本地缓存/账号 HTTP 客户端）+ `:app:ui` 的「账号」设置区；详见 [user-system.md](user-system.md)。
- 测试：8 个 `:user:jvmTest` + 25 个 `:sync:jvmTest` + 8 个 `:sync-server:test`（含注册/登录/多账号隔离/对象 round-trip/端到端），全部通过。

### 12.2 怎么试

1. 两台设备（或两个 `TRELLIS_HOME`）先「设置 → 账号」登录同一账号；
2. HTTP 模式最快路径：账号卡片里点「用此账号开启同步」（自动带上服务器地址和网关口令并跑一轮），然后点「立即同步」；
   - 文件夹模式：在「多端同步 → 配置」填同一个目录（iCloud/Dropbox/Syncthing/局域网共享目录）；
   - 手动 HTTP：填服务地址（如 `https://xxx.trycloudflare.com`）和网关口令。
2. 一台改笔记或计划文档，等 60 秒或点「立即同步」，另一台同步后可见；
3. 同一篇两边同时改 → 各留一份，冲突版本在 `<home>/sync/conflicts/<domain>/`。

### 12.3 还没做（按优先级）

1. **记录域**：`concepts.json` / `graph.user.json` / `auto-completions.json` / `store.json`（按 key）——整文件 LWW + 冲突副本。
2. **追加日志域**：`steward/memory.jsonl` / `exams/attempts.jsonl`（并集 + 去重 + 读取排序修复）。
3. **plan.db 逻辑快照 + 单写租约**（前置：`PlanBackend` 收敛 3 个连接）。
4. **WebDAV transport**（HTTP transport 已做；WebDAV 的 PROPFIND 还没接，安卓可能要 OkHttp）。
5. **冲突面板**：现在只有 `conflicts.jsonl` + 副本文件，设置页还没有列表/处理入口。
6. **写前检查**：进入计划页/写命令前先拉一次 head（租约的退化版）。
7. **多开锁**：同一 home 两个实例时用 `sync/.lock` 只让一个跑同步（设计里有，代码未接）。

### 12.4 已知限制

- 只同步**知识库笔记 + 计划文档**；会话、活动、`llm.json`、插件、UI/布局不同步（理由见 §2）。
- 冲突副本目前只能从 `sync/conflicts/` 手动打开，页面内还没有横幅。
- 桌面端未跑真实 UI（只做了编译验证）；Android 目标未在本机编译（缺 Android SDK），但代码只用 java.io/java.nio（minSdk 26 可用）。
- 用户系统 v1 没有登录失败锁定/找回密码/账号删除；试用阶段靠邀请码 + 网关限流。

### 12.5 部署 HTTP 服务（网关 + 临时隧道）

结构：

```
Trellis 客户端 → Cloudflare 隧道 → 网关 :8100（校验 token）→ sync-server :8000（对象存储）
```

服务端构建（有 JDK 17 的机器）：

```bash
./gradlew :sync-server:installDist
# 产物：sync-server/build/install/sync-server/{bin,lib}
```

零依赖替代（VPS 没有 JDK 时先用）：`tools/sync-server/sync_server.py`（Python 3.8+ 标准库）。
它只实现**对象端点**（GET/PUT/LIST），没有账号系统；要用户/会话隔离必须用 Kotlin `:sync-server`。

```bash
python3 tools/sync-server/sync_server.py --host 127.0.0.1 --port 8000 \
  --root /root/workspace/sync-data
```

正式环境仍建议用 Kotlin `:sync-server`（有测试、和前两者同协议）。

如果对端是手机/NAS/另一套程序，先让它按 [server-integration-contract.md](server-integration-contract.md)（v1，含 OpenAPI 与验收清单）实现，再做联调。

部署到 VPS（示例）：

```bash
rsync -av sync-server/build/install/sync-server/ root@vps:/root/workspace/sync-server/
ssh root@vps
cd /root/workspace
# 只绑 127.0.0.1：外部只能经过网关进来；--invite-code 给第二个及之后的注册用户用
nohup sync-server/bin/sync-server --host 127.0.0.1 --port 8000 \
  --root /root/workspace/sync-data --invite-code '<随机邀请码>' > sync-server.log 2>&1 &
```

网关（你已经有的那套）：

```bash
python3 - <<'P'
import json; p="/root/workspace/server/gateway.json"
c=json.load(open(p)); c["target"]="http://127.0.0.1:8000"
json.dump(c, open(p,"w"), indent=2)
P
sh gw.sh restart
```

验证：

```bash
curl -fsS http://127.0.0.1:8000/health          # 服务端本机探活，不需要 token
curl -fsS -H "X-Token: <网关口令>" https://<隧道域名>/health
# 不带 token 应该是 401；带 token 是 200

# 注册第一个用户（自动 ADMIN）并拿用户 token
REG=$(curl -s -H "X-Token: <网关口令>" -H "Content-Type: application/json" \
  -X POST --data '{"username":"alice","password":"password123"}' \
  https://<隧道域名>/v1/auth/register)
USER_TOKEN=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["token"])' "$REG")

# 用用户 token 访问对象接口
curl -fsS -H "X-Token: <网关口令>" -H "X-Trellis-Auth: Bearer $USER_TOKEN" \
  "https://<隧道域名>/v1/list?prefix="
```

客户端（桌面/手机）：设置 → 多端同步 → **HTTP 服务**，填隧道地址和口令；或者直接写
`<home>/sync/config.json`：

```json
{
  "schema": 1,
  "enabled": true,
  "transport": "http",
  "baseUrl": "https://<你的隧道域名>",
  "token": "lXH...",
  "domains": ["notes", "plan-docs"]
}
```

用户会话 token 不在 `sync/config.json`：登录后存在 `<home>/account.json`（见 [user-system.md](user-system.md)）。

注意：

- `/health` 故意不校验 token，方便网关/隧道探活；其它端点必须带网关 token（若启用）。
- 服务端不校验网关 token（因为网关已经校验并在转发前剥掉），但**对象接口必须带用户 token**（`X-Trellis-Auth`）；**不要**把 8000 直接暴露到公网。
- 隧道地址重启会变，旧地址会返回 **530 / TLS 超时**；账号里的 `serverUrl` 和同步 `baseUrl` 必须一起更新，通常要重新登录一次。Quick Tunnel 只适合临时试跑，长期用请换命名隧道/自有域名/Tailscale。
- 网关不支持 WebSocket——这正好，同步全程是 HTTP 请求/响应，不需要长连接。

**多用户试用靠账号系统隔离**（见 [user-system.md](user-system.md)）：

1. 部署时给 `sync-server` 一个随机 `--invite-code`；第一个注册用户自动 ADMIN；
2. 每个试用者注册自己的账号；对象写到 `<root>/users/<userId>/`，互不可见；
3. 网关仍用统一 trial token 挡外网（`X-Token`），用户 token 走 `X-Trellis-Auth`；
4. 限流按网关 token/IP 做（建议 120 请求/分钟、单对象 ≤ 5MB、总存储配额），不要按「总调用次数」——一次同步是多个请求；
5. 口令/用户 token 都不要放 query（浏览器/代理日志会记 URL）。

---

## 13. 参考与对照

- 本地优先软件七理想（Local-first software, Ink & Switch）：多设备、离线、协作、长期可用——本方案只取「多设备 + 离线 + 长期可用」，放弃实时协作。
- Obsidian Sync / Remotely Save：文件级同步 + 冲突文件，本方案同类。
- Syncthing：文件级同步；对 SQLite 不友好，这也是「SQLite 不进同步盘」的论据。
- ActivityWatch `aw-sync`：事件级合并，适合活动数据但不适合计划/笔记。
- HLC（Hybrid Logical Clock）：无需中心时钟的全序时间戳。
- SQLite 官方备份 API / `VACUUM INTO`：快照的备选实现，但移动端 SQLite 版本不可控，故本方案用逻辑导出/导入（纯 SQL，桌面/Android 都能跑）。
