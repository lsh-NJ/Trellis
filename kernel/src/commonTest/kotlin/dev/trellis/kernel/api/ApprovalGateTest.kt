package dev.trellis.kernel.api

import dev.trellis.kernel.approval.ActionRequest
import dev.trellis.kernel.approval.ApprovalDecision
import dev.trellis.kernel.approval.ApprovalOutcome
import dev.trellis.kernel.approval.DefaultApprovalGate
import dev.trellis.kernel.approval.ManualApprovalHandler
import dev.trellis.kernel.audit.Actor
import dev.trellis.kernel.audit.AuditOutcome
import dev.trellis.kernel.audit.InMemoryAuditLedger
import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val PLUGIN = PluginId("dev.a")

private fun request(
    capability: CapabilityId,
    actor: Actor = Actor.USER,
    action: String = "data.delete",
) = ActionRequest(PLUGIN, actor, capability, action, "删除 3 条记录")

private fun gate(
    handler: ManualApprovalHandler? = null,
): Pair<DefaultApprovalGate, InMemoryAuditLedger> {
    val ledger = InMemoryAuditLedger()
    val g = DefaultApprovalGate(ledger, FakeClock(1_000L))
    g.setManualHandler(handler)
    return g to ledger
}

internal class ContextProbe(override val manifest: PluginManifest) : Plugin {
    var captured: PluginContext? = null
        private set

    override suspend fun init(ctx: PluginContext) {
        captured = ctx
    }
}

class RiskPolicyTest {

    @Test
    fun readOnlyUserActionIsAuto() {
        val (g, _) = gate()
        val d = g.review(request(Capabilities.ReadData.id))
        assertTrue(d is ApprovalDecision.Auto, d.toString())
        assertEquals(dev.trellis.kernel.approval.RiskLevel.SAFE, d.risk)
    }

    @Test
    fun reversibleWriteByUserIsAutoButAudited() {
        val (g, _) = gate()
        val d = g.review(request(Capabilities.Notify.id))
        assertTrue(d is ApprovalDecision.Auto, d.toString())
        assertEquals(dev.trellis.kernel.approval.RiskLevel.NOTIFY, d.risk)
    }

    @Test
    fun irreversibleUserActionNeedsManualApproval() {
        val (g, _) = gate()
        val d = g.review(request(Capabilities.DeleteData.id))
        assertTrue(d is ApprovalDecision.NeedsManual, d.toString())
    }

    @Test
    fun agentInitiatedActionsAreEscalatedOneLevel() {
        val (g, _) = gate()
        // 只读对用户是 SAFE，对智能体升到 NOTIFY
        val readByAgent = g.review(request(Capabilities.ReadData.id, actor = Actor.AGENT))
        assertTrue(readByAgent is ApprovalDecision.Auto)
        assertEquals(dev.trellis.kernel.approval.RiskLevel.NOTIFY, readByAgent.risk)

        // 可逆写对用户是自动放行，对智能体升到必须人工
        val writeByAgent = g.review(request(Capabilities.Notify.id, actor = Actor.AGENT))
        assertTrue(
            writeByAgent is ApprovalDecision.NeedsManual,
            "同一动作，人做自动放行、智能体做必须人工点头：" + writeByAgent,
        )
    }

    @Test
    fun anUnknownCapabilityIsTreatedAsTheMostDangerousOne() {
        val (g, _) = gate()
        val d = g.review(request(CapabilityId("teleport")))
        assertTrue(d is ApprovalDecision.NeedsManual, "描述不出来的东西，必须问，而不是放行")
    }
}

class ApprovalFlowTest {

    @Test
    fun autoApprovedActionIsRecordedInTheAuditLog() = runTest {
        val (g, ledger) = gate()
        val outcome = g.request(request(Capabilities.ReadData.id))

        assertTrue(outcome is ApprovalOutcome.Approved, outcome.toString())
        assertTrue(outcome.automatic)
        assertEquals(1, ledger.entries().size)
        assertEquals(AuditOutcome.APPROVED_AUTO, ledger.entries().first().outcome)
        assertEquals(PLUGIN, ledger.entries().first().pluginId)
    }

