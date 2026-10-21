package dev.trellis.ai.tool

import dev.trellis.ai.ScriptedLlm
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 会动手的 agent：先查再答，查错能纠，步数能刹。 */
class ToolAgentTest {

    private class Recorder : AgentTool {
        var calls = 0
        override val name = "list_events"
        override val description = "看看有哪些事件能触发规则"
        override val parameters = "无"

        override suspend fun call(arguments: JsonObject): ToolResult {
            calls++
            return ToolResult("task.completed：完成一个任务")
        }
    }

    @Test
    fun itLooksBeforeItAnswers() = runTest {
        val llm = ScriptedLlm(
            """{"tool": "list_events", "arguments": {}}""",
            """{"done": true, "message": "有一个 task.completed"}""",
        )
        val tool = Recorder()

        val run = ToolAgent(llm, listOf(tool)).run("你是插件作者", "有什么事件")

        assertTrue(run.ok, run.message)
        assertEquals("有一个 task.completed", run.message)
        assertEquals(1, tool.calls)
        // 第二次请求里必须带着工具的真实返回，否则它就是在瞎猜
        assertTrue(
            llm.seenMessages[1].any { it.content.contains("task.completed") },
            llm.seenMessages[1].toString(),
        )
    }

    @Test
    fun itStopsWhenTheToolSaysDone() = runTest {
        val deploy = object : AgentTool {
            override val name = "deploy"
            override val description = "装上去"
            override val parameters = "files"
            override suspend fun call(arguments: JsonObject) = ToolResult("装好了：ai.x", done = true)
        }
        val llm = ScriptedLlm("""{"tool": "deploy", "arguments": {}}""")

        val run = ToolAgent(llm, listOf(deploy)).run("你是插件作者", "做一个")

        assertTrue(run.ok)
        assertEquals("装好了：ai.x", run.message)
        assertEquals(1, llm.seenMessages.size)
    }

    @Test
    fun anInventedToolIsCorrectedInsteadOfCrashing() = runTest {
        val llm = ScriptedLlm(
            """{"tool": "shell", "arguments": {"cmd": "rm -rf /"}}""",
            """{"done": true, "message": "好吧"}""",
        )

        val run = ToolAgent(llm, listOf(Recorder())).run("你是插件作者", "随便")

        assertTrue(run.ok)
        assertTrue(
            llm.seenMessages[1].any { it.content.contains("没有叫 shell 的工具") },
            llm.seenMessages[1].toString(),
        )
    }

    @Test
    fun theStepBudgetIsHard() = runTest {
        val llm = ScriptedLlm("""{"tool": "list_events", "arguments": {}}""")
        val tool = Recorder()

        val run = ToolAgent(llm, listOf(tool), maxSteps = 3).run("你是插件作者", "一直查")

        assertTrue(!run.ok)
        assertTrue(run.message.contains("3 步"), run.message)
        assertEquals(3, tool.calls)
    }
}
