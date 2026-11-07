package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 提示词的顺序就是缓存策略本身：静态前缀（含工作流）→ 动态状态 → 历史 → 这一句。
 * 顺序一挪，前缀缓存就全废了 —— 而这件事从功能上看不出来。
 */
class ManagerPromptTest {

    private val catalog = CapabilityCatalog(emptyList(), emptyList())
    private val prompt = ManagerPrompt(ManagerPrompt.buildBase(catalog, emptyList(), "守则"))

    @Test
    fun theRoleSaysWhatTheHostDraws() {
        assertTrue(prompt.base.contains("<what_the_host_draws>"))
        assertTrue(prompt.base.contains("圆角"))
        assertTrue(prompt.base.contains("宿主"))
    }

    @Test
    fun theStaticPrefixIsIdenticalEveryTime() {
        val again = ManagerPrompt.buildBase(catalog, emptyList(), "守则")
        assertEquals(prompt.base, again)
        assertFalse(prompt.base.contains("已装插件："))
    }

    @Test
    fun stateGoesAfterTheStaticPrefixAndBeforeHistory() {
        val history = listOf(
            LlmMessage(LlmRole.User, "之前说的话"),
            LlmMessage(LlmRole.Assistant, "之前的回答"),
        )
        val messages = prompt.messages("已装插件：1 个", "把话说清楚", history)

        assertEquals(LlmRole.System, messages[0].role)
        assertEquals(prompt.base, messages[0].content)
        assertEquals(LlmRole.System, messages[1].role)
        assertTrue(messages[1].content.contains("<current_state>"))
        assertEquals("之前说的话", messages[2].content)
        assertEquals("之前的回答", messages[3].content)
        assertEquals(LlmRole.User, messages.last().role)
        assertEquals("我想要：把话说清楚", messages.last().content)
    }

    @Test
    fun theIdentityComesBeforeThePluginManual() {
        // 以前第一句是「你是一个为 Trellis 写声明式插件的作者」，把管理员框成了插件作者；
        // 现在身份必须在最前，插件说明书只作为「要造插件时才看」的分支。
        val base = prompt.base
        val role = base.indexOf("你是这个应用的管理员")
        val pluginManual = base.indexOf("<when_you_make_a_plugin>")
        check(role >= 0) { "系统提示词开头要有管理员身份" }
        check(pluginManual > role) { "身份要排在插件说明书前面" }
        assertFalse(
            base.trimStart().startsWith("你是一个为 Trellis 写声明式插件的作者"),
            "第一句不能再是插件作者",
        )
    }

    @Test
    fun theWorkflowIsInTheStaticPrefixAndCoversTheWholeSurface() {
        // 工具全量之后，工作流也必须覆盖所有本事 —— 少写一条，那一类活就会被判成「不该做」。
        val base = prompt.base
        assertTrue("<task:work>" in base)
        for (key in listOf("学习计划", "B 站", "deadline", "confirmed", "插件", "deploy", "get_diagnostics")) {
            assertTrue(key in base, "工作流里要提到：$key")
        }
    }

    @Test
    fun aChatTurnCarriesNoExtraTaskModule() {
        // 删掉意图分类器之后没有「任务模块」了：每轮都是同一份静态前缀 + 这一句。
        val chat = prompt.messages("已装插件：1 个", "你好")
        val text = chat.joinToString("\n") { it.content }
        assertEquals(1, Regex("<task:work>").findAll(text).count())
        assertEquals(LlmRole.User, chat.last().role)
    }
}
