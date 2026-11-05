package dev.trellis.app.mobile.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.mobile.PhoneApp
import dev.trellis.domain.TaskStatus
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * 手机界面预览器。
 *
 * 渲染的就是**手机上跑的那一份** `PhoneApp` —— 不是另画一版示意图。
 * 这样「手机布局对不对」在电脑上就能看，不用每次装 APK。
 *
 *   ./gradlew :app:mobile-preview:run              开 390×844 的窗口，可以真的点
 *   TRELLIS_SHOT_DIR=build/preview ...             不开窗，逐页渲染 PNG
 *   TRELLIS_SEED=1 ...                             先灌一份演示数据
 *   TRELLIS_APPEARANCE=dark ...                    看暗色
 *
 * 数据目录默认 `build/preview-home`，**不碰 `~/.trellis`**：预览器是开发工具。
 */
fun main() {
    val home = File(System.getenv("TRELLIS_PREVIEW_HOME") ?: "build/preview-home").apply { mkdirs() }
    val runtime = runBlocking { AppRuntime.create(home) }

    if (System.getenv("TRELLIS_SEED") == "1") {
        runBlocking { seedIfEmpty(runtime) }
    }
    // 外观走同一个入口（它会顺便落盘并 bump tick，界面才会刷新）。
    val appearance = System.getenv("TRELLIS_APPEARANCE")?.takeIf { it.isNotBlank() }
    if (appearance != null) {
        runtime.updateUiPrefs(runtime.uiPrefs.copy(appearance = appearance))
    }

    val shotDir = System.getenv("TRELLIS_SHOT_DIR")?.takeIf { it.isNotBlank() }
    if (shotDir != null) {
        renderAll(runtime, File(shotDir))
        return
    }

    Runtime.getRuntime().addShutdownHook(
        Thread { runBlocking { runCatching { runtime.shutdown() } } },
    )

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Trellis · 手机预览 390×844",
            state = rememberWindowState(size = DpSize(390.dp, 844.dp)),
        ) {
            PhoneApp(runtime)
        }
    }
}

/** 手机竖屏 + 平板，各来一套 —— 自适应只验一种尺寸等于没验。 */
private val formFactors = listOf(
    Triple("phone", 390, 844),
    Triple("tablet", 834, 1112),
)

/** 顺序与 `PhoneTab` 一致：今日 / 计划 / 对话 / 知识 / 我的；知识再看「笔记」与「网络」。 */
private val pages = listOf(
    Shot("01-today", 0),
    Shot("02-plan", 1),
    Shot("03-chat", 2),
    Shot("04-knowledge-notes", 3),
    Shot("05-knowledge-graph", 3, knowledgeTab = 1),
    Shot("06-knowledge-overview", 3, knowledgeTab = 1, graphMode = 1),
    Shot("07-knowledge-note", 3, noteId = "n_preview_transport"),
    Shot("08-me", 4),
)

private data class Shot(
    val label: String,
    val tab: Int,
    val knowledgeTab: Int = 0,
    val graphMode: Int = 0,
    val noteId: String? = null,
)

private fun renderAll(runtime: AppRuntime, dir: File) {
    dir.mkdirs()
    for ((name, w, h) in formFactors) {
        for (shot in pages) {
            val out = File(dir, "$name-${shot.label}.png")
            render(w, h, out) {
                PhoneApp(
                    runtime,
                    startTab = shot.tab,
                    startKnowledgeTab = shot.knowledgeTab,
                    startGraphMode = shot.graphMode,
                    startNoteId = shot.noteId,
                )
            }
            println("已渲染：${out.absolutePath}")
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(widthDp: Int, heightDp: Int, out: File, content: @Composable () -> Unit) {
    val scale = 2
    val scene = ImageComposeScene(
        width = widthDp * scale,
        height = heightDp * scale,
        density = Density(scale.toFloat()),
        content = content,
    )
    try {
        val data = scene.render().encodeToData() ?: error("PNG 编码失败")
        out.parentFile?.mkdirs()
        out.writeBytes(data.bytes)
    } finally {
        scene.close()
    }
}

/**
 * 演示数据：只在预览目录、只在 `TRELLIS_SEED=1` 时灌一次。
 *
 * 空界面看不出排版对不对 —— 分组高度、逾期颜色、长标题会不会溢出，
 * 都要有真实数据才暴露得出来。
 */
private fun homeOf(runtime: AppRuntime): File = runtime.home

private suspend fun seedIfEmpty(runtime: AppRuntime) {
    val plan = runtime.planService
    if (plan.tasks().isNotEmpty()) return
    val now = runtime.clock.nowMs()
    val hour = 3_600_000L
    val day = 86_400_000L

    plan.addTask("整理错题本：传输层三道题", dueAt = now + 2 * hour)
    plan.addTask("看第 5 章：传输层与拥塞控制", dueAt = now + 6 * hour)
    plan.addTask(
        "把「为什么 TCP 要三次握手而不是两次」用自己的话写明白，并补上两次握手会遇到的经典问题",
        dueAt = now + 8 * hour,
    )
    plan.addTask("补交上机实验报告（第 4 章）", dueAt = now - 3 * day)
    plan.addTask("复习第一章：分层与协议栈", dueAt = now + day)
    plan.addTask("背 50 个网络术语", dueAt = null)
    plan.addTask("读完第 3 章", dueAt = null)

    repeat(6) { i ->
        val task = plan.addTask("第 ${i + 1} 组练习", dueAt = now - (i % 3) * day)
        plan.setStatus(task.id, TaskStatus.DONE)
        if (i == 2) plan.summarize(task.id, "先看例题再动手，速度快了一倍。")
    }

    // 知识库：直接按落盘格式写两篇。不碰 internal 的 KnowledgeStore ——
    // 预览器是另一个模块，本来就不该看见它；文件格式才是真契约（见 KnowledgeNote.formatNoteMarkdown）。
    val notesDir = File(homeOf(runtime), "knowledge/notes").apply { mkdirs() }
    if (notesDir.listFiles().isNullOrEmpty()) {
        File(notesDir, "n_preview_transport.md").writeText(
            """
            ---
            title: 传输层笔记
            concepts: TCP, UDP, 拥塞控制, 流量控制
            ---

            # 传输层
            传输层负责端到端的可靠交付，两个主角是 [[TCP]] 与 [[UDP]]。

            ## 拥塞控制
            [[拥塞控制]] 是发送方根据网络反馈调整速率的机制，常见四段：慢启动、拥塞避免、快重传、快恢复。

            ## 与 [[流量控制]] 的区别
            流量控制看接收方，拥塞控制看网络。
            """.trimIndent(),
            Charsets.UTF_8,
        )
        File(notesDir, "n_preview_handshake.md").writeText(
            """
            ---
            title: 三次握手
            concepts: TCP, 拥塞控制
            ---

            # 三次握手
            [[TCP]] 建立连接需要三次握手：SYN、SYN-ACK、ACK。
            两次不够，因为网络里可能残留旧连接请求。

            与 [[拥塞控制]] 无关，别混在一起。
            """.trimIndent(),
            Charsets.UTF_8,
        )
    }
    runtime.refresh()
}
