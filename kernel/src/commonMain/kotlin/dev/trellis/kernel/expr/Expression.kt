package dev.trellis.kernel.expr

/**
 * 一个**全函数**的小表达式。
 *
 * ## 为什么是「全函数」
 *
 * 全函数的意思：**任何输入都能在有限步内结束，不会抛异常，不会卡住。**
 *
 * 做到它只需要三件事：
 *  - 语言里**没有循环、没有递归、没有函数定义**（只有内置的几个纯函数）
 *  - 求值步数和表达式长度成正比（上限 [MAX_LENGTH]）
 *  - 所有错误都退化成 [ExprValue.Absent]，而不是异常
 *
 * 于是最坏情况永远只是「算出一个难看的数字」。这是刻意选的：
 * 这段文本可能由自动生成，能做的事越少，越不需要额外的沙箱。
 *
 * ## 用法
 *
 * 求值分两步，因为取值可能是挂起的：
 * ```
 * val expr = Expression.parse("a * 100 / b")
 * val values = expr.references.associateWith { fetch(it) }   // 调用方去取，可以挂起
 * expr.evaluate(values).render()
 * ```
 */
class Expression private constructor(
    val source: String,
    private val root: Node,
    /** 这个表达式依赖哪些字段路径。调用方据此取数。 */
    val references: List<String>,
) {

    /** 纯函数求值。[values] 里缺的键按 [ExprValue.Absent] 处理。 */
    fun evaluate(values: Map<String, ExprValue>): ExprValue =
        Evaluator(values).eval(root)

    override fun toString(): String = source

    companion object {
        /** 表达式长度上限。够写一段百分比、够写个三元判断，写不下一个程序。 */
        const val MAX_LENGTH = 300

        /**
         * 上限：同一个表达式里允许的比较/算术运算次数。
         * 有了长度上限其实已经隐含了这个，这里只是再兜一层。
         */
        const val MAX_NODES = 200

        /**
         * 解析。失败抛 [ExpressionException]，消息是**写给生成者看的**：
         * 说清楚第几个字符、错在哪，好让它照着改。
         */
        fun parse(source: String): Expression {
            val trimmed = source.trim()
            if (trimmed.isEmpty()) throw ExpressionException("表达式是空的")
            if (trimmed.length > MAX_LENGTH) {
                throw ExpressionException(
                    "表达式有 " + trimmed.length + " 个字符，最多 " + MAX_LENGTH + " 个",
                )
            }
            val parser = Parser(trimmed)
            val root = parser.parseAll()
            if (parser.nodeCount > MAX_NODES) {
                throw ExpressionException("表达式太复杂（超过 " + MAX_NODES + " 个运算），拆成两个字段试试")
            }
            return Expression(trimmed, root, parser.references.toList())
        }
    }
}

