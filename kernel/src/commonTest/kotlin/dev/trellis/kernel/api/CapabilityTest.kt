package dev.trellis.kernel.api

import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.capability.GrantDialog
import dev.trellis.kernel.capability.InMemoryGrantStore
import dev.trellis.kernel.core.CapabilityId
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

internal class CapabilityProbe(
    override val manifest: PluginManifest,
    private val hasChecks: List<CapabilityId> = emptyList(),
    private val requireChecks: List<CapabilityId> = emptyList(),
) : Plugin {

    var observed: Map<String, Boolean> = emptyMap()
        private set

    override suspend fun init(ctx: PluginContext) {
        observed = hasChecks.associate { it.value to ctx.capabilities.has(it) }
        for (capability in requireChecks) ctx.capabilities.require(capability)
    }
}

private fun withCaps(id: String, caps: Set<CapabilityId>): PluginManifest =
    m2Manifest(id).copy(capabilities = caps)

class CapabilityTest {

    @Test
    fun aDeclaredAndGrantedCapabilityIsUsable() = runTest {
        val plugin = CapabilityProbe(
            withCaps("dev.a", setOf(Capabilities.Notify.id)),
            hasChecks = listOf(Capabilities.Notify.id),
            requireChecks = listOf(Capabilities.Notify.id),
        )
        val store = InMemoryGrantStore().apply { grant(PluginId("dev.a"), setOf(Capabilities.Notify.id)) }
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a")))).copy(grantStore = store),
        )

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
        assertEquals(mapOf("notify" to true), plugin.observed)
    }

    @Test
    fun aDeclaredButUngrantedCapabilityMakesRequireThrowAndThePluginFailWithK005() = runTest {
        val plugin = CapabilityProbe(
            withCaps("dev.a", setOf(Capabilities.DeleteData.id)),
            hasChecks = listOf(Capabilities.DeleteData.id),
            requireChecks = listOf(Capabilities.DeleteData.id),
        )
        // 没给 grantStore，也没给授权界面 -> 默认拒绝
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a")))))

        val info = kernel.plugin(PluginId("dev.a"))!!
        assertEquals(PluginState.FAILED, info.state)
        assertEquals(ErrorCode.CAPABILITY_DENIED, info.errors.first().code)
        assertTrue(info.errors.first().detail.contains("delete_data"), info.errors.first().detail)
    }

    @Test
    fun hasReturnsFalseInsteadOfThrowingSoPluginsCanDegradeGracefully() = runTest {
        val plugin = CapabilityProbe(
            withCaps("dev.a", setOf(Capabilities.Notify.id)),
            hasChecks = listOf(Capabilities.Notify.id, Capabilities.Network.id),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a")))))

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state, "只用 has 不该让插件失败")
        assertEquals(mapOf("notify" to false, "network" to false), plugin.observed)
    }

    @Test
    fun anUndeclaredCapabilityIsNeverGrantedEvenIfTheStoreSaysSo() = runTest {
        // manifest 什么都没声明，但授权表里有  声明是授权的必要条件
        val plugin = CapabilityProbe(
            m2Manifest("dev.a"),
            hasChecks = listOf(Capabilities.Notify.id),
        )
        val store = InMemoryGrantStore().apply { grant(PluginId("dev.a"), setOf(Capabilities.Notify.id)) }
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a")))).copy(grantStore = store),
        )

        assertEquals(mapOf("notify" to false), plugin.observed, "没声明的能力，授权表里写了也不能生效")
        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
    }

    @Test
    fun aCapabilityTheKernelDoesNotRecogniseIsReportedAndNotGranted() = runTest {
        val plugin = CapabilityProbe(
            withCaps("dev.a", setOf(CapabilityId("teleport"))),
            hasChecks = listOf(CapabilityId("teleport")),
        )
        val kernel = Kernel.boot(m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a")))))

        assertEquals(mapOf("teleport" to false), plugin.observed)
        val error = kernel.errors.first { it.code == ErrorCode.CAPABILITY_UNDECLARED }
        assertTrue(error.detail.contains("teleport"), error.detail)
        assertTrue(error.detail.contains("delete_data"), "报错要列出内核认识的能力：" + error.detail)
    }

    @Test
    fun theGrantDialogIsAskedOnceAndTheAnswerIsRemembered() = runTest {
        val store = InMemoryGrantStore()
        val asked = mutableListOf<Set<CapabilityId>>()
        val dialog = GrantDialog { _, requested, _ ->
            asked += requested
            requested
        }

        val first = CapabilityProbe(withCaps("dev.a", setOf(Capabilities.Notify.id)))
        val k1 = Kernel.boot(
            m2Config(CatalogLoader(listOf(first), listOf(PluginId("dev.a"))))
                .copy(grantStore = store, grantDialog = dialog),
        )
        k1.shutdown()

        val second = CapabilityProbe(withCaps("dev.a", setOf(Capabilities.Notify.id)))
        val k2 = Kernel.boot(
            m2Config(CatalogLoader(listOf(second), listOf(PluginId("dev.a"))))
                .copy(grantStore = store, grantDialog = dialog),
        )

        assertEquals(1, asked.size, "第二次启动不该再弹一次授权框，那是一种骚扰")
        assertEquals(PluginState.ACTIVE, k2.plugin(PluginId("dev.a"))!!.state)
        assertEquals(setOf(Capabilities.Notify.id), store.grantedTo(PluginId("dev.a")))
    }

    @Test
    fun aRefusedCapabilityIsRememberedAsRefused() = runTest {
        val store = InMemoryGrantStore()
        val asked = mutableListOf<Set<CapabilityId>>()
        val dialog = GrantDialog { _, requested, _ ->
            asked += requested
            emptySet()
        }
        val plugin = CapabilityProbe(
            withCaps("dev.a", setOf(Capabilities.Notify.id)),
            requireChecks = listOf(Capabilities.Notify.id),
        )
        Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.a"))))
                .copy(grantStore = store, grantDialog = dialog),
        )

        assertEquals(1, asked.size)
        assertTrue(store.isDecided(PluginId("dev.a")))
        assertTrue(store.grantedTo(PluginId("dev.a")).isEmpty())
    }
}
