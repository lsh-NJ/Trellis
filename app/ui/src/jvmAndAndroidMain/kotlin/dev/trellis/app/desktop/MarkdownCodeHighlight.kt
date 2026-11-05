package dev.trellis.app.desktop

import androidx.compose.ui.graphics.Color

/**
 * 代码块的轻量语法高亮。
 *
 * 不引第三方高亮库：构建要能离线跑、Android 端也要共享同一份实现，而高亮只是「读起来
 * 顺眼」的辅助，不需要完整 parser。这里按围栏的语言写一个逐行分词器：
 *
 * - 只认常见语言；认不出就按纯文本（不着色），不会报错；
 * - 支持跨行的块注释、Python/Kotlin 的三引号、Go 的反引号原始字符串；
 * - token 分五类：关键字 / 字符串 / 注释 / 数字 / 元信息（预处理指令、注解、变量、键名）。
 *
 * 颜色不在这里定：编辑器按「深色/浅色」各取一套 GitHub 配色。
 */
internal enum class CodeLanguage(val label: String) {
    Cpp("C++"),
    C("C"),
    Kotlin("Kotlin"),
    Java("Java"),
    JavaScript("JavaScript"),
    TypeScript("TypeScript"),
    Python("Python"),
    Json("JSON"),
    Yaml("YAML"),
    Bash("Shell"),
    Sql("SQL"),
    Html("HTML"),
    Xml("XML"),
    Css("CSS"),
    Markdown("Markdown"),
    Rust("Rust"),
    Go("Go"),
    Plain(""),
}

internal enum class CodeTokenKind { Keyword, String, Comment, Number, Meta }

/** 一行的着色区间；[start]..[end] 是行内相对坐标（开区间）。 */
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

/**
 * 跨行状态：块注释没闭合、三引号没闭合。
 *
 * 逐行喂给 [highlightCodeLine] 时要带上上一行返回的 state。
 */
internal data class CodeHighlightState(
    val inBlockComment: Boolean = false,
    val inTripleQuote: Char = '\u0000',
) {
    companion object {
        val Empty = CodeHighlightState()
    }
}

internal data class CodeLineHighlight(
    val tokens: List<CodeToken>,
    val state: CodeHighlightState,
)

/** 围栏 info string → 语言；`cpp`、`{.cpp}`、`cpp title=...` 都认。 */
internal fun codeLanguageOf(info: String): CodeLanguage {
    val rawInfo = info.trim()
    val key = if (rawInfo.startsWith("{")) {
        // Pandoc 风格的 {...}：`{.cpp .numberLines}` 取第一个类名。
        rawInfo.removePrefix("{").removeSuffix("}").trim()
            .substringBefore(' ').removePrefix(".").lowercase()
    } else {
        rawInfo.substringBefore(' ').lowercase()
    }
    return when (key) {
        "cpp", "c++", "cc", "cxx", "hpp", "hxx" -> CodeLanguage.Cpp
        "c", "h" -> CodeLanguage.C
        "kotlin", "kt", "kts" -> CodeLanguage.Kotlin
        "java" -> CodeLanguage.Java
        "js", "javascript", "jsx", "mjs", "cjs" -> CodeLanguage.JavaScript
        "ts", "typescript", "tsx" -> CodeLanguage.TypeScript
        "py", "python", "python3" -> CodeLanguage.Python
        "json", "jsonc", "json5" -> CodeLanguage.Json
        "yaml", "yml" -> CodeLanguage.Yaml
        "sh", "bash", "shell", "zsh", "console" -> CodeLanguage.Bash
        "sql" -> CodeLanguage.Sql
        "html", "htm" -> CodeLanguage.Html
        "xml", "svg", "plist" -> CodeLanguage.Xml
        "css", "scss", "less" -> CodeLanguage.Css
        "md", "markdown" -> CodeLanguage.Markdown
        "rust", "rs" -> CodeLanguage.Rust
        "go", "golang" -> CodeLanguage.Go
        else -> CodeLanguage.Plain
    }
}

