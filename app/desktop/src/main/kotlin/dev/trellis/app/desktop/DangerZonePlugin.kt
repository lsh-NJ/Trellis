package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.trellis.domain.PlanService
import dev.trellis.domain.TaskQuery
import dev.trellis.domain.TaskStatus
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
import kotlinx.coroutines.launch

/**
 * 演示「危险操作的自动审查 + 手动批准」。
 *
 * 这条链路有两道闸，缺一不可：
 *
 *  1. **能力**（`ctx.capabilities`） 「你有没有删数据的权力？」
 *     由 manifest 声明 + 用户授权决定。装的时候问一次，之后记住。
 *
 *  2. **审批门**（`ctx.approvals`） 「你这一次要删，准不准？」
 *     由能力的危险等级 + 谁发起的决定；不可逆的操作每次都要人工点头。
 *
 * 少了第 1 道，插件可以为所欲为；少了第 2 道，一次误点就把数据没了。
 */
class DangerZonePlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("dev.danger"),
        version = SemVer.parse("1.0.0"),
        name = "危险操作",
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

                Column {
                    Button(
                        enabled = canDelete,
                        onClick = {
                            scope.launch {
                                result = try {
                                    // 第一道闸：我真的有这个权力吗
                                    ctx.capabilities.require(Capabilities.DeleteData.id)

                                    // 第二道闸：这一次要不要人点头  界面会弹出来
                                    when (val outcome = ctx.approvals.request(
                                        Capabilities.DeleteData.id,
                                        "data.delete",
                                        "清空所有已完成的待办",
                                    )) {
                                        is ApprovalOutcome.Approved -> {
                                            val done = plan.tasks(TaskQuery(status = TaskStatus.DONE))
                                            done.forEach { plan.deleteTask(it.id) }
                                            "已清空 " + done.size + " 条" +
                                                if (outcome.automatic) "（自动放行）" else "（你批准了）"
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
                    if (result.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(result, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
        )
    }
}
