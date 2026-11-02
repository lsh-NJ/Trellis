package dev.trellis.app.desktop

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 笔记与学习计划的关联走 frontmatter（`plan` / `occurrence`），不建索引库。
 *
 * 这组用例钉住两件事：写进去的字段能原样读回来；清除关联时不会把别的 extras 抹掉。
 */
class KnowledgePlanLinkTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun note() = KnowledgeNote(
        id = "n_1",
        title = "虚拟语气",
        body = "正文",
        createdAt = 0,
        updatedAt = 0,
        extras = mapOf("source" to "manual"),
    )

    @Test
    fun linkIsWrittenToFrontmatterAndComesBackOnParse() {
        val linked = note().withPlanLink("p_1", "o_1", "雅思语法", "第 3 单元")
        assertEquals("p_1", linked.planId)
        assertEquals("o_1", linked.occurrenceId)

        val raw = formatNoteMarkdown(linked, zone)
        val parsed = parseNoteMarkdown("n_1", raw, zone, fallbackMs = 0)

        assertEquals("p_1", parsed.planId)
        assertEquals("o_1", parsed.occurrenceId)
        assertEquals("雅思语法", parsed.extras["plan_title"])
        assertEquals("第 3 单元", parsed.extras["node_title"])
        assertEquals("manual", parsed.extras["source"], "别的 extras 不能被顺手删掉")
    }

    @Test
    fun clearingTheLinkKeepsOtherExtras() {
        val linked = note().withPlanLink("p_1", "o_1", "计划", "节点")
        val cleared = linked.withPlanLink(null, null)

        assertNull(cleared.planId)
        assertNull(cleared.occurrenceId)
        assertEquals("manual", cleared.extras["source"])
    }
}
