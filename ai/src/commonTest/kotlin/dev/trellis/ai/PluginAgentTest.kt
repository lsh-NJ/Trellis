package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PluginAgentTest {

    private fun agent(
        llm: LlmClient,
        installer: PluginInstaller = ScriptedInstaller(null),
        maxAttempts: Int = 3,
    ) = PluginAgent(llm, TEST_CATALOG, installer, maxAttempts)

    @Test
    fun installsOnTheFirstTry() = runTest {
        val installer = ScriptedInstaller(null)
        val outcome = agent(ScriptedLlm(goodJson()), installer).run("今天还剩多少事")

        assertEquals(AgentOutcome.Installed("ai.today", 1), outcome)
        assertEquals(1, installer.installedFiles.size)
        assertEquals(setOf("manifest.json", "main.dsl.json"), installer.installedFiles.single().keys)
    }

    /** prompt 里必须带着当前程序的能力清单，否则模型只能瞎猜。 */
    @Test
    fun theSystemPromptCarriesTheCatalog() = runTest {
        val llm = ScriptedLlm(goodJson())
        agent(llm).run("随便")

        val system = llm.seenMessages.first().first { it.role == LlmRole.System }.content
        assertTrue(system.contains("slot.home.section"), "挂载点必须在 prompt 里")
        assertTrue(system.contains("{{ summary.dueToday }}"), "数据字段必须在 prompt 里")
        assertTrue(system.contains("ai."), "id 前缀规则必须在 prompt 里")
    }

    /**
     * 这条测试是整个 harness 存在的理由：
     * **模型写错了，把报错喂回去，它改对了。**
     */
    @Test
    fun feedsTheValidationErrorBackAndSucceedsOnRetry() = runTest {
        val wrong = """{"id":"ai.x","name":"x","description":"x","slots":[{"point":"slot.homeSection","title":"t","body":"b"}]}"""
        val llm = ScriptedLlm(wrong, goodJson())
        val outcome = agent(llm).run("随便")

        assertEquals(AgentOutcome.Installed("ai.today", 2), outcome)

        // 第二次请求里必须带着「上次哪儿错了」
        val secondRequest = llm.seenMessages[1]
        val feedback = secondRequest.last().content
        assertTrue(feedback.contains("slot.homeSection"), "要点名错的那个")
        assertTrue(feedback.contains("slot.home.section"), "要给正确的选项")
    }

    /** 编造数据字段在**本地**就被挡下 —— 安装器一次都不该被调用。 */
    @Test
    fun inventedDataFieldsNeverReachTheInstaller() = runTest {
        val bad = """{"id":"ai.x","name":"x","description":"x","slots":[{"point":"slot.home.section","title":"t","body":"{{ summary.magic }}"}]}"""
        val installer = ScriptedInstaller(null)
        val outcome = agent(ScriptedLlm(bad, bad, bad), installer).run("随便")

        assertTrue(outcome is AgentOutcome.GaveUp)
        assertTrue(installer.installedFiles.isEmpty(), "本地自查没过就不该落地")
    }

    @Test
    fun aReplyWithNoJsonIsFedBack() = runTest {
        val llm = ScriptedLlm("抱歉，我做不到。", goodJson())
        val outcome = agent(llm).run("随便")

        assertEquals(AgentOutcome.Installed("ai.today", 2), outcome)
        assertTrue(llm.seenMessages[1].last().content.contains("没有找到 JSON 对象"))
    }

    /** 模型用 ``` 围栏包起来 —— 能救就救，不该浪费一轮。 */
    @Test
    fun markdownFencesAreTolerated() = runTest {
        val fenced = "```json\n" + goodJson() + "\n```"
        val outcome = agent(ScriptedLlm(fenced)).run("随便")
        assertEquals(AgentOutcome.Installed("ai.today", 1), outcome)
    }

    /** 安装器（内核那侧）失败也要喂回去。 */
    @Test
    fun installFailuresAreFedBack() = runTest {
        // 第一次失败、第二次成功（只传一个错误会让每次都失败）
        val installer = ScriptedInstaller("插件 id「ai.today」已经存在", null)
        val llm = ScriptedLlm(goodJson(), goodJson("ai.other"))
        val outcome = agent(llm, installer).run("随便")

        assertEquals(AgentOutcome.Installed("ai.other", 2), outcome)
        assertTrue(llm.seenMessages[1].last().content.contains("已经存在"))
    }

    @Test
    fun givesUpAfterMaxAttemptsAndKeepsTheLastProblems() = runTest {
        val bad = """{"id":"ai.x","name":"x","description":"x","slots":[]}"""
        val outcome = agent(ScriptedLlm(bad), maxAttempts = 3).run("随便")

        assertTrue(outcome is AgentOutcome.GaveUp)
        assertEquals(3, (outcome as AgentOutcome.GaveUp).attempts)
        assertTrue(outcome.problems.any { it.contains("slots 是空的") })
    }
}
