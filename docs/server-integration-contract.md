# 同步服务端对接契约（v1）

> 状态：**v1 冻结**（2026-11-19）
> 适用：任何实现 Trellis 同步服务端的程序——Android 手机、VPS、NAS、云函数都行。
> 客户端版本：当前 `:sync` / `:user`（schema = 1）。
> 参考实现：`:sync-server`（Kotlin/JVM，带账号）、`tools/sync-server/sync_server.py`（仅对象接口）。
> 关联：[user-system.md](user-system.md)、[multi-device-sync.md](multi-device-sync.md)

---

## 0. 一句话

服务端 = **账号系统 + 按用户隔离的对象存储**。客户端只要求 HTTP/1.1 的 4 个对象端点 + 4 个账号端点；**不需要 WebSocket、不需要数据库、不需要服务端理解同步算法**。

如果手机端自己实现服务器，必须满足本文的请求/响应、头部、状态码和目录布局；客户端不会为某一台服务器写特例。

---

## 1. 范围

### 必须实现

- `GET /health`：探活（不需要任何 token）。
- 账号：`POST /v1/auth/register`、`POST /v1/auth/login`、`GET /v1/auth/me`、`POST /v1/auth/logout`；可选 `GET /v1/users`（管理员）。
- 对象：`GET /v1/object?path=...`、`PUT /v1/object?path=...`、`GET /v1/list?prefix=...`。
- **按用户隔离存储**：对象路径相对于 `users/<userId>/`。
- 路径安全（拒绝 `..`/绝对路径）、原子写、UTF-8 JSON。
- 账号口令哈希与会话 token 的安全存储（见 §4）。

### 不需要 / 明确不做

- WebSocket、SSE、长连接、推送。
- 数据库（文件系统即可；用户文件 + 对象目录）。
- 服务端做 CRDT/合并/冲突解决（客户端做）。
- 服务端解析笔记/计划内容。
- TLS 终结（由 Cloudflare/网关/反向代理负责；手机建议 Tailscale 隧道）。
- 多租户组织/分享/计费。
- 活动原始数据、聊天记录、`llm.json`、插件目录的同步。

---

## 2. 网络与网关

推荐两种部署：

```
A. 公网：Client → HTTPS(Cloudflare/域名) → 网关（校验 X-Token）→ sync-server :8000
B. 手机：Client → Tailscale 私有网 → 手机 App 内的 HTTP 服务 :8000
```

硬性要求：

- 服务端**只监听 127.0.0.1**（或 Tailscale 接口），不要直接把 8000 暴露到公网。
- 公网入口必须有 HTTPS（TLS 由网关/隧道终止）。
- 网关的 trial token 与用户的会话 token 是两个概念（见 §4）。
- 网关不支持 WebSocket 不影响本协议。

---

## 3. 协议基础

- HTTP/1.1；请求/响应均为 UTF-8。
- JSON 响应 `Content-Type: application/json; charset=utf-8`；对象响应 `application/octet-stream`。
- 查询参数使用 URL 编码（`URLEncoder`，空格为 `+` 或 `%20` 均可，服务端用 URLDecoder 解）。
- 头部名大小写不敏感。
- 错误统一：

```json
{"error":"machine_code","message":"给人看的一句话"}
```

| 状态码 | 含义 |
|---|---|
| 200 | 成功（GET/JSON） |
| 204 | 成功但无 body（PUT/logout/OPTIONS） |
| 400 | 参数/JSON/路径非法 |
| 401 | 缺 token / token 无效或过期 / 用户名密码错 |
| 403 | 邀请码不对 / 账号停用 / 非管理员 |
| 404 | 对象或路径不存在 |
| 409 | 用户名已占用 |
| 413 | 请求体超过限制 |
| 500 | 服务端内部错误（不要泄露堆栈） |

推荐上限：单对象 **≤ 64MB**（客户端实际只传笔记/JSON/快照，通常 < 5MB）；`path` ≤ 1024 字节。

---

## 4. 认证与账号

### 4.1 两个 token

| 用途 | 请求头 | 谁校验 | 说明 |
|---|---|---|---|
| 网关 trial token（可选） | `X-Token: <edge-token>` | 网关 | 挡外网；网关校验后可在转发前剥掉 |
| 用户会话 token | `X-Trellis-Auth: Bearer <user-token>` | 本服务端 | 决定「你是谁、数据归谁」 |

