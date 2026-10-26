package dev.trellis.app.desktop

import dev.trellis.ai.AuthoredScript
import dev.trellis.ai.ScriptCardDecl
import dev.trellis.ai.ScriptThemeDecl
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 脚本插件的 manifest 里必须真的写出 `kernel.theme`：
 * 内核注册扩展点时会对照 manifest 的 contributes，少这一行，主题就装不进去。
 */
class ScriptThemeManifestTest {

    @Test
    fun aThemeIsDeclaredInTheGeneratedManifest() {
        val files = AuthoredScript(
            id = "ai.warm",
            name = "暖纸",
            script = "trellis.register({ cards: [] });",
        ).toFiles(
            cards = listOf(ScriptCardDecl("c", "slot.home.section")),
            themes = listOf(
                ScriptThemeDecl(
                    id = "warm-paper",
                    name = "暖纸",
                    description = "",
                    light = mapOf("primary" to "#8A5A2B"),
                    dark = emptyMap(),
                ),
            ),
        )

        val manifest = files.getValue("manifest.json")
        assertTrue(manifest.contains("\"kernel.theme\""), manifest)
        assertTrue(manifest.contains("warm-paper"), manifest)
        assertTrue(manifest.contains("\"slot.home.section\""), manifest)
    }
}
