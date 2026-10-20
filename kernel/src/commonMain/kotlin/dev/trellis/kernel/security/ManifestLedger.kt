package dev.trellis.kernel.security

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.plugin.PluginManifest

/**
 * 记住每个插件 manifest 的指纹。见设计文档 6.1 规则 3 与不变量 I8。
 *
 * 这是「插件无法自我提权」的技术实现：安装时把指纹落库，
 * 之后每次启动都重新算一遍。插件偷偷把 capabilities 从 [] 改成 [plugin_admin]，
 * 指纹就对不上，直接拒绝加载  **不靠约定，靠哈希**。
 */
interface ManifestLedger {

    /** 返回之前记下的指纹；从没见过则返回 null。 */
    fun fingerprintOf(pluginId: PluginId): String?

    fun remember(pluginId: PluginId, fingerprint: String)

    /**
     * 忘掉这个插件的指纹。「彻底删除」时调用。
     *
     * 不忘的话，用户删掉插件、再用同一个 id 生成一个能力清单不同的新插件，
     * 会被当成「插件被篡改」永久拒装 —— 而实际上什么都没篡改，就是重做了一个。
     */
    fun forget(pluginId: PluginId)
}

class InMemoryManifestLedger : ManifestLedger {

    private val fingerprints = mutableMapOf<PluginId, String>()

    override fun fingerprintOf(pluginId: PluginId): String? = fingerprints[pluginId]

    override fun remember(pluginId: PluginId, fingerprint: String) {
        fingerprints[pluginId] = fingerprint
    }

    override fun forget(pluginId: PluginId) {
        fingerprints.remove(pluginId)
    }
}

/**
 * 参与指纹计算的字段 = **能改变插件权力或加载行为的字段**。
 *
 * 刻意不含 name / description：改个显示名不该触发「插件被篡改」。
 * 也刻意不含 version：升版本走的是正常的安装流程，那是另一条路径。
 * 只盯着 capabilities / dependsOn  **能让插件多拿一点东西的字段**。
 */
internal fun manifestFingerprint(manifest: PluginManifest): String {
    val canonical = buildString {
        append(manifest.id.value).append('\u0000')
        append(manifest.sdkRange).append('\u0000')
        append(manifest.loader).append('\u0000')
        append(manifest.required).append('\u0000')
        append(manifest.builtin).append('\u0000')
        manifest.capabilities.map { it.value }.sorted().forEach { append(it).append(',') }
        append('\u0000')
        manifest.dependsOn
            .map { it.id.value + "@" + it.range + (if (it.optional) "?" else "") }
            .sorted()
            .forEach { append(it).append(',') }
    }
    return fnv1a64(canonical)
}

/** FNV-1a 64 位。选它是因为跨平台结果完全一致，且不依赖任何平台库。 */
private fun fnv1a64(text: String): String {
    var hash = -3750763034362895579L
    for (ch in text) {
        hash = hash xor ch.code.toLong()
        hash *= 1099511628211L
    }
    return hash.toULong().toString(16).padStart(16, '0')
}