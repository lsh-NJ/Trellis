package dev.trellis.ai.manager

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        // B 站课程排计划要全量导入：AI 只选源，不再逐条抄分集标题。
        assertTrue(module.contains("bvid"))
        // 只读元数据这条边界要说清，否则它会承诺「帮你下载/判断讲得好不好」。
        assertTrue(module.contains("不能播放"))
        assertTrue(module.contains("下载"))
    }

    @Test
    fun thePlanManageModulePointsAtThePlanToolsAndKeepsTheToneRule() {
        val module = ManagerPrompt.taskModule(AgentIntent.PlanManage)!!
        // 读状态、推进周节奏、勾节点、复盘都要有明确的工具入口，否则模型只能空谈。
        assertTrue(module.contains("get_learning_plan"))
        assertTrue(module.contains("plan_next_week"))
        assertTrue(module.contains("complete_plan_node"))
        assertTrue(module.contains("review_last_week"))
        // 写操作必须先拿到用户同意。
        assertTrue(module.contains("confirmed=true"))
        // 文案约束是产品硬要求（不羞辱、不用损失框架），必须钉在提示词里。
        assertTrue(module.contains("不羞辱"))
        assertTrue(module.contains("落后 N 天"))
    }

    /**
     * 工具报错时模型要「自己查」，不能把环境问题原样丢给用户。
     * 真实事故：打包版缺 java.sql，模型只回「计划库打不开，你去计划页看一眼错误提示」。
     */
    @Test
    fun theRoleTellsTheModelToDiagnoseItsOwnToolFailures() {
        assertTrue(prompt.base.contains("<when_a_tool_fails>"))
        assertTrue(prompt.base.contains("get_diagnostics"))
        assertTrue(prompt.base.contains("环境问题"))
        assertTrue(prompt.base.contains("用户修不好"))
    }

    /**
     * P1 实验：稳定工具集模式下，静态前缀末尾多一份 `<task:general>` 通用工作流。
     * 旧路径必须一个字节都不变（A/B 的基线）。
     */
    @Test
    fun theStableExperimentAddsOneGeneralWorkflowToTheStaticPrefix() {
        assertFalse("<task:general>" in prompt.base, "旧路径的静态前缀里不该有通用工作流")

        val stable = ManagerPrompt.buildBase(catalog, emptyList(), "守则", stableTools = true)
        assertTrue("<task:general>" in stable)
        // 前缀仍然是静态的：两次生成逐字节一致。
        assertEquals(stable, ManagerPrompt.buildBase(catalog, emptyList(), "守则", stableTools = true))
        // 通用工作流必须覆盖「动手」的全部面子，否则全量工具配一份缺胳膊少腿的流程。
        for (keyword in listOf(
            "search_bilibili",
            "create_learning_plan",
            "get_learning_plan",
            "plan_next_week",
            "ask_choices",
            "deploy",
            "get_diagnostics",
            "confirmed=true",
        )) {
            assertTrue(keyword in stable, "通用工作流里要提到 $keyword")
        }
    }

    /**
     * 稳定模式下 messages() 不再按意图追加任务模块：工作流只在静态前缀里出现一次，
     * 否则每一项都要重算一遍「这轮是哪类意图」，缓存和旧毛病一起回来。
     */
    @Test
    fun theStableExperimentDoesNotAppendAPerIntentTaskModule() {
        val stable = ManagerPrompt(
            ManagerPrompt.buildBase(catalog, emptyList(), "守则", stableTools = true),
            stableTools = true,
        )
        val messages = stable.messages("已装插件：1 个", "把视频排成计划", emptyList(), AgentIntent.FindResource)
        val text = messages.joinToString("\n") { it.content }

        assertEquals(1, Regex("<task:general>").findAll(text).count(), "通用工作流只该来自静态前缀")
        for (legacy in listOf("<task:create_plugin>", "<task:find_resource>", "<task:plan_manage>")) {
            assertFalse(legacy in text, "稳定模式不该再追加 $legacy")
        }
        assertEquals(LlmRole.User, messages.last().role)
        assertEquals("我想要：把视频排成计划", messages.last().content)
    }
}
