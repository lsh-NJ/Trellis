package dev.trellis.dsl

import dev.trellis.kernel.api.Kernel
import dev.trellis.kernel.api.KernelConfig
import dev.trellis.kernel.core.SemVer
import dev.trellis.kernel.slot.SlotTone
import dev.trellis.kernel.slot.SlotTextSize
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TemplateSlot
import dev.trellis.kernel.spi.Platform
import dev.trellis.kernel.time.FakeClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PARTS_ROOT = "/plugins"

/**
 * 具名零件能不能从 JSON 一路走到内核里。
 *
 * 守的是「说得出来就画得出来」这条：插件写的语气、数值、进度、标签，
 * 到内核手里必须还是同一件事，不能被悄悄丢掉。
 */
class SlotPartsSchemaTest {

    private suspend fun load(entry: String): TemplateSlot {
        val fs = InMemoryFileSystem(
            mapOf(
                "$PARTS_ROOT/p/manifest.json" to """{"id":"p","version":"1.0.0","name":"p"}""",
                "$PARTS_ROOT/p/main.dsl.json" to entry,
            ),
        )
        val kernel = Kernel.boot(
            KernelConfig(
                sdkVersion = SemVer.parse("1.0.0"),
                platform = Platform.JVM,
                pluginsDir = PARTS_ROOT,
                loaders = listOf(DslLoader(fs, FakeClock(1_000L))),
                clock = FakeClock(1_000L),
            ),
        )
        val found = kernel.extensions.all(Slots.HomeSection).filterIsInstance<TemplateSlot>()
        check(found.isNotEmpty()) {
            "没有卡片，内核报错：" + kernel.errors.joinToString(" | ") { it.code.code + " " + it.detail }
        }
        return found.first()
    }

    @Test
    fun namedPartsSurviveTheTripIntoTheKernel() = runTest {
        val slot = load(
            """{"slots":[{"point":"slot.home.section","id":"c","title":"今天到期","body":"还有两件事", """ +
                """"titleTone":"muted","titleSize":"large","bodyTone":"accent",""" +
                """"stat":{"value":"{{ summary.dueToday }}","unit":"项","label":"今天到期","tone":"danger"},""" +
                """"progress":{"value":"{{ summary.done }}","max":"{{ summary.total }}","label":"进度"},""" +
                """"badges":[{"text":"逾期","tone":"danger"}],""" +
                """"actions":[{"action":"addTask","label":"加一条","args":{"title":"x"},"tone":"accent","disabled":"先建一个任务"}]}]}""",
        )

        assertEquals(SlotTone.DANGER, slot.stat?.tone)
        assertEquals("{{ summary.dueToday }}", slot.stat?.value)
        assertEquals("项", slot.stat?.unit)
        assertEquals("{{ summary.total }}", slot.progress?.max)
        assertEquals(1, slot.badges.size)
        assertEquals(SlotTone.DANGER, slot.badges[0].tone)
        assertEquals(SlotTone.MUTED, slot.titleTone)
        assertEquals(SlotTextSize.LARGE, slot.titleSize)
        assertEquals(SlotTone.ACCENT, slot.bodyTone)
        assertEquals(SlotTone.ACCENT, slot.actions[0].tone)
        assertEquals("先建一个任务", slot.actions[0].disabledReason)
    }

    @Test
    fun aMisspelledToneNeverStopsTheWholePlugin() = runTest {
        // 拼错一个颜色不该让插件装不上，但也不能悄悄变默认值 —— 加载器会记一条日志。
        // 必须带一个具名零件，否则加载器会把它当成纯文字卡片（TextSlot），测不到语气。
        val slot = load("""{"slots":[{"point":"slot.home.section","id":"c","title":"t","body":"b","titleTone":"红色","stat":{"value":"1","label":"条"}}]}""")

        assertEquals(SlotTone.DEFAULT, slot.titleTone)
    }
}
