package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 编辑器形态解析 + 键位覆盖的存取与校验（评审意见一/三的落点）。
 *
 * 这几条都是「设置页改完必须立刻生效、坏配置不能把编辑器搞坏」的钉子：
 * - 形态解析的优先级错了，会表现成「选了段落式、重启又变回单文本」；
 * - 覆盖文件坏了/命令没了/键位非法，必须回退默认而不是让笔记打不开；
 * - 键位冲突必须在改键那一刻拒绝，不能存进去之后悄悄被忽略。
 */
class MarkdownEditorPlatformTest {

    @Test
    fun editorModePreferenceIsUsedUnlessTheEnvironmentForcesOne() {
        assertEquals(MARKDOWN_EDITOR_WEBVIEW, resolveMarkdownEditorMode(MARKDOWN_EDITOR_WEBVIEW, null))
        assertEquals(MARKDOWN_EDITOR_COMPOSE, resolveMarkdownEditorMode(MARKDOWN_EDITOR_COMPOSE, null))
        // 环境变量给演示/调试用，优先级最高。
        assertEquals(MARKDOWN_EDITOR_WEBVIEW, resolveMarkdownEditorMode(MARKDOWN_EDITOR_COMPOSE, "webview"))
        assertEquals(MARKDOWN_EDITOR_COMPOSE, resolveMarkdownEditorMode(MARKDOWN_EDITOR_WEBVIEW, "compose"))
        assertEquals(MARKDOWN_EDITOR_COMPOSE, resolveMarkdownEditorMode(MARKDOWN_EDITOR_WEBVIEW, " COMPOSE "))
    }

    @Test
    fun unknownEditorModeFallsBackToWebView() {
        // 2026-11-15 起默认段落式：配置坏了/没配置都该落到 WebView，而不是悄悄退回单文本。
        assertEquals(MARKDOWN_EDITOR_WEBVIEW, resolveMarkdownEditorMode("tiptap", null))
        assertEquals(MARKDOWN_EDITOR_WEBVIEW, resolveMarkdownEditorMode("", null))
    }

    @Test
    fun keyStoreRoundTripsOverridesAndDropsJunk() {
        val home = Files.createTempDirectory("trellis-markdown-keys").toFile().apply { deleteOnExit() }
        val store = MarkdownKeyStore(home)
        store.save(
            mapOf(
                "bold" to "Ctrl/Cmd+Shift+B",
                "italic" to "", // 明确解绑
                "no-such-command" to "Ctrl/Cmd+J", // 命令不存在，丢
                "strike" to "随便写", // 非法键位，丢
            ),
        )
        val loaded = store.load()
        assertEquals("Ctrl/Cmd+Shift+B", loaded["bold"])
        assertEquals("", loaded["italic"])
        assertNull(loaded["no-such-command"])
        assertNull(loaded["strike"])
    }

    @Test
    fun brokenOverrideFileIsTreatedAsNoOverrides() {
        val home = Files.createTempDirectory("trellis-markdown-keys-bad").toFile().apply { deleteOnExit() }
        File(home, "markdown.json").writeText("{ not json ", Charsets.UTF_8)
        assertTrue(MarkdownKeyStore(home).load().isEmpty())
    }

    @Test
    fun runtimeAppliesAndRejectsShortcutChanges() {
        val home = Files.createTempDirectory("trellis-markdown-runtime").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        try {
            val changed = runtime.updateMarkdownBinding("bold", "Ctrl/Cmd+Shift+B")
            assertTrue(changed.contains("改为"), changed)
            assertEquals("Ctrl/Cmd+Shift+B", runtime.markdownKeys["bold"])
            assertEquals(
                "Ctrl/Cmd+Shift+B",
                runtime.markdownBindings.first { it.commandId == "bold" }.label,
            )

            // 冲突：Shift+B 已经被粗体占了，斜体不能再绑同一个键。
            val conflict = runtime.updateMarkdownBinding("italic", "Ctrl/Cmd+Shift+B")
            assertTrue(conflict.contains("占用"), conflict)
            assertFalse(runtime.markdownKeys.containsKey("italic"))

            // 解绑：命令从生效表里消失，但覆盖表记着「明确不要键位」。
            runtime.updateMarkdownBinding("bold", "")
            assertTrue(runtime.markdownBindings.none { it.commandId == "bold" })
            assertEquals("", runtime.markdownKeys["bold"])

            // 恢复默认：覆盖表清空，默认键位回来。
            runtime.resetMarkdownBindings()
            assertTrue(runtime.markdownKeys.isEmpty())
            assertEquals("Ctrl/Cmd+B", runtime.markdownBindings.first { it.commandId == "bold" }.label)
        } finally {
            runBlocking { runtime.shutdown() }
        }
    }

