package dev.trellis.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 用一个 JDK 自带的小 HTTP 服务模拟「网关后面的同步服务」，验证 URL 编码、
 * 404、token 校验和 LIST 解析。不引入新依赖，也不碰真实网络。
 */
class HttpSyncTransportTest {

    private class FakeServer(private val token: String) {
        val store = linkedMapOf<String, ByteArray>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        fun start(): FakeServer {
            server.createContext("/v1/object") { exchange ->
                if (!authorized(exchange)) return@createContext unauthorized(exchange)
                val path = query(exchange, "path")
                when (exchange.requestMethod) {
                    "GET" -> {
                        val bytes = store[path]
                        if (bytes == null) respond(exchange, 404, "not found".toByteArray())
                        else respond(exchange, 200, bytes)
                    }
                    "PUT" -> {
                        store[path] = exchange.requestBody.use { it.readBytes() }
                        respond(exchange, 204, ByteArray(0))
                    }
                    else -> respond(exchange, 405, "method".toByteArray())
                }
            }
            server.createContext("/v1/list") { exchange ->
                if (!authorized(exchange)) return@createContext unauthorized(exchange)
                val prefix = query(exchange, "prefix")
                val body = buildString {
                    append("[")
                    store.entries.filter { it.key.startsWith(prefix) }.forEachIndexed { index, entry ->
                        if (index > 0) append(",")
                        append("{\"path\":\"").append(entry.key.replace("\"", "\\\"")).append("\",")
                        append("\"size\":").append(entry.value.size).append("}")
                    }
                    append("]")
                }.toByteArray()
                respond(exchange, 200, body)
            }
            server.executor = Executors.newFixedThreadPool(2)
            server.start()
            return this
        }

        fun baseUrl(): String = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)

        private fun authorized(exchange: HttpExchange): Boolean {
            val header = exchange.requestHeaders.getFirst("X-Token")
                ?: exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            return header == token
        }

        private fun unauthorized(exchange: HttpExchange) =
            respond(exchange, 401, "unauthorized".toByteArray())

        private fun query(exchange: HttpExchange, name: String): String {
            val raw = exchange.requestURI.rawQuery.orEmpty()
            return raw.split("&").firstNotNullOfOrNull { pair ->
                val key = pair.substringBefore("=", "")
                if (key == name) URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8") else null
            }.orEmpty()
        }

        private fun respond(exchange: HttpExchange, code: Int, body: ByteArray) {
            exchange.responseHeaders.set("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun putGetListRoundTrip() = runBlocking {
        val fake = FakeServer("secret").start()
        try {
            val transport = HttpSyncTransport(fake.baseUrl(), token = "secret")
            transport.put("heads/notes/dev_a.json", byteArrayOf(1, 2, 3))
            transport.put("blobs/notes/abc", "hello".toByteArray())
            assertTrue(transport.get("heads/notes/dev_a.json")!!.contentEquals(byteArrayOf(1, 2, 3)))
            assertEquals("hello", transport.get("blobs/notes/abc")!!.toString(Charsets.UTF_8))
            assertNull(transport.get("missing"))
            val files = transport.list("heads/notes/")
            assertEquals(listOf("heads/notes/dev_a.json"), files.map { it.path })
            assertEquals(3L, files.first().size)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun wrongTokenIsRejected() = runBlocking {
        val fake = FakeServer("secret").start()
        try {
            val transport = HttpSyncTransport(fake.baseUrl(), token = "wrong")
            val error = assertFailsWith<SyncHttpException> {
                transport.get("anything")
            }
            assertEquals(401, error.status)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun pathWithSpacesAndChineseIsEncoded() = runBlocking {
        val fake = FakeServer("secret").start()
        try {
            val transport = HttpSyncTransport(fake.baseUrl(), token = "secret")
            val key = "plan-docs/pln_1/总览 文档.md"
            transport.put(key, "正文".toByteArray())
            assertEquals("正文", transport.get(key)!!.toString(Charsets.UTF_8))
            assertEquals(key, transport.list("plan-docs/").single().path)
        } finally {
            fake.stop()
        }
    }

    @Test
    fun baseUrlMustBeHttp() {
        assertFailsWith<IllegalArgumentException> { HttpSyncTransport("ftp://example.com") }
    }
}
