package dev.trellis.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * 流式响应断流的兜底。
 *
 * 真实事故：对话页点了「排成学习计划」，计划已经建好了，界面上却一直「运行中」转圈——
 * 最后那次模型调用的 SSE 响应在中途断了，而 `ofLines` 的读循环没有超时，永远等下一行。
 * 这里用本地 SSE 服务模拟「发了一个分片后不再写也不关」，要求它变成一条可读的错误。
 */
class HttpLlmClientStreamTest {

    @Test
    fun aStalledStreamBecomesAnErrorInsteadOfSpinningForever() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n".toByteArray())
            out.flush()
            // 之后故意不写、不关：模拟网络/代理半途断流。
        }
        server.start()
        try {
            val client = HttpLlmClient(
                config = LlmConfig(
                    endpoint = "http://127.0.0.1:" + server.address.port + "/v1/chat/completions",
                    apiKey = "test",
                    model = "test",
                ),
                streamIdleSeconds = 1,
            )
            val deltas = mutableListOf<String>()

            val error = runCatching {
                withTimeout(20_000) {
                    client.stream(listOf(LlmMessage(LlmRole.User, "hi"))) { deltas += it }
                }
            }.exceptionOrNull()

            val llmError = error as? LlmException
            assertTrue(llmError != null, "断流应该抛 LlmException，而不是挂到测试超时：$error")
            assertTrue(
                llmError.message?.contains("没有新内容") == true,
                "错误文案要说清是断流/超时：" + llmError.message,
            )
            assertEquals(listOf("你好"), deltas, "断流前已经吐出来的分片不能丢")
        } finally {
            server.stop(0)
        }
    }

    /**
     * 「停止生成」必须真的能停：读循环阻塞在 InputStream 上，协程取消本身叫不醒它。
     * 旧实现里按钮按下去只是把界面标成停止，后台还挂在 readLine 上，chatBusy 永远不回落。
     */
    @Test
    fun cancellingAStalledStreamStopsImmediately() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            out.write("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n".toByteArray())
            out.flush()
            // 之后一直不写不关，模拟卡住的流。
        }
        server.start()
        try {
            val client = HttpLlmClient(
                config = LlmConfig(
                    endpoint = "http://127.0.0.1:" + server.address.port + "/v1/chat/completions",
                    apiKey = "test",
                    model = "test",
                ),
                // 故意给很长的断流超时：这个用例只验「取消能叫醒它」，不靠看门狗。
                streamIdleSeconds = 60,
            )
            val job = launch {
                runCatching {
                    client.stream(listOf(LlmMessage(LlmRole.User, "hi"))) {}
                }
            }
            // 等它真的进到读循环（收到第一个分片），再按「停止」。
            delay(500)
            withTimeout(5_000) { job.cancelAndJoin() }
        } finally {
            server.stop(0)
        }
    }

    /**
     * 非流式请求也一样：工具循环每步用的是 `complete`，body 半截断掉同样会永远等下去。
     * 真实事故：计划已经建好了，最后那次 `complete` 卡在断流上，界面一直「运行中」。
     */
    @Test
    fun aStalledNonStreamingResponseAlsoBecomesAnError() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, 0)
            val out = exchange.responseBody
            // 只吐半截 JSON 然后不写不关：模拟响应体断流。
            out.write("{\"choices\":[{\"message\":{\"content\":\"半".toByteArray())
            out.flush()
        }
        server.start()
        try {
            val client = HttpLlmClient(
                config = LlmConfig(
                    endpoint = "http://127.0.0.1:" + server.address.port + "/v1/chat/completions",
                    apiKey = "test",
                    model = "test",
                ),
                completeIdleSeconds = 1,
            )
            val error = runCatching {
                withTimeout(20_000) {
                    client.complete(listOf(LlmMessage(LlmRole.User, "hi")))
                }
            }.exceptionOrNull()

            val llmError = error as? LlmException
            assertTrue(llmError != null, "断流应该抛 LlmException，而不是挂到测试超时：$error")
            assertTrue(
                llmError.message?.contains("没有新内容") == true,
                "错误文案要说清是断流/超时：" + llmError.message,
            )
        } finally {
            server.stop(0)
        }
    }
}
