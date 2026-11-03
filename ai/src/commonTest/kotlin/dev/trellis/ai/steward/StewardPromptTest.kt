package dev.trellis.ai.steward

import dev.trellis.ai.LlmRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 管家的提示词与确定性模板：模板和给模型的事实必须同源，语气克制。 */
class StewardPromptTest {

    private fun morningContext() = StewardContext(
        dayLabel = "11月4日 周二",
        planTitle = "Linux 编程速通",
        plannedToday = 3,
        doneToday = 0,
        todayMinutes = 90,
        overdueThisWeek = listOf(StewardOverdueDay("11月3日 周一", 2)),
        backlogCount = 5,
        remainingMinutesInWeek = 240,
    )

    private fun eveningContext() = StewardContext(
        dayLabel = "11月4日 周二",
        planTitle = "Linux 编程速通",
        plannedToday = 3,
        doneToday = 1,
        todayMinutes = 90,
        overdueThisWeek = listOf(StewardOverdueDay("11月3日 周一", 2)),
        backlogCount = 5,
        remainingMinutesInWeek = 240,
    )

    @Test
    fun morningTemplateUsesTheFactsAndEndsWithAQuestion() {
        val text = StewardPrompt.template(StewardRitual.Morning, morningContext())
        assertTrue(text.startsWith("早上好。"), text)
        assertTrue(text.contains("今天「Linux 编程速通」有 3 件、约 90 分钟。"), text)
        assertTrue(text.contains("本周还有没做完的：11月3日 周一 2 件。"), text)
        assertTrue(text.contains("更早欠下 5 个"), text)
        assertTrue(text.contains("先做哪一件？"), text)
    }

    @Test
    fun eveningTemplateReportsWhatIsLeft() {
        val text = StewardPrompt.template(StewardRitual.Evening, eveningContext())
        assertTrue(text.startsWith("今天结束了。"), text)
        assertTrue(text.contains("计划 3 件，完成 1 件。"), text)
        assertTrue(text.contains("还有 2 件没做"), text)
        assertTrue(text.contains("挪到明天的排期里"), text)
    }

    @Test
    fun mergedRitualPutsTheCatchUpFirstAndStillGreets() {
        val context = morningContext().copy(catchUpEvening = "昨晚完成 2 件、剩 1 件。")
        val text = StewardPrompt.template(StewardRitual.MorningWithEveningCatchUp, context)
        assertTrue(text.startsWith("昨晚完成 2 件、剩 1 件。"), text)
        assertTrue(text.contains("早上好。"), text)
    }

    @Test
    fun factsTextCarriesTheNumbersTheModelNeeds() {
        val facts = StewardPrompt.factsText(StewardRitual.Morning, morningContext())
        assertTrue(facts.contains("应用内日期：11月4日 周二"), facts)
        assertTrue(facts.contains("当前计划：Linux 编程速通"), facts)
        assertTrue(facts.contains("今天计划：3 件、约 90 分钟"), facts)
        assertTrue(facts.contains("本周还没有做的排期：11月3日 周一 2 件"), facts)
        assertTrue(facts.contains("更早欠下、还没安排进任何一周的节点：5 个"), facts)
        assertTrue(facts.contains("本周还剩约 240 分钟可投入"), facts)
    }

    @Test
    fun systemPromptIsRestrained() {
        val prompt = StewardPrompt.systemPrompt()
        assertTrue(prompt.contains("2–4 句"), prompt)
        assertTrue(prompt.contains("不说教、不用感叹号"), prompt)
        assertTrue(prompt.contains("不超过 120 个字"), prompt)
        assertTrue(prompt.contains("不要替用户执行任何写操作"), prompt)
    }

    @Test
    fun messagesStartWithTheSystemPromptAndEndWithTheFacts() {
        val messages = StewardPrompt.messages(StewardRitual.Morning, morningContext())
        assertEquals(2, messages.size)
        assertEquals(LlmRole.System, messages.first().role)
        assertEquals(LlmRole.User, messages.last().role)
        assertTrue(messages.last().content.contains("今天计划：3 件"), messages.last().content)
    }

    @Test
    fun noTemplateShouts() {
        listOf(
            StewardPrompt.template(StewardRitual.Morning, morningContext()),
            StewardPrompt.template(StewardRitual.Evening, eveningContext()),
        ).forEach { text -> assertFalse(text.contains("！"), text) }
    }
}