    @Test
    fun dangerousActionWithNoApprovalUiIsDeniedByDefault() = runTest {
        val (g, ledger) = gate(handler = null)
        val outcome = g.request(request(Capabilities.DeleteData.id))

        assertTrue(outcome is ApprovalOutcome.Denied, outcome.toString())
        assertEquals(AuditOutcome.DENIED, ledger.entries().first().outcome)
        assertTrue(
            ledger.entries().first().detail.contains("没有可用的批准界面"),
            ledger.entries().first().detail,
        )
    }

    @Test
    fun dangerousActionIsApprovedOnlyWhenTheUserSaysYes() = runTest {
        var shown: ActionRequest? = null
        val (g, ledger) = gate(ManualApprovalHandler { req ->
            shown = req
            true
        })
        val outcome = g.request(request(Capabilities.DeleteData.id))

        assertTrue(outcome is ApprovalOutcome.Approved, outcome.toString())
        assertEquals(false, (outcome as ApprovalOutcome.Approved).automatic)
        assertEquals("删除 3 条记录", shown?.summary, "给用户看的必须是能读懂的话，不是动作名")
        assertEquals(AuditOutcome.APPROVED_MANUAL, ledger.entries().first().outcome)
    }

    @Test
    fun userRefusalIsRecordedAndReported() = runTest {
        val (g, ledger) = gate(ManualApprovalHandler { false })
        val outcome = g.request(request(Capabilities.DeleteData.id))

        assertTrue(outcome is ApprovalOutcome.Denied, outcome.toString())
        assertEquals(AuditOutcome.DENIED, ledger.entries().first().outcome)
    }

    @Test
    fun theAuditLogDistinguishesAgentFromUser() = runTest {
        val (g, ledger) = gate(ManualApprovalHandler { true })
        g.request(request(Capabilities.DeleteData.id, actor = Actor.USER, action = "user.delete"))
        g.request(request(Capabilities.DeleteData.id, actor = Actor.AGENT, action = "agent.delete"))

        val actors = ledger.entries().map { it.actor }
        assertEquals(listOf(Actor.USER, Actor.AGENT), actors, "审计必须能回答「这是谁干的」")
    }

    @Test
    fun aPluginCanReachTheGateThroughItsContext() = runTest {
        val probe = ContextProbe(m2Manifest("dev.a"))
        val (g, ledger) = gate(ManualApprovalHandler { true })
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(probe), listOf(PluginId("dev.a")))).copy(approvalGate = g),
        )

        val ctx = probe.captured ?: error("插件没拿到 context")
        val outcome = ctx.approvals.request(Capabilities.ReadData.id, "data.read", "读取你的计划")

        assertTrue(outcome is ApprovalOutcome.Approved, outcome.toString())
        assertEquals(PLUGIN, ledger.entries().first().pluginId, "审计里的插件 id 必须由内核补，不能靠插件自报")
    }

    @Test
    fun agentCallThroughTheContextIsEscalated() = runTest {
        val probe = ContextProbe(m2Manifest("dev.a"))
        var asked = 0
        val (g, _) = gate(ManualApprovalHandler { asked++; false })
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(probe), listOf(PluginId("dev.a")))).copy(approvalGate = g),
        )
        val ctx = probe.captured ?: error("插件没拿到 context")

        // 同一个能力：走 request 自动放行，走 requestAsAgent 必须问人
        val byUser = ctx.approvals.request(Capabilities.Notify.id, "x", "")
        assertTrue(byUser is ApprovalOutcome.Approved && asked == 0, byUser.toString())

        val byAgent = ctx.approvals.requestAsAgent(Capabilities.Notify.id, "x", "")
        assertTrue(byAgent is ApprovalOutcome.Denied)
        assertEquals(1, asked)
    }
}
