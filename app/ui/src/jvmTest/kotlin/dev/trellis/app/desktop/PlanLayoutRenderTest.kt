package dev.trellis.app.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 计划页左栏的视觉验收（2026-11-08 晚：改成和对话/知识库同一条 flush 线）。
 *
 * 这张图回答两件事：
 *  1. 左栏贴不贴导航栏——左栏左边缘应该紧跟在 88dp 的导航栏后面，中间不再有「居中列」的
 *     那一大段留白（改之前 1440 窗口里从窗口左边到列表有 200dp+）；
 *  2. 正文有没有限宽——窗口拉到 1600 时正文列仍然 1120 封顶、居中，不跟着拉成一整屏长条。
 *
 * 交互链路（拖拽条改宽度）在 Compose 里不方便断言，这里只保证画得出来、画出来不是空白，
 * 再把 PNG 写进 `app/ui/build/` 给人看。
 */
class PlanLayoutRenderTest {

    private fun write(name: String, bytes: ByteArray) {
        val out = File("build/$name")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersThePlanPageWithTheFlushLeftSidebar() {
        val home = Files.createTempDirectory("trellis-plan-layout").toFile()
        val runtime = runBlocking { AppRuntime.create(home) }
        runtime.currentTab = TAB_PLAN

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
        try {
            val bytes = scene.render().encodeToData()?.bytes
            assertTrue(bytes != null && bytes.isNotEmpty(), "计划页没渲染出图")
            write("ui-shot-plan-flush.png", bytes!!)
            assertTrue(bytes.size > 5_000, "计划页截图太小了，八成是空白画面：" + bytes.size)
        } finally {
            scene.close()
            runBlocking { runtime.shutdown() }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersThePlanPageAtANarrowWindow() {
        val home = Files.createTempDirectory("trellis-plan-layout-narrow").toFile()
        val runtime = runBlocking { AppRuntime.create(home) }
        runtime.currentTab = TAB_PLAN

        val scene = ImageComposeScene(width = 1180, height = 820, density = Density(1f)) {
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
        try {
            val bytes = scene.render().encodeToData()?.bytes
            assertTrue(bytes != null && bytes.isNotEmpty(), "计划页没渲染出图")
            write("ui-shot-plan-flush-narrow.png", bytes!!)
            assertTrue(bytes.size > 5_000, "计划页截图太小了，八成是空白画面：" + bytes.size)
        } finally {
            scene.close()
            runBlocking { runtime.shutdown() }
        }
    }
}
