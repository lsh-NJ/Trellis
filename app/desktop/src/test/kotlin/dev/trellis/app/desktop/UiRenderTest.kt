package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 离屏渲染整个界面，并存成 PNG。
 *
 * 两个作用：
 *  1. 证明界面**能画出来而不抛异常**  这件事在纯逻辑测试里验证不到
 *  2. 产出一张可以直接看的图（`app/desktop/build/ui-shot*.png`），
 *     在没有显示器的环境里也能知道它长什么样
 *
 * 它不需要显示器：`ImageComposeScene` 走的是软件光栅化。
 *
 * 2026-10-28 起，截图**走真实的 [AppTheme]**（浅色 + 深色两张）。
 * 之前这里套的是裸 `MaterialTheme {}`，用的是 Material3 的默认紫，
 * 和用户实际看到的配色不是一回事 —— 拿它评审界面会被带偏。
 */
class UiRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(runtime: AppRuntime, dark: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 1180, height = 820, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark),
                dark = dark,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark),
            ) {
                AppScreen(runtime)
            }
        }
        val image = scene.render()
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    private fun write(name: String, bytes: ByteArray) {
        val out = File("build/$name")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
    }

    @Test
    fun rendersTheWholeScreenOffscreen() {
        val home = Files.createTempDirectory("trellis-ui").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }

        runBlocking {
            runtime.addTask("第 1 章 计算机组成")
            runtime.addTask("第 2 章 操作系统")
            runtime.addTask("整理错题")
            runtime.toggle(runtime.tasks.last())
            runtime.logTime(runtime.tasks.last(), 45)
        }

        // 任务页：浅色 + 深色（这两张是评审时最常看的主界面）。
        runtime.currentTab = TAB_TASKS
        val light = render(runtime, dark = false)
        val dark = render(runtime, dark = true)
        write("ui-shot.png", light)
        write("ui-shot-dark.png", dark)
        assertTrue(light.size > 5_000, "PNG 太小了，八成是空白画面：" + light.size)
        assertTrue(dark.size > 5_000, "深色截图太小了，八成是空白画面：" + dark.size)

        // 计划页 / 对话页也各留一张：外壳组件（按钮、输入框、卡片）是三个页签共用的，
        // 只拍任务页的话，改外壳时另外两页什么时候坏掉没人知道。
        for ((tab, name) in listOf(TAB_PLAN to "ui-shot-plan.png", TAB_CHAT to "ui-shot-chat.png")) {
            runtime.currentTab = tab
            val shot = render(runtime, dark = false)
            write(name, shot)
            assertTrue(shot.size > 5_000, name + " 太小了，八成是空白画面：" + shot.size)
        }
        runtime.currentTab = TAB_TASKS

        runBlocking { runtime.shutdown() }
    }
}
