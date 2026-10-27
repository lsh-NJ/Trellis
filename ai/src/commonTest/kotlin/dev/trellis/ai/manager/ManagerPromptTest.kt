package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 分层提示词的顺序就是缓存策略本身。
 *
 * 这些断言不是在测「文案」：它们钉的是**稳定前缀必须逐字节一致**、
 * 动态状态必须在静态前缀之后、任务模块必须在历史之后。
 * 顺序一挪，前缀缓存就全废了 —— 而这件事从功能上看不出来。
 */
class ManagerPromptTest {

    private val catalog = CapabilityCatalog(emptyList(), emptyList())
    private val prompt = ManagerPrompt(ManagerPrompt.buildBase(catalog, emptyList(), "守则"))

    @Test
    fun theRoleSaysWhatTheHostDraws() {
        // 卡片外框是宿主的：模型不知道这件事，就会把「给每张卡加圆角边框」
        // 当成插件能力，然后一本正经地拒绝一个其实早就实现了的需求。
        assertTrue(prompt.base.contains("<what_the_host_draws>"))
        assertTrue(prompt.base.contains("圆角"))
        assertTrue(prompt.base.contains("宿主"))
    }

    @Test
    fun theStaticPrefixIsIdenticalEveryTime() {
        val again = ManagerPrompt.buildBase(catalog, emptyList(), "守则")
        assertEquals(prompt.base, again)
        // 前缀里不该出现「当前装了什么」这种每轮都变的东西
        assertTrue(!prompt.base.contains("已装插件："))
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
    fun aTaskModuleOnlyShowsUpForWork() {
        assertNull(ManagerPrompt.taskModule(AgentIntent.Chat))

        val creating = ManagerPrompt.taskModule(AgentIntent.CreatePlugin)
        assertTrue(creating!!.contains("<task:create_plugin>"))
        assertTrue(ManagerPrompt.taskModule(AgentIntent.ManagePlugin)!!.contains("<task:manage_plugin>"))
        assertTrue(ManagerPrompt.taskModule(AgentIntent.ArrangeUi)!!.contains("<task:arrange_ui>"))

        // 聊天时不插模块：模块是有副作用倾向的指令，不能陪聊。
        // （静态前缀的守则里本来就写着 <task:...> 这三个字，所以按模块全文比对，
        //  不能只看内容里有没有 "<task:"。）
        val chat = prompt.messages("已装插件：1 个", "你好")
        val modules = listOf(
            ManagerPrompt.taskModule(AgentIntent.CreatePlugin),
            ManagerPrompt.taskModule(AgentIntent.ManagePlugin),
            ManagerPrompt.taskModule(AgentIntent.ArrangeUi),
        )
        assertTrue(chat.none { it.content in modules })
    }

    @Test
    fun theArrangeModulePointsAtTheLayoutToolsInsteadOfPretending() {
        val module = ManagerPrompt.taskModule(AgentIntent.ArrangeUi)!!
        // 布局现在真的能改了：模块必须把 agent 指向那两个工具，
        // 并且说清边界（横排可以，网格/拖拽不行），这样它不会一边说做不到、
        // 一边又把已经能做的也说成做不到。
        assertTrue(module.contains("list_cards"))
        assertTrue(module.contains("arrange_cards"))
        assertTrue(module.contains("横排"))
        assertTrue(module.contains("网格"), "没有网格这件事必须写清楚，否则它会去承诺")
    }

    @Test
    fun theResourceModulePointsAtTheBilibiliToolsAndStatesTheBoundary() {
        val module = ManagerPrompt.taskModule(AgentIntent.FindResource)!!
        // 资源不在模型脑子里：必须让它去查，而不是凭记忆报课程名。
        assertTrue(module.contains("search_bilibili"))
        assertTrue(module.contains("list_bilibili_parts"))
        // 分集要变成用户能勾的待办；清单只装这一轮的动作。
        assertTrue(module.contains("add_tasks"))
        assertTrue(module.contains("set_plan"))
        // 只读元数据这条边界要说清，否则它会承诺「帮你下载/判断讲得好不好」。
        assertTrue(module.contains("不能播放"))
        assertTrue(module.contains("下载"))
    }
}
