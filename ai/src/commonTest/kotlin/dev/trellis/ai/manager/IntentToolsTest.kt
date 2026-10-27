package dev.trellis.ai.manager

import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 意图 → 工具是**单选**：一轮只拿一套手，不是「全套减两只」。
 *
 * 真实事故：用户只是要「速通操作系统」，模型查完课顺手把「速通进度卡」
 * 装进了插件列表。提示词劝不住的时候，得让那只手根本不存在。
 */
class IntentToolsTest {

    private fun tool(name: String) = object : AgentTool {
        override val name: String = name
        override val description: String = ""
        override val parameters: String = ""
        override suspend fun call(arguments: JsonObject): ToolResult = ToolResult("")
    }

    private val all = listOf(
        tool("list_plugins"),
        tool("remove_plugin"),
        tool("try_plugin"),
        tool("deploy"),
        tool("list_cards"),
        tool("arrange_cards"),
        tool("set_theme"),
        tool("search_bilibili"),
        tool("add_tasks"),
        tool("set_plan"),
    )

    private fun names(intent: AgentIntent) = toolsForIntent(intent, all).map { it.name }

    @Test
    fun createAndManageShareThePluginLifecycleButOnlyCreateCanInstall() {
        val create = names(AgentIntent.CreatePlugin)
        assertTrue("try_plugin" in create)
        assertTrue("deploy" in create)
        assertTrue("list_plugins" in create)

        val manage = names(AgentIntent.ManagePlugin)
        assertTrue("remove_plugin" in manage)
        assertEquals(false, "deploy" in manage, "管插件不该有 deploy")
        assertEquals(false, "try_plugin" in manage, "管插件不该有 try_plugin")
    }

    @Test
    fun layoutIntentSeesOnlyLayoutHands() {
        val layout = names(AgentIntent.ArrangeUi)
        assertEquals(listOf("list_cards", "arrange_cards", "set_theme", "set_plan"), layout)
    }

    @Test
    fun resourceIntentSeesOnlyTheTwoReadOnlyToolsPlusTasks() {
        val resource = names(AgentIntent.FindResource)
        assertTrue("search_bilibili" in resource)
        assertTrue("add_tasks" in resource)
        assertEquals(false, "deploy" in resource)
        assertEquals(false, "arrange_cards" in resource)
    }

    @Test
    fun chatHasNoHandsAtAll() {
        assertEquals(emptyList(), names(AgentIntent.Chat))
    }

    @Test
    fun unknownToolsDefaultToThePluginIntents() {
        val mystery = tool("some_future_tool")
        val pool = all + mystery
        assertEquals(listOf("some_future_tool"), toolsForIntent(AgentIntent.ManagePlugin, pool).filter { it.name == "some_future_tool" }.map { it.name })
        assertEquals(emptyList(), toolsForIntent(AgentIntent.FindResource, pool).filter { it.name == "some_future_tool" }.map { it.name })
    }

    /**
     * 真实事故：资源意图下模型想调 `ask_choices` 把「只看建议 / 落成待办 / 排成计划」摆出来，
     * 结果路由没给它这只手，过程行写「它想调不存在的工具 ask_choices」，只能把问题写成一段话。
     */
    @Test
    fun askChoicesIsAvailableToEveryIntentThatCanChangeData() {
        val pool = all + tool("ask_choices")
        for (intent in listOf(
            AgentIntent.CreatePlugin,
            AgentIntent.ManagePlugin,
            AgentIntent.ArrangeUi,
            AgentIntent.FindResource,
        )) {
            assertTrue("ask_choices" in toolsForIntent(intent, pool).map { it.name }, "$intent 缺 ask_choices")
        }
        assertEquals(emptyList(), toolsForIntent(AgentIntent.Chat, pool).filter { it.name == "ask_choices" }.map { it.name })
    }
}
