package dev.trellis.net

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 桌面：一个共享的 `HttpClient`。它内部自带连接池，每次新建反而更慢。 */
private val sharedClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

actual suspend fun httpGetText(
    url: String,
    headers: Map<String, String>,
    timeoutSeconds: Int,
): HttpTextResult = withContext(Dispatchers.IO) {
    val request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .GET()
        .build()
    val response = sharedClient.send(
        request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),
    )
    HttpTextResult(response.statusCode(), response.body())
}
