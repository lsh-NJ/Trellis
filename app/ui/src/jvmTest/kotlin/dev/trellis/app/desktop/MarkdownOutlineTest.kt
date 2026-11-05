package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 目录解析与折叠。
 *
 * 钉住三件事：**偏移对得上正文**（跳转的命门）、**代码块里的 `#` 不算标题**、
 * **层级跳级（# 直接到 ###）不能把节点挂丢**。
 */
class MarkdownOutlineTest {

    private fun flattenTexts(nodes: List<OutlineItem>): List<String> = buildList {
        fun walk(list: List<OutlineItem>) {
            for (node in list) {
                add(node.text)
                walk(node.children)
            }
        }
        walk(nodes)
    }

    @Test
    fun headingsBecomeANestedTree() {
        val body = "# 一\n正文\n## 一甲\n### 一甲一\n## 一乙\n# 二"
        val roots = parseOutline(body)
        assertEquals(2, roots.size)
        assertEquals("一", roots[0].text)
        assertEquals(2, roots[0].children.size)
        assertEquals("一甲", roots[0].children[0].text)
        assertEquals("一甲一", roots[0].children[0].children.single().text)
        assertEquals("二", roots[1].text)
        assertTrue(roots[1].children.isEmpty())
    }

    @Test
    fun offsetsPointAtTheHeadingLineStart() {
        val body = "开头\n\n## 标题\n正文"
        val item = parseOutline(body).single()
        assertEquals(4, item.offset)
        assertEquals(2, item.lineIndex)
        assertEquals("## 标题", body.substring(item.offset).substringBefore('\n'))
    }

    @Test
    fun headingsInsideFencesAreIgnored() {
        val body = "# 真的标题\n```\n# 这是代码里的注释\n```\n## 也是真的"
        assertEquals(listOf("真的标题", "也是真的"), flattenTexts(parseOutline(body)))
    }

    @Test
    fun hashWithoutSpaceIsNotAHeading() {
        assertTrue(parseOutline("#标签 不是标题").isEmpty())
        assertTrue(parseOutline("#").isEmpty())
        assertTrue(parseOutline("#######七个井号").isEmpty())
    }

    @Test
    fun ordinalsFollowDocumentOrder() {
        val body = "# a\n## b\n# c\n### d"
        val ordinals = buildMap {
            fun walk(nodes: List<OutlineItem>) {
                for (node in nodes) {
                    put(node.text, node.ordinal)
                    walk(node.children)
                }
            }
            walk(parseOutline(body))
        }
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2, "d" to 3), ordinals)
    }

    @Test
    fun collapsingANodeHidesOnlyItsSubtree() {
        val roots = parseOutline("# a\n## b\n### c\n# d")
        val branch = roots[0].children.first()
        val rows = flattenOutline(roots, setOf(branch.offset))
        assertEquals(listOf("a", "b", "d"), rows.map { it.item.text })
        assertTrue(rows[1].hasChildren)
        assertTrue(!rows[1].expanded)
    }

    @Test
    fun emptyBodyHasNoOutline() {
        assertTrue(parseOutline("").isEmpty())
        assertTrue(parseOutline("没有标题的一段话").isEmpty())
    }
}
