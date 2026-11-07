package dev.trellis.ai.manager

import dev.trellis.ai.TEST_CATALOG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 题库的完整性 + 防泄漏。
 *
 * 防泄漏这条和插件作者题库同一个理由：验收题如果出现在提示词里，量到的是背题能力。
 * 往静态前缀里抄验收题的人，会在这里被拦下。
 */
class ManagerEvalPromptsTest {

    @Test
    fun theAdminBankHasTwelveDistinctCases() {
        val prompts = ManagerEvalPrompts.all
        assertEquals(12, prompts.size, "管理员验收题库要保持 12 题（与 docs/eval-prompts.md 对齐）")
        assertTrue(prompts.all { it.input.isNotBlank() && it.expectation.isNotBlank() })
        assertEquals(
            prompts.size,
            prompts.map { it.input }.toSet().size,
            "题目不能重复：" + prompts.map { it.input },
        )
        val grouped = ManagerEvalPrompts.groups.values.flatten()
        assertEquals(prompts.indices.toList(), grouped.sorted(), "分组要覆盖全部题目且不重不漏")
    }

    @Test
    fun theAdminBankDoesNotLeakIntoThePrompt() {
        val base = ManagerPrompt.buildBase(TEST_CATALOG, emptyList(), "")
        for (prompt in ManagerEvalPrompts.all) {
            assertTrue(prompt.input !in base, "验收题出现在静态前缀里了：${prompt.input}")
        }
    }
}
