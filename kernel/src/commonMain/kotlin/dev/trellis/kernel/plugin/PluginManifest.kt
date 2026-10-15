package dev.trellis.kernel.plugin

import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange

data class PluginRequirement(
    val id: PluginId,
    val range: VersionRange = VersionRange.ANY,
    val optional: Boolean = false,
)

data class ContributionDecl(
    val id: String,
    val order: Int = 100,
    val payload: String? = null,
)

data class MigrationDecl(
    val version: Int,
    val sql: String,
)

/**
 * 插件身份证。见设计文档 4.2。
 *
 * 安装时 [manifestHash] 会被落库冻结，每次启动校验  这是「Agent 无法自我提权」的技术实现。
 */
data class PluginManifest(
    val id: PluginId,
    val version: SemVer,
    val name: String,
    val sdkRange: VersionRange = VersionRange.ANY,
    val loader: String = "builtin",
    val dependsOn: List<PluginRequirement> = emptyList(),
    val capabilities: Set<CapabilityId> = emptySet(),
    val contributes: Map<ExtensionPointId, List<ContributionDecl>> = emptyMap(),
    val entry: String? = null,
    val migrations: List<MigrationDecl> = emptyList(),
    val builtin: Boolean = false,
    val required: Boolean = false,
    val manifestHash: String = "",
) {
    override fun toString(): String = "${id.value}@$version"
}