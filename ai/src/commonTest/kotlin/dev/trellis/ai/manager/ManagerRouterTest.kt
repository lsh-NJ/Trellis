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
 * 装配层只剩两件事：**一套工具 + 一份提示词**。
 *
 * 2026-11-07 删掉意图分类器之后，这里钉的是「不给模型留退路」：
 * 工具目录全量、静态前缀一份、工作流一次 —— 少一样就会退回「判错意图 = 这轮干不了活」。
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
        tool("remember_memory", "记住长期目标"),
        tool("recall_memory", "回忆之前说过的事"),
    )

    private val router = ManagerRouter(
        catalog = CapabilityCatalog(emptyList(), emptyList()),
        allTools = all,
        guidance = "",
    )

    /** `<tools>` 段才是「这一轮给了哪些工具」的权威清单。 */
    private fun toolCatalogue(prompt: String): String {
        val start = prompt.indexOf("<tools>")
        val end = prompt.indexOf("</tools>")
        return if (start >= 0 && end > start) prompt.substring(start, end) else ""
    }

    @Test
    fun everyToolIsAvailableAndTheOrderIsStable() {
        assertEquals(all.map { it.name }.sorted(), router.tools.map { it.name })
        // 排序是为了前缀缓存：同一份装配在两次请求里逐字节一致。
        assertEquals(router.tools.map { it.name }, sortedToolset(all).map { it.name })
    }

    @Test
    fun thePromptCatalogueListsEveryTool() {
        val catalogue = toolCatalogue(router.prompt.base)
        assertTrue(catalogue.isNotBlank(), "<tools> 段不能是空的")
        for (name in all.map { it.name }) {
            assertTrue(name in catalogue, "全量工具目录里要有 $name")
        }
    }

    @Test
    fun thereIsOneStaticPrefixAndNoPerIntentModules() {
        val base = router.prompt.base
        for (legacy in listOf(
            "<task:create_plugin>",
            "<task:manage_plugin>",
            "<task:arrange_ui>",
            "<task:find_resource>",
            "<task:plan_manage>",
        )) {
            assertFalse(legacy in base, "静态前缀里不该再有某一类意图的模块：$legacy")
        }
        assertTrue("<task:work>" in base, "通用工作流要在静态前缀里")
        assertEquals(1, Regex("<task:work>").findAll(base).count(), "工作流只该出现一次")
        // 同一次装配拿两次，逐字节一致 —— 前缀缓存的前提。
        assertEquals(base, ManagerPrompt.buildBase(CapabilityCatalog(emptyList(), emptyList()), all, ""))
    }

    @Test
    fun aRequestOnlyCarriesThatOneStaticPrefix() {
        val messages = router.prompt.messages("", "把话说清楚")
        val text = messages.joinToString("\n") { it.content }
        assertEquals(1, Regex("<task:work>").findAll(text).count(), "每轮只带一份工作流")
        assertTrue(messages.first().content == router.prompt.base)
        assertTrue(messages.last().content == "我想要：把话说清楚")
    }
}
