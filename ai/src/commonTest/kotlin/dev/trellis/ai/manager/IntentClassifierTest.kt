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
    fun uppercaseChineseStillWorks() {
        // 中文没有大小写，但空格和换行不该影响判断
        assertEquals(AgentIntent.ArrangeUi, classify("  把插件  排成一行  "))
    }
}
