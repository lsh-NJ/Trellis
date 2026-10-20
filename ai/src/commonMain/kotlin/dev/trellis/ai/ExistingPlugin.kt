package dev.trellis.ai

/**
 * 已经装着的插件，给模型看的极简版。
 *
 * 只给 id / 名字 / 一句话，**不给它的 JSON 源码**。原因是：
 * 给源码会诱导模型去「改造」已经装好的那个，而它根本没有改插件的能力，
 * 结果只能造一个新的近似物 —— 正好是我们要防的重复。
 */
data class ExistingPlugin(
    val id: String,
    val name: String,
    val description: String,
)
