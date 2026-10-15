package dev.trellis.kernel.capability

import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId

/**
 * 授权记录。
 *
 * 刻意**不随插件快照回滚**  那是用户意图，不是软件状态。
 * 回滚插件版本时顺手把「用户点过的允许」也撤掉，是很讨人厌的行为。
 */
interface GrantStore {

    fun grantedTo(pluginId: PluginId): Set<CapabilityId>

    fun grant(pluginId: PluginId, capabilities: Set<CapabilityId>)

    fun revokeAll(pluginId: PluginId)

    /**
     * 用户是否已经就这个插件的能力清单做过决定（批准或拒绝）。
     * 有它才不会每次启动都弹一遍同样的授权框  那是一种骚扰。
     */
    fun isDecided(pluginId: PluginId): Boolean

    fun markDecided(pluginId: PluginId)
}

class InMemoryGrantStore : GrantStore {

    private val grants = mutableMapOf<PluginId, MutableSet<CapabilityId>>()

    override fun grantedTo(pluginId: PluginId): Set<CapabilityId> =
        grants[pluginId]?.toSet() ?: emptySet()

    override fun grant(pluginId: PluginId, capabilities: Set<CapabilityId>) {
        grants.getOrPut(pluginId) { mutableSetOf() }.addAll(capabilities)
    }

    override fun revokeAll(pluginId: PluginId) {
        grants.remove(pluginId)
        decided.remove(pluginId)
    }

    private val decided = mutableSetOf<PluginId>()

    override fun isDecided(pluginId: PluginId): Boolean = pluginId in decided

    override fun markDecided(pluginId: PluginId) {
        decided.add(pluginId)
    }
}

/**
 * 首次遇到未授权的能力时，向用户问一次。UI 层实现它。
 *
 * 没注册实现时**默认拒绝**  这是安全默认值：宁可能力没生效，
 * 也不能因为「没人来问」就默认放行。
 */
fun interface GrantDialog {
    /** 返回用户批准的能力子集。 */
    suspend fun ask(
        pluginId: PluginId,
        requested: Set<CapabilityId>,
        alreadyGranted: Set<CapabilityId>,
    ): Set<CapabilityId>
}