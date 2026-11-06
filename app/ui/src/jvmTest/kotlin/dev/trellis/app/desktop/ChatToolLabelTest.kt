package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 过程行里的工具名要翻成人话（批 5）：
 * 模型看到 `create_learning_plan`，用户不该看到函数名。
 */
class ChatToolLabelTest {
    @Test
    fun knownToolsSpeakChinese() {
        assertEquals("建了学习计划", toolActionLabel("create_learning_plan"))
        assertEquals("勾了计划节点", toolActionLabel("complete_plan_node"))
        assertEquals("截了屏", toolActionLabel("take_screenshot"))
    }

    @Test
    fun shortArgumentsAreShown() {
        assertEquals("搜了 B 站课程（线性代数）", toolActionLabel("search_bilibili(线性代数)"))
    }

    @Test
    fun longArgumentsAreDropped() {
        assertEquals("加了待办", toolActionLabel("add_tasks(" + "很长的清单".repeat(10) + ")"))
    }

    @Test
    fun unknownToolsAreNotInvented() {
        assertEquals("mystery_tool", toolActionLabel("mystery_tool"))
        assertTrue(toolActionLabel("mystery_tool(x)").startsWith("mystery_tool"))
    }
}
