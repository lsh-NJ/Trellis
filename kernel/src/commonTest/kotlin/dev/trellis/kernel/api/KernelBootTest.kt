package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginRequirement
import dev.trellis.kernel.plugin.PluginState
import dev.trellis.kernel.spi.BuiltinLoader
import dev.trellis.kernel.spi.DiscoveredPlugin
import dev.trellis.kernel.spi.DiscoveryResult
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.spi.PluginDiscovery
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private fun manifestOf(
    id: String,
    version: String = "1.0.0",
    loader: String = "builtin",
    required: Boolean = false,
    dependsOn: List<String> = emptyList(),
): PluginManifest = PluginManifest(
    id = PluginId(id),
    version = SemVer.parse(version),
    name = id,
    sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
    loader = loader,
    dependsOn = dependsOn.map { PluginRequirement(PluginId(it)) },
    required = required,
)

private class TestPlugin(
    override val manifest: PluginManifest,
    private val failOnInit: Boolean = false,
    private val failOnStart: Boolean = false,
    private val hangOnInit: Boolean = false,
) : Plugin {

    var disposed: Boolean = false
        private set

    var initCalls: Int = 0
        private set

    override suspend fun init(ctx: PluginContext) {
        initCalls++
        if (hangOnInit) delay(60_000)
        if (failOnInit) error("故意在 init 里炸")
    }

    override suspend fun start() {
        if (failOnStart) error("故意在 start 里炸")
    }

    override suspend fun dispose() {
        disposed = true
    }
}

private fun config(
    plugins: List<Plugin> = emptyList(),
    discoveries: List<PluginDiscovery> = emptyList(),
): KernelConfig = KernelConfig(
    sdkVersion = SemVer.parse("1.0.0"),
    platform = Platform.JVM,
    loaders = listOf(BuiltinLoader(plugins)),
    discoveries = discoveries,
    clock = FakeClock(1_000L),
)

class KernelBootTest {

    // ---- 不变量 I1：零插件也必须能启动 ----

    @Test
    fun zeroPluginsStillBoots() = runTest {
        val kernel = Kernel.boot(config())
        assertEquals(KernelState.ACTIVE, kernel.currentState)
        assertTrue(kernel.manifests().isEmpty())
        assertTrue(kernel.errors.isEmpty())
    }

