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

    /** 假模型：聊天时按片吐字；也可以指定一调用就炸。 */
    private class FakeLlm(
        private val reply: String = "好的",
        private val failWith: String? = null,
    ) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String {
            if (failWith != null) throw IllegalStateException(failWith)
            return reply
        }

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
        ): String {
            if (failWith != null) throw IllegalStateException(failWith)
            reply.chunked(3).forEach { onDelta(it) }
            return reply
        }
    }

    /** 假模型：按顺序吐脚本，用完就收尾。 */
    private class ScriptedLlm(private val replies: MutableList<String>) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String =
            if (replies.isEmpty()) "{\"done\":true,\"message\":\"好了\"}" else replies.removeAt(0)

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
        ): String = complete(messages)
    }

    @Test
    fun theFinishCheckOnlyAppliesToPluginWork() = kotlinx.coroutines.test.runTest {
        // 「试过、装上了」是造插件的标准。拿它去卡调布局，会把已经摆好的说成没做成 ——
        // 实测会连退六次、烧满 10 步。这里钉住：收尾检查只在 CreatePlugin 时生效。
        val intents = mutableListOf<AgentIntent>()
        val tool = object : AgentTool {
            override val name: String = "list_cards"
            override val description: String = "看某个挂载点上有什么卡片"
            override val parameters: String = "不需要参数。"
            override suspend fun call(arguments: JsonObject): ToolResult = ToolResult("slot.home.section：（空）")
        }
        val llm = ScriptedLlm(
            mutableListOf(
                "{\"tool\":\"list_cards\",\"arguments\":{}}",
                "{\"done\":true,\"message\":\"排好了\"}",
            ),
        )
        val agent = ManagerAgent(
            llm = llm,
            prompt = prompt(),
            tools = listOf(tool),
            beforeFinishing = { intent ->
                intents += intent
                if (intent == AgentIntent.CreatePlugin) "必须试过、装上了才能收" else null
            },
        )

        val result = agent.respond(state = "", request = "把首页的卡片横着排")

        assertEquals(AgentIntent.ArrangeUi, result.intent)
        assertTrue(result.ok, result.transcript.joinToString("\n"))
        assertEquals(listOf(AgentIntent.ArrangeUi), intents)
        assertFalse(result.transcript.contains("必须试过"))
    }

    @Test
    fun chattingStreamsAndKeepsTheWholeAnswer() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(FakeLlm("我是这里的管理员"), prompt(), tools = emptyList())
        val deltas = mutableListOf<String>()
        val result = agent.respond(
            state = "已装插件：3 个",
            request = "你是谁",
            onDelta = { deltas += it },
        )

        assertEquals(AgentIntent.Chat, result.intent)
        assertEquals("我是这里的管理员", result.text)
        assertEquals("我是这里的管理员", deltas.joinToString(""))
        assertTrue(result.ok)
    }

    @Test
    fun aModelFailureIsReportedInsteadOfThrown() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(FakeLlm(failWith = "连不上"), prompt(), tools = emptyList())
        val result = agent.respond(state = "", request = "你好")

        assertFalse(result.ok)
        assertTrue(result.text.contains("连不上"))
    }

    @Test
    fun aChatThatAnswersWithTheDoneEnvelopeStillReadsLikeAMessage() = kotlinx.coroutines.test.runTest {
        // 静态前缀里有工具协议，模型有时会直接按协议回 {"done":true,"message":"…"}。
        // 这是照协议说话，不该把大括号和转义 \n 原样摊到界面上。
        val agent = ManagerAgent(
            FakeLlm("{\"done\": true, \"message\": \"你好！我能记东西、算一算、管插件。\\n\\n想做什么直说。\"}"),
            prompt(),
            tools = emptyList(),
        )
        val result = agent.respond(state = "", request = "你好")

        assertEquals(AgentIntent.Chat, result.intent)
        assertEquals("你好！我能记东西、算一算、管插件。\n\n想做什么直说。", result.text)
    }

    @Test
    fun aJsonStringLiteralIsUnwrappedToo() = kotlinx.coroutines.test.runTest {
        val agent = ManagerAgent(FakeLlm("\"只有一句\\n换行\""), prompt(), tools = emptyList())
        val result = agent.respond(state = "", request = "你好")

        assertEquals("只有一句\n换行", result.text)
    }

    @Test
    fun theTaskModuleForAWorkRequestIsNotUsedForChat() = kotlinx.coroutines.test.runTest {
        // 判成造插件时会走工具循环；这里没有工具、模型只回一句非工具 JSON，
        // 期望它至少不要崩，并把模型的最后一句带回来。
        val agent = ManagerAgent(FakeLlm("{\"say\":\"先看清楚再说\"}"), prompt(), tools = emptyList())
        val result = agent.respond(state = "", request = "做一个心情打卡")

        assertEquals(AgentIntent.CreatePlugin, result.intent)
        assertTrue(result.text.isNotBlank())
    }
}
