package dev.trellis.app.desktop

import dev.trellis.feature.goal.GoalPhase
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `goal_plan` 这只手真的能改插件状态。
 *
 * 这一层是「对话 → 插件」的接缝：它上面是模型的工具调用，下面是 `GoalService.update`
 * 和 `ctx.kv`。接缝断掉的表现很隐蔽 —— 界面上一切正常，只是永远「还没有目标」。
 * 所以这里不放宽：从第一次 start 一路走到 confirm，每一步都断言状态。
 *
 * 也顺手钉住工具返回的**键名**：模型下一步要拿「这一轮问」里括号里的键去调 answer，
 * 键名对不上就是一轮白问。
 */
class GoalToolsTest {

    private val today = java.time.LocalDate.now().toString()

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-goal-tools").toFile().apply { deleteOnExit() }

    private fun step(action: String, vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        put("action", action)
        pairs.forEach { (key, value) -> put(key, value) }
    }

    @Test
    fun theToolWalksAGoalFromStatementToActive() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = GoalTools(
            service = { runtime.goalPlugin?.serviceOrNull() },
            dailyMinutes = { 90 },
            today = { today },
            nowMs = { 0L },
        ).all.single()
        assertEquals("goal_plan", tool.name)

        // 第 1 步：用户只说了「我想考研」。工具回两个待问字段，并给出可用的键名。
        val started = tool.call(step("start", "statement" to "我想考研", "kind" to "academic"))
        assertFalse(started.isError, started.content)
        assertTrue(started.content.contains("我想考研"), "要把原话回显出来：" + started.content)

        val service = assertNotNull(runtime.goalPlugin?.serviceOrNull())
        assertEquals("我想考研", service.mirror().contract.statement, "原话一字不改")

        // 每轮最多问 2 个，且返回里带着键名（模型要照着它调 answer）。
        val keys = Regex("（([A-Z_]+)）").findAll(started.content).map { it.groupValues[1] }.toList()
        assertTrue(keys.size in 1..2, "一轮最多问 2 个，实际 " + keys + "：" + started.content)
        println("[工具] 第 1 轮问：" + keys)

        // 反复问、反复答，直到工具说该出草稿了。
        var round = 0
        var last = started
        while (true) {
            val asking = Regex("（([A-Z_]+)）").findAll(last.content).map { it.groupValues[1] }.toList()
            if (asking.isEmpty()) break
            round += 1
            assertTrue(round <= 4, "最多三轮采集就该停下来，实际第 " + round + " 轮还在问：" + last.content)
            for (key in asking) {
                last = tool.call(step("answer", "field" to key, "value" to "第 " + round + " 轮答的 " + key))
                assertFalse(last.isError, last.content)
            }
        }
        assertTrue(round in 1..4, "采集轮数不对：" + round)
        assertTrue(last.content.contains("draft"), "问完之后要明确让模型调 draft：" + last.content)

        // 出草稿：今天 + 明天，条目不为空。
        val drafted = tool.call(step("draft"))
        assertFalse(drafted.isError, drafted.content)
        var mirror = service.mirror()
        assertEquals(GoalPhase.DRAFT, mirror.phase, "draft 之后停在草稿，不替用户拍板")
        assertNotNull(mirror.dayPlan(today), "今天要有安排：" + drafted.content)
        assertTrue(drafted.content.contains("今天"), "回话要说清今天几条：" + drafted.content)
        println("[工具] 草稿：" + drafted.content.replace("\n", " | "))

        // 用户点确认。
        val confirmed = tool.call(step("confirm"))
        assertFalse(confirmed.isError, confirmed.content)
        mirror = service.mirror()
        assertEquals(GoalPhase.ACTIVE, mirror.phase)
        assertTrue(confirmed.content.contains("推进中"), "确认之后状态要变：" + confirmed.content)

        runtime.shutdown()
    }

    @Test
    fun aWrongFieldNameComesWithTheFullList() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = GoalTools(
            service = { runtime.goalPlugin?.serviceOrNull() },
            dailyMinutes = { 90 },
            today = { today },
            nowMs = { 0L },
        ).all.single()

        tool.call(step("start", "statement" to "我想考研", "kind" to "academic"))
        val bad = tool.call(step("answer", "field" to "目标学校", "value" to "清华"))

        assertTrue(bad.isError, "不认识的字段要报错，不能静默丢掉")
        // 报错要能指导修改：一次性列出全部可用值（键名 + 中文标签）。
        assertTrue(bad.content.contains("DAILY_MINUTES"), "要把可用字段列出来：" + bad.content)
        assertTrue(bad.content.contains("每天能投入多久"), "键名之外也要给中文标签：" + bad.content)

        runtime.shutdown()
    }

    @Test
    fun aMissingActionExplainsWhatIsAvailable() = runTest {
        val runtime = AppRuntime.create(tempHome())
        val tool = GoalTools(
            service = { runtime.goalPlugin?.serviceOrNull() },
            dailyMinutes = { 90 },
            today = { today },
            nowMs = { 0L },
        ).all.single()

        val bad = tool.call(buildJsonObject { })
        assertTrue(bad.isError)
        assertTrue(bad.content.contains("start"), bad.content)

        runtime.shutdown()
    }
}
