package dev.trellis.ai.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型吐出来的「DSML」标记语言：认出来、剥干净。
 *
 * 这些用例钉的是真实事故：模型用它自己那套标记调 `search_bilibili`，
 * 宿主没认出来 —— 气泡里是一屏乱码，工具一个都没跑，用户看到的是「查到了没下文」。
 */
class DeepSeekMarkupTest {

    private val reply = """
        我先查一下有没有合适的课。
        <||DSML||calls><||DSML||invoke name="search_bilibili">
        {"keyword": "操作系统 考研", "order": "热度", "limit": 6}
        </||DSML||parameter></||DSML||invoke></||DSML||calls>
    """.trimIndent()

    @Test
    fun itRecognisesTheDialect() {
        assertTrue(DeepSeekMarkup.contains(reply))
    }

    @Test
    fun itPullsTheFirstInvocationWithItsArguments() {
        val invocation = DeepSeekMarkup.firstInvocation(reply)!!
        assertEquals("search_bilibili", invocation.name)
        val args = Json.parseToJsonElement(invocation.arguments).jsonObject
        assertEquals("操作系统 考研", args["keyword"]!!.jsonPrimitive.content)
        assertEquals(6, args["limit"]!!.jsonPrimitive.int)
    }

    @Test
    fun itStripsTheBlockAndKeepsTheProse() {
        assertEquals("我先查一下有没有合适的课。", DeepSeekMarkup.strip(reply))
    }

    @Test
    fun itDropsADanglingBlockFromAStreamingCut() {
        val half = "先看看\n<||DSML||calls><||DSML||invoke name=\"search_bilibili\">{\"keyword\":\"操作系统\"}"
        assertEquals("先看看", DeepSeekMarkup.strip(half))
    }

    @Test
    fun itLeavesTheNormalJsonProtocolAlone() {
        val plain = """{"tool": "list_events", "arguments": {}}"""
        assertEquals(plain, DeepSeekMarkup.strip(plain))
        assertNull(DeepSeekMarkup.firstInvocation(plain))
    }

    @Test
    fun markupWithoutArgumentsIsAnEmptyObject() {
        val bare = """<||DSML||calls><||DSML||invoke name="list_events"></||DSML||invoke></||DSML||calls>"""
        assertEquals("""{}""", DeepSeekMarkup.firstInvocation(bare)!!.arguments)
    }

    /**
     * 现场样本：模型把竖线之间也加了空格，`< | | DSML | | calls>`。
     *
     * 这条是照着用户截图抄下来的 —— 之前只认紧凑写法，于是这一版标记原样
     * 留在了气泡里，看起来像「markdown 没适配」。
     */
    private val spaced = """
        I'll help you speedrun operating systems. Let me first check what's available.
        < | | DSML | | calls>< | | DSML | | invoke name="search_bilibili">
        {"keyword": "操作系统 考研 速成", "order": "热度", "limit": 6}</ | | DSML | | parameter></ | | DSML | | invoke></ | | DSML | | calls>
    """.trimIndent()

    @Test
    fun itRecognisesTheSpacedVariant() {
        assertTrue(DeepSeekMarkup.contains(spaced))
        assertEquals("search_bilibili", DeepSeekMarkup.firstInvocation(spaced)!!.name)
        assertEquals(
            "I'll help you speedrun operating systems. Let me first check what's available.",
            DeepSeekMarkup.strip(spaced),
        )
    }

    /**
     * 真正存进磁盘的那一版：竖线是**全角**的 `｜`（U+FF5C）。
     *
     * 这段是从 `~/.trellis/chat` 里抄的原文 —— 之前只认 ASCII 竖线，
     * 所以界面上一直露出一屏标记，被当成「markdown 没适配」。
     */
    @Test
    fun itRecognisesTheFullWidthVariant() {
        val real = "I'll help you. Let me check what's available.\n\n" +
            "<｜｜DSML｜｜ calls>\n" +
            "<｜｜DSML｜｜ invoke name=\"search_bilibili\">\n" +
            "{\"keyword\": \"操作系统 考研 速成\", \"order\": \"热度\", \"limit\": 6}\n" +
            "</｜｜DSML｜｜ parameter>\n" +
            "</｜｜DSML｜｜ invoke>\n" +
            "</｜｜DSML｜｜ calls>"
        assertTrue(DeepSeekMarkup.contains(real))
        assertEquals("search_bilibili", DeepSeekMarkup.firstInvocation(real)!!.name)
        assertEquals("I'll help you. Let me check what's available.", DeepSeekMarkup.strip(real))
    }
}
