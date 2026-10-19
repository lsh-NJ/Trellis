package dev.trellis.dsl

import kotlinx.serialization.Serializable

/**
 * 声明式插件的磁盘结构。见设计文档附录 A。
 *
 * 这些类是**对外协议**：Agent 会照着它生成 JSON。所以：
 *  - 真正必须的字段不给默认值  缺了就让解析器直接报「Field 'id' is required」，
 *    而不是悄悄变成空字符串然后在别处出诡异的错；
 *  - 其余字段给合理默认值，让 Agent 少写一点。
 *
 * 解析用的是严格模式（ignoreUnknownKeys = false）：agent 把 "title" 拼成 "tittle"
 * 时必须炸出来，而不是静默渲染一张空白卡片。
 */
@Serializable
internal data class DslManifestJson(
    val id: String,
    val version: String,
    val schemaVersion: Int = 1,
    val name: String = "",
    val description: String = "",
    val usage: String = "",
    val limitation: String = "",
    val sdkRange: String = "*",
    val loader: String = "dsl",
    val builtin: Boolean = false,
    val required: Boolean = false,
    val dependsOn: List<DslRequirementJson> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val entry: String = "main.dsl.json",
)

@Serializable
internal data class DslRequirementJson(
    val id: String,
    val range: String = "*",
    val optional: Boolean = false,
)

@Serializable
internal data class DslEntryJson(
    val schemaVersion: Int = 1,
    val slots: List<DslSlotJson> = emptyList(),
)

@Serializable
internal data class DslSlotJson(
    val point: String,
    val id: String,
    val order: Int = 100,
    val title: String = "",
    val body: String = "",
)