/** 给一行代码分词；[state] 是上一行留下的跨行状态。 */
internal fun highlightCodeLine(
    line: String,
    language: CodeLanguage,
    state: CodeHighlightState = CodeHighlightState.Empty,
): CodeLineHighlight {
    val spec = languageSpec(language) ?: return CodeLineHighlight(emptyList(), CodeHighlightState.Empty)
    val tokens = mutableListOf<CodeToken>()
    var index = 0
    var inBlock = state.inBlockComment
    var triple = state.inTripleQuote

    fun emit(start: Int, end: Int, kind: CodeTokenKind) {
        if (end > start) tokens += CodeToken(start, end, kind)
    }

    while (index < line.length) {
        // 1) 上一条没结束的块注释/三引号先把本行吃完。
        val blockEnd = spec.blockCommentEnd
        if (inBlock && blockEnd != null) {
            val close = line.indexOf(blockEnd, index)
            if (close < 0) {
                emit(index, line.length, CodeTokenKind.Comment)
                index = line.length
                break
            }
            val stop = close + blockEnd.length
            emit(index, stop, CodeTokenKind.Comment)
            index = stop
            inBlock = false
            continue
        }
        if (triple != '\u0000') {
            val marker = "$triple$triple$triple"
            val close = line.indexOf(marker, index)
            if (close < 0) {
                emit(index, line.length, CodeTokenKind.String)
                index = line.length
                break
            }
            val stop = close + marker.length
            emit(index, stop, CodeTokenKind.String)
            index = stop
            triple = '\u0000'
            continue
        }

        val char = line[index]
        // 2) 行首元信息：C 的 #include、Rust 的 #[derive]、YAML 的 ---。
        val metaLine = spec.metaLine
        if (metaLine != null && line.indexOfFirst { !it.isWhitespace() } == index) {
            val match = metaLine.find(line, index)
            if (match != null && match.range.first == index) {
                emit(index, match.range.last + 1, CodeTokenKind.Meta)
                index = match.range.last + 1
                continue
            }
        }
        // 3) 行注释。
        val lineComment = spec.lineComments.firstOrNull { line.startsWith(it, index) }
        if (lineComment != null) {
            emit(index, line.length, CodeTokenKind.Comment)
            index = line.length
            break
        }
        // 4) 块注释开头。
        val blockStart = spec.blockCommentStart
        if (blockStart != null && blockEnd != null && line.startsWith(blockStart, index)) {
            val close = line.indexOf(blockEnd, index + blockStart.length)
            if (close < 0) {
                emit(index, line.length, CodeTokenKind.Comment)
                index = line.length
                inBlock = true
                break
            }
            val stop = close + blockEnd.length
            emit(index, stop, CodeTokenKind.Comment)
            index = stop
            continue
        }
        // 5) 三引号 / 原始字符串。
        if (char in spec.tripleQuotes && line.startsWith("$char$char$char", index)) {
            val marker = "$char$char$char"
            val close = line.indexOf(marker, index + marker.length)
            if (close < 0) {
                emit(index, line.length, CodeTokenKind.String)
                index = line.length
                triple = char
                break
            }
            val stop = close + marker.length
            emit(index, stop, CodeTokenKind.String)
            index = stop
            continue
        }
        if (spec.rawQuote != null && char == spec.rawQuote) {
            val close = line.indexOf(char, index + 1)
            val stop = if (close < 0) line.length else close + 1
            emit(index, stop, CodeTokenKind.String)
            index = stop
            continue
        }
        // 6) 普通字符串（处理反斜杠转义）。
        if (char in spec.quotes) {
            var cursor = index + 1
            while (cursor < line.length) {
                val current = line[cursor]
                if (spec.escapes && current == '\\') {
                    cursor += 2
                    continue
                }
                cursor += 1
                if (current == char) break
            }
            val stop = cursor.coerceAtMost(line.length)
            emit(index, stop, CodeTokenKind.String)
            index = stop
            continue
        }
        // 7) shell 变量 $NAME / ${NAME}。
        if (spec.variables != null && char == spec.variables) {
            var cursor = index + 1
            if (cursor < line.length && (line[cursor].isLetter() || line[cursor] == '_')) {
                while (cursor < line.length && (line[cursor].isLetterOrDigit() || line[cursor] == '_')) cursor += 1
                emit(index, cursor, CodeTokenKind.Meta)
                index = cursor
                continue
            }
            if (cursor < line.length && line[cursor] == '{') {
                val close = line.indexOf('}', cursor)
                val stop = if (close < 0) line.length else close + 1
                emit(index, stop, CodeTokenKind.Meta)
                index = stop
                continue
            }
        }
        // 8) 注解 @Override / #[derive] 以外的 # 已按行注释处理。
        if (spec.annotation != null && char == spec.annotation) {
            var cursor = index + 1
            while (cursor < line.length && (line[cursor].isLetterOrDigit() || line[cursor] == '_')) cursor += 1
            if (cursor > index + 1) {
                emit(index, cursor, CodeTokenKind.Meta)
                index = cursor
                continue
            }
        }
        // 9) 数字。
        if (spec.numbers && (char.isDigit() || (char == '.' && index + 1 < line.length && line[index + 1].isDigit()))) {
            var cursor = index
            if (char == '0' && index + 1 < line.length && (line[index + 1] == 'x' || line[index + 1] == 'X' ||
                    line[index + 1] == 'b' || line[index + 1] == 'o')
            ) {
                cursor = index + 2
                while (cursor < line.length && (line[cursor].isLetterOrDigit() || line[cursor] == '_')) cursor += 1
            } else {
                while (cursor < line.length && (line[cursor].isDigit() || line[cursor] == '_')) cursor += 1
                if (cursor < line.length && line[cursor] == '.') {
                    cursor += 1
                    while (cursor < line.length && (line[cursor].isDigit() || line[cursor] == '_')) cursor += 1
                }
                if (cursor < line.length && (line[cursor] == 'e' || line[cursor] == 'E')) {
                    var exponent = cursor + 1
                    if (exponent < line.length && (line[exponent] == '+' || line[exponent] == '-')) exponent += 1
                    if (exponent < line.length && line[exponent].isDigit()) {
                        cursor = exponent
                        while (cursor < line.length && line[cursor].isDigit()) cursor += 1
                    }
                }
                while (cursor < line.length && (line[cursor] == 'f' || line[cursor] == 'F' || line[cursor] == 'l' ||
                        line[cursor] == 'L' || line[cursor] == 'u' || line[cursor] == 'U')
                ) {
                    cursor += 1
                }
            }
            emit(index, cursor, CodeTokenKind.Number)
            index = cursor
            continue
        }
        // 10) 标识符：关键字、YAML/JSON 键名、CSS 属性名。
        if (char.isLetter() || char == '_' || char == '$') {
            var cursor = index
            while (cursor < line.length && (line[cursor].isLetterOrDigit() || line[cursor] == '_' || line[cursor] == '$')) {
                cursor += 1
            }
            val word = line.substring(index, cursor)
            val lookup = if (spec.caseInsensitiveKeywords) word.lowercase() else word
            if (lookup in spec.keywords) {
                emit(index, cursor, CodeTokenKind.Keyword)
            } else if (spec.keyNames) {
                var probe = cursor
                while (probe < line.length && (line[probe] == ' ' || line[probe] == '\t')) probe += 1
                if (probe < line.length && line[probe] == ':') {
                    val prefixBlank = line.substring(0, index).isBlank() || spec.cssKeys
                    if (prefixBlank) emit(index, cursor, if (spec.cssKeys) CodeTokenKind.Keyword else CodeTokenKind.Meta)
                }
            }
            index = cursor
            continue
        }
        index += 1
    }
    return CodeLineHighlight(tokens, CodeHighlightState(inBlock, triple))
}

