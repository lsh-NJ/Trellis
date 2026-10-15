package dev.trellis.app.desktop

import dev.trellis.kernel.api.KernelState
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.slot.TextSlot
import dev.trellis.kernel.extension.displayName
import dev.trellis.kernel.slot.Slots
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 应用层的可测部分：内核启动、领域服务、文件存储、插件卡片。
 *
 * 界面本身（Compose 窗口）需要显示器，跑不了单元测试 
 * 但界面的**全部输入**都在这里，所以把它测透，UI 就只剩「画出来」这一件事。
 */
class AppRuntimeTest {

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-test").toFile().apply { deleteOnExit() }

    @Test
    fun bootsTheKernelAndCreatesAStarterGoal() = runTest {
        val runtime = AppRuntime.create(tempHome())

        assertEquals(KernelState.ACTIVE, runtime.kernel.currentState)
        assertEquals(PluginState.ACTIVE, runtime.kernel.plugin(dev.trellis.kernel.core.PluginId("core.plan"))!!.state)
        assertTrue(runtime.goal != null, "第一次启动应当自动建一个初始计划")
        assertTrue(runtime.pluginInfos.isNotEmpty())

        runtime.shutdown()
    }

    @Test
    fun addingAndCompletingTasksUpdatesTheSummary() = runTest {
        val runtime = AppRuntime.create(tempHome())

        runtime.addTask("第 1 章 计算机组成")
        runtime.addTask("第 2 章 操作系统")
        assertEquals(2, runtime.tasks.size)
        assertEquals(2, runtime.summary.total)
        assertEquals(0, runtime.summary.done)

        runtime.toggle(runtime.tasks.first())
        assertEquals(1, runtime.summary.done)
        runtime.toggle(runtime.tasks.first { it.isDone })
        assertEquals(0, runtime.summary.done, "再点一次应当取消完成")

        runtime.shutdown()
    }

    @Test
    fun deletingATaskRemovesIt() = runTest {
        val runtime = AppRuntime.create(tempHome())
        runtime.addTask("临时任务")
        assertEquals(1, runtime.tasks.size)

        runtime.delete(runtime.tasks.single())
        assertEquals(0, runtime.tasks.size)
        runtime.shutdown()
    }

    /** 应用最要紧的一条性质：**关掉再打开，东西还在。** */
    @Test
    fun dataSurvivesARestart() = runTest {
        val home = tempHome()

        val first = AppRuntime.create(home)
        first.addTask("刷完第三章")
        val kept = first.tasks.single()
        first.toggle(kept)
        first.logTime(first.tasks.single(), 45)
        first.shutdown()

        val second = AppRuntime.create(home)
        assertEquals(1, second.tasks.size, "重启后任务丢了")
        assertEquals("刷完第三章", second.tasks.single().title)
        assertTrue(second.tasks.single().isDone)
        assertEquals(45, second.tasks.single().spentMinutes)
        second.shutdown()
    }

    @Test
    fun theFirstPartyPluginsContributeComposableCards() = runTest {
        val runtime = AppRuntime.create(tempHome())

        // 两个第一方插件都用 ComposeSlot 往同一个挂载点上放卡片
        val slots = runtime.kernel.extensions.all(Slots.HomeSection)
        assertEquals(
            setOf("quick-add", "danger-zone"),
            slots.map { it.id }.toSet(),
            slots.map { it.id }.toString(),
        )
        assertTrue(slots.all { it is dev.trellis.ui.ComposeSlot })

        runtime.shutdown()
    }

    /** 插件管理界面要显示「这个插件贡献了什么」 那是按属主查的，不是按挂载点。 */
    @Test
    fun everyPluginReportsWhatItContributed() = runTest {
        val runtime = AppRuntime.create(tempHome())

        val services = runtime.servicesOf(dev.trellis.kernel.core.PluginId("core.plan"))
        assertEquals(1, services.size, "core.plan 应当正好发布一个服务")
        assertTrue(services.single().contract.displayName().contains("PlanService"))

        val cards = runtime.contributionsOf(dev.trellis.kernel.core.PluginId("dev.quickadd"))
        assertEquals(1, cards.size)
        assertEquals("slot.home.section", cards.single().pointId.value)
        assertEquals("quick-add", cards.single().id)

        // 一个插件完全没有贡献也是合法的  这里顺便确认它不会崩
        assertTrue(runtime.contributionsOf(dev.trellis.kernel.core.PluginId("不存在")).isEmpty())

        runtime.shutdown()
    }

    /**
     * 两道闸的第一道：声明 != 授予。
     *
     * 插件在 manifest 里「声明」了删数据的能力，但在用户点授权之前，
     * `ctx.capabilities.has()` 是 false  它连按钮都点不亮。
     */
    @Test
    fun aDeclaredCapabilityStartsUngrantedAndCanBeGrantedAtRuntime() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val danger = dev.trellis.kernel.core.PluginId("dev.danger")

        val (declared, granted) = runtime.capabilitiesOf(danger)
        assertTrue(declared.isNotEmpty(), "危险插件必须声明能力，否则演示不了")
        assertTrue(granted.isEmpty(), "没点授权之前不该有任何权限")

        // 授权会重载插件  因为能力是在 init() 时算好交给插件的固定集合
        runtime.grantAndReload(danger, declared)

        val (_, afterGrant) = runtime.capabilitiesOf(danger)
        assertEquals(declared, afterGrant)
        assertEquals(
            PluginState.ACTIVE,
            runtime.kernel.plugin(danger)!!.state,
            "重载之后它应当还是活的",
        )

        runtime.shutdown()
    }

    @Test
    fun aDeclarativePluginDroppedIntoThePluginsFolderGetsLoaded() = runTest {
        val home = tempHome()
        val pluginDir = File(home, "plugins/ai.demo").apply { mkdirs() }
        File(pluginDir, "manifest.json").writeText(
            """{ "id": "ai.demo", "version": "1.0.0", "name": "演示" }""",
            Charsets.UTF_8,
        )
        File(pluginDir, "main.dsl.json").writeText(
            """{ "slots": [ { "point": "slot.home.header", "id": "demo",
                            "title": "来自 JSON", "body": "没有编译过" } ] }""",
            Charsets.UTF_8,
        )

        val runtime = AppRuntime.create(home)

        val cards = runtime.kernel.extensions.all(Slots.HomeHeader)
        assertEquals(1, cards.size, cards.map { it.id }.toString())
        assertTrue(cards.single() is TextSlot)
        assertEquals("来自 JSON", (cards.single() as TextSlot).title)

        runtime.shutdown()
    }

    /**
     * 端到端地验证「停用」：
     *  - 当场生效
     *  - **活过重启**（这条曾经是坏的  卸载只活在内存里，重启就复现）
     *  - 名字还记得（界面上要能列出来）
     *  - 一键启用回来
     */
    @Test
    fun disablingAPluginSticksAcrossRestartAndCanBeReversed() = runTest {
        val home = tempHome()
        val quickAdd = dev.trellis.kernel.core.PluginId("dev.quickadd")

        val first = AppRuntime.create(home)
        assertTrue(first.pluginInfos.any { it.manifest.id == quickAdd })
        first.disablePlugin("dev.quickadd")
        assertTrue(quickAdd in first.disabledPlugins)
        assertTrue(first.pluginInfos.none { it.manifest.id == quickAdd }, "停用之后当场就不该在跑")
        first.shutdown()

        val second = AppRuntime.create(home)
        assertTrue(
            second.pluginInfos.none { it.manifest.id == quickAdd },
            "停用必须活过重启  否则界面上那个按钮就是假的",
        )
        assertEquals("快捷操作", second.nameOf(quickAdd), "名字要记得，界面上还要列出来")
        assertTrue(quickAdd in second.disabledPlugins)

        second.enablePlugin("dev.quickadd")
        assertTrue(second.pluginInfos.any { it.manifest.id == quickAdd }, "启用之后应当回来")
        assertTrue(quickAdd !in second.disabledPlugins)
        second.shutdown()
    }

    @Test
    fun aBrokenPluginDoesNotStopTheAppFromStarting() = runTest {
        val home = tempHome()
        val broken = File(home, "plugins/ai.broken").apply { mkdirs() }
        File(broken, "manifest.json").writeText("""{ "id": "", "version": "1.0.0" }""", Charsets.UTF_8)

        val runtime = AppRuntime.create(home)

        assertEquals(KernelState.ACTIVE, runtime.kernel.currentState, "一个坏插件不该让 App 打不开")
        assertTrue(runtime.kernel.errors.isNotEmpty(), "但诊断里必须留下记录")
        runtime.shutdown()
    }
}
