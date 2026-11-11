package dev.trellis.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull

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
    fun deepSeekAsksForThinkingAndLeavesRoomForIt() {
        val deep = HttpLlmClient(
            LlmConfig(
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                apiKey = "k",
                model = "deepseek-chat",
            ),
        )
        val json = deep.requestBody(listOf(LlmMessage(LlmRole.User, "hi")), stream = true, jsonMode = false)
        // DeepSeek 服务端默认不思考：不显式发这个开关，reasoning_content 永远为空。
        assertTrue(json.contains("\"thinking\":{\"type\":\"enabled\"}"), json)
        // 思考 token 也算 completion，2048 会把正文/JSON 挤没。
        assertTrue(json.contains("\"max_tokens\":8192"), json)
    }

    @Test
    fun otherOpenAiCompatibleEndpointsDoNotGetTheDeepSeekOnlyField() {
        val json = body(listOf(LlmMessage(LlmRole.User, "hi")))
        assertTrue(!json.contains("thinking"), json)
    }

    @Test
    fun thinkingCanBeSwitchedOffExplicitly() {
        val off = HttpLlmClient(
            LlmConfig(
                endpoint = "https://api.deepseek.com/v1/chat/completions",
                apiKey = "k",
                model = "deepseek-chat",
                thinking = false,
            ),
        )
        val json = off.requestBody(listOf(LlmMessage(LlmRole.User, "hi")), stream = false, jsonMode = false)
        assertTrue(!json.contains("thinking"), json)
    }

    @Test
    fun jsonModeIsOptInAndOffForStreaming() {
        assertTrue(!body(listOf(LlmMessage(LlmRole.User, "hi"))).contains("response_format"))
        assertTrue(body(listOf(LlmMessage(LlmRole.User, "hi")), jsonMode = true).contains("response_format"))
        // 流式对话是自由文本，不能开 JSON mode
        assertTrue(!body(listOf(LlmMessage(LlmRole.User, "hi")), stream = true).contains("response_format"))
    }

    /**
     * 「只要一句人话」的调用不能带上 JSON mode。
     *
     * 真实事故：编辑器里的 AI 补全调用 `complete`（默认 JSON mode），模型按要求把
     * 那句补全包成了 `{"content": "..."}`，于是整段 JSON 差点被填进 `{{…}}`。
     */
    @Test
    fun completeTextAsksForPlainTextWithoutJsonMode() = runBlocking {
        val captured = AtomicReference("")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            captured.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val reply = """{"choices":[{"message":{"content":"一句人话"}}]}"""
            val bytes = reply.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = HttpLlmClient(
                LlmConfig(
                    endpoint = "http://127.0.0.1:" + server.address.port + "/v1/chat/completions",
                    apiKey = "k",
                    model = "m",
                ),
            )
            val text = client.completeText(listOf(LlmMessage(LlmRole.User, "补一句")))
            assertEquals("一句人话", text)
            val request = captured.get()
            assertTrue(!request.contains("response_format"), "completeText 不能开 JSON mode：" + request)
            assertTrue(request.contains("补一句"), request)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aJsonNullChunkIsNotTheStringNull() {
        // 开思考后，只有 reasoning 的分片 content 是 JSON null。
        // kotlinx 的 JsonNull.content 恰好是字符串 "null"；若按普通 primitive 取值，
        // 每个思考分片都会往正文/思考里塞一个 "null"（界面上就是 nullnullnull…）。
        assertNull(client.flattenText(JsonNull))
    }
}
