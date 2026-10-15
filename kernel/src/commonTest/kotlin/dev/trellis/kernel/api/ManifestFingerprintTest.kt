package dev.trellis.kernel.api

import dev.trellis.kernel.capability.Capabilities
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.security.InMemoryManifestLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ManifestFingerprintTest {

    private suspend fun bootWith(ledger: InMemoryManifestLedger, plugin: dev.trellis.kernel.plugin.Plugin) =
        Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(plugin.manifest.id)))
                .copy(manifestLedger = ledger),
        )

    @Test
    fun anUnchangedManifestIsTrustedOnTheSecondBoot() = runTest {
        val ledger = InMemoryManifestLedger()

        val first = CapabilityProbe(m2Manifest("dev.a"))
        bootWith(ledger, first).shutdown()

        // 换一个实例、但 manifest 内容一模一样
        val second = CapabilityProbe(m2Manifest("dev.a"))
        val kernel = bootWith(ledger, second)

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
        assertTrue(kernel.errors.isEmpty(), kernel.errors.toString())
    }

    @Test
    fun silentlyAddingACapabilityIsCaughtByTheFingerprint() = runTest {
        val ledger = InMemoryManifestLedger()

        val plain = CapabilityProbe(m2Manifest("dev.a"))
        bootWith(ledger, plain).shutdown()

        // 插件偷偷把 capabilities 从 [] 改成 [plugin_admin]
        val tampered = CapabilityProbe(
            m2Manifest("dev.a").copy(capabilities = setOf(Capabilities.PluginAdmin.id)),
        )
        val kernel = bootWith(ledger, tampered)

        val info = kernel.plugin(PluginId("dev.a"))!!
        assertEquals(PluginState.SKIPPED, info.state, "被篡改的插件绝不能跑起来")
        assertEquals(ErrorCode.MANIFEST_TAMPERED, info.errors.first().code)
        assertTrue(info.errors.first().detail.contains("重新安装"), info.errors.first().detail)
    }

    @Test
    fun addingADependencyIsAlsoCaught() = runTest {
        val ledger = InMemoryManifestLedger()

        val plain = CapabilityProbe(m2Manifest("dev.a"))
        bootWith(ledger, plain).shutdown()

        val tampered = CapabilityProbe(
            m2Manifest("dev.a", dependsOn = listOf("dev.something")),
        )
        val kernel = bootWith(ledger, tampered)

        assertEquals(PluginState.SKIPPED, kernel.plugin(PluginId("dev.a"))!!.state)
        assertTrue(
            kernel.errors.any { it.code == ErrorCode.MANIFEST_TAMPERED },
            kernel.errors.toString(),
        )
    }

    @Test
    fun changingOnlyTheDisplayNameIsNotTreatedAsTampering() = runTest {
        val ledger = InMemoryManifestLedger()

        val first = CapabilityProbe(m2Manifest("dev.a"))
        bootWith(ledger, first).shutdown()

        // 改显示名不该触发「插件被篡改」 那不改变插件的任何权力
        val renamed = CapabilityProbe(m2Manifest("dev.a").copy(name = "换了个名字"))
        val kernel = bootWith(ledger, renamed)

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.a"))!!.state)
        assertTrue(kernel.errors.isEmpty(), kernel.errors.toString())
    }
}
