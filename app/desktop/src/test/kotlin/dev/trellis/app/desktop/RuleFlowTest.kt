package dev.trellis.app.desktop

import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.domain.TaskQuery
import dev.trellis.domain.TaskStatus
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        runtime.llmOverride = OneShotLlm(rulePlugin)

        val outcome = runtime.generatePlugin("完成一项就自动帮我建一条复习")
        assertIs<AgentOutcome.Installed>(outcome)

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
}