private class LanguageSpec(
    val lineComments: List<String> = emptyList(),
    val blockCommentStart: String? = null,
    val blockCommentEnd: String? = null,
    val quotes: Set<Char> = setOf('"'),
    val tripleQuotes: Set<Char> = emptySet(),
    val rawQuote: Char? = null,
    val escapes: Boolean = true,
    val variables: Char? = null,
    val annotation: Char? = null,
    val keywords: Set<String> = emptySet(),
    val caseInsensitiveKeywords: Boolean = false,
    val metaLine: Regex? = null,
    val keyNames: Boolean = false,
    val cssKeys: Boolean = false,
    /** 有些语言里「数字」太常见且没有语义（YAML/HTML 的普通文本），关掉避免乱上色。 */
    val numbers: Boolean = true,
)

private val cLikeKeywords = setOf(
    "auto", "break", "case", "catch", "char", "class", "const", "constexpr", "continue", "default", "delete",
    "do", "double", "else", "enum", "explicit", "export", "extern", "false", "final", "float", "for", "friend",
    "goto", "if", "inline", "int", "long", "mutable", "namespace", "new", "noexcept", "nullptr", "operator",
    "override", "private", "protected", "public", "register", "return", "short", "signed", "sizeof", "static",
    "struct", "switch", "template", "this", "throw", "true", "try", "typedef", "typename", "union", "unsigned",
    "using", "virtual", "void", "volatile", "while",
)

