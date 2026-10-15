package dev.trellis.kernel.registry

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.diag.KernelError
import dev.trellis.kernel.plugin.PluginManifest

/**
 * 依赖解析结果。
 *
 * 注意这**不是**「全成或全败」：设计文档 4.6 要求失败必须隔离 
 * 被拒的插件进 [rejected]，其余的照常启动。
 * （设计文档里原写作 Ok/Failed 的 sealed interface，实现时发现那样会丢掉
 * 「健康的插件」这个信息，改为携带 rejected 的单一结果类型。）
 */
data class ResolveResult(
    /** 可以正常启动的插件，按依赖序排列（被依赖者在前）。 */
    val order: List<PluginId>,
    /** 被拒插件 -> 原因。 */
    val rejected: Map<PluginId, List<KernelError>>,
    /** 全部错误，含被拒插件的。 */
    val errors: List<KernelError>,
) {
    val isHealthy: Boolean get() = rejected.isEmpty()

    companion object {
        val EMPTY = ResolveResult(emptyList(), emptyMap(), emptyList())
    }
}

/**
 * 依赖解析：版本区间校验 + 失败闭包传播 + Kahn 拓扑排序 + 环检测。
 * 见设计文档 4.6。这是纯函数，没有任何 I/O，可以放心单测。
 */
object DependencyResolver {

    fun resolve(
        all: List<PluginManifest>,
        sdkVersion: SemVer,
        nowMs: Long = 0L,
    ): ResolveResult {
        val errors = mutableListOf<KernelError>()
        val byId = LinkedHashMap<PluginId, PluginManifest>()
        val rejected = LinkedHashMap<PluginId, MutableList<KernelError>>()

        fun reject(id: PluginId, error: KernelError) {
            rejected.getOrPut(id) { mutableListOf() }.add(error)
            errors.add(error)
        }

        // ---- 1. 重复 id：后者覆盖前者，但必须报错（K004）----
        for (m in all) {
            val prev = byId.put(m.id, m)
            if (prev != null) {
                reject(
                    m.id,
                    KernelError(
                        ErrorCode.MANIFEST_INVALID, m.id,
                        "重复的插件 id：${m.id.value}（已有 $prev，又发现 $m）", nowMs,
                    ),
                )
            }
        }

        // ---- 2. sdkRange 校验（K001）----
        for (m in byId.values) {
            if (sdkVersion !in m.sdkRange) {
                reject(
                    m.id,
                    KernelError(
                        ErrorCode.SDK_VERSION_MISMATCH, m.id,
                        "插件要求 sdk ${m.sdkRange}，当前内核 sdk 为 $sdkVersion", nowMs,
                    ),
                )
            }
        }

        // ---- 3. 直接依赖的存在性与版本（K002 / K013）----
        for (m in byId.values) {
            if (m.id in rejected) continue
            for (dep in m.dependsOn) {
                if (dep.optional) continue
                val target = byId[dep.id]
                when {
                    target == null -> reject(
                        m.id,
                        KernelError(
                            ErrorCode.MISSING_DEPENDENCY, m.id,
                            "缺少依赖 ${dep.id.value} ${dep.range}", nowMs,
                        ),
                    )

                    target.version !in dep.range -> reject(
                        m.id,
                        KernelError(
                            ErrorCode.DEPENDENCY_VERSION_MISMATCH, m.id,
                            "依赖 ${dep.id.value} 需要 ${dep.range}，实际为 ${target.version}", nowMs,
                        ),
                    )
                }
            }
        }

        // ---- 4. 失败闭包传播：依赖被拒 -> 自己也被拒（K014）----
        var changed = true
        while (changed) {
            changed = false
            for (m in byId.values) {
                if (m.id in rejected) continue
                val bad = m.dependsOn.firstOrNull { !it.optional && it.id in rejected } ?: continue
                reject(
                    m.id,
                    KernelError(
                        ErrorCode.DEPENDENCY_UNAVAILABLE, m.id,
                        "依赖 ${bad.id.value} 未能加载，本插件一并跳过", nowMs,
                    ),
                )
                changed = true
            }
        }

        // ---- 5. Kahn 拓扑排序（只对幸存者）----
        val alive = byId.values.filter { it.id !in rejected }
        val aliveIds = alive.mapTo(mutableSetOf()) { it.id }
        val indegree = alive.associate { it.id to 0 }.toMutableMap()
        val dependents = alive.associate { it.id to mutableListOf<PluginId>() }.toMutableMap()

        for (m in alive) {
            val deps = m.dependsOn.map { it.id }.filter { it in aliveIds }.distinct()
            for (depId in deps) {
                dependents.getValue(depId).add(m.id)
                indegree[m.id] = indegree.getValue(m.id) + 1
            }
        }

        // 排序时一律按 id 字典序取队首，保证同一输入永远产出同一顺序（可重现）。
        val queue = ArrayDeque(
            alive.map { it.id }.filter { indegree.getValue(it) == 0 }.sortedBy { it.value },
        )
        val order = mutableListOf<PluginId>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            order.add(id)
            for (next in dependents.getValue(id).sortedBy { it.value }) {
                val left = indegree.getValue(next) - 1
                indegree[next] = left
                if (left == 0) queue.addLast(next)
            }
        }

        // ---- 6. 环检测（K003）----
        // Kahn 排不完的节点 = 环上节点 + 环的下游。它们全部不可用。
        if (order.size < alive.size) {
            val done = order.toSet()
            val remaining = alive.map { it.id }.filterNot { it in done }.toSortedSet(compareBy { it.value })
            val path = extractCycle(remaining.toSet(), byId)
            val detail = if (path.isEmpty()) {
                "以下插件无法排序：${remaining.joinToString { it.value }}"
            } else {
                "循环依赖路径：" + path.joinToString(" -> ") { it.value }
            }
            var first = true
            for (id in remaining) {
                reject(
                    id,
                    KernelError(
                        ErrorCode.CIRCULAR_DEPENDENCY, id,
                        if (first) detail else "位于循环依赖的下游",
                        nowMs,
                    ),
                )
                first = false
            }
        }

        return ResolveResult(order, rejected, errors)
    }

    /** DFS 找出任意一条环路径，形如 [A, B, C, A]。找不到环（理论上不该发生）时返回空。 */
    private fun extractCycle(
        nodes: Set<PluginId>,
        byId: Map<PluginId, PluginManifest>,
    ): List<PluginId> {
        val color = mutableMapOf<PluginId, Int>() // 0 未访问 / 1 在栈上 / 2 已完成
        val stack = mutableListOf<PluginId>()
        var found: List<PluginId>? = null

        fun visit(u: PluginId) {
            color[u] = 1
            stack.add(u)
            val deps = byId[u]?.dependsOn
                ?.map { it.id }
                ?.filter { it in nodes }
                ?.distinct()
                ?.sortedBy { it.value }
                .orEmpty()

            for (v in deps) {
                if (found != null) return
                when (color[v] ?: 0) {
                    0 -> visit(v)
                    1 -> {
                        val start = stack.indexOf(v)
                        if (start >= 0) found = stack.subList(start, stack.size).toList() + v
                        return
                    }
                }
            }
            if (found == null) {
                stack.removeAt(stack.size - 1)
                color[u] = 2
            }
        }

        for (n in nodes.sortedBy { it.value }) {
            if ((color[n] ?: 0) == 0) {
                visit(n)
                if (found != null) break
            }
        }
        return found.orEmpty()
    }
}