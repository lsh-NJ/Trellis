# 云端与服务器路线（官网 / 多端同步 / Agent 云数据源）

> 状态：**方案 Draft v0.1**（2026-11-19）
> 关联：[multi-device-sync.md](multi-device-sync.md)（同步协议与分域策略）
> 定位：回答「现在要不要搞服务器、以后官网和 Agent 云数据源怎么接」，给判定标准与分阶段路线，不写具体实现代码。

---

## 0. 结论

**不要先搞「大一统服务器」。** 把三件事拆开，各自的答案不一样：

| 诉求 | 现在需要服务器吗 | 最小做法 | 什么时候才升级 |
|---|---|---|---|
| **官网**（介绍/下载/文档/更新日志） | **不需要** | 静态托管 + CDN（Cloudflare Pages / Vercel / 阿里云 OSS） | 需要账号、支付、下载鉴权、用户数据看板时 |
| **多端同步** | **不需要**（先） | WebDAV（坚果云/Nextcloud）+ 同步协议 | 需要推送/在线状态/免配置/多用户时，换自建同步服务 |
| **Agent 云端数据源** | **要一个「薄 API」**，但不必是常驻服务器 | Serverless：公共内容 API + 搜索代理 + 版本/公告 | 要读**用户自己的云数据**、或 Agent 在网页/后台跑时，才上账号+存储+常驻服务 |

**推荐起步**：静态官网 + WebDAV 同步 + 一个 Serverless 薄 API（只做公共内容/搜索代理，**不存用户数据**）。这条路的成本接近 0、隐私风险最低，还能立刻让 Agent 有「云端数据源」。

**上常驻服务器的触发条件**（满足 ≥3 条再考虑）：

1. 用户不想配 WebDAV（非技术用户）；
2. 需要推送、实时、在线设备列表；
3. 需要在网页端/没有设备在线时访问用户数据；
4. Agent 要在后台定时跑（主动式管家、周报、提醒）；
5. 要做账号、分享、多用户、付费；
6. 要统一代理模型/搜索的 API Key、做配额与计费；
7. 要做官方资源分发、下载统计、灰度/feature flags。

---

## 1. 三个诉求别混在一起

### 1.1 官网 = 静态站 + 少量动态

- 页面：首页、功能、截图、下载（macOS / Windows / Android APK）、更新日志、文档、隐私政策、FAQ、反馈入口。
- 技术：Astro / VitePress / Next 静态导出，或直接手写 HTML；托管在 Cloudflare Pages、Vercel、Netlify、阿里云 OSS + CDN、GitHub Pages 都行。
- 「动态」其实很少：
  - 下载计数 → 托管平台自带的 Analytics / Umami / Cloudflare Web Analytics；
  - 反馈 → Formspree / 一个 Worker；
  - 版本检查 → 一个静态 `latest.json`（客户端拉，不需要后端）；
  - 灰度/公告 → 静态 JSON 或 CDN 边缘配置。
- 真正需要服务端的场景：账号登录、付费、下载鉴权、用户数据看板、插件市场。**这些都可以后置**。

### 1.2 多端同步 = 先 WebDAV，服务器是「体验升级」

- 同步协议已经按「每设备命名空间 + 内容寻址 blob + head/log」设计（见 [multi-device-sync.md](multi-device-sync.md)），传输层是可替换的。
- WebDAV 的短板：租约要靠「读→写→隔 1 秒再读」抢；不能推送；没有在线设备；每个用户要自己准备网盘。
- 自建同步服务的收益：原子 CAS（ETag/If-Match）、真正的租约、SSE/WebSocket 推送、在线状态、统一鉴权。代价：部署/运维/备份/证书。
- **只有你自己用 2–3 台设备时，WebDAV 足够**；面向非技术用户时，自建同步服务才值得。

### 1.3 Agent 云端数据源 = 先分清「公共内容」还是「用户数据」

这是三件事里唯一**现在就值得做薄 API** 的，因为「Agent 能获取云端数据」不一定等于「把用户数据传到云端」。

