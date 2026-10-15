package dev.trellis.dsl

import dev.trellis.kernel.api.InstallResult
import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.api.KernelState
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.diag.ErrorCode
import dev.trellis.kernel.slot.ConsoleSlotRenderer
import dev.trellis.kernel.slot.SlotHost
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.time.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private const val ROOT = "/plugins"

private fun pluginFiles(directory: String, manifest: String, entry: String): Map<String, String> = mapOf(
    "$directory/manifest.json" to manifest,
    "$directory/main.dsl.json" to entry,
)

private fun manifestJson(id: String, name: String = id): String =
    """{"id":"$id","version":"1.0.0","name":"$name"}"""

private fun entryJson(vararg slots: String): String =
    """{"slots":[""" + slots.joinToString(",") + "]}"

private fun slotJson(
    point: String = "slot.home.header",
    id: String,
    order: Int = 100,
    title: String = "",
    body: String = "",
): String = """{"point":"$point","id":"$id","order":$order,"title":"$title","body":"$body"}"""

private fun kernelConfig(fs: PluginFileSystem): KernelConfig = KernelConfig(
    sdkVersion = SemVer.parse("1.0.0"),
    platform = Platform.JVM,
    pluginsDir = ROOT,
    loaders = listOf(DslLoader(fs, FakeClock(1_000L))),
    clock = FakeClock(1_000L),
)

class DslLoaderTest {

    // ---- M5 的核心命题：纯 JSON 的插件，在内核眼里和 Kotlin 插件完全等价 ----

