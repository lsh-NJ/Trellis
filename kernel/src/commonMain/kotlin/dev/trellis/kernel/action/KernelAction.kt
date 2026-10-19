package dev.trellis.kernel.action

import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.extension.OrderedContribution

/** 一个动作的参数说明。给界面和生成器看，说明「这个动作要填什么」。 */
data class ActionParam(val name: String, val description: String, val required: Boolean = true)

/** 动作拿到的参数。缺的参数不会抛，只有显式 [require] 才会。 */
class ActionArgs(private val raw: Map<String, String>) {
    fun get(name: String): String? = raw[name]?.trim()?.takeIf { it.isNotEmpty() }
    fun require(name: String): String = get(name) ?: throw MissingActionArg(name)
    fun all(): Map<String, String> = raw
}

class MissingActionArg(val name: String) : Exception("动作缺少参数「" + name + "」")

/**
 * 一个**原子动作**：由宿主实现，插件只能「选一个 + 填参数」。
 *
 * 这是整套「让插件做事」的关键约束：
 * 插件**不写代码**，它只是把宿主已经实现好的动作排个序、填上参数。
 * 于是安全、跨端、可审查这三件事同时成立：
 *
 *  - 安全：动作是宿主写的，插件发明不出新的
 *  - 跨端：动作由各端各自实现同一个 id，插件不需要关心
 *  - 可审查：不用跑就能列出「这个插件会新建任务、会发通知」
 */
interface KernelAction : OrderedContribution {

    /** 给人看的名字，例如「新建任务」。 */
    val label: String

    /** 要填哪些参数。 */
    val params: List<ActionParam>

    /** 执行它需要什么能力。null 表示不需要额外能力。 */
    val capability: CapabilityId?

    /**
     * 真正执行。返回一句人话描述「做了什么」，会写进审计。
     * 失败就抛异常，异常信息同样会被记下来。
     */
    suspend fun execute(args: ActionArgs): String
}
