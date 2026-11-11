package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 编辑器「AI 补全」小浮窗的触发规则：只有光标停在一行行尾的 `。。。` 上才弹。
 *
 * 行号用 0 起的正文行号，和 `findAiFillSlots` / `forEachPlainBodyLine` 同一套；
 * 代码块和 `:::ai` 块里的 `。。。` 不算空位。
 */
class MarkdownAiFillPromptTest {

    @Test
    fun promptOnTheMarkerLineAtTheCaret() {
        val text = "第一行\n\nRAG：。。。"
        assertEquals(2, aiFillPromptLine(text, text.length))
    }

    @Test
    fun caretInsideTheMarkerDoesNotPrompt() {
        val text = "RAG：。。。"
        assertNull(aiFillPromptLine(text, text.length - 1))
        assertNull(aiFillPromptLine(text, 0))
    }

    @Test
    fun bareMarkerLineIsAPrompt() {
        assertEquals(0, aiFillPromptLine("。。。", 3))
        // 行尾空格不影响，用户可能在标记后面留了空格。
        assertEquals(0, aiFillPromptLine("RAG：。。。  ", 9))
    }

    @Test
    fun markerMustBeAtTheLineEnd() {
        assertNull(aiFillPromptLine("RAG：。。。 后面还有字", 13))
        assertNull(aiFillPromptLine("RAG：", 4))
        assertNull(aiFillPromptLine("", 0))
    }

    @Test
    fun caretOnAnotherLineDoesNotPrompt() {
        val text = "。。。\n下一行"
        assertNull(aiFillPromptLine(text, text.length))
        assertEquals(0, aiFillPromptLine(text, 3))
    }

    @Test
    fun codeFenceAndAiBlockAreNotPrompts() {
        val code = "```\nRAG：。。。\n```"
        assertNull(aiFillPromptLine(code, code.indexOf("。。。") + 3))

        val ai = ":::ai\nRAG：。。。\n:::"
        assertNull(aiFillPromptLine(ai, ai.indexOf("。。。") + 3))
    }

    @Test
    fun carriageReturnAtLineEndStillDetectsTheMarker() {
        val text = "RAG：。。。\r\n下一行"
        assertEquals(0, aiFillPromptLine(text, 7))
    }

    @Test
    fun cursorBeyondTheTextIsClamped() {
        val text = "RAG：。。。"
        assertEquals(0, aiFillPromptLine(text, 999))
    }

    @Test
    fun jsonShapedReplyIsUnwrappedBeforeFilling() {
        // 历史事故：模型按 JSON mode 回 {"content": "..."}，整段 JSON 差点被填进正文。
        assertEquals(
            "在检索前后加优化",
            normalizeAiFillText("""{"content": "在检索前后加优化"}"""),
        )
        // 不是 JSON 的正常句子原样保留。
        assertEquals("一句人话。", normalizeAiFillText("一句人话。"))
    }

    @Test
    fun popupLabelReflectsState() {
        assertEquals("AI 补全", aiFillSuggestionText(busy = false, error = null))
        assertEquals("AI 补全中…", aiFillSuggestionText(busy = true, error = null))
        assertEquals(AI_FILL_ERROR_TEXT, aiFillSuggestionText(busy = true, error = AI_FILL_ERROR_TEXT))
    }
}
