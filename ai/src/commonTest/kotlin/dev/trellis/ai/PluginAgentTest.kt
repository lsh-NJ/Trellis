package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

    /**
     * 模型老实说「做不到」时必须被当成**好结局**：
     * 不重试、不落地、不糊弄用户。
     *
     * 这条守的是第二次真实实验暴露的问题：
     * 用户要「按精力曲线安排复习」，模型生成了三张写着
     * 「按精力高低排顺序」的只读卡片 —— 一个动作都没做，读起来却像做好了。
     */
    @Test
    fun aRefusalIsAValidOutcomeAndInstallsNothing() = runTest {
        val refusal = """{"cannotDo":"自动提醒需要定时执行动作，而我现在只能显示卡片，做不到。"}"""
        val installer = ScriptedInstaller(null)
        val llm = ScriptedLlm(refusal)

        val outcome = agent(llm, installer).run("我没完成任务时自动发通知催我")

        val refused = assertIs<AgentOutcome.Refused>(outcome)
        assertTrue(refused.reason.contains("自动提醒"), "要把理由原样带出来：" + refused.reason)
        assertEquals(1, refused.attempts, "拒绝是一次就成的，不该重试")
        assertEquals(1, llm.calls, "拒绝之后不该再请求模型")
        assertTrue(installer.installedFiles.isEmpty(), "拒绝就不该落地")
    }

    /** prompt 必须把「做不到什么」写死，否则模型只会硬凑一张卡。 */
    @Test
    fun theSystemPromptSaysWhatItCannotDo() = runTest {
        val llm = ScriptedLlm(goodJson())
        agent(llm).run("随便")

        val system = llm.seenMessages.first().first { it.role == LlmRole.System }.content
        assertTrue(system.contains("你做不到什么"), "要有专门的一节")
        assertTrue(system.contains("修改数据"), "要说清楚不能改数据")
        assertTrue(system.contains("不能承诺任何没有发生的动作"), "要有这条硬规则")
        assertTrue(system.contains("cannotDo"), "要告诉它拒绝的写法")
    }


    /**
     * prompt 必须教会模型区分「有价值的降级」和「原样抄一遍」。
     *
     * 真实踩到的例子：用户要「按精力曲线安排复习」，模型生成了
     * 「今天到期 0 项，还剩 2 项」—— 而这两个数字界面上本来就有。
     * 它退了一步，但退出来的东西毫无增量，而且**没说**自己退了。
     */
    @Test
    fun theSystemPromptExplainsWhenADowngradeIsWorthless() = runTest {
        val llm = ScriptedLlm(goodJson())
        agent(llm).run("随便")

        val system = llm.seenMessages.first().first { it.role == LlmRole.System }.content

        assertTrue(system.contains("原样抄一遍"), "要明确说「抄一遍不如不做」")
        assertTrue(system.contains("换一个说法"), "要给出有价值的降级长什么样")
        assertTrue(system.contains("limitation"), "要告诉它退步必须写下来")
        assertTrue(system.contains("不吭声"), "要点明不吭声比拒绝更糟")
    }

    /** 带 limitation 的插件也要能正常装上 —— 它是合法输出，不是错误。 */
    @Test
    fun aDowngradeWithALimitationInstallsFine() = runTest {
        val downgraded = """{"id":"ai.review","name":"复习进度","description":"显示复习数量","howToUse":"在首页看","limitation":"你要的是安排复习，我只做了显示数量，没有安排。","slots":[{"point":"slot.home.section","title":"今天的复习","body":"还剩 {{ summary.todo }} 项"}]}"""
        val installer = ScriptedInstaller(null)

        val outcome = agent(ScriptedLlm(downgraded), installer).run("按我的精力曲线安排复习")

        assertEquals(AgentOutcome.Installed("ai.review", 1), outcome)
        assertEquals(1, installer.installedFiles.size)
        // limitation 必须真的写进 manifest，否则界面上看不见
        val manifest = installer.installedFiles.single()["manifest.json"].orEmpty()
        assertTrue(manifest.contains("limitation"), "退步说明要落进 manifest：" + manifest)
        assertTrue(manifest.contains("没有安排"), "内容也要带上：" + manifest)
    }

}
