package dev.trellis.ai.steward

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 早间重排轮：只读、只给方案、用户点选后才写。
 * 同时钉住「不硬编码科目 / 方向」——重排哪门课由数据决定，不由提示词决定。
 */
class MorningRearrangePromptTest {

    private val prompt = MorningRearrangePrompt.systemPrompt()

    @Test
    fun itIsAnAutomaticMorningCheckNotAUserMessage() {
        assertTrue(prompt.contains("早间自动检查回合"), prompt)
        assertTrue(prompt.contains("不是用户发来的消息"), prompt)
        assertTrue(prompt.contains("用户刚打开应用"), prompt)
    }

    @Test
    fun theRoundToolsetIsReadOnly() {
        assertTrue(prompt.contains("read_plan"), prompt)
        assertTrue(prompt.contains("rearrangeExtraMinutes"), prompt)
        assertTrue(prompt.contains("ask_choices"), prompt)
        assertTrue(prompt.contains("本轮没有 update_plan"), prompt)
        assertTrue(prompt.contains("本轮没有") && prompt.contains("写工具"), prompt)
        assertTrue(prompt.contains("不要声称「已经排好"), prompt)
    }

    @Test
    fun itPreviewsSeveralExtraMinuteOptions() {
        for (minutes in listOf("0", "15", "30", "45")) {
            assertTrue(prompt.contains(minutes), "要把 $minutes 分钟这一档摆出来")
        }
        assertTrue(prompt.contains("重排预览"), prompt)
        assertTrue(prompt.contains("不改任何数据"), prompt)
    }

    @Test
    fun itExplainsWhatWillMove() {
        assertTrue(prompt.contains("逾期"), prompt)
        assertTrue(prompt.contains("整体后移"), prompt)
        assertTrue(prompt.contains("顺延到下周"), prompt)
        assertTrue(prompt.contains("多投"), prompt)
    }

    @Test
    fun itEndsWithAskChoicesAndRecommendsOne() {
        assertTrue(prompt.contains("2–3 个可执行选项"), prompt)
        assertTrue(prompt.contains("推荐项放第一个"), prompt)
    }

    @Test
    fun nothingToDoMeansSilence() {
        assertTrue(prompt.contains("没有逾期"), prompt)
        assertTrue(prompt.contains("不要为了说话而说话"), prompt)
        assertTrue(prompt.contains("直接结束"), prompt)
    }

    @Test
    fun theGuidanceNamesOnlyTheReadOnlyTools() {
        assertTrue(MorningRearrangePrompt.guidance.contains("read_plan"), MorningRearrangePrompt.guidance)
        assertTrue(MorningRearrangePrompt.guidance.contains("rearrangeExtraMinutes"), MorningRearrangePrompt.guidance)
        assertTrue(MorningRearrangePrompt.guidance.contains("ask_choices"), MorningRearrangePrompt.guidance)
        assertTrue(MorningRearrangePrompt.guidance.contains("不要直接改计划"), MorningRearrangePrompt.guidance)
    }

    @Test
    fun noHardcodedSubjectsOrDirections() {
        val banned = listOf(
            "数学", "英语", "物理", "化学", "生物", "历史", "地理",
            "Python", "Java", "Kotlin", "前端", "后端", "考研", "雅思", "托福",
            "机器学习", "深度学习", "数据结构", "算法", "RAG", "B 站", "bilibili",
        )
        for (word in banned) {
            assertFalse(prompt.contains(word), "提示词里不应出现具体科目/方向：$word")
            assertFalse(MorningRearrangePrompt.guidance.contains(word), "guidance 里不应出现具体科目/方向：$word")
        }
    }
}
