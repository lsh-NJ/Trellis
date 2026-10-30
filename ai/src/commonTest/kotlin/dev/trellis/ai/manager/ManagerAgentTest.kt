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

    /**
     * 真实事故：AI 问「每天能投入多少分钟」，用户点选项「每天 60 分钟」——
     * 这句没有意图关键词，重新分类成 Chat（零工具），模型想调 `create_learning_plan`
     * 却调不到，只能回「我这边没有那只工具」。
     *
     * 修复：上一轮用 `ask_choices` 问了用户就记下意图，下一句短回答沿用；这里用
     * `forcedIntent` 模拟那条路径，断言工具真的被执行。
     */
    @Test
    fun aFollowUpAnswerKeepsTheIntentThatAskedTheQuestion() = kotlinx.coroutines.test.runTest {
        var called = 0
        val createPlan = object : AgentTool {
            override val name: String = "create_learning_plan"
            override val description: String = "建学习计划"
            override val parameters: String = "计划参数"
            override suspend fun call(arguments: JsonObject): ToolResult {
                called += 1
                return ToolResult("计划建好了")
            }
        }
        val llm = ScriptedLlm(
            mutableListOf(
                "{\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                "{\"done\":true,\"message\":\"按每天 60 分钟排好了\"}",
            ),
        )
        val agent = ManagerAgent(llm, prompt(), tools = listOf(createPlan))

        val result = agent.respond(
            state = "",
            request = "每天 60 分钟",
            forcedIntent = AgentIntent.FindResource,
        )

        assertEquals(AgentIntent.FindResource, result.intent, "短回答要沿用上一轮意图，不能退回零工具的聊天")
        assertEquals(1, called, "工具必须真的被执行：" + result.transcript.joinToString("\n"))
        assertTrue(result.ok, result.transcript.joinToString("\n"))
    }

    /**
     * 兜底：即使没有 `forcedIntent`（比如用户换了会话、或意图没被记下），
     * 模型自己点名了一个真实工具时，也要换到拥有它的意图再跑，
     * 而不是拿空工具箱连续报「没有这个工具」。
     */
    @Test
    fun aChatThatReachesForARealToolGetsReroutedToThatToolsIntent() = kotlinx.coroutines.test.runTest {
        var called = 0
        val createPlan = object : AgentTool {
            override val name: String = "create_learning_plan"
            override val description: String = "建学习计划"
            override val parameters: String = "计划参数"
            override suspend fun call(arguments: JsonObject): ToolResult {
                called += 1
                return ToolResult("计划建好了")
            }
        }
        val llm = ScriptedLlm(
            mutableListOf(
                // ① Chat 流式回显：模型自己点名了真实工具，触发换路。
                "我先把它排成计划 {\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                // ② 换到 FindResource 后，ToolAgent 重新发请求，模型应当真的调工具。
                "{\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                // ③ 收尾。
                "{\"done\":true,\"message\":\"排好了\"}",
            ),
        )
        val agent = ManagerAgent(llm, prompt(), tools = listOf(createPlan))
        val logs = mutableListOf<String>()

        val result = agent.respond(
            state = "",
            request = "每天 60 分钟",
            onLog = { logs += it },
        )

        assertEquals(AgentIntent.FindResource, result.intent, "要换到工具真正的归属意图")
        assertEquals(1, called, result.transcript.joinToString("\n"))
        assertTrue(
            logs.any { it.contains("换到那条路") },
            "过程里要说清为什么换路：" + logs.joinToString("\n"),
        )
    }

    /** 「先问清楚」是意图歧义；模型自己调 ask_choices 问用户是另一回事，下一轮要承接。 */
    @Test
    fun anAmbiguousIntentIsMarkedAsClarificationNotAsAQuestionAboutArguments() =
        kotlinx.coroutines.test.runTest {
            val agent = ManagerAgent(FakeLlm("不会用到"), prompt(), tools = emptyList())
            val result = agent.respond(state = "", request = "帮我弄一下")

            assertTrue(result.clarified)
            assertTrue(result.choices.isNotEmpty())
        }

    /**
     * P1 实验（`TRELLIS_MANAGER_STABLE_TOOLS=1`）：Chat 本身就带着全量工具，
     * 模型在聊天里点名工具时**不换意图**，直接在同一轮跑工具循环。
     *
     * 旧路径靠「工具名 → 意图」兜底，是因为 Chat 手里空着；实验路径把那只手直接给出去，
     * 兜底也就不需要了（P3 连同换意图补丁一起删）。
     */
    @Test
    fun theStableExperimentRunsNamedToolsFromChatWithoutSwitchingIntent() =
        kotlinx.coroutines.test.runTest {
            var called = 0
            val createPlan = object : AgentTool {
                override val name: String = "create_learning_plan"
                override val description: String = "建学习计划"
                override val parameters: String = "计划参数"
                override suspend fun call(arguments: JsonObject): ToolResult {
                    called += 1
                    return ToolResult("计划建好了")
                }
            }
            val llm = ScriptedLlm(
                mutableListOf(
                    // ① Chat 流式回显：它决定动手。
                    "我来排 {\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                    // ② 同一轮工具循环里真的调工具。
                    "{\"tool\":\"create_learning_plan\",\"arguments\":{}}",
                    // ③ 收尾。
                    "{\"done\":true,\"message\":\"排好了\"}",
                ),
            )
            val router = ManagerRouter(prompt(), listOf(createPlan), stableTools = true)
            val agent = ManagerAgent(llm, router)
            val logs = mutableListOf<String>()

            val result = agent.respond(state = "", request = "每天 60 分钟", onLog = { logs += it })

            assertEquals(AgentIntent.Chat, result.intent, "稳定模式不按工具名换意图")
            assertEquals(1, called, result.transcript.joinToString("\n"))
            assertTrue(result.ok, result.transcript.joinToString("\n"))
            assertTrue(
                logs.any { it.contains("稳定全量") },
                "过程里要能看出实验开关开着：" + logs.joinToString("\n"),
            )
            assertFalse(
                logs.any { it.contains("换到那条路") },
                "稳定模式下不该再走换意图补丁：" + logs.joinToString("\n"),
            )
        }
}
