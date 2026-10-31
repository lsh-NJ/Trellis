package dev.trellis.app.desktop

import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 开发期 UI 看门狗：窗口假死时把全部线程栈写进 app.log。
 *
 * 背景（2026-10-31）：用户报「点完『今天做』再点昨天的日条」窗口假死，CPU 约 485%、
 * app.log 里没有异常，打包版 runtime 又没有 attach 模块（jstack / sample / lldb 都取不到栈）。
 *
 * 心跳用 Swing EDT 排队探测：每 2 秒往事件队列里 post 一个探针。如果 UI 线程超过
 * [STALE_MS] 没有执行它（无论是死锁、busy loop 还是渲染卡住），说明界面已经不处理事件了，
 * 这时把 [Thread.getAllStackTraces] 落盘，然后冷却一分钟再探，避免同一个卡死把日志刷爆。
 *
 * 只在 `TRELLIS_DEV=1` 时启动；正常使用不产生任何线程、不写盘。
 */
internal object UiWatchdog {
    private const val ENV = "TRELLIS_DEV"
    private const val PROBE_MS = 2_000L
    private const val STALE_MS = 12_000L
    private const val COOLDOWN_MS = 60_000L

    private val heartbeat = AtomicLong(System.nanoTime())

    @Volatile
    private var installed = false

    fun startIfEnabled(home: File) {
        if (System.getenv(ENV)?.trim() != "1") return
        if (installed) return
        installed = true
        val logFile = File(home, "app.log")
        Thread({
            var lastDumpMs = 0L
            while (true) {
                javax.swing.SwingUtilities.invokeLater { heartbeat.set(System.nanoTime()) }
                Thread.sleep(PROBE_MS)
                val ageMs = (System.nanoTime() - heartbeat.get()) / 1_000_000
                // UI 线程忙但还能跑事件时，心跳每个探测周期都会刷新，age 不会超过 2 秒。
                if (ageMs < STALE_MS) continue
                val nowMs = System.currentTimeMillis()
                if (lastDumpMs != 0L && nowMs - lastDumpMs < COOLDOWN_MS) continue
                lastDumpMs = nowMs
                runCatching { appendDump(logFile, ageMs) }
            }
        }, "trellis-ui-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun appendDump(logFile: File, ageMs: Long) {
        val dump = buildString {
            append("\n[").append(Instant.now()).append("] 看门狗：UI 线程 ")
                .append(ageMs / 1000).append(" 秒没有处理事件，写全线程栈\n")
            Thread.getAllStackTraces().forEach { (thread, stack) ->
                append("\nThread \"").append(thread.name).append("\" state=")
                    .append(thread.state).append(" daemon=").append(thread.isDaemon).append("\n")
                stack.forEach { frame -> append("\tat ").append(frame).append("\n") }
            }
            append("\n")
        }
        logFile.parentFile?.mkdirs()
        logFile.appendText(dump, Charsets.UTF_8)
    }
}
