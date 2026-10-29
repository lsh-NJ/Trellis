package dev.trellis.ai.manager

import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 插件上下文片段在提示词里的位置与形状。
 *
 * 位置是这里唯一重要的东西：片段必须落在 `<current_state>` 之后、`<task:...>` 之前。
 * 放到 task 之后，模型会把「现在是什么」当成「这一轮要干什么」；
 * 放到历史里，它会当成旧闻。两种都实测过，都会让它重做一遍已经做过的事。
 */
class PromptSectionTest {

    private val prompt = ManagerPrompt(base = "BASE")

    private fun blockOf(intent: AgentIntent, sections: List<String>): List<String> =
        prompt.messages(
            state = "内核：ACTIVE",
            request = "帮我看看今天干嘛",
            history = emptyList(),
            intent = intent,
            sections = sections,
        ).filter { it.role == LlmRole.System }.map { it.content }

    @Test
    fun sectionsSitBetweenStateAndTask() {
        val blocks = blockOf(AgentIntent.FindResource, listOf("<goal_context>\n目标：考研\n</goal_context>"))

        val stateIndex = blocks.indexOfFirst { it.startsWith("<current_state>") }
        val pluginIndex = blocks.indexOfFirst { it.startsWith("<plugin_context>") }
        val taskIndex = blocks.indexOfFirst { it.startsWith("<task:") }

        assertTrue(stateIndex >= 0, "要有 current_state：" + blocks)
        assertTrue(pluginIndex >= 0, "要有 plugin_context：" + blocks)
        assertTrue(taskIndex >= 0, "要保留任务模块：" + blocks)
        assertTrue(stateIndex < pluginIndex, "片段必须在 current_state 之后：" + blocks)
        assertTrue(pluginIndex < taskIndex, "片段必须在 task 之前：" + blocks)
    }

    @Test
    fun noSectionsMeansNoEmptyBlock() {
        val blocks = blockOf(AgentIntent.Chat, emptyList())
        assertFalse(blocks.any { it.contains("<plugin_context>") }, "没交片段就不该有空的容器：" + blocks)
    }

    @Test
    fun multipleSectionsKeepTheirOrder() {
        val blocks = blockOf(
            AgentIntent.Chat,
            listOf("<goal_context>第一段</goal_context>", "<goal_context>第二段</goal_context>"),
        )
        val block = blocks.first { it.startsWith("<plugin_context>") }
        assertTrue(block.indexOf("第一段") < block.indexOf("第二段"), "顺序要跟调用方给的一致：" + block)
    }
}
