package dev.trellis.net

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 安卓：`HttpURLConnection`。
 *
 * 安卓上没有 `java.net.http`（那是 Java 11 的 API，ART 一直没实现），所以走这个
 * 从 Android 1.0 就在的老接口。它的行为足够用：自动跟随重定向、自动解 gzip。
 */
actual suspend fun httpGetText(
    url: String,
    headers: Map<String, String>,
    timeoutSeconds: Int,
): HttpTextResult = withContext(Dispatchers.IO) {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = timeoutSeconds * 1_000
        instanceFollowRedirects = true
        // 不要自己设 Accept-Encoding：连接对象会加 gzip 并**自动解压**；
        // 手写一个 gzip 进去，拿回来的就是压缩字节，JSON 解析直接炸。
        headers.forEach { (name, value) -> setRequestProperty(name, value) }
    }
    try {
        val status = connection.responseCode
        // 4xx/5xx 的正文在 errorStream 里；不读它，B 站的风控原因就丢了。
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        HttpTextResult(status, body)
    } finally {
        connection.disconnect()
    }
}
