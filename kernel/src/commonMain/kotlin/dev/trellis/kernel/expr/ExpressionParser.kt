package dev.trellis.kernel.expr

internal sealed interface Token {
    val pos: Int

    data class Num(val value: Double, override val pos: Int) : Token
    data class Text(val value: String, override val pos: Int) : Token
    data class Ident(val path: String, override val pos: Int) : Token
    data class Sym(val text: String, override val pos: Int) : Token
}

/**
 * 递归下降解析器。
 *
 * 语法（从低到高）：
 * ```
 * 条件     cond := or ('?' cond ':' cond)?
 * 或       or   := and ('||' and)*
 * 与       and  := eq ('&&' eq)*
 * 相等     eq   := cmp (('=='|'!=') cmp)*
 * 比较     cmp  := add (('<'|'<='|'>'|'>=') add)*
 * 加减     add  := mul (('+'|'-') mul)*
 * 乘除     mul  := unary (('*'|'/'|'%') unary)*
 * 一元     unary:= ('-'|'!') unary | primary
 * 原子     primary := 数字 | 文本 | 路径 | 路径 '(' 参数 ')' | '(' cond ')'
 * ```
 *
 * 路径里带点（`summary.todo`）在**词法阶段**就拼成一个标识符，
 * 所以语法树里没有「成员访问」这一层 —— 少一层就少一类错误。
 */
internal class Parser(private val source: String) {

    private val tokens: List<Token> = tokenize(source)
    private var index = 0

    var nodeCount = 0
        private set

    /** 表达式用到的全部字段路径，按出现顺序去重。 */
    val references = LinkedHashSet<String>()

    fun parseAll(): Node {
        if (tokens.isEmpty()) fail("表达式是空的")
        val node = parseConditional()
        if (index < tokens.size) {
            fail("第 " + (tokens[index].pos + 1) + " 个字符开始多余了：" + describe(tokens[index]))
        }
        return node
    }

    // ---- 优先级链 ----

    private fun parseConditional(): Node {
        val condition = parseOr()
        if (!matchSym("?")) return condition
        val yes = parseConditional()
        expectSym(":", "条件表达式里缺冒号。写法是：条件 ? 成立时的值 : 不成立时的值")
        val no = parseConditional()
        return node(ConditionalNode(condition, yes, no))
    }

    private fun parseOr(): Node = parseLeftAssociative(::parseAnd, setOf("||"))

    private fun parseAnd(): Node = parseLeftAssociative(::parseEquality, setOf("&&"))

    private fun parseEquality(): Node = parseLeftAssociative(::parseComparison, setOf("==", "!="))

    private fun parseComparison(): Node = parseLeftAssociative(::parseAdditive, setOf("<", "<=", ">", ">="))

    private fun parseAdditive(): Node = parseLeftAssociative(::parseMultiplicative, setOf("+", "-"))

    private fun parseMultiplicative(): Node = parseLeftAssociative(::parseUnary, setOf("*", "/", "%"))

    private fun parseLeftAssociative(next: () -> Node, ops: Set<String>): Node {
        var left = next()
        while (true) {
            val op = peekSym() ?: return left
            if (op !in ops) return left
            advance()
            val right = next()
            left = node(BinaryNode(op, left, right))
        }
    }

