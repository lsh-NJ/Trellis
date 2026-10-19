package dev.trellis.dsl

import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.extension.DataProvider
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TemplateSlot
import dev.trellis.kernel.slot.TextSlot
import dev.trellis.kernel.spi.BuiltinLoader
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val ROOT = "/plugins"

private class FakeSummaryProvider(
    override val id: String = "summary",
    override val order: Int = 10,
) : DataProvider {
    var calls = 0
        private set

    override suspend fun resolve(path: String, context: DataContext): String? {
        calls++
        return when (path) {
            "todo" -> "7"
            "total" -> "10"
            "overdue" -> "2"
            else -> null
        }
    }
}

private class ProviderPlugin(
    override val manifest: PluginManifest,
    private val provider: DataProvider,
) : Plugin {
    override suspend fun init(ctx: PluginContext) {
        ctx.extensions.register(KernelPoints.Data, provider)
    }
}

/**
 * 轨道 A 的**端到端**验证：一份纯 JSON 的卡片，内容是从真实数据算出来的。
 *
 * 这条测试是整条链路的汇合点：
 *   扩展点  数据提供者  模板解析  求值  渲染
 * 而插件那一侧，从头到尾只有两个 JSON 文件。
 */
private val CONTEXT = DataContext.ofNow(nowMs = 1_000L)

private suspend fun ExtensionRegistry.render(template: String): String =
    renderTemplate(template, CONTEXT)

class DslDataBindingTest {

    private fun jsonPluginFiles(pluginDir: String, manifest: String, entry: String) = mapOf(
        "$ROOT/$pluginDir/manifest.json" to manifest,
        "$ROOT/$pluginDir/main.dsl.json" to entry,
    )

    private suspend fun boot(
        fs: InMemoryFileSystem,
        provider: DataProvider,
        clock: FakeClock = FakeClock(1_000L),
    ): Kernel {
        val providerPlugin = ProviderPlugin(
            PluginManifest(
                id = PluginId("dev.data"),
                version = SemVer.parse("1.0.0"),
                name = "数据源",
                loader = "builtin",
                contributes = mapOf(KernelPoints.Data.id to emptyList()),
            ),
            provider,
        )
        return Kernel.boot(
            KernelConfig(
                sdkVersion = SemVer.parse("1.0.0"),
                platform = Platform.JVM,
                pluginsDir = ROOT,
                loaders = listOf(
                    BuiltinLoader(listOf(providerPlugin)),
                    DslLoader(fs, clock),
                ),
                clock = clock,
            ),
        )
    }

    @Test
    fun aJsonCardWithPlaceholdersBecomesATemplateSlotAndResolves() = runTest {
        val fs = InMemoryFileSystem(
            jsonPluginFiles(
                "ai.stats",
                """{"id":"ai.stats","version":"1.0.0","name":"统计","dependsOn":[{"id":"dev.data","range":">=1.0.0"}]}""",
                """{"slots":[{"point":"slot.home.header","id":"stats","title":"今日","body":"{{ summary.todo }} 项待办，{{ summary.overdue }} 项逾期"}]}""",
            ),
        )
        val kernel = boot(fs, FakeSummaryProvider())

        val card = assertIs<TemplateSlot>(
            kernel.extensions.all(Slots.HomeHeader).single(),
            "带占位符的卡片应当是 TemplateSlot，而不是别的贡献类型",
        )

        assertEquals("7 项待办，2 项逾期", kernel.extensions.render(card.body))
        kernel.shutdown()
    }

    /** 没有占位符的卡片保持静态  它没有「重新求值」这回事，不该白跑一遍求值器。 */
    @Test
    fun aJsonCardWithoutPlaceholdersStaysStatic() = runTest {
        val fs = InMemoryFileSystem(
            jsonPluginFiles(
                "ai.static",
                """{"id":"ai.static","version":"1.0.0","name":"静态","dependsOn":[{"id":"dev.data","range":">=1.0.0"}]}""",
                """{"slots":[{"point":"slot.home.header","id":"static","title":"写死的","body":"还剩 18:20"}]}""",
            ),
        )
        val provider = FakeSummaryProvider()
        val kernel = boot(fs, provider)

        assertIs<TextSlot>(
            kernel.extensions.all(Slots.HomeHeader).single(),
            "静态卡片应当是 TextSlot，而不是 TemplateSlot",
        )
        assertEquals(0, provider.calls, "静态卡片不该去问数据源")
        kernel.shutdown()
    }

    /** 数据源不在 -> 那一段变成 `--`，卡片照样画出来。 */
    @Test
    fun aMissingDataProviderDegradesToOnePlaceholder() = runTest {
        val fs = InMemoryFileSystem(
            jsonPluginFiles(
                "ai.orphan",
                """{"id":"ai.orphan","version":"1.0.0","name":"孤儿"}""",
                """{"slots":[{"point":"slot.home.header","id":"x","title":"没有数据源","body":"{{ nothing.here }} 项"}]}""",
            ),
        )
        val kernel = boot(fs, FakeSummaryProvider())

        val card = assertIs<TemplateSlot>(kernel.extensions.all(Slots.HomeHeader).single())
        assertEquals("-- 项", kernel.extensions.render(card.body))
        kernel.shutdown()
    }

    /**
     * 这一条是「让插件自己造字段」的端到端证明。
     *
     * 没有任何 Kotlin 代码参与计算：插件声明了 `rate` 这个字段，
     * 表达式是 percent(summary.todo, summary.total)，内核负责取数、求值，
     * 再把结果塞进模板的 {{ self.rate }}。
     *
     * 从此插件不再只是「挑我们给好的字段」，它可以自己造。
     */
    @Test
    fun aPluginCanDefineItsOwnComputedFields() = runTest {
        val fs = InMemoryFileSystem(
            jsonPluginFiles(
                "ai.rate",
                """{"id":"ai.rate","version":"1.0.0","name":"完成率","usage":"在首页看","dependsOn":[{"id":"dev.data","range":">=1.0.0"}]}""",
                """{"fields":{"rate":"percent(summary.todo, summary.total)"},"slots":[{"point":"slot.home.header","id":"r","title":"进度","body":"还剩 {{ self.rate }}%"}]}""",
            ),
        )
        val kernel = boot(fs, FakeSummaryProvider())

        val card = assertIs<TemplateSlot>(kernel.extensions.all(Slots.HomeHeader).single())
        assertTrue(card.owner != null, "卡片要记住自己属于谁，否则 self 找不到归属")

        // todo=7, total=10 -> 70%
        assertEquals(
            "还剩 70%",
            kernel.extensions.renderTemplate(card.body, CONTEXT, owner = card.owner),
        )
        kernel.shutdown()
    }

    /** 字段表达式里引用 self 就是自己引用自己，加载时就该被拒。 */
    @Test
    fun aFieldMayNotReferenceItself() = runTest {
        val fs = InMemoryFileSystem(
            jsonPluginFiles(
                "ai.loop",
                """{"id":"ai.loop","version":"1.0.0","name":"自引用"}""",
                """{"fields":{"a":"self.b + 1","b":"self.a + 1"},"slots":[]}""",
            ),
        )
        val kernel = boot(fs, FakeSummaryProvider())

        assertTrue(
            kernel.errors.any { it.detail.contains("自己引用自己") },
            "要明确拒绝自引用：" + kernel.errors.map { it.detail },
        )
        kernel.shutdown()
    }

}
