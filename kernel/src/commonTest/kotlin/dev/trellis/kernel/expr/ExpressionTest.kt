package dev.trellis.kernel.expr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionTest {

    private fun eval(source: String, values: Map<String, ExprValue> = emptyMap()): String =
        Expression.parse(source).evaluate(values).render()

    private fun num(n: Double) = ExprValue.Num(n)
    private fun text(s: String) = ExprValue.Text(s)

    // ---- 算术 ----

    @Test
    fun arithmeticFollowsPrecedence() {
        assertEquals("14", eval("2 + 3 * 4"))
        assertEquals("20", eval("(2 + 3) * 4"))
        assertEquals("-5", eval("10 - 15"))
        assertEquals("1", eval("7 % 3"))
        assertEquals("2.33", eval("7 / 3"))
    }

    @Test
    fun divisionByZeroBecomesAbsent() {
        assertEquals("--", eval("1 / 0"))
        assertEquals("--", eval("1 % 0"))
    }

    /** 溢出不许变成 Infinity 再显示成奇怪的东西。 */
    @Test
    fun hugeResultsBecomeAbsent() {
        assertEquals("--", eval("1e300 * 1e300"))
    }

    // ---- 字段引用 ----

    /** 数据源给的都是字符串，所以文本必须能自动当数字用。 */
    @Test
    fun textFieldsAreCoercedToNumbers() {
        val values = mapOf("summary.done" to text("2"), "summary.total" to text("4"))
        assertEquals("50", eval("summary.done * 100 / summary.total", values))
        assertEquals("50", eval("percent(summary.done, summary.total)", values))
    }

    @Test
    fun referencesListWhatItNeeds() {
        val expr = Expression.parse("summary.done * 100 / summary.total + summary.todo")
        assertEquals(listOf("summary.done", "summary.total", "summary.todo"), expr.references)
    }

    /** 缺一个字段 -> 整个表达式没有值，而不是偷偷当 0 算出一个像模像样的错数。 */
    @Test
    fun aMissingFieldPoisonsTheWholeExpression() {
        assertEquals("--", eval("summary.done + summary.total", mapOf("summary.done" to num(1.0))))
    }

    @Test
    fun nonNumericTextInArithmeticIsAbsent() {
        assertEquals("--", eval("a + 1", mapOf("a" to text("很多"))))
    }

    // ---- 比较与逻辑 ----

    @Test
    fun comparisonsWorkOnNumbersAndText() {
        val values = mapOf("done" to text("3"))
        assertEquals("是", eval("done > 2", values))
        assertEquals("否", eval("done > 3", values))
        assertEquals("是", eval("done >= 3", values))
        assertEquals("是", eval("done == 3", values))
        assertEquals("是", eval("done != 4", values))
        assertEquals("是", eval("'a' < 'b'"))
    }

    @Test
    fun logicalOperatorsShortCircuit() {
        // 右边是 1/0（Absent）。短路生效的话根本不该去算它
        assertEquals("否", eval("0 == 1 && 1 / 0"))
        assertEquals("是", eval("1 == 1 || 1 / 0"))
    }

    @Test
    fun absentOnTheLeftOfAndOrPropagates() {
        assertEquals("--", eval("missing && true"))
        assertEquals("--", eval("missing || true"))
    }

    // ---- 三元 ----

    @Test
    fun conditionalPicksABranch() {
        val zero = mapOf("todo" to text("0"))
        assertEquals("清空了", eval("todo == 0 ? '清空了' : '还有东西'", zero))
        assertEquals("还有东西", eval("todo > 0 ? '清空了' : '还有东西'", zero))
    }

    // ---- 函数 ----

    @Test
    fun builtinFunctions() {
        assertEquals("3", eval("round(2.5)"))
        assertEquals("2.57", eval("round(2.567, 2)"))
        assertEquals("3", eval("floor(3.9)"))
        assertEquals("4", eval("ceil(3.1)"))
        assertEquals("5", eval("abs(-5)"))
        assertEquals("1", eval("min(3, 1, 2)"))
        assertEquals("3", eval("max(3, 1, 2)"))
    }

    @Test
    fun anUnknownFunctionIsAbsentNotAnError() {
        assertEquals("--", eval("deleteEverything(1)"))
    }

    // ---- 解析错误：消息必须能照着改 ----

    @Test
    fun syntaxErrorsPointAtTheProblem() {
        val message = assertFailsWith<ExpressionException> { Expression.parse("1 + * 2") }.message.orEmpty()
        assertTrue(message.contains("第 5 个字符"), "要指出位置：" + message)
        assertTrue(message.contains("*"), "要指出是哪个符号：" + message)
    }

    @Test
    fun aBadFieldNameIsExplained() {
        val message = assertFailsWith<ExpressionException> {
            Expression.parse("summary..todo + 1")
        }.message.orEmpty()
        assertTrue(message.contains("summary..todo"), "要把写错的原样带出来：" + message)
        assertTrue(message.contains("例如 summary.todo"), "要给个正确写法：" + message)
    }

    @Test
    fun anEmptyExpressionIsRejected() {
        assertFailsWith<ExpressionException> { Expression.parse("   ") }
    }

    @Test
    fun tooLongIsRejected() {
        assertFailsWith<ExpressionException> { Expression.parse("1" + " + 1".repeat(100)) }
    }

    @Test
    fun anUnclosedParenthesisIsReported() {
        val message = assertFailsWith<ExpressionException> { Expression.parse("(1 + 2") }.message.orEmpty()
        assertTrue(message.contains("括号"), "要提示是括号的问题：" + message)
    }

    @Test
    fun anUnclosedStringIsReported() {
        val message = assertFailsWith<ExpressionException> { Expression.parse("'没关上") }.message.orEmpty()
        assertTrue(message.contains("引号"), "要提示是引号的问题：" + message)
    }

    // ---- 全函数性：这一节才是「不需要沙箱」的底气 ----

    /**
     * 随便给什么输入，都只有两种结局：**解析失败**，或者**算出一个值**。
     * 不允许抛别的异常，不允许卡住。
     */
    @Test
    fun parsingArbitraryGarbageOnlyEverFailsWithOurOwnError() {
        val garbage = listOf(
            "", "   ", "(", ")", "((((((", "1 +", "* 2", "???", "a ? b", "a : b",
            "1 2 3", "\"未闭合", "'", "..", ".", "a.", "a..b", "1 === 2", "@#\$",
            "round(", "round(1,", "1e", "0x10", "true", "null", "a &&& b", "a[0]", "a.b(c)",
        )
        for (source in garbage) {
            // 只允许 ExpressionException。抛别的类型 = 测试失败。
            try {
                Expression.parse(source)
            } catch (ignored: ExpressionException) {
                // 预期之内
            }
        }
    }

    /**
     * 解析成功的表达式，对**任意**字段值都必须返回一个值。
     * 空串、非数字、极大数、负数、纯符号 —— 都不许抛异常。
     */
    @Test
    fun evaluatingWithArbitraryValuesAlwaysReturnsAValue() {
        val expressions = listOf(
            "a * 100 / b",
            "a > b ? a : b",
            "round(a, 2) + percent(a, b)",
            "min(a, b) * max(a, b) % 7",
            "a && b || !c",
        )
        val nasty = listOf(
            "", " ", "0", "-0", "1e300", "-1e300", "abc",
            "999999999999999999999", "--", "0.0001", "-1", "是", "\u0000",
        )

        for (source in expressions) {
            val expr = Expression.parse(source)
            // 没有字段值
            expr.evaluate(emptyMap())
            // 每个恶心值都跑一遍
            for (raw in nasty) {
                expr.evaluate(mapOf("a" to text(raw), "b" to text(raw), "c" to text(raw)))
            }
            // 极端数字
            expr.evaluate(mapOf("a" to num(Double.MAX_VALUE), "b" to num(0.0), "c" to num(-1.0)))
        }
    }

    /** 渲染也必须是全函数：任何值都能变成一段文字。 */
    @Test
    fun renderingAnyValueProducesText() {
        val values = listOf(
            ExprValue.Num(0.0), ExprValue.Num(-1.5), ExprValue.Num(1e300),
            ExprValue.Num(Double.NaN), ExprValue.Num(Double.POSITIVE_INFINITY),
            ExprValue.Text(""), ExprValue.Text("中文"), ExprValue.Flag(true), ExprValue.Absent,
        )
        for (value in values) {
            // 只要求「不抛异常、返回字符串」。空文本本来就该渲染成空，
            // 那是合法值，不是错误。
            value.render()
        }
    }
}
