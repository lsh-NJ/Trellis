package dev.trellis.kernel.capability

import dev.trellis.kernel.core.CapabilityId

/**
 * 能力的危险等级。见设计文档 6。
 *
 * 关键：**风险由能力决定，不由调用方声明。**
 * 如果让插件自己上报「我这个操作很安全」，那它当然会说安全  这种自证没有任何意义。
 */
enum class CapabilityKind {
    /** 只读。 */
    READ_ONLY,

    /** 会改数据，但可逆。 */
    MUTATING,

    /** 不可逆。 */
    DESTRUCTIVE,
}

data class Capability(
    val id: CapabilityId,
    val title: String,
    val kind: CapabilityKind,
)

/**
 * 内核认识的全部能力。
 *
 * 刻意保持**领域中立**  这里是 read_data / write_data，不是 read_plan / write_task。
 * 业务能力该由 :sdk-domain 扩展，内核只提供机制。（verifyKernelPurity 会盯着这条。）
 */
object Capabilities {

    val ReadData = Capability(CapabilityId("read_data"), "读取你的计划与记录", CapabilityKind.READ_ONLY)

    val WriteData = Capability(CapabilityId("write_data"), "创建与修改内容", CapabilityKind.MUTATING)

    val DeleteData = Capability(CapabilityId("delete_data"), "删除数据（不可恢复）", CapabilityKind.DESTRUCTIVE)

    val Notify = Capability(CapabilityId("notify"), "发送通知", CapabilityKind.MUTATING)

    val Background = Capability(CapabilityId("background"), "在后台持续运行", CapabilityKind.MUTATING)

    val FocusMode = Capability(CapabilityId("focus_mode"), "进入专注模式", CapabilityKind.MUTATING)

    val Network = Capability(CapabilityId("network"), "访问网络", CapabilityKind.MUTATING)

    val Clipboard = Capability(CapabilityId("clipboard"), "读取剪贴板", CapabilityKind.READ_ONLY)

    /**
     * 允许被外部智能体当作工具调用。
     *
     * 名字刻意不叫 AgentXxx：内核不认识「智能体」这个东西，
     * 它只认识「这个能力可以被暴露成工具」这件事实。
     */
    val ToolExposure = Capability(CapabilityId("tool_exposure"), "作为工具被智能体调用", CapabilityKind.MUTATING)

    val PluginAdmin = Capability(CapabilityId("plugin_admin"), "安装、卸载、启用插件", CapabilityKind.DESTRUCTIVE)

    val all: List<Capability> = listOf(
        ReadData, WriteData, DeleteData, Notify, Background,
        FocusMode, Network, Clipboard, ToolExposure, PluginAdmin,
    )

    private val index: Map<CapabilityId, Capability> = all.associateBy { it.id }

    fun byId(id: CapabilityId): Capability? = index[id]

    /** 认不认识这个能力。不认识的会被审批门当作「未知 -> 一律要人工批准」。 */
    fun isKnown(id: CapabilityId): Boolean = index.containsKey(id)

    val knownIds: Set<CapabilityId> get() = index.keys

    /** 给授权界面用的一句话说明。 */
    fun describe(id: CapabilityId): String = index[id]?.title ?: id.value
}