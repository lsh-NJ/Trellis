package dev.trellis.kernel.approval

import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.capability.CapabilityKind

/**
 * 风险判定。见设计文档 6.2 的风险判定表。
 *
 * 判定依据只有两个：**能力的危险等级** + **谁发起的**。
 * 刻意不接受调用方传入「我这个操作可逆吗」这类自述  那种自证毫无意义。
 */
object RiskPolicy {

    fun classify(request: ActionRequest): ApprovalDecision {
        val capability = Capabilities.byId(request.capability)

        // 内核不认识的能力：一律当最危险处理。
        // 理由：对一个描述不出来的东西，用户没有做出知情决定的依据，
        // 那就必须问  而不是因为「不认识」就放行。
        var level = capability?.kind?.baseRisk() ?: RiskLevel.DANGEROUS

        if (request.actor == Actor.AGENT) level = level.escalate()

        return when (level) {
            RiskLevel.SAFE, RiskLevel.NOTIFY -> ApprovalDecision.Auto(level)
            RiskLevel.DANGEROUS -> ApprovalDecision.NeedsManual(level)
        }
    }

    private fun CapabilityKind.baseRisk(): RiskLevel = when (this) {
        CapabilityKind.READ_ONLY -> RiskLevel.SAFE
        CapabilityKind.MUTATING -> RiskLevel.NOTIFY
        CapabilityKind.DESTRUCTIVE -> RiskLevel.DANGEROUS
    }

    /** 智能体发起的动作升一级：用户点删除是明确意图，智能体说删除可能只是幻觉。 */
    private fun RiskLevel.escalate(): RiskLevel = when (this) {
        RiskLevel.SAFE -> RiskLevel.NOTIFY
        RiskLevel.NOTIFY -> RiskLevel.DANGEROUS
        RiskLevel.DANGEROUS -> RiskLevel.DANGEROUS
    }
}