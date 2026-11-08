package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * 提问卡的数据面（`ask_choices` → `AppRuntime.chatAsk`）。
 *
 * 这张卡回答一件事：**用户点的那一项是哪个问题的答案**。所以问题不能半路丢，
 * 选项要清洗；答完、换会话、开下一轮都要清掉，别把上一问的选项留到新对话里。
 */
class ChatAskTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-chat-ask").toFile().apply { deleteOnExit() }

    @Test
    fun keepsTheQuestionAndTrimsTheOptions() {
        val runtime = runBlocking { AppRuntime.create(tempHome()) }
        runtime.offerChoices("  这次先改哪一块？  ", listOf("  对话页 ", "", "计划页", "   "))

        val ask = runtime.chatAsk
        assertNotNull(ask)
        assertEquals("这次先改哪一块？", ask.question)
        assertEquals(listOf("对话页", "计划页"), ask.options.map { it.label })
        // label 就是点了之后发出去的原话：选项只有一句短话，不做二次映射。
        assertEquals(listOf("对话页", "计划页"), ask.options.map { it.text })

        runBlocking { runtime.shutdown() }
    }

    @Test
    fun fallsBackToAPlaceholderQuestionWhenTheModelForgetsIt() {
        val runtime = runBlocking { AppRuntime.create(tempHome()) }
        runtime.offerChoices("   ", listOf("浅色", "深色"))

        assertEquals("选一个", runtime.chatAsk?.question)

        runBlocking { runtime.shutdown() }
    }

    @Test
    fun ignoresAQuestionWithoutUsableOptions() {
        val runtime = runBlocking { AppRuntime.create(tempHome()) }
        runtime.offerChoices("先改哪块？", listOf("", "   "))

        assertNull(runtime.chatAsk, "没有选项的提问画不出来，也不该占着状态")

        runBlocking { runtime.shutdown() }
    }

    @Test
    fun switchingChatsDropsThePendingQuestion() {
        val home = tempHome()
        val now = System.currentTimeMillis()
        val store = ChatStore(home)
        store.save(ChatSession("chat-a", "第一段", now, now, listOf(ChatLine(1, ChatRole.User, "你好", atMs = now))))
        store.save(ChatSession("chat-b", "第二段", now, now, listOf(ChatLine(2, ChatRole.User, "在吗", atMs = now))))

        val runtime = runBlocking { AppRuntime.create(home) }
        runtime.openChat("chat-a")
        runtime.offerChoices("先改哪块？", listOf("对话页", "计划页"))
        assertNotNull(runtime.chatAsk)

        // 问题属于上一段会话：切过去之后还留着，点一下会当成新会话的提问发出去。
        runtime.openChat("chat-b")
        assertNull(runtime.chatAsk)

        runBlocking { runtime.shutdown() }
    }
}
