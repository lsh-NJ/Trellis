package dev.trellis.sync

import java.io.File

/**
 * 面向宿主的同步入口：读 [SyncConfig]（传输方式 + 地址 + 启用的域），拼好
 * transport/时钟/域，跑一次 [sync]。
 *
 * 传输方式：
 * - folder：本地/挂载目录；
 * - http：自建 HTTP 服务（GET/PUT/LIST），可以放在 token 网关 + 隧道后面。
 *
 * 域：知识库笔记与计划文档（文件域）。追加日志、结构化 JSON、plan.db 快照按
 * docs/multi-device-sync.md 的 P1/P2 继续接。
 */
class TrellisSyncService(
    private val home: File,
    config: SyncConfig,
    platform: String = "unknown",
    wallClock: () -> Long = System::currentTimeMillis,
    /** 登录后的用户会话 token；HTTP 服务用它做数据归属。 */
    bearerToken: String? = null,
) {

    private val syncStore = SyncStore(home)

    private val engine: SyncEngine

    init {
        require(config.isValid()) { "同步配置不完整：检查远端目录或 HTTP 地址" }
        val transport: SyncTransport = when (config.transport) {
            SyncConfig.TRANSPORT_HTTP -> HttpSyncTransport(
                baseUrl = config.baseUrl,
                token = config.token,
                bearerToken = bearerToken,
            )
            else -> FolderTransport(File(config.remotePath))
        }
        val domains = buildList {
            if (SyncConfig.DOMAIN_NOTES in config.domains) {
                add(
                    FileTreeDomain(
                        id = SyncConfig.DOMAIN_NOTES,
                        root = File(File(home, "knowledge"), "notes"),
                    ),
                )
            }
            if (SyncConfig.DOMAIN_PLAN_DOCS in config.domains) {
                add(
                    FileTreeDomain(
                        id = SyncConfig.DOMAIN_PLAN_DOCS,
                        root = File(File(home, "knowledge"), "plan-docs"),
                    ),
                )
            }
        }
        engine = SyncEngine(
            remote = transport,
            store = syncStore,
            clock = HlcClock(syncStore.deviceId(), wallClock),
            domains = domains,
            platform = platform,
        )
    }

    suspend fun sync(): SyncReport = engine.sync()

    fun deviceId(): String = syncStore.deviceId()
}
