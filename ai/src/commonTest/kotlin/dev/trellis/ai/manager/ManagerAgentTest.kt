package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagerAgentTest {

    private fun prompt(): ManagerPrompt =
        ManagerPrompt(ManagerPrompt.buildBase(CapabilityCatalog(emptyList(), emptyList()), emptyList()))

    /**
     * 假模型：聊天阶段（stream）回 [streamReply]；工具循环（complete）按 [completions] 依次吐。
     * 两个通道分开，是为了钉住「先聊天、发现要动手再切工具循环」这个顺序。
     */
    private class ScriptedLlm(
        private val streamReply: String = "好的",
        private val completions: MutableList<String> = mutableListOf(),
        private val failWith: String? = null,
        private val reasoning: String? = null,
    ) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String {
            if (failWith != null) throw IllegalStateException(failWith)
            return if (completions.isEmpty()) "{\"done\":true,\"message\":\"好了\"}" else completions.removeAt(0)
        }

        private var streams = 0

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
            onReasoning: suspend (String) -> Unit,
            jsonMode: Boolean,
        ): String {
            if (failWith != null) throw IllegalStateException(failWith)
            // 第一次是聊天阶段；进工具循环后每一步也走流式，按 completions 依次吐。
            val reply = if (streams++ == 0) {
                streamReply
            } else if (completions.isEmpty()) {
                "{\"done\":true,\"message\":\"好了\"}"
            } else {
                completions.removeAt(0)
            }
            reasoning?.chunked(3)?.forEach { onReasoning(it) }
            reply.chunked(3).forEach { onDelta(it) }
            return reply
        }
    }

    private fun countingTool(name: String = "create_learning_plan"): Pair<AgentTool, () -> Int> {
        var called = 0
        val tool = object : AgentTool {
            override val name: String = name
            override val description: String = "测试工具"
            override val parameters: String = "无"
            override suspend fun call(arguments: JsonObject): ToolResult {
                called += 1
                return ToolResult("做完了")
            }
        }
        return tool to { called }
    }

    @Test
    fun chattingStreamsAndKeepsTheWholeAnswer() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(ScriptedLlm(streamReply = "我是这里的管理员"), prompt(), tools = emptyList())
        val deltas = mutableListOf<String>()
        val result = agent.respond(
            state = "已装插件：3 个",
            request = "你是谁",
            onDelta = { deltas += it },
        )

        assertEquals("我是这里的管理员", result.text)
        assertEquals("我是这里的管理员", deltas.joinToString(""))
        assertTrue(result.ok)
        assertTrue(result.transcript.isEmpty(), "纯聊天不该进工具循环")
    }

    @Test
    fun theModelsOwnReasoningIsReportedSeparatelyFromTheAnswer() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(
            ScriptedLlm(
                streamReply = "你好！",
                reasoning = "用户只是打个招呼，回一句就行，不用调工具。",
            ),
            prompt(),
            tools = emptyList(),
        )
        val thoughts = mutableListOf<String>()

        val result = agent.respond(state = "", request = "你好", onThinking = { thoughts += it })

        // 思考是模型自己给的 reasoning_content，不是从正文里猜的；正文不该把它带出来。
        assertEquals(listOf("用户只是打个招呼，回一句就行，不用调工具。"), thoughts)
        assertEquals("你好！", result.text)
        assertFalse(result.text.contains("打个招呼"))

        // 没有思考内容的回复不调 onThinking，界面上也就不该多一行。
        val quiet = ManagerAgent(ScriptedLlm(streamReply = "好的"), prompt(), tools = emptyList())
        val none = mutableListOf<String>()
        quiet.respond(state = "", request = "你好", onThinking = { none += it })
        assertTrue(none.isEmpty(), none.toString())
    }

    @Test
    fun aModelFailureIsReportedInsteadOfThrown() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(ScriptedLlm(failWith = "连不上"), prompt(), tools = emptyList())
        val result = agent.respond(state = "", request = "你好")

        assertFalse(result.ok)
        assertTrue(result.text.contains("连不上"))
    }

    @Test
    fun aChatThatAnswersWithTheDoneEnvelopeStillReadsLikeAMessage() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(
            ScriptedLlm(streamReply = "{\"done\": true, \"message\": \"你好！我能记东西、算一算、管插件。\\n\\n想做什么直说。\"}"),
            prompt(),
            tools = emptyList(),
        )
        val result = agent.respond(state = "", request = "你好")

        assertEquals("你好！我能记东西、算一算、管插件。\n\n想做什么直说。", result.text)
    }

    @Test
    fun aJsonStringLiteralIsUnwrappedToo() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(ScriptedLlm(streamReply = "\"只有一句\\n换行\""), prompt(), tools = emptyList())
        val result = agent.respond(state = "", request = "你好")

        assertEquals("只有一句\n换行", result.text)
    }

    /**
     * 模型在聊天里说「我先去建计划」，随后吐出工具调用 JSON —— 这不是瞎说，是它想动手。
     * 流式那一步给用户看，然后同一条上下文交给工具循环真去干，别让这句话停在界面上没下文。
     */
    @Test
    fun aStreamedToolCallSwitchesToTheToolLoop() = kotlinx.coroutines.test.runTest {
        val (tool, called) = countingTool()
        val llm = ScriptedLlm(
            streamReply = "我先把它排成计划 {\"tool\":\"create_learning_plan\",\"arguments\":{}}",
            completions = mutableListOf(
                "{\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                "{\"done\":true,\"message\":\"排好了\"}",
            ),
        )
        val agent = ManagerAgent(llm, prompt(), tools = listOf(tool))
        val logs = mutableListOf<String>()

        val result = agent.respond(state = "", request = "每天 60 分钟", onLog = { logs += it })

        assertEquals(1, called(), "工具必须真的被执行：" + result.transcript.joinToString("\n"))
        assertEquals("排好了", result.text)
        assertTrue(result.ok, result.transcript.joinToString("\n"))
        assertTrue(logs.any { it.contains("切到工具模式") }, "过程里要说清为什么切：" + logs.joinToString("\n"))
    }

    /**
     * 收尾检查不再按意图给：它只看「这一轮碰过工具、模型又说做完了」。
     * 拦一次（返回一句话）之后模型再交活就放行，过程里能看到「还要补」。
     */
    @Test
    fun theFinishCheckRunsInsideTheToolLoop() = kotlinx.coroutines.test.runTest {
        var checks = 0
        val (tool, _) = countingTool()
        val llm = ScriptedLlm(
            streamReply = "先看一眼 {\"tool\":\"create_learning_plan\",\"arguments\":{}}",
            completions = mutableListOf(
                "{\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                "{\"done\":true,\"message\":\"好了\"}",
                "{\"done\":true,\"message\":\"真的好了\"}",
            ),
        )
        val agent = ManagerAgent(
            llm = llm,
            prompt = prompt(),
            tools = listOf(tool),
            beforeFinishing = { message ->
                // 只有它说「做好了」才核对；这里用一个显式的旗子让第一次拦、第二次放。
                if (message.contains("好了") && checks++ == 0) "还没核对，再确认一次" else null
            },
        )

        val result = agent.respond(state = "", request = "把计划建起来")

        assertEquals(2, checks, "第一次拦下，第二次放行")
        assertTrue(result.ok, result.transcript.joinToString("\n"))
        assertEquals("真的好了", result.text)
        assertTrue(result.transcript.any { it.contains("还要补") }, result.transcript.joinToString("\n"))
    }

    @Test
    fun aRefusalIsNotAFailure() = kotlinx.coroutines.test.runTest {
        val (tool, _) = countingTool()
        val llm = ScriptedLlm(
            streamReply = "我来试试 {\"tool\":\"create_learning_plan\",\"arguments\":{}}",
            completions = mutableListOf(
                "{\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                "{\"cannotDo\":\"这件事需要解锁手机，我做不到\"}",
            ),
        )
        val agent = ManagerAgent(llm, prompt(), tools = listOf(tool))
        val result = agent.respond(state = "", request = "帮我看手机")

        assertTrue(result.refused)
        assertFalse(result.ok)
        assertTrue(result.text.contains("解锁手机"), result.text)
    }
}
