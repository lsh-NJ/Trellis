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
    /**
     * 一句大白话说这个插件干什么。**不参与指纹**，改它不会让插件失效。
     */
    val description: String = "",
    /**
     * 怎么用：在哪看、什么时候用。
     *
     * 为什么这个字段重要：一个插件装上了，用户并不能自动知道
     * 「它长什么样、去哪儿找」。没有这句话，用户只能反复开关插件玩找不同。
     */
    val usage: String = "",
    /**
     * 这个插件**少做了什么**。留空表示完全按用户说的做了。
     *
     * 用户要「安排复习」、插件只做了「显示数量」时，这句话必须写出来。
     * 不写的话，用户会以为这就是他要的东西，然后觉得这个插件很没用 ——
     * 而实际上插件作者（可能是 AI）心里清楚自己退了一步，只是没说。
     */
    val limitation: String = "",
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