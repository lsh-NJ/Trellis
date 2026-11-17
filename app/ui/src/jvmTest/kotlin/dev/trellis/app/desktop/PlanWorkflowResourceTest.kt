package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 计划工作流的资源聚合规则：站点 → 类型，按类型分层，B 站单独走检索。
 *
 * 这三条是纯函数，值得钉住：模型只能在候选项里挑资源，候选错了，计划里的
 * 资源就会变成「标题好看但没用」的链接 —— 或者更糟，模型自己编一个 URL。
 */
class PlanWorkflowResourceTest {

    private fun workflow(): PlanWorkflow = PlanWorkflow(
        service = { null },
        workflow = { null },
        docStore = { null },
        research = { null },
        clock = SystemTrellisClock(),
        llm = object : LlmClient {
            override suspend fun complete(messages: List<LlmMessage>): String = ""
        },
        gateway = { null },
        onChanged = {},
    )

    private fun candidate(title: String, url: String, type: String, score: Int = 0) =
        PlanWorkflow.Candidate(title = title, url = url, type = type, source = "test", score = score)

    @Test
    fun hostsMapToResourceTypes() {
        val wf = workflow()
        assertEquals("视频", wf.typeOf("https://www.bilibili.com/video/BV1xx"))
        assertEquals("仓库", wf.typeOf("https://github.com/rust-lang/rust"))
        assertEquals("文档", wf.typeOf("https://developer.aliyun.com/article/1"))
        assertEquals("文档", wf.typeOf("https://cloud.tencent.com/developer/article/2"))
        assertEquals("问答", wf.typeOf("https://www.zhihu.com/question/3"))
        assertEquals("文章", wf.typeOf("https://blog.csdn.net/a/article/details/4"))
        assertEquals("文章", wf.typeOf("https://www.cnblogs.com/a/p/5.html"))
        assertEquals("其他", wf.typeOf("https://example.com/x"))
    }

    @Test
    fun picksRepresentativesPerTypeAndDedupes() {
        val wf = workflow()
        val picked = wf.pickPerType(
            listOf(
                candidate("课1", "https://www.bilibili.com/video/BV1", "视频", score = 40),
                candidate("课2", "https://www.bilibili.com/video/BV2", "视频", score = 10),
                candidate("重复", "https://www.bilibili.com/video/BV1", "视频", score = 99),
                candidate("书1", "https://github.com/a/b", "仓库"),
                candidate("文1", "https://blog.csdn.net/a", "文章"),
                candidate("文2", "https://blog.csdn.net/b", "文章"),
                candidate("文3", "https://blog.csdn.net/c", "文章"),
                candidate("文4", "https://blog.csdn.net/d", "文章"),
            ),
        )
        // 同一 URL 只留一条；视频按分集数（score）降序；文章类最多 3 条。
        assertEquals(1, picked.count { it.url == "https://www.bilibili.com/video/BV1" })
        assertEquals("课1", picked.first { it.type == "视频" }.title)
        assertEquals(2, picked.count { it.type == "视频" })
        assertEquals(3, picked.count { it.type == "文章" })
        assertTrue(picked.size <= 12)
    }

    @Test
    fun searchKeywordDropsFillerWords() {
        val wf = workflow()
        val keyword = wf.searchKeyword("我要学习操作系统，应对两个月后的期末考试")
        assertTrue(keyword.contains("操作系统"), keyword)
        assertTrue(!keyword.contains("我要"), keyword)
        assertTrue(keyword.length <= 24, keyword)
    }
}
