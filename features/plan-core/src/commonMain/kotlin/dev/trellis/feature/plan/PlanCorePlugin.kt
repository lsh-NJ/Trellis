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
            // 给声明式插件用的数据源，命名空间是 summary
            KernelPoints.Data.id to listOf(ContributionDecl("summary")),
            KernelPoints.Event.id to listOf(ContributionDecl(TaskCompletedEvent.TYPE)),
            KernelPoints.Action.id to listOf(
                ContributionDecl("addTask"),
                ContributionDecl("logTime"),
                ContributionDecl("completeTask"),
                ContributionDecl("renameTask"),
                ContributionDecl("rescheduleTask"),
                ContributionDecl("deleteTask"),
            ),
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        val service = KvPlanService(ctx.kv, ctx.clock, { message -> ctx.log(message) }) { event ->
            ctx.events.emit(event)
        }
        ctx.extensions.publish(PlanService::class, service)

        // 同一个 service 换个面：把统计数字暴露给声明式插件
        ctx.extensions.register(KernelPoints.Data, PlanSummaryDataProvider(service, ctx.clock))

        // 原子动作：自动生成的插件可以选它们，但发明不出新的
        ctx.extensions.register(KernelPoints.Action, AddTaskAction(service, ctx.clock))
        ctx.extensions.register(KernelPoints.Action, LogTimeAction(service))
        ctx.extensions.register(KernelPoints.Action, CompleteTaskAction(service))
        ctx.extensions.register(KernelPoints.Action, RenameTaskAction(service, ctx.clock))
        ctx.extensions.register(KernelPoints.Action, RescheduleTaskAction(service, ctx.clock))
        ctx.extensions.register(KernelPoints.Action, DeleteTaskAction(service, ctx.clock))

        // 让规则知道有哪些事件可挂。不登记的话，模型只能猜事件名。
        ctx.extensions.register(KernelPoints.Event, TaskCompletedEvent)
    }
}
