package dev.trellis.app.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.trellis.domain.Plan
import dev.trellis.domain.PlanService
import dev.trellis.domain.PlanSummary
import dev.trellis.domain.Task
import dev.trellis.domain.TaskStatus
import dev.trellis.dsl.DslLoader
import dev.trellis.dsl.JvmPluginFileSystem
import dev.trellis.feature.plan.PlanCorePlugin
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.api.PluginInfo
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.approval.ActionRequest
import dev.trellis.kernel.approval.DefaultApprovalGate
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.diag.KernelError
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.extension.ServiceBinding
import dev.trellis.kernel.spi.BuiltinLoader
import dev.trellis.kernel.spi.Platform
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred

/**
 * 应用运行时：内核 + 领域服务 + 界面状态。
 *
 * 构造分两步（先建空壳、启动内核、再挂上去），因为**内核在启动过程中就会调用授权对话框**，
 * 那时候运行时对象必须已经存在  否则第一轮授权无处可问，
 * 插件会被「没有可用的授权界面」默认拒绝掉。
 */
class AppRuntime private constructor() {

    lateinit var kernel: Kernel
        private set

    lateinit var planService: PlanService
        private set

    lateinit var storeFile: File
        private set

    lateinit var pluginsDir: File
        private set

    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set

    var summary by mutableStateOf(PlanSummary())
        private set

    var goal by mutableStateOf<Plan?>(null)
        private set

    var pluginInfos by mutableStateOf<List<PluginInfo>>(emptyList())
        private set

    /** 被停用的插件。它们不加载，但随时能启用回来。 */
    var disabledPlugins by mutableStateOf<Set<PluginId>>(emptySet())
        private set

    /** 见过的 manifest 缓存  插件被停用后就不在 plugins() 里了，但界面上还想显示它的名字。 */
    private val manifestCache = mutableMapOf<PluginId, PluginManifest>()

    /** 待用户回答的审批询问。非空时界面弹出确认框。 */
    var pendingApproval by mutableStateOf<ActionRequest?>(null)
        private set

    private var approvalAnswer: CompletableDeferred<Boolean>? = null

    val kernelErrors: List<KernelError> get() = kernel.errors

    fun answerApproval(allow: Boolean) {
        pendingApproval = null
        approvalAnswer?.complete(allow)
        approvalAnswer = null
    }

    /** 某个插件贡献了什么（卡片、服务）。点开详情才需要。 */
    fun contributionsOf(id: PluginId): List<Contribution> = kernel.extensions.contributionsOf(id)

    fun servicesOf(id: PluginId): List<ServiceBinding> = kernel.extensions.servicesOf(id)

    fun openPluginsFolder() {
        runCatching { java.awt.Desktop.getDesktop().open(pluginsDir) }
    }

    suspend fun refresh() {
        goal = planService.plans().firstOrNull()
        tasks = planService.tasks()
        val now = System.currentTimeMillis()
        summary = planService.summary(now, endOfTodayMs(now))
        pluginInfos = kernel.plugins()
        // 从**已安装目录**（不是运行列表）填缓存  停用的插件也要能显示名字
        for (manifest in kernel.catalog()) manifestCache[manifest.id] = manifest
        disabledPlugins = kernel.disabledPlugins()
    }

    suspend fun addTask(title: String) {
        if (title.isBlank()) return
        planService.addTask(title.trim(), planId = goal?.id)
        refresh()
    }

    suspend fun toggle(task: Task) {
        planService.setStatus(task.id, if (task.isDone) TaskStatus.TODO else TaskStatus.DONE)
        refresh()
    }

    suspend fun delete(task: Task) {
        planService.deleteTask(task.id)
        refresh()
    }

    suspend fun logTime(task: Task, minutes: Int) {
        planService.logTime(task.id, minutes)
        refresh()
    }

    /** 停用：不加载，但文件和数全都留着，随时能启用回来。 */
    suspend fun disablePlugin(id: String) {
        kernel.disable(PluginId(id))
        refresh()
    }

