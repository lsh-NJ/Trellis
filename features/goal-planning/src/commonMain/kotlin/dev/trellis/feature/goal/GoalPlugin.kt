package dev.trellis.feature.goal

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.context.Contexts
import dev.trellis.kernel.slot.Slots

/**
 * 「目标达成规划」插件。
 *
 * 为什么必须是内置 Kotlin 轨道（另外两条轨道做不到）：
 * 1. 它要往 AI 上下文里交一段**随状态变化**的事实，声明式没有代码；
 * 2. 它要常驻对话页并跨天结算，脚本轨道的沙箱没有时钟、也不能定时；
 * 3. 它必须**不可卸载** —— 一旦被卸载，用户攒了三个月的目标契约会看起来凭空消失。
 *
 * [PluginManifest.required] = true 让内核直接拒绝卸载（`UninstallResult.Rejected("必需插件不可卸载")`），
 * 这是内核层的硬闸，不是界面上把按钮变灰。唯一的退出口是「删除这份规划」：
 * 清业务数据、插件本体保留、状态回 EMPTY。
 */
class GoalPlugin(
    private val dailyMinutes: () -> Int,
    private val today: () -> String,
    private val onOpenChat: () -> Unit,
    /**
     * 今天的实际投入（活动记录）。没接活动采集 / 今天没同步过就返回 null。
     *
     * 用 lambda 注入而不是 import `:features:activity`：插件和「今天干了什么」的数据源
     * 之间不应该是硬依赖 —— 换一个活动采集方案，这里一行都不用改。
     */
    private val activityEffort: suspend () -> TodayEffort? = { null },
) : Plugin {

    private var service: GoalService? = null

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId(ID),
        version = SemVer.parse("1.0.0"),
        name = "目标达成规划",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "builtin",
        builtin = true,
        // 不可卸载：它持有用户的长期目标契约，卸载等于让三个月的上下文凭空消失。
        required = true,
        description = "把目标固化成一份可追溯的契约，每天只排今天和明天两件事，并把压缩过的进度带进对话",
        usage = "对话页右侧是常驻条（目标 / 今天几件 / 明天预览）；首页卡片可以勾今天的事；" +
            "设置页可以改复盘周期、推送时间和删除这份规划",
        limitation = "推送仅在应用运行时生效；关闭期间不推送，下次启动补一条。",
        contributes = mapOf(
            Slots.ChatAside.id to listOf(ContributionDecl("goal-bar", order = 40)),
            Slots.HomeSection.id to listOf(ContributionDecl("goal-today", order = 30)),
            Slots.SettingsSection.id to listOf(ContributionDecl("goal-settings", order = 30)),
            Contexts.Host.id to listOf(ContributionDecl("goal-context", order = 20)),
        ),
    )

    override suspend fun init(ctx: PluginContext) {
        val kit = GoalKit(ctx.kv)
        // 时间一律走 ctx.clock：插件里出现 System.currentTimeMillis()，
        // 「跳到次日补推一次」这条测试就永远只能靠改系统时间。
        val goalService = GoalService(kit, ctx.clock, dailyMinutes, today, activityEffort)
        service = goalService

        ctx.extensions.register(
            Slots.ChatAside,
            GoalAsideSlot(goalService, today, onOpenChat),
        )
        ctx.extensions.register(
            Slots.HomeSection,
            GoalTodaySlot(goalService, today),
        )
        ctx.extensions.register(
            Slots.SettingsSection,
            GoalSettingsSlot(goalService, dailyMinutes),
        )
        ctx.extensions.register(
            Contexts.Host,
            GoalContextSection(goalService, today),
        )
    }

    /** 给宿主读状态用（截图、诊断）；没初始化时返回 null。 */
    fun serviceOrNull(): GoalService? = service

    companion object {
        const val ID = "core.goal"

        /** AI 能看到的插件 id（工具报错时会回显它）。 */
        val PLUGIN_ID = PluginId(ID)
    }
}
