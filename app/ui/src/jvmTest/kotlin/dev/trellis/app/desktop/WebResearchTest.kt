package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * DeepSeek 官方 Search / Fetch 接口的响应解析。
 *
 * 真实响应里有不少可缺字段（site_name、date、summary…），解析必须能降级，
 * 因为调研只是规划时的参考：解析不出来就当「没搜到」，不能把计划流程带崩。
 */
class WebResearchTest {

    @Test
    fun searchResultsAreParsedWithGracefulFallbacks() {
        val raw = """
            {"results":[
              {"title":"AI 应用工程师学习路线","url":"https://example.com/a","site_name":"example.com",
               "authority":3,"rerank_score":2.4,"date":"2026-04-09T16:00:00+00:00",
               "summary":"先打基础，再做 RAG 项目。"},
              {"title":"岗位要求","url":"https://jobs.example.org/b","snippet":"熟悉向量库与检索增强生成。"}
            ]}
        """.trimIndent()

        val hits = parseSearchResults(raw)
        assertEquals(2, hits.size)
        assertEquals("AI 应用工程师学习路线", hits[0].title)
        assertEquals("example.com", hits[0].siteName)
        assertEquals(3, hits[0].authority)
        assertTrue(hits[0].date.startsWith("2026-04-09"))

        // 没给 site_name 时用 host 兜底，没给 summary 时退回 snippet。
        assertEquals("jobs.example.org", hits[1].siteName)
        assertEquals("熟悉向量库与检索增强生成。", hits[1].summary)
        assertEquals(-1, hits[1].authority)
    }

    @Test
    fun brokenOrEmptySearchResultsDegradeToEmptyList() {
        assertTrue(parseSearchResults("").isEmpty())
        assertTrue(parseSearchResults("{}").isEmpty())
        assertTrue(parseSearchResults("[]").isEmpty())
        assertTrue(parseSearchResults("{\"results\":[{\"title\":\"没有链接\"}]}").isEmpty())
    }

    @Test
    fun fetchedPageKeepsTitleUrlAndMarkdown() {
        val raw = """{"title":"AI 应用工程师学习路线","url":"https://example.com/a","content":"# 路线\n1. 基础"}"""
        val page = parseFetchedPage(raw)
        assertTrue(page != null)
        assertEquals("AI 应用工程师学习路线", page.title)
        assertEquals("https://example.com/a", page.url)
        assertTrue(page.markdown.contains("# 路线"))
        assertNull(parseFetchedPage("{\"title\":\"空\"}"))
    }

    @Test
    fun renderedSourcesCarryTitlesSitesAndMarkdown() {
        val sources = ResearchSources(
            hits = listOf(
                ResearchHit(
                    title = "AI 应用工程师学习路线",
                    url = "https://example.com/a",
                    siteName = "example.com",
                    summary = "先打基础，再做 RAG 项目。",
                    date = "2026-04-09T16:00:00+00:00",
                    authority = 3,
                ),
            ),
            pages = listOf(
                ResearchPage(url = "https://example.com/a", title = "AI 应用工程师学习路线", markdown = "# 路线"),
            ),
        )

        val text = sources.render()
        assertTrue(text.startsWith("<research_sources>"), text)
        assertTrue(text.contains("[1] AI 应用工程师学习路线 — example.com — 2026-04-09"), text)
        assertTrue(text.contains("先打基础，再做 RAG 项目。"), text)
        assertTrue(text.contains("https://example.com/a"), text)
        assertTrue(text.trimEnd().endsWith("</research_sources>"), text)
    }
}
