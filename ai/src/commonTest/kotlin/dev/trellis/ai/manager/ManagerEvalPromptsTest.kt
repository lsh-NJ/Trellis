package dev.trellis.ai.manager

import dev.trellis.ai.TEST_CATALOG
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 题库的完整性 + 防泄漏。
 *
 * 防泄漏这条和插件作者题库同一个理由：验收题如果出现在提示词里，量到的是背题能力。
 * 往通用工作流里抄验收题的人，会在这里被拦下。
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
        // 分组要覆盖全部 12 条，且每条只属于一组。
        val grouped = ManagerEvalPrompts.groups.values.flatten()
        assertEquals(prompts.indices.toList(), grouped.sorted(), "分组要覆盖全部题目且不重不漏")
    }

    @Test
    fun theAdminBankDoesNotLeakIntoThePrompt() {
        val legacy = ManagerPrompt.buildBase(TEST_CATALOG, emptyList(), "")
        val stable = ManagerPrompt.buildBase(TEST_CATALOG, emptyList(), "", stableTools = true)

        // 只查静态前缀：旧版按意图的任务模块里本来就把真实事故原话当例子写
        //（「我要速通操作系统」就在 find_resource / plan_manage 的用例里），
        // 那套模块 P3 会删；A/B 要量的是「新统一前缀不背题」，所以不拿旧模块当泄漏。
        for (prompt in ManagerEvalPrompts.all) {
            val sentence = prompt.input
            assertTrue(sentence !in legacy, "验收题出现在旧静态前缀里了：$sentence")
            assertTrue(sentence !in stable, "验收题出现在稳定模式静态前缀里了：$sentence")
        }
    }
}
