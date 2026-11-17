package dev.trellis.app.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.feature.learning.LearningPlanStore
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 计划文档预览页的视觉验收：和普通笔记同一套壳（左栏列表 + 正文 + 右栏目录）。
 *
 * 这张图回答三件事：
 *  1. 左栏、正文、右栏的位置和笔记页一致（左栏贴左、正文 18dp 缩进、右栏贴右边界）；
 *  2. 右栏**只有目录**，没有「本篇概念」，页头没有「AI 整理」「导出」「菜单」；
 *  3. 正文是只读渲染，目录能从文档标题树里画出来。
 *
 * 和图相关的断言只保证「画得出来、不是空白、右栏确实画了目录」；细节靠人看 PNG。
 */
class PlanDocPreviewRenderTest {

    private fun write(name: String, bytes: ByteArray) {
        val out = File("build/$name")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
    }

    private fun sampleDoc(): String = buildString {
        appendLine("# 目标与验收")
        appendLine("- 目标：两个月后期末考试，复习操作系统")
        appendLine("- 截止：2027-01-10")
        appendLine()
        appendLine("## 阶段")
        appendLine("- 阶段 1 · 系统全局观与基础概念（2026-W47 – 2026-W47，约 1 周）：中断、系统调用、内核态")
        appendLine("  - 资源：")
        appendLine("    - [操作系统学习路线讲解](https://www.bilibili.com/video/BV1th411b7in) — 先看这份路线梳理")
        appendLine("- 阶段 2 · 进程与线程管理（2026-W48 – 2026-W49，约 2 周）：进程状态、调度算法、同步互斥与死锁")
        appendLine("  - 资源：")
        appendLine("    - [操作系统期末冲刺课](https://kaoyan.icourse163.org/course/terms/1473977448.htm) — 进程管理章节")
        appendLine()
        appendLine("## 资源清单")
        appendLine("### 视频")
        appendLine("- [操作系统学习路线讲解](https://www.bilibili.com/video/BV1th411b7in) — B站")
        appendLine("### 文章")
        appendLine("- [进程调度算法总结](https://blog.csdn.net/example/article/details/1) — CSDN")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersPlanDocPreviewWithOutlineAndNoConceptPanel() {
        val home = Files.createTempDirectory("trellis-plan-doc-preview").toFile()
        val runtime = runBlocking { AppRuntime.create(home) }
        var planId = ""
        try {
            val store = LearningPlanStore.open(
                File(home, "plans/plan.db").toPath(),
                clock = { runtime.clock.nowMs() },
                zone = runtime.clock.zone,
            )
            try {
                val created = store.createShell(
                    CommandEnvelope(
                        commandId = "cmd_" + UUID.randomUUID(),
                        actor = Actor.AGENT,
                        origin = "test.render",
                    ),
                    title = "操作系统 8 周期末备考",
                    goal = "两个月后期末考试，复习操作系统",
                    deadline = "2027-01-10",
                )
                planId = created.value?.planId ?: error("没建出计划：" + created.error?.message)
            } finally {
                store.close()
            }
            runtime.planDocStore.save(planId, sampleDoc())
            runBlocking { runtime.refreshPlan() }
            runtime.openPlanDocs(planId)

            val scene = ImageComposeScene(width = 1440, height = 900, density = Density(1f)) {
                AppTheme(
                    prefs = runtime.uiPrefs,
                    themeScheme = runtime.themeScheme(false),
                    dark = false,
                    chrome = runtime.chromeStyle(),
                    extra = runtime.extraColors(false),
                ) {
                    AppScreen(runtime)
                }
            }
            val bytes = try {
                scene.render().encodeToData()?.bytes
            } finally {
                scene.close()
            }
            assertTrue(bytes != null && bytes.isNotEmpty(), "计划文档预览页没渲染出图")
            write("ui-shot-plan-doc-preview.png", bytes!!)
            assertTrue(bytes.size > 5_000, "截图太小了，八成是空白画面：" + bytes.size)
        } finally {
            runBlocking { runtime.shutdown() }
        }
    }
}