| 数据类型 | 例子 | 是否公开 | 需要的后端 |
|---|---|---|---|
| **公共内容** | 官方题库、课程目录、文档索引、资源清单、常见问题 | 公开 | 静态 JSON/JSONL + CDN；或一个只读 API |
| **平台数据** | 排行榜、学习统计、推荐、热门路径 | 聚合/匿名 | 需要 API + 数据库（可后置） |
| **外部实时数据** | 网页搜索、资讯、视频信息 | 第三方 | 服务端代理（隐藏 key、缓存、限流） |
| **用户自己的数据** | 计划、笔记、记忆、练习记录 | 私有 | 账号 + 存储 + 授权 + 审计（最重） |

**推荐先做前三类，不碰第四类。** 理由：

- 公共内容/搜索代理不需要账号，不存用户数据，隐私风险≈0；
- 能直接解决 Agent 现在的两个痛点：①模型没有「官方内容」可查；②每个用户都要自己配 DeepSeek key、自己承担搜索费用；
- 等同步跑通、确实需要「手机上问 Agent，它知道你桌面上的计划/笔记」时，再评估第四类。

---

## 2. 如果要上，第一个服务器应该是什么

**一个只读的「内容 + 代理」薄 API**，建议就叫 `trellis-cloud`：

```
GET  /v1/meta                     # 最新版本、公告、feature flags、维护窗口
GET  /v1/catalog?kind=course&topic=os
GET  /v1/exercises?subject=计算机网络&limit=20
POST /v1/search { "query": "..." } # 代理 DeepSeek Search / 抓取，注入服务端 key
GET  /v1/health
```

特点：

- **只读**，不接受用户数据上传；
- 无账号或只用匿名设备 token；按 IP/设备限流；
- 缓存优先：搜索结果 TTL 1–7 天，公共内容放 CDN/对象存储；
- 失败降级：服务不可用 → 客户端回退到「用户自带 key 直连」或「没有资料」；
- API 版本化 `/v1`，客户端按 `schema` 兼容；
- 审计只记录 query 的 hash/计数，不记录内容（避免隐私与存储成本）。

这样做的好处：**先有「云端数据源」这个能力，再决定要不要为它建账号体系和常驻服务。**

---

## 3. 分阶段架构

### S0：0 服务器（现在）

- 官网：静态站 + CDN。
- 同步：WebDAV（坚果云/Nextcloud/Alist）+ [multi-device-sync.md](multi-device-sync.md) 的 P0/P1。
- Agent：继续用端侧直连（DeepSeek Search/Fetch、B 站、内置题库）。
- 目标：把数据模型、冲突、端到端跑通；不引入运维。

### S1：薄 API（建议第一个服务端，约 1 周）

- 部署：Cloudflare Workers（海外/免运维）或阿里云函数计算 + OSS（国内合规）。
- 内容：
  - 官方资源目录/题库索引（静态 JSON/JSONL 放对象存储，Worker 只做路由/鉴权/缓存）；
  - 搜索代理（服务端 key + 缓存 + 限流）；
  - 版本/公告 `/v1/meta`。
- 客户端：
  - `ResearchGateway` 增加 `CloudResearchGateway`（配置 `cloud.json`，失败降级到现有直连）；
  - Agent 新增只读工具 `cloud_catalog` / `cloud_exercises` / `cloud_search`；
  - 设置页增加「云端数据源」开关、地址、token、隐私说明。
- 验收：断网/服务挂掉时 Agent 仍可用；搜索走代理时客户端不再需要暴露 key；同一个 query 第二次命中缓存。
- 成本：Workers 免费额度 10 万请求/天；阿里云函数计算每月 100 万次调用 + 40 万 CU 秒免费额度（以服务商为准，见 §7）。

### S2：可选自建同步服务（1–2 周，按需）

- 只在「WebDAV 体验不够」时做：设备配对码、CAS、租约、推送（SSE）、在线设备、配额。
- 实现方式：新增一个 `HttpSyncTransport`（协议与现有对象/head/blob 对齐），服务端可以很薄：
  - `GET/PUT/LIST` + `If-Match`；
  - `POST /v1/lease/acquire` / `renew` / `release`；
  - `GET /v1/events`（SSE，可选）。
