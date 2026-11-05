package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 管家的落盘：设置、checkpoint、记忆、摘要、旧计划检查迁移。 */
class StewardStoreTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-steward-store").toFile()

    @Test
    fun settingsDefaultAndSurviveAReload() {
        val home = tempHome()
        val store = StewardStore(home)
        assertEquals("08:30", store.settings().morningTime)
        assertEquals("22:30", store.settings().eveningTime)
        assertTrue(store.settings().morningEnabled)

        store.saveSettings(StewardSettings(morningTime = "09:15", eveningTime = "23:00", notifyDesktop = false))

        // 换实例读：设置要能落盘。
        val again = StewardStore(home).settings()
        assertEquals("09:15", again.morningTime)
        assertEquals("23:00", again.eveningTime)
        assertEquals(false, again.notifyDesktop)
    }

    @Test
    fun badSettingsFallBackToDefaults() {
        val home = tempHome()
        File(home, "steward").mkdirs()
        File(File(home, "steward"), "settings.json").writeText("not json", Charsets.UTF_8)
        assertEquals(StewardSettings(), StewardStore(home).settings())

        // 手改坏了的时间退回默认，不把程序带崩。
        assertEquals("08:30", normalizeStewardTime("25:00", "08:30"))
        assertEquals("08:30", normalizeStewardTime("7:5", "08:30"))
        assertEquals("07:05", normalizeStewardTime("7:05", "08:30"))
    }

    @Test
    fun checkpointsSurviveAReload() {
        val home = tempHome()
        StewardStore(home).saveCheckpoints(
            StewardCheckpoints(lastMorningDay = "2026-11-04", lastEveningDay = "2026-11-03"),
        )
        val again = StewardStore(home).checkpoints()
        assertEquals("2026-11-04", again.lastMorningDay)
        assertEquals("2026-11-03", again.lastEveningDay)
    }

    @Test
    fun memoryIsAppendOnlyAndReadByTimeWindow() {
        val home = tempHome()
        val store = StewardStore(home)
        store.appendMemory(StewardMemoryEvent(1, "2026-11-01", StewardMemoryKind.Morning, "旧"))
        store.appendMemory(StewardMemoryEvent(2, "2026-11-02", StewardMemoryKind.Evening, "中"))
        store.appendMemory(StewardMemoryEvent(3, "2026-11-04", StewardMemoryKind.Morning, "新"))

        // 3 天窗口：2026-11-02..2026-11-04，按日期正序。
        val events = StewardStore(home).recentMemory(today = "2026-11-04", days = 3)
        assertEquals(listOf("中", "新"), events.map { it.text })

        // limit 从尾部取：新的优先，再翻回正序。
        assertEquals(listOf("新"), StewardStore(home).recentMemory(today = "2026-11-04", days = 3, limit = 1).map { it.text })
        assertTrue(StewardStore(home).recentMemory(today = "2026-11-04", days = 30).size == 3)
        assertTrue(StewardStore(home).recentMemory(today = "2026-10-01", days = 3).isEmpty())
        assertNull(store.recentMemory(today = "不是日期", days = 3).firstOrNull())
    }

    @Test
    fun digestsAreWrittenPerDayAndReadNewestLast() {
        val home = tempHome()
        val store = StewardStore(home)
        store.saveDigest("2026-11-02", "周一总结")
        store.saveDigest("2026-11-03", "周二总结")

        assertEquals("周一总结", StewardStore(home).digest("2026-11-02")?.trim())
        assertNull(StewardStore(home).digest("2026-11-01"))

        // 旧 → 新。
        assertEquals(
            listOf("周一总结", "周二总结"),
            StewardStore(home).recentDigests(today = "2026-11-04", days = 3).map { it.second.trim() },
        )
    }

    @Test
    fun legacyPlanCheckFilesAreRemoved() {
        val home = tempHome()
        File(home, "chat").mkdirs()
        val store = File(home, "plan-check.json").apply { writeText("{\"lastDay\":\"2026-11-03\"}") }
        val chat = File(File(home, "chat"), "plan-check.json").apply { writeText("{\"id\":\"plan-check\"}") }

        assertTrue(StewardStore(home).migrateLegacyPlanCheck(), "迁移应该删掉旧文件")
        assertTrue(!store.exists())
        assertTrue(!chat.exists())
        // 再跑一次不报错（幂等）。
        assertTrue(StewardStore(home).migrateLegacyPlanCheck())
    }
}
