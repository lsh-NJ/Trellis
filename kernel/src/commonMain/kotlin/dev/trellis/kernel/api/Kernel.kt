package dev.trellis.kernel.api

import dev.trellis.kernel.approval.ApprovalBroker
import dev.trellis.kernel.approval.ApprovalGate
import dev.trellis.kernel.approval.DefaultApprovalGate
import dev.trellis.kernel.approval.PluginApprovalBroker
import dev.trellis.kernel.audit.AuditLedger
import dev.trellis.kernel.audit.InMemoryAuditLedger
import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.capability.CapabilityAccess
import dev.trellis.kernel.capability.CapabilityDeniedException
import dev.trellis.kernel.capability.FixedCapabilityAccess
import dev.trellis.kernel.capability.GrantDialog
import dev.trellis.kernel.capability.GrantStore
import dev.trellis.kernel.capability.InMemoryGrantStore
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.security.InMemoryManifestLedger
import dev.trellis.kernel.security.ManifestLedger
import dev.trellis.kernel.security.manifestFingerprint
import dev.trellis.kernel.storage.InMemoryStorageBackend
import dev.trellis.kernel.storage.KERNEL_NAMESPACE
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.storage.NamespacedKvStore
import dev.trellis.kernel.storage.StorageBackend
import dev.trellis.kernel.storage.StorageQuota
import dev.trellis.kernel.storage.StorageQuotaExceededException
import dev.trellis.kernel.storage.storageNamespaceOf
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.diag.KernelError
import dev.trellis.kernel.event.DefaultEventBus
import dev.trellis.kernel.event.EventBus
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.rule.RuleDecl
import dev.trellis.kernel.rule.RuleEngine
import dev.trellis.kernel.rule.RuleRegistrar
import dev.trellis.kernel.extension.ExtensionRegistrar
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.extension.MutableExtensionRegistry
import dev.trellis.kernel.extension.SELF_NAMESPACE
import dev.trellis.kernel.extension.PluginRegistrar
import dev.trellis.kernel.extension.RegistryServiceAccess
import dev.trellis.kernel.extension.ServiceAccess
import dev.trellis.kernel.extension.displayName
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.plugin.PluginStateMachine
import dev.trellis.kernel.registry.DependencyResolver
import dev.trellis.kernel.spi.DiscoveredPlugin
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.spi.PluginDiscovery
import dev.trellis.kernel.spi.PluginLoader
import dev.trellis.kernel.time.KernelClock
import dev.trellis.kernel.time.SystemClock
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class KernelState {
    BOOTING,

    /** 一切正常。零插件启动也属于此状态（不变量 I1）。 */
    ACTIVE,

    /** 有 required 插件没能跑起来。系统可用，但残缺。 */
    DEGRADED,

    SHUTDOWN,
}

enum class BootMode {
    NORMAL,

    /** 安全模式：只加载 builtin 且 required 的插件。见设计文档 11.2 */
    SAFE,
}

data class KernelConfig(
    val sdkVersion: SemVer,
    val platform: Platform,
    val pluginsDir: String = "",
    val dataDir: String = "",
    val snapshotDir: String = "",
    val loaders: List<PluginLoader> = emptyList(),

    /** 平台无关的发现源。实现了 [PluginDiscovery] 的 loader 会被自动并入。 */
    val discoveries: List<PluginDiscovery> = emptyList(),

val clock: KernelClock = SystemClock,
    val bootMode: BootMode = BootMode.NORMAL,

    /**
     * 启动时启用哪些插件。null = 全部启用。
     *
     * 这个维度**必须和「发现」分开**：
     *  - 发现 = 「目录里已经装了什么」（磁盘事实）
     *  - 启用 = 「这次启动跑哪些」（用户意图）
     *
     * 混在一起会有个直接后果：[install] 是按 id 重新扫描目录来找插件的，
     * 如果扫描结果已经被「启用集」过滤过，那么**没启用的插件永远装不上** 
     * 而「装一个还没启用的插件」恰恰是 install 最主要的用途。
     *
     * 必需插件（builtin && required）不受此限制，永远启用。
     */
    val enabledPlugins: Set<PluginId>? = null,

    /** 授权记录。刻意不随插件快照回滚  那是用户意图，不是软件状态。 */
    val grantStore: GrantStore = InMemoryGrantStore(),

    /** manifest 指纹账本。见不变量 I8。 */
    val manifestLedger: ManifestLedger = InMemoryManifestLedger(),

    /** 审计日志。只追加。 */
    val auditLedger: AuditLedger = InMemoryAuditLedger(),

    /** UI 层实现的授权询问。没提供 = 默认拒绝。 */
    val grantDialog: GrantDialog? = null,

    /** 不提供时内核自建一个默认审批门。 */
    val approvalGate: ApprovalGate? = null,

    /** 存储后端。默认内存实现；真实持久化落地时换一个实现即可。 */
    val storage: StorageBackend = InMemoryStorageBackend(),

    /** 见设计文档 6.3 配额表的存储部分。 */
    val storageQuota: StorageQuota = StorageQuota(),

    /** 见设计文档 6.3 配额表。 */
    val loadTimeoutMs: Long = 2_000L,
    val initTimeoutMs: Long = 2_000L,
    val startTimeoutMs: Long = 5_000L,
    val stopTimeoutMs: Long = 2_000L,
)

/** 对内是可变活动记录，对外只暴露只读快照 [PluginInfo]。 */
data class PluginInfo(
    val manifest: PluginManifest,
    val state: PluginState,
    val errors: List<KernelError>,
    val logs: List<String>,
)

sealed interface InstallResult {
    data class Installed(val state: PluginState) : InstallResult
    data class Rejected(val errors: List<KernelError>) : InstallResult
}

