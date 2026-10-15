package dev.trellis.demo

import dev.trellis.domain.PlanService
import dev.trellis.dsl.DslLoader
import dev.trellis.dsl.JvmPluginFileSystem
import dev.trellis.feature.plan.PlanCorePlugin
import dev.trellis.kernel.api.InstallResult
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.api.PluginInfo
import dev.trellis.kernel.api.UninstallResult
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginRequirement
import dev.trellis.kernel.slot.ConsoleSlotRenderer
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.slot.SlotHost
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TextSlot
import dev.trellis.kernel.spi.BuiltinLoader
import dev.trellis.kernel.spi.DiscoveredPlugin
import dev.trellis.kernel.spi.DiscoveryResult
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.spi.PluginDiscovery
import dev.trellis.kernel.spi.PluginLoader
import java.io.File
import kotlinx.coroutines.runBlocking

private const val HOUR = 60L * 60L * 1000L
private const val DAY = 24L * HOUR
private const val RULE = "--------------------------------------------------------------------------"

private fun banner(title: String) {
    println()
    println("=".repeat(74))
    println("  $title")
    println("=".repeat(74))
}

private fun show(host: SlotHost, title: String) {
    println()
    println("  [ " + title + " ]")
    val lines = host.render(Slots.HomeHeader)
    if (lines.isEmpty()) println("    (空)") else lines.forEach { println("    " + it) }
}

private fun showPlugins(label: String, plugins: List<PluginInfo>) {
    println()
    println("  [ " + label + " ]")
    println("  " + RULE)
    for (p in plugins) println("    " + p.manifest.id.value.padEnd(14) + p.state)
}

private fun writePlugin(root: File, directory: String, manifestJson: String, entryJson: String) {
    val dir = File(root, directory)
    dir.mkdirs()
    File(dir, "manifest.json").writeText(manifestJson, Charsets.UTF_8)
    File(dir, "main.dsl.json").writeText(entryJson, Charsets.UTF_8)
}

/**
 * 「目录 vs 启用集」：目录里放着所有已安装的插件，启动时只启用其中一部分。
 * 这正是真实产品里「插件已安装但未启用」的模型，也是 install() 存在的理由。
 */
private class CatalogLoader(private val catalog: List<Plugin>) : PluginLoader, PluginDiscovery {

    override val scheme: String = "catalog"
    override val name: String = "catalog"
    override val platforms: Set<Platform> = Platform.entries.toSet()

    // 发现 = 「目录里装了什么」，**不**做任何启用过滤。
    // 过滤是 enabledPlugins 的事  否则 install(id) 永远找不到它想装的那个插件。
    override suspend fun discover(root: String): DiscoveryResult =
        DiscoveryResult(
            catalog.map { DiscoveredPlugin(it.manifest, "catalog:" + it.manifest.id.value) },
        )

    override suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin =
        catalog.first { it.manifest.id == manifest.id }
}

/**
 * 一个普通的第三方插件：它**不碰存储**，只通过 [PlanService] 读真实数据。
 *
 * 这就是「插件机制的消费者」长什么样  它不知道数据存在哪、怎么存的，
 * 只知道有一个叫 PlanService 的接口。所以将来把 KV 换成 SQLite，它一行都不用改。
 */
private class SummaryCardPlugin : Plugin {

    override val manifest: PluginManifest = PluginManifest(
        id = PluginId("dev.summary"),
        version = SemVer.parse("1.0.0"),
        name = "今日概览",
        sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
        loader = "catalog",
        dependsOn = listOf(
            PluginRequirement(PluginId("core.plan"), VersionRange.parse(">=1.0.0")),
        ),
        contributes = mapOf(Slots.HomeHeader.id to listOf(ContributionDecl("summary"))),
    )

    override suspend fun init(ctx: PluginContext) {
        // 拿不到就抛 -> 这个插件会转 FAILED，而不是默默显示一个空卡片
        val plan = ctx.services.require(PlanService::class)
        val now = ctx.clock.nowMs()
        val summary = plan.summary(now, now + DAY)
        ctx.extensions.register(
            Slots.HomeHeader,
            TextSlot(
                id = "summary",
                order = 5,
                title = "今日概览",
                body = summary.dueToday.toString() + " 项待办  " +
                    summary.overdue + " 项逾期  已投入 " + summary.spentMinutes + " 分钟",
            ),
        )
    }
}