- 数据仍是「每设备命名空间 + 内容寻址」；服务端只做中转和鉴权，不做内容理解。
- 验收：两端同时改同一文件 → 服务端 CAS 直接返回冲突；客户端不再 sleep 抢租约。

#### 自建 HTTP 同步服务到底需要准备什么

「HTTP 同步服务」必须有四样东西，缺一不可：

1. 一个**常驻进程**（或 Serverless 函数）在跑；
2. 一个**可达地址**（域名 / 公网 IP / 内网穿透）；
3. **TLS**（HTTPS；否则手机端笔记就是明文传输）；
4. **存储**（磁盘或 S3 兼容对象存储）+ 备份。

**不需要数据库**：我们的协议只要求「按路径读写对象 + 列目录 + 可选条件写」。服务端可以就是一个
带 PUT/LIST 的对象存储网关；只有租约、推送、在线设备才需要一点状态（内存/一个小表即可）。

四种落地形态：

| 形态 | 要自己准备机器吗 | 成本 | 适合 |
|---|---|---|---|
| 第三方 WebDAV（坚果云/Nextcloud） | 不用 | ¥0–30/月 | 先验证、自己用 |
| 家里 NAS / 旧电脑 + Docker + Caddy + Tailscale 或 Cloudflare Tunnel | 不用买新机器，但要有常开设备 | 电费 + 域名 | 已有 NAS/常开电脑，想自己掌控数据 |
| 云服务器 VPS + Docker + Caddy | **要** | €5–10/月 或 ¥50–100/月 | 想完全控制、以后面向他人 |
| Serverless（Cloudflare Workers + R2 / 阿里云 FC + OSS） | 不用（平台托管） | ¥0–30/月（免费额度内） | 不想运维，接受平台限制 |

最小规格：**1 vCPU / 512MB–2GB RAM / 10–20GB 存储**就够（同步的是笔记、JSON、快照，量很小）；
带宽取决于快照大小；国内用自定义域名要 ICP 备案。Android 访问家里 NAS 时，Tailscale 客户端最省事；
Cloudflare Tunnel 需要域名；frp/ngrok 的稳定性和安全要自己负责。

**上线前必须有的**：设备令牌（可撤销）、HTTPS、限流、每日备份、日志（不记正文）、升级/打补丁。

**代价**：服务器一旦自己运维，宕机、证书过期、磁盘满、备份丢失都是你的责任。单用户场景通常不值得
为了「同步」自建，除非明确需要「推送 / 在线状态 / 免配置 / 多用户」——这也正是 S2 放在「可选」的原因。

#### 手机当同步服务器：HTTP 还是 WebSocket

**结论：HTTP 为主，WebSocket 不作为第一版。**

原因：

- 同步是离散的 pull/push（读 head、下 blob、上传），天然是请求/响应；HTTP 的幂等、重试、断点、
  缓存、代理都成熟。
- 手机做主机最脆弱的是「常驻连接」：Doze、后台限制、息屏断 Wi-Fi、IP 变化都会让长连接断；
  WebSocket 要自己处理重连/心跳/会话恢复，而 HTTP 每次短请求失败重试即可。
- 我们的协议已经按「按路径读写对象」设计，映射成 GET/PUT/LIST 最直接；WebSocket 还要自定义帧协议。

手机做服务器先解决这三件事（比协议选择更重要）：

1. **后台存活**：Android 前台服务 + 常驻通知（声明 FGS type，Android 14+ 必需），或「打开 App 后开启
   同步模式 N 分钟」；Doze/省电会杀后台，必要时引导用户开电池优化白名单。
2. **地址发现**：手机 IP 会变。推荐 Tailscale（100.x 固定地址 + WireGuard 加密），或在同一 Wi-Fi 用
   mDNS/NSD/手动 IP；移动网络下的公网端口映射基本不可行。
