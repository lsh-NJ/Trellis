package dev.trellis.app.mobile.preview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import dev.trellis.app.desktop.AppRuntime
import dev.trellis.app.mobile.PhoneApp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 手机界面的冒烟测试：每一页都真的渲染一遍，检查画面里**有东西**。
 *
 * 这是在「没有真机、没有模拟器」的机器上唯一能自动化的那部分验证：
 * - 界面能装配起来（`PhoneApp` 本身不会在组合时报错）；
 * - 每个页签都有内容，不是白屏（渲染出来是纯色 = 那一页崩了或没数据）；
 * - 手机与平板两种宽度都不抛异常。
 *
 * 它**不**替代真机验证：点击、输入、返回键、通知权限这些都要真设备。
 * 但它能挡住最难看的一类回归 ——「改完还能编译，装上去某页是白的」。
 */
class PhoneRenderSmokeTest {

    @Test
    fun everyTabRendersNonBlankContent() {
        withRuntime { runtime ->
            for ((name, width, height) in formFactors) {
                for (tab in dev.trellis.app.mobile.PhoneTab.entries) {
                    val bytes = renderToPng(width, height) {
                        PhoneApp(runtime, startTab = tab.ordinal)
                    }
                    val label = "$name/${tab.label}"
                    val image = decode(bytes, label)
                    assertEquals(width * 2, image.width, "$label 宽度不对")
                    assertEquals(height * 2, image.height, "$label 高度不对")

                    // 纯色（比如整页背景）说明这一页渲染了但**没有任何内容**。
                    // 阈值取 24：真机上素一点的页面也有几十种颜色（文字抗锯齿），
                    // 而空白页只有 1–2 种。
                    val colors = distinctColors(image)
                    assertTrue(colors > 24, "$label 只有 $colors 种颜色，像是白屏")
                }
            }
        }
    }

    @Test
    fun knowledgeSubPagesRenderTheirOwnContent() {
        withRuntime { runtime ->
            val notes = renderToPng(390, 844) { PhoneApp(runtime, startTab = 3, startKnowledgeTab = 0) }
            val graph = renderToPng(390, 844) { PhoneApp(runtime, startTab = 3, startKnowledgeTab = 1) }
            // 「笔记」和「网络」是两个分段，画面必须不一样 ——
            // 一样就说明分段没生效（点「网络」还停在笔记列表）。
            assertTrue(
                !notes.contentEquals(graph),
                "知识页的两个分段渲染出了同一张图，分段切换没生效",
            )
        }
    }

    private fun withRuntime(block: (AppRuntime) -> Unit) {
        val home: File = Files.createTempDirectory("trellis-preview-test").toFile()
        val runtime = runBlocking { AppRuntime.create(home) }
        try {
            block(runtime)
        } finally {
            runBlocking { runCatching { runtime.shutdown() } }
            home.deleteRecursively()
        }
    }

    private fun decode(bytes: ByteArray, label: String): BufferedImage {
        assertTrue(bytes.size > 4_000, "$label 的 PNG 只有 ${bytes.size} 字节，太小了")
        return ImageIO.read(ByteArrayInputStream(bytes)) ?: error("$label 的 PNG 解不开")
    }

    /** 统计颜色种数。按 4 位精度折叠，避免渐变抗锯齿把计数顶到几万。 */
    private fun distinctColors(image: BufferedImage): Int {
        val seen = HashSet<Int>()
        var y = 0
        while (y < image.height) {
            var x = 0
            while (x < image.width) {
                val rgb = image.getRGB(x, y)
                seen += (rgb shr 4) and 0xFFFFFF
                x += 6
            }
            y += 6
        }
        return seen.size
    }
}
