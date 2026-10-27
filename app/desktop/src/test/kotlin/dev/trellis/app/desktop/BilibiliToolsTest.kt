package dev.trellis.app.desktop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B 站工具的行为：只看我们自己写的部分 —— 签名参数、文案、出错时说什么。
 *
 * 网络用假的：一个函数按 URL 回不同 JSON。真网络只在手动冒烟时跑，
 * 单元测试不该依赖 B 站此刻achable与否。
 */
class BilibiliToolsTest {

    private val navJson = """
        {"code":0,"data":{"wbi_img":{
          "img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
          "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"}}}
    """.trimIndent()

    private val spiJson = """{"code":0,"data":{"b_3":"BUV3TEST","b_4":"BUV4TEST"}}"""

    private val searchJson = """
        {"code":0,"data":{"result":[
          {"title":"<em class=\"keyword\">操作</em>系统 考研","bvid":"BV1YE411D7nH","author":"王道计算机教育",
           "play":24545313,"duration":"1502:13","video_review":243084},
          {"title":"操作系统 速成","bvid":"BV19E411D78Q","author":"某 up","play":8300,"duration":"42:10","video_review":0}
        ]}}
    """.trimIndent()

    private val pagelistJson = """
        {"code":0,"data":[
          {"page":1,"part":"课程白嫖指南","duration":277},
          {"page":2,"part":"1.1 操作系统的概念","duration":1218},
          {"page":3,"part":"1.2 操作系统的发展","duration":647}
        ]}
    """.trimIndent()

    private val urls = mutableListOf<String>()

    private fun client(json: (String) -> String): BilibiliClient = BilibiliClient(
        fetch = { url, _ ->
            urls += url
            json(url)
        },
        clock = { 1_700_000_000_000L },
    )

    private fun happy(url: String): String = when {
        "finger/spi" in url -> spiJson
        "web-interface/nav" in url -> navJson
        "wbi/search/type" in url -> searchJson
        "player/pagelist" in url -> pagelistJson
        else -> error("unexpected url: " + url)
    }

    @Test
    fun searchSignsTheRequestAndFormatsWhatPeopleNeed() = runTest {
        val videos = client(::happy).search("操作系统", "click", 5)

        assertEquals(2, videos.size)
        assertEquals("操作系统 考研", videos[0].title, "标题里的 <em> 要去掉")
        assertEquals("BV1YE411D7nH", videos[0].bvid)
        assertEquals("2454.5 万", videos[0].playText)
        assertEquals("1502:13", videos[0].duration)
        assertEquals("8300", videos[1].playText)
        assertEquals(3, videos[0].parts, "分集数是另问一次分集接口补的")
        val search = urls.first { "wbi/search/type" in it }
        assertTrue("w_rid=" in search, "搜索请求必须带 w_rid 签名")
        assertTrue("wts=1700000000" in search, "签名里要有时刻")
        assertTrue("order=click" in search, "默认按热度")
        assertTrue("keyword=" + BilibiliClient.url("操作系统") in search, "关键词要 URL 编码")
    }

    @Test
    fun partsBecomeADirectoryWithReadableDurations() = runTest {
        val parts = client(::happy).parts("BV1YE411D7nH")

        assertEquals(3, parts.size)
        assertEquals("1.1 操作系统的概念", parts[1].title)
        assertEquals("20:18", parts[1].durationText)
        assertEquals("4:37", parts[0].durationText)
    }

    @Test
    fun riskControlAndBlockedPagesGetHumanMessages() = runTest {
        val risk = client { url ->
            when {
                "finger/spi" in url -> spiJson
                "web-interface/nav" in url -> navJson
                else -> """{"code":-412,"message":"请求被拦截"}"""
            }
        }
        val error = runCatching { risk.search("操作系统", "click", 3) }.exceptionOrNull()
        assertTrue(error is BilibiliException)
        assertTrue(error!!.message!!.contains("风控"), "412 要说人话：" + error.message)

        val html = client { url ->
            when {
                "finger/spi" in url -> spiJson
                "web-interface/nav" in url -> navJson
                else -> "<!DOCTYPE html><html><body>412</body></html>"
            }
        }
        val htmlError = runCatching { html.search("操作系统", "click", 3) }.exceptionOrNull()
        assertTrue(htmlError is BilibiliException, "返回网页不能当成 JSON 硬解")
    }

    @Test
    fun toolsExplainThemselvesWhenArgumentsAreWrong() = runTest {
        val tools = BilibiliTools(client(::happy))
        val search = tools.all.single { it.name == "search_bilibili" }
        val parts = tools.all.single { it.name == "list_bilibili_parts" }

        val noKeyword = search.call(JsonObject(emptyMap()))
        assertTrue(noKeyword.isError)
        assertTrue(noKeyword.content.contains("keyword"))

        val ok = search.call(buildJsonObject {
            put("keyword", "操作系统")
            put("limit", 99)
        })
        assertFalse(ok.isError)
        assertTrue(ok.content.contains("BV1YE411D7nH"))
        assertTrue(ok.content.contains("list_bilibili_parts"), "结果里要告诉下一步怎么做")
        assertTrue(urls.any { "page_size=10" in it }, "limit 超上限要被夹到 10")

        val noBvid = parts.call(JsonObject(emptyMap()))
        assertTrue(noBvid.isError)

        val directory = parts.call(buildJsonObject { put("bvid", "BV1YE411D7nH") })
        assertFalse(directory.isError)
        assertTrue(directory.content.contains("共 3 集"))
        assertTrue(directory.content.contains("P2 1.1 操作系统的概念（20:18，约 21 分钟）"))
        assertTrue(directory.content.contains("6-12"), "要提示只挑几集当步骤")
    }

    @Test
    fun singleEpisodeVideoIsStillAValidStep() = runTest {
        val single = client { url ->
            when {
                "finger/spi" in url -> spiJson
                "web-interface/nav" in url -> navJson
                else -> """{"code":0,"data":[{"page":1,"part":"唯一一集","duration":600}]}"""
            }
        }
        val result = BilibiliTools(single).all.single { it.name == "list_bilibili_parts" }
            .call(buildJsonObject { put("bvid", "BV1xx411c7mD") })
        assertFalse(result.isError)
        assertTrue(result.content.contains("单集"))
    }

    @Test
    fun playCountsReadLikePeopleReadThem() {
        assertEquals("9999", BilibiliClient.playText(9_999))
        assertEquals("1.0 万", BilibiliClient.playText(10_000))
        assertEquals("2454.5 万", BilibiliClient.playText(24_545_313))
        assertEquals("1.0 亿", BilibiliClient.playText(100_000_000))
    }
}
