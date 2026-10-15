package dev.trellis.feature.plan

import dev.trellis.domain.PlanService
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest

/**
 * 计划与任务的实现插件。
 *
 * 三个标记值得注意：
 *  - `builtin = true`     编译进 App 包，不是从磁盘加载的
 *  - `required = true`    不可卸载；它挂掉时内核转 DEGRADED，而不是若无其事地继续
 *  - `loader = "builtin"` 但**走的是和其他插件完全相同的生命周期**  没有后门
 *
 * 它对外只暴露 [PlanService] 这一个接口。第三方插件想读写任务必须经过它，
 * 而不是自己翻存储  这就是设计文档 7 规则 4「领域数据不直连 DB」。
 */
class PlanCorePlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("core.plan"),
        version = SemVer.parse("1.0.0"),
        name = "计划核心",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        required = true,
        contributes = mapOf(
            KernelPoints.Service.id to listOf(ContributionDecl("PlanService")),
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        ctx.extensions.publish(
            PlanService::class,
            KvPlanService(ctx.kv, ctx.clock) { message -> ctx.log(message) },
        )
    }
}