    @Test
    fun runtimeSwitchesEditorModeThroughUiPrefs() {
        val home = Files.createTempDirectory("trellis-markdown-editor-mode").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        try {
            assertEquals(MARKDOWN_EDITOR_WEBVIEW, runtime.uiPrefs.editorMode)
            runtime.updateEditorMode(MARKDOWN_EDITOR_COMPOSE)
            assertEquals(MARKDOWN_EDITOR_COMPOSE, runtime.uiPrefs.editorMode)
            assertEquals(MARKDOWN_EDITOR_COMPOSE, UiPrefsStore(home).load().editorMode)
            runtime.updateEditorMode("nonsense")
            assertEquals(MARKDOWN_EDITOR_WEBVIEW, runtime.uiPrefs.editorMode)
        } finally {
            runBlocking { runtime.shutdown() }
        }
    }

    @Test
    fun keySwapsStayConsistentBetweenStorageAndEffectiveBindings() {
        val home = Files.createTempDirectory("trellis-markdown-swap").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        try {
            // 轮流让位：斜体先挪走，粗体占 I，斜体再占 B——每一步都该真正生效。
            runtime.updateMarkdownBinding("italic", "Ctrl/Cmd+Alt+I")
            runtime.updateMarkdownBinding("bold", "Ctrl/Cmd+I")
            val swapped = runtime.updateMarkdownBinding("italic", "Ctrl/Cmd+B")
            assertTrue(swapped.contains("改为"), swapped)
            // 存进去的覆盖 == 算出来的生效键位（不能出现「说改好了、按下去还是旧键」）。
            for ((commandId, label) in runtime.markdownKeys) {
                assertEquals(
                    label,
                    runtime.markdownBindings.first { it.commandId == commandId }.label,
                    "覆盖和生效键位对不上：" + commandId,
                )
            }
            // 这时把斜体恢复默认会和粗体现在占着的 Ctrl/Cmd+I 撞车：必须拒绝并说清是谁占着。
            val rejected = runtime.resetMarkdownBinding("italic")
            assertTrue(rejected.contains("占"), rejected)
            assertTrue(runtime.markdownKeys.containsKey("italic"), "被拒绝的恢复不能改配置")
        } finally {
            runBlocking { runtime.shutdown() }
        }
    }

    @Test
    fun pageShortcutsCannotBeBoundToCommands() {
        val home = Files.createTempDirectory("trellis-markdown-reserved").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        try {
            // Ctrl/Cmd+S/N//Q 是页面/窗口快捷键，绑给命令永远不会生效（Q 还会退应用）。
            for (reserved in listOf("Ctrl/Cmd+S", "Ctrl/Cmd+N", "Ctrl/Cmd+/", "Ctrl/Cmd+Q")) {
                val rejected = runtime.updateMarkdownBinding("bold", reserved)
                assertTrue(rejected.contains("页面") || rejected.contains("窗口"), rejected)
                assertFalse(runtime.markdownKeys.containsKey("bold"))
            }
        } finally {
            runBlocking { runtime.shutdown() }
        }
    }

    @Test
    fun javaFxFailureFallsBackInsteadOfMountingTheWebView() {
        // JavaFX 起不来时必须回退单文本：硬挂 JFXPanel 会让 EDT 卡在 secondary loop 上，整页假死。
        assertEquals(WebViewState.Ready, webViewStateFor(javaFxReady = true))
        assertEquals(WebViewState.Unavailable, webViewStateFor(javaFxReady = false))
    }
}
