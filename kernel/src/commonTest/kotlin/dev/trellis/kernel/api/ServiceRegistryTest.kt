package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.extension.ServiceUnavailableException
import dev.trellis.kernel.plugin.PluginState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun greeter(text: String) = Greeter::class to GreeterImpl(text)

private suspend fun boot(vararg plugins: dev.trellis.kernel.plugin.Plugin) = Kernel.boot(
    m2Config(CatalogLoader(plugins.toList(), plugins.map { it.manifest.id })),
)

class ServiceRegistryTest {

    @Test
    fun aPluginCanProvideAServiceThatAnotherPluginConsumes() = runTest {
        val provider = ServicePlugin(
            m2Manifest("dev.provider", declares = listOf(KernelPoints.Service)),
            publishes = listOf(greeter("来自 provider")),
        )
        val consumer = ServicePlugin(
            m2Manifest("dev.consumer", dependsOn = listOf("dev.provider")),
            requires = listOf(Greeter::class),
        )
        val kernel = boot(provider, consumer)

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.consumer"))!!.state)
        assertEquals("来自 provider", (consumer.acquired.single() as Greeter).greet())
    }

    @Test
    fun theConsumedServiceIsTheVerySameInstanceTheProviderPublished() = runTest {
        val provider = ServicePlugin(
            m2Manifest("dev.provider", declares = listOf(KernelPoints.Service)),
            publishes = listOf(Counter::class to CounterImpl()),
        )
        val consumer = ServicePlugin(
            m2Manifest("dev.consumer", dependsOn = listOf("dev.provider")),
            requires = listOf(Counter::class),
        )
        val kernel = boot(provider, consumer)

        val counter = consumer.acquired.single() as Counter
        assertEquals(1, counter.next())
        assertEquals(2, counter.next(), "必须是同一个实例，不是拷贝")
        assertSame(kernel.extensions.service(Counter::class), counter)
    }

    @Test
    fun requiringAServiceNobodyProvidesFailsThePluginWithAUsefulMessage() = runTest {
        val orphan = ServicePlugin(m2Manifest("dev.orphan"), requires = listOf(Greeter::class))
        val kernel = boot(orphan)

        val info = kernel.plugin(PluginId("dev.orphan"))!!
        assertEquals(PluginState.FAILED, info.state)
        assertEquals(ErrorCode.PLUGIN_INIT_FAILED, info.errors.first().code)
        assertTrue(info.errors.first().detail.contains("Greeter"), info.errors.first().detail)
        assertTrue(
            info.errors.first().detail.contains("没有任何服务被发布"),
            "报错要说清是「没人提供」还是「名字写错了」：" + info.errors.first().detail,
        )
    }

    @Test
    fun aFailedConsumerDoesNotTakeDownTheProvider() = runTest {
        val provider = ServicePlugin(
            m2Manifest("dev.provider", declares = listOf(KernelPoints.Service)),
            publishes = listOf(greeter("我没事")),
        )
        val consumer = ServicePlugin(
            m2Manifest("dev.consumer", dependsOn = listOf("dev.provider")),
            requires = listOf(Counter::class),
        )
        val kernel = boot(provider, consumer)

        assertEquals(PluginState.ACTIVE, kernel.plugin(PluginId("dev.provider"))!!.state)
        assertEquals(PluginState.FAILED, kernel.plugin(PluginId("dev.consumer"))!!.state)
        assertEquals(KernelState.ACTIVE, kernel.currentState, "消费者失败不该让内核降级")
    }

    @Test
    fun publishingWithoutDeclaringKernelServiceIsRejected() = runTest {
        val sneaky = ServicePlugin(
            m2Manifest("dev.sneaky"),
            publishes = listOf(greeter("偷偷提供")),
        )
        val kernel = boot(sneaky)

        assertNull(kernel.extensions.service(Greeter::class), "未声明的服务绝不能生效")
        val info = kernel.plugin(PluginId("dev.sneaky"))!!
        assertEquals(PluginState.ACTIVE, info.state)
        assertTrue(info.errors.any { it.code == ErrorCode.CONTRIBUTION_REJECTED }, info.errors.toString())
    }

    @Test
    fun twoPluginsProvidingTheSameContractProduceK007AndADeterministicWinner() = runTest {
        val a = ServicePlugin(
            m2Manifest("dev.a", declares = listOf(KernelPoints.Service)),
            publishes = listOf(greeter("A")),
        )
        val b = ServicePlugin(
            m2Manifest("dev.b", declares = listOf(KernelPoints.Service)),
            publishes = listOf(greeter("B")),
        )
        val kernel = boot(a, b)

        assertEquals("A", kernel.extensions.service(Greeter::class)!!.greet(), "按属主名取第一个，结果必须确定")
        assertTrue(
            kernel.errors.any { it.code == ErrorCode.CONTRIBUTION_CONFLICT },
            "静默覆盖不可接受：" + kernel.errors.toString(),
        )
    }

    @Test
    fun uninstallingTheProviderPromotesTheRunnerUpThenRemovesTheServiceEntirely() = runTest {
        val a = ServicePlugin(
            m2Manifest("dev.a", declares = listOf(KernelPoints.Service)),
            publishes = listOf(greeter("A")),
        )
        val b = ServicePlugin(
            m2Manifest("dev.b", declares = listOf(KernelPoints.Service)),
            publishes = listOf(greeter("B")),
        )
        val kernel = boot(a, b)
        assertEquals("A", kernel.extensions.service(Greeter::class)!!.greet())

        assertEquals(UninstallResult.Uninstalled(1), kernel.uninstall(PluginId("dev.a")))
        assertEquals("B", kernel.extensions.service(Greeter::class)!!.greet(), "赢家走了，第二名顶上")

        assertEquals(UninstallResult.Uninstalled(1), kernel.uninstall(PluginId("dev.b")))
        assertNull(kernel.extensions.service(Greeter::class))
        assertTrue(kernel.extensions.serviceContracts().isEmpty(), "不能有任何残留")
    }

    @Test
    fun serviceUnavailableExceptionIsAnIllegalStateSoGuardedLifecycleTurnsItIntoFailure() {
        val e = ServiceUnavailableException(Greeter::class, "没人提供")
        assertTrue(e is IllegalStateException, "必须是 IllegalStateException，才能被 runLifecycle 的守卫兜住")
        assertEquals(Greeter::class, e.contract)
    }
}