package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「AI 补全」小浮窗的离屏渲染检查（人工看图）。
 *
 * 打开一篇正文只有「混合检索：。。。」的笔记、把光标定位到行尾：补全建议应该**浮在光标
 * 下一行**（像代码编辑器的补全弹窗，不是行内灰字、也不是编辑器下方另起一条），
 * 显示「AI 补全」+ Tab 键帽。产出 `build/ui-shot-ai-fill-popup.png`。
 */
class MarkdownAiFillRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersAiFillSuggestionPopup() {
        val home = Files.createTempDirectory("trellis-ai-fill-ui").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        val body = "混合检索：。。。"
        val scene = ImageComposeScene(width = 1600, height = 700, density = Density(2f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark = false),
                dark = false,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark = false),
            ) {
                MarkdownDocumentEditor(
                    noteId = "note-ai-fill",
                    body = body,
                    onBodyChange = {},
                    modifier = Modifier.fillMaxSize(),
                    reveal = TextRange(body.length),
                    revealToken = 1,
                    onRequestAiFill = { draft, _ -> draft },
                )
            }
        }
        // 前几帧：布局 → reveal 请求焦点 → 小浮窗出现。
        repeat(4) { scene.render() }
        val image = scene.render()
        scene.close()

        val out = File("build/ui-shot-ai-fill-popup.png")
        out.parentFile.mkdirs()
        val bytes = image.encodeToData()?.bytes ?: error("PNG 编码失败")
        out.writeBytes(bytes)
        runBlocking { runtime.shutdown() }

        assertTrue(bytes.size > 5_000, "补全浮窗截图像素太少：" + bytes.size)
    }
}
