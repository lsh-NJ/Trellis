package dev.trellis.app.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 跨平台通知：只验证「拼出来的命令对不对」，不真的弹通知。 */
class NotifierTest {

    private fun commandsFor(osName: String, title: String = "管家", body: String = "早上好。"): List<List<String>> {
        val commands = mutableListOf<List<String>>()
        SystemNotifier(osName = osName) { commands += it }.notify(title, body)
        return commands
    }

    @Test
    fun macUsesOsaScriptWithEscapedStrings() {
        val command = commandsFor("mac os x", title = "Trellis 管家", body = "今天计划 3 件，0 件完成。").single()
        assertEquals("osascript", command.first())
        assertEquals("-e", command[1])
        val script = command.last()
        assertTrue(script.startsWith("display notification "), script)
        assertTrue(script.contains("with title \"Trellis 管家\""), script)
        assertTrue(script.contains("今天计划 3 件，0 件完成。"), script)
    }

    @Test
    fun windowsUsesPowerShellToast() {
        val command = commandsFor("windows 11").single()
        assertEquals("powershell", command.first())
        assertEquals(listOf("powershell", "-NoProfile", "-NonInteractive", "-Command"), command.take(4))
        val script = command.last()
        assertTrue(script.contains("ToastNotificationManager"), script)
        assertTrue(script.contains("FromBase64String"), script)
    }

    @Test
    fun linuxUsesNotifySend() {
        assertEquals(listOf("notify-send", "管家", "早上好。"), commandsFor("linux").single())
    }

    @Test
    fun aMissingCommandDoesNotCrashTheRitual() {
        val notifier = SystemNotifier(osName = "linux") { error("没有 notify-send") }
        // 通知是尽力而为：失败必须静默。
        notifier.notify("管家", "早上好。")
    }

    @Test
    fun silentNotifierDoesNothing() {
        Notifier.silent.notify("管家", "早上好。")
    }

    @Test
    fun appleScriptStringsEscapeQuotesAndNewlines() {
        assertEquals("\"a\\\\b\\\"c d\"", appleScriptString("a\\b\"c\nd"))
    }
}