sealed interface UninstallResult {
    data class Uninstalled(
        val removedContributions: Int,
        /** 数据是否被立刻清除。false 表示按保留策略暂存，等待回收。 */
        val dataPurged: Boolean = false,
    ) : UninstallResult
    data class Rejected(val error: KernelError) : UninstallResult
    data object NotFound : UninstallResult
}

internal class PluginEntry(val manifest: PluginManifest) {

    var state: PluginState = PluginState.DISCOVERED
        private set

    val errors: MutableList<KernelError> = mutableListOf()
    val logs: MutableList<String> = mutableListOf()
    var instance: Plugin? = null

    /** 所有状态变更都必须过这里，非法迁移直接抛  见设计文档 4.5。 */
    fun transitionTo(next: PluginState) {
        PluginStateMachine.requireTransition(state, next)
        state = next
    }

    fun toInfo(): PluginInfo = PluginInfo(manifest, state, errors.toList(), logs.toList())
}

private class SimplePluginContext(
    override val id: PluginId,
    override val clock: KernelClock,
    private val registrar: ExtensionRegistrar,
    private val serviceAccess: ServiceAccess,
    private val capabilityAccess: CapabilityAccess,
    private val approvalBroker: ApprovalBroker,
    private val kvStore: KvStore,
    override val events: EventBus,
    override val rules: RuleRegistrar,
    private val sink: (String) -> Unit,
) : PluginContext {
    override val extensions: ExtensionRegistrar get() = registrar
    override val services: ServiceAccess get() = serviceAccess
    override val capabilities: CapabilityAccess get() = capabilityAccess
    override val approvals: ApprovalBroker get() = approvalBroker
    override val kv: KvStore get() = kvStore
    override fun log(message: String) {
        sink(message)
    }
}

/**
 * 内核门面。见设计文档 4.7 / 10。
 *
 * 唯一入口是 [boot]；构造器私有，保证内核一定经过完整的启动序列。
 */
