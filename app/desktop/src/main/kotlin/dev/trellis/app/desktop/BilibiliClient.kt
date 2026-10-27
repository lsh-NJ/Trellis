package dev.trellis.app.desktop

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** B 站没答上来，或者答了但不是我们要的。消息是给人看的，工具会原样交给模型。 */
class BilibiliException(message: String) : Exception(message)

/**
 * 只读的 B 站客户端：搜视频、拿分集。
 *
 * 为什么是「自己拼」而不是上 SDK：B 站没有一个稳定的公开搜索 API，网页版用的是
 * **WBI 签名**（当前时间戳 wts + 图片/子图两段 key 混出来的 mixin key，再 md5）。
 * 这套东西目前不登录也能用，但**必须带一个 buvid3 cookie**，否则搜索接口直接 412。
 * 所以这里的流程固定成三步：
 *
 * 1. `/x/frontend/finger/spi` 拿 buvid3（一次，缓存）；
 * 2. `/x/web-interface/nav` 拿 wbi_img 的 img_key/sub_key，混成 mixin key（一小时有效）；
 * 3. 参数按字典序拼上 wts，算 w_rid，再请求 `/x/web-interface/wbi/search/type`。
 *
 * 分集（`/x/player/pagelist`）不需要签名，公开可读。
 *
 * 不引网络库：和 [dev.trellis.ai.HttpLlmClient] 一样只用 JDK 自带的 HttpClient。
 * 不下载、不播放、不登录 —— 只读元数据，这是这个工具的全部边界。
 */
