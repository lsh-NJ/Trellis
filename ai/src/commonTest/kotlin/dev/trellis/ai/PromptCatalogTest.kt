package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 提示词里的「动作能带什么参数」只能从目录来。
 * 这条测试盯的是：目录里有的参数，提示词里得看得见 —— 模型不知道参数名就只能瞎猜。
 */
class PromptCatalogTest {
    @Test
    fun everyActionShowsWhatItNeeds() {
        val prompt = PromptBuilder.system(TEST_CATALOG)

        assertContains(prompt, "参数：title（必填）：任务标题")
        assertContains(prompt, "taskId（必填）：给哪个任务记")
        assertContains(prompt, "minutes（必填）：记多少分钟")
    }

    @Test
    fun anActionWithoutParamsSaysSoInsteadOfGoingSilent() {
        val bare = CapabilityCatalog(
            mountPoints = TEST_CATALOG.mountPoints,
            dataFields = TEST_CATALOG.dataFields,
            actions = listOf(CatalogAction("ping", "什么都不用给")),
        )

        val prompt = PromptBuilder.system(bare)
        assertTrue(
            prompt.contains("- ping：什么都不用给　参数：无"),
            "实际是: " + prompt.lines().filter { "ping" in it }.joinToString(" | "),
        )
    }
}