package dev.trellis.kernel.api

import dev.trellis.kernel.action.ActionArgs
import dev.trellis.kernel.action.ActionParam
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.event.KernelEvent
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.rule.RuleDecl
import dev.trellis.kernel.rule.RuleEngine
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TextSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 恶意/写坏的插件会不会把程序拖下水。
 *
 * 这些测试不是在验证「插件很善良」，恰恰相反：**每一个插件都是故意坏的。**
 * 内核的承诺只有一条 —— 一个插件的失败，不能变成整个程序的失败。
 *
 * 做不到的那部分写在文件末尾，不许假装做到了。
 */

private enum class Sabotage { NONE, INIT, START, STOP }

private class HostilePlugin(
    override val manifest: PluginManifest,
    private val sabotage: Sabotage,
    private val registrations: List<Pair<ExtensionPoint<SlotContribution>, SlotContribution>> = emptyList(),
) : Plugin {

    /** 炸之前先往注册表里塞东西 —— 这才是最难清理的坏法。 */
    override suspend fun init(ctx: PluginContext) {
        for ((point, contribution) in registrations) {
            ctx.extensions.register(point, contribution)
        }
        if (sabotage == Sabotage.INIT) error("我故意在 init 里炸的")
    }

    override suspend fun start() {
        if (sabotage == Sabotage.START) error("我故意在 start 里炸的")
    }

    override suspend fun stop() {
        if (sabotage == Sabotage.STOP) error("我故意在 stop 里炸的")
    }
}

class HostilePluginTest {

    private fun goodPlugin() = ContribPlugin(
        m2Manifest("dev.good", declares = listOf(Slots.HomeHeader)),
        registrations = listOf(Slots.HomeHeader to TextSlot("good", title = "好插件", body = "我没事")),
    )

    private suspend fun boot(bad: HostilePlugin, good: ContribPlugin = goodPlugin()) =
        Kernel.boot(
            m2Config(
                CatalogLoader(
                    listOf(bad, good),
                    listOf(PluginId("dev.bad"), PluginId("dev.good")),
                ),
            ),
        )

    @Test
    fun aPluginThatBlowsUpInInitDoesNotTakeTheKernelDown() = runTest {
        val kernel = boot(HostilePlugin(m2Manifest("dev.bad"), Sabotage.INIT))

        assertEquals(
            PluginState.ACTIVE, kernel.plugin(PluginId("dev.good"))!!.state,
            "坏插件在 init 里炸了，好插件必须照常起来",
        )
        assertEquals(PluginState.FAILED, kernel.plugin(PluginId("dev.bad"))!!.state)
        assertTrue(
            kernel.errors.any { it.code == ErrorCode.PLUGIN_INIT_FAILED },
            kernel.errors.toString(),
        )
        kernel.shutdown()
    }

    @Test
    fun aPluginThatBlowsUpInStartDoesNotTakeTheKernelDown() = runTest {
        val kernel = boot(HostilePlugin(m2Manifest("dev.bad"), Sabotage.START))

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.good"))!!.state)
        assertEquals(PluginState.FAILED, kernel.plugin(PluginId("dev.bad"))!!.state)
        assertTrue(
            kernel.errors.any { it.code == ErrorCode.PLUGIN_START_FAILED },
            kernel.errors.toString(),
        )
        kernel.shutdown()
    }

    /**
     * 最刁钻的一种坏法：先注册一堆贡献，再在 init 末尾自杀。
     *
     * 如果内核只记一条诊断就完事，那些贡献会留在注册表里 ——
     * 界面上于是出现一张**没有任何插件认领**的卡片，点它不知道属于谁，
     * 想停也停不掉（因为「停用」是按插件 id 清理的，而它已经不在插件列表里了）。
     */
    @Test
    fun aPluginThatDiesMidInitLeavesNothingBehind() = runTest {
        val bad = HostilePlugin(
            m2Manifest("dev.bad", declares = listOf(Slots.HomeSection)),
            Sabotage.INIT,
            registrations = listOf(
                Slots.HomeSection to TextSlot("ghost", title = "幽灵卡片", body = "它的主人已经死了"),
            ),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(bad), listOf(PluginId("dev.bad")))))

        assertEquals(PluginState.FAILED, kernel.plugin(PluginId("dev.bad"))!!.state)
        assertTrue(
            kernel.extensions.all(Slots.HomeSection).isEmpty(),
            "插件死了，它注册的东西必须一起消失，否则会留下一张没人认领的卡："
                + kernel.extensions.all(Slots.HomeSection).map { it.id },
        )
        kernel.shutdown()
    }

    @Test
    fun aPluginThatBlowsUpInStopCanStillBeUninstalled() = runTest {
        val bad = HostilePlugin(m2Manifest("dev.bad"), Sabotage.STOP)
        val kernel = boot(bad)

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.bad"))!!.state)

        val result = kernel.disable(PluginId("dev.bad"))

        assertTrue(result !is UninstallResult.Rejected, "stop() 炸了也得卸得掉：" + result)
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.good"))!!.state, "别的插件不受影响")
        kernel.shutdown()
    }

    // ------------------------------------------------------------ 规则动作

    @Test
    fun aThrowingActionDoesNotStopTheRestOfTheRule() = runTest {
        val log = mutableListOf<String>()

        val explode = object : KernelAction {
            override val id = "explode"
            override val order = 1
            override val label = "炸"
            override val params = emptyList<ActionParam>()
            override val capability: CapabilityId? = null
            override suspend fun execute(args: ActionArgs): String = error("我故意炸的")
        }
        val logIt = object : KernelAction {
            override val id = "logIt"
            override val order = 2
            override val label = "记一笔"
            override val params = emptyList<ActionParam>()
            override val capability: CapabilityId? = null
            override suspend fun execute(args: ActionArgs): String {
                log += "记上了"
                return "ok"
            }
        }

        val engine = RuleEngine { listOf(explode, logIt) }
        engine.install(
            "dev.bad",
            listOf(
                RuleDecl(
                    on = "task.done",
                    actions = listOf(ActionCall("explode", emptyMap()), ActionCall("logIt", emptyMap())),
                ),
            ),
        )

        val results = engine.handle(KernelEvent("task.done", emptyMap()))

        assertEquals(2, results.size)
        assertFalse(results[0].ok, "第一个动作本来就该失败")
        assertTrue(results[1].ok, "第一个动作炸了，第二个还得跑完：" + results[1].message)
        assertEquals(listOf("记上了"), log)
    }
}

/*
 * 内核**做不到**的事，写在这里，免得以后有人以为它做到了：
 *
 * 1. 死循环拦不住。load/init/start 外面套的是 withTimeout，而超时只在挂起点
 *    生效。一段 `while (true) {}` 或者 `Thread.sleep(一小时)` 会把线程钉死，
 *    超时永远等不到下一次检查。要真拦，只能另起进程。
 * 2. 内存耗尽拦不住。OutOfMemoryError 被 catch (t: Throwable) 抓到了，
 *    但那时进程往往已经救不回来了。
 * 3. 界面里的插件代码拦不住。Compose 不允许 try/catch 包 composable，
 *    一个插件画的卡片里抛异常 = 整个窗口崩。所以第三方插件**只能是数据**
 *    （声明式 JSON），压根没有代码路径进到界面里。真正要跑第三方代码，
 *    得等轨道 C（独立进程 + IPC）。
 */
