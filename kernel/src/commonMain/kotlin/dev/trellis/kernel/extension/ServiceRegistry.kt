package dev.trellis.kernel.extension

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import kotlin.reflect.KClass

/**
 * 一条服务登记：某个插件声明「这个接口由我来实现」。见设计文档 5.4。
 *
 * 为什么用 KClass 当键而不是字符串：Kotlin 插件写 `require(PlanService::class)` 最自然。
 * 注意这里**只把 KClass 当键用**，从不调用它的 isInstance 
 * 那需要 kotlin-reflect，而 KMP 的 common 里没有。类型安全由泛型在调用点保证。
 */
data class ServiceBinding(
    val contract: KClass<*>,
    val instance: Any,
    val owner: PluginId,
)

/**
 * 插件之间通信的**唯一通道**。
 *
 * 铁律 1 说「插件之间不许互相 import 类」 那它们怎么协作？答案就是这个：
 * 通过接口交换实现，而不是通过具体的类。消费者只认识 `PlanService` 这个接口，
 * 不认识 `RealPlanService` 是哪个插件写的、怎么写的。
 */
interface ServiceAccess {

    fun <T : Any> find(contract: KClass<T>): T?

    /** 拿不到就抛  调用方通常处在 init()/start() 里，于是自然会转成 FAILED。 */
    fun <T : Any> require(contract: KClass<T>): T

    /** 当前可用服务的契约清单，给诊断面板用。 */
    fun contracts(): Set<KClass<*>>
}

class ServiceUnavailableException(
    val contract: KClass<*>,
    detail: String,
) : IllegalStateException(detail)

/**
 * 内核自己声明的「系统级」扩展点。
 * 它们不属于任何业务，所以留在内核；业务扩展点属于 :sdk-domain。
 */
object KernelPoints {

    /**
     * 服务发布的声明位。
     *
     * manifest 的 `contributes` 里必须出现这个 id，插件才被允许 publish 
     * 于是「这个插件会对外提供哪些能力」在安装之前就能被静态看到，
     * 而不是等它跑起来才知道。这是让 Agent 生成的插件可审查的前提。
     */
    val Service: ExtensionPoint<ServiceBinding> = ExtensionPoint(
        ExtensionPointId("kernel.service"),
        Cardinality.MANY,
        "ServiceBinding",
    )
}

/** 内核内部实现。[MutableExtensionRegistry] 上的一层只读视图。 */
internal class RegistryServiceAccess(
    private val registry: MutableExtensionRegistry,
) : ServiceAccess {

    override fun <T : Any> find(contract: KClass<T>): T? {
        val binding = registry.effectiveService(contract) ?: return null
        @Suppress("UNCHECKED_CAST")
        return binding.instance as T
    }

    override fun <T : Any> require(contract: KClass<T>): T =
        find(contract) ?: throw ServiceUnavailableException(
            contract,
            "没有插件提供 " + contract.displayName() + "。" + availabilityHint(),
        )

    override fun contracts(): Set<KClass<*>> = registry.serviceContracts()

    private fun availabilityHint(): String {
        val available = registry.serviceContracts()
        return if (available.isEmpty()) {
            "当前没有任何服务被发布（检查一下 provider 插件是否在 dependsOn 里，以及它是否声明了 kernel.service）"
        } else {
            "已发布的服务：" + available.map { it.displayName() }.sorted().joinToString(", ")
        }
    }
}

fun KClass<*>.displayName(): String {
    val simple = try {
        simpleName
    } catch (t: Throwable) {
        null
    }
    return simple ?: "<未知类型>"
}