class Kernel private constructor(
    private val config: KernelConfig,
    private val entries: MutableList<PluginEntry>,
    private val mutableExtensions: MutableExtensionRegistry,
    initialState: KernelState,
    private val errorSink: MutableList<KernelError>,
    private val reportedConflicts: MutableSet<String>,
    private val approvalGate: ApprovalGate,
    /**
     * 已安装目录：发现源看得见的一切，**不论启用与否**。
     *
     * 内核原本只有「正在跑的」这一个概念，于是「已安装但已停用」的插件
     * 对界面来说是不存在的  用户停用它之后就再也看不见它，也没法启用回来。
     *
     * 三个状态各归各的：
     *  - 已安装 = 在 [catalog] 里
     *  - 已启用 = 在 [bootOrder] 里（并且状态到了 ACTIVE）
     *  - 已停用 = 在 catalog 里但不在 bootOrder 里
     */
    private val catalog: MutableMap<PluginId, PluginManifest>,
    private val eventBus: EventBus,
    private val ruleEngine: RuleEngine,
) {

    /** 事件总线。领域层发，规则收。 */
    val events: EventBus get() = eventBus

    /** 诊断用：当前已经登记了多少条规则。 */
    fun declaredRuleCount(): Int = ruleEngine.ruleCount()

    /**
     * 不变量 I3（设计文档 4.8）：所有注册表变更必须串行。
     *
     * 用 Mutex 而不是「单线程 dispatcher」：KMP 的 common 里没有通用单线程调度器，
     * 而 Mutex 给出同样的串行化保证，且不需要额外线程。
     *
     * 注意 install/uninstall 会在持锁期间**等待插件的 init/start**  这是有意的：
     * 那段时间里注册表正处在「半安装」状态，绝不能让别人看见。
     * 不会死锁，因为插件拿不到 Kernel 引用（ctx 里没有它），根本无法重入。
     */
    private val mutationLock: Mutex = Mutex()

    private val _state: MutableStateFlow<KernelState> = MutableStateFlow(initialState)

    val state: StateFlow<KernelState> = _state.asStateFlow()

    val currentState: KernelState get() = _state.value

    /** 扩展点只读窗口。UI 与插件都从这里读别人的贡献。 */
    val extensions: ExtensionRegistry = ExtensionRegistry(mutableExtensions)

    val errors: List<KernelError> get() = errorSink.toList()

    /** 审计日志（只读窗口）。「到底发生了什么」的一手证据，由内核写，插件改不了。 */
    val audit: AuditLedger get() = config.auditLedger

    /** 审批门。UI 层可以对它调用 setManualHandler。 */
    val approvals: ApprovalGate get() = approvalGate

    val grants: GrantStore get() = config.grantStore

    fun plugins(): List<PluginInfo> = entries.map { it.toInfo() }

    fun plugin(id: PluginId): PluginInfo? = entries.firstOrNull { it.manifest.id == id }?.toInfo()

    fun manifests(): List<PluginManifest> = entries.map { it.manifest }

    /**
     * 已安装的全部插件（含已停用的）。
     *
     * 界面上要能列出停用的插件并支持启用，就必须知道它们叫什么 
     * 而它们不在 `plugins()` 里，因为那个列表只包含正在跑的。
     */
    fun catalog(): List<PluginManifest> = catalog.values.sortedBy { it.id.value }

    /**
     * 激活一个「在目录里但没在运行」的插件。
     *
     * 注意语义：它**不负责把插件搬到磁盘上**  发现与搬运是 Loader 与 M6 目录扫描的活。
     * 它负责的是把已经能被 loader 装载的插件接进运行中的内核：
     * 校验依赖与 sdkRange -> 装载 -> init -> start -> 登记贡献 -> 冲突裁决。
     */
    suspend fun install(manifest: PluginManifest): InstallResult =
        mutationLock.withLock { installLocked(manifest) }

    private suspend fun installLocked(manifest: PluginManifest): InstallResult {
        val clock = config.clock
        val id = manifest.id

        if (currentState == KernelState.SHUTDOWN) {
            return InstallResult.Rejected(
                listOf(KernelError(ErrorCode.MANIFEST_INVALID, id, "内核已关闭", clock.nowMs())),
            )
        }

        entries.firstOrNull { it.manifest.id == id }?.let { existing ->
            return InstallResult.Rejected(
                listOf(
                    KernelError(
                        ErrorCode.MANIFEST_INVALID, id,
                        "插件 ${id.value} 已在运行（状态 ${existing.state}）", clock.nowMs(),
                    ),
                ),
            )
        }

        val gateErrors = mutableListOf<KernelError>()

        if (config.sdkVersion !in manifest.sdkRange) {
            gateErrors += KernelError(
                ErrorCode.SDK_VERSION_MISMATCH, id,
                "插件要求 sdk ${manifest.sdkRange}，当前内核 sdk 为 ${config.sdkVersion}",
                clock.nowMs(),
            )
        }

        for (dep in manifest.dependsOn) {
            if (dep.optional) continue
            val target = entries.firstOrNull { it.manifest.id == dep.id }
            when {
                target == null || target.state != PluginState.ACTIVE -> gateErrors += KernelError(
                    ErrorCode.MISSING_DEPENDENCY, id,
                    "依赖 ${dep.id.value} 不在运行中（当前 ${target?.state ?: "未安装"}）", clock.nowMs(),
                )

                target.manifest.version !in dep.range -> gateErrors += KernelError(
                    ErrorCode.DEPENDENCY_VERSION_MISMATCH, id,
                    "依赖 ${dep.id.value} 需要 ${dep.range}，实际为 ${target.manifest.version}",
                    clock.nowMs(),
                )
            }
        }

        val loader = config.loaders.associateBy { it.scheme }[manifest.loader]
        if (loader == null || config.platform !in loader.platforms) {
            gateErrors += KernelError(
                ErrorCode.LOADER_UNAVAILABLE, id,
                "没有可用于 [${manifest.loader}] 的 loader（当前平台 ${config.platform}）",
                clock.nowMs(),
            )
        }

        if (gateErrors.isNotEmpty()) return InstallResult.Rejected(gateErrors)

        val entry = PluginEntry(manifest)

        if (!verifyFingerprint(config, manifest, errorSink, entry, clock.nowMs())) {
            return InstallResult.Rejected(entry.errors.toList())
        }
        val grantedCapabilities =
            resolveCapabilities(config, manifest, errorSink, entry, clock.nowMs())

        val activated = runLifecycle(
            entry, loader!!, mutableExtensions, errorSink, config,
            grantedCapabilities, approvalGate, eventBus, ruleEngine,
        )
        entries.add(entry)

        if (!activated) return InstallResult.Rejected(entry.errors.toList())

        validateRegistrations(mutableExtensions, reportedConflicts, errorSink, entries, clock.nowMs())

        // 重装成功 -> 取消回收计划。数据还在，插件回来就该能接着用。
        config.storage.delete(KERNEL_NAMESPACE, purgeKeyOf(id))

        recomputeState()
        return InstallResult.Installed(entry.state)
    }

    /**
     * 按 id 安装：重新扫描插件目录，找到对应 manifest 再走 [install]。
     *
     * 这是「插件商店」的实际流程  内容已经落在磁盘上（可能是刚被写进去的），
     * 内核负责把它接进正在运行的实例。
     */
    suspend fun install(id: PluginId): InstallResult {
        val clock = config.clock
        if (currentState == KernelState.SHUTDOWN) {
            return InstallResult.Rejected(
                listOf(KernelError(ErrorCode.MANIFEST_INVALID, id, "内核已关闭", clock.nowMs())),
            )
        }
        val scanErrors = mutableListOf<KernelError>()
        val found = discoverAll(config, scanErrors).firstOrNull { it.manifest.id == id }
        if (found == null) {
            // 扫描期的具体原因（解析失败、挂载点拼错）比「没找到」有用得多，排前面。
            return InstallResult.Rejected(
                scanErrors + KernelError(
                    ErrorCode.MANIFEST_INVALID, id,
                    "在插件目录里没有找到 " + id.value, clock.nowMs(),
                ),
            )
        }
        return mutationLock.withLock { installLocked(found.manifest) }
    }

    /**
     * 停用并摘除一个插件。
     *
     * 关键设计：**注销贡献点由内核强制完成，不依赖插件的 dispose()**。
     * 插件写漏了就摘不干净  那种架构下「卸载无残留」（不变量 I6）永远只是个愿望。
     */
    /**
     * 停用：不再加载它，但**插件文件、数据、授权全都保留**。
     *
     * 和「删除」的区别是根本性的：
     *  - 停用 = 我暂时不用它（随时 `enable()` 回来，零成本）
     *  - 删除 = 我不要它了（进回收站，7 天后连数据一起清）
     *
     * 停用会**落盘**。不落盘的话重启就复现，用户会觉得这个按钮是假的。
     */
    suspend fun disable(id: PluginId): UninstallResult = mutationLock.withLock {
        val result = detachLocked(id)
        if (result is UninstallResult.Uninstalled) rememberDisabled(id)
        result
    }

    /**
     * 删除：不加载它，**并且**把它的数据放进 7 天回收窗口。
     *
     * 和 [disable] 的区别只有一件事：会不会开始计时清数据。
     *  - 停用 = 我暂时不用（数据原地不动，随时 `enable()` 回来）
     *  - 删除 = 我不要了（7 天后数据清掉，这期间还能 `enable()` 反悔）
     *
     * 插件文件本身现在还没动  移入回收站需要文件操作，是另一块。
     * 但因为它同时被记进了停用清单，**重启之后不会自己回来**。
     */
    suspend fun remove(id: PluginId): UninstallResult = mutationLock.withLock {
        val result = detachLocked(id)
        if (result is UninstallResult.Uninstalled) {
            rememberDisabled(id)
            handleDataOnUninstall(id, purgeNow = false)
        }
        result
    }

    /**
     * 启用一个被停用（或已删除）的插件。
     *
     * 顺手取消数据回收计划  这就是「7 天后悔期」的兑现方式：
     * 只要还在窗口内，`enable()` 一次，插件和数据一起回来。
     */
    suspend fun enable(id: PluginId): InstallResult = mutationLock.withLock {
        forgetDisabled(id)
        config.storage.delete(KERNEL_NAMESPACE, purgeKeyOf(id))
        installLockedById(id)
    }

    /**
     * 兼容旧名字。
     *
     * `purgeData = true` 才立刻清数据；不传就是纯粹的停用。
     * 新代码请直接用 `disable()` / `remove()`。
     */
    @Deprecated("改用 disable()（停用）或 remove()（删除）。")
    suspend fun uninstall(id: PluginId, purgeData: Boolean = false): UninstallResult =
        if (purgeData) {
            mutationLock.withLock {
                val result = detachLocked(id)
                if (result is UninstallResult.Uninstalled) {
                    rememberDisabled(id)
                    handleDataOnUninstall(id, purgeNow = true)
                    // 立刻清了就如实报回去  调用方要能区分「暂存」和「已清除」
                    UninstallResult.Uninstalled(result.removedContributions, true)
                } else {
                    result
                }
            }
        } else {
            disable(id)
        }

    /** 已经持锁时按 id 装载（enable 用）。 */
    private suspend fun installLockedById(id: PluginId): InstallResult {
        val scanErrors = mutableListOf<KernelError>()
        val found = discoverAll(config, scanErrors).firstOrNull { it.manifest.id == id }
        if (found == null) {
            return InstallResult.Rejected(
                scanErrors + KernelError(
                    ErrorCode.MANIFEST_INVALID, id,
                    "在插件目录里没有找到 " + id.value, config.clock.nowMs(),
                ),
            )
        }
        // 重新发现出来的东西要补进目录  启用的插件也得能在界面上显示
        // 「装上」就等于「我要它跑」：顺手把停用标记清掉。
        // 不这么做的话，「停用 -> 覆盖安装 -> 重启」会让它凭空消失，
        // 而用户刚刚明明装成功了。
        forgetDisabled(id)

        for (d in discoverAll(config, mutableListOf())) catalog[d.manifest.id] = d.manifest
        return installLocked(found.manifest)
    }

    private suspend fun rememberDisabled(id: PluginId) {
        val next = loadDisabledSet(config.storage) + id
        saveDisabledSet(config.storage, next)
    }

    private suspend fun forgetDisabled(id: PluginId) {
        val next = loadDisabledSet(config.storage) - id
        saveDisabledSet(config.storage, next)
    }

    /** 当前被停用的插件。界面上要能列出它们并支持「重新启用」。 */
    suspend fun disabledPlugins(): Set<PluginId> = loadDisabledSet(config.storage)

    private suspend fun detachLocked(id: PluginId): UninstallResult {
        val entry = entries.firstOrNull { it.manifest.id == id } ?: return UninstallResult.NotFound
        val clock = config.clock

        if (entry.manifest.required) {
            return UninstallResult.Rejected(
                KernelError(ErrorCode.MANIFEST_INVALID, id, "必需插件不可卸载", clock.nowMs()),
            )
        }

        val instance = entry.instance
        if (instance != null) {
            guard { withTimeout(config.stopTimeoutMs) { instance.stop() } }
            guard { withTimeout(config.stopTimeoutMs) { instance.dispose() } }
        }

        val removed = mutableExtensions.removeOwner(id)
        if (!PluginStateMachine.isTerminal(entry.state)) entry.transitionTo(PluginState.DISPOSED)
        entries.remove(entry)
        recomputeState()

        // 注意这里**不碰数据**：拆下来就是拆下来，清不清数据由调用方决定
        // （disable 不清，remove 记回收期限，uninstall(purgeData=true) 立刻清）
        return UninstallResult.Uninstalled(removed, false)
    }

    /** 按依赖逆序停止并释放全部插件。可重复调用。 */
    suspend fun shutdown() = mutationLock.withLock { shutdownLocked() }

    private suspend fun shutdownLocked() {
        for (entry in entries.asReversed()) {
            val instance = entry.instance
            if (instance != null) {
                guard { withTimeout(config.stopTimeoutMs) { instance.stop() } }
                guard { withTimeout(config.stopTimeoutMs) { instance.dispose() } }
            }
            if (!PluginStateMachine.isTerminal(entry.state)) {
                entry.transitionTo(PluginState.DISPOSED)
            }
        }
        // 刻意不清空 entries：关机之后还应当能查到「刚才跑了什么、怎么结束的」。
        // 清空是纯粹的信息损失，而 DISPOSED 恰好就是该留下的终态记录。
        _state.value = KernelState.SHUTDOWN
    }

    /** 卸载后按保留策略暂存的数据，以及各自的回收时间。 */
    suspend fun pendingPurges(): Map<PluginId, Long> =
        config.storage.keys(KERNEL_NAMESPACE, PURGE_KEY_PREFIX).mapNotNull { key ->
            val deadline = config.storage.read(KERNEL_NAMESPACE, key)?.toLongOrNull()
                ?: return@mapNotNull null
            PluginId(key.removePrefix(PURGE_KEY_PREFIX)) to deadline
        }.toMap()

    /**
     * 回收已过保留期的插件数据。
     *
     * 之所以要有这一步：卸载时保留数据是对的（用户可能只是临时禁用），
     * 但「永远保留」就会让存储无限膨胀，而且用户删掉的插件其实没有真的被删干净。
     */
    suspend fun purgeExpiredData(): List<PluginId> = mutationLock.withLock {
        val now = config.clock.nowMs()
        val expired = pendingPurges().filterValues { it <= now }.keys.toList()
        for (id in expired) {
            config.storage.clearNamespace(storageNamespaceOf(id))
            config.storage.delete(KERNEL_NAMESPACE, purgeKeyOf(id))
        }
        expired
    }

    /**
     * 当前占用了存储的**插件**命名空间。
     *
     * 刻意排除内核自己的命名空间  那里放的是停用清单、回收期限这类内核记账，
     * 不是「插件数据」。混在一起会让「这个插件有没有残留」这个问题失去答案。
     */
    suspend fun dataNamespaces(): List<String> =
        config.storage.namespaces().filterNot { it == KERNEL_NAMESPACE }

    private suspend fun handleDataOnUninstall(id: PluginId, purgeNow: Boolean): Boolean =
        if (purgeNow) {
            config.storage.clearNamespace(storageNamespaceOf(id))
            config.storage.delete(KERNEL_NAMESPACE, purgeKeyOf(id))
            true
        } else {
            val deadline = config.clock.nowMs() + DATA_RETENTION_MS
            config.storage.write(KERNEL_NAMESPACE, purgeKeyOf(id), deadline.toString())
            false
        }

    private fun recomputeState() {
        if (_state.value == KernelState.SHUTDOWN) return
        val stranded = entries.any { it.manifest.required && it.state != PluginState.ACTIVE }
        _state.value = if (stranded) KernelState.DEGRADED else KernelState.ACTIVE
    }

    companion object {

        /**
         * 启动序列。见设计文档 10。
         *
         * 核心约定（不变量 I1）：任何单点失败都不得让 boot 抛异常。
         * 零插件、插件全挂、loader 全废  都必须返回一个可用的 Kernel。
         */
        suspend fun boot(config: KernelConfig): Kernel {
            val clock = config.clock
            val allErrors = mutableListOf<KernelError>()
            val registry = MutableExtensionRegistry()
            val approvalGate = config.approvalGate ?: DefaultApprovalGate(config.auditLedger, clock)

            // ---- 步骤 1：发现（平台无关；能不能跑由步骤 4b 决定）----
            val discovered = discoverAll(config, allErrors)

            // ---- 步骤 1b：决定这一轮跑哪些 ----
            //
            // 两个维度叠加：
            //  - enabled（内存里的白名单）：测试与一次性场景用
            //  - disabled（**持久化的黑名单**）：用户在界面上「停用」过的插件
            //
            // 后者是必须的：没有它，「停用」只活在内存里  重启之后插件原样回来，
            // 用户会觉得那个按钮是假的。（这个坑真的存在过，见 UninstallPersistenceTest。）
            val disabled = loadDisabledSet(config.storage)
            val enabled = config.enabledPlugins
            val effective = when {
                config.bootMode == BootMode.SAFE ->
                    discovered.filter { it.manifest.builtin && it.manifest.required }

                enabled == null -> discovered.filter { it.manifest.id !in disabled }

                else -> discovered.filter {
                    it.manifest.id in enabled || (it.manifest.builtin && it.manifest.required)
                }
            }

            // ---- 步骤 2：建条目 ----
            val entries = LinkedHashMap<PluginId, PluginEntry>()
            for (d in effective) {
                if (d.manifest.id !in entries) entries[d.manifest.id] = PluginEntry(d.manifest)
            }

            // ---- 步骤 2b：指纹校验（必须早于依赖解析）----
            // 放这里的原因：如果等依赖解析先跑，被篡改的插件会先以「缺少依赖」之类的
            // 理由被拒，K011 就永远没机会出现  那会让一次安全拦截伪装成一次配置错误。
            // 动作从扩展点现查，所以引擎只拿一个查询函数，不认识任何具体动作
            val ruleEngine = RuleEngine {
                registry.raw(KernelPoints.Action.id).mapNotNull { it.value as? KernelAction }
            }
            val eventBus = DefaultEventBus()
            // 规则引擎挂在总线上：一条事件进来，匹配的规则就地跑完
            eventBus.builtin = { event ->
                ruleEngine.handle(event).forEach { execution ->
                    if (!execution.ok) {
                        recordInto(
                            allErrors, ErrorCode.CONTRIBUTION_REJECTED, null,
                            "规则动作 " + execution.action + " 失败：" + execution.message,
                            config.clock.nowMs(), null,
                        )
                    }
                }
            }

            val ordered = mutableListOf<PluginEntry>()
            val tampered = mutableSetOf<PluginId>()
            for ((id, entry) in entries) {
                if (!verifyFingerprint(config, entry.manifest, allErrors, entry, clock.nowMs())) {
                    entry.transitionTo(PluginState.SKIPPED)
                    tampered.add(id)
                    ordered.add(entry)
                }
            }
            val trustworthy = effective.filterNot { it.manifest.id in tampered }

            // ---- 步骤 3：依赖解析 ----
            val resolved = DependencyResolver.resolve(
                trustworthy.map { it.manifest }, config.sdkVersion, clock.nowMs(),
            )
            allErrors.addAll(resolved.errors)
            for ((id, errs) in resolved.rejected) {
                val entry = entries[id] ?: continue
                entry.errors.addAll(errs)
                if (PluginStateMachine.canTransition(entry.state, PluginState.SKIPPED)) {
                    entry.transitionTo(PluginState.SKIPPED)
                }
            }

            // ---- 步骤 4：装载 -> 初始化 -> 启动 ----
            val loadersByScheme = config.loaders.associateBy { it.scheme }
            val broken = resolved.rejected.keys.toMutableSet()
            // 被篡改的插件同样算「不可用」，依赖它的人要一起跳过
            broken.addAll(tampered)
            for (id in resolved.rejected.keys) entries[id]?.let { ordered.add(it) }

            for (id in resolved.order) {
                val entry = entries[id] ?: continue
                val manifest = entry.manifest

                // 4a. 依赖已废 -> 跳过（闭包传播，不变量 I5 的另一半）
                val badDep = manifest.dependsOn.firstOrNull { !it.optional && it.id in broken }
                if (badDep != null) {
                    recordInto(
                        allErrors, ErrorCode.DEPENDENCY_UNAVAILABLE, id,
                        "依赖 ${badDep.id.value} 不可用", clock.nowMs(), entry,
                    )
                    entry.transitionTo(PluginState.SKIPPED)
                    broken.add(id)
                    ordered.add(entry)
                    continue
                }

                // 4a.5 能力授权：声明 != 必需，内核只负责「给或不给」
                val grantedCapabilities =
                    resolveCapabilities(config, manifest, allErrors, entry, clock.nowMs())

                // 4b. 当前平台没有对应 loader -> UNAVAILABLE，不是错误
                val loader = loadersByScheme[manifest.loader]
                if (loader == null || config.platform !in loader.platforms) {
                    recordInto(
                        allErrors, ErrorCode.LOADER_UNAVAILABLE, id,
                        "没有可用于 [${manifest.loader}] 的 loader（当前平台 ${config.platform}）",
                        clock.nowMs(), entry,
                    )
                    entry.transitionTo(PluginState.RESOLVED)
                    entry.transitionTo(PluginState.UNAVAILABLE)
                    broken.add(id)
                    ordered.add(entry)
                    continue
                }

                // 4c~4e. 生命周期
                if (
                    runLifecycle(
                        entry, loader, registry, allErrors, config,
                        grantedCapabilities, approvalGate, eventBus, ruleEngine,
                    )
                ) {
                    broken.remove(id)
                } else {
                    broken.add(id)
                }
                ordered.add(entry)
            }

            // ---- 步骤 5：扩展点冲突裁决 ----
            val conflicts = mutableSetOf<String>()
            validateRegistrations(registry, conflicts, allErrors, ordered, clock.nowMs())

            // ---- 步骤 6：裁决内核状态 ----
            val stranded = ordered.filter { it.manifest.required && it.state != PluginState.ACTIVE }
            if (stranded.isNotEmpty()) {
                allErrors.add(
                    KernelError(
                        ErrorCode.REQUIRED_PLUGIN_NOT_READY, null,
                        "必需插件未就绪：" + stranded.joinToString { it.manifest.id.value },
                        clock.nowMs(),
                    ),
                )
            }
            val finalState = if (stranded.isEmpty()) KernelState.ACTIVE else KernelState.DEGRADED

            // 目录 = 发现源看得见的一切，**早于**任何过滤。
            // 这样「停用」的插件也留在目录里，界面才能列出它并让你启用回来。
            val catalog = LinkedHashMap<PluginId, PluginManifest>()
            for (d in discovered) catalog[d.manifest.id] = d.manifest

            val kernel = Kernel(
                config, ordered, registry, finalState, allErrors, conflicts, approvalGate, catalog,
                eventBus, ruleEngine,
            )

            // 启动时顺手回收过期数据。
            // 以前这个方法只在测试里被调过  回收记录写进去了，却没有任何地方读它，
            // 于是「7 天后清除」实际上永远不会发生。
            val purged = kernel.purgeExpiredData()
            if (purged.isNotEmpty()) {
                allErrors.add(
                    KernelError(
                        ErrorCode.MANIFEST_INVALID, null,
                        "已回收 " + purged.size + " 个插件的过期数据：" +
                            purged.joinToString { it.value },
                        clock.nowMs(),
                    ),
                )
            }
            return kernel
        }
    }
}

