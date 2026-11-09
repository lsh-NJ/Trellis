package dev.trellis.app.desktop

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 过程行：折叠态每条只留一个标签（不做预览摘要），点开才给正文；
 * 整块的高度上限按视口算，超出的在块内滚。
 */
class ProcessTraceFormatTest {

    @Test
    fun thinkingRowOnlySaysThinkingAndCarriesTheBodyAsDetail() {
        val view = processRowView("思考：先看看它想干嘛，再决定要不要动手。")
        assertEquals(ProcessRowView("思考", "先看看它想干嘛，再决定要不要动手。"), view)
    }

    @Test
    fun judgementAndResultRowsUseShortLabels() {
        assertEquals(ProcessRowView("判断", "它要动手，切到工具模式"), processRowView("判断：它要动手，切到工具模式"))
        assertEquals(ProcessRowView("结果", "查到 3 条资料"), processRowView("　→ 查到 3 条资料"))
    }

    @Test
    fun toolRowsShowOnlyTheToolNameAndHaveNoTextDetail() {
        // 参数和结果在 ChatLine.tool 里，折叠行的文本行不该再重复它们。
        val view = processRowView("第 1 步：research_plan")
        assertEquals(ProcessRowView("research_plan", null), view)
    }

    @Test
    fun thinkingDetailIsBlankWhenThereIsNoBody() {
        // 「思考：」后面没字：标签还在，但没有可展开的正文。
        val view = processRowView("思考：")
        assertEquals("思考", view?.label)
        assertNull(view?.detail)
    }

    @Test
    fun oldSessionsFinishedMarkerIsHiddenUnlessThereIsACatchUpReason() {
        assertNull(processRowView("　· 它说完了"))
        assertEquals(
            ProcessRowView("还要补", "补一张图"),
            processRowView("　· 它说完了，但 补一张图"),
        )
    }

    @Test
    fun plainProgressLinesKeepTheirOwnText() {
        assertEquals(ProcessRowView("出错了：LLM 没配置", "出错了：LLM 没配置"), processRowView("出错了：LLM 没配置"))
        assertNull(processRowView("   "))
    }

    @Test
    fun hostCorrectionsAreHiddenFromTheProcessList() {
        // 模型没吐 JSON、宿主让它重说：这是宿主和模型之间的返工，不给用户看。
        // 旧会话里这类行已经落成独立文本行，读取时也要滤掉。
        assertNull(processRowView("第 2 步：格式不对（里面没有 JSON），让它重说"))
        assertNull(processRowView("第 2 步输出格式不对（里面没有 JSON），已让它重说"))
        assertNull(processRowView("第 3 步：它想调不存在的工具 shell"))
    }

    @Test
    fun processWindowTakesJustOverHalfTheViewportWithinBounds() {
        assertEquals(440.dp, processPanelMaxHeight(800.dp))
        assertEquals(220.dp, processPanelMaxHeight(200.dp))
        assertEquals(560.dp, processPanelMaxHeight(1400.dp))
    }

    @Test
    fun prettyArgumentsIndentsJsonAndKeepsBrokenInputAsIs() {
        val pretty = prettyArguments("""{"week":"2026-W45"}""")
        assertTrue(pretty.contains("\n"), pretty)
        assertTrue(pretty.contains("\"week\""), pretty)
        assertEquals("not json", prettyArguments("not json"))
    }

    @Test
    fun toolDetailShowsAHumanActionNotTheStepPrefix() {
        // 展开详情里的一句人话：工具名 → 动作；折叠行本身已经只写工具名了。
        assertEquals("读了计划文档", toolActionLabel("read_plan_doc"))
        assertEquals("调研并建了计划", toolActionLabel("research_plan"))
        assertEquals("查了学习计划", toolActionLabel("get_learning_plan"))
    }
}
