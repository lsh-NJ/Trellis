package dev.trellis.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** HTTP 传输失败：状态码 + 服务端返回的一小段说明（不含 token/正文）。 */
class SyncHttpException(val status: Int, val detail: String) :
    IOException("HTTP $status：$detail")

/**
 * 把同步对象接口走 HTTP：`GET/PUT /v1/object?path=...` + `GET /v1/list?prefix=...`。
 *
 * 面向「网关 + token」的部署：请求带 `X-Token`（网关也支持 `Authorization: Bearer`，
 * 这里默认用 X-Token；网关会校验并在转发前剥掉）。
 *
 * 用 `HttpURLConnection` 而不是平台分叉：桌面 JVM 和 Android 都有，且只需要
 * GET/PUT（不需要 PROPFIND 之类的自定义方法）。HTTPS 由它自己的 TLS 栈处理，
 * Cloudflare 隧道/正式域名都适用。
 */
class HttpSyncTransport(
    baseUrl: String,
    private val token: String = "",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 60_000,
    /** 用户会话 token，走 `Authorization: Bearer`；服务端用它决定数据归谁。 */
    private val bearerToken: String? = null,
    /** 额外请求头：自测时的自定义头。 */
    private val extraHeaders: Map<String, String> = emptyMap(),
) : SyncTransport {

    private val base: String = baseUrl.trim().trimEnd('/')

    private companion object {
        const val MAX_ATTEMPTS = 4
        val RETRYABLE_STATUS = setOf(429, 502, 503, 504)
    }

    init {
        require(base.startsWith("http://") || base.startsWith("https://")) {
            "同步地址必须以 http:// 或 https:// 开头：$baseUrl"
        }
    }

    override suspend fun get(path: String): ByteArray? = withContext(Dispatchers.IO) {
        withRetry {
            val conn = open("GET", objectUrl(path))
            try {
                when (val code = conn.responseCode) {
                    200 -> conn.inputStream.use { it.readBytes() }
                    404 -> null
                    else -> throw errorOf(conn, code)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun put(path: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        withRetry {
            val conn = open("PUT", objectUrl(path), bytes)
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw errorOf(conn, code)
                conn.inputStream?.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }

    override suspend fun list(prefix: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        withRetry {
            val conn = open("GET", listUrl(prefix))
            try {
                when (val code = conn.responseCode) {
                    200 -> parseFileList(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                    404 -> emptyList()
                    else -> throw errorOf(conn, code)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 网关/隧道偶尔会吐 502/503/504（Cloudflare 回源抖动），同步对象又是幂等的：
     * GET/PUT/list 都值得重试。401/403/404/413 这类确定性错误不重试。
     */
    private suspend fun <T> withRetry(block: () -> T): T {
        var last: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return block()
            } catch (t: Throwable) {
                val retryable = when (t) {
                    is SyncHttpException -> t.status in RETRYABLE_STATUS
                    is java.net.SocketTimeoutException -> true
                    is java.net.ConnectException -> true
                    is IOException -> true
                    else -> false
                }
                if (!retryable || attempt == MAX_ATTEMPTS - 1) {
                    if (t is SyncHttpException && t.status in RETRYABLE_STATUS) {
                        throw SyncHttpException(t.status, "网关/隧道暂时不可用，已重试 ${attempt + 1} 次")
                    }
                    throw t
                }
                last = t
                delay(250L * (1L shl attempt))
            }
        }
        throw last ?: IOException("HTTP 请求失败")
    }

    private fun open(method: String, url: String, body: ByteArray? = null): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.useCaches = false
        if (token.isNotBlank()) conn.setRequestProperty("X-Token", token)
        // 用自定义头而不是 Authorization：网关自己的 trial token 也支持 Authorization Bearer，
        // 两边共用同一个头会互相覆盖/误判。
        if (!bearerToken.isNullOrBlank()) conn.setRequestProperty("X-Trellis-Auth", "Bearer $bearerToken")
        extraHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        return conn
    }

    private fun objectUrl(path: String): String =
        base + "/v1/object?path=" + URLEncoder.encode(path, "UTF-8")

    private fun listUrl(prefix: String): String =
        base + "/v1/list?prefix=" + URLEncoder.encode(prefix, "UTF-8")

    private fun errorOf(conn: HttpURLConnection, code: Int): SyncHttpException {
        val detail = runCatching {
            conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull().orEmpty().trim().take(300)
        return SyncHttpException(code, detail.ifBlank { "服务端没有返回说明" })
    }

    private fun parseFileList(text: String): List<RemoteFile> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> root["files"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L
            RemoteFile(path, size)
        }
    }
}