    @Test
    fun aPluginMadeOfNothingButJsonBecomesACard() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/ai.review",
                manifestJson("ai.review", "艾宾浩斯复习"),
                entryJson(slotJson(id = "due", order = 50, title = "待复习", body = "今天有 12 张卡片到期")),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))
        val host = SlotHost(kernel.extensions, ConsoleSlotRenderer())

        assertEquals(KernelState.ACTIVE, kernel.currentState)
        val lines = host.render(Slots.HomeHeader)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("待复习"), lines[0])
        assertTrue(lines[0].contains("12 张"), lines[0])
    }

    /**
     * 这一条才是 M5 真正想要的性质：
     * **往磁盘上写一份新 JSON，然后 install，界面就长出新东西  没有编译，没有重启。**
     */
    @Test
    fun writingANewJsonFileAndInstallingMakesANewCardAppear() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/ai.review",
                manifestJson("ai.review"),
                entryJson(slotJson(id = "due", order = 100, title = "待复习", body = "12 张")),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))
        val host = SlotHost(kernel.extensions, ConsoleSlotRenderer())
        assertEquals(1, host.render(Slots.HomeHeader).size)

        // 假装这是 Agent 刚写完的两份文件
        fs.write("$ROOT/ai.streak/manifest.json", manifestJson("ai.streak", "连续打卡"))
        fs.write(
            "$ROOT/ai.streak/main.dsl.json",
            entryJson(slotJson(id = "streak", order = 10, title = "连续 7 天", body = "别断")),
        )

        val result = kernel.install(PluginId("ai.streak"))
        assertTrue(result is InstallResult.Installed, result.toString())

        val lines = host.render(Slots.HomeHeader)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("连续 7 天"), "order 10 应排在 order 100 之前：" + lines[0])
        assertTrue(lines[1].contains("待复习"))
    }

    @Test
    fun contributesIsDerivedFromContentAndNeverNeedsToBeHandWritten() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/ai.review",
                manifestJson("ai.review"),
                entryJson(
                    slotJson(id = "a"),
                    slotJson(point = "slot.settings.section", id = "b"),
                ),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        assertEquals(
            setOf(Slots.HomeHeader.id, Slots.SettingsSection.id),
            kernel.extensions.pointIds(),
        )
    }

    @Test
    fun slotsAcrossPluginsAreMergedByOrder() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles("$ROOT/p1", manifestJson("p1"), entryJson(slotJson(id = "p1", order = 300, title = "第三"))),
            pluginFiles("$ROOT/p2", manifestJson("p2"), entryJson(slotJson(id = "p2", order = 100, title = "第一"))),
            pluginFiles("$ROOT/p3", manifestJson("p3"), entryJson(slotJson(id = "p3", order = 200, title = "第二"))),
        )
        val kernel = Kernel.boot(kernelConfig(fs))
        val lines = SlotHost(kernel.extensions, ConsoleSlotRenderer()).render(Slots.HomeHeader)

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("第一"), lines[0])
        assertTrue(lines[1].contains("第二"), lines[1])
        assertTrue(lines[2].contains("第三"), lines[2])
    }

    // ---- 坏内容必须被挡住，而且不能连累邻居 ----

    @Test
    fun oneBrokenManifestDoesNotHideItsHealthyNeighbours() = runTest {
        val fs = InMemoryFileSystem(
            mapOf("$ROOT/broken/manifest.json" to """{"id":"","version":"1.0.0"}""") +
                pluginFiles("$ROOT/good", manifestJson("good"), entryJson(slotJson(id = "ok", title = "我没事"))),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        assertEquals(KernelState.ACTIVE, kernel.currentState)
        val lines = SlotHost(kernel.extensions, ConsoleSlotRenderer()).render(Slots.HomeHeader)
        assertEquals(1, lines.size, "一个坏 manifest 不该让好插件一起消失")
        assertTrue(lines[0].contains("我没事"))
        assertTrue(kernel.errors.any { it.code == ErrorCode.MANIFEST_INVALID }, kernel.errors.toString())
    }

    @Test
    fun anUnknownSlotPointIsRejectedAndTheKnownOnesAreListed() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/typo",
                manifestJson("typo"),
                entryJson(slotJson(point = "slot.home.hedaer", id = "oops")),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        assertEquals(0, SlotHost(kernel.extensions, ConsoleSlotRenderer()).render(Slots.HomeHeader).size)
        val error = kernel.errors.first { it.code == ErrorCode.MANIFEST_INVALID }
        assertTrue(error.detail.contains("slot.home.hedaer"), error.detail)
        assertTrue(error.detail.contains("slot.home.header"), "报错应当把可用挂载点列出来：" + error.detail)
    }

    @Test
    fun aTypoInAFieldNameIsLoudlyRejectedInsteadOfSilentlyDefaulting() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/typo",
                manifestJson("typo"),
                """{"slots":[{"point":"slot.home.header","id":"x","tittle":"我拼错了"}]}""",
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        assertEquals(0, SlotHost(kernel.extensions, ConsoleSlotRenderer()).render(Slots.HomeHeader).size)
        val error = kernel.errors.first { it.code == ErrorCode.MANIFEST_INVALID }
        assertTrue(error.detail.contains("tittle"), "必须指出是哪个字段拼错了：" + error.detail)
    }

    @Test
    fun aMissingEntryFileIsRejected() = runTest {
        val fs = InMemoryFileSystem(
            mapOf("$ROOT/lost/manifest.json" to """{"id":"lost","version":"1.0.0","entry":"nowhere.json"}"""),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        val error = kernel.errors.first { it.code == ErrorCode.MANIFEST_INVALID }
        assertTrue(error.detail.contains("entry 文件不存在"), error.detail)
        assertTrue(error.detail.contains("nowhere.json"), error.detail)
    }

    @Test
    fun duplicateSlotIdsInsideOnePluginAreRejected() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/dup",
                manifestJson("dup"),
                entryJson(slotJson(id = "same", title = "第一个"), slotJson(id = "same", title = "第二个")),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        assertEquals(0, SlotHost(kernel.extensions, ConsoleSlotRenderer()).render(Slots.HomeHeader).size)
        assertTrue(kernel.errors.any { it.detail.contains("重复") }, kernel.errors.toString())
    }

    @Test
    fun allProblemsInOneEntryFileAreReportedAtOnce() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/messy",
                manifestJson("messy"),
                entryJson(
                    slotJson(point = "slot.nope", id = "a"),
                    slotJson(id = "b", order = -5),
                ),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        val errors = kernel.errors.filter { it.code == ErrorCode.MANIFEST_INVALID }
        assertEquals(2, errors.size, "两个问题应当一次报完，而不是让作者改一个跑一次：" + errors.toString())
    }

    // ---- 依赖声明走的是同一套解析 ----

    @Test
    fun aJsonPluginCanDeclareADependencyOnAnotherJsonPlugin() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/base",
                """{"id":"base","version":"1.0.0","name":"基础"}""",
                entryJson(slotJson(id = "base-card", title = "底座")),
            ),
            pluginFiles(
                "$ROOT/on-top",
                """{"id":"on-top","version":"1.0.0","name":"上层","dependsOn":[{"id":"base","range":">=1.0.0"}]}""",
                entryJson(slotJson(id = "top-card", order = 200, title = "上层")),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))
        val lines = SlotHost(kernel.extensions, ConsoleSlotRenderer()).render(Slots.HomeHeader)

        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("底座"), "被依赖者应当先启动")
        assertTrue(lines[1].contains("上层"))
    }

    @Test
    fun aJsonPluginWithAMissingDependencyIsSkippedNotFatal() = runTest {
        val fs = InMemoryFileSystem(
            pluginFiles(
                "$ROOT/orphan",
                """{"id":"orphan","version":"1.0.0","dependsOn":[{"id":"ghost"}]}""",
                entryJson(slotJson(id = "x")),
            ),
        )
        val kernel = Kernel.boot(kernelConfig(fs))

        assertEquals(KernelState.ACTIVE, kernel.currentState)
        assertTrue(kernel.errors.any { it.code == ErrorCode.MISSING_DEPENDENCY }, kernel.errors.toString())
    }
}