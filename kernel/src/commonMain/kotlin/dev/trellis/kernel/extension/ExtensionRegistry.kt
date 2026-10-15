package dev.trellis.kernel.extension

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import kotlin.reflect.KClass

/**
 * 可写注册表。只在内核内部使用  插件永远拿不到它，只能拿到 [ExtensionRegistrar]。
 *
 * 设计要点：[add] 只登记，不做裁决；「谁是赢家」在读取时计算。
 * 这样卸载插件时不需要「把赢家位置让给第二名」这类状态修补，删掉就能自愈。
 */
internal class MutableExtensionRegistry {

    private val byPoint = LinkedHashMap<ExtensionPointId, MutableList<Contribution>>()
    private val cardinality = LinkedHashMap<ExtensionPointId, Cardinality>()

    /** 服务是**按键取值**的，不是有序列表，所以单独一张表。见设计文档 5.4。 */
    private val services = LinkedHashMap<KClass<*>, MutableList<ServiceBinding>>()

    fun recordCardinality(point: ExtensionPoint<*>) {
        val previous = cardinality.put(point.id, point.cardinality)
        check(previous == null || previous == point.cardinality) {
            "扩展点 ${point.id.value} 被声明了两种基数：$previous 与 ${point.cardinality}"
        }
    }

    fun add(contribution: Contribution) {
        byPoint.getOrPut(contribution.pointId) { mutableListOf() }.add(contribution)
    }

    fun publish(binding: ServiceBinding) {
        services.getOrPut(binding.contract) { mutableListOf() }.add(binding)
    }

    /** 同一个契约被多个插件实现时，按属主名取第一个  确定的，不是随机的。 */
    fun effectiveService(contract: KClass<*>): ServiceBinding? =
        services[contract]?.minByOrNull { it.owner.value }

    fun serviceContracts(): Set<KClass<*>> =
        services.filterValues { it.isNotEmpty() }.keys.toSet()

    /** (落选者, 赢家)。启动时用它生成 K007。 */
    fun serviceConflicts(): List<Pair<ServiceBinding, ServiceBinding>> =
        services.values
            .filter { it.size > 1 }
            .flatMap { list ->
                val sorted = list.sortedBy { it.owner.value }
                sorted.drop(1).map { it to sorted.first() }
            }

    /** 按属主移除全部登记，返回移除条数。卸载插件时由内核调用，插件自己的 dispose 不作数。 */
    fun removeOwner(owner: PluginId): Int {
        var removed = 0
        for (list in byPoint.values) {
            removed += list.count { it.owner == owner }
            list.removeAll { it.owner == owner }
        }
        for (list in services.values) {
            removed += list.count { it.owner == owner }
            list.removeAll { it.owner == owner }
        }
        return removed
    }

    fun raw(pointId: ExtensionPointId): List<Contribution> = byPoint[pointId].orEmpty()

    fun allContributions(): List<Contribution> = byPoint.values.flatten()

    fun allServices(): List<ServiceBinding> = services.values.flatten()

    fun pointIds(): Set<ExtensionPointId> = byPoint.keys.toSet()

    fun cardinalityOf(pointId: ExtensionPointId): Cardinality =
        cardinality[pointId] ?: Cardinality.MANY

    /** 生效的贡献列表：MANY 全给，ONE 只给排第一的。 */
    fun effective(pointId: ExtensionPointId): List<Contribution> {
        val sorted = raw(pointId).sorted()
        return when (cardinalityOf(pointId)) {
            Cardinality.MANY -> sorted
            Cardinality.ONE -> sorted.take(1)
        }
    }

    /**
     * 被基数规则挤掉的贡献。启动时用它生成 K007。
     * 返回值是 (被淘汰者, 赢家) 的列表。
     */
    fun rejected(pointId: ExtensionPointId): List<Pair<Contribution, Contribution>> {
        if (cardinalityOf(pointId) != Cardinality.ONE) return emptyList()
        val sorted = raw(pointId).sorted()
        if (sorted.size <= 1) return emptyList()
        val winner = sorted.first()
        return sorted.drop(1).map { it to winner }
    }
}

/**
 * 只读视图。这是内核对外暴露的扩展点窗口，插件通过它读取别人的贡献。
 */
class ExtensionRegistry internal constructor(
    private val mutable: MutableExtensionRegistry,
) {

    fun <T : Any> all(point: ExtensionPoint<T>): List<T> {
        @Suppress("UNCHECKED_CAST")
        return mutable.effective(point.id).map { it.value } as List<T>
    }

    fun <T : Any> first(point: ExtensionPoint<T>): T? = all(point).firstOrNull()

    fun <T : Any> isEmpty(point: ExtensionPoint<T>): Boolean = mutable.effective(point.id).isEmpty()

    /** 只为了诊断：某个扩展点上有几条登记（含被挤掉的）。 */
    fun rawCount(pointId: ExtensionPointId): Int = mutable.raw(pointId).size

    fun pointIds(): Set<ExtensionPointId> = mutable.pointIds()

    /** 只读的服务查询，给诊断面板与测试用。插件侧请用 ctx.services。 */
    fun <T : Any> service(contract: KClass<T>): T? {
        val binding = mutable.effectiveService(contract) ?: return null
        @Suppress("UNCHECKED_CAST")
        return binding.instance as T
    }

    fun serviceContracts(): Set<KClass<*>> = mutable.serviceContracts()

    /**
     * 某个插件贡献了什么。给插件管理界面用。
     *
     * 「贡献」不是文件、不是类，是**运行时调用了几次 register()**  调一次登记一条。
     * 所以一个插件可能有十几条，也可能一条都没有（活着，但什么都没干）。
     */
    fun contributionsOf(owner: PluginId): List<Contribution> =
        mutable.allContributions().filter { it.owner == owner }.sortedWith(Contribution.COMPARATOR)

    /** 某个插件发布了哪些服务。 */
    fun servicesOf(owner: PluginId): List<ServiceBinding> =
        mutable.allServices().filter { it.owner == owner }
}