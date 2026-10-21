package dev.trellis.ai.flow

import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.ScriptedInstaller
import dev.trellis.ai.ScriptedLlm
import dev.trellis.ai.TEST_CATALOG
import dev.trellis.ai.goodJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 按剧本报毛病的假试跑器：第 n 次返回第 n 份结果，用完了重复最后一份。 */
private class ScriptedSelfTest(private vararg val rounds: List<Issue>) : SelfTestTool {
    var calls = 0
        private set

    override suspend fun test(files: Map<String, String>): List<Issue> {
        val result = rounds[minOf(calls, rounds.size - 1)]
        calls++
        return result
    }
}

private fun flow(
    llm: ScriptedLlm,
    selfTest: SelfTestTool,
    installer: ScriptedInstaller = ScriptedInstaller(null),
) = AgentFlow(
    llm = llm,
    catalog = TEST_CATALOG,
    installer = installer,
    selfTest = selfTest,
)

class AgentFlowTest {

    @Test
    fun aPluginThatFailsTheRunIsSentBackWithTheProblem() = runTest {
        val llm = ScriptedLlm(goodJson(), goodJson())
        val selfTest = ScriptedSelfTest(
            listOf(Issue("RENDER_FAILED", "main.js", "卡片 notebook 画不出来：not a function")),
            emptyList(),
        )

        val outcome = flow(llm, selfTest).run("给我一个错题本")

        assertEquals(AgentOutcome.Installed("ai.today", 2), outcome)
        assertEquals(2, llm.calls)
        assertEquals(2, selfTest.calls)
    }

    @Test
    fun theProblemIsPutInFrontOfTheModelWordForWord() = runTest {
        val llm = ScriptedLlm(goodJson(), goodJson())
        val selfTest = ScriptedSelfTest(
            listOf(Issue("RENDER_FAILED", "main.js", "卡片 notebook 画不出来：not a function")),
            emptyList(),
        )

        flow(llm, selfTest).run("给我一个错题本")

        val second = llm.seenMessages[1].last().content
        assertTrue(second.contains("RENDER_FAILED"), "第二条消息要把错误码原样带上")
        assertTrue(second.contains("not a function"), "还要带上引擎说的原话")
        assertTrue(second.contains("完整"), "必须要求它重写整个插件，不是打补丁")
    }

    @Test
    fun itStopsInsteadOfLoopingForever() = runTest {
        val llm = ScriptedLlm(goodJson())
        val selfTest = ScriptedSelfTest(listOf(Issue("RENDER_FAILED", "main.js", "永远画不出来")))

        val outcome = flow(llm, selfTest).run("给我一个错题本")

        assertTrue(outcome is AgentOutcome.GaveUp, "试不过就该放弃，而不是一直转")
        val gaveUp = outcome as AgentOutcome.GaveUp
        assertEquals(4, gaveUp.attempts)
        assertTrue(gaveUp.problems.any { it.contains("永远画不出来") })
    }

    @Test
    fun aRefusalEndsItAtOnce() = runTest {
        val llm = ScriptedLlm("""{"cannotDo": "应用里没有定时器，做不到每天九点提醒"}""")
        val selfTest = ScriptedSelfTest(emptyList())

        val outcome = flow(llm, selfTest).run("每天九点提醒我喝水")

        assertTrue(outcome is AgentOutcome.Refused)
        assertEquals(0, selfTest.calls, "模型说做不到，就不该再去试跑")
    }

    @Test
    fun aPluginWithNoScriptPassesWithoutRunningAnything() = runTest {
        val llm = ScriptedLlm(goodJson())
        val selfTest = ScriptedSelfTest(emptyList())

        val outcome = flow(llm, selfTest).run("给我一个今日待办卡片")

        assertEquals(AgentOutcome.Installed("ai.today", 1), outcome)
        assertEquals(1, llm.calls)
    }
}