/**
 * 装载 -> init -> start。boot 与 install 共用这一段，避免两条路径行为不一致。
 *
 * registrar 的生命周期是这里的关键：init() 一返回就立刻关闭，
 * 所以「在 init() 之外注册贡献点」这件事在结构上就不可能发生。
 */
private suspend fun runLifecycle(
    entry: PluginEntry,
    loader: PluginLoader,
    registry: MutableExtensionRegistry,
    errorSink: MutableList<KernelError>,
    config: KernelConfig,
    grantedCapabilities: Set<CapabilityId>,
    approvalGate: ApprovalGate,
    eventBus: EventBus,
    ruleEngine: RuleEngine,
): Boolean {
    val clock = config.clock
    val id = entry.manifest.id

    entry.transitionTo(PluginState.RESOLVED)

    val registrar = PluginRegistrar(
        pluginId = id,
        declaredPoints = entry.manifest.contributes.keys,
        target = registry,
    ) { code, detail -> recordInto(errorSink, code, id, detail, clock.nowMs(), entry) }

    val ctx = SimplePluginContext(
        id = id,
        clock = clock,
        registrar = registrar,
        serviceAccess = RegistryServiceAccess(registry),
        capabilityAccess = FixedCapabilityAccess(grantedCapabilities),
        approvalBroker = PluginApprovalBroker(id, approvalGate),
        kvStore = NamespacedKvStore(config.storage, storageNamespaceOf(id), id.value, config.storageQuota),
        sink = { entry.logs.add(it) },
        events = eventBus,
        rules = object : RuleRegistrar {
            override fun declare(rules: List<RuleDecl>) = ruleEngine.install(id.value, rules)
        },
    )

    val loaded = guard { withTimeout(config.loadTimeoutMs) { loader.load(entry.manifest, ctx) } }
    val instance = loaded.getOrNull()
    if (instance == null) {
        registrar.open = false
        failPlugin(
            entry, errorSink, id, clock.nowMs(),
            classify(loaded.exceptionOrNull(), ErrorCode.INIT_TIMEOUT, ErrorCode.PLUGIN_LOAD_FAILED),
            "加载失败：" + loaded.exceptionOrNull()?.message,
        )
        return false
    }
    entry.instance = instance
    entry.transitionTo(PluginState.LOADED)

    val initFailure = guard { withTimeout(config.initTimeoutMs) { instance.init(ctx) } }
    registrar.open = false
    if (initFailure.isFailure) {
        failPlugin(
            entry, errorSink, id, clock.nowMs(),
            classify(initFailure.exceptionOrNull(), ErrorCode.INIT_TIMEOUT, ErrorCode.PLUGIN_INIT_FAILED),
            "init() 失败：" + initFailure.exceptionOrNull()?.message,
        )
        return false
    }
    entry.transitionTo(PluginState.INITIALIZED)

    val startFailure = guard { withTimeout(config.startTimeoutMs) { instance.start() } }
    if (startFailure.isFailure) {
        failPlugin(
            entry, errorSink, id, clock.nowMs(),
            classify(startFailure.exceptionOrNull(), ErrorCode.INIT_TIMEOUT, ErrorCode.PLUGIN_START_FAILED),
            "start() 失败：" + startFailure.exceptionOrNull()?.message,
        )
        return false
    }
    entry.transitionTo(PluginState.ACTIVE)
    return true
}

