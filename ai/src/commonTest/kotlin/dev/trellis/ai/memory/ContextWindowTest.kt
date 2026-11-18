package dev.trellis.ai.memory

import dev.trellis.ai.LlmMessage
import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 窗口与检查点的纯逻辑：切点必须落在轮边界上，预算再紧也要留住最近几轮。
 * 这些都是「看起来能跑、实际把对话切成两半」的坑，用测试钉住。
 */
class ContextWindowTest {

    private fun turn(user: String, answer: String): List<LlmMessage> = listOf(
        LlmMessage(LlmRole.User, user),
        LlmMessage(LlmRole.Assistant, answer),
    )

    private fun longHistory(turns: Int, chars: Int = 400): List<LlmMessage> = buildList {
        repeat(turns) { index ->
            addAll(turn("问题$index" + "字".repeat(chars), "回答$index" + "字".repeat(chars)))
        }
    }

    @Test
    fun theEstimatorFollowsThePublishedRatio() {
        // DeepSeek：1 汉字 ≈ 0.6 token、1 英文字符 ≈ 0.3 token，按整数四舍五入。
        assertEquals(6, estimateTokens("字".repeat(10)))
        assertEquals(3, estimateTokens("a".repeat(10)))
        assertEquals(1, estimateTokens("abcd"))
        assertEquals(2, estimateTokens("abcdefgh"))
        assertTrue(estimateTokens("你好世界") > estimateTokens("abcd"), "同样字数，中文比 ASCII 贵一倍")
    }

    @Test
    fun imagesDoNotCountTheirBase64Bytes() {
        // data URL 的 base64 长度没有参考价值：算进去会把预算一次撑爆。
        val withImage = LlmMessage(LlmRole.User, "看图", images = listOf("data:image/png;base64," + "A".repeat(100_000)))
        assertTrue(estimateTokens(listOf(withImage)) < 2_000)
    }

    @Test
    fun shortHistoryIsLeftAlone() {
        val history = longHistory(2)
        val plan = planWindow(history)
        assertEquals(history, plan.kept)
        assertTrue(plan.folded.isEmpty())
        assertTrue(!plan.changed)
    }

    @Test
    fun foldingKeepsTheNewestTurnsVerbatim() {
        val history = longHistory(12)
        val plan = planWindow(history, keepTokens = 3_000, minTurns = 3)

        assertTrue(plan.changed, "12 轮长历史应该触发折叠")
        assertTrue(plan.folded.isNotEmpty())
        // 压缩只换表示，不丢轮次：两段拼起来必须还是原历史。
        assertEquals(history, plan.folded + plan.kept)
        // 最近一轮一个字都不能动。
        assertEquals(history.takeLast(2), plan.kept.takeLast(2))
        assertTrue(plan.kept.size >= 3 * 2, "至少保留 MIN_TURNS 轮")
    }

    @Test
    fun theCutNeverSplitsATurn() {
        val history = longHistory(12)
        val plan = planWindow(history, keepTokens = 2_500, minTurns = 3)

        // 折叠段必须以助手回复收尾，保留段必须以用户消息开头 ——
        // 否则接手模型会看到一句没有问题的回答。
        assertEquals(LlmRole.Assistant, plan.folded.last().role)
        assertEquals(LlmRole.User, plan.kept.first().role)
    }

    @Test
    fun budgetIsSoftButContinuityIsHard() {
        // 预算比最近三轮还小：仍然保留三轮，宁可超预算也不把对话切成半句。
        val plan = planWindow(longHistory(10, chars = 900), keepTokens = 100, minTurns = 3)
        assertTrue(plan.kept.size >= 6, "预算再紧也要保留最近三轮")
        assertEquals(10 * 2, plan.kept.size + plan.folded.size)
    }

    @Test
    fun tailWithinAlwaysKeepsTheLastTurn() {
        val history = longHistory(8, chars = 900)
        val kept = tailWithin(history, budget = 100)
        assertEquals(history.takeLast(2), kept, "最后一轮永远保留，否则模型不知道刚才说了什么")
    }

    @Test
    fun aCheckpointRequestCarriesTheOldCheckpointAndTheFoldedText() {
        val folded = longHistory(3).take(4)
        val messages = checkpointMessages("上一版摘要：用户想考软考", folded)

        assertEquals(2, messages.size)
        assertEquals(LlmRole.System, messages[0].role)
        assertTrue(messages[0].content.contains("## 进度"))
        assertTrue(messages[0].content.contains("## 决策与约束"))
        assertTrue(messages[0].content.contains("## 下一步"))
        val body = messages[1].content
        assertTrue(body.contains("<已有的检查点>"))
        assertTrue(body.contains("用户想考软考"))
        assertTrue(body.contains("问题0"))
        assertTrue(body.contains("</被折叠的会话>"))
    }

    @Test
    fun anOversizedFoldedRangeIsTrimmedAndSaysSo() {
        val folded = buildList {
            repeat(200) { index -> add(LlmMessage(LlmRole.User, "第" + index + "条" + "字".repeat(300))) }
        }
        val body = checkpointMessages("", folded)[1].content
        assertTrue(body.contains("条消息已省略"), "超长折叠范围要从最老的开始丢，并注明省略了多少条")
    }

    @Test
    fun normalizeStripsFencesAndCapsTheLength() {
        val wrapped = "```markdown\n## 进度\n\n\n做完了\n```"
        assertEquals("## 进度\n\n做完了", normalizeCheckpoint(wrapped))
        val long = normalizeCheckpoint("字".repeat(ContextBudget.CHECKPOINT_CHARS + 500))
        assertEquals(ContextBudget.CHECKPOINT_CHARS, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("", normalizeCheckpoint("   "))
    }

    @Test
    fun anEmptyCheckpointAddsNoPromptBlock() {
        assertEquals("", checkpointBlock("  "))
        val block = checkpointBlock("做到第三阶段")
        assertTrue(block.startsWith("<session_checkpoint>"))
        assertTrue(block.contains("做到第三阶段"))
        assertTrue(block.endsWith("</session_checkpoint>"))
    }

    @Test
    fun needsCompactionOnlyAfterTheBudget() {
        val short = longHistory(1, chars = 100)
        assertTrue(!needsCompaction(short, limit = 1_000))
        assertTrue(needsCompaction(longHistory(10, chars = 400), limit = 1_000))
    }
}
