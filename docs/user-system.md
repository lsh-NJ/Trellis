# 用户系统（账号 / 会话 / 本地缓存）

> 状态：**v1 已落地**（2026-11-19）
> 关联：[multi-device-sync.md](multi-device-sync.md)（同步协议与部署）、[cloud-roadmap.md](cloud-roadmap.md)（网关/隧道）
> 定位：在数据同步之前把「你是谁、数据归谁」定下来。单用户本地使用不强制登录；HTTP 多端同步必须登录。

---

## 0. 结论

- **服务端是用户库的权威**：`<server root>/.auth/users.json`（用户 + 会话），密码只存 PBKDF2 哈希。
- **客户端本地存一份账号缓存**：`<home>/account.json`（`UserPublic` + 会话 token + 服务器地址），**不存密码、不存 passwordHash**。
- **HTTP 同步按账号隔离**：服务端把对象写到 `<server root>/users/<userId>/`；客户端用登录后的 Bearer token 访问。
- **两种 token 分开走**：
  - 网关 trial token：`X-Token`（或网关支持的 query/Bearer），挡外网；
  - 用户会话 token：`X-Trellis-Auth: Bearer <token>`，决定数据归属。
  - 不用 `Authorization` 给用户 token：网关自己也支持 `Authorization: Bearer` 做 trial 鉴权，会冲突。
- 单用户/离线使用：不登录也能用本地功能；同步配置为 HTTP 但没登录时，同步显示「未登录」并停止。

---

## 1. 数据模型

| 类型 | 存在哪 | 内容 |
|---|---|---|
| `UserRecord` | 服务端 `.auth/users.json` | id、username、displayName、role（ADMIN/USER）、status（ACTIVE/DISABLED）、createdAt/updatedAt、**passwordHash** |
| `UserPublic` | 服务端响应 / 客户端 `account.json` | 同上，去掉 passwordHash |
| `SessionRecord` | 服务端 `.auth/users.json` | token 的 SHA-256、userId、createdAt、expiresAt、deviceName |
| `LocalAccount` | 客户端 `<home>/account.json` | serverUrl、gatewayToken、userId、username、displayName、role、**会话 token**、expiresAt |
| `LoginSession` | 登录响应 | token（明文只出现一次）、expiresAt、user |

规则：

- 用户名 3–32 位 `[A-Za-z0-9._-]`，**大小写不敏感唯一**；
- 密码 8–128 位；PBKDF2-HMAC-SHA256，210k 次迭代，16 字节 salt，格式自带参数（以后可升级）；
- 会话默认 30 天；logout 删除会话；停用账号/重置密码会踢掉该用户所有会话；
- 第一个注册用户自动成为 ADMIN；之后注册需要邀请码（`--invite-code`），否则 403。

---

## 2. HTTP 接口

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/health` | 无 | 探活；网关/隧道可用 |
| POST | `/v1/auth/register` | 网关 token（若启用） | `{username,password,displayName,inviteCode,deviceName}` → `{token,expiresAt,user}` |
| POST | `/v1/auth/login` | 网关 token（若启用） | `{username,password,deviceName}` → 同上 |
| GET | `/v1/auth/me` | 用户 token | `{user}`；401 表示 token 失效 |
| POST | `/v1/auth/logout` | 用户 token | 204 |
| GET | `/v1/users` | 用户 token（ADMIN） | `{users:[...]}` |
| GET | `/v1/object?path=...` | 用户 token | 对象字节；不存在 404 |
| PUT | `/v1/object?path=...` | 用户 token | 原始字节；204 |
| GET | `/v1/list?prefix=...` | 用户 token | `[{"path":"...","size":123}]` |

错误统一 `{"error":"code","message":"..."}`，状态码：400 参数/JSON 错、401 未登录/密码错、403 邀请码/停用、404 找不到、409 用户名已占用、413 太大。

---

## 3. 客户端行为

1. 启动：读 `<home>/account.json`，有缓存就显示当前账号（离线也能显示）；后台调 `/v1/auth/me` 校验，401 自动清缓存。
2. 登录/注册：设置页「账号」→ 填服务器地址、网关 token（可空）、用户名/密码；注册模式下还要显示名/邀请码。
3. 登录成功：写 `account.json`，重启同步任务；HTTP 同步会带用户 token。
4. 退出：先尽力通知服务端 logout，再清本地缓存并停同步。
5. 过期：同步前检查 `expiresAt`；过期/401 提示重新登录，不拿失效 token 硬刷。

**本地一份、服务器一份**的含义：服务器用户库是权威；本地是「当前账号的缓存 + 会话」，不是第二份用户注册表。多设备各自缓存自己的会话，登录同一账号即可。

---

## 4. 与同步的关系

```
客户端 → 网关（X-Token）→ sync-server
                              ├─ X-Trellis-Auth → 找出 user
                              ├─ 对象写到 users/<userId>/...
                              └─ 用户库 .auth/users.json