/**
 * 基数裁决。ONE 扩展点上多于一家的，按 [Contribution.COMPARATOR] 取第一，
 * 其余记 K007  **不许静默覆盖**。
 */
private fun validateRegistrations(
    registry: MutableExtensionRegistry,
    reported: MutableSet<String>,
    errorSink: MutableList<KernelError>,
    entries: List<PluginEntry>,
    nowMs: Long,
) {
    for (pointId in registry.pointIds()) {
        for ((loser, winner) in registry.rejected(pointId)) {
            val key = pointId.value + "#" + loser.owner.value + "#" + loser.id
            if (!reported.add(key)) continue
            val detail = "扩展点 ${pointId.value} 的基数是 ONE，已由 " +
                "${winner.owner.value}#${winner.id} 占用；${loser.owner.value}#${loser.id} 被拒绝"
            recordInto(
                errorSink, ErrorCode.CONTRIBUTION_CONFLICT, loser.owner, detail, nowMs,
                entries.firstOrNull { it.manifest.id == loser.owner },
            )
        }
    }

    // 同一个数据命名空间被两个插件提供  只留一个，另一个记 K007。
    // 不报的话，{{ summary.todo }} 就会「时好时坏」，而且没人知道为什么。
    for ((namespace, group) in registry.raw(KernelPoints.Data.id).groupBy { it.id }) {
        if (group.size <= 1) continue
        // self 是按 owner 解析的，多个插件同时用它天经地义，不算冲突
        if (namespace == SELF_NAMESPACE) continue
        val sorted = group.sortedWith(Contribution.COMPARATOR)
        val winner = sorted.first()
        for (loser in sorted.drop(1)) {
            val key = "data#" + namespace + "#" + loser.owner.value
            if (!reported.add(key)) continue
            recordInto(
                errorSink, ErrorCode.CONTRIBUTION_CONFLICT, loser.owner,
                "数据命名空间「" + namespace + "」已经由 " + winner.owner.value +
                    " 提供；" + loser.owner.value + " 的被忽略",
                nowMs, entries.firstOrNull { it.manifest.id == loser.owner },
            )
        }
    }

    // 同一个契约被两个插件实现  只留一个，另一个记 K007，不许静默覆盖
    for ((loser, winner) in registry.serviceConflicts()) {
        val key = "service#" + loser.contract.displayName() + "#" + loser.owner.value
        if (!reported.add(key)) continue
        val detail = "服务 " + loser.contract.displayName() + " 已经由 " +
            winner.owner.value + " 提供；" + loser.owner.value +
            " 的实现被拒绝（同一个契约只能有一个实现）"
        recordInto(
            errorSink, ErrorCode.CONTRIBUTION_CONFLICT, loser.owner, detail, nowMs,
            entries.firstOrNull { it.manifest.id == loser.owner },
        )
    }
}

