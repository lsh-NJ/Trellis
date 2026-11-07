package dev.trellis.app.desktop

import dev.trellis.ai.tool.ToolResult
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 给模型的两只记忆手：`remember_memory` 写、`recall_memory` 读。
 *
 * 不碰网络、不调模型：这里钉的是参数解析、goal 去重、以及「写进去的东西
 * StewardStore 立刻读得到」——落盘和读取必须是同一条链路。
 */
class MemoryToolsTest {

    /** 固定业务日期的假时钟；`nowMs` 每次 +1，保证记忆的 at 有先后。 */
    private class FixedClock(private val date: LocalDate = LocalDate.of(2026, 11, 7)) : TrellisClock {
        private var tick = 1_000L
        override val zone: ZoneId = ZoneId.of("Asia/Shanghai")
        override fun nowMs(): Long = tick++
        override fun monotonicMs(): Long = tick
        override fun today(): LocalDate = date
        override fun isoWeek(): String = isoWeekOf(date)
        override fun simulatedDate(): LocalDate? = null
    }

    private fun tempHome(): File = Files.createTempDirectory("trellis-memory-tools").toFile()

    private fun newTools(home: File): MemoryTools = MemoryTools(StewardStore(home), FixedClock())

    private suspend fun remember(
        tools: MemoryTools,
        kind: String,
        text: String,
        topics: List<String> = emptyList(),
        entities: List<String> = emptyList(),
    ): ToolResult {
        val tool = tools.all.first { it.name == "remember_memory" }
        return tool.call(
            buildJsonObject {
                put("kind", kind)
                put("text", text)
                if (topics.isNotEmpty()) {
                    put("topics", buildJsonArray { topics.forEach { add(it) } })
                }
                if (entities.isNotEmpty()) {
                    put("entities", buildJsonArray { entities.forEach { add(it) } })
                }
            },
        )
    }

    private suspend fun recall(
        tools: MemoryTools,
        query: String? = null,
        days: Int? = null,
        limit: Int? = null,
    ): ToolResult {
        val tool = tools.all.first { it.name == "recall_memory" }
        return tool.call(
            buildJsonObject {
                query?.let { put("query", it) }
                days?.let { put("days", it) }
                limit?.let { put("limit", it) }
            },
        )
    }

    @Test
    fun rememberGoalWritesIntoTheSameLogTheStewardReads() = runTest {
        val home = tempHome()
        val tools = newTools(home)

        val result = remember(
            tools,
            kind = "goal",
            text = "想考软考中级",
            topics = listOf("软考"),
            entities = listOf("希赛", "CSAPP"),
        )
        assertFalse(result.isError, result.content)
        assertTrue(result.content.contains("目标"), result.content)

        // 换实例读：工具写的是同一份 `<home>/steward/memory.jsonl`。
        val goals = StewardStore(home).goals()
        assertEquals(listOf("想考软考中级"), goals.map { it.text })
        assertEquals("2026-11-07", goals[0].day, "day 必须走注入时钟，不是系统时间")
        assertEquals(listOf("软考"), goals[0].topics)
        assertEquals(listOf("希赛", "CSAPP"), goals[0].entities)

        // note 走同一个工具写入，kind 是 Note。
        remember(tools, kind = "note", text = "周五前背完单词")
        val notes = StewardStore(home).recentMemory(today = "2026-11-07", days = 1)
            .filter { it.kind == StewardMemoryKind.Note }
        assertEquals(listOf("周五前背完单词"), notes.map { it.text })
    }

    @Test
    fun sameGoalSentenceIsNotWrittenTwice() = runTest {
        val home = tempHome()
        val tools = newTools(home)
        remember(tools, kind = "goal", text = "想考软考中级")

        // 空白/大小写不同还是同一句：不重复写。
        val again = remember(tools, kind = "goal", text = "  想考软考中级  ")
        assertFalse(again.isError, again.content)
        assertTrue(again.content.contains("已经在记忆里"), again.content)
        assertEquals(1, StewardStore(home).goals().size)

        // 换一个说法算另一条目标。
        remember(tools, kind = "goal", text = "想考软考高级")
        assertEquals(2, StewardStore(home).goals().size)
    }

    @Test
    fun rememberExplainsBadArguments() = runTest {
        val home = tempHome()
        val tools = newTools(home)

        val badKind = remember(tools, kind = "memo", text = "随便")
        assertTrue(badKind.isError)
        assertTrue(badKind.content.contains("goal / preference / note"), badKind.content)
        assertTrue(badKind.content.contains("memo"), "报错要回显模型给的值，方便它改：${badKind.content}")

        // text 为空白等于没给。
        val missingText = newTools(home).all.first { it.name == "remember_memory" }
            .call(buildJsonObject {
                put("kind", "goal")
                put("text", "   ")
            })
        assertTrue(missingText.isError)
        assertTrue(missingText.content.contains("text"), missingText.content)
        assertTrue(StewardStore(home).goals().isEmpty(), "参数错误时不能写脏数据")
    }

    @Test
    fun recallFindsByQueryAndLabelsTheKind() = runTest {
        val home = tempHome()
        val store = StewardStore(home)
        store.appendMemory(StewardMemoryEvent(1, "2026-11-03", StewardMemoryKind.Note, "周五前背完单词"))
        store.appendMemory(StewardMemoryEvent(2, "2026-11-04", StewardMemoryKind.Adopted, "采纳：先做计划再刷题"))
        store.appendMemory(StewardMemoryEvent(3, "2026-11-05", StewardMemoryKind.Goal, "想考软考中级"))
        val tools = MemoryTools(store, FixedClock())

        val hit = recall(tools, query = "软考")
        assertFalse(hit.isError, hit.content)
        assertTrue(hit.content.contains("命中 1 条记忆"), hit.content)
        assertTrue(hit.content.contains("想考软考中级"), hit.content)
        assertTrue(hit.content.contains("目标"), "结果要带 kind 标签：${hit.content}")

        // 没命中也只是「没有」，不是错误；模型应如实告诉用户。
        val miss = recall(tools, query = "不存在的词")
        assertFalse(miss.isError, miss.content)
        assertTrue(miss.content.contains("记忆里没有"), miss.content)

        // 不带 query：回最近几天的记忆。
        val recent = recall(tools)
        assertFalse(recent.isError, recent.content)
        assertTrue(recent.content.contains("先做计划再刷题"), recent.content)
    }

    @Test
    fun recallRespectsTheDaysWindowAndCapsTheLimit() = runTest {
        val home = tempHome()
        val store = StewardStore(home)
        (1..25).forEach { index ->
            store.appendMemory(
                StewardMemoryEvent(index.toLong(), "2026-11-05", StewardMemoryKind.Note, "第 $index 条检索命中"),
            )
        }
        val tools = MemoryTools(store, FixedClock())

        // days=1 只看 2026-11-07 当天：11-05 的不该出现。
        val tooNarrow = recall(tools, query = "检索命中", days = 1)
        assertTrue(tooNarrow.content.contains("记忆里没有"), tooNarrow.content)

        // limit 超过 20 按 20 截；25 条只回 20 条。
        val capped = recall(tools, query = "检索命中", days = 30, limit = 99)
        assertTrue(capped.content.contains("命中 20 条记忆"), capped.content)
    }
}
