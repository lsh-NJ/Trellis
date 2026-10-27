package dev.trellis.ai.manager

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 意图路由是最便宜的一层开关，也是最容易悄悄坏掉的一层：
 * 判成 Chat 就永远不动手，判成 Create 就每句话都想去写插件。
 * 所以拿真实说法钉住它。
 */
class IntentClassifierTest {

    private fun classify(text: String) = KeywordIntentClassifier.classify(text)

    @Test
    fun arrangingComesBeforeCreatingAndManaging() {
        // 这句话里同时有「插件」和「排」
        assertEquals(AgentIntent.ArrangeUi, classify("把这三个插件横向排列"))
        assertEquals(AgentIntent.ArrangeUi, classify("把心情插件挪到首页上面"))
        assertEquals(AgentIntent.ArrangeUi, classify("这几张卡片放一起合成一个"))
    }

    @Test
    fun managingPluginsIsNotCreatingThem() {
        assertEquals(AgentIntent.ManagePlugin, classify("把心情打卡停掉"))
        assertEquals(AgentIntent.ManagePlugin, classify("现在装了什么插件"))
        assertEquals(AgentIntent.ManagePlugin, classify("把读书那个卸载了"))
        assertEquals(AgentIntent.ManagePlugin, classify("看看它的源码"))
    }

    @Test
    fun creatingIsDetectedFromWhatTheUserWantsToHave() {
        assertEquals(AgentIntent.CreatePlugin, classify("做一个俯卧撑记账"))
        assertEquals(AgentIntent.CreatePlugin, classify("我想要一个每天提醒喝水的卡片"))
        assertEquals(AgentIntent.CreatePlugin, classify("统计一下我这周读了多少页"))
    }

    @Test
    fun plainQuestionsStayChat() {
        assertEquals(AgentIntent.Chat, classify("你好"))
        assertEquals(AgentIntent.Chat, classify("你是谁"))
        assertEquals(AgentIntent.Chat, classify("   "))
    }

    @Test
    fun lookingForLearningResourcesIsItsOwnIntent() {
        assertEquals(AgentIntent.FindResource, classify("我想学操作系统，帮我找 B 站教程"))
        assertEquals(AgentIntent.FindResource, classify("有没有好的计算机网络课程"))
        assertEquals(AgentIntent.FindResource, classify("从哪学 k8s 比较快"))
        // 「做个学习计划」里有「做个」，但它要的是资源+计划，不是写插件：
        // 判成 CreatePlugin 会被 beforeFinishing 的「必须先 try_plugin + deploy」卡住。
        assertEquals(AgentIntent.FindResource, classify("帮我做个学习计划，用 B 站的分集当目录"))
        // 目标型说法（没提 B 站、也没有「学习」二字）也必须落在这儿：
        // 判成聊天就没有 set_plan / add_tasks / ask_choices，只能回一段「这做不到」。
        assertEquals(AgentIntent.FindResource, classify("我要速通操作系统"))
        assertEquals(AgentIntent.FindResource, classify("两个月之内考过六级"))
    }

    @Test
    fun uppercaseChineseStillWorks() {
        // 中文没有大小写，但空格和换行不该影响判断
        assertEquals(AgentIntent.ArrangeUi, classify("  把插件  排成一行  "))
    }
}