private val javaKeywords = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
    "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
    "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "null", "package",
    "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized",
    "this", "throw", "throws", "transient", "true", "false", "try", "void", "volatile", "while", "var", "record",
)

private val kotlinKeywords = javaKeywords + setOf(
    "fun", "val", "var", "when", "is", "in", "as", "object", "companion", "sealed", "data", "suspend", "lateinit",
    "internal", "open", "override", "typealias", "infix", "operator", "reified", "inline", "crossinline",
    "noinline", "init", "constructor", "field", "it", "by", "where", "actual", "expect", "value", "context",
)

private val jsKeywords = setOf(
    "async", "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
    "do", "else", "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof",
    "let", "new", "null", "of", "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof",
    "undefined", "var", "void", "while", "with", "yield", "interface", "type", "enum", "implements", "private",
    "protected", "public", "readonly", "declare", "namespace", "as", "satisfies",
)

private val pythonKeywords = setOf(
    "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
    "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
    "or", "pass", "raise", "return", "True", "try", "while", "with", "yield", "match", "case", "self",
)

private val rustKeywords = setOf(
    "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern", "false",
    "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref", "return",
    "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe", "use", "where", "while",
    "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32", "u64", "u128", "usize", "f32", "f64",
    "bool", "char", "str", "String", "Option", "Result", "Some", "None", "Ok", "Err", "Vec", "Box",
)

private val goKeywords = setOf(
    "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", "for", "func",
    "go", "goto", "if", "import", "interface", "map", "package", "range", "return", "select", "struct", "switch",
    "type", "var", "nil", "true", "false", "iota", "string", "int", "int8", "int16", "int32", "int64", "uint",
    "uint8", "uint16", "uint32", "uint64", "float32", "float64", "bool", "byte", "rune", "error", "any",
)

private val bashKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function",
    "select", "time", "return", "exit", "echo", "printf", "export", "local", "readonly", "declare", "source",
    "cd", "set", "unset", "shift", "trap", "wait", "exec", "eval", "test", "true", "false",
)

private val sqlKeywords = setOf(
    "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table", "alter",
    "drop", "index", "view", "join", "left", "right", "inner", "outer", "on", "group", "order", "by", "having",
    "limit", "offset", "distinct", "as", "and", "or", "not", "null", "is", "in", "exists", "between", "like",
    "case", "when", "then", "else", "end", "union", "all", "primary", "key", "foreign", "references", "default",
    "integer", "text", "varchar", "boolean", "timestamp", "count", "sum", "avg", "min", "max",
)

private val jsonKeywords = setOf("true", "false", "null")

private val cssKeywords = setOf(
    "important", "media", "supports", "keyframes", "font-face", "import", "charset", "mixin", "include", "extend",
    "var", "calc", "rgb", "rgba", "hsl", "hsla", "url",
)

private val cPreprocessor = Regex("""^\s*#\s*[A-Za-z_]+.*$""")
private val rustAttribute = Regex("""^\s*#!?\[[^\]]*]""")
private val yamlDocument = Regex("""^\s*(-{3}|\.{3})\s*$""")

