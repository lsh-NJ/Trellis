package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 「今天已经提醒过」的标记：能读回来、坏文件不炸、可以每天覆盖。 */
class PlanCheckStoreTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-plan-check-test").toFile()

    @Test
    fun theLastNotifiedDayRoundTrips() {
        val home = tempHome()
        val store = PlanCheckStore(home)
        assertNull(store.lastDay(), "第一次启动没有记录")

        store.save("2026-11-02")
        assertEquals("2026-11-02", PlanCheckStore(home).lastDay())
    }

    @Test
    fun savingAgainReplacesTheDay() {
        val home = tempHome()
        PlanCheckStore(home).save("2026-11-02")
        PlanCheckStore(home).save("2026-11-03")
        assertEquals("2026-11-03", PlanCheckStore(home).lastDay())
    }

    @Test
    fun aCorruptFileMeansNotYetNotified() {
        val home = tempHome()
        File(home, "plan-check.json").writeText("{ nope", Charsets.UTF_8)
        assertNull(PlanCheckStore(home).lastDay())
    }

    @Test
    fun aBomDoesNotHideTheDay() {
        val home = tempHome()
        File(home, "plan-check.json").writeText("\uFEFF{\"lastDay\":\"2026-11-02\"}", Charsets.UTF_8)
        assertEquals("2026-11-02", PlanCheckStore(home).lastDay())
    }
}
