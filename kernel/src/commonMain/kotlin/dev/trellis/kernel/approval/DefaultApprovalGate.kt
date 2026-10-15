package dev.trellis.kernel.approval

import dev.trellis.kernel.audit.AuditEntry
import dev.trellis.kernel.audit.AuditLedger
import dev.trellis.kernel.audit.AuditOutcome
import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.time.KernelClock

/**
 * 默认审批门。三条规则：
 *
 *  1. 自动放行 -> 写审计（`NOTIFY` 级别的动作必须留痕）
 *  2. 需要人工 -> 有界面就问，没界面就**拒绝**
 *  3. 任何结果都写审计，且审计由内核写，插件碰不到
 *
 * 第 2 条的后半句是重点：**没有批准界面  默认批准**。
 * 这是安全默认值，与 6.1「默认拒绝」是同一条原则。
 */
class DefaultApprovalGate(
    private val ledger: AuditLedger,
    private val clock: KernelClock,
) : ApprovalGate {

    private var handler: ManualApprovalHandler? = null

    /** UI 层启动时注册。可以在运行期替换（比如窗口关了）。 */
    fun setManualHandler(handler: ManualApprovalHandler?) {
        this.handler = handler
    }

    override fun review(request: ActionRequest): ApprovalDecision = RiskPolicy.classify(request)

    override suspend fun request(request: ActionRequest): ApprovalOutcome =
        when (val decision = review(request)) {
            is ApprovalDecision.Auto -> {
                record(
                    request,
                    AuditOutcome.APPROVED_AUTO,
                    "自动放行（" + decision.risk + "）：" + request.action,
                )
                ApprovalOutcome.Approved(request, decision.risk, automatic = true)
            }

            is ApprovalDecision.NeedsManual -> {
                val current = handler
                if (current == null) {
                    record(
                        request,
                        AuditOutcome.DENIED,
                        "需要人工批准，但当前没有可用的批准界面，按安全默认值拒绝：" + request.action,
                    )
                    ApprovalOutcome.Denied(
                        request,
                        "「" + request.action + "」需要你亲自确认，但当前没有可用的确认界面，因此被拒绝",
                    )
                } else {
                    val approved = current.ask(request)
                    if (approved) {
                        record(
                            request,
                            AuditOutcome.APPROVED_MANUAL,
                            "人工批准：" + request.action,
                        )
                        ApprovalOutcome.Approved(request, decision.risk, automatic = false)
                    } else {
                        record(request, AuditOutcome.DENIED, "用户拒绝了：" + request.action)
                        ApprovalOutcome.Denied(request, "你拒绝了这个操作")
                    }
                }
            }
        }

    private fun record(request: ActionRequest, outcome: AuditOutcome, detail: String) {
        ledger.append(
            AuditEntry(
                atMs = clock.nowMs(),
                actor = request.actor,
                action = request.action,
                pluginId = request.pluginId,
                capability = request.capability,
                detail = detail + " | 能力：" + Capabilities.describe(request.capability) +
                    " | 预览：" + request.summary,
                outcome = outcome,
            ),
        )
    }
}