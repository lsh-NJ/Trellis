package dev.trellis.kernel.extension

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId

/**
 * 扩展点的基数。见设计文档 5.1。
 *  - MANY：多个插件可以同时提供，按 order 排序
 *  - ONE ：只允许一个实现，冲突必须报 K007，**不许静默覆盖**
 */
enum class Cardinality { ONE, MANY }

/**
 * 扩展点。**它是坐标，不是插件能随便发明的**  内核与 SDK 预先声明全部扩展点。
 *
 * 刻意不持有 KClass：KMP 的 common 代码里没有可用的反射，
 * 类型安全由泛型参数在调用点保证（`register(point, value)` 的第二个参数必须匹配 T）。
 */
class ExtensionPoint<T : Any>(
    val id: ExtensionPointId,
    val cardinality: Cardinality = Cardinality.MANY,
    val contractName: String = "",
) {
    override fun toString(): String = "${id.value}(${cardinality.name})"
}

/** 贡献者可选实现：想让内核按 order 排序、按 id 标识，就实现它。 */
interface OrderedContribution {
    val id: String
    val order: Int
}

/** 注册表里的一条登记。 */
data class Contribution(
    val pointId: ExtensionPointId,
    val owner: PluginId,
    val id: String,
    val order: Int,
    val value: Any,
) : Comparable<Contribution> {

    override fun compareTo(other: Contribution): Int = COMPARATOR.compare(this, other)

    /** 排序必须完全确定：order -> owner -> id。同一输入永远产出同一顺序。 */
    companion object {
        val COMPARATOR: Comparator<Contribution> =
            compareBy({ it.order }, { it.owner.value }, { it.id })
    }
}