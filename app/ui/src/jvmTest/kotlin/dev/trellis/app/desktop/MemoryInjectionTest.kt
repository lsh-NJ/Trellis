package dev.trellis.app.desktop

import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.ai.steward.StewardPrompt
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 「跨会话记忆」注入链路的接线测试。
 *
 * 单独测 `memoryBlock()` 的纯函数（见 `MemoryBlockTest`）只证明块拼得对；
 * 这里钉的是另一件事：`sendChat` 真的把它塞进了**两条**提示词路径 ——
 * 普通管理员会话（按意图拼前缀）和管家会话（稳定前缀）。
 *
 * 和 `ChatManagerTest` 一样用假模型：真模型会改写回答，而我们要看的是
 * 发给它的 messages 里有没有记忆块、guidance 里有没有点名两只工具。
 */
class MemoryInjectionTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-memory-injection-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    private fun writeFakeLlmConfig(home: File) {
        // invalid endpoint：即使某条路径漏了 llmOverride，也只会失败，不会真的连网。
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** 只记录「模型看到了什么」，然后给一句固定回答。 */
    private class CapturingFake(private val reply: String = "好的。") : LlmClient {
        val calls = mutableListOf<List<LlmMessage>>()

        override suspend fun complete(messages: List<LlmMessage>): String {
            calls += messages
            return reply
        }

        override suspend fun stream(
            messages: List<LlmMessage>,
            onDelta: suspend (String) -> Unit,
            onReasoning: suspend (String) -> Unit,
            jsonMode: Boolean,
        ): String {
            calls += messages
            onDelta(reply)
            return reply
        }
    }

    private fun promptOf(call: List<LlmMessage>): String =
        call.joinToString("\n") { it.content }

    private fun appendGoal(runtime: AppRuntime, text: String) {
        runtime.stewardStore.appendMemory(
            StewardMemoryEvent(
                at = runtime.clock.nowMs(),
                day = runtime.clock.today().toString(),
                kind = StewardMemoryKind.Goal,
                text = text,
            ),
        )
    }

    private suspend fun withRuntime(home: File, block: suspend (AppRuntime, CapturingFake) -> Unit) {
        val runtime = AppRuntime.create(home)
        try {
            val fake = CapturingFake()
            // 先换假模型再发消息：启动时的管家检查是异步的，可能已经在跑，
            // 但它的提示词一定不含记忆块，断言用 any/none 就不会被它干扰。
            runtime.llmOverride = fake
            block(runtime, fake)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun normalChatPromptCarriesTheGoalAndTheMemoryGuidance() = runTest {
        val home = tempHome()
        writeFakeLlmConfig(home)
        withRuntime(home) { runtime, fake ->
            appendGoal(runtime, "今年考过软考中级")
            assertTrue(
                runtime.memoryBlock().contains("今年考过软考中级"),
                "memoryBlock 应当已经能看到刚写进去的目标",
            )

            runtime.sendChat("今天学点什么")

            val prompts = fake.calls.map { promptOf(it) }
            assertTrue(prompts.isNotEmpty(), "普通会话必须真的把提示词发给模型")
            val withMemory = prompts.firstOrNull { it.contains(CROSS_SESSION_MEMORY_OPEN) }
            assertTrue(withMemory != null, "普通会话的提示词里应当带上跨会话记忆块")
            withMemory!!
            assertTrue(withMemory.contains("今年考过软考中级"), "记忆块里要带上目标原文")
            assertTrue(withMemory.contains(CROSS_SESSION_MEMORY_CLOSE), "记忆块要有闭合标签")
            assertTrue(withMemory.contains("remember_memory"), "guidance 要点名 remember_memory")
            assertTrue(withMemory.contains("recall_memory"), "guidance 要点名 recall_memory")
        }
    }

    @Test
    fun stewardChatPromptCarriesTheSameMemoryBlock() = runTest {
        val home = tempHome()
        writeFakeLlmConfig(home)
        withRuntime(home) { runtime, fake ->
            appendGoal(runtime, "今年考过软考中级")
            val steward = runtime.chatSessions.first { it.kind == SessionKind.Steward }
            runtime.openChat(steward.id)

            runtime.sendChat("早安")

            val prompts = fake.calls.map { promptOf(it) }
            val withMemory = prompts.firstOrNull { it.contains(CROSS_SESSION_MEMORY_OPEN) }
            assertTrue(withMemory != null, "管家会话同样要带跨会话记忆块")
            withMemory!!
            assertTrue(withMemory.contains("今年考过软考中级"))
            // 管家路径的 base 是 StewardPrompt.systemPrompt() + 工具目录 + guidance，
            // 命中这一段就说明走的确实是管家那条构造分支。
            assertTrue(
                withMemory.contains(StewardPrompt.systemPrompt()),
                "管家会话的提示词应当以管家人格开头",
            )
        }
    }

    @Test
    fun aFreshHomeHasNoCrossSessionMemorySection() = runTest {
        val home = tempHome()
        writeFakeLlmConfig(home)
        withRuntime(home) { runtime, fake ->
            assertTrue(runtime.memoryBlock().isBlank(), "空记忆的 home 不该渲染出记忆块")

            runtime.sendChat("你好")

            assertTrue(fake.calls.isNotEmpty(), "这轮必须调过模型")
            assertTrue(
                fake.calls.none { promptOf(it).contains(CROSS_SESSION_MEMORY_OPEN) },
                "没有目标/偏好/近期决定时，提示词里不该出现空记忆块（保持前缀稳定）",
            )
        }
    }
}