class ExpressionException(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// 语法树
// ---------------------------------------------------------------------------

internal sealed interface Node

internal data class LiteralNode(val value: ExprValue) : Node

internal data class RefNode(val path: String) : Node

internal data class UnaryNode(val op: String, val operand: Node) : Node

internal data class BinaryNode(val op: String, val left: Node, val right: Node) : Node

internal data class ConditionalNode(val condition: Node, val yes: Node, val no: Node) : Node

internal data class CallNode(val name: String, val args: List<Node>) : Node

// ---------------------------------------------------------------------------
// 求值
// ---------------------------------------------------------------------------

/**
 * 求值器。规则很短：
 *  - 任何一步拿到 [ExprValue.Absent]，结果就是它（一路传下去）
 *  - 除法/取余的除数是 0 -> Absent
 *  - 结果数字超出范围 -> Absent（不让它变成 Infinity 再显示成奇怪的东西）
 */
internal class Evaluator(private val values: Map<String, ExprValue>) {

    fun eval(node: Node): ExprValue = when (node) {
        is LiteralNode -> node.value
        is RefNode -> values[node.path] ?: ExprValue.Absent
        is UnaryNode -> evalUnary(node)
        is BinaryNode -> evalBinary(node)
        is ConditionalNode -> evalConditional(node)
        is CallNode -> evalCall(node)
    }

    private fun evalUnary(node: UnaryNode): ExprValue {
        val v = eval(node.operand)
        if (v is ExprValue.Absent) return v
        return when (node.op) {
            "-" -> numberOrAbsent(v)?.let { ExprValue.Num(-it) } ?: ExprValue.Absent
            "!" -> ExprValue.Flag(!truthy(v))
            else -> ExprValue.Absent
        }
    }

    private fun evalBinary(node: BinaryNode): ExprValue {
        val left = eval(node.left)

        // 短路：&& 和 || 在左值就能定的时候不去碰右值
        if (node.op == "&&") {
            if (left is ExprValue.Absent) return left
            if (!truthy(left)) return ExprValue.Flag(false)
            val right = eval(node.right)
            if (right is ExprValue.Absent) return right
            return ExprValue.Flag(truthy(right))
        }
        if (node.op == "||") {
            if (left is ExprValue.Absent) return left
            if (truthy(left)) return ExprValue.Flag(true)
            val right = eval(node.right)
            if (right is ExprValue.Absent) return right
            return ExprValue.Flag(truthy(right))
        }

        val right = eval(node.right)
        if (left is ExprValue.Absent) return left
        if (right is ExprValue.Absent) return right

        return when (node.op) {
            "+" -> arithmetic(left, right) { a, b -> a + b }
            "-" -> arithmetic(left, right) { a, b -> a - b }
            "*" -> arithmetic(left, right) { a, b -> a * b }
            "/" -> arithmetic(left, right) { a, b -> if (b == 0.0) null else a / b }
            "%" -> arithmetic(left, right) { a, b -> if (b == 0.0) null else a % b }
            "==" -> ExprValue.Flag(equalTo(left, right))
            "!=" -> ExprValue.Flag(!equalTo(left, right))
            "<" -> compare(left, right) { it < 0 }
            "<=" -> compare(left, right) { it <= 0 }
            ">" -> compare(left, right) { it > 0 }
            ">=" -> compare(left, right) { it >= 0 }
            else -> ExprValue.Absent
        }
    }

    private fun evalConditional(node: ConditionalNode): ExprValue {
        val condition = eval(node.condition)
        if (condition is ExprValue.Absent) return condition
        return eval(if (truthy(condition)) node.yes else node.no)
    }

    private fun evalCall(node: CallNode): ExprValue {
        val args = node.args.map { eval(it) }
        if (args.any { it is ExprValue.Absent }) return ExprValue.Absent
        val numbers = args.map { numberOrAbsent(it) }

        return when (node.name) {
            "abs" -> single(numbers) { kotlin.math.abs(it) }
            "floor" -> single(numbers) { kotlin.math.floor(it) }
            "ceil" -> single(numbers) { kotlin.math.ceil(it) }
            "round" -> when (args.size) {
                1 -> single(numbers) { roundHalfUp(it) }
                2 -> {
                    val digits = numbers.getOrNull(1)?.toInt() ?: return ExprValue.Absent
                    val factor = Math.pow10(digits)
                    val x = numbers[0] ?: return ExprValue.Absent
                    number(ExprValue.Num(roundHalfUp(x * factor) / factor))
                }
                else -> ExprValue.Absent
            }
            "min" -> numbers.filterNotNull().takeIf { it.isNotEmpty() }
                ?.minOrNull()?.let { ExprValue.Num(it) } ?: ExprValue.Absent
            "max" -> numbers.filterNotNull().takeIf { it.isNotEmpty() }
                ?.maxOrNull()?.let { ExprValue.Num(it) } ?: ExprValue.Absent
            // percent(a, b) = a 占 b 的百分比。最常见的需求，给个糖。
            "percent" -> {
                val a = numbers.getOrNull(0) ?: return ExprValue.Absent
                val b = numbers.getOrNull(1) ?: return ExprValue.Absent
                if (b == 0.0) ExprValue.Absent else number(ExprValue.Num(a * 100.0 / b))
            }
            // 字符串判断。规则的条件要靠它们挡住「自己触发自己」的连环。
            "startsWith" -> textTest(args) { a, b -> a.startsWith(b) }
            "endsWith" -> textTest(args) { a, b -> a.endsWith(b) }
            "contains" -> textTest(args) { a, b -> a.contains(b) }
            else -> ExprValue.Absent
        }
    }

    private inline fun textTest(
        args: List<ExprValue>,
        test: (String, String) -> Boolean,
    ): ExprValue {
        val a = (args.getOrNull(0) as? ExprValue.Text)?.value ?: return ExprValue.Absent
        val b = (args.getOrNull(1) as? ExprValue.Text)?.value ?: return ExprValue.Absent
        return ExprValue.Flag(test(a, b))
    }

    private inline fun single(numbers: List<Double?>, compute: (Double) -> Double): ExprValue {
        val x = numbers.getOrNull(0) ?: return ExprValue.Absent
        return number(ExprValue.Num(compute(x)))
    }

    private fun arithmetic(left: ExprValue, right: ExprValue, compute: (Double, Double) -> Double?): ExprValue {
        val a = numberOrAbsent(left) ?: return ExprValue.Absent
        val b = numberOrAbsent(right) ?: return ExprValue.Absent
        val result = compute(a, b) ?: return ExprValue.Absent
        return number(ExprValue.Num(result))
    }

    private inline fun compare(left: ExprValue, right: ExprValue, test: (Int) -> Boolean): ExprValue {
        val a = numberOrAbsent(left)
        val b = numberOrAbsent(right)
        return if (a != null && b != null) {
            ExprValue.Flag(test(a.compareTo(b)))
        } else {
            val sa = (left as? ExprValue.Text)?.value ?: return ExprValue.Absent
            val sb = (right as? ExprValue.Text)?.value ?: return ExprValue.Absent
            ExprValue.Flag(test(sa.compareTo(sb)))
        }
    }
}

private fun number(value: ExprValue.Num): ExprValue =
    if (value.value.isFinite() && kotlin.math.abs(value.value) < 1e15) value else ExprValue.Absent

private fun math10(digits: Int): Double = Math.pow10(digits)

private object Math {
    fun pow10(digits: Int): Double {
        var result = 1.0
        repeat(kotlin.math.abs(digits).coerceAtMost(6)) {
            result *= if (digits >= 0) 10.0 else 0.1
        }
        return result
    }
}

/** 数字：文本能解析成数字也算。解析不了 -> null。 */
/**
 * 四舍五入。
 *
 * 刻意**不用** kotlin.math.round：那个是银行家舍入，2.5 会变成 2。
 * 用户算完成率看到 2.5 被舍成 2，只会觉得算错了。
 */
private fun roundHalfUp(x: Double): Double =
    if (x >= 0) kotlin.math.floor(x + 0.5) else kotlin.math.ceil(x - 0.5)

private fun numberOrAbsent(value: ExprValue): Double? = when (value) {
    is ExprValue.Num -> value.value
    is ExprValue.Text -> value.value.trim().toDoubleOrNull()
    is ExprValue.Flag -> if (value.value) 1.0 else 0.0
    ExprValue.Absent -> null
}

private fun truthy(value: ExprValue): Boolean = value.isTrue()

private fun equalTo(left: ExprValue, right: ExprValue): Boolean {
    val a = numberOrAbsent(left)
    val b = numberOrAbsent(right)
    if (a != null && b != null) return a == b
    return left.render() == right.render()
}
