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
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.kernel.slot.UiTextStyle
import dev.trellis.ui.DynamicSlotView
import dev.trellis.ui.PluginInputDialog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 插件输入框的离屏渲染检查（人工看图）。
 *
 * 默认形态是**浮窗**：卡片里只有一行可点的字（submitLabel），点开才弹输入框；
 * `popup:false` 才回到常驻的「一行文字 + 底线 + ↵ 提交」。这里走真实 [AppTheme] +
 * 真实 [DynamicSlotView]，产出：
 *   app/ui/build/ui-shot-plugin-input.png        卡片上的两个入口
 *   app/ui/build/ui-shot-plugin-dialog.png       浮窗打开的样子
 * 给 Lead 人工确认「美观度」。
 */
class PluginInputRenderTest {

    /** 只有输入框的卡片桩：不引内核、不碰真实数据。 */
    private class StubSlot(private val node: UiNode) : DynamicSlot {
        override val id = "slot.home.section.plugin-input-preview"
        override val order = 10
        override suspend fun build(context: DataContext): UiNode = node
        override suspend fun perform(handler: String, payload: Map<String, String>): ActionOutcome =
            ActionOutcome(ok = true, message = "已记下：" + payload["value"].orEmpty())
    }

    private val stubNode = UiNode.Column(
        children = listOf(
            UiNode.Text("读书", style = UiTextStyle.Title),
            UiNode.ListRow(
                title = "三体",
                subtitle = "120 ｜ 300 页",
                trailing = "打开笔记",
            ),
            UiNode.Row(
                children = listOf(
                    UiNode.Input(
                        hint = "加一本书：书名 可带总页数（如 三体 300）",
                        submitLabel = "加一本书",
                        onSubmit = UiTap.Plugin("addBook"),
                    ),
                    UiNode.Input(
                        hint = "记页数：书名 页数（如 三体 120）",
                        submitLabel = "记一页",
                        onSubmit = UiTap.Plugin("setPage"),
                    ),
                ),
                gap = 16,
            ),
            UiNode.Input(
                hint = "常驻输入（popup:false）",
                submitLabel = "记一句",
                onSubmit = UiTap.Plugin("quick"),
                popup = false,
            ),
        ),
        gap = 10,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderCard(dark: Boolean): ByteArray {
        // density 2 = Retina：截图要和真实桌面端一样，中文按 2x 像素栅格化。
        val scene = ImageComposeScene(width = 1400, height = 620, density = Density(2f)) {
            AppTheme(prefs = UiPrefs(), dark = dark) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(48.dp),
                ) {
                    Surface(
                        modifier = Modifier.width(640.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Box(Modifier.padding(24.dp)) {
                            DynamicSlotView(
                                slot = StubSlot(stubNode),
                                refreshKey = 1,
                                onAction = null,
                            )
                        }
                    }
                }
            }
        }
        scene.render() // 第一帧让 LaunchedEffect 里的 build 先跑完
        val image = scene.render()
        scene.close()
        return image.encodeToData()?.bytes ?: error("PNG 编码失败")
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderDialog(dark: Boolean): ByteArray {
        val scene = ImageComposeScene(width = 1400, height = 620, density = Density(2f)) {
            AppTheme(prefs = UiPrefs(), dark = dark) {
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
                    PluginInputDialog(
                        hint = "加一本书：书名 可带总页数（如 三体 300）",
                        submitLabel = "加一本书",
                        onDismissRequest = {},
                        onSubmit = {},
                    )
                }
            }
        }
        scene.render()
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
    fun rendersPopupTriggersOnTheCard() {
        val bytes = renderCard(dark = false)
        write("ui-shot-plugin-input.png", bytes)
        assertTrue(bytes.size > 5_000, "输入框截图像素太少：" + bytes.size)
    }

    @Test
    fun rendersPopupTriggersOnTheCardInDarkMode() {
        val bytes = renderCard(dark = true)
        write("ui-shot-plugin-input-dark.png", bytes)
        assertTrue(bytes.size > 5_000, "暗色输入框截图像素太少：" + bytes.size)
    }

    @Test
    fun rendersThePopupDialog() {
        val bytes = renderDialog(dark = false)
        write("ui-shot-plugin-dialog.png", bytes)
        assertTrue(bytes.size > 5_000, "浮窗截图像素太少：" + bytes.size)
    }
}
