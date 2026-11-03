package dev.trellis.app.desktop

import java.util.Base64

/**
 * 往系统通知中心说一句话。
 *
 * 通知是**尽力而为**：失败、没权限、平台不认识，都必须静默吞掉 —— 管家该发的消息
 * 已经落进会话里了（未读红点兜底），不能因为通知发不出去让仪式失败。
 *
 * 跨平台实现是命令行的三套（macOS `osascript` / Windows PowerShell Toast /
 * Linux `notify-send`），不引第三方依赖：桌面端打包成 app 后没有额外的原生桥，
 * 而这三条命令在各自系统上都自带。点通知不跳转（回调需要原生 API），
 * 点了只是系统行为；真正的入口是应用里的会话红点。
 */
internal fun interface Notifier {
    fun notify(title: String, body: String)

    companion object {
        /** 测试/无声环境。 */
        val silent = Notifier { _, _ -> }

        /** 按当前系统挑实现。 */
        fun create(): Notifier = SystemNotifier()
    }
}

internal class SystemNotifier(
    private val osName: String = System.getProperty("os.name").orEmpty().lowercase(),
    /** 注入点：测试里换成记录命令的假实现，不真的弹通知。 */
    private val runner: (List<String>) -> Unit = ::runNotificationCommand,
) : Notifier {
    override fun notify(title: String, body: String) {
        val command = when {
            osName.contains("mac") -> listOf(
                "osascript",
                "-e",
                "display notification ${appleScriptString(body)} with title ${appleScriptString(title)}",
            )
            osName.contains("win") -> windowsCommand(title, body)
            else -> listOf("notify-send", title, body)
        }
        runCatching { runner(command) }
    }

    companion object {
        /**
         * Windows：用 PowerShell 调 WinRT 的 ToastNotification。
         *
         * 标题/正文先转 base64 再塞进脚本，避免引号、中文、换行和 `$` 被 PowerShell
         * 当代码解释 —— 这是唯一不需要额外模块（BurntToast）又能弹 Toast 的写法。
         */
        fun windowsCommand(title: String, body: String): List<String> {
            val titleB64 = base64(title)
            val bodyB64 = base64(body)
            val script = """
                [Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                [Windows.UI.Notifications.ToastNotification, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null
                [Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null
                ${'$'}enc = [Text.Encoding]::UTF8
                ${'$'}title = ${'$'}enc.GetString([Convert]::FromBase64String('$titleB64'))
                ${'$'}body = ${'$'}enc.GetString([Convert]::FromBase64String('$bodyB64'))
                ${'$'}xml = New-Object Windows.Data.Xml.Dom.XmlDocument
                ${'$'}xml.LoadXml("<toast><visual><binding template=""ToastGeneric""><text>" + [System.Security.SecurityElement]::Escape(${'$'}title) + "</text><text>" + [System.Security.SecurityElement]::Escape(${'$'}body) + "</text></binding></visual></toast>")
                ${'$'}toast = New-Object Windows.UI.Notifications.ToastNotification ${'$'}xml
                [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Trellis').Show(${'$'}toast)
            """.trimIndent()
            return listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
        }

        private fun base64(text: String): String =
            Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
    }
}

/** AppleScript 双引号字符串：反斜杠和双引号要转义，换行退化成空格。 */
internal fun appleScriptString(text: String): String {
    val escaped = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
        .replace("\r", " ")
    return "\"$escaped\""
}

/** 真正跑命令：不读输出、不等结果（通知是即发即忘），失败抛给调用方吞掉。 */
private fun runNotificationCommand(command: List<String>) {
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    process.outputStream.close()
    // 后台退出：不阻塞仪式的协程；命令不存在时会在 start() 就抛，也由调用方吞掉。
    Thread {
        runCatching { process.waitFor() }
    }.apply {
        isDaemon = true
        start()
    }
}