    private fun parseUnary(): Node {
        val op = peekSym()
        if (op == "-" || op == "!") {
            advance()
            return node(UnaryNode(op, parseUnary()))
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Node {
        val token = peek() ?: fail("表达式在这里就结束了，后面少了东西")

        when (token) {
            is Token.Num -> {
                advance()
                return node(LiteralNode(ExprValue.Num(token.value)))
            }

            is Token.Text -> {
                advance()
                return node(LiteralNode(ExprValue.Text(token.value)))
            }

            is Token.Ident -> {
                when (token.path) {
                    "true" -> { advance(); return node(LiteralNode(ExprValue.Flag(true))) }
                    "false" -> { advance(); return node(LiteralNode(ExprValue.Flag(false))) }
                    "null" -> { advance(); return node(LiteralNode(ExprValue.Absent)) }
                }
                advance()
                if (matchSym("(")) {
                    val args = mutableListOf<Node>()
                    if (!matchSym(")")) {
                        do {
                            args += parseConditional()
                        } while (matchSym(","))
                        expectSym(")", "函数的括号没关上")
                    }
                    return node(CallNode(token.path, args))
                }
                references += token.path
                return node(RefNode(token.path))
            }

            is Token.Sym -> {
                if (token.text == "(") {
                    advance()
                    val inner = parseConditional()
                    expectSym(")", "括号没关上")
                    return inner
                }
                fail("第 " + (token.pos + 1) + " 个字符这里不该出现「" + token.text + "」")
            }
        }
    }

    // ---- 词法 ----

    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++

                c.isDigit() -> {
                    val start = i
                    while (i < text.length && text[i].isDigit()) i++
                    if (i < text.length && text[i] == '.') {
                        i++
                        while (i < text.length && text[i].isDigit()) i++
                    }
                    // 科学计数法：1e300、2.5E-3。不支持的话 1e300 会被切成 1 和 e300。
                    if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                        val mark = i
                        i++
                        if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                        if (i < text.length && text[i].isDigit()) {
                            while (i < text.length && text[i].isDigit()) i++
                        } else {
                            i = mark
                        }
                    }
                    val raw = text.substring(start, i)
                    val value = raw.toDoubleOrNull()
                        ?: fail("第 " + (start + 1) + " 个字符起的数字「" + raw + "」看不懂")
                    out += Token.Num(value, start)
                }

                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                    val raw = text.substring(start, i)
                    if (!IDENT_PATTERN.matches(raw)) {
                        fail(
                            "「" + raw + "」这个字段名不合规。只能由字母、数字、下划线组成，" +
                                "多段之间用点分隔，例如 summary.todo",
                        )
                    }
                    out += Token.Ident(raw, start)
                }

                c == '"' || c == '\'' -> {
                    val start = i
                    val quote = c
                    i++
                    val builder = StringBuilder()
                    var closed = false
                    while (i < text.length) {
                        val ch = text[i]
                        if (ch == '\\' && i + 1 < text.length) {
                            builder.append(text[i + 1])
                            i += 2
                            continue
                        }
                        if (ch == quote) {
                            closed = true
                            i++
                            break
                        }
                        builder.append(ch)
                        i++
                    }
                    if (!closed) fail("第 " + (start + 1) + " 个字符起的文本没有引号收尾")
                    out += Token.Text(builder.toString(), start)
                }

                else -> {
                    val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                    if (two in TWO_CHAR_OPS) {
                        out += Token.Sym(two, i)
                        i += 2
                    } else if (c in ONE_CHAR_OPS) {
                        out += Token.Sym(c.toString(), i)
                        i++
                    } else {
                        fail("第 " + (i + 1) + " 个字符不认识：「" + c + "」")
                    }
                }
            }
        }
        return out
    }

    // ---- 小工具 ----

    private fun peek(): Token? = tokens.getOrNull(index)

    private fun advance() {
        index++
    }

    private fun peekSym(): String? = (tokens.getOrNull(index) as? Token.Sym)?.text

    private fun matchSym(text: String): Boolean {
        if (peekSym() == text) {
            advance()
            return true
        }
        return false
    }

    private fun expectSym(text: String, hint: String) {
        if (!matchSym(text)) fail(hint)
    }

    private fun <T : Node> node(value: T): T {
        nodeCount++
        if (nodeCount > Expression.MAX_NODES) {
            fail("表达式太复杂了（超过 " + Expression.MAX_NODES + " 个运算）")
        }
        return value
    }

    private fun describe(token: Token): String = when (token) {
        is Token.Num -> "数字 " + token.value
        is Token.Text -> "文本「" + token.value + "」"
        is Token.Ident -> "名字 " + token.path
        is Token.Sym -> "符号 " + token.text
    }

    private fun fail(message: String): Nothing = throw ExpressionException(message)

    private companion object {
        val IDENT_PATTERN = Regex("""^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$""")
        val TWO_CHAR_OPS = setOf("==", "!=", "<=", ">=", "&&", "||")
        val ONE_CHAR_OPS = setOf('+', '-', '*', '/', '%', '(', ')', ',', '?', ':', '<', '>', '!')
    }
}