class BilibiliClient(
    private val fetch: suspend (url: String, cookie: String?) -> String = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var cookie: String? = null
    private var mixinKey: String? = null
    private var mixinFetchedAt: Long = 0

    /** 一条搜索结果。播放量已经格式化成「2454.5 万」，模型和用户都读得懂。 */
    data class Video(
        val title: String,
        val bvid: String,
        val author: String,
        val playText: String,
        val duration: String,
        val parts: Int,
    )

    /** 一集。多 P 视频的分集名就是天然目录。 */
    data class Part(
        val page: Int,
        val title: String,
        val durationText: String,
        /** 时长（分钟，向上取整）：做计划节点时直接当预估时间用。 */
        val durationMinutes: Int = 0,
    )

    suspend fun search(keyword: String, order: String, limit: Int): List<Video> {
        val signed = sign(
            buildMap {
                put("search_type", "video")
                put("keyword", keyword)
                put("order", order)
                put("page", "1")
                put("page_size", limit.toString())
            },
        )
        val body = call("https://api.bilibili.com/x/web-interface/wbi/search/type?" + signed)
        val root = parse(body)
        val items = (root["data"] as? JsonObject)?.get("result") as? JsonArray
            ?: throw BilibiliException("B 站这次没返回搜索结果，过会儿再试。")
        val videos = items.take(limit).mapNotNull(::videoOf)
        // 搜索结果里没有分集数（video_review 是弹幕数，别拿它冒充），
        // 想知道「这视频有几集」只能再问一次分集接口 —— 顺手替模型问了，省一轮调用。
        return coroutineScope {
            videos.map { video ->
                async {
                    val count = runCatching { parts(video.bvid).size }.getOrDefault(0)
                    if (count > 0) video.copy(parts = count) else video
                }
            }.awaitAll()
        }
    }

    suspend fun parts(bvid: String): List<Part> {
        val body = call("https://api.bilibili.com/x/player/pagelist?bvid=" + url(bvid))
        val root = parse(body)
        val items = root["data"] as? JsonArray ?: throw BilibiliException("这个 BV 号没拿到分集，确认一下是不是写错了。")
        return items.mapNotNull(::partOf)
    }

    private fun videoOf(element: kotlinx.serialization.json.JsonElement): Video? {
        val row = element as? JsonObject ?: return null
        val bvid = row["bvid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        return Video(
            title = clean(row["title"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            bvid = bvid,
            author = clean(row["author"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            playText = playText(row["play"]?.jsonPrimitive?.longOrNull ?: 0L),
            duration = row["duration"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            // 分集数由 search() 再问一次分集接口补上；video_review 是弹幕数，不能当集数。
            parts = 0,
        )
    }

    private fun partOf(element: kotlinx.serialization.json.JsonElement): Part? {
        val row = element as? JsonObject ?: return null
        val page = row["page"]?.jsonPrimitive?.intOrNull ?: return null
        val seconds = row["duration"]?.jsonPrimitive?.intOrNull ?: 0
        return Part(
            page = page,
            title = clean(row["part"]?.jsonPrimitive?.contentOrNull.orEmpty()),
            durationText = clockText(seconds),
            // 向上取整到分钟：19 分 5 秒算 20 分钟，做计划时宁可多留一点。
            durationMinutes = if (seconds <= 0) 0 else (seconds + 59) / 60,
        )
    }

    private suspend fun call(url: String): String {
        val text = fetch(url, ensureCookie())
        if (text.trimStart().startsWith("<")) {
            throw BilibiliException("B 站这次把请求挡了（返回了网页而不是数据），过几分钟再试。")
        }
        return text
    }

    /** nav 的 code 是 -101（未登录）但 data 里照样有 wbi_img，所以它不能走 [parse] 的 code 校验。 */
    private suspend fun fetchJson(url: String): JsonObject {
        val body = call(url)
        return try {
            Json.parseToJsonElement(body) as? JsonObject ?: throw BilibiliException("B 站返回的内容看不懂。")
        } catch (e: BilibiliException) {
            throw e
        } catch (e: Exception) {
            throw BilibiliException("B 站返回的内容看不懂：" + (e.message ?: "格式不对"))
        }
    }

    private fun parse(body: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject ?: throw BilibiliException("B 站返回的内容看不懂。")
        } catch (e: BilibiliException) {
            throw e
        } catch (e: Exception) {
            throw BilibiliException("B 站返回的内容看不懂：" + (e.message ?: "格式不对"))
        }
        val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
        if (code != 0) {
            val message = root["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            throw BilibiliException(
                when (code) {
                    -412 -> "B 站要求先过风控（412）。等一下再试；连续搜太多次也会这样。"
                    -101 -> "B 站要求登录（-101），这个工具只能匿名搜公开内容。"
                    -400 -> "B 站说参数不对（-400）：" + message
                    else -> "B 站没答上来（" + code + "）：" + message
                },
            )
        }
        return root
    }

    /** 拿 buvid3。失败就退回无 cookie —— 搜索会 412，那时错误信息会说明原因。 */
    private suspend fun ensureCookie(): String? {
        cookie?.let { return it }
        val body = runCatching { fetch("https://api.bilibili.com/x/frontend/finger/spi", null) }.getOrNull() ?: return null
        val buvid = runCatching {
            (Json.parseToJsonElement(body).jsonObject["data"] as JsonObject)["b_3"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (!buvid.isNullOrBlank()) cookie = "buvid3=" + buvid
        return cookie
    }

    private suspend fun mixin(): String {
        val now = clock()
        mixinKey?.let { if (now - mixinFetchedAt < 3_600_000) return it }
        val wbi = (fetchJson("https://api.bilibili.com/x/web-interface/nav")["data"] as? JsonObject)
            ?.get("wbi_img") as? JsonObject
            ?: throw BilibiliException("B 站没给出签名用的 key，搜索暂时用不了。")
        val img = wbi["img_url"]?.jsonPrimitive?.contentOrNull.orEmpty().substringAfterLast('/').substringBefore('.')
        val sub = wbi["sub_url"]?.jsonPrimitive?.contentOrNull.orEmpty().substringAfterLast('/').substringBefore('.')
        val key = MIXIN_ORDER.map { (img + sub).getOrNull(it) ?: '_' }.joinToString("").take(32)
        mixinKey = key
        mixinFetchedAt = now
        return key
    }

    private suspend fun sign(params: Map<String, String>): String {
        val key = mixin()
        val withWts = params + ("wts" to (clock() / 1000).toString())
        val query = withWts.entries.sortedBy { it.key }.joinToString("&") { url(it.key) + "=" + url(it.value) }
        val wRid = md5(query + key)
        return query + "&w_rid=" + wRid
    }

    companion object {
        /** B 站的 wbi mixin 表：把 img_key+sub_key 的这几位置拿出来拼成 32 位 key。 */
        private val MIXIN_ORDER = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52,
        )

        private val CLIENT: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        private suspend fun httpGet(url: String, cookie: String?): String = withContext(Dispatchers.IO) {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json")
                .apply { if (!cookie.isNullOrBlank()) header("Cookie", cookie) }
                .GET()
                .build()
            val response = try {
                CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: Exception) {
                throw BilibiliException("连不上 B 站：" + (e.message ?: "网络不通") + "。检查一下网络，或者过会儿再试。")
            }
            if (response.statusCode() != 200) {
                throw BilibiliException("B 站回了 HTTP " + response.statusCode() + "，等一下再试。")
            }
            response.body()
        }

        fun url(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        fun md5(text: String): String =
            MessageDigest.getInstance("MD5").digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        fun clean(raw: String): String =
            raw.replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").trim()

        fun playText(count: Long): String = when {
            count >= 100_000_000 -> oneDecimal(count / 100_000_000.0) + " 亿"
            count >= 10_000 -> oneDecimal(count / 10_000.0) + " 万"
            else -> count.toString()
        }

        fun clockText(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        /** 一位小数：2454.5313 万 读作 2454.5 万，1 万读作 1.0 万。 */
        private fun oneDecimal(value: Double): String =
            "%.1f".format(kotlin.math.round(value * 10) / 10)
    }
}