    @Test
    fun noLoadersAtAllStillBoots() = runTest {
        val kernel = Kernel.boot(
            KernelConfig(sdkVersion = SemVer.parse("1.0.0"), platform = Platform.JVM),
        )
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    @Test
    fun healthyPluginReachesActive() = runTest {
        val p = TestPlugin(manifestOf("dev.a"))
        val kernel = Kernel.boot(config(listOf(p)))
        assertEquals(PluginState.ACTIVE, kernel.requirePlugin("dev.a").state)
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    @Test
    fun pluginsBootInDependencyOrder() = runTest {
        val b = TestPlugin(manifestOf("dev.b", dependsOn = listOf("dev.a")))
        val a = TestPlugin(manifestOf("dev.a"))
        val kernel = Kernel.boot(config(listOf(b, a)))
        assertEquals(KernelState.ACTIVE, kernel.currentState)
        assertEquals(PluginState.ACTIVE, kernel.requirePlugin("dev.a").state)
        assertEquals(PluginState.ACTIVE, kernel.requirePlugin("dev.b").state)
    }

    // ---- 不变量 I5：一个插件崩溃不影响不依赖它的插件 ----

    @Test
    fun failingPluginDoesNotTakeDownItsSiblings() = runTest {
        val good = TestPlugin(manifestOf("dev.good"))
        val bad = TestPlugin(manifestOf("dev.bad"), failOnInit = true)
        val kernel = Kernel.boot(config(listOf(good, bad)))

        assertEquals(KernelState.ACTIVE, kernel.currentState)
        assertEquals(PluginState.ACTIVE, kernel.requirePlugin("dev.good").state)
        assertEquals(PluginState.FAILED, kernel.requirePlugin("dev.bad").state)
        assertEquals(
            ErrorCode.PLUGIN_INIT_FAILED,
            kernel.requirePlugin("dev.bad").errors.first().code,
        )
    }

    @Test
    fun pluginFailingInStartIsAlsoIsolated() = runTest {
        val bad = TestPlugin(manifestOf("dev.bad"), failOnStart = true)
        val kernel = Kernel.boot(config(listOf(bad)))
        assertEquals(PluginState.FAILED, kernel.requirePlugin("dev.bad").state)
        assertEquals(
            ErrorCode.PLUGIN_START_FAILED,
            kernel.requirePlugin("dev.bad").errors.first().code,
        )
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    @Test
    fun dependentOfFailedPluginIsSkippedNotFailed() = runTest {
        val bad = TestPlugin(manifestOf("dev.bad"), failOnStart = true)
        val dep = TestPlugin(manifestOf("dev.dep", dependsOn = listOf("dev.bad")))
        val kernel = Kernel.boot(config(listOf(bad, dep)))

        assertEquals(PluginState.FAILED, kernel.requirePlugin("dev.bad").state)
        val info = kernel.requirePlugin("dev.dep")
        assertEquals(PluginState.SKIPPED, info.state)
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, info.errors.first().code)
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    @Test
    fun skippedPluginIsNeverInitialized() = runTest {
        val bad = TestPlugin(manifestOf("dev.bad"), failOnInit = true)
        val dep = TestPlugin(manifestOf("dev.dep", dependsOn = listOf("dev.bad")))
        Kernel.boot(config(listOf(bad, dep)))
        assertEquals(0, dep.initCalls)
    }

    // ---- required 插件：决定内核是 ACTIVE 还是 DEGRADED ----

    @Test
    fun requiredPluginFailureDegradesKernel() = runTest {
        val core = TestPlugin(manifestOf("dev.core", required = true), failOnInit = true)
        val kernel = Kernel.boot(config(listOf(core)))
        assertEquals(KernelState.DEGRADED, kernel.currentState)
        assertTrue(
            kernel.errors.any { it.code == ErrorCode.REQUIRED_PLUGIN_NOT_READY },
            kernel.errors.toString(),
        )
    }

    @Test
    fun requiredPluginBootingNormallyKeepsKernelActive() = runTest {
        val core = TestPlugin(manifestOf("dev.core", required = true))
        val kernel = Kernel.boot(config(listOf(core)))
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    // ---- 跨端一致性：被发现了，但这个平台没有能跑它的 loader ----

    @Test
    fun pluginWithoutPlatformLoaderBecomesUnavailableNotFatal() = runTest {
        val discovery = object : PluginDiscovery {
            override val name: String = "test"
            override suspend fun discover(root: String): DiscoveryResult =
                DiscoveryResult(listOf(DiscoveredPlugin(manifestOf("dev.shadow", loader = "shadow"), "test")))
        }
        val kernel = Kernel.boot(config(discoveries = listOf(discovery)))

        val info = kernel.requirePlugin("dev.shadow")
        assertEquals(PluginState.UNAVAILABLE, info.state)
        assertEquals(ErrorCode.LOADER_UNAVAILABLE, info.errors.first().code)
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    @Test
    fun unavailablePluginDoesNotBlockItsSiblings() = runTest {
        val discovery = object : PluginDiscovery {
            override val name: String = "test"
            override suspend fun discover(root: String): DiscoveryResult = DiscoveryResult(
                listOf(DiscoveredPlugin(manifestOf("dev.shadow", loader = "shadow"), "test")),
            )
        }
        val good = TestPlugin(manifestOf("dev.builtin"))
        val kernel = Kernel.boot(
            KernelConfig(
                sdkVersion = SemVer.parse("1.0.0"),
                platform = Platform.JVM,
                loaders = listOf(BuiltinLoader(listOf(good))),
                discoveries = listOf(discovery),
                clock = FakeClock(1_000L),
            ),
        )
        assertEquals(PluginState.UNAVAILABLE, kernel.requirePlugin("dev.shadow").state)
        assertEquals(PluginState.ACTIVE, kernel.requirePlugin("dev.builtin").state)
    }

    // ---- 超时隔离 ----

    @Test
    fun initTimeoutIsCaughtAndQuarantined() = runTest {
        val slow = TestPlugin(manifestOf("dev.slow"), hangOnInit = true)
        val kernel = Kernel.boot(config(listOf(slow)).copy(initTimeoutMs = 50L))

        val info = kernel.requirePlugin("dev.slow")
        assertEquals(PluginState.FAILED, info.state)
        assertEquals(ErrorCode.INIT_TIMEOUT, info.errors.first().code)
        assertEquals(KernelState.ACTIVE, kernel.currentState)
    }

    // ---- 安全模式 ----

    @Test
    fun safeModeLoadsOnlyBuiltinRequiredPlugins() = runTest {
        val core = TestPlugin(
            PluginManifest(
                id = PluginId("dev.core"),
                version = SemVer.parse("1.0.0"),
                name = "core",
                sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
                builtin = true,
                required = true,
            ),
        )
        val extra = TestPlugin(manifestOf("dev.extra"))
        val kernel = Kernel.boot(
            config(listOf(core, extra)).copy(bootMode = BootMode.SAFE),
        )
        assertEquals(PluginState.ACTIVE, kernel.requirePlugin("dev.core").state)
        assertTrue(kernel.plugin(PluginId("dev.extra")) == null, "安全模式不该加载非必需插件")
    }

    // ---- 卸载 ----

    @Test
    fun shutdownDisposesEverythingAndIsIdempotent() = runTest {
        val a = TestPlugin(manifestOf("dev.a"))
        val b = TestPlugin(manifestOf("dev.b", dependsOn = listOf("dev.a")))
        val kernel = Kernel.boot(config(listOf(a, b)))
        assertEquals(KernelState.ACTIVE, kernel.currentState)

        kernel.shutdown()
        assertEquals(KernelState.SHUTDOWN, kernel.currentState)
        assertTrue(a.disposed)
        assertTrue(b.disposed)
        assertEquals(PluginState.DISPOSED, kernel.requirePlugin("dev.a").state)

        kernel.shutdown()
        assertEquals(KernelState.SHUTDOWN, kernel.currentState)
    }
}

private fun Kernel.requirePlugin(id: String) =
    requireNotNull(plugin(PluginId(id))) { "内核里没有插件 $id" }