    suspend fun enablePlugin(id: String) {
        kernel.enable(PluginId(id))
        refresh()
    }

    /** 删除：停用 + 数据进入 7 天回收窗口。 */
    suspend fun removePlugin(id: String) {
        kernel.remove(PluginId(id))
        refresh()
    }

    /** 停用的插件不在 plugins() 里，名字只能靠缓存。 */
    fun nameOf(id: PluginId): String = manifestCache[id]?.name?.ifBlank { id.value } ?: id.value

    fun isBuiltin(id: PluginId): Boolean = manifestCache[id]?.builtin ?: false

    suspend fun shutdown() {
        if (this::kernel.isInitialized) kernel.shutdown()
    }

    /** 这个插件声明了哪些能力、已经拿到哪些。 */
    fun capabilitiesOf(id: PluginId): Pair<Set<CapabilityId>, Set<CapabilityId>> {
        val info = kernel.plugins().firstOrNull { it.manifest.id == id }
        val declared = info?.manifest?.capabilities ?: emptySet()
        return declared to kernel.grants.grantedTo(id)
    }

    /**
     * 授予权限，然后**重载这个插件**。
     *
     * 为什么必须重载：能力是在 `init()` 时算好交给插件的
     * （`ctx.capabilities` 是一个固定集合）。不重启它，插件不会知道自己刚拿到了新权限。
     */
    suspend fun grantAndReload(id: PluginId, capabilities: Set<CapabilityId>) {
        kernel.grants.grant(id, capabilities)
        kernel.uninstall(id)
        kernel.install(id)
        refresh()
    }

    private fun wireApprovalDialog() {
        val gate = kernel.approvals as? DefaultApprovalGate ?: return
        gate.setManualHandler { request ->
            val answer = CompletableDeferred<Boolean>()
            approvalAnswer = answer
            pendingApproval = request
            answer.await()
        }
    }

    companion object {

        /** [home] 可注入，测试就不必污染真实的家目录。 */
        suspend fun create(
            home: File = File(System.getProperty("user.home"), ".trellis"),
        ): AppRuntime {
            val runtime = AppRuntime()
            val storeFile = File(home, "store.json")
            val pluginsDir = File(home, "plugins").apply { mkdirs() }

            val kernel = Kernel.boot(
                KernelConfig(
                    sdkVersion = SemVer.parse("1.0.0"),
                    platform = Platform.JVM,
                    pluginsDir = pluginsDir.absolutePath,
                    loaders = listOf(
                        BuiltinLoader(listOf(PlanCorePlugin(), QuickAddPlugin(), DangerZonePlugin())),
                        DslLoader(JvmPluginFileSystem()),
                    ),
                    storage = FileStorageBackend(storeFile),
                    // 刻意**不**在这里挂授权对话框：
                    // 内核启动时会调用它，而那时界面还没起来  弹不出框也答不了，
                    // 唯一的结果就是死锁（这个坑真踩到了）。
                    // 授权改成「插件详情页里点一下」，见 grantAndReload()。
                ),
            )
            val plan = kernel.extensions.service(PlanService::class)
                ?: error("core.plan 没有发布 PlanService")

            runtime.kernel = kernel
            runtime.planService = plan
            runtime.storeFile = storeFile
            runtime.pluginsDir = pluginsDir
            runtime.wireApprovalDialog()

            if (plan.plans().isEmpty()) plan.createPlan("我的第一个计划")
            runtime.refresh()
            return runtime
        }
    }
}

/**
 * 「今天」的边界由**界面层**给出，领域层不该猜时区  这是 PlanService.summary 的约定。
 * 这里用 JVM 的系统时区算，Android 上换成平台 API 即可。
 */
private fun endOfTodayMs(nowMs: Long): Long {
    val zone = ZoneId.systemDefault()
    return Instant.ofEpochMilli(nowMs)
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atStartOfDay(zone)
        .toInstant()
        .toEpochMilli()
}
