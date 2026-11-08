package dev.trellis.ai.tool

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmReply
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

    @Test
    fun aToolCallIsReportedWithItsDetailsForTheProcessPanel() = runTest {
        val llm = ScriptedLlm(
            """{"tool": "list_events", "arguments": {"limit": 3}}""",
            """{"done": true, "message": "有一个 task.completed"}""",
        )
        val traces = mutableListOf<ToolTrace>()

        ToolAgent(llm, listOf(Recorder())).run(
            "你是插件作者",
            "有什么事件",
            onTrace = { traces += it },
        )

        // 过程行只需要人话；参数、说明、结果这些大块内容放在 trace 里，点开才看。
        val call = traces.filterIsInstance<ToolTrace.Call>().single()
        assertEquals(1, call.step)
        assertEquals("list_events", call.tool)
        assertEquals("看看有哪些事件能触发规则", call.description)
        assertTrue(call.arguments.contains("limit"), call.arguments)
        assertTrue(call.result.contains("task.completed"), call.result)
        assertTrue(!call.isError)
    }

    @Test
    fun theToolLineOpensBeforeTheToolActuallyRuns() = runTest {
        val traces = mutableListOf<ToolTrace>()
        var startedBeforeRun = false
        val slow = object : AgentTool {
            override val name = "slow_tool"
            override val description = "要跑一会儿"
            override val parameters = "无"

            override suspend fun call(arguments: JsonObject): ToolResult {
                startedBeforeRun = traces.any { it is ToolTrace.CallStarted }
                return ToolResult("跑完了", done = true)
            }
        }

        ToolAgent(ScriptedLlm("{\"tool\": \"slow_tool\", \"arguments\": {}}"), listOf(slow)).run(
            "你是插件作者",
            "跑一下",
            onTrace = { traces += it },
        )

        assertTrue(startedBeforeRun, "工具开始时必须先发出 CallStarted，界面才能先占一行")
        assertTrue(traces.any { it is ToolTrace.CallStarted && it.tool == "slow_tool" })
    }

    /** 带原生思考的假模型：第一次调用给 reasoning，之后不给（钉住「有才显示」）。 */
    private class ThinkingLlm(private val replies: List<String>) : LlmClient {
        private var index = 0

        override suspend fun complete(messages: List<LlmMessage>): String {
            val reply = replies[minOf(index, replies.size - 1)]
            index += 1
            return reply
        }

        override suspend fun completeWithReasoning(messages: List<LlmMessage>): LlmReply =
            LlmReply(
                content = complete(messages),
                reasoning = if (index == 1) "  先查一下有哪些事件  " else null,
            )
    }

    @Test
    fun theModelsOwnReasoningGoesIntoTheProcessPanel() = runTest {
        // DeepSeek 的 reasoning_content 才是真思考；宿主不再从正文里猜。
        val llm = ThinkingLlm(
            listOf(
                """我先查查 {"tool": "list_events", "arguments": {}}""",
                """{"done": true, "message": "有一个 task.completed"}""",
            ),
        )
        val traces = mutableListOf<ToolTrace>()

        val run = ToolAgent(llm, listOf(Recorder())).run(
            "你是插件作者",
            "有什么事件",
            onTrace = { traces += it },
        )

        assertTrue(run.ok, run.message)
        val thinking = traces.filterIsInstance<ToolTrace.Thinking>().single()
        assertEquals("先查一下有哪些事件", thinking.text)
        // 思考行要排在本步工具调用之前：它是这一步的依据，不是结果的注脚。
        assertTrue(
            traces.indexOf(thinking) < traces.indexOfFirst { it is ToolTrace.Call },
            traces.joinToString("\n"),
        )
    }
}
