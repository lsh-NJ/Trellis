package dev.trellis.app.desktop

import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 按剧本一句一句往下念的假模型。念完了就一直是最后一句。 */
private class QueuedLlm(private val replies: List<String>) : LlmClient {
    var calls = 0
        private set

    override suspend fun complete(messages: List<LlmMessage>): String {
        val reply = replies[minOf(calls, replies.lastIndex)]
        calls++
        return reply
    }
}

/**
 * 它很容易「看了一圈，然后说自己记得的那件事已经装好了」。
 * 这时界面上会写「它没有装」—— 不能让它用一句自己编的话冒充做完了。
 */
class LyingAgentTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File = File.createTempFile("trellis-lying-", "").let {
        it.delete()
        it.mkdirs()
        tempDirs += it
        it
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun sayingItIsAlreadyInstalledIsNotTheSameAsHavingDoneIt() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        runtime.llmOverride = QueuedLlm(
            listOf(
                """{"tool":"list_plugins","arguments":{}}""",
                """{"done":true,"message":"「本周读书页数」已经装好了，不用再动"}""",
                """{"cannotDo":"装不了，我手上没有这个东西"}""",
            ),
        )
        runtime.researchFirst = true

        val outcome = runtime.generatePlugin("我要一个显示这周读了几页书的卡片")

        assertFalse(outcome is AgentOutcome.Installed, "什么都没装，不能报装好了：" + outcome)
        assertIs<AgentOutcome.Refused>(outcome)
        // 关键是它没有把「已经装好了」当成结论交出去：这次什么都没装，就只能是不做。
        assertTrue(runtime.agentLog.isNotEmpty(), "它得说点什么，不能静默失败")
    }
}