**不要用 `Authorization` 给用户 token**：网关自己也支持 `Authorization: Bearer` 做 trial 鉴权，两个语义会冲突。服务端可以兼容读取 `Authorization`（方便 curl），但客户端只发 `X-Trellis-Auth`。

`/health` 两个头都不要。

### 4.2 账号规则

- 用户名：3–32 位 `[A-Za-z0-9._-]`，**大小写不敏感唯一**；存储时保留用户输入的大小写。
- 密码：8–128 位。
- 密码哈希：**PBKDF2-HMAC-SHA256，210,000 次迭代，16 字节随机 salt，32 字节输出**；存储格式：
  `pbkdf2-sha256$210000$<saltBase64>$<hashBase64>`（Base64 不带 padding）。
- 会话 token：32 字节安全随机 → URL-safe Base64（不带 padding）；服务端**只存 SHA-256 十六进制**。
- 会话有效期：默认 30 天；过期即 401。logout 删除会话；停用账号/重置密码应删除该用户全部会话。
- 第一个注册用户自动 `ADMIN`；之后注册必须带邀请码（服务端配置）。
- 角色：`ADMIN` / `USER`；状态：`ACTIVE` / `DISABLED`。

### 4.3 账号端点

#### POST /v1/auth/register

请求：

```json
{
  "username": "alice",
  "password": "password123",
  "displayName": "Alice",
  "inviteCode": "",
  "deviceName": "desktop"
}
```

- 第一个用户：`inviteCode` 可为空，注册成功即 ADMIN。
- 其它用户：邀请码必须匹配服务端配置，否则 403。
- 成功 200：

```json
{
  "token": "<user-token>",
  "expiresAt": 1797683467625,
  "user": {
    "id": "usr_...",
    "username": "alice",
    "displayName": "Alice",
    "role": "ADMIN",
    "status": "ACTIVE",
    "createdAt": 1795091467270,
    "updatedAt": 1795091467270
  }
}
```

其它：400 参数不合法；409 用户名已占用；403 邀请码错误。

#### POST /v1/auth/login

请求 `{ "username": "...", "password": "...", "deviceName": "..." }`，响应同 register。密码错 401，停用 403。

#### GET /v1/auth/me

请求头：`X-Trellis-Auth: Bearer <user-token>`。成功 200：

```json
{"user": { ...UserPublic... }}
```

token 失效 401。

#### POST /v1/auth/logout

同上鉴权；成功 204；token 无效 401。

#### GET /v1/users（管理员）

同上鉴权；非 ADMIN 403。成功：

```json
{"users": [ { ...UserPublic... } ]}
```

**任何响应都不能包含 passwordHash。**

### 4.4 账号数据文件

服务端文件（参考实现）：

```
<root>/.auth/users.json          # {"schema":1,"users":[...],"sessions":[...]}
<root>/.auth/users.json.bak      # 可选
```

- 用户记录里只存 `passwordHash`；会话里只存 `tokenHash`。
- 写盘用 tmp + rename；读坏文件应保留 `.bad-<ts>` 并报错/从空开始（不要静默丢用户）。
- 文件权限尽量 600。

---

## 5. 对象存储 API

### 5.1 数据布局

```
<root>/
  .auth/users.json
  users/<userId>/
    heads/<domain>/<deviceId>.json
    blobs/<domain>/<sha256>
    logs/<domain>/<deviceId>/<seq>.jsonl   # 预留，v1 客户端还不用
```

- `<userId>` 来自用户 token；服务端**不得**接受请求里的 userId 参数（防止越权）。
- `path` 是相对于 **该用户根目录** 的 POSIX 路径，例如 `heads/notes/dev_x.json`、`blobs/notes/<sha>`。
- 路径中不允许 `..`、绝对路径、空段；服务端必须做 canonical path 校验。
- 用户根目录必须存在（首次 PUT 时创建）。

### 5.2 GET /v1/object?path=...

- 鉴权：用户 token。
- 不存在：404（body 可空或 JSON 错误）。
- 存在：200 + 原始字节（`application/octet-stream`），**服务端不能改写内容**。

### 5.3 PUT /v1/object?path=...

- 鉴权：用户 token。
- body：原始字节；长度 ≤ 限制。
- 成功：204；不存在则创建，存在则覆盖（幂等）。
- 必须原子写（tmp + rename），避免客户端并发读到半截文件。

### 5.4 GET /v1/list?prefix=...

