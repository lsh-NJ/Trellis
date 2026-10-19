package dev.trellis.ai

import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.slot.Slots

/** 一个挂载点：id 和一句人话。 */
data class CatalogMountPoint(val id: String, val label: String)

/** 一个可引用的数据字段：完整路径（如 `summary.todo`）和一句人话。 */
data class CatalogDataField(val path: String, val description: String)

/**
 * 「现在这个程序能做什么」的机器可读清单。
 *
 * 它是 prompt 的全部素材：模型能往哪挂、能引用什么，都只从这里来。
 * **清单之外的东西一律会被拒**，所以这份清单必须完整且准确 ——
 * 少列一个，模型就用不上；多列一个，模型就会写一个装不上的卡片。
 *
 * 挂载点是静态的（来自 [Slots]），数据字段是动态的（来自当前注册的数据源）。
 */
data class CapabilityCatalog(
    val mountPoints: List<CatalogMountPoint>,
    val dataFields: List<CatalogDataField>,
) {
    val mountPointIds: Set<String> get() = mountPoints.mapTo(LinkedHashSet()) { it.id }
    val fieldPaths: Set<String> get() = dataFields.mapTo(LinkedHashSet()) { it.path }

    companion object {

        /** 从运行中的内核现取 —— 装了什么插件，清单就是什么。 */
        fun of(registry: ExtensionRegistry): CapabilityCatalog {
            // Slots.point(id, label) 把人类可读的名字放在 contractName 里
            val mounts = Slots.all.map { CatalogMountPoint(it.id.value, it.contractName) }

            // all() 已经把 value 取出来了，所以这里拿到的就是 DataProvider 本身
            val fields = registry.all(KernelPoints.Data).flatMap { provider ->
                provider.fields.map { CatalogDataField(provider.id + "." + it.path, it.description) }
            }

            return CapabilityCatalog(mounts, fields)
        }
    }
}
