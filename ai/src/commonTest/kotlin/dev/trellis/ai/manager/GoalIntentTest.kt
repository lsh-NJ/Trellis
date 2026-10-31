package dev.trellis.ai.manager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「说自己的目标」这句话得走到有工具的那个意图上。
 *
 * 背景：聊天意图（[AgentIntent.Chat]）按设计**一只工具都不给** —— 它只能回话。
 * 所以「我想考研」如果被判成聊天，模型手里就没有 `goal_plan`，
 * 目标插件那三张卡会永远停在「还没有目标」，状态机写得再全也进不去。
 *
 * 这几条钉的就是那句路由：这两句演示用的话，必须落到 [AgentIntent.FindResource]。
 */
class GoalIntentTest {

    @Test
    fun sayingYouWantToTakeAnExamReachesTheToolBearingIntent() {
        // 用户 A 的演示原话。
        assertEquals(AgentIntent.FindResource, KeywordIntentClassifier.classify("我想考研"))
        // 用户 B 的演示原话：带截止时间的具体目标。
        assertEquals(
            AgentIntent.FindResource,
            KeywordIntentClassifier.classify("我要 6 月前拿到 XX 岗 offer"),
        )
    }

    @Test
    fun theGoalToolIsHandedToChatTooButNothingElseIs() {
        val tools = listOf(FakeTool("goal_plan"), FakeTool("search_bilibili"), FakeTool("deploy"))

        assertTrue(
            toolsForIntent(AgentIntent.FindResource, tools).any { it.name == "goal_plan" },
            "找资源/排计划这条路上必须带 goal_plan，否则模型没法把目标写进去",
        )
        // 聊天也要这一只：用户说「每天 10 小时」时判不出别的意图，
        // 手里没有它就只能在对话里回一句「我没有工具」（实测就是这么撞上的）。
        assertEquals(
            listOf("goal_plan"),
            toolsForIntent(AgentIntent.Chat, tools).map { it.name },
            "聊天只该多出写目标这一只，不能连删插件、搜视频的也带进去",
        )
        assertTrue(
            toolsForIntent(AgentIntent.ArrangeUi, tools).none { it.name == "goal_plan" },
            "调布局时不该冒出一只写用户目标的工具",
        )
    }

    private class FakeTool(override val name: String) : dev.trellis.ai.tool.AgentTool {
        override val description: String = ""
        override val parameters: String = ""
        override suspend fun call(arguments: kotlinx.serialization.json.JsonObject) =
            dev.trellis.ai.tool.ToolResult("ok")
    }
}