- 鉴权：用户 token。
- `prefix` 为相对路径前缀（通常以 `/` 结尾，如 `heads/notes/`）；空 = 用户根。
- 200 + JSON 数组（顺序不要求，客户端会排序）：

```json
[
  {"path": "heads/notes/dev_a.json", "size": 123},
  {"path": "heads/notes/dev_b.json", "size": 0}
]
```

- 只列文件，不列目录；`path` 是相对用户根的 POSIX 路径；`size` 为字节数。
- 前缀无对象返回 `[]`（不是 404）。
- 禁止返回 `.tmp-` 临时文件。

### 5.5 不需要的端点

v1 客户端**不会**调用 DELETE、MOVE、COPY、PROPFIND、WebSocket。删除通过 `heads/<deviceId>.json` 里的 `deleted: true` 墓碑表达，不是删文件。

---

## 6. 同步协议（服务端只需“哑存储”）

客户端每轮同步对一个域（`notes` / `plan-docs`）做：

1. `GET /v1/list?prefix=heads/<domain>/`；
2. 逐个 `GET /v1/object?path=heads/<domain>/<deviceId>.json`，解析 head 并合并（取 HLC 最大）；
3. 需要时 `GET /v1/object?path=blobs/<domain>/<sha256>` 下载内容，校验 SHA-256；
4. 本地变更：先 `PUT blobs/<domain>/<sha256>`，再 `PUT heads/<domain>/<selfDeviceId>.json`；
5. 不会修改别的设备的 head，也不会删 blob。

### 6.1 head 文件格式（客户端写，服务端原样存）

```json
{
  "schema": 1,
  "deviceId": "dev_01J...",
  "objects": {
    "notes/<noteId>.md": {
      "hlc": "1763538000123-0-dev_01J...",
      "sha": "<64 位小写 hex>",
      "size": 1234,
      "deleted": false
    }
  }
}
```

- `hlc`：`<毫秒>-<计数器>-<deviceId>`；计数器 0–65535；deviceId 不含 `-`。
- `sha`：sha256 小写十六进制；`deleted:true` 时可为空。
- key：对象在该域内的相对路径，允许 `/`；不允许 `..`。
- 服务端**不需要解析**这些字段，但必须保证字节不被改写。

### 6.2 blob 文件

- 路径 `blobs/<domain>/<sha256>`；内容就是原始文件字节。
- 同一 sha 内容不可变；重复 PUT 是幂等的。
- 客户端会校验 sha；服务端若缓存/压缩/转发损坏，同步会失败。

### 6.3 一致性要求

- PUT 成功返回后，立刻 GET 必须能读到同一内容（read-after-write）。
- 同一用户的并发 PUT 到同一 path 必须原子（最后写入者赢即可）。
- 多设备可能同时 PUT 不同 path；服务端不要做锁/排队（客户端协议自己处理顺序）。
- 服务端不需要跨用户锁。

---

## 7. 兼容性与版本

- 当前协议版本：`schema = 1`（head 文件和 `.auth/users.json` 都有）。
- JSON 解析必须忽略未知字段（向前兼容）。
- 不认识的路径/方法返回 404/405，不要返回 500。
- 破坏性变更走 `/v2` 路径并更新本文件；v1 客户端不会自动升级。
- 客户端配置里的 `baseUrl` 可以带路径前缀（如 `https://host/sync`），服务端要按前缀后面路由；不确定时让 baseUrl 指向根。

---

## 8. 手机端（Android）额外要求

如果服务端跑在手机 App 内：

1. **不能使用 JDK `com.sun.net.httpserver`**（Android 没有）。用 NanoHTTPD / Ktor CIO / 自写 `ServerSocket` 适配器；路由/账号/对象逻辑应与参考实现一致。
2. **前台服务**：`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` 权限；`<service android:foregroundServiceType="dataSync">`；常驻通知（含停止按钮）；只能从 Activity 启动。
3. **存储**：服务端数据放 `filesDir/trellis/server`，不要和 App 自己的 `filesDir/trellis` 混。
4. **网络**：绑定 `0.0.0.0` 或 Tailscale 接口；推荐 Tailscale 直连，不建议在手机上跑 Cloudflare Tunnel。
5. **电量/系统限制**：Doze/省电会杀服务；Android 15+ 对 `dataSync` 前台服务有时长限制。客户端必须能处理「连接失败 → 稍后重试」，不要假设服务永远在线。
6. **账号**：手机是服务端时，用户库也在手机上；首个注册用户 ADMIN。手机自己作为客户端时访问 `http://127.0.0.1:8000`。
7. **UI**：至少提供启动/停止、监听端口、当前地址（Tailscale IP/局域网 IP）、账号登录状态、停止通知。
8. **反向隧道**：如果坚持走现有 VPS 网关，手机必须主动把 8000 反向映射到 VPS 的 `127.0.0.1:8000`（frpc 等）；否则网关无法访问手机 loopback。

