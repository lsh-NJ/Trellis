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
    fun prettyArgumentsIndentsJsonAndKeepsBrokenInputAsIs() {
        val pretty = prettyArguments("""{"week":"2026-W45"}""")
        assertTrue(pretty.contains("\n"), pretty)
        assertTrue(pretty.contains("\"week\""), pretty)
        assertEquals("not json", prettyArguments("not json"))
    }
}