/**
 * 不变量 I8：manifest 一旦被改动过（capabilities / dependsOn / loader 等），就不再被信任。
 *
 * 首次见到就记下指纹；之后每次启动重新算一遍比对。
 * **不靠约定，靠哈希**  插件把自己从「无权限」改成「有 plugin_admin」，这里会拦下来。
 */
private fun verifyFingerprint(
    config: KernelConfig,
    manifest: PluginManifest,
    sink: MutableList<KernelError>,
    entry: PluginEntry?,
    nowMs: Long,
): Boolean {
    val actual = manifestFingerprint(manifest)
    val remembered = config.manifestLedger.fingerprintOf(manifest.id)
    if (remembered == null) {
        config.manifestLedger.remember(manifest.id, actual)
        return true
    }
    if (remembered == actual) return true
    recordInto(
        sink, ErrorCode.MANIFEST_TAMPERED, manifest.id,
        "manifest 与安装时记录的不一致（可能改过 capabilities 或 dependsOn）：" +
            remembered + " -> " + actual + "；请重新安装这个插件",
        nowMs, entry,
    )
    return false
}

/**
 * 能力授权。见设计文档 6.1。
 *
 * 刻意**不因为缺能力就让插件整个失败**  声明 != 必需。
 * 哪个能力是「没有它我别跑了」、哪个是「没有它我少显示一块」，只有插件自己知道；
 * 内核的职责是执行，不是揣测。插件用 ctx.capabilities.has / require 自己定。
 */