---

## 9. 验收清单（对接方按这个自测）

### 9.1 基础

```bash
BASE=http://127.0.0.1:8000
curl -fsS $BASE/health                                   # 200 ok

REG=$(curl -s -H 'X-Token: <edge>' -H 'Content-Type: application/json' \
  -X POST --data '{"username":"alice","password":"password123"}' \
  $BASE/v1/auth/register)
TOKEN=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["token"])' "$REG")

curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Token: <edge>' \
  -H "X-Trellis-Auth: Bearer $TOKEN" $BASE/v1/auth/me     # 200
curl -s -o /dev/null -w '%{http_code}\n' $BASE/v1/list?prefix=   # 401（没有用户 token）

curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Token: <edge>' \
  -H "X-Trellis-Auth: Bearer $TOKEN" -X PUT --data-binary 'hello' \
  "$BASE/v1/object?path=notes/a.md"                       # 204
curl -s -H 'X-Token: <edge>' -H "X-Trellis-Auth: Bearer $TOKEN" \
  "$BASE/v1/object?path=notes/a.md"                       # hello
curl -s -H 'X-Token: <edge>' -H "X-Trellis-Auth: Bearer $TOKEN" \
  "$BASE/v1/list?prefix=notes/"                           # [{"path":"notes/a.md","size":5}]
```

### 9.2 安全/边界

- 无 `X-Trellis-Auth` 访问对象 → 401。
- 用户 A 的 token 取不到用户 B 的对象（用两个账号各 PUT 同 path，读回各自内容）。
- `path=../x`、`/etc/passwd` → 400，且不能落盘到根目录外。
- 重复 PUT 同 path → 204 且内容为最后一次。
- 超过大小限制 → 413。
- 注册第二个用户不带邀请码 → 403；带对 → 200。
- 停用账号后旧 token → 401。

### 9.3 两设备同步

1. 设备 A 登录，写笔记，同步；
2. 设备 B 登录同一账号，同步，看到笔记；
3. B 删除，同步；A 同步后删除传播；
4. A/B 同时改同一篇 → 两边各保留一份 + `sync/conflicts/` 有副本（客户端行为，服务端只需保证对象存取正确）。

### 9.4 手机端附加

- 锁屏 10 分钟后前台服务仍在（或按系统限制正确停止并提示）；
- 切换 Wi-Fi/蜂窝后 Tailscale 地址仍可达；
- 停止服务后对象接口拒绝连接；
- App 重启后服务端数据仍在。

---

## 10. 参考实现与运行

| 实现 | 位置 | 说明 |
|---|---|---|
| Kotlin 完整版 | `:sync-server`（`sync-server/src/main/kotlin/dev/trellis/sync/server/Main.kt`） | 账号 + 对象；JDK `com.sun.net.httpserver`；测试见 `sync-server/src/test` |
| 账号/密码/会话核心 | `:user`（`user/src/commonMain/kotlin/dev/trellis/user/`） | 可被 Android/JVM 复用 |
| 对象协议客户端 | `:sync` 的 `HttpSyncTransport` | 用于对比头部/状态码/路径 |
| Python 仅对象版 | `tools/sync-server/sync_server.py` | 没有账号；只适合单用户临时测试 |

启动 Kotlin 服务端：

```bash
./gradlew :sync-server:installDist
sync-server/build/install/sync-server/bin/sync-server \
  --host 127.0.0.1 --port 8000 --root /path/to/sync-data \
  --invite-code '<随机邀请码>'
```

---

## 11. 对接方需要先确认的事

1. 服务端跑在哪：VPS / NAS / Android 手机 / 云函数？
2. 是否保留现有网关（`X-Token`）和 Cloudflare 隧道？手机当服务器时如何被桌面访问（Tailscale 还是反向隧道）？
3. 邀请码策略：固定邀请码 / 每人一个 / 管理员创建？
4. 单对象和单用户存储上限。
5. 是否需要 `GET /v1/users` 的管理 PC 端，还是先用 curl/管理脚本。
6. TLS 由谁终结、域名/证书怎么配。
