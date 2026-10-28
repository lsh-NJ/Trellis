package dev.trellis.app.desktop

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「每天预计投入」是宿主唯一自己认的设置项：归一化、落盘、坏文件兜底。
 *
 * 重点钉住乘 7 之后仍然落在后端那道 CHECK 的区间里 —— 否则设置页能存、
 * 建计划那一步会失败，而失败信息出现在几页之外的对话里。
 */
class StudyPrefsTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-study-prefs-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun defaultIsOneHourADay() {
        assertEquals(60, StudyPrefs().dailyMinutes)
        assertEquals(420, StudyPrefs().weeklyMinutes)
    }

    @Test
    fun outOfRangeValuesAreClampedInsteadOfRejected() {
        assertEquals(MIN_DAILY_MINUTES, StudyPrefs(dailyMinutes = 1).normalized().dailyMinutes)
        assertEquals(MAX_DAILY_MINUTES, StudyPrefs(dailyMinutes = 9999).normalized().dailyMinutes)
    }

    @Test
    fun weeklyCapacityStaysInsideTheBackendRange() {
        // 后端 PlanCommands 里那道 CHECK 是 30–3000 分钟。
        assertTrue(MIN_DAILY_MINUTES * 7 >= 30)
        assertTrue(MAX_DAILY_MINUTES * 7 <= 3000)
    }

    @Test
    fun describeTurnsMinutesIntoHours() {
        val text = StudyPrefs(dailyMinutes = 90).describe()
        assertTrue(text.contains("90 分钟"))
        assertTrue(text.contains("一周约 10 小时"))
    }

    @Test
    fun savedPrefsComeBack() {
        val home = tempHome()
        val store = StudyPrefsStore(home)
        store.save(StudyPrefs(dailyMinutes = 105))
        assertEquals(105, store.load().dailyMinutes)
    }

    @Test
    fun brokenFileFallsBackToDefault() {
        val home = tempHome()
        File(home, "study.json").writeText("{ 这不是 JSON", Charsets.UTF_8)
        assertEquals(DEFAULT_DAILY_MINUTES, StudyPrefsStore(home).load().dailyMinutes)
    }

    @Test
    fun bomPrefixedFileIsStillRead() {
        val home = tempHome()
        File(home, "study.json").writeText("\uFEFF{\"dailyMinutes\": 45}", Charsets.UTF_8)
        assertEquals(45, StudyPrefsStore(home).load().dailyMinutes)
    }

    @Test
    fun storedValueIsNormalizedOnTheWayIn() {
        val home = tempHome()
        File(home, "study.json").writeText("{\"dailyMinutes\": -30}", Charsets.UTF_8)
        assertEquals(MIN_DAILY_MINUTES, StudyPrefsStore(home).load().dailyMinutes)
    }
}
