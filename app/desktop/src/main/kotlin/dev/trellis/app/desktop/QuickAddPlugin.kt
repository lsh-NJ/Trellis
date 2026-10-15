package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import dev.trellis.domain.PlanService
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
 * 第一方插件：贡献一个带**行为**的卡片（不是只显示一行字）。
 *
 * 它演示的是 [ComposeSlot] 这条路  只有第一方插件才该用它，
 * 因为 Compose 装不下第三方代码的异常边界（见 SlotArea 的说明）。
 *
 * 它同样只通过 `PlanService` 读写数据，**不碰存储**。
 */
class QuickAddPlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("dev.quickadd"),
        version = SemVer.parse("1.0.0"),
        name = "快捷操作",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        dependsOn = listOf(
            PluginRequirement(PluginId("core.plan"), VersionRange.parse(">=1.0.0")),
        ),
        contributes = mapOf(Slots.HomeSection.id to listOf(ContributionDecl("quick-add"))),
    )

    override suspend fun init(ctx: PluginContext) {
        val plan = ctx.services.require(PlanService::class)

        ctx.extensions.register(
            Slots.HomeSection,
            ComposeSlot(id = "quick-add", order = 10) {
                val scope = rememberCoroutineScope()
                Row(
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp()),
                ) {
                    Button(onClick = {
                        scope.launch { plan.addTask("番茄钟 25 分钟") }
                    }) { Text("加一个番茄钟") }

                    Button(onClick = {
                        scope.launch { plan.addTask("复习昨天的内容") }
                    }) { Text("复习昨天") }
                }
            },
        )
    }
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())
