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

    /**
     * Chat 意图手无寸铁，但模型有时会自己点名一个真实工具。
     * `intentForTool` 是那条补救路径的入口：从工具名倒推出拥有它的意图。
     */
    @Test
    fun aNamedToolCanBeTracedBackToTheIntentThatOwnsIt() {
        val recoveryRouter = ManagerRouter(
            catalog = CapabilityCatalog(emptyList(), emptyList()),
            allTools = listOf(
                tool("create_learning_plan"),
                tool("get_diagnostics"),
                tool("arrange_cards"),
                tool("made_up_tool"),
            ),
            guidance = "",
        )

        assertEquals(AgentIntent.FindResource, recoveryRouter.intentForTool("create_learning_plan"))
        // 多归属（五个动手意图都有 get_diagnostics）：按兜底优先级先给任务类。
        assertEquals(AgentIntent.FindResource, recoveryRouter.intentForTool("get_diagnostics"))
        assertEquals(AgentIntent.ArrangeUi, recoveryRouter.intentForTool("arrange_cards"))
        // 没登记的工具按默认归属（造/改插件、管插件）算。
        assertEquals(AgentIntent.CreatePlugin, recoveryRouter.intentForTool("made_up_tool"))
        assertEquals(null, recoveryRouter.intentForTool("not_even_installed"))
    }

    /**
     * P1 实验（`TRELLIS_MANAGER_STABLE_TOOLS=1`）：工具集不再按意图裁。
     *
     * 前提是「所有意图拿到同一套全量工具，且 Chat 也有」。少一个意图或 Chat 空手，
     * 就退回到「判错意图 = 这轮干不了活」的老毛病。
     */
    @Test
    fun theStableExperimentGivesEveryIntentTheSameFullToolset() {
        val stable = ManagerRouter(
            catalog = CapabilityCatalog(emptyList(), emptyList()),
            allTools = all,
            guidance = "",
            stableTools = true,
        )
        val expected = all.map { it.name }.sorted()
        for (intent in AgentIntent.entries) {
            assertEquals(
                expected,
                stable.tools(intent).map { it.name },
                "稳定模式下每个意图都拿到同一套全量工具（含 Chat）：$intent",
            )
        }
        assertTrue(stable.stableTools)
    }

    /**
     * 稳定模式的提示词必须是**一份**静态前缀：六个意图逐字节相同，
     * 工作流在 `<task:general>` 里，旧的按意图任务模块不再出现。
     */
    @Test
    fun theStableExperimentUsesOneStaticPrefixForEveryIntent() {
        val stable = ManagerRouter(
            catalog = CapabilityCatalog(emptyList(), emptyList()),
            allTools = all,
            guidance = "",
            stableTools = true,
        )
        val bases = AgentIntent.entries.map { stable.prompt(it).base }
        assertEquals(1, bases.toSet().size, "稳定模式下所有意图共用一份静态前缀")

        val base = bases.first()
        assertTrue("<task:general>" in base)
        for (legacy in listOf("<task:create_plugin>", "<task:manage_plugin>", "<task:arrange_ui>", "<task:find_resource>", "<task:plan_manage>")) {
            assertFalse(legacy in base, "静态前缀里不该再写某一类意图的模块：$legacy")
        }
        for (name in all.map { it.name }) {
            assertTrue(name in toolCatalogue(base), "全量工具目录里要有 $name")
        }

        // 每一轮只带静态前缀里那一份通用工作流，末尾不再追加意图模块。
        for (intent in AgentIntent.entries) {
            val text = stable.prompt(intent).messages("", "你好", emptyList(), intent)
                .joinToString("\n") { it.content }
            assertEquals(1, Regex("<task:general>").findAll(text).count(), "工作流只该出现在静态前缀里一次：$intent")
            for (legacy in listOf("<task:create_plugin>", "<task:find_resource>", "<task:plan_manage>")) {
                assertFalse(legacy in text, "稳定模式这一轮不该有 $legacy")
            }
        }
    }
}
