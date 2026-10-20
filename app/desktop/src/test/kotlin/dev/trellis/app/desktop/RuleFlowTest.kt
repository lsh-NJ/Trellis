package dev.trellis.app.desktop

import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.TaskQuery
import dev.trellis.kernel.approval.DefaultApprovalGate
import dev.trellis.domain.TaskStatus
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

private class OneShotLlm(private val reply: String) : LlmClient {
    override suspend fun complete(messages: List<LlmMessage>): String = reply
}

/**
 * 「完成一项 -> 自动新建一条复习任务」。
 *
 * 这是整个项目从「会显示」走到「会做事」的那条线：
 *   一句人话 -> 生成插件 -> 插件声明规则 -> 完成任务 -> 规则跑起来 -> 数据真的变了
 *
 * 全程没有一行插件自己写的代码，它只是**选了一个宿主提供的动作**。
 */
class RuleFlowTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-rule-", "").let {
            it.delete(); it.mkdirs(); tempDirs += it; it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private val rulePlugin = """
        {
          "id": "ai.review",
          "name": "完成即复习",
          "description": "完成一项任务后，自动建一条复习任务",
          "howToUse": "正常完成任务就行，复习任务会自己出现",
          "slots": [
            { "point": "slot.home.section", "title": "复习助手",
              "body": "已完成 {{ summary.done }} 项" }
          ],
          "rules": [
            { "on": "task.completed",
              "do": [ { "action": "addTask",
                        "args": { "title": "复习：{{ event.title }}" } } ] }
          ]
        }
    """.trimIndent()

    @Test
    fun completingATaskTriggersTheGeneratedRule() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )

        val runtime = AppRuntime.create(home)
        // 审批策略是 ask：给一个自动点头的处理器，把审批流程本身留给它自己的测试。
        (runtime.kernel.approvals as? DefaultApprovalGate)?.setManualHandler { true }
        runtime.llmOverride = OneShotLlm(rulePlugin)

        val outcome = runtime.generatePlugin("完成一项就自动帮我建一条复习")
        assertTrue(
            outcome is AgentOutcome.Installed,
            "应当装好，实际：" + outcome + "；agentLog：" + runtime.agentLog.joinToString(" | "),
        )

        // 造一项任务，然后把它完成 —— 这一步会广播 task.completed
        val original = runtime.planService.addTask("第 1 章 计算机组成")
        runtime.planService.setStatus(original.id, TaskStatus.DONE)

        val titles = runtime.planService.tasks(TaskQuery(includeDone = true)).map { it.title }

        assertTrue(
            titles.any { it == "复习：第 1 章 计算机组成" },
            "规则应当真的建出一条复习任务，实际任务列表：" + titles,
        )
        // 原来那条还在，规则是「新增」不是「替换」
        assertTrue(titles.contains("第 1 章 计算机组成"), "原任务不该被吃掉：" + titles)

        runtime.shutdown()
    }

    /** 重复把同一条设成完成，不该重复触发规则。 */
    @Test
    fun completingTwiceDoesNotTriggerTwice() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )

        val runtime = AppRuntime.create(home)
        (runtime.kernel.approvals as? DefaultApprovalGate)?.setManualHandler { true }
        runtime.llmOverride = OneShotLlm(rulePlugin)
        runtime.generatePlugin("随便")

        val original = runtime.planService.addTask("第 1 章")
        runtime.planService.setStatus(original.id, TaskStatus.DONE)
        runtime.planService.setStatus(original.id, TaskStatus.DONE)
        runtime.planService.setStatus(original.id, TaskStatus.DONE)

        val reviews = runtime.planService.tasks(TaskQuery(includeDone = true))
            .count { it.title.startsWith("复习：") }

        assertEquals(1, reviews, "连点三下只该建一条复习任务")
        runtime.shutdown()
    }

    /**
     * 护栏：审批门拒绝时，规则动作**必须不执行**。
     *
     * 这一条是整条「让插件做事」路线上的安全底线。
     * 没有它，一条自动生成的规则就能在没人看着的时候改你的数据。
     */
    @Test
    fun aDeniedApprovalBlocksTheRuleAction() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )

        val runtime = AppRuntime.create(home)
        // 审批门一律摇头
        (runtime.kernel.approvals as? DefaultApprovalGate)?.setManualHandler { false }

        runtime.llmOverride = OneShotLlm(rulePlugin)
        assertIs<AgentOutcome.Installed>(runtime.generatePlugin("完成一项就自动建复习"))

        val original = runtime.planService.addTask("第 1 章")
        runtime.planService.setStatus(original.id, TaskStatus.DONE)

        val titles = runtime.planService.tasks(TaskQuery(includeDone = true)).map { it.title }

        assertTrue(
            titles.none { it.startsWith("复习：") },
            "审批被拒之后不该建出复习任务，实际：" + titles,
        )
        assertTrue(
            runtime.kernel.errors.any { it.detail.contains("审批门拒绝") },
            "拒绝原因要留在诊断里：" + runtime.kernel.errors.map { it.detail },
        )

        runtime.shutdown()
    }

    /**
     * 规则动手之前，**界面必须收到一个待批准的请求**。
     *
     * 这条测的是那句承诺：「规则是后台触发的，执行前会请用户确认一次」。
     * 如果它不成立，用户什么都没看见、数据却变了 —— 那比「做不到」坏得多。
     *
     * 刻意**不**替换处理器：上面几条把审批门换成自动点头是为了测规则本身，
     * 那是有意的屏蔽。这一条要的就是真实的那条路径。
     */
    @Test
    fun theRuleAsksBeforeItTouchesAnything() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )

        val runtime = AppRuntime.create(home)
        runtime.llmOverride = OneShotLlm(rulePlugin)
        assertIs<AgentOutcome.Installed>(runtime.generatePlugin("完成一项就自动建复习"))

        val original = runtime.planService.addTask("第 1 章")

        // 完成会卡在审批上，所以丢到后台
        val job = launch { runtime.planService.setStatus(original.id, TaskStatus.DONE) }
        advanceUntilIdle()

        val pending = runtime.pendingApproval
        assertNotNull(pending, "规则要改数据，界面必须收到待批准请求，否则用户什么都看不见")
        assertEquals("智能体", if (pending.actor == dev.trellis.kernel.audit.Actor.AGENT) "智能体" else "你")
        assertEquals("rule.addTask", pending.action)

        val before = runtime.planService.tasks(TaskQuery(includeDone = true)).map { it.title }
        assertTrue(before.none { it.startsWith("复习：") }, "批准之前不该动手：" + before)

        runtime.answerApproval(true)
        advanceUntilIdle()
        job.join()

        val after = runtime.planService.tasks(TaskQuery(includeDone = true)).map { it.title }
        assertTrue(after.any { it == "复习：第 1 章" }, "批准之后才执行：" + after)

        runtime.shutdown()
    }

}