private suspend fun resolveCapabilities(
    config: KernelConfig,
    manifest: PluginManifest,
    sink: MutableList<KernelError>,
    entry: PluginEntry?,
    nowMs: Long,
): Set<CapabilityId> {
    val requested = manifest.capabilities
    if (requested.isEmpty()) return emptySet()

    val unknown = requested.filterNot { Capabilities.isKnown(it) }
    for (capability in unknown) {
        recordInto(
            sink, ErrorCode.CAPABILITY_UNDECLARED, manifest.id,
            "manifest 声明了内核不认识的能力「" + capability.value + "」；内核认识的是：" +
                Capabilities.knownIds.sortedBy { it.value }.joinToString { it.value },
            nowMs, entry,
        )
    }
    val valid = requested - unknown.toSet()
    if (valid.isEmpty()) return emptySet()

    val already = config.grantStore.grantedTo(manifest.id)
    val missing = valid - already
    if (missing.isEmpty()) return already

    // 问过一次就不再打扰：用户当时拒绝了就是拒绝，可以在插件设置里改
    if (config.grantStore.isDecided(manifest.id)) {
        recordInto(
            sink, ErrorCode.CAPABILITY_DENIED, manifest.id,
            "尚未授权：" + describeAll(missing) +
                "（你之前已经做过选择，可在插件设置里修改）",
            nowMs, entry,
        )
        return already
    }

    val dialog = config.grantDialog
    if (dialog == null) {
        recordInto(
            sink, ErrorCode.CAPABILITY_DENIED, manifest.id,
            "需要授权：" + describeAll(missing) +
                "；当前没有可用的授权界面，按默认拒绝处理",
            nowMs, entry,
        )
        return already
    }

    val approved = dialog.ask(manifest.id, valid, already).intersect(valid)
    config.grantStore.grant(manifest.id, approved)
    config.grantStore.markDecided(manifest.id)

    val denied = valid - approved
    if (denied.isNotEmpty()) {
        recordInto(
            sink, ErrorCode.CAPABILITY_DENIED, manifest.id,
            "你拒绝了：" + describeAll(denied),
            nowMs, entry,
        )
    }
    return config.grantStore.grantedTo(manifest.id)
}

/**
 * 能力清单的展示形式：**标题 + 机器可读 id**。
 *
 * 只给标题（「删除数据」）人看得懂但程序用不了；只给 id（delete_data）程序能用但用户看不懂。
 * 而读这段文本的既可能是用户，也可能是 Agent  两边都得照顾。
 */
private const val PURGE_KEY_PREFIX = "purge_deadline:"

/** 被用户停用的插件。存在内核自己的命名空间里，插件碰不到。 */
private const val DISABLED_KEY = "profile:disabled"

private suspend fun loadDisabledSet(storage: StorageBackend): Set<PluginId> =
    storage.read(KERNEL_NAMESPACE, DISABLED_KEY)
        ?.split('\n')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.map { PluginId(it) }
        ?.toSet()
        ?: emptySet()

private suspend fun saveDisabledSet(storage: StorageBackend, disabled: Set<PluginId>) {
    storage.write(
        KERNEL_NAMESPACE,
        DISABLED_KEY,
        disabled.map { it.value }.sorted().joinToString("\n"),
    )
}

/** 卸载后数据保留多久。设计文档 7 规则 5 写的是 7 天。 */
private const val DATA_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

private fun purgeKeyOf(id: PluginId): String = PURGE_KEY_PREFIX + id.value

private fun describeAll(capabilities: Collection<CapabilityId>): String =
    capabilities.sortedBy { it.value }.joinToString { Capabilities.describe(it) + "(" + it.value + ")" }

private fun failPlugin(
    entry: PluginEntry,
    sink: MutableList<KernelError>,
    id: PluginId,
    nowMs: Long,
    code: ErrorCode,
    detail: String,
) {
    recordInto(sink, code, id, detail, nowMs, entry)
    entry.transitionTo(PluginState.FAILED)
}

private fun recordInto(
    sink: MutableList<KernelError>,
    code: ErrorCode,
    pluginId: PluginId?,
    detail: String,
    nowMs: Long,
    entry: PluginEntry? = null,
): KernelError {
    val error = KernelError(code, pluginId, detail, nowMs)
    entry?.errors?.add(error)
    sink.add(error)
    return error
}

private fun classify(t: Throwable?, timeoutCode: ErrorCode, failCode: ErrorCode): ErrorCode = when (t) {
    is TimeoutCancellationException -> timeoutCode
    // 缺能力导致的失败必须有专属错误码，不能被淹没在「init 失败」里
    is CapabilityDeniedException -> ErrorCode.CAPABILITY_DENIED
    // 存储超限同理：它是一条可操作的诊断，不是一次普通的 init 崩溃
    is StorageQuotaExceededException -> ErrorCode.QUOTA_EXCEEDED
    else -> failCode
}

/**
 * 执行插件提供的代码，但绝不把 CancellationException 之外的东西放出去，
 * 也绝不吞掉真正的协程取消（那会破坏结构化并发）。
 *
 * 顺序很关键：TimeoutCancellationException 是 CancellationException 的子类，
 * catch 必须先子后父，写反了超时就永远识别不出来。
 */
private suspend fun <T> guard(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (timeout: TimeoutCancellationException) {
        Result.failure(timeout)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Result.failure(t)
    }

/** 跑一遍全部发现源。失败被收集，不打断其他插件  见 [DiscoveryResult]。 */
private suspend fun discoverAll(
    config: KernelConfig,
    sink: MutableList<KernelError>,
): List<DiscoveredPlugin> {
    val discovered = mutableListOf<DiscoveredPlugin>()
    val sources = config.discoveries + config.loaders.filterIsInstance<PluginDiscovery>()
    for (source in sources) {
        val outcome = guard { source.discover(config.pluginsDir) }
        val result = outcome.getOrNull()
        if (result != null) {
            discovered.addAll(result.plugins)
            sink.addAll(result.errors)
        } else {
            sink.add(
                KernelError(
                    ErrorCode.LOADER_UNAVAILABLE, null,
                    "发现源 [" + source.name + "] 失败：" + outcome.exceptionOrNull()?.message,
                    config.clock.nowMs(),
                ),
            )
        }
    }
    return discovered
}