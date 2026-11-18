package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 检查点是 L2 里唯一需要落盘的东西：重开应用后必须还接得住上下文，
 * 而旧会话文件不该因为这次升级多出空字段。
 */
class ChatCheckpointTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-chat-checkpoint").toFile()

    private fun session(id: String, checkpoint: String) = ChatSession(
        id = id,
        title = "长对话",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        lines = listOf(
            ChatLine(1, ChatRole.User, "第一句", 1_000L),
            ChatLine(2, ChatRole.Assistant, "第一句的回答", 1_001L),
        ),
        checkpoint = checkpoint,
    )

    @Test
    fun aCheckpointSurvivesASaveAndReload() {
        val home = tempHome()
        ChatStore(home).save(session("chat-1", "## 进度\n做到第二步"))

        assertEquals("## 进度\n做到第二步", ChatStore(home).read("chat-1")!!.checkpoint)
    }

    @Test
    fun oldFilesWithoutACheckpointReadAsEmpty() {
        val home = tempHome()
        val dir = File(home, "chat").apply { mkdirs() }
        // 升级前的会话文件没有 checkpoint 字段：读回来必须是空串（= 还没折叠过），不能报错。
        File(dir, "chat-legacy.json").writeText(
            """{"id":"chat-legacy","title":"旧的","createdAt":1,"updatedAt":2,"lines":[]}""",
            Charsets.UTF_8,
        )

        assertEquals("", ChatStore(home).read("chat-legacy")!!.checkpoint)
    }

    @Test
    fun aBlankCheckpointIsNotWritten() {
        val home = tempHome()
        ChatStore(home).save(session("chat-1", ""))

        val raw = File(File(home, "chat"), "chat-1.json").readText(Charsets.UTF_8)
        assertTrue(!raw.contains("checkpoint"), "没有摘要就不该写字段：" + raw)
    }
}
