package dev.trellis.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 桌面侧的 HTTP 语义：状态码、正文、请求头、非 2xx 也要能读到正文。
 *
 * 安卓侧走的是另一份实现（`HttpURLConnection`），这里测不到 —— 但两边对外承诺
 * 的是同一个 `HttpTextResult`，所以这一组用例同时也是那份实现的验收标准。
 */
class HttpTextTest {

    private lateinit var server: HttpServer
    private var base: String = ""

    @BeforeTest
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { ex -> respond(ex, 200, "你好，世界") }
        server.createContext("/missing") { ex -> respond(ex, 404, "没有这个页面") }
        server.createContext("/echo") { ex ->
            respond(ex, 200, ex.requestHeaders.getFirst("X-Test").orEmpty())
        }
        server.start()
        base = "http://127.0.0.1:" + server.address.port
    }

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `2xx 返回状态码和 UTF-8 正文`() = runTest {
        val result = httpGetText("$base/ok")
        assertTrue(result.ok)
        assertEquals(200, result.status)
        // 中文要原样回来：解码用的是 UTF-8，不是平台默认编码。
        assertEquals("你好，世界", result.body)
    }

    @Test
    fun `非 2xx 也要把正文带回来`() = runTest {
        val result = httpGetText("$base/missing")
        assertFalse(result.ok)
        assertEquals(404, result.status)
        // 风控/鉴权的原因通常就在这段正文里，丢掉它等于让调用方猜。
        assertEquals("没有这个页面", result.body)
    }

    @Test
    fun `请求头原样传给服务端`() = runTest {
        val result = httpGetText("$base/echo", mapOf("X-Test" to "abc"))
        assertEquals("abc", result.body)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
