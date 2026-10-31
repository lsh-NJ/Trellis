package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 聊天记录要能落盘、能重开、坏一个文件不能带崩列表。 */
class ChatStoreTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-chat-test").toFile()

    private fun session(id: String, title: String, at: Long, text: String = "你好"): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = at,
        updatedAt = at,
        lines = listOf(
            ChatLine(1, ChatRole.User, text, at),
            ChatLine(2, ChatRole.Assistant, "在，说吧", at + 1),
            ChatLine(3, ChatRole.Step, "第 1 步：list_events", at + 2),
            ChatLine(4, ChatRole.Status, "—— 本轮完成", at + 3),
        ),
    )

    @Test
    fun aConversationSurvivesASaveAndReload() {
        val home = tempHome()
        ChatStore(home).save(session("chat-1", "把首页排成三列", 1_000L))

        // 同一个目录换一个实例读，模拟「关掉程序再打开」
        val again = ChatStore(home).read("chat-1")
        assertTrue(again != null, "没有读回来")
        assertEquals("把首页排成三列", again!!.title)
        assertEquals(4, again.lines.size)
        assertEquals(ChatRole.User, again.lines[0].role)
        assertEquals(1_000L, again.lines[0].atMs)
        assertEquals(ChatRole.Step, again.lines[2].role)
    }

    @Test
    fun theListIsSortedByRecentTalk() {
        val store = ChatStore(tempHome())
        store.save(session("chat-old", "旧的", 1_000L))
        store.save(session("chat-new", "新的", 9_000L))
        store.save(session("chat-mid", "中间", 5_000L))

        assertEquals(listOf("chat-new", "chat-mid", "chat-old"), store.list().map { it.id })
    }

    @Test
    fun deletingRemovesTheFileAndTheEntry() {
        val home = tempHome()
        val store = ChatStore(home)
        store.save(session("chat-1", "删我", 1_000L))
        store.delete("chat-1")

        assertNull(store.read("chat-1"))
        assertTrue(store.list().isEmpty())
        assertTrue(!File(File(home, "chat"), "chat-1.json").exists())
    }

    @Test
    fun aCorruptedFileIsIgnoredInsteadOfCrashingTheList() {
        val home = tempHome()
        val store = ChatStore(home)
        store.save(session("chat-good", "好的", 2_000L))
        File(File(home, "chat"), "chat-bad.json").writeText("{ 这不是 JSON", Charsets.UTF_8)

        val list = store.list()
        assertEquals(listOf("chat-good"), list.map { it.id })
        assertNull(store.read("chat-bad"))
    }

    @Test
    fun aFileWithABomIsStillReadable() {
        val home = tempHome()
        val store = ChatStore(home)
        store.save(session("chat-bom", "带 BOM", 3_000L))
        val file = File(File(home, "chat"), "chat-bom.json")
        file.writeText("\uFEFF" + file.readText(Charsets.UTF_8), Charsets.UTF_8)

        // PowerShell 写出来的 UTF-8 文件常带 BOM，读不了就等于会话凭空消失。
        assertTrue(store.read("chat-bom") != null)
    }

    @Test
    fun anEmptyHomeIsAnEmptyList() {
        assertTrue(ChatStore(tempHome()).list().isEmpty())
    }

    @Test
    fun theUnreadMarkSurvivesAReloadAndOldFilesDefaultToRead() {
        val home = tempHome()
        ChatStore(home).save(session("plan-check", "计划检查", 1_000L).copy(unread = true))
        assertTrue(ChatStore(home).read("plan-check")!!.unread, "主动消息的未读应该能落盘")

        // 旧会话文件没有 unread 字段：默认已读。升级后不该凭空冒出一排红点。
        File(File(home, "chat"), "chat-legacy.json").writeText(
            """{"id":"chat-legacy","title":"旧的","createdAt":1,"updatedAt":2,"lines":[]}""",
            Charsets.UTF_8,
        )
        assertEquals(false, ChatStore(home).read("chat-legacy")!!.unread)
    }
}
