package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 跨会话记忆的落盘与查询：v1 新增的 Goal / Preference、entities 字段、词法检索、
 * 以及「旧 memory.jsonl 还能不能读」。
 *
 * 这些是用户真实数据的最小契约：`<home>/steward/memory.jsonl` 是 append-only 的，
 * 新版本写进去的行，旧版本还能读；旧版本写的行，新版本必须照读不误。
 */
class StewardMemoryQueryTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-memory-query").toFile()

    private fun memoryFile(home: File): File = File(File(home, "steward"), "memory.jsonl")

    @Test
    fun goalsAndPreferencesSurviveAReloadAndKeepEntities() {
        val home = tempHome()
        val store = StewardStore(home)
        store.appendMemory(
            StewardMemoryEvent(
                at = 1,
                day = "2026-11-05",
                kind = StewardMemoryKind.Goal,
                text = "想考软考中级",
                topics = listOf("软考"),
                entities = listOf("希赛", "CSAPP"),
            ),
        )
        store.appendMemory(
            StewardMemoryEvent(
                at = 2,
                day = "2026-11-06",
                kind = StewardMemoryKind.Preference,
                text = "晚上别排新任务",
            ),
        )
        store.appendMemory(
            StewardMemoryEvent(at = 3, day = "2026-11-06", kind = StewardMemoryKind.Note, text = "随手记"),
        )

        // 换实例读：新 kind 和 entities 都要能落盘再读回。
        val again = StewardStore(home)
        val goals = again.goals()
        assertEquals(listOf("想考软考中级"), goals.map { it.text })
        assertEquals(listOf("软考"), goals[0].topics)
        assertEquals(listOf("希赛", "CSAPP"), goals[0].entities)
        assertEquals(listOf("晚上别排新任务"), again.preferences().map { it.text })

        // entities 只在非空时写：Goal 那行有，Note 那行不该多一个空数组。
        val lines = memoryFile(home).readLines()
        assertTrue(lines[0].contains("\"entities\":[\"希赛\",\"CSAPP\"]"))
        assertFalse(lines[2].contains("entities"))
    }

    @Test
    fun goalsAndPreferencesAreNewestFirstAndLimited() {
        val home = tempHome()
        val store = StewardStore(home)
        store.appendMemory(StewardMemoryEvent(1, "2026-10-01", StewardMemoryKind.Goal, "旧目标"))
        store.appendMemory(StewardMemoryEvent(2, "2026-10-15", StewardMemoryKind.Goal, "中目标"))
        store.appendMemory(StewardMemoryEvent(3, "2026-11-01", StewardMemoryKind.Goal, "新目标"))
        store.appendMemory(StewardMemoryEvent(4, "2026-11-02", StewardMemoryKind.Morning, "晨间独白"))

        // 按记录时间降序；Morning 不属于目标。
        assertEquals(listOf("新目标", "中目标", "旧目标"), store.goals().map { it.text })
        assertEquals(listOf("新目标", "中目标"), store.goals(limit = 2).map { it.text })
        assertTrue(store.preferences().isEmpty())
    }

    @Test
    fun oldJsonlWithoutEntitiesStillParsesAndUnknownKindsBecomeNote() {
        val home = tempHome()
        File(home, "steward").mkdirs()
        memoryFile(home).writeText(
            """
            {"at":1,"day":"2026-10-01","kind":"Goal","text":"旧版本写的目标"}
            {"at":2,"day":"2026-10-02","kind":"Morning","text":"旧版本写的晨间"}
            {"at":3,"day":"2026-10-03","kind":"SomethingFromTheFuture","text":"将来才会有的类型"}
            {"at":4,"day":"不是日期","kind":"Note","text":"day 坏了这行应当被丢弃"}
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )

        val store = StewardStore(home)
        val goals = store.goals()
        assertEquals(listOf("旧版本写的目标"), goals.map { it.text })
        assertTrue(goals[0].entities.isEmpty(), "旧行没有 entities 字段，应当按空列表读")

        // 不认识的 kind 兜底 Note（行不能丢）；day 坏掉的行走 parseMemory 的丢弃分支。
        val recent = store.recentMemory(today = "2026-10-04", days = 10)
        assertEquals(3, recent.size)
        assertEquals(StewardMemoryKind.Note, recent.last().kind)
    }

    @Test
    fun searchMatchesTextQuoteTopicsAndEntitiesIgnoringCase() {
        val home = tempHome()
        val store = StewardStore(home)
        store.appendMemory(StewardMemoryEvent(1, "2026-11-01", StewardMemoryKind.Note, "Learn Rust"))
        store.appendMemory(
            StewardMemoryEvent(2, "2026-11-02", StewardMemoryKind.UserReply, "随便", quote = "我想学 Rust"),
        )
        store.appendMemory(
            StewardMemoryEvent(3, "2026-11-03", StewardMemoryKind.Note, "工具链", topics = listOf("RUST")),
        )
        store.appendMemory(
            StewardMemoryEvent(4, "2026-11-04", StewardMemoryKind.Note, "包管理", entities = listOf("Cargo")),
        )

        val today = "2026-11-07"
        // text / quote / topics / entities 四类字段都参与匹配，大小写不敏感；新的在前。
        assertEquals(listOf(3L, 2L, 1L), store.searchMemory("rust", today = today).map { it.at })
        assertEquals(listOf(4L), store.searchMemory("CARGO", today = today).map { it.at })
        assertTrue(store.searchMemory("没出现过", today = today).isEmpty())
        assertTrue(store.searchMemory("   ", today = today).isEmpty(), "空白 query 不该退化成全量")
        assertTrue(StewardStore(tempHome()).searchMemory("任何东西", today = today).isEmpty())
    }

    @Test
    fun searchRespectsTheRecentDaysWindowAndLimit() {
        val home = tempHome()
        val store = StewardStore(home)
        store.appendMemory(StewardMemoryEvent(1, "2026-08-01", StewardMemoryKind.Note, "旧目标 软考"))
        store.appendMemory(StewardMemoryEvent(2, "2026-11-01", StewardMemoryKind.Note, "新目标 软考"))
        store.appendMemory(StewardMemoryEvent(3, "2026-11-06", StewardMemoryKind.Note, "新目标 软考 二"))
        val today = "2026-11-07"

        // 7 天窗口 = 11-01..11-07，8 月那条被过滤。
        assertEquals(listOf(3L, 2L), store.searchMemory("软考", days = 7, today = today).map { it.at })
        assertEquals(3, store.searchMemory("软考", days = 400, today = today).size)
        // limit 从新的开始截。
        assertEquals(listOf(3L), store.searchMemory("软考", days = 7, limit = 1, today = today).map { it.at })
        // 不传 today：窗口终点落在日志里最新的一天（11-06），不是全量。
        assertEquals(listOf(3L, 2L), store.searchMemory("软考", days = 7).map { it.at })
    }
}
