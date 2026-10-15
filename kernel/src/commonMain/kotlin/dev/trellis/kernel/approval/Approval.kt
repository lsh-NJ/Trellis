package dev.trellis.kernel.approval

import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId

enum class RiskLevel {
    /** 只读且已授权：直接放行。 */
    SAFE,

    /** 可逆的写操作：放行，但要留审计。 */
    NOTIFY,

    /** 不可逆：必须人工点头。 */
    DANGEROUS,
}

data class ActionRequest(
    val pluginId: PluginId,
    val actor: Actor,
    val capability: CapabilityId,
    /** 语义化动作名，例如 data.delete。用于审计与策略，不是给人看的长句。 */
    val action: String,
    /** 给人看的预览：将要发生什么。人工批准时展示的就是它。 */
    val summary: String,
)

/**
 * 「这次动作需不需要人点头」 只是判断，不含结果。
 *
 * 刻意没有 Denied 分支：拒绝是一种**结果**，不是一种**判断**。
 * 判断说「需要人工」，结果才可能是「用户点了拒绝」。把两者混在一个类型里，
 * 就会写出「策略层直接拒绝」这种绕过用户的操作，那是错的。
 */
sealed interface ApprovalDecision {
    val risk: RiskLevel

    data class Auto(override val risk: RiskLevel) : ApprovalDecision

    data class NeedsManual(override val risk: RiskLevel) : ApprovalDecision
}

sealed interface ApprovalOutcome {

    val request: ActionRequest

    data class Approved(
        override val request: ActionRequest,
        val risk: RiskLevel,
        /** true = 自动放行；false = 人工批准。审计里要区分。 */
        val automatic: Boolean,
    ) : ApprovalOutcome

    data class Denied(
        override val request: ActionRequest,
        val reason: String,
    ) : ApprovalOutcome
}

/** 审批门。内核持有，插件通过 [ApprovalBroker] 使用。 */
interface ApprovalGate {

    /** 只判断，不产生副作用，也不写审计。 */
    fun review(request: ActionRequest): ApprovalDecision

    /** 走完整流程：判断 -> （必要时）人工批准 -> 写审计。 */
    suspend fun request(request: ActionRequest): ApprovalOutcome
}

/**
 * 插件看到的那一面。
 *
 * 限制要说清楚：**actor 目前由调用方声明，内核还没有工具调用上下文来强制它。**
 * 也就是说，一个插件可以谎报自己是 USER 来规避风险升级。
 * 谎报会被原样写进审计日志（那是内核写的，插件改不了），但内核此刻拦不住它。
 * 这是已知缺口，留到工具调用框架落地时用「上下文里的 actor」替掉「参数里的 actor」。
 */
interface ApprovalBroker {

    /** 用户直接发起的动作。 */
    suspend fun request(capability: CapabilityId, action: String, summary: String): ApprovalOutcome

    /** 由外部智能体发起的动作  风险等级会升一级。 */
    suspend fun requestAsAgent(capability: CapabilityId, action: String, summary: String): ApprovalOutcome
}

fun interface ManualApprovalHandler {
    /** UI 层实现：把 [request] 展示给用户，返回是否批准。 */
    suspend fun ask(request: ActionRequest): Boolean
}

internal class PluginApprovalBroker(
    private val pluginId: PluginId,
    private val gate: ApprovalGate,
) : ApprovalBroker {

    override suspend fun request(
        capability: CapabilityId,
        action: String,
        summary: String,
    ): ApprovalOutcome = gate.request(ActionRequest(pluginId, Actor.USER, capability, action, summary))

    override suspend fun requestAsAgent(
        capability: CapabilityId,
        action: String,
        summary: String,
    ): ApprovalOutcome = gate.request(ActionRequest(pluginId, Actor.AGENT, capability, action, summary))
}