private fun languageSpec(language: CodeLanguage): LanguageSpec? = when (language) {
    CodeLanguage.Cpp -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        keywords = cLikeKeywords,
        metaLine = cPreprocessor,
    )

    CodeLanguage.C -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        keywords = cLikeKeywords - setOf("class", "namespace", "template", "new", "delete", "nullptr", "operator",
            "explicit", "override", "final", "using", "typename"),
        metaLine = cPreprocessor,
    )

    CodeLanguage.Kotlin -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        tripleQuotes = setOf('"'),
        annotation = '@',
        keywords = kotlinKeywords,
    )

    CodeLanguage.Java -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        tripleQuotes = setOf('"'),
        annotation = '@',
        keywords = javaKeywords,
    )

    CodeLanguage.JavaScript, CodeLanguage.TypeScript -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        rawQuote = '`',
        keywords = jsKeywords,
    )

    CodeLanguage.Python -> LanguageSpec(
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
        tripleQuotes = setOf('"', '\''),
        keywords = pythonKeywords,
    )

    CodeLanguage.Json -> LanguageSpec(
        quotes = setOf('"'),
        keywords = jsonKeywords,
        keyNames = true,
    )

    CodeLanguage.Yaml -> LanguageSpec(
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
        keywords = jsonKeywords,
        keyNames = true,
        metaLine = yamlDocument,
        numbers = false,
    )

    CodeLanguage.Bash -> LanguageSpec(
        lineComments = listOf("#"),
        quotes = setOf('"', '\''),
        rawQuote = null,
        variables = '$',
        keywords = bashKeywords,
        metaLine = Regex("""^\s*#!.*$"""),
    )

    CodeLanguage.Sql -> LanguageSpec(
        lineComments = listOf("--"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('\'', '"'),
        keywords = sqlKeywords,
        caseInsensitiveKeywords = true,
    )

    CodeLanguage.Html, CodeLanguage.Xml -> LanguageSpec(
        blockCommentStart = "<!--",
        blockCommentEnd = "-->",
        quotes = setOf('"', '\''),
        keywords = emptySet(),
        metaLine = Regex("""^\s*<[!?][^>]*>"""),
        numbers = false,
    )

    CodeLanguage.Css -> LanguageSpec(
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        keywords = cssKeywords,
        metaLine = Regex("""^\s*@[A-Za-z-]+.*$"""),
        keyNames = true,
        cssKeys = true,
    )

    CodeLanguage.Markdown -> null
    CodeLanguage.Rust -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"'),
        rawQuote = null,
        keywords = rustKeywords,
        metaLine = rustAttribute,
    )

    CodeLanguage.Go -> LanguageSpec(
        lineComments = listOf("//"),
        blockCommentStart = "/*",
        blockCommentEnd = "*/",
        quotes = setOf('"', '\''),
        rawQuote = '`',
        keywords = goKeywords,
    )

    CodeLanguage.Plain -> null
}

/** GitHub 配色（关键字/字符串/注释/数字/元信息）。深色主题另一套。 */
internal data class CodeSyntaxColors(
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val meta: Color,
)

internal fun codeSyntaxColors(dark: Boolean): CodeSyntaxColors = if (dark) {
    CodeSyntaxColors(
        keyword = Color(0xFFFF7B72),
        string = Color(0xFFA5D6FF),
        comment = Color(0xFF8B949E),
        number = Color(0xFF79C0FF),
        meta = Color(0xFFD2A8FF),
    )
} else {
    CodeSyntaxColors(
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        comment = Color(0xFF6E7781),
        number = Color(0xFF0550AE),
        meta = Color(0xFF8250DF),
    )
}

internal fun CodeSyntaxColors.of(kind: CodeTokenKind): Color = when (kind) {
    CodeTokenKind.Keyword -> keyword
    CodeTokenKind.String -> string
    CodeTokenKind.Comment -> comment
    CodeTokenKind.Number -> number
    CodeTokenKind.Meta -> meta
}
