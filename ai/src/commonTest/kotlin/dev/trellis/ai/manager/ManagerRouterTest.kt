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

    /**
     * `<tools>` 段才是「这一轮给了哪些工具」的权威清单。
     *
     * base 里还有一段不变的 `<your_role>` 守则，会拿具体工具名举例
     * （比如「先查资源（search_bilibili）」）——那是给模型的流程建议，不是工具目录。
     * 断言必须只看目录，否则守则里提一次工具名就会被误判成「工具被列出来了」。
     */
    private fun toolCatalogue(prompt: String): String {
        val start = prompt.indexOf("<tools>")
        val end = prompt.indexOf("</tools>")
        return if (start >= 0 && end > start) prompt.substring(start, end) else ""
    }

    @Test
    fun thePromptOnlyListsTheToolsOfThisIntent() {
        val find = toolCatalogue(router.prompt(AgentIntent.FindResource).base)
        assertTrue("search_bilibili" in find)
        assertFalse("deploy" in find, "找资源的工具目录里不该出现 deploy")
        assertFalse("arrange_cards" in find)

        val create = toolCatalogue(router.prompt(AgentIntent.CreatePlugin).base)
        assertTrue("deploy" in create)
        assertFalse("search_bilibili" in create)
    }

    @Test
    fun chatGetsNoToolCatalogueAtAll() {
        assertTrue(router.tools(AgentIntent.Chat).isEmpty(), "纯聊天不该拿到任何工具")

        val chat = toolCatalogue(router.prompt(AgentIntent.Chat).base)
        assertTrue(chat.isNotBlank(), "空清单也要有 <tools> 段，模型才知道这一轮没有手")
        for (name in listOf("deploy", "search_bilibili", "arrange_cards", "list_plugins")) {
            assertFalse(name in chat, "聊天那一轮的工具目录里不该有 $name")
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
