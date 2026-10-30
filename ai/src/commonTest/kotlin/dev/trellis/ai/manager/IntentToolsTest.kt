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
            AgentIntent.PlanManage,
        )) {
            assertTrue("ask_choices" in toolsForIntent(intent, pool).map { it.name }, "$intent 缺 ask_choices")
        }
        assertEquals(emptyList(), toolsForIntent(AgentIntent.Chat, pool).filter { it.name == "ask_choices" }.map { it.name })
    }

    /**
     * 管学习计划的那一轮，手里只有计划那几只：读现状、推进周节奏、勾节点、顺延、复盘、改每天投入。
     * 没有 deploy / arrange_cards —— 想顺手造插件或排界面，手根本不在。
     */
    @Test
    fun planManageSeesOnlyThePlanHands() {
        val planTools = listOf(
            "get_learning_plan",
            "plan_next_week",
            "complete_plan_node",
            "move_plan_node",
            "review_last_week",
            "set_plan_pace",
        )
        val pool = all + planTools.map { tool(it) } + tool("step_done") + tool("ask_choices")
        val plan = toolsForIntent(AgentIntent.PlanManage, pool).map { it.name }

        planTools.forEach { assertTrue(it in plan, "PlanManage 缺 $it") }
        assertTrue("set_plan" in plan, "多步活要能摆清单")
        assertTrue("step_done" in plan, "清单要能打勾")
        assertEquals(false, "deploy" in plan, "管计划不该有 deploy")
        assertEquals(false, "arrange_cards" in plan, "管计划不该有 arrange_cards")

        // 新工具显式登记过，就不会落进「默认给插件意图」的兜底里 ——
        // 否则「做个插件」那一轮也拿到 complete_plan_node，又变成越界。
        val create = toolsForIntent(AgentIntent.CreatePlugin, pool).map { it.name }
        val manage = toolsForIntent(AgentIntent.ManagePlugin, pool).map { it.name }
        assertEquals(false, "get_learning_plan" in create)
        assertEquals(false, "plan_next_week" in manage)
    }

    /** 「在动已有计划」的话要判 PlanManage，但「排成学习计划」「速通/学会」仍然是找资源。 */
    @Test
    fun planMaintenanceIsItsOwnIntentButBuildingAPlanIsStillResourceWork() {
        assertEquals(AgentIntent.PlanManage, KeywordIntentClassifier.classify("帮我复盘这周计划"))
        assertEquals(AgentIntent.PlanManage, KeywordIntentClassifier.classify("帮我看看这周计划/复盘一下"))
        assertEquals(AgentIntent.PlanManage, KeywordIntentClassifier.classify("这周没怎么看，帮我调整"))
        assertEquals(AgentIntent.PlanManage, KeywordIntentClassifier.classify("上周的节点有几个没做完，顺延一下"))
        // 「排成学习计划」是往计划页新建，不是维护现成的那份：
        // 判成 PlanManage 就没有 create_learning_plan，计划页还是空的。
        assertEquals(AgentIntent.FindResource, KeywordIntentClassifier.classify("把视频排成学习计划"))
        // 目标型说法（速通/学会）也不能被计划词抢走。
        assertEquals(AgentIntent.FindResource, KeywordIntentClassifier.classify("这周我要学会操作系统"))
        // 「完成」是计划词，但「完成 B 站课程」要先找资源，别被计划词抢走。
        assertEquals(AgentIntent.FindResource, KeywordIntentClassifier.classify("完成 B 站课程"))
    }

    /**
     * 「自己看错」是通用能力：哪个意图的工具报错，那个意图就得能查诊断，
     * 否则模型只能把错误原样丢给用户（真实事故：打包版缺 java.sql，用户看到 java/sql/DriverManager）。
     */
    @Test
    fun everyWorkingIntentCanLookAtItsOwnDiagnostics() {
        val pool = all + tool("get_diagnostics")
        for (intent in listOf(
            AgentIntent.CreatePlugin,
            AgentIntent.ManagePlugin,
            AgentIntent.ArrangeUi,
            AgentIntent.FindResource,
            AgentIntent.PlanManage,
        )) {
            assertTrue("get_diagnostics" in toolsForIntent(intent, pool).map { it.name }, "$intent 缺 get_diagnostics")
        }
        assertEquals(emptyList(), toolsForIntent(AgentIntent.Chat, pool).filter { it.name == "get_diagnostics" }.map { it.name })
    }
}