```

- 老的单租户 `--user-header` 模式不再需要：账号 token 直接决定用户目录，网关无法伪造。
- 同一账号多设备：数据互通（同一个 `users/<id>/`）；不同账号：完全隔离。
- 网关的 trial token 仍然可以保留，作为「谁能访问这个部署」的外层闸；用户 token 是内层身份。
- 只有登录成功后 HTTP 同步才启动；文件夹模式不需要账号（本机使用）。

---

## 5. 安全清单

- 网关负责 TLS（Cloudflare 隧道/域名）；服务端只绑 `127.0.0.1`，不要直接暴露 8000。
- 密码哈希参数升级：`needsRehash` 已留；以后登录成功可自动重哈希。
- token 只存 SHA-256；明文只在登录响应出现一次；`account.json` 桌面尽量 600 / Android 应用私有目录。
- 登录失败目前没有锁定/限流；对外试用时在网关加「按 IP/token 限流 + 登录失败退避」。
- 注册默认关闭（首个用户后必须邀请码）；不要把 `--invite-code` 放进公开仓库。
- 不在日志里打 password/token/body；服务端只记 method/path。
- 账号删除/密码找回/双因素暂未做；试用阶段由管理员通过服务端文件/后续管理接口处理。

---

## 6. 试用运营建议

1. 部署时给 `sync-server` 一个随机 `--invite-code`；
2. 第一个注册的用户就是管理员；
3. 每个试用者一个账号（不要共用），登录后数据天然隔离；
4. 网关按 IP/账号限流（建议 120 请求/分钟），对象大小 ≤ 5–10MB；
5. 有人退出试用：停用账号（`status=DISABLED`）会踢掉会话；数据保留，后续再清理。

后续可加：管理员页（用户列表/停用/重置密码）、账号删除、设备列表与踢下线、登录失败锁定、密码找回、2FA。

---

## 7. 代码位置

| 位置 | 内容 |
|---|---|
| `:user` | `UserModel.kt`、`PasswordHasher.kt`、`TokenCodec.kt`、`UserStore.kt`、`AccountStore.kt`、`HttpUserClient.kt` |
| `:sync-server` | `Main.kt`：`/v1/auth/*`、`/v1/users`、对象接口按用户分目录 |
| `:sync` | `HttpSyncTransport` 支持 `X-Trellis-Auth` 用户 token |
| `:app:ui` | `AppRuntime` 的账号状态/login/register/logout；设置页「账号」与「多端同步」 |
| 文档 | 本文件；部署与隧道见 [multi-device-sync.md](multi-device-sync.md) §12.5；服务端对接契约见 [server-integration-contract.md](server-integration-contract.md) |

---

## 8. 待定

1. **手机当服务器**：当前 `:sync-server` 用 JDK `com.sun.net.httpserver`，**跑不了 Android**；账号核心（`:user`）是共享的，但 Android 要另写 HTTP 监听层（Ktor/NanoHTTPD + 前台服务）。
2. 注册策略：只邀请码 / 管理员创建 / 邮箱验证。
3. 账号与网关 trial token 是否长期共存，还是域名下来后去掉网关 token。
4. 是否需要「一个账号多设备可见设备列表 + 单独踢下线」。