3. **安全**：局域网明文 HTTP 不可取；用 Tailscale 隧道，或 HTTPS + 自签证书 + 证书 pinning；
   再加设备 token（可撤销）。

端点（无数据库，直接映射现有 `SyncTransport`）：

- `GET /v1/object?path=...`
- `PUT /v1/object?path=...`（body 为原始字节）
- `GET /v1/list?prefix=...`（JSON）
- 可选 `HEAD` / `If-Match`（租约/CAS）

实现：Android 用 Ktor server(CIO) 或 NanoHTTPD；桌面/手机客户端新增 `HttpSyncTransport`。
第一版做「手机打开 App → 开启同步服务 → 桌面拉取」，不追求息屏常驻；以后要即时再加 SSE/WebSocket，
只做「有变更」通知，数据仍走 HTTP。

#### 临时隧道试跑（域名未批时）

先确认 8000 跑在哪台机器——tunnel 只能转发到**它所在机器能访问的地址**：

- **服务在 VPS / 与网关同机**：在 VPS 上起 tunnel，指向 `127.0.0.1:8000`（或网关的监听端口），手机用隧道给的 HTTPS 地址。
- **服务在手机**：别指望在手机上稳定跑 cloudflared/ngrok；优先 **Tailscale**（两端装，直接 `http://100.x:8000`），或者手机跑 `frpc` 把 8000 反向映射到 VPS 的 `127.0.0.1:8000`，再由网关/tunnel 暴露。

VPS 上最快的两种临时方案：

```bash
# Cloudflare Quick Tunnel：零配置，输出 https://xxx.trycloudflare.com
cloudflared tunnel --url http://127.0.0.1:8000

# 或 ngrok
ngrok http 8000

# 或 Tailscale Funnel（需要 tailnet）
tailscale funnel 8000
```

验证链路（先本机、再隧道）：

```bash
curl -fsS http://127.0.0.1:8000/health
curl -fsS https://xxx.trycloudflare.com/health
```

注意：

- Quick Tunnel 的 URL **每次重启都会变**，只能临时用；客户端 `baseUrl` 必须可配置，不能硬编码。
- 隧道一旦建立就是公网可达：**必须加 token**（`Authorization: Bearer ...`），测试期用临时 `TRELLIS_HOME`，不要同步真实笔记。
- 网关在 8000 前面时，tunnel 要指向**网关端口**，不要绕过去。
- 域名下来后换成命名 tunnel + 自有域名 + 访问策略（Cloudflare Access / 反向代理 basic auth），临时 URL 停掉。

### S3：常驻服务 / 账号 / 云端 Agent（3 周+，按需求）

- 触发：要做网页端、云端主动 Agent、多用户、付费。
- 组成：
  - API：Ktor（Kotlin，复用协议模型）或 Go（单二进制、内存小）；
  - DB：SQLite（单机够用）→ Postgres（多用户/托管）；
  - 对象存储：S3 兼容（R2 / OSS / MinIO）；
  - 队列（可选）：定时任务/后台 Agent 用；
  - 反向代理/TLS：Caddy（自动证书）；
  - 监控：Uptime Kuma / 日志；
  - 备份：每日 DB + 对象存储版本。
- 云端 Agent 的数据授权模型见 §4.3。

---

## 4. Agent 云端数据源设计

### 4.1 端侧 Agent + 云端只读内容（推荐先做）

```
手机/桌面 App
  └─ Agent 工具循环
       ├─ 本地工具（计划/笔记/题库…）
       ├─ cloud_search   → 薄 API（代理搜索/抓取，服务端 key + 缓存）
       ├─ cloud_catalog  → 薄 API（官方课程/资源目录）
       └─ cloud_exercises→ 薄 API（官方题库/题目索引）
```

- Agent 仍然在设备上跑，模型调用仍用用户自己的 key（或以后统一代理）；
- 云端只提供「资料」，不接触用户数据；
- 工具失败 → 降级到本地/直连，不阻塞对话；
- 这样「Agent 能获取云端数据」这件事**不需要账号体系**就能成立。

