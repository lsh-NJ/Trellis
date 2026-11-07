package dev.trellis.ai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 把真正发给模型的提示词落成文件，好让 lint 脚本和人眼都能检查。
 * 这不是断言提示词的措辞——措辞会变；它守的是预算和「装好两个插件后」这个最常见的样子。
 */
class PromptSnapshotTest {
    @Test
    fun dumpThePromptAndKeepItInsideItsBudget() {
        val prompt = PromptBuilder.system(
            TEST_CATALOG,
            listOf(
                ExistingPlugin("ai.today", "今日待办", "一眼看到今天还剩多少事"),
                ExistingPlugin("ai.encourage", "坚持鼓励", "用真实进度给你一句鼓励"),
            ),
        )

        val out = File("build/prompt-snapshot.txt")
        out.parentFile?.mkdirs()
        out.writeText(prompt)

        println("提示词 ${prompt.length} 字符 -> ${out.absolutePath}")
        // 上限从 9k → 12k → 12.5k：脚本例子、身份与「插件说明书」框架各占了一点。
        // 例子比叮嘱管用，但不能无节制地加；再涨就要先删旧内容，而不是继续放预算。
        assertTrue(prompt.length in 4_500..12_500, "提示词 ${prompt.length} 字符，超出 4.5k~12.5k 预算")
    }
}