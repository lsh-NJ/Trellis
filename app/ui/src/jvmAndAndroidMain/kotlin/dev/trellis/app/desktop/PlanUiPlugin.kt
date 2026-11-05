package dev.trellis.app.desktop

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

/**
 * 任务界面的「壳」本身也是一个内置插件。
 *
 * 为什么要把宿主自己的两个 composable（今日盘点 + 待办清单）变成插件卡片：
 * 只要它们写在宿主的布局里，agent 就永远只能排别的卡片、改别的卡片样式，
 * 用户说「把今日盘点挪到右边」「这块太挤了」时只能得到「做不到」。
 *
 * 变成 `dev.planui` 的两个贡献之后：
 *  - `list_cards` 能看到 `slot.home.header` 的 `plan-header` 和
 *    `slot.home.section` 的 `plan-board`；
 *  - `arrange_cards` 能把它们跟插件卡片排进同一行（按 12 栏跨栏）；
 *  - `set_plugin_card` 能单独调整它们的卡片外观。
 *
 * 代价是：宿主不再直接调用 [Header]、[TaskBoard]，改用 `SlotArea` 渲染它们。
 * 这两个 composable 仍留在 AppScreen.kt 里（`internal`），插件只是转手注册。
 */
internal class PlanUiPlugin(private val runtime: AppRuntime) : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("dev.planui"),
        version = SemVer.parse("1.0.0"),
        name = "任务界面",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        description = "今日盘点与待办清单；交给插件系统托管，agent 才能调整它们的位置和样式",
        dependsOn = listOf(
            PluginRequirement(PluginId("core.plan"), VersionRange.parse(">=1.0.0")),
        ),
        contributes = mapOf(
            Slots.HomeHeader.id to listOf(ContributionDecl("plan-header")),
            Slots.HomeSection.id to listOf(ContributionDecl("plan-board")),
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        // framed = false：SlotCard 已经画了卡片外壳，内容里不要再套一层。
        ctx.extensions.register(
            Slots.HomeHeader,
            ComposeSlot(id = "plan-header", order = 80) {
                Header(runtime, framed = false)
            },
        )
        ctx.extensions.register(
            Slots.HomeSection,
            ComposeSlot(id = "plan-board", order = 95) {
                TaskBoard(runtime, runtime.taskFocus, framed = false)
            },
        )
    }
}
