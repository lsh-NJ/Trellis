package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * 对话页整条链路的接线测试。
 *
 * 这里故意用假模型：真模型会把「你好」回答得很长，而我们要钉的是**接线**——
 * 用户那一行有没有进列表、流式的字有没有拼回同一行、结束之后 busy 有没有放开。
 * 模型说什么不重要。
 */
class ChatManagerTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-chat-", "").let {
            it.delete(); it.mkdirs(); tempDirs += it; it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private class StreamingFake(private val full: String) : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String = full

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
        ): String {
            full.chunked(2).forEach { onDelta(it) }
            return full
        }
    }

    /** 永远不回的模型：用来验证「停止」这条路。 */
    private class StuckFake : LlmClient {
        override suspend fun complete(messages: List<LlmMessage>): String = awaitCancellation()

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
        ): String = awaitCancellation()
    }

    @Test
    fun aChatTurnLandsInTheConversationAndUnlocksTheInput() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        runtime.llmOverride = StreamingFake("你好，我是这里的管理员。")

        runtime.sendChat("你好")

        val lines = runtime.chat.toList()
        assertTrue(lines.isNotEmpty(), "对话里应当至少有两行")
        assertEquals(ChatRole.User, lines.first().role)
        assertEquals("你好", lines.first().text)

        val answer = lines.filter { it.role == ChatRole.Assistant }.last()
        assertEquals("你好，我是这里的管理员。", answer.text)
        assertEquals(ChatRole.Status, lines.last().role, "一轮末尾必须有一条界标，用户才知道说完了")
        assertTrue(lines.last().text.contains("完成"), "界标要写清这轮是完成还是中断")
        assertFalse(runtime.chatBusy, "这一轮跑完必须把输入放开")
    }

    @Test
    fun askingWhatIsInstalledIsStillJustChat() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        runtime.llmOverride = StreamingFake("现在装了三个插件。")

        runtime.sendChat("你好")

        // 没有工具调用、没有过程行混进正式对话：纯聊天就是「你 → 管理员 → 界标」。
        assertEquals(
            listOf(ChatRole.User, ChatRole.Assistant, ChatRole.Status),
            runtime.chat.map { it.role },
        )
    }

    /**
     * 停止必须留痕。
     *
     * 这一轮跑在 runtime 的 scope 上（不是聊天页的 scope），所以切页不会掐断它；
     * 能掐断它的只有「停止」和程序退出。掐断之后界面要写「已停止」，
     * 而不是让半截回答挂在那里、让人猜它是不是还在想。
     */
    @Test
    fun stoppingATurnLeavesAMarkerAndUnlocksTheInput() = runBlocking {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        runtime.llmOverride = StuckFake()

        runtime.ask("你好")
        withTimeout(5_000) { while (!runtime.chatBusy) delay(20) }

        runtime.stopChat()
        withTimeout(5_000) { while (runtime.chatBusy) delay(20) }

        assertEquals(ChatRole.Status, runtime.chat.last().role)
        assertEquals("—— 已停止", runtime.chat.last().text)
        runtime.shutdown()
    }
}
