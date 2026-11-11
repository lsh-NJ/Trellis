package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.ui.DynamicSlotView
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 把仓库里的读书记录范例**真渲染**出来，用肉眼看版式（离屏，不需要显示器）。
 *
 * v3 的看点是「卡片只留数据与动作」：标题 + 每本书一行 + 底部两个浮窗触发器，
 * 不应再看到说明行、−/+ 或每本书的输入框。光靠断言文字够不够，看不出
 * 「挤不挤、空不空、有没有重复」；所以这里产出 `app/ui/build/ui-shot-reading-log.png` /
 * `-dark.png` 两张图给人工复核。
 *
 * 用两张书：一张有总页数（画进度），一张没设（只有当前页），能一眼看出两种形态。
 */
class ReadingLogExampleRenderTest {

    private fun tempHome(): File =
        File.createTempFile("trellis-reading-shot-", "").let { it.delete(); it.mkdirs(); it }

    private fun exampleDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/plugins/ai.reading-log") }
            .firstOrNull { it.isDirectory }
            ?: error("找不到 examples/plugins/ai.reading-log")

    private fun seed(home: File) {
        val src = exampleDir()
        val dst = File(home, "plugins/ai.reading-log").apply { mkdirs() }
        for (name in listOf("manifest.json", "main.js")) {
            File(src, name).copyTo(File(dst, name), overwrite = true)
        }
        // v1 就在用的 store 键；tot: 是 v2 新加的，缺省时卡片只显示当前页。
        File(home, "store.json").writeText(
            """
            {
              "plugin/ai.reading-log": {
                "books": "三体||假如给我三天光明",
                "p:三体": "120",
                "tot:三体": "300",
                "p:假如给我三天光明": "3",
                "t:假如给我三天光明": "1794280111695"
              }
            }
            """.trimIndent(),
        )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private suspend fun render(dark: Boolean): ByteArray {
        val home = tempHome()
        try {
            seed(home)
            val runtime = AppRuntime.create(home)
            val slot = runtime.contributionsOf(dev.trellis.kernel.core.PluginId("ai.reading-log"))
                .mapNotNull { it.value as? DynamicSlot }
                .single()
            val scene = ImageComposeScene(width = 1400, height = 1180, density = Density(2f)) {
                AppTheme(prefs = UiPrefs(), dark = dark) {
                    Box(Modifier.fillMaxSize().padding(48.dp)) {
                        Surface(
                            modifier = Modifier.width(660.dp),
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Box(Modifier.padding(24.dp)) {
                                DynamicSlotView(slot = slot, refreshKey = 1, onAction = null)
                            }
                        }
                    }
                }
            }
            scene.render()
            val image = scene.render()
            scene.close()
            return image.encodeToData()?.bytes ?: error("PNG 编码失败")
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun rendersTheReadingLogExampleCard() = runTest {
        val bytes = render(dark = false)
        val out = File("build/ui-shot-reading-log.png")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
        assertTrue(bytes.size > 5_000, "读书记录截图像素太少：" + bytes.size)
    }

    @Test
    fun rendersTheReadingLogExampleCardInDarkMode() = runTest {
        val bytes = render(dark = true)
        val out = File("build/ui-shot-reading-log-dark.png")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        assertTrue(bytes.size > 5_000, "暗色读书记录截图像素太少：" + bytes.size)
    }
}
