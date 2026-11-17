package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 只读预览的目录跳转映射：正文里第 n 个标题 = 第 n 个 Heading 渲染块。
 *
 * 计划文档预览（[PlanDocPage]）靠它把目录项换算成块下标，再用 `onBlockLayout` 报的 y 滚过去；
 * 映射错了不是崩，而是「点目录滚到错误的地方」——所以单独钉一下。
 */
class MarkdownHeadingBlocksTest {

    @Test
    fun headingsMapToTheirRenderBlocksInDocumentOrder() {
        val body = "# 目标与验收\n- 目标：学操作系统\n## 阶段\n段落\n### 阶段 1 · 基础\n更多正文\n## 资源清单"
        val indexes = headingBlockIndexes(body)
        // 四个标题：# / ## / ### / ##；下标必须严格递增，且指向的块都是标题。
        assertEquals(4, indexes.size, indexes.toString())
        assertEquals(indexes.sorted(), indexes)
        val blocks = parseMarkdown(body)
        indexes.forEach { index -> assertEquals(true, blocks[index] is MdBlock.Heading, "第 $index 块不是标题") }
    }

    @Test
    fun codeFenceHeadingsAreNotBlocks() {
        // 围栏里的 `#` 既不算目录项（parseOutline 的规则），也不该被当成标题块。
        val body = "# 真标题\n\n```bash\n# 注释\n```\n\n## 另一个标题"
        val indexes = headingBlockIndexes(body)
        assertEquals(2, indexes.size, indexes.toString())
        assertEquals(2, parseOutline(body).size + parseOutline(body).sumOf { it.children.size })
    }

    @Test
    fun documentWithoutHeadingsHasNoMapping() {
        assertTrue(headingBlockIndexes("只有正文，没有标题。").isEmpty())
    }
}
