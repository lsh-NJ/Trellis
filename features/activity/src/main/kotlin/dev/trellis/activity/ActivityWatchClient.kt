package dev.trellis.activity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * ActivityWatch 的只读数据源。
 *
 * 只做三件事：确认服务在、列 bucket、按时间窗拉**原始事件**。
 *
 * 刻意不碰的东西：AW 自己的 `category_sets`、`/api/0/query/` 里的 `categorize`、
 * 以及任何「已经整理过」的结果。整理是 Trellis 自己的事 —— 这样在 Trellis 里改分类规则，
 * 历史数据不用重新导入，也不会因为 AW 换分类而失真。
 */
interface ActivitySource {
    suspend fun info(): JsonObject

    suspend fun buckets(): List<AwBucket>

    /**
     * 拉 `[fromMs, toMs)` 内的原始事件。aw-server 返回的是**最新在前**，实现负责统一成时间序。
     */
    suspend fun events(
        bucket: String,
        fromMs: Long,
        toMs: Long,
        limit: Int = DEFAULT_LIMIT,
    ): List<RawEvent>

    companion object {
        /** aw-server 单次返回上限。拿满这个数不代表数据完整，调用方要会切窗重拉。 */
        const val DEFAULT_LIMIT: Int = 10_000
    }
}

/**
 * 走 JDK 自带 `HttpClient` 的实现。
 *
 * 和 `BilibiliClient` 同一个套路：不引网络库、`fetch` 可注入（单测不给真服务也能跑）。
 * AW 默认不开鉴权；开了的话把 token 填进 `apiKey`。
 */
class ActivityWatchClient(
    private val fetch: suspend (url: String, apiKey: String) -> String = ::activityWatchGet,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String = "",
) : ActivitySource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun info(): JsonObject =
        json.parseToJsonElement(fetch("$baseUrl/info", apiKey)).jsonObject

    override suspend fun buckets(): List<AwBucket> {
        val root = runCatching { json.parseToJsonElement(fetch("$baseUrl/buckets/", apiKey)).jsonObject }
            .getOrElse { return emptyList() }
        return root.mapNotNull { (key, value) ->
            val o = runCatching { value.jsonObject }.getOrNull() ?: return@mapNotNull null
            val type = o.str("type") ?: return@mapNotNull null
            AwBucket(
                id = o.str("id") ?: key,
                type = type,
                hostname = o.str("hostname"),
                createdAt = o.str("created"),
            )
        }
    }

    override suspend fun events(bucket: String, fromMs: Long, toMs: Long, limit: Int): List<RawEvent> {
        if (toMs <= fromMs) return emptyList()
        val url = buildString {
            append(baseUrl).append("/buckets/").append(bucket).append("/events")
            append("?start=").append(encode(iso(fromMs)))
            append("&end=").append(encode(iso(toMs)))
            append("&limit=").append(limit)
        }
        val raw = runCatching { fetch(url, apiKey) }.getOrElse { return emptyList() }
        val array = runCatching { json.parseToJsonElement(raw).jsonArray }.getOrElse { return emptyList() }
        return array
            .mapNotNull { el -> runCatching { parse(bucket, el.jsonObject) }.getOrNull() }
            .sortedBy { it.tsMs }
    }

    private fun parse(bucket: String, o: JsonObject): RawEvent? {
        val stamp = o.str("timestamp") ?: return null
        val tsMs = runCatching {
            OffsetDateTime.parse(stamp).toInstant().toEpochMilli()
        }.getOrElse {
            runCatching { Instant.parse(stamp).toEpochMilli() }.getOrElse { return null }
        }
        val durSec = o["duration"]?.jsonPrimitive?.doubleOrNull ?: return null
        val data = runCatching { o.getValue("data").jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val awId = o["id"]?.jsonPrimitive?.longOrNull
        return RawEvent(
            bucket = bucket,
            // 裸 bucket 查询一定带 id；万一没有（聚合型返回），用内容算一个稳定的替代 id，
            // 保证同一批数据重复拉取时仍然是幂等的。
            awId = awId ?: stableId(tsMs, data.str("app"), data.str("title"), data.str("status")),
            tsMs = tsMs,
            durMs = (durSec * 1000.0).roundToLong(),
            app = data.str("app"),
            title = data.str("title"),
            status = data.str("status"),
        )
    }

    private fun stableId(tsMs: Long, app: String?, title: String?, status: String?): Long {
        val h = "$tsMs|${app.orEmpty()}|${title.orEmpty()}|${status.orEmpty()}".hashCode()
        return (tsMs shl 20) xor (h.toLong() and 0xFFFFFL)
    }

    private fun iso(ms: Long): String = FORMAT.format(
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()),
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL: String = "http://localhost:5600/api/0"
        private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}

private val httpClient: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
}

/**
 * AW 跑在本机，正常情况响应是毫秒级；30 秒超时只是为了不让界面在 aw-server 卡住时一起卡死。
 */
private suspend fun activityWatchGet(url: String, apiKey: String): String = withContext(Dispatchers.IO) {
    val builder = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofSeconds(30))
        .GET()
    if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
    val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
        error("ActivityWatch 返回 HTTP ${response.statusCode()}：$url")
    }
    response.body()
}