### 4.2 工具契约（建议）

| 工具 | 输入 | 输出 | 说明 |
|---|---|---|---|
| `cloud_search` | query, count | 标题/URL/摘要/日期 | 服务端代理 DeepSeek Search，缓存 + 限流 |
| `cloud_catalog` | kind, topic, level | 资源列表（标题/URL/时长/标签） | 官方维护的课程/文档索引 |
| `cloud_exercises` | subject, course, limit | 题目（不含答案，答案随判分接口） | 与本地题库同构，可直接导入 |
| `cloud_meta` | — | 版本/公告/维护状态 | 客户端更新检查也可用 |

- 这些是**只读工具**，可以不走审批；写操作仍然只走本地内核。
- 建议按插件架构里的「remote-execution」模式实现：本地 manifest 声明 endpoint/capability，内核负责鉴权、配额、审计；云端不可用不影响本地内核 ACTIVE（见 `docs/research/plugin-hotplug/03-oss-case-studies.md` 的 remote-execution 一节）。

### 4.3 如果以后要让 Agent 读「用户自己的云数据」

这时才开始需要：账号、设备配对、云端存储、授权 scope、审计、撤销。

- **隐私矛盾**：服务端能读明文，才能让云端 Agent 理解内容；端到端加密则服务端读不了。
- 三种路线：
  1. **只上传投影/摘要**（推荐）：计划标题、进度、概念名、记忆条目、错题统计；不上传笔记全文。云端 Agent 拿到的是「结构化上下文」，不是原文。
  2. **设备作代理**：云端 Agent 要查数据时，请求转发给在线的设备，由设备本地查询后返回；服务端不落盘。设备离线则不可用。
  3. **全量上云 + 明文**：体验最好，隐私最差；必须明示同意 + 可关闭 + 可导出/删除。
- 授权：`sync:rw`、`agent:read`、`content:read` 分开；每个设备一个 token，可单独撤销。
- 审计：记录「哪个设备/哪个 Agent 轮次读了什么类别」，不记录正文。

**建议顺序**：先 1（摘要），必要时 2（设备代理），最后才考虑 3。

---

## 5. 技术选型

| 方案 | 适合 | 优点 | 缺点 |
|---|---|---|---|
| **Cloudflare Workers + R2/KV/D1** | 海外用户、先验证、免运维 | 免费额度大（10 万请求/天）、全球 CDN、部署简单 | 国内访问不稳定；需域名；D1 限制 |
| **阿里云函数计算 + OSS + TableStore** | 国内用户、要备案 | 国内速度/合规、免费额度（每月 100 万次调用 + 40 万 CU 秒） | 冷启动、平台绑定、备案时间 |
| **VPS + Docker + Caddy + Ktor/Go** | 常驻服务、推送、后台 Agent | 完全可控、便宜（2C4G ≈ €5.5/月）、WebSocket 方便 | 要自己运维/备份/监控/安全 |
| **托管 BaaS（Supabase/Firebase）** | 账号/DB 快速起步 | 省事、自带 Auth | 依赖、国内访问、成本随用量 |

**推荐**：

- 先做 S1 薄 API：国内为主 → 阿里云 FC + OSS（备案）；先验证/海外 → Cloudflare Workers。
- 需要常驻/推送再上 VPS：Hetzner（€5.49/月起，2C4G）或国内轻量云（约 ¥50–100/月）；Ktor 可以复用 Kotlin 生态与协议模型，Go 更省内存。
- **不要**为了官网单独买服务器；静态托管足够。

---

## 6. 安全、隐私与合规

- 官网/薄 API：全站 HTTPS；API 限流；不存用户内容；日志不落 query 正文。
- 搜索代理：服务端 key 只存在服务端环境变量；按设备/IP 配额；防滥用。
- 同步服务（如果上）：设备 token、可撤销；对象存储桶私有；快照/ blob 加密可选。
- 用户数据上云（如果做）：明示同意、可导出、可删除；敏感字段（笔记全文）默认不上传；考虑 E2E（代价是云端 Agent 读不了）。
- 国内合规：ICP 备案、隐私政策、App 下载页/权限说明；Android 分发渠道政策。
- 成本控制：CDN 缓存、搜索缓存、对象生命周期、免费额度告警。

