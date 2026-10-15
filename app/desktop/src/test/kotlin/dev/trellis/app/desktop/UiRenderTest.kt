package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
 * 离屏渲染整个界面，并存成一张 PNG。
 *
 * 两个作用：
 *  1. 证明界面**能画出来而不抛异常**  这件事在纯逻辑测试里验证不到
 *  2. 产出一张可以直接看的图（`app/desktop/build/ui-shot.png`），
 *     在没有显示器的环境里也能知道它长什么样
 *
 * 它不需要显示器：`ImageComposeScene` 走的是软件光栅化。
 */
class UiRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
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

        val scene = ImageComposeScene(width = 900, height = 700, density = Density(1f)) {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppScreen(runtime)
                }
            }
        }

        val image = scene.render()
        scene.close()

        val data = image.encodeToData() ?: error("PNG 编码失败")
        val out = File("build/ui-shot.png")
        out.parentFile?.mkdirs()
        out.writeBytes(data.bytes)

        println("  截图已写出: " + out.absolutePath + "  (" + data.bytes.size + " 字节)")
        assertTrue(data.bytes.size > 5_000, "PNG 太小了，八成是空白画面：" + data.bytes.size)

        runBlocking { runtime.shutdown() }
    }
}
