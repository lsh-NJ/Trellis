package dev.trellis.ai.steward

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自主轮提示词钉住的是「这一轮能做什么、不能做什么」：
 * 只读工具 + 只给建议 + 没事就 rest，写操作留给用户确认后的下一轮。
 * 同时钉住「不许硬编码科目」——学什么由用户的数据决定，不由提示词决定。
 */
class ProactivePromptTest {

    private val prompt = ProactivePrompt.systemPrompt()

    @Test
    fun itIsAnAutonomousCheckNotAnOrdinaryChat() {
        assertTrue(prompt.contains("这不是一次普通聊天"), prompt)
        assertTrue(prompt.contains("自主检查"), prompt)
        assertTrue(prompt.contains("用户不在对话页"), prompt)
    }

    @Test
    fun theRoundToolsetIsReadOnly() {
        for (tool in listOf(
            "read_learning_overview",
            "read_knowledge_overview",
            "read_plan",
            "list_goals",
            "recall_memory",
            "search_web",
            "search_exercises",
            "recommend_exercises",
        )) {
            assertTrue(prompt.contains(tool), "自主轮要说明有哪些工具：$tool")
        }
        assertTrue(prompt.contains("写操作本轮不在工具集里"), prompt)
    }

    @Test
    fun everyChangeGoesThroughSuggestionAndAskChoices() {
        assertTrue(prompt.contains("只建议，不直接改"), prompt)
        assertTrue(prompt.contains("ask_choices"), prompt)
        assertTrue(prompt.contains("下一轮普通轮才会真的执行"), prompt)
        assertTrue(prompt.contains("绝不要说「已经帮你建好"), prompt)
    }

    @Test
    fun knowledgeNetworkMeansAlreadyMastered() {
        assertTrue(prompt.contains("知识网络"), prompt)
        assertTrue(prompt.contains("默认表示用户已经了解、基本掌握"), prompt)
        assertTrue(prompt.contains("能力证据"), prompt)
        assertTrue(prompt.contains("不要建议重复学习"), prompt)
        assertTrue(prompt.contains("向上、向外"), prompt)
    }

    @Test
    fun aCompletedStageEndsInPracticeOrRest() {
        assertTrue(prompt.contains("通识课"), prompt)
        assertTrue(prompt.contains("recommend_exercises"), prompt)
        assertTrue(prompt.contains("propose_practice"), prompt)
        assertTrue(prompt.contains("rest"), prompt)
        assertTrue(prompt.contains("不要为了说话而说话"), prompt)
    }

    @Test
    fun aCompletedPlanGetsAConcreteNextStepBeforeAsking() {
        assertTrue(prompt.contains("下一步方案"), prompt)
        assertTrue(prompt.contains("为什么是它"), prompt)
        assertTrue(prompt.contains("阶段与周次"), prompt)
        assertTrue(prompt.contains("第一周具体做什么"), prompt)
        assertTrue(prompt.contains("recall_memory"), prompt)
    }

    @Test
    fun aCompletedPlanStaysDoneAndExpansionOpensANewPlan() {
        // 旧计划完成就保持完成：拓展 / 进阶内容进新计划，不往旧计划里继续排。
        assertTrue(prompt.contains("旧计划已经完成"), prompt)
        assertTrue(prompt.contains("保持完成状态"), prompt)
        assertTrue(prompt.contains("不再往里面追加单元、不再排周、不再改节奏"), prompt)
        assertTrue(prompt.contains("新计划"), prompt)
        assertTrue(prompt.contains("update_plan"), prompt)
        assertTrue(prompt.contains("insert plan"), prompt)
        assertTrue(prompt.contains("goalId"), prompt)
        assertTrue(prompt.contains("不塞进已完成的那份计划里"), prompt)
        assertTrue(prompt.contains("这一轮不要声称已经建好"), prompt)
        // 不能留下「在原计划上继续排」的口径。
        assertFalse(prompt.contains("在原计划上继续"), prompt)
        assertFalse(prompt.contains("往已完成计划里加"), prompt)
        assertFalse(prompt.contains("旧计划里继续排"), prompt)
    }

    @Test
    fun stageCompletionNeverClaimsTheScheduleIsAlreadyChanged() {
        assertTrue(prompt.contains("也不要说「已经排好」"), prompt)
        assertTrue(prompt.contains("新开计划口径"), prompt)
        assertFalse(prompt.contains("已排好"), prompt)
    }

    @Test
    fun guidanceSaysExpansionNeedsANewPlan() {
        val guidance = ProactivePrompt.guidance
        assertTrue(guidance.contains("新开一份计划"), guidance)
        assertTrue(guidance.contains("不往已经完成的计划里加"), guidance)
    }

    @Test
    fun thePromptHardcodesNoSubjectsOrDirections() {
        // 学什么由用户的数据和记忆决定；提示词一旦写死科目，别的方向就会被带偏。
        for (subject in listOf(
            "数学", "英语", "物理", "化学", "生物", "历史", "地理",
            "Python", "Java", "Kotlin", "编程", "前端", "后端",
            "考研", "雅思", "托福", "机器学习", "深度学习", "数据结构", "算法",
        )) {
            assertFalse(prompt.contains(subject), "提示词里不该硬编码具体科目/方向：$subject")
        }
    }

    @Test
    fun theOverlayIsDeterministic() {
        assertEquals(prompt, ProactivePrompt.systemPrompt())
    }

    @Test
    fun guidanceNamesTheCapabilitiesAndKeepsTheSuggestionRule() {
        val guidance = ProactivePrompt.guidance
        assertTrue(guidance.isNotBlank())
        for (tool in listOf(
            "read_learning_overview",
            "read_knowledge_overview",
            "search_web",
            "recommend_exercises",
            "propose_practice",
            "ask_choices",
        )) {
            assertTrue(guidance.contains(tool), "常驻说明要提到：$tool")
        }
        assertTrue(guidance.contains("必须先建议"), guidance)
    }

    @Test
    fun guidanceCarriesNoImplementationDetails() {
        val guidance = ProactivePrompt.guidance
        for (detail in listOf("AppRuntime", "buildChatRig", "SessionKind", "ChatStore")) {
            assertFalse(guidance.contains(detail), "常驻说明不写实现细节：$detail")
        }
    }
}