---

## 7. 成本估算（量级）

| 项目 | 方案 | 估算 |
|---|---|---|
| 域名 | .com/.cn | ¥50–100/年 |
| 静态官网 | Cloudflare Pages / Vercel / OSS+CDN | ¥0–20/月 |
| 薄 API | Workers / 阿里云 FC | ¥0–30/月（免费额度内≈0） |
| 常驻 VPS | Hetzner CX23 2C4G 或国内轻量 | €5.5/月 起 / ¥50–100/月 |
| 对象存储 | R2 / OSS / MinIO | ¥1–20/月（取决于快照/历史量） |
| 数据库 | SQLite（免费）/ 托管 PG | ¥0 / ¥30–100/月 |
| 运维时间 | 备份、监控、升级 | 常驻服务约 1–2 小时/月 |

> 价格以服务商当前页面为准：
> Cloudflare Workers 免费额度 10 万请求/天（[limits](https://developers.cloudflare.com/workers/platform/limits/)）；
> 阿里云函数计算免费额度 100 万次调用 + 40 万 CU 秒/月（[文档](https://help.aliyun.com/en/document_detail/421403.html)）；
> Hetzner 2C4G ≈ €5.49/月（[价格汇总](https://1vps.com/pricing/)）。

---

## 8. 与现有代码的接入点

| 位置 | 改动 |
|---|---|
| `WebResearch.kt` / `ResearchGateway` | 增加 `CloudResearchGateway`（走薄 API），保留现有直连作为降级 |
| `LlmConfigLoader` / `<home>/llm.json` | 新增 `<home>/cloud.json`（云端地址、匿名 token、开关），不混进 LLM 配置 |
| `AppRuntime` 工具集 | 新增 `cloud_search` / `cloud_catalog` / `cloud_exercises` / `cloud_meta`（只读，不审批） |
| 插件内核 | 云能力按 remote-execution 插件建模：manifest 声明 endpoint/capability，内核鉴权/配额/审计 |
| 同步 | 若上自建同步服务，只新增 `HttpSyncTransport`；对象/head/blob 协议不变 |
| 设置页 | 「云端数据源」：开关、地址、连接测试、隐私说明、降级提示 |
| 官网 | 独立目录/仓库，构建产物部署到静态托管；App 内只放「检查更新」静态 JSON 链接 |

---

## 9. 路线图与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| S0 | 静态官网 + WebDAV 同步（沿用 sync 文档 P0/P1） | 官网可访问/可下载；同步两端收敛 |
| S1 | 薄 API：meta/catalog/exercises/search | Agent 能查官方内容；服务挂掉自动降级；key 不下发客户端 |
| S2 | 自建同步服务（可选） | 并发写 CAS 冲突；推送/在线状态；WebDAV 用户可无感迁移 |
| S3 | 账号 + 用户数据摘要 + 云端 Agent/后台任务 + 付费 | 授权/撤销/审计齐全；隐私政策与导出删除可用 |

**建议现在做的事**：把 S0 做完（官网静态页 + 同步 P0/P1），同时**只设计不实现** S1 的 API 契约；等同步和 Agent 工具稳定后再上薄 API。

---

## 10. 待拍板

1. 官网托管：国内备案（阿里云/腾讯云）还是海外（Cloudflare/Vercel）？
2. Agent 云端数据先做「公共内容 + 搜索代理」，还是直接做「用户数据摘要」？（推荐前者）
3. 云端 Agent 是否需要（还是全部端侧）？
4. 是否接受服务端能读用户数据明文？E2E 与「云端 Agent 可理解内容」二选一怎么选？
5. 长期同步：继续 WebDAV，还是做自建同步服务（S2）？
6. 账号/付费什么时候做？是否接受先「设备配对码」不注册？
