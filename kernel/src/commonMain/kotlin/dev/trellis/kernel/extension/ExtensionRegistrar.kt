package dev.trellis.kernel.extension

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import kotlin.reflect.KClass

/**
 * 插件在 init() 期间能拿到的唯一写入口。见设计文档 5.3。
 *
 * 两条硬约束（都不是靠自觉，是靠代码）：
 *  1. **只能注册 manifest 里声明过的扩展点。** 未声明就注册 = 拒绝 + K019。
 *     这样 Agent 生成的插件在安装前就能被静态校验，而不是跑起来才发现它想接管什么。
 *  2. **只能在 init() 期间注册。** init() 返回后 registrar 关闭，再注册 = 拒绝 + K019。
 *
 * 注意「声明了但没注册」是完全合法的  那就是条件注册：
 * 插件可以声明两个候选扩展点，运行时根据能力决定实际注册哪一个。
 */
interface ExtensionRegistrar {

    /** 声明过的扩展点可以注册。返回 false 表示被拒绝（原因已记入诊断）。 */
    fun <T : Any> register(point: ExtensionPoint<T>, contribution: T): Boolean

    /**
     * 发布一个服务实现，供其他插件通过 [ServiceAccess.require] 取用。
     * 需要 manifest 的 contributes 里声明 kernel.service  与 register 同一个闸门。
     */
    fun <T : Any> publish(contract: KClass<T>, instance: T): Boolean

    /** 本插件在 manifest 里声明过的扩展点。 */
    val declaredPoints: Set<ExtensionPointId>
}

internal class PluginRegistrar(
    private val pluginId: PluginId,
    override val declaredPoints: Set<ExtensionPointId>,
    private val target: MutableExtensionRegistry,
    private val reject: (ErrorCode, String) -> Unit,
) : ExtensionRegistrar {

    /** init() 结束后置 false。 */
    var open: Boolean = true

    override fun <T : Any> register(point: ExtensionPoint<T>, contribution: T): Boolean {
        if (!open) {
            reject(
                ErrorCode.CONTRIBUTION_REJECTED,
                "插件 ${pluginId.value} 在 init() 之外尝试注册扩展点 ${point.id.value}；" +
                    "扩展点只允许在 init() 期间注册",
            )
            return false
        }
        if (point.id !in declaredPoints) {
            reject(
                ErrorCode.CONTRIBUTION_REJECTED,
                "插件 ${pluginId.value} 未在 manifest 的 contributes 中声明扩展点 ${point.id.value}",
            )
            return false
        }
        target.recordCardinality(point)
        val identity = identityOf(contribution, point)
        target.add(Contribution(point.id, pluginId, identity.first, identity.second, contribution))
        return true
    }

    override fun <T : Any> publish(contract: KClass<T>, instance: T): Boolean {
        if (!open) {
            reject(
                ErrorCode.CONTRIBUTION_REJECTED,
                "插件 ${"$"}pluginId.value 在 init() 之外尝试发布服务 ${"$"}{contract.displayName()}；" +
                    "服务只允许在 init() 期间发布",
            )
            return false
        }
        if (KernelPoints.Service.id !in declaredPoints) {
            reject(
                ErrorCode.CONTRIBUTION_REJECTED,
                "插件 ${"$"}pluginId.value 未在 manifest 的 contributes 中声明 " +
                    "kernel.service，因此不能发布服务 ${"$"}{contract.displayName()}",
            )
            return false
        }
        target.publish(ServiceBinding(contract, instance, pluginId))
        return true
    }

    private fun identityOf(contribution: Any, point: ExtensionPoint<*>): Pair<String, Int> =
        if (contribution is OrderedContribution) {
            contribution.id to contribution.order
        } else {
            // 没实现 OrderedContribution 的贡献（例如服务）用稳定合成 id，order 取中位
            "${pluginId.value}:${point.id.value}" to DEFAULT_ORDER
        }

    private companion object {
        const val DEFAULT_ORDER = 100
    }
}