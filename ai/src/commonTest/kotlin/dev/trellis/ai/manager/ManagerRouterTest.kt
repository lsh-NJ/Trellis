package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.tool.AgentTool
import dev.trellis.ai.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 流程必须是这个次序：意图（单选）→ 工具（单选）→ 提示词（按这轮的意图拼）→ 模型。
 *
 * 这里钉两件事：①提示词里只列这一轮真的能用到的工具；②有歧义时不往下走。
 */
class ManagerRouterTest {

    private fun tool(name: String, description: String = "") = object : AgentTool {
        override val name: String = name
        override val description: String = description
        override val parameters: String = ""
        override suspend fun call(arguments: JsonObject): ToolResult = ToolResult("")
    }

    private val all = listOf(
        tool("deploy", "把插件装上"),
        tool("search_bilibili", "搜 B 站视频"),
        tool("arrange_cards", "摆卡片"),
        tool("list_plugins", "列插件"),
    )

    private val router = ManagerRouter(
        catalog = CapabilityCatalog(emptyList(), emptyList()),
        allTools = all,
        guidance = "",
    )

    @Test
    fun thePromptOnlyListsTheToolsOfThisIntent() {
        val find = router.prompt(AgentIntent.FindResource).base
        assertTrue("search_bilibili" in find)
        assertFalse("deploy" in find, "找资源的提示词里不该出现 deploy")
        assertFalse("arrange_cards" in find)

        val create = router.prompt(AgentIntent.CreatePlugin).base
        assertTrue("deploy" in create)
        assertFalse("search_bilibili" in create)
    }

    @Test
    fun chatGetsNoToolCatalogueAtAll() {
        val chat = router.prompt(AgentIntent.Chat).base
        for (name in listOf("deploy", "search_bilibili", "arrange_cards", "list_plugins")) {
            assertFalse(name in chat, "聊天那一轮不该看到工具 $name")
        }
    }

    @Test
    fun anAmbiguousSentenceStopsBeforeToolsAndModel() {
        val decision = router.decide("把视频排成一行")
        assertTrue(decision.ambiguous)
        assertTrue(decision.question.isNotBlank())
        assertEquals(listOf("找学习资源", "调一下界面"), decision.choices)
    }

    @Test
    fun aVagueHalfSentenceAsksInsteadOfGuessing() {
        val decision = router.decide("帮我弄一下")
        assertTrue(decision.ambiguous)
        assertTrue("做个/改个插件" in decision.choices)
    }

    @Test
    fun aPlainChatAndAClearTaskAreNotAmbiguous() {
        assertFalse(router.decide("你是谁").ambiguous)
        assertFalse(router.decide("把首页的卡片横着排").ambiguous)
        assertEquals(AgentIntent.ArrangeUi, router.decide("把首页的卡片横着排").intent)
    }
}
