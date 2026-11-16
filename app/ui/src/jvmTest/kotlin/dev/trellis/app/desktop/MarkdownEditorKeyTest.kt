package dev.trellis.app.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 键盘接线的离屏集成检查：Tab/Shift+Tab 缩进和自定义快捷键是否真的走到编辑器里。
 *
 * 纯函数单测（MarkdownIndentTest / MarkdownKeysTest）只能证明「逻辑对」；
 * 这里证明「按键真的被分发到了这些函数」——否则很容易出现「函数写了但没接线」。
 * 用 [ImageComposeScene] 铺编辑器 + 直接灌 Compose 键事件，跑完就关，不留窗口。
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class MarkdownEditorKeyTest {

    private fun keyEvent(
        key: Key,
        ctrl: Boolean = false,
        meta: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): KeyEvent = KeyEvent(
        key = key,
        type = KeyEventType.KeyDown,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isShiftPressed = shift,
        isAltPressed = alt,
    )

    private fun pressKey(
        body: String,
        revealOffset: Int,
        event: KeyEvent,
        bindings: List<MarkdownBinding> = defaultMarkdownBindings,
        onRequestAiFill: (suspend (String, Int) -> String?)? = null,
    ): String {
        val home = Files.createTempDirectory("trellis-key-ui").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        var text = body
        val scene = ImageComposeScene(width = 1200, height = 700, density = Density(2f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(dark = false),
                dark = false,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(dark = false),
            ) {
                MarkdownDocumentEditor(
                    noteId = "note-key",
                    body = text,
                    onBodyChange = { text = it },
                    modifier = Modifier.fillMaxSize(),
                    reveal = TextRange(revealOffset),
                    revealToken = 1,
                    bindings = bindings,
                    onRequestAiFill = onRequestAiFill,
                )
            }
        }
        try {
            // 先跑几帧：布局 -> reveal 请求焦点 -> 焦点真正落到正文输入框上。
            repeat(4) { scene.render() }
            scene.sendKeyEvent(event)
            repeat(2) { scene.render() }
        } finally {
            scene.close()
            runBlocking { runtime.shutdown() }
        }
        return text
    }

    @Test
    fun tabIndentsTheCaretLineAndShiftTabUndoesIt() {
        val indented = pressKey(
            body = "- 一级\n- 二级\n",
            revealOffset = "- 一级\n".length + 2,
            event = keyEvent(Key.Tab),
        )
        assertEquals("- 一级\n  - 二级\n", indented)

        val dedented = pressKey(
            body = "- 一级\n  - 二级\n",
            revealOffset = "- 一级\n".length + 3,
            event = keyEvent(Key.Tab, shift = true),
        )
        assertEquals("- 一级\n- 二级\n", dedented)
    }

    @Test
    fun tabInsideACodeFenceIndentsWithFourSpaces() {
        val body = "```kotlin\nfun main() {}\n```\n"
        val indented = pressKey(
            body = body,
            revealOffset = "```kotlin\n".length + 2,
            event = keyEvent(Key.Tab),
        )
        assertEquals("```kotlin\n    fun main() {}\n```\n", indented)
    }

    @Test
    fun customizedShortcutReplacesTheDefaultOne() {
        // 把粗体改成 Cmd/Ctrl+Shift+B 之后：Cmd+B 不再插入粗体，Cmd+Shift+B 才插入。
        val customized = effectiveMarkdownBindings(mapOf("bold" to "Ctrl/Cmd+Shift+B"))
        val defaultResult = pressKey(
            body = "文字",
            revealOffset = 0,
            event = keyEvent(Key.B, meta = true),
            bindings = customized,
        )
        assertEquals("文字", defaultResult)

        val customizedResult = pressKey(
            body = "文字",
            revealOffset = 0,
            event = keyEvent(Key.B, meta = true, shift = true),
            bindings = customized,
        )
        assertEquals("**粗体**文字", customizedResult)
    }

    @Test
    fun tabOnAnEmptyCodeFenceLineAddsFourSpaces() {
        // 代码块里的空行按 Tab 要补缩进（代码编辑器常态），不能「按了没反应」。
        val fence = "```"
        val body = fence + "\n\n" + fence + "\n"
        val filled = pressKey(
            body = body,
            revealOffset = fence.length + 1,
            event = keyEvent(Key.Tab),
        )
        assertEquals(fence + "\n    \n" + fence + "\n", filled)
    }

    @Test
    fun aiFillTabWinsOverIndentation() {
        // 行尾是「。。。」空位时，Tab 先给 AI 补全（R2.4 的优先级），不能让缩进抢走。
        val body = "想法：。。。\n"
        var requested = false
        val result = pressKey(
            body = body,
            revealOffset = body.length - 1,
            event = keyEvent(Key.Tab),
            onRequestAiFill = { _, _ ->
                requested = true
                null
            },
        )
        assertEquals(body, result)
        assertTrue(requested, "行尾有空位时 Tab 应先触发 AI 补全")
    }
}
