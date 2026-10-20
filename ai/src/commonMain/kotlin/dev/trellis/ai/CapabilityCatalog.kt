package dev.trellis.ai

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.slot.Slots

/** 一个挂载点：id 和一句人话。 */
data class CatalogMountPoint(val id: String, val label: String)

/** 一个事件载荷字段。 */
data class CatalogEventField(val name: String, val description: String)

/** 一个可以触发规则的事件。 */
data class CatalogEvent(val id: String, val label: String, val payload: List<CatalogEventField>)

/** 一个可以调用的原子动作。 */
data class CatalogAction(val id: String, val label: String)

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
    val actions: List<CatalogAction> = emptyList(),
    val events: List<CatalogEvent> = emptyList(),
) {
    val eventIds: Set<String> get() = events.mapTo(LinkedHashSet()) { it.id }
    val actionIds: Set<String> get() = actions.mapTo(LinkedHashSet()) { it.id }
    val mountPointIds: Set<String> get() = mountPoints.mapTo(LinkedHashSet()) { it.id }
    val fieldPaths: Set<String> get() = dataFields.mapTo(LinkedHashSet()) { it.path }

    companion object {

        /**
         * 从运行中的内核现取。装了什么插件，数据字段清单就是什么。
         *
         * [renderedPoints] 是**界面上真的渲染了的**挂载点。默认全给，但真实应用
         * 必须传实际渲染的那几个。否则模型会往看不见的地方挂卡片，
         * 用户装完之后在界面上找不到，只能反复开关插件玩「找不同」。
         */
        fun of(
            registry: ExtensionRegistry,
            renderedPoints: Set<ExtensionPointId> = Slots.knownIds,
        ): CapabilityCatalog {
            // Slots.point(id, label) 把人类可读的名字放在 contractName 里
            val mounts = Slots.all
                .filter { it.id in renderedPoints }
                .map { CatalogMountPoint(it.id.value, it.contractName) }

            // all() 已经把 value 取出来了，所以这里拿到的就是 DataProvider 本身
            val fields = registry.all(KernelPoints.Data).flatMap { provider ->
                provider.fields.map { CatalogDataField(provider.id + "." + it.path, it.description) }
            }

            val availableActions = registry.all(KernelPoints.Action)
                .map { CatalogAction(it.id, it.label) }

            val availableEvents = registry.all(KernelPoints.Event).map { declaration ->
                CatalogEvent(
                    declaration.type,
                    declaration.label,
                    declaration.payload.map { CatalogEventField(it.name, it.description) },
                )
            }

            return CapabilityCatalog(mounts, fields, availableActions, availableEvents)
        }
    }
}
