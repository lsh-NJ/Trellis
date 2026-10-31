package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 块切分与 splice 的纯函数测试。
 *
 * 钉住三件事：块的范围能精确还原正文（raw == substring、块之间不重叠）、
 * 各类语法的切分边界、splice 遇到越界输入时不动正文。
 */
class MarkdownBlockEditorTest {

    private fun kinds(body: String): List<BlockKind> = splitBodyBlocks(body).map { it.kind }

    @Test
    fun emptyBodyIsOneEmptyParagraph() {
        val blocks = splitBodyBlocks("")
        assertEquals(1, blocks.size)
        assertEquals(BodyBlock(0, 0, "", BlockKind.Paragraph), blocks.single())
    }

    @Test
    fun consecutiveNonBlankLinesFormOneParagraph() {
        val body = "第一段第一行\n第一段第二行"
        val blocks = splitBodyBlocks(body)
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.Paragraph, blocks.single().kind)
        assertEquals(body, blocks.single().raw)
        assertEquals(0, blocks.single().start)
        assertEquals(body.length, blocks.single().end)
    }

    @Test
    fun blankLineSplitsParagraphs() {
        val body = "第一段\n\n第二段"
        val blocks = splitBodyBlocks(body)
        assertEquals(2, blocks.size)
        assertEquals(listOf(BlockKind.Paragraph, BlockKind.Paragraph), blocks.map { it.kind })
        assertEquals("第一段", blocks[0].raw)
        assertEquals("第二段", blocks[1].raw)
        // 空行是块之间的间隔，不属于任何一块；两个换行原样留在正文里。
        assertEquals(2, blocks[1].start - blocks[0].end)
    }

    @Test
    fun headingIsSingleLineBlock() {
        val body = "# 标题\n正文"
        val blocks = splitBodyBlocks(body)
        assertEquals(listOf(BlockKind.Heading, BlockKind.Paragraph), blocks.map { it.kind })
        assertEquals("# 标题", blocks[0].raw)
        assertEquals("正文", blocks[1].raw)
    }

    @Test
    fun headingInsideCodeFenceIsNotAHeading() {
        val body = "```kotlin\n# 这不是标题\nval x = 1\n```"
        val blocks = splitBodyBlocks(body)
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.Code, blocks.single().kind)
        assertEquals(body, blocks.single().raw)
    }

    @Test
    fun unclosedFenceRunsToTheEnd() {
        val body = "```\ncode"
        val blocks = splitBodyBlocks(body)
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.Code, blocks.single().kind)
        assertEquals(body, blocks.single().raw)
        assertEquals(body.length, blocks.single().end)
    }

    @Test
    fun tildeFenceIsCodeToo() {
        val body = "~~~\na\n~~~\n正文"
        assertEquals(listOf(BlockKind.Code, BlockKind.Paragraph), kinds(body))
    }

    @Test
    fun tableRowsMergeIntoOneBlock() {
        val body = "| a | b |\n| --- | --- |\n| 1 | 2 |\n正文"
        val blocks = splitBodyBlocks(body)
        assertEquals(listOf(BlockKind.Table, BlockKind.Paragraph), blocks.map { it.kind })
        assertEquals("| a | b |\n| --- | --- |\n| 1 | 2 |", blocks[0].raw)
    }

    @Test
    fun mathBlockSpansUntilClosingDollarLine() {
        val body = "$$\nE = mc^2\n$$\n正文"
        val blocks = splitBodyBlocks(body)
        assertEquals(listOf(BlockKind.Math, BlockKind.Paragraph), blocks.map { it.kind })
        assertEquals("$$\nE = mc^2\n$$", blocks[0].raw)
    }

    @Test
    fun singleLineMathIsItsOwnBlock() {
        val body = "\$\$x + y\$\$\n正文"
        val blocks = splitBodyBlocks(body)
        assertEquals(listOf(BlockKind.Math, BlockKind.Paragraph), blocks.map { it.kind })
        assertEquals("\$\$x + y\$\$", blocks[0].raw)
    }

    @Test
    fun aiBlockEndsAtTripleColon() {
        val body = ":::ai\n- 补充一句\n:::\n正文"
        val blocks = splitBodyBlocks(body)
        assertEquals(listOf(BlockKind.AiBlock, BlockKind.Paragraph), blocks.map { it.kind })
        assertEquals(":::ai\n- 补充一句\n:::", blocks[0].raw)
    }

    @Test
    fun unclosedAiBlockRunsToTheEnd() {
        val body = ":::ai\n- 补充一句"
        val blocks = splitBodyBlocks(body)
        assertEquals(1, blocks.size)
        assertEquals(BlockKind.AiBlock, blocks.single().kind)
    }

    @Test
    fun listItemsAndQuoteAreOneLineEach() {
        val body = "- 一项\n1. 第一步\n> 引用\n- [ ] 待办"
        assertEquals(
            listOf(BlockKind.ListItem, BlockKind.ListItem, BlockKind.Quote, BlockKind.ListItem),
            kinds(body),
        )
    }

    @Test
    fun paragraphStopsWhereAnotherBlockStarts() {
        val body = "正文\n# 标题"
        assertEquals(listOf(BlockKind.Paragraph, BlockKind.Heading), kinds(body))
    }

    @Test
    fun everyBlockMatchesItsRangeInTheBody() {
        val body = "# 标题\n正文\n\n- 项\n> 引\n```\ncode\n```\n| a | b |\n\$\$x\$\$\n:::ai\n- 补\n:::"
        val blocks = splitBodyBlocks(body)
        // 块按出现顺序排列、互不重叠。
        var previousEnd = -1
        for (block in blocks) {
            assertTrue(block.start >= 0, "start 不能是负数")
            assertTrue(block.end >= block.start, "end 不能早于 start")
            assertTrue(block.start >= previousEnd, "块不能重叠")
            previousEnd = block.end
            assertEquals(block.raw, body.substring(block.start, block.end))
            assertEquals(block.start + block.raw.length, block.end)
        }
        // 块没覆盖到的只有空行；用原文把间隔补回去能还原整篇。
        val rebuilt = buildString {
            var cursor = 0
            for (block in blocks) {
                append(body, cursor, block.start)
                append(block.raw)
                cursor = block.end
            }
            append(body, cursor, body.length)
        }
        assertEquals(body, rebuilt)
    }

    @Test
    fun blocksJoinBackWithNewlinesWhenBodyHasNoBlankLines() {
        val body = "# 标题\n正文\n- 一项\n> 引用\n```\ncode\n```\n| a | b |\n\$\$x\$\$"
        val blocks = splitBodyBlocks(body)
        assertEquals(body, blocks.joinToString("\n") { it.raw })
    }

    @Test
    fun spliceReplacesOnlyTheGivenRange() {
        val body = "abc\ndef"
        assertEquals("abXYdef", spliceBlockRange(body, 2, 4, "XY"))
        assertEquals("abc\ndef", spliceBlockRange(body, 0, 0, ""))
        assertEquals("Xabc\ndef", spliceBlockRange(body, 0, 0, "X"))
        // 删掉整块内容但不带走块尾换行。
        assertEquals("\ndef", spliceBlockRange(body, 0, 3, ""))
    }

    @Test
    fun spliceRejectsOutOfBoundsRanges() {
        val body = "abc"
        assertEquals(body, spliceBlockRange(body, -1, 2, "X"))
        assertEquals(body, spliceBlockRange(body, 2, 1, "X"))
        assertEquals(body, spliceBlockRange(body, 0, 99, "X"))
    }

    @Test
    fun blockAtHitsEachBlockAndItsTrailingNewline() {
        val body = "第一段\n\n# 标题\n正文"
        val blocks = splitBodyBlocks(body)
        val paragraph = blocks[0]
        val heading = blocks[1]
        val tail = blocks[2]

        assertEquals(paragraph, blockAt(blocks, 0))
        assertEquals(paragraph, blockAt(blocks, paragraph.start + 1))
        // 块尾换行处算这一块。
        assertEquals(paragraph, blockAt(blocks, paragraph.end))
        assertEquals(paragraph, blockAt(blocks, paragraph.end + 1))
        assertEquals(heading, blockAt(blocks, heading.start))
        assertEquals(heading, blockAt(blocks, heading.end))
        assertEquals(tail, blockAt(blocks, tail.start))
        assertEquals(tail, blockAt(blocks, body.length))
        assertEquals(tail, blockAt(blocks, body.length + 50))
        assertEquals(paragraph, blockAt(blocks, -5))
    }

    @Test
    fun blockAtReturnsNullForEmptyList() {
        assertEquals(null, blockAt(emptyList(), 3))
    }

    @Test
    fun inlineMathDetection() {
        assertTrue(containsInlineMath("公式 \$x+y\$ 成立"))
        assertTrue(containsInlineMath("行内 \\(a^2\\) 写法"))
        assertFalse(containsInlineMath("价格 \$5 元"))
        assertFalse(containsInlineMath("\$\$x\$\$"))
    }

    @Test
    fun editingAcrossBlocksReparsesStructure() {
        val body = "第一段"
        val blocks = splitBodyBlocks(body)
        assertEquals(1, blocks.size)
        val withBlankLine = spliceBlockRange(body, blocks[0].start, blocks[0].end, "第一段\n\n第二段")
        assertEquals(
            listOf(BlockKind.Paragraph, BlockKind.Paragraph),
            splitBodyBlocks(withBlankLine).map { it.kind },
        )
        // 把段落改成标题：重新切块后第一块应当变成 Heading。
        val asHeading = spliceBlockRange(withBlankLine, 0, "第一段".length, "# 标题")
        assertEquals(BlockKind.Heading, splitBodyBlocks(asHeading).first().kind)
    }
}
