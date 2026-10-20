package dev.trellis.kernel.expr

import kotlin.test.Test
import kotlin.test.assertEquals

/** 字符串判断。规则的 when 要靠它挡住「自己触发自己」。 */
class StringFunctionTest {

    private fun eval(source: String, vararg values: Pair<String, String>): String =
        Expression.parse(source)
            .evaluate(values.associate { (k, v) -> k to ExprValue.Text(v) })
            .render()

    @Test
    fun startsWithCatchesTheTitleItWouldGenerate() {
        val source = "startsWith(event.title, '复习：')"
        assertEquals("是", eval(source, "event.title" to "复习：完成实验"))
        assertEquals("否", eval(source, "event.title" to "完成实验"))
    }

    @Test
    fun theNegatedFormIsWhatTheRuleNeeds() {
        val source = "!startsWith(event.title, '复习：')"
        assertEquals("否", eval(source, "event.title" to "复习：完成实验"))
        assertEquals("是", eval(source, "event.title" to "完成实验"))
    }

    @Test
    fun endsWithAndContainsWorkTheSameWay() {
        assertEquals("否", eval("endsWith(event.title, '报告')", "event.title" to "写周报"))
        assertEquals("是", eval("endsWith(event.title, '报告')", "event.title" to "写完报告"))
        assertEquals("是", eval("contains(event.title, '复习')", "event.title" to "复习：第一章"))
    }

    @Test
    fun aMissingFieldMakesTheWholeThingAbsent() {
        assertEquals("--", eval("startsWith(event.nothing, '复习：')", "event.title" to "完成实验"))
    }
}
