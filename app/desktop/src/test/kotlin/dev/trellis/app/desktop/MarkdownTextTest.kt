package dev.trellis.app.desktop

import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 会话里的 Markdown 渲染只做「轻量」那部分；这里钉住的是**不吞内容**：
 * 认不出的语法必须原样留下，否则模型认真写的答复会被渲染器吃掉。
 */
class MarkdownTextTest {

    @Test
    fun paragraphsStayOneBlockAndBlankLinesSplitThem() {
        val blocks = parseMarkdown("第一段第一行\n第一段第二行\n\n第二段")
        assertEquals(2, blocks.size)
        assertEquals("第一段第一行 第一段第二行", (blocks[0] as MdBlock.Paragraph).text)
        assertEquals("第二段", (blocks[1] as MdBlock.Paragraph).text)
    }

    @Test
    fun headingsBulletsNumbersAndQuotesAreSplit() {
        val source = """
            ## 今日小结
            - 完成了三件事
            - 还有两件在等
            1. 先做 A
            2. 再做 B
            > 一句提醒
        """.trimIndent()
        val blocks = parseMarkdown(source)
        assertTrue(blocks[0] is MdBlock.Heading)
        assertEquals(2, (blocks[0] as MdBlock.Heading).level)
        assertEquals("今日小结", (blocks[0] as MdBlock.Heading).text)
        assertTrue(blocks.any { it is MdBlock.Bullet && it.text == "完成了三件事" })
        assertTrue(blocks.any { it is MdBlock.Numbered && it.number == "2" && it.text == "再做 B" })
        assertTrue(blocks.any { it is MdBlock.Quote && it.text == "一句提醒" })
    }

    @Test
    fun aFenceKeepsCodeVerbatim() {
        val source = """
            前一句
            ```kotlin
            val a = 1
            fun b() = a + 1
            ```
            后一句
        """.trimIndent()
        val blocks = parseMarkdown(source)
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val a = 1\nfun b() = a + 1", code.code)
        assertEquals("前一句", (blocks.first() as MdBlock.Paragraph).text)
        assertEquals("后一句", (blocks.last() as MdBlock.Paragraph).text)
    }

    @Test
    fun anUnclosedFenceDoesNotSwallowTheAnswer() {
        val blocks = parseMarkdown("```\n半截代码没有收尾")
        val code = blocks.filterIsInstance<MdBlock.Code>().single()
        assertEquals("半截代码没有收尾", code.code)
    }

    @Test
    fun inlineStylesAreAppliedAndUnknownSyntaxSurvives() {
        val text = inlineMarkdown("普通 **加粗** `代码` *斜体* [链接](https://example.com) 和 ~~删除~~")
        assertTrue(text.text.contains("普通 加粗 代码 斜体 链接 (https://example.com) 和 删除"))
        assertTrue(text.spanStyles.size >= 5, "粗体/代码/斜体/链接/删除线都要有样式，实际 " + text.spanStyles)
        // 不闭合的星号不能被当成样式，也不能被吞掉。
        assertEquals("a * b * c", inlineMarkdown("a * b * c").text)
    }

    @Test
    fun aPipeTableBecomesRealRows() {
        val blocks = parseMarkdown("| 档位 | 已售 |\n| --- | --- |\n| 早鸟 | 742 |\n| 预售 | 1180 |")
        val table = blocks.filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("档位", "已售"), table.header)
        assertEquals(listOf(listOf("早鸟", "742"), listOf("预售", "1180")), table.rows)
    }

    @Test
    fun aTableWithoutASeparatorRowStillWorks() {
        // 模型有时忘记写 |---| 那一行：第一行当表头，其余当数据，不能整块吞掉。
        val table = parseMarkdown("| a | b |\n| 1 | 2 |").filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.header)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun aDashedRuleBecomesADivider() {
        val blocks = parseMarkdown("上一段\n\n---\n\n下一段")
        assertEquals(3, blocks.size)
        assertEquals(MdBlock.Rule, blocks[1])
        // 关键回归：`---` 以前被当成普通段落，预览里显示成三个横杠。
        assertTrue(blocks.none { it is MdBlock.Paragraph && it.text.contains("---") })
    }

    @Test
    fun rulesMadeOfStarsOrUnderscoresAlsoCount() {
        assertTrue(parseMarkdown("a\n\n***\n\nb").any { it == MdBlock.Rule })
        assertTrue(parseMarkdown("a\n\n___\n\nb").any { it == MdBlock.Rule })
        // 中间夹空格也认；但不能把 `- 列表项` 吃成横线。
        assertTrue(parseMarkdown("a\n\n- - -\n\nb").any { it == MdBlock.Rule })
        assertTrue(parseMarkdown("- 一项").first() is MdBlock.Bullet)
    }

    @Test
    fun aTableSeparatorIsStillATableNotARule() {
        val table = parseMarkdown("| a | b |\n| --- | --- |\n| 1 | 2 |").filterIsInstance<MdBlock.Table>().single()
        assertEquals(listOf("a", "b"), table.header)
    }

    @Test
    fun taskItemsCarryTheirCheckedState() {
        val bullets = parseMarkdown("- [ ] 待办\n- [x] 已办").filterIsInstance<MdBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertEquals(false, bullets[0].checked)
        assertEquals("待办", bullets[0].text)
        assertEquals(true, bullets[1].checked)
        assertEquals("已办", bullets[1].text)
    }

    @Test
    fun imagesKeepAltTextAndUrl() {
        val text = inlineMarkdown("![架构图](assets/a.png)")
        assertTrue(text.text.contains("［图］架构图"), "实际 " + text.text)
        assertTrue(text.text.contains("assets/a.png"), "实际 " + text.text)
    }

    @Test
    fun tripleAsteriskIsBoldItalic() {
        val text = inlineMarkdown("***重点***")
        assertEquals("重点", text.text)
        val style = text.spanStyles.single().item
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, style.fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, style.fontStyle)
    }

    @Test
    fun anEmptyHeadingMarkerFallsBackToParagraph() {
        // `## ` 后面没字：不出空标题（目录里也数不到），但那一行的内容也不能丢。
        val blocks = parseMarkdown("## ")
        assertEquals(1, blocks.size)
        assertEquals("##", (blocks.single() as MdBlock.Paragraph).text)
    }

    @Test
    fun headingSizesFormADescendingScaleThatStaysAboveBodyText() {
        val base = androidx.compose.ui.text.TextStyle(fontSize = 16.sp)
        val sizes = (1..6).map { level ->
            markdownHeadingStyle(level, base).fontSize.value
        }
        // 一级最大、六级最小，且严格递减（同级不能有一样大的）。
        assertEquals(sizes.sortedDescending(), sizes)
        assertTrue(sizes.zipWithNext().all { (a, b) -> a > b }, "标题字号必须严格递减：$sizes")
        // 任何一级都不小于正文，否则「加粗正文」会看起来比标题还大。
        assertTrue(sizes.min() >= 16f, "最小一级标题不能小于正文：$sizes")
        assertTrue(sizes.first() >= 22f, "一级标题要明显大于正文：$sizes")
    }

    @Test
    fun headingScaleFollowsTheBaseBodySize() {
        // 主题缩放/用户字号：系数乘在传入的正文上，大字号下阶梯跟着放大。
        val small = markdownHeadingStyle(1, androidx.compose.ui.text.TextStyle(fontSize = 14.sp)).fontSize.value
        val large = markdownHeadingStyle(1, androidx.compose.ui.text.TextStyle(fontSize = 20.sp)).fontSize.value
        assertTrue(large > small)
    }
}
