package dev.trellis.kernel.plugin

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.approval.ApprovalBroker
import dev.trellis.kernel.capability.CapabilityAccess
import dev.trellis.kernel.extension.ExtensionRegistrar
import dev.trellis.kernel.extension.ServiceAccess
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.time.KernelClock

/**
 * 插件能看见的全部世界。见设计文档 4.4。
 *
 * 这是整份设计里最重要的接口：想让插件拥有更多自由，就朝这里加方法，绝不放开别处。
 * 每一次增长都必须保持向后兼容。
 *
 * M0/M1: id / clock / log
 * M2   : extensions（只写；读通过 Kernel.extensions 或 SlotHost）
 */
interface PluginContext {
    val id: PluginId
    val clock: KernelClock

/**
     * 跨插件通信的唯一通道。见设计文档 5.4。
     * 只读，任何时候都能用  与 [extensions] 不同，它没有阶段限制。
     */
    val services: ServiceAccess

    /**
     * 插件自己的数据抽屉。见设计文档 7。
     *
     * 它已经绑死了本插件的命名空间  「读别人的数据」在类型层面就写不出来。
     * 卸载时数据默认保留（重装可恢复），过了保留期才由内核回收。
     */
    val kv: KvStore

    /** 特权能力检查。默认拒绝：没在 manifest 里声明的能力，这里一律拿不到。 */
    val capabilities: CapabilityAccess

    /** 危险动作的审批门。需要人工点头的动作走这里。 */
    val approvals: ApprovalBroker

    /** 贡献点写入口。**只在 init() 期间可用**，之后再碰会抛异常。 */
    val extensions: ExtensionRegistrar

    /** 插件日志。当前转发到内核诊断收集器。 */
    fun log(message: String)
}

/** 插件生命周期。见设计文档 4.3  四个方法的语义边界不要混。 */
interface Plugin {

    val manifest: PluginManifest

    /** 注册贡献点、准备资源。禁止在这里启动后台任务。 */
    suspend fun init(ctx: PluginContext) {}

    /** 开始干活：订阅事件、启动定时。 */
    suspend fun start() {}

    /** 停止干活，但保留资源，可能被重新 start。 */
    suspend fun stop() {}

    /**
     * 释放一切。注意：**注销贡献点不靠这个方法** 
     * 卸载时内核会按属主强制清空登记，插件写漏了也清得掉。
     */
    suspend fun dispose() {}
}