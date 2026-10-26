package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 发出去的请求体长什么样。
 *
 * 真发一次请求测不了（要 key、要网、要模型配合），但发出去的字节可以钉住：
 * 纯文本必须仍是字符串，带图必须换成 OpenAI 兼容的 content 数组。
 */
class HttpLlmClientTest {

    private val client = HttpLlmClient(
        LlmConfig(endpoint = "http://localhost/v1/chat/completions", apiKey = "k", model = "m"),
    )

    private fun body(
        messages: List<LlmMessage>,
        stream: Boolean = false,
        jsonMode: Boolean = false,
    ): String = client.requestBody(messages, stream = stream, jsonMode = jsonMode)

    @Test
    fun textOnlyMessagesStayPlainStrings() {
        val json = body(listOf(LlmMessage(LlmRole.User, "你好")))
        assertTrue(json.contains("\"content\":\"你好\""), json)
        assertTrue(!json.contains("image_url"), json)
    }

    @Test
    fun anImageIsSentAsAContentArray() {
        val json = body(
            listOf(LlmMessage(LlmRole.User, "看一眼界面", listOf("data:image/png;base64,AA"))),
        )
        assertTrue(json.contains("\"type\":\"image_url\""), json)
        assertTrue(json.contains("data:image/png;base64,AA"), json)
        assertTrue(json.contains("\"type\":\"text\""), json)
        assertTrue(json.contains("\"text\":\"看一眼界面\""), json)
    }

    @Test
    fun jsonModeIsOptInAndOffForStreaming() {
        assertTrue(!body(listOf(LlmMessage(LlmRole.User, "hi"))).contains("response_format"))
        assertTrue(body(listOf(LlmMessage(LlmRole.User, "hi")), jsonMode = true).contains("response_format"))
        // 流式对话是自由文本，不能开 JSON mode
        assertTrue(!body(listOf(LlmMessage(LlmRole.User, "hi")), stream = true).contains("response_format"))
    }
}
