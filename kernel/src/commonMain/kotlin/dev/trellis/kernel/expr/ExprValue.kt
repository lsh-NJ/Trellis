package dev.trellis.kernel.expr

/**
 * 表达式能算出来的东西。只有三种，外加一个「没有值」。
 *
 * 刻意**没有**列表、没有对象、没有日期类型 ——
 * 每多一种类型，就得给每一种都定义运算规则，组合爆炸。
 * 需要更多类型时再说，现在这三种够用了。
 */
sealed interface ExprValue {

    data class Num(val value: Double) : ExprValue

    data class Text(val value: String) : ExprValue

    data class Flag(val value: Boolean) : ExprValue

    /**
     * 没有值。
     *
     * 它会在运算里一路传下去：任何一步是它，结果就是它。
     * 最后渲染层把它变成 `--`，和模板里取不到字段时的表现一致。
     */
    data object Absent : ExprValue
}

/** 把值变成能显示的文字。整数不带小数点，小数最多两位并去掉末尾的 0。 */
fun ExprValue.render(): String = when (this) {
    is ExprValue.Num -> renderNumber(value)
    is ExprValue.Text -> value
    is ExprValue.Flag -> if (value) "是" else "否"
    ExprValue.Absent -> "--"
}

private fun renderNumber(value: Double): String {
    if (!value.isFinite()) return "--"
    // 整数就按整数显示，别让用户看见 "3.0"
    if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) {
        return value.toLong().toString()
    }
    val rounded = kotlin.math.round(value * 100) / 100
    return rounded.toString().trimEnd('0').trimEnd('.')
}