fun main() = runBlocking {
    val workspace = File(System.getProperty("java.io.tmpdir"), "trellis-demo")
    workspace.deleteRecursively()
    workspace.mkdirs()

    banner("Trellis  从内核到产品")
    println("  这个演示里同时跑着三类插件，它们走的是**完全相同**的生命周期：")
    println("    1. core.plan        必需内置插件，用存储层实现 PlanService")
    println("    2. dev.summary      普通插件，只通过 Service 读数据，不碰存储")
    println("    3. 声明式插件        磁盘上的两份 JSON，没有编译过任何东西")

    // ---- 声明式插件：磁盘上的两份 JSON ----
    writePlugin(
        workspace, "dev.focus",
        """{ "id": "dev.focus", "version": "1.0.0", "name": "专注计时" }""",
        """{ "slots": [ { "point": "slot.home.header", "id": "timer", "order": 50,
                        "title": "专注计时", "body": "还剩 18:20" } ] }""",
    )

    // 三个加载器各管一种插件来源：
    //   builtin -> core.plan（编译进包里的必需插件）
    //   catalog -> dev.summary（已安装但未启用，等 install() 唤醒）
    //   dsl     -> 磁盘上的 JSON
    val kernel = Kernel.boot(
        KernelConfig(
            sdkVersion = SemVer.parse("1.0.0"),
            platform = Platform.JVM,
            pluginsDir = workspace.absolutePath,
            loaders = listOf(
                BuiltinLoader(listOf(PlanCorePlugin())),
                CatalogLoader(listOf(SummaryCardPlugin())),
                DslLoader(JvmPluginFileSystem()),
            ),
            // 第一幕只跑这两个；dev.summary 装在目录里但没启用，等第二幕唤醒
            enabledPlugins = setOf(PluginId("dev.focus")),
        ),
    )
    val host = SlotHost(kernel.extensions, ConsoleSlotRenderer())

    // ---- 第一幕：真实数据 ----
    val plan = kernel.extensions.service(PlanService::class) ?: error("core.plan 没发布 PlanService")

    banner("第一幕：真实数据（不再是写死的字符串）")
    val goal = plan.createPlan("6 周通过软考中级")
    val now = System.currentTimeMillis()
    plan.addTask("第 1 章 计算机组成", planId = goal.id, dueAt = now - HOUR, estimateMinutes = 90)
    plan.addTask("第 2 章 操作系统", planId = goal.id, dueAt = now + 3 * HOUR)
    plan.addTask("第 3 章 网络基础", planId = goal.id, dueAt = now + DAY)
    val done = plan.addTask("整理错题", dueAt = now - 2 * HOUR)
    plan.setStatus(done.id, dev.trellis.domain.TaskStatus.DONE)
    plan.logTime(done.id, 45)
    plan.summarize(done.id, "第三章的协议分层还要再看")

    println("  计划： " + goal.title)
    println("  " + RULE)
    for (t in plan.tasks()) {
        val mark = if (t.isDone) "[x]" else "[ ]"
        // 跨模块的公开属性不能智能转换，先取到局部变量
        val dueAt = t.dueAt
        val due = when {
            dueAt == null -> "        "
            dueAt < now -> "  逾期  "
            else -> "        "
        }
        val spent = if (t.spentMinutes > 0) "  (已投入 " + t.spentMinutes + " 分钟)" else ""
        println("    " + mark + " " + due + t.title + spent)
    }

    banner("第二幕：另一个插件读到这些数据（消费者出现了）")
    println("  dev.summary 声明了 dependsOn=[core.plan]，所以它 init 时 PlanService 一定已经就绪。")
    println("  它自己完全不知道数据存在哪、怎么存的  只知道有这么一个接口。")
    val installed = kernel.install(PluginId("dev.summary"))
    println()
    println("  install() -> " + describe(installed))
    show(host, "首页头部  dev.summary 那行是真数据，dev.focus 那行是写死的 JSON")
    showPlugins("插件列表", kernel.plugins())

    // ---- 第三幕：Agent 写插件 ----
    banner("第三幕：现场写一份 JSON，新插件立刻生效")
    writePlugin(
        workspace, "ai.streak",
        """{ "id": "ai.streak", "version": "1.0.0", "name": "连续打卡" }""",
        """{ "slots": [ { "point": "slot.home.header", "id": "streak", "order": 10,
                        "title": "连续 7 天", "body": "今天还没打卡" } ] }""",
    )
    println()
    println("  已写入 " + workspace.absolutePath + "/ai.streak/{manifest.json, main.dsl.json}")
    println("  install() -> " + describe(kernel.install(PluginId("ai.streak"))))
    show(host, "首页头部  order 10 的新卡片排到了最前")
    showPlugins("插件列表", kernel.plugins())

    // ---- 第四幕：卸载 ----
    banner("第四幕：卸载")
    println("  " + kernel.uninstall(PluginId("ai.streak")))
    show(host, "首页头部  立刻消失，没有残留")

    // ---- 收尾 ----
    banner("收尾：这些卡片里哪些数字是真的")
    println("  dev.summary 的三个数字  真的，从存储里算出来的。")
    println("  dev.focus / ai.streak 的内容  写死的，因为声明式插件还没有数据绑定。")
    println()
    println("  它们走的是同一条生命周期、同一套扩展点、同一套权限模型。")
    println("  差别只在「谁写的」：一个是 Kotlin，一个是 JSON。")

    kernel.shutdown()
    println()
    println("  内核最终状态：" + kernel.currentState)
}

private fun describe(result: InstallResult): String = when (result) {
    is InstallResult.Installed -> "成功，状态 " + result.state
    is InstallResult.Rejected -> "被拒绝：" + result.errors.joinToString()
}
