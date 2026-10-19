package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JsonExtractionTest {

    @Test
    fun takesAPlainObject() {
        assertEquals("""{"a":1}""", JsonExtraction.extractFirstObject("""{"a":1}"""))
    }

    @Test
    fun stripsMarkdownFences() {
        val raw = "好的，这是插件：\n```json\n{\"id\":\"ai.x\"}\n```\n它会在首页显示。"
        assertEquals("""{"id":"ai.x"}""", JsonExtraction.extractFirstObject(raw))
    }

    @Test
    fun takesOnlyTheFirstObject() {
        val raw = """{"a":1} 然后还有 {"b":2}"""
        assertEquals("""{"a":1}""", JsonExtraction.extractFirstObject(raw))
    }

    /** 正文里写了个 `{` 不能把它带偏 —— 这在实际生成里很常见。 */
    @Test
    fun bracesInsideStringsDoNotConfuseIt() {
        val raw = """{"body":"用 `{x}` 表示占位"}"""
        assertEquals(raw, JsonExtraction.extractFirstObject(raw))
    }

    @Test
    fun escapedQuotesInsideStringsAreHandled() {
        val raw = """{"body":"他说\"你好\""}"""
        assertEquals(raw, JsonExtraction.extractFirstObject(raw))
    }

    @Test
    fun nestedObjectsAreBalanced() {
        val raw = """{"slots":[{"point":"a","title":"b"}]}"""
        assertEquals(raw, JsonExtraction.extractFirstObject(raw))
    }

    @Test
    fun returnsNullWhenThereIsNoObject() {
        assertNull(JsonExtraction.extractFirstObject("抱歉，我做不到。"))
        assertNull(JsonExtraction.extractFirstObject("""{"没有闭合"""))
    }
}
