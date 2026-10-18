package dev.trellis.kernel.api

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.extension.DataProvider
import dev.trellis.kernel.extension.ExtensionRegistry
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.extension.Template
import dev.trellis.kernel.plugin.Plugin
import dev.trellis.kernel.plugin.PluginContext
import dev.trellis.kernel.plugin.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private class FixedProvider(
    override val id: String,
    override val order: Int = 100,
    private val values: Map<String, String> = emptyMap(),
    private val boom: Boolean = false,
) : DataProvider {
    override suspend fun resolve(path: String, context: DataContext): String? {
        if (boom) error("这个提供者炸了")
        return values[path]
    }
}

internal class DataPlugin(
    override val manifest: PluginManifest,
    private val providers: List<DataProvider>,
) : Plugin {
    override suspend fun init(ctx: PluginContext) {
        for (provider in providers) ctx.extensions.register(KernelPoints.Data, provider)
    }
}

class TemplateSyntaxTest {

    @Test
    fun parsesNamespaceAndPath() {
        val found = Template.parse("你好 {{ summary.todo }} 项")
        assertEquals(1, found.size)
        assertEquals("summary", found.single().namespace)
        assertEquals("todo", found.single().path)
        assertEquals("你好 3 项", "你好 {{ summary.todo }} 项".replaceRange(
            found.single().start, found.single().end, "3",
        ))
    }

    @Test
    fun allowsSpacesInsideTheBraces() {
        val found = Template.parse("{{  summary.todo  }}")
        assertEquals("summary", found.single().namespace)
        assertEquals("todo", found.single().path)
    }

    /**
     * 不成形的一律**原样保留**，不吞掉。
     *
     * 宁可让作者看见自己写歪了的那一坨，也不要悄悄吃掉他写的东西。
     */
    @Test
    fun leavesMalformedPlaceholdersAlone() {
        for (text in listOf(
            "没有闭合 {{ summary.todo",
            "没有点号 {{ summary }}",
            "里面是空的 {{ }}",
            "只有点 {{ . }}",
            "没有占位符",
        )) {
            assertEquals(emptyList(), Template.parse(text), "不该把这一段当占位符：" + text)
        }
    }

    @Test
    fun keepsMultiplePlaceholdersInOrder() {
        val found = Template.parse("{{ a.x }}-{{ b.y }}-{{ c.z }}")
        assertEquals(listOf("a", "b", "c"), found.map { it.namespace })
    }
}

/** 测试里固定一个「今天」：00:00 到 24:00。真实界面会按时区算。 */
private val CONTEXT = DataContext.ofNow(nowMs = 1_000L)

private suspend fun ExtensionRegistry.render(
    template: String,
    onFailure: (String) -> Unit = {},
): String = renderTemplate(template, CONTEXT, onFailure)

class DataBindingTest {

    private suspend fun bootWith(vararg providers: DataProvider): Kernel {
        val plugin = DataPlugin(
            m2Manifest("dev.data", declares = listOf(KernelPoints.Data)),
            providers.toList(),
        )
        return Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.data")))),
        )
    }

    @Test
    fun resolvesPlaceholdersThroughRegisteredProviders() = runTest {
        val kernel = bootWith(
            FixedProvider("summary", values = mapOf("todo" to "3", "overdue" to "1")),
        )

        assertEquals(
            "3 项待办，1 项逾期",
            kernel.extensions.render("{{ summary.todo }} 项待办，{{ summary.overdue }} 项逾期"),
        )
        kernel.shutdown()
    }

    /** 取不到值 -> 那一段变成 `--`，其余照常。**不是整张卡片消失。** */
    @Test
    fun missingValuesDegradeToOnePlaceholderOnly() = runTest {
        val kernel = bootWith(FixedProvider("summary", values = mapOf("todo" to "3")))
        val failures = mutableListOf<String>()

        val text = kernel.extensions.render(
            "{{ nope.x }} / {{ summary.nope }} / {{ summary.todo }}",
        ) { failures += it }

        assertEquals("-- / -- / 3", text)
        assertEquals(2, failures.size, "每一次取不到都该留下痕迹：" + failures)
        kernel.shutdown()
    }

    /** 提供者抛异常 -> 也只坏它自己的那一段。 */
    @Test
    fun aThrowingProviderOnlyBreaksItsOwnPlaceholder() = runTest {
        val kernel = bootWith(
            FixedProvider("bad", order = 10, boom = true),
            FixedProvider("good", order = 20, values = mapOf("x" to "OK")),
        )

        assertEquals("-- 然后 OK", kernel.extensions.render("{{ bad.x }} 然后 {{ good.x }}"))
        kernel.shutdown()
    }

    /** 两个插件抢同一个命名空间：报 K007，取 order 更小的那个  确定的，不是随机的。 */
    @Test
    fun twoProvidersClaimingTheSameNamespaceAreReported() = runTest {
        val kernel = bootWith(
            FixedProvider("summary", order = 10, values = mapOf("todo" to "A")),
            FixedProvider("summary", order = 20, values = mapOf("todo" to "B")),
        )

        assertTrue(
            kernel.errors.any { it.code == ErrorCode.CONTRIBUTION_CONFLICT },
            "静默覆盖不可接受：" + kernel.errors.toString(),
        )
        assertEquals("A", kernel.extensions.render("{{ summary.todo }}"))
        kernel.shutdown()
    }

    /** 没声明 kernel.data 就想注册数据源 -> 被拒（和扩展点、服务同一道闸门）。 */
    @Test
    fun registeringDataWithoutDeclaringItIsRejected() = runTest {
        val plugin = DataPlugin(
            m2Manifest("dev.sneaky"),
            listOf(FixedProvider("summary", values = mapOf("todo" to "偷来的"))),
        )
        val kernel = Kernel.boot(
            m2Config(CatalogLoader(listOf(plugin), listOf(PluginId("dev.sneaky")))),
        )

        assertEquals("--", kernel.extensions.render("{{ summary.todo }}"))
        assertTrue(
            kernel.plugin(PluginId("dev.sneaky"))!!.errors.any { it.code == ErrorCode.CONTRIBUTION_REJECTED },
        )
        kernel.shutdown()
    }
}
