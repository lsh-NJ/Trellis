package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertTrue

/** 具名零件从模型手里出来、进到 DSL 文件里，中间不能被吞掉。 */
class SlotPartsTest {

    private fun plugin(
        stat: AuthoredStat? = null,
        titleTone: String = "",
        tone: String = "",
    ): AuthoredPlugin = AuthoredPlugin(
        id = "ai.stat",
        name = "今天到期",
        description = "把今天到期的事单独拎出来看",
        howToUse = "在首页看",
        slots = listOf(
            AuthoredSlot(
                point = "slot.home.section",
                title = "今天到期",
                stat = stat,
                titleTone = titleTone,
                actions = listOf(
                    AuthoredAction(
                        action = "addTask",
                        args = mapOf("title" to "临时"),
                        label = "加一条",
                        tone = tone,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun namedPartsEndUpInTheEntryFile() {
        val files = plugin(AuthoredStat("{{ summary.dueToday }}", "项", "今天到期", "danger"))
            .toFiles(TEST_CATALOG)
        val entry = files.getValue("main.dsl.json")

        assertTrue(entry.contains("\"stat\""), entry)
        assertTrue(entry.contains("summary.dueToday"), entry)
        assertTrue(entry.contains("\"tone\": \"danger\""), entry)
    }

    @Test
    fun aBogusToneIsCaughtBeforeItReachesTheKernel() {
        val problems = plugin(titleTone = "红色").validate(TEST_CATALOG)

        assertTrue(problems.any { it.contains("titleTone") }, problems.toString())
    }

    @Test
    fun aRealTonePassesSelfCheck() {
        val problems = plugin(titleTone = "danger", tone = "accent").validate(TEST_CATALOG)

        assertTrue(problems.isEmpty(), problems.toString())
    }
}
