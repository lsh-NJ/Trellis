package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.core.VersionRange
import dev.trellis.kernel.extension.ExtensionPoint
import dev.trellis.kernel.plugin.ContributionDecl
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.plugin.PluginRequirement
import dev.trellis.kernel.slot.SlotContribution
import dev.trellis.kernel.spi.DiscoveredPlugin
import dev.trellis.kernel.spi.DiscoveryResult
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.spi.PluginDiscovery
import dev.trellis.kernel.spi.PluginLoader
import dev.trellis.kernel.time.FakeClock
import dev.trellis.kernel.time.KernelClock
import kotlin.reflect.KClass
import kotlinx.coroutines.delay

internal fun m2Manifest(
    id: String,
    version: String = "1.0.0",
    loader: String = "catalog",
    required: Boolean = false,
    declares: List<ExtensionPoint<*>> = emptyList(),
    dependsOn: List<String> = emptyList(),
): PluginManifest = PluginManifest(
    id = PluginId(id),
    version = SemVer.parse(version),
    name = id,
    sdkRange = VersionRange.parse(">=1.0.0 <2.0.0"),
    loader = loader,
    dependsOn = dependsOn.map { PluginRequirement(PluginId(it)) },
    required = required,
    contributes = declares.associate { it.id to listOf(ContributionDecl("declared-" + it.id.value)) },
)

/** 可声明、可注册贡献点的测试插件。[lateRegistrations] 会在 start() 里注册  那是违规的。 */
internal class ContribPlugin(
    override val manifest: PluginManifest,
    private val registrations: List<Pair<ExtensionPoint<SlotContribution>, SlotContribution>> = emptyList(),
    private val lateRegistrations: List<Pair<ExtensionPoint<SlotContribution>, SlotContribution>> = emptyList(),
    private val initDelayMs: Long = 0,
    private val stopDelayMs: Long = 0,
) : Plugin {

    var initCompleted: Boolean = false
        private set

    private var ctxRef: PluginContext? = null

    override suspend fun init(ctx: PluginContext) {
        ctxRef = ctx
        if (initDelayMs > 0) delay(initDelayMs)
        for ((point, contribution) in registrations) {
            ctx.extensions.register(point, contribution)
        }
        initCompleted = true
    }

    override suspend fun stop() {
        if (stopDelayMs > 0) delay(stopDelayMs)
    }

    override suspend fun start() {
        val ctx = ctxRef ?: return
        for ((point, contribution) in lateRegistrations) {
            ctx.extensions.register(point, contribution)
        }
    }
}

/**
 * 模拟真实的「目录 vs 运行中」两层：catalog 里放着所有已安装的插件，
 * discover 只返回其中被启用的一部分。install() 用来把剩下的接进运行中的内核。
 */
internal class CatalogLoader(
    catalog: List<Plugin>,
    private val enabledIds: List<PluginId>,
) : PluginLoader, PluginDiscovery {

    private val catalog: List<Plugin> = catalog.toList()

    override val scheme: String = "catalog"

    override val name: String = "catalog"

    override val platforms: Set<Platform> = Platform.entries.toSet()

    override suspend fun discover(root: String): DiscoveryResult =
        DiscoveryResult(
            plugins = catalog.filter { it.manifest.id in enabledIds }
                .map { DiscoveredPlugin(it.manifest, "catalog:" + it.manifest.id.value) },
        )

    override suspend fun load(manifest: PluginManifest, ctx: PluginContext): Plugin =
        catalog.firstOrNull { it.manifest.id == manifest.id }
            ?: error("目录里没有插件 " + manifest.id.value)
}

internal fun m2Config(
    loader: PluginLoader,
    clock: KernelClock = FakeClock(1_000L),
): KernelConfig = KernelConfig(
    sdkVersion = SemVer.parse("1.0.0"),
    platform = Platform.JVM,
    loaders = listOf(loader),
    clock = clock,
)

// ---------------------------------------------------------------- 服务夹具

internal interface Greeter {
    fun greet(): String
}

internal interface Counter {
    fun next(): Int
}

internal class GreeterImpl(private val text: String = "你好") : Greeter {
    override fun greet(): String = text
}

internal class CounterImpl : Counter {
    private var value = 0
    override fun next(): Int = ++value
}

/** 能发布服务、也能索取服务的测试插件。 */
internal class ServicePlugin(
    override val manifest: PluginManifest,
    private val publishes: List<Pair<KClass<*>, Any>> = emptyList(),
    private val requires: List<KClass<*>> = emptyList(),
    private val initDelayMs: Long = 0,
    private val stopDelayMs: Long = 0,
) : Plugin {

    var acquired: List<Any> = emptyList()
        private set

    override suspend fun init(ctx: PluginContext) {
        if (initDelayMs > 0) delay(initDelayMs)
        for ((contract, instance) in publishes) {
            ctx.extensions.publish(contract.erasedToAny(), instance)
        }
        acquired = requires.map { ctx.services.require(it.erasedToAny()) }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun KClass<*>.erasedToAny(): KClass<Any> = this as KClass<Any>