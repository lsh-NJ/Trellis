package dev.trellis.kernel.core

/** 插件身份。反向域名，例如 dev.trellis.core.plan */
@JvmInline
value class PluginId(val value: String) {
    override fun toString(): String = value
}

/** 特权能力标识。见设计文档 6 */
@JvmInline
value class CapabilityId(val value: String) {
    override fun toString(): String = value
}

/** 扩展点坐标。见设计文档 5 */
@JvmInline
value class ExtensionPointId(val value: String) {
    override fun toString(): String = value
}