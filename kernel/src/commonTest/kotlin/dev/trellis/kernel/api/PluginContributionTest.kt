package dev.trellis.kernel.api

import dev.trellis.kernel.core.ExtensionPointId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.extension.Cardinality
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TextSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private val OnlyOne = ExtensionPoint<SlotContribution>(
    ExtensionPointId("test.slot.only-one"), Cardinality.ONE, "独占位",
)

class PluginContributionTest {

    @Test
    fun pluginCanRegisterAPointItDeclared() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(Slots.HomeHeader)),
            registrations = listOf(Slots.HomeHeader to TextSlot("a-card", title = "A", body = "1")),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
        assertEquals(listOf("a-card"), kernel.extensions.all(Slots.HomeHeader).map { it.id })
        assertTrue(kernel.errors.isEmpty(), kernel.errors.toString())
    }

    @Test
    fun registeringAnUndeclaredPointIsRejectedButDoesNotKillThePlugin() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a"),
            registrations = listOf(Slots.HomeHeader to TextSlot("sneaky", title = "偷偷插一张卡", body = "!")),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))

        assertTrue(kernel.extensions.all(Slots.HomeHeader).isEmpty(), "未声明的贡献绝不能生效")

        val info = kernel.plugin(PluginId("dev.a"))!!
        assertEquals(PluginState.ACTIVE, info.state, "拒绝一次注册不该让插件整个失败")
        assertTrue(
            info.errors.any { it.code == ErrorCode.CONTRIBUTION_REJECTED },
            info.errors.toString(),
        )
    }

    @Test
    fun registeringOutsideInitIsRejected() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(Slots.HomeHeader)),
            lateRegistrations = listOf(Slots.HomeHeader to TextSlot("too-late", title = "迟到", body = "!")),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))

        assertTrue(kernel.extensions.all(Slots.HomeHeader).isEmpty(), "init() 之外注册的贡献绝不能生效")
        assertTrue(
            kernel.plugin(PluginId("dev.a"))!!.errors.any { it.code == ErrorCode.CONTRIBUTION_REJECTED },
        )
    }

    @Test
    fun declaredButNotRegisteredIsPerfectlyLegal() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(Slots.HomeHeader, Slots.DetailAside)),
            registrations = listOf(Slots.HomeHeader to TextSlot("only-one", title = "只注册一个", body = "ok")),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(a), listOf(PluginId("dev.a")))))

        assertEquals(1, kernel.extensions.all(Slots.HomeHeader).size)
        assertTrue(kernel.extensions.all(Slots.DetailAside).isEmpty())
        assertTrue(kernel.errors.isEmpty(), kernel.errors.toString())
    }

    @Test
    fun twoPluginsFightingOverAOnePointProduceK007() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(OnlyOne)),
            registrations = listOf(OnlyOne to TextSlot("from-a", title = "A 的实现", body = "")),
        )
        val b = ContribPlugin(
            m2Manifest("dev.b", declares = listOf(OnlyOne)),
            registrations = listOf(OnlyOne to TextSlot("from-b", title = "B 的实现", body = "")),
        )
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b")))),
        )

        assertEquals(
            listOf("from-a"), kernel.extensions.all(OnlyOne).map { it.id },
            "ONE 扩展点只能有一个生效，且必须是确定的那一个",
        )
        assertTrue(
            kernel.errors.any { it.code == ErrorCode.CONTRIBUTION_CONFLICT },
            "静默覆盖是不可接受的，必须留下 K007：" + kernel.errors.toString(),
        )
        assertEquals(2, kernel.extensions.rawCount(OnlyOne.id), "落选者要留在登记里，卸载赢家时才能顶上")
    }

    @Test
    fun uninstallingTheWinnerPromotesTheRunnerUp() = runTest {
        val a = ContribPlugin(
            m2Manifest("dev.a", declares = listOf(OnlyOne)),
            registrations = listOf(OnlyOne to TextSlot("from-a", order = 100, title = "A", body = "")),
        )
        val b = ContribPlugin(
            m2Manifest("dev.b", declares = listOf(OnlyOne)),
            registrations = listOf(OnlyOne to TextSlot("from-b", order = 50, title = "B", body = "")),
        )
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(a, b), listOf(PluginId("dev.a"), PluginId("dev.b")))),
        )

        assertEquals(listOf("from-b"), kernel.extensions.all(OnlyOne).map { it.id })

        kernel.uninstall(PluginId("dev.b"))
        assertEquals(
            listOf("from-a"), kernel.extensions.all(OnlyOne).map { it.id },
            "赢家被卸载后，第二名应当自动顶上  这正是「裁决在读取时计算」换来的性质",
        )
    }
}