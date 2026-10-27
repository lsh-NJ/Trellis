package dev.trellis.app.desktop

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.trellis.domain.TaskQuery
import dev.trellis.domain.TaskStatus
import dev.trellis.domain.PlanService
import dev.trellis.kernel.approval.ApprovalOutcome
import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginRequirement
import dev.trellis.kernel.slot.Slots
import dev.trellis.ui.ComposeSlot
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * 只存在于测试里的「危险操作」夹具。
 *
 * 以前它是个内置插件（`dev.danger`），挂在首页最下面演示两道闸；但它没有任何真实用途，
 * 又占用户一格，所以从产品里删掉了。演示能力、审批门那几条用例仍然需要它，就搬到这里。
 *
 * 两道闸的分工没有变：
 *
 *  1. 能力（`ctx.capabilities`）：「你有没有删数据的权力」，装的时候授权；
 *  2. 审批门（`ctx.approvals`）：「这一次准不准」，不可逆的操作每次都要点头。
 */
class DangerZoneFixturePlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("test.danger"),
        version = SemVer.parse("1.0.0"),
        name = "测试用危险操作",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        capabilities = setOf(Capabilities.DeleteData.id),
        dependsOn = listOf(
            PluginRequirement(PluginId("core.plan"), VersionRange.parse(">=1.0.0")),
        ),
        contributes = mapOf(Slots.HomeSection.id to listOf(ContributionDecl("danger-zone"))),
    )

    override suspend fun init(ctx: PluginContext) {
        val plan = ctx.services.require(PlanService::class)
        ctx.extensions.register(
            Slots.HomeSection,
            ComposeSlot(id = "danger-zone", order = 90) {
                val scope = rememberCoroutineScope()
                var result by remember { mutableStateOf("") }
                val canDelete = ctx.capabilities.has(Capabilities.DeleteData.id)
                Button(
                    enabled = canDelete,
                    onClick = {
                        scope.launch {
                            result = try {
                                ctx.capabilities.require(Capabilities.DeleteData.id)
                                when (val outcome = ctx.approvals.request(
                                    Capabilities.DeleteData.id,
                                    "data.delete",
                                    "清空所有已完成的待办",
                                )) {
                                    is ApprovalOutcome.Approved -> {
                                        val done = plan.tasks(TaskQuery(status = TaskStatus.DONE))
                                        done.forEach { plan.deleteTask(it.id) }
                                        "已清空 " + done.size + " 条"
                                    }
                                    is ApprovalOutcome.Denied -> "被拒绝：" + outcome.reason
                                }
                            } catch (t: Throwable) {
                                "缺少权限：" + (t.message ?: "")
                            }
                        }
                    },
                ) {
                    Text(if (canDelete) "清空已完成（危险操作）" else "缺少删除权限")
                }
                if (result.isNotEmpty()) Text(result)
            },
        )
    }
}
