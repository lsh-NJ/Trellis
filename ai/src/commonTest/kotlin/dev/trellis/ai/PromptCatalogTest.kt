package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * 提示词里的「动作能带什么参数」只能从目录来。
 * 这条测试盯的是：目录里有的参数，提示词里得看得见 —— 模型不知道参数名就只能瞎猜。
 */
class PromptCatalogTest {

    /**
     * 「显示」和「记下来」是两道题：前者卡片能做，后者只有脚本能做。
     * 这条规则必须排在所有其他判断前面，否则模型会先想「能不能用卡片」，然后退而求其次。
     */
    @Test
    fun aRequestThatWritesSomethingIsRoutedToScript() {
        val prompt = PromptBuilder.system(TEST_CATALOG)
        val rule = prompt.indexOf("用户要不要往里写东西")
        check(rule >= 0) { "提示词里没有这条路线判断" }
        check(rule < prompt.indexOf("<when_to_write_code>")) { "路线判断应该排在写代码的教学前面" }
        assertTrue(prompt.contains("卡片没有输入框，也没有抽屉"), "要把原因说清楚，不然模型只记住结论")
        assertTrue(prompt.contains("统计我做过的事"), "要把「写东西」翻译成模型认得出的说法")
        // 「没有这个字段」不是拒绝的理由，是该补一个能记的地方。
        // 少了这一句，模型会用它自己的推理推出「做不到」，然后礼貌地交白卷。
        assertTrue(
            prompt.contains("那不算做不到，那是轮到你来补"),
            "缺数据时要引导它去补，而不是引它去拒绝",
        )
    }
    /**
     * 「做满」必须排在「退」前面，退步还得是一条能照着走的阶梯。
     *
     * 真实踩到的例子：提示词里「做不到」写了三遍，「退一步」写得比「能做」还细，
     * 模型于是先想「这会不会重复」，把一次能做的交互退成一串早就见过的数字。
     * 这里要教的是：能做的先做满，真做不到才沿阶梯往下走一级，而不是停在拒绝上。
     */
    @Test
    fun doingItFullyIsTaughtBeforeRetreating() {
        val prompt = PromptBuilder.system(TEST_CATALOG)

        val full = prompt.indexOf("做满，再谈退")
        check(full >= 0) { "提示词里没有「做满再谈退」的顺序" }
        val ladder = prompt.indexOf("退步不是失败")
        check(ladder > full) { "先教做满，再教退步" }
        assertTrue(prompt.contains("改交互"), "退步阶梯要落到具体动作，不能只是一句态度")
        assertTrue(prompt.contains("改触发"), "触发方式也是能退的一级")

        val boundary = prompt.indexOf("边界，不是禁令")
        check(boundary > ladder) { "边界清单要排在退步阶梯后面，且点明它是边界不是禁令" }
    }

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