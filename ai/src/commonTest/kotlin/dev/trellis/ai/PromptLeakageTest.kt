package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 守一件事：送检的话不能出现在提示词里。
 * 提示词里的例子是训练集，HeldOutPrompts 是测试集，两边串味，验出来的分数就是假的。
 */
class PromptLeakageTest {
    private val prompt = PromptBuilder.system(TEST_CATALOG)

    private fun examples(): List<String> =
        Regex("<input>(.*?)</input>", RegexOption.DOT_MATCHES_ALL)
            .findAll(prompt)
            .map { it.groupValues[1].trim() }
            .toList()

    @Test
    fun noHeldOutRequestAppearsInThePrompt() {
        HeldOutPrompts.all.forEach { request ->
            assertFalse(
                prompt.contains(request),
                "送检请求「" + request + "」出现在提示词里了 —— 那它就不是测试集，是背下来的答案",
            )
        }
    }

    @Test
    fun theExamplesAndTheHeldOutSetDoNotCollide() {
        val shared = HeldOutPrompts.all.filter { it in examples() }
        assertTrue(shared.isEmpty(), "这几句话既当例子又当考题：" + shared)
    }

    @Test
    fun thePromptKeepsItsOwnExamples() {
        // 把例子删光也能让上面两条变绿，但那是拆掉 few-shot 换一个好看的分数
        val count = examples().size
        assertTrue(count >= 4, "提示词里的例子只剩 " + count + " 条，few-shot 已经名存实亡")
    }
}