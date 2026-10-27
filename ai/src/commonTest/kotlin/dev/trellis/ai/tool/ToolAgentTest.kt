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
    fun itKeepsGoingPastTheOldTenStepLimit() = runTest {
        // 12 步才做完：以前固定 8/10 步上限会在这里腰斩，用户看到的是「没做完」。
        val replies = (1..12).map { i ->
            """{"tool": "list_events", "arguments": {"n": $i}}"""
        } + """{"done": true, "message": "查完了"}"""
        val llm = ScriptedLlm(*replies.toTypedArray())
        val tool = Recorder()

        val run = ToolAgent(llm, listOf(tool)).run("你是插件作者", "把十二件事都查一遍")

        assertTrue(run.ok, run.message)
        assertEquals("查完了", run.message)
        assertEquals(12, tool.calls)
    }

    @Test
    fun theSameCallRepeatedIsTreatedAsStuckInsteadOfBurningTokens() = runTest {
        val llm = ScriptedLlm("""{"tool": "list_events", "arguments": {}}""")
        val tool = Recorder()

        val run = ToolAgent(llm, listOf(tool)).run("你是插件作者", "一直查同一件事")

        assertTrue(!run.ok, run.message)
        assertTrue(run.message.contains("同一个工具"), run.message)
        // 第三次调同一件事之前就判定卡住，所以工具只真的被执行了两次。
        assertEquals(2, tool.calls)
    }

    @Test
    fun anExplicitBudgetStillStopsWhenTheCallerAsksForOne() = runTest {
        // 调用方明确说「这一轮最多 3 步」时才生效；参数不同，避免被重复检测提前收掉。
        val replies = (1..8).map { i ->
            """{"tool": "list_events", "arguments": {"n": $i}}"""
        }
        val llm = ScriptedLlm(*replies.toTypedArray())
        val tool = Recorder()

        val run = ToolAgent(llm, listOf(tool), maxSteps = 3).run("你是插件作者", "只准看三步")

        assertTrue(!run.ok)
        assertTrue(run.message.contains("3 步"), run.message)
        assertEquals(3, tool.calls)
    }

    @Test
    fun aScreenshotRidesAlongWithTheToolResult() = runTest {
        val camera = object : AgentTool {
            override val name = "take_screenshot"
            override val description = "截图"
            override val parameters = "无"
            override suspend fun call(arguments: JsonObject) =
                ToolResult("截图已保存", image = "data:image/png;base64,AAAA")
        }
        val llm = ScriptedLlm(
            """{"tool": "take_screenshot", "arguments": {}}""",
            """{"done": true, "message": "看到了"}""",
        )

        val run = ToolAgent(llm, listOf(camera)).run("你是插件作者", "看一眼")

        assertTrue(run.ok, run.message)
        // 截图必须跟着工具结果一起进模型上下文，否则它还是瞎子。
        val withImage = llm.seenMessages[1].filter { it.images.isNotEmpty() }
        assertEquals(1, withImage.size, llm.seenMessages[1].toString())
        assertEquals("data:image/png;base64,AAAA", withImage[0].images[0])
    }
    @Test
    fun itUnderstandsTheModelsOwnMarkupDialect() = runTest {
        // 真实事故：模型不吐约定的 JSON，而是吐 DSML 标记语言，工具一次都没跑，
        // 界面上是一屏乱码，用户以为「查到了就没下文」。
        val llm = ScriptedLlm(
            """
            我先查一下有没有相关的事件。
            <||DSML||calls><||DSML||invoke name="list_events">
            {}
            </||DSML||parameter></||DSML||invoke></||DSML||calls>
            """.trimIndent(),
            """{"done": true, "message": "有一个 task.completed"}""",
        )
        val tool = Recorder()

        val run = ToolAgent(llm, listOf(tool)).run("你是插件作者", "有什么事件")

        assertTrue(run.ok, run.message)
        assertEquals(1, tool.calls)
        assertEquals("有一个 task.completed", run.message)
    }

}
