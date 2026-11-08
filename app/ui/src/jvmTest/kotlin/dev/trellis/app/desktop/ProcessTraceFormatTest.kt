package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 过程行折叠时只露一行参数预览，点开才展开完整 JSON。 */
class ProcessTraceFormatTest {

    @Test
    fun longArgumentsAreCutToASinglePreviewLine() {
        val raw = """{"week": "2026-W45", "focus": "命令行与 Python 起步", "items": [1,2,3]}"""
        val compact = compactArguments(raw, max = 24)
        assertTrue(compact.length <= 25, compact)
        assertTrue(compact.endsWith("…"), compact)
        assertTrue(!compact.contains("\n"), compact)
    }

    @Test
    fun compactArgumentsCollapsesWhitespaceAndHidesEmptyObjects() {
        assertEquals("{ \"a\": 1 }", compactArguments("{\n \"a\":  1\n}"))
        assertEquals("", compactArguments("{}"))
        assertEquals("", compactArguments("   "))
    }

    @Test
    fun theActionCountOnlyCountsRealToolCalls() {
        val trace = ChatToolTrace(
            tool = "research_plan",
            description = "查资料并建计划",
            arguments = "{}",
            result = "好了",
            isError = false,
        )
        val steps = listOf(
            ChatLine(1, ChatRole.Step, "思考：先看看它想干嘛"),
            ChatLine(2, ChatRole.Step, "判断：它要动手，切到工具模式"),
            ChatLine(3, ChatRole.Step, "第 1 步：research_plan", tool = trace),
            ChatLine(4, ChatRole.Step, "　· 查到 3 条资料，开始拆阶段"),
        )

        // 「2 个动作」的旧算法把思考/判断也算进去，摘要就和个人体验对不上。
        assertEquals(1, processActionCount(steps))
        assertEquals(0, processActionCount(emptyList()))
    }

    @Test
    fun prettyArgumentsIndentsJsonAndKeepsBrokenInputAsIs() {
        val pretty = prettyArguments("""{"week":"2026-W45"}""")
        assertTrue(pretty.contains("\n"), pretty)
        assertTrue(pretty.contains("\"week\""), pretty)
        assertEquals("not json", prettyArguments("not json"))
    }

    @Test
    fun toolRowsShowAHumanActionNotTheStepPrefix() {
        // ToolStepRow 传进来的是工具名，不是「第 1 步：xxx」；否则会原样露出内部名字。
        assertEquals("读了计划文档", toolActionLabel("read_plan_doc"))
        assertEquals("调研并建了计划", toolActionLabel("research_plan"))
        assertEquals("查了学习计划", toolActionLabel("get_learning_plan"))
    }
}
