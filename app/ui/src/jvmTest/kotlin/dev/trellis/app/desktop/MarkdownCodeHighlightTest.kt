package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 代码块语法高亮的契约测试。
 *
 * 高亮只是「读起来顺眼」的辅助，不需要完整 parser；这里钉住的是：
 * 常见围栏能认出来、五类 token 的位置和边界不越界、跨行注释/三引号能接上。
 */
class MarkdownCodeHighlightTest {

    private fun kindsOf(line: String, language: CodeLanguage, state: CodeHighlightState = CodeHighlightState.Empty): List<CodeTokenKind> =
        highlightCodeLine(line, language, state).tokens.map { it.kind }

    private fun textOf(line: String, language: CodeLanguage, token: CodeToken): String =
        line.substring(token.start, token.end)

    @Test
    fun recognizesCommonFenceLanguages() {
        assertEquals(CodeLanguage.Cpp, codeLanguageOf("cpp"))
        assertEquals(CodeLanguage.Cpp, codeLanguageOf("C++"))
        assertEquals(CodeLanguage.Kotlin, codeLanguageOf("kt"))
        assertEquals(CodeLanguage.JavaScript, codeLanguageOf("js"))
        assertEquals(CodeLanguage.TypeScript, codeLanguageOf("tsx"))
        assertEquals(CodeLanguage.Python, codeLanguageOf("python3"))
        assertEquals(CodeLanguage.Yaml, codeLanguageOf("yml"))
        assertEquals(CodeLanguage.Bash, codeLanguageOf("shell"))
        assertEquals(CodeLanguage.Rust, codeLanguageOf("rust"))
        assertEquals(CodeLanguage.Go, codeLanguageOf("golang"))
        assertEquals(CodeLanguage.Cpp, codeLanguageOf("{.cpp}"))
        assertEquals(CodeLanguage.Cpp, codeLanguageOf("cpp title=demo"))
        assertEquals(CodeLanguage.Plain, codeLanguageOf(""))
        assertEquals(CodeLanguage.Plain, codeLanguageOf("brainfuck"))
    }

    @Test
    fun highlightsCppKeywordsStringsCommentsAndNumbers() {
        val line = "const char* s = \"hi\"; // note 42"
        val result = highlightCodeLine(line, CodeLanguage.Cpp)
        val kinds = result.tokens.map { it.kind }
        assertTrue(CodeTokenKind.Keyword in kinds)
        assertTrue(CodeTokenKind.String in kinds)
        assertTrue(CodeTokenKind.Comment in kinds)
        val string = result.tokens.first { it.kind == CodeTokenKind.String }
        assertEquals("\"hi\"", textOf(line, CodeLanguage.Cpp, string))
        val comment = result.tokens.first { it.kind == CodeTokenKind.Comment }
        assertEquals("// note 42", textOf(line, CodeLanguage.Cpp, comment))
        val numberLine = "int n = 0x1F;"
        val number = highlightCodeLine(numberLine, CodeLanguage.Cpp).tokens.first { it.kind == CodeTokenKind.Number }
        assertEquals("0x1F", numberLine.substring(number.start, number.end))
    }

    @Test
    fun blockCommentContinuesOnTheNextLine() {
        val first = highlightCodeLine("/* start", CodeLanguage.Cpp)
        assertEquals(CodeTokenKind.Comment, first.tokens.single().kind)
        assertTrue(first.state.inBlockComment)

        val second = highlightCodeLine("still */ int x;", CodeLanguage.Cpp, first.state)
        assertTrue(second.tokens.any { it.kind == CodeTokenKind.Comment })
        assertTrue(second.tokens.any { it.kind == CodeTokenKind.Keyword })
        assertEquals(false, second.state.inBlockComment)
    }

    @Test
    fun pythonTripleQuoteSpansLines() {
        val first = highlightCodeLine("text = \"\"\"abc", CodeLanguage.Python)
        assertEquals(CodeTokenKind.String, first.tokens.single().kind)
        assertEquals('"', first.state.inTripleQuote)

        val second = highlightCodeLine("defg\"\"\"", CodeLanguage.Python, first.state)
        assertEquals(CodeTokenKind.String, second.tokens.single().kind)
        assertEquals('\u0000', second.state.inTripleQuote)
    }

    @Test
    fun yamlKeysAndCommentsAreMeta() {
        val comment = highlightCodeLine("# top", CodeLanguage.Yaml)
        assertEquals(listOf(CodeTokenKind.Comment), comment.tokens.map { it.kind })
        val key = highlightCodeLine("name: value", CodeLanguage.Yaml)
        assertEquals(CodeTokenKind.Meta, key.tokens.first().kind)
        assertEquals("name", "name: value".substring(key.tokens.first().start, key.tokens.first().end))
    }

    @Test
    fun goRawStringKeepsBackticksAsOneString() {
        val line = "x := `raw text`"
        val tokens = highlightCodeLine(line, CodeLanguage.Go).tokens
        val string = tokens.first { it.kind == CodeTokenKind.String }
        assertEquals("`raw text`", line.substring(string.start, string.end))
    }

    @Test
    fun unknownLanguageProducesNoTokens() {
        assertTrue(highlightCodeLine("plain text", CodeLanguage.Plain).tokens.isEmpty())
        assertTrue(highlightCodeLine("plain text", CodeLanguage.Markdown).tokens.isEmpty())
    }

    @Test
    fun githubColorsDifferBetweenLightAndDark() {
        val light = codeSyntaxColors(dark = false)
        val dark = codeSyntaxColors(dark = true)
        assertNotEquals(light.keyword, dark.keyword)
        assertNotEquals(light.string, dark.string)
        assertNotEquals(light.comment, dark.comment)
        assertEquals(light.number, light.of(CodeTokenKind.Number))
    }
}
