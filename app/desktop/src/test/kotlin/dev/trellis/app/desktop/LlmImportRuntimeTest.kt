package dev.trellis.app.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「一键导入」落到运行时这一层的行为。
 *
 * 界面只是壳：真正要钉住的是「导入之后立刻算已配置」「导入失败不覆盖旧配置」。
 * 用的是临时 home，所以不会碰真实机器上的 llm.json。
 */
class LlmImportRuntimeTest {

    private fun runtime(): AppRuntime {
        val home = Files.createTempDirectory("trellis-llm-import").toFile().apply { deleteOnExit() }
        return runBlocking { AppRuntime.create(home) }
    }

    @Test
    fun importingAKeyTakesEffectImmediately() {
        val runtime = runtime()
        assertNull(runtime.llmConfig(), "临时 home 里不该有配置")
        assertFalse(runtime.llmConfigured())

        val outcome = runtime.importLlmConfig(
            "sk-test-1234567890",
            "https://api.deepseek.com/v1/chat/completions",
            "deepseek-chat",
        )

        assertTrue(outcome.ok, outcome.message)
        assertEquals("deepseek-chat", runtime.llmConfig()?.model)
        assertTrue(runtime.llmConfigured(), "导入之后就该算已配置")
        assertEquals("llm.json", runtime.llmSource())
        assertTrue(outcome.message.contains("deepseek"), outcome.message)
    }

    @Test
    fun aFailedImportLeavesThePreviousConfigAlone() {
        val runtime = runtime()
        runtime.importLlmConfig("sk-first-1234567890", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat")

        val failed = runtime.importLlmConfig("abc", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat")

        assertFalse(failed.ok)
        assertTrue(failed.message.contains("太短"), failed.message)
        assertEquals("sk-first-1234567890", runtime.llmConfig()?.apiKey, "失败的导入不能把旧的冲掉")
    }

    @Test
    fun aJsonPasteOverridesTheChosenPreset() {
        val runtime = runtime()
        val outcome = runtime.importLlmConfig(
            """{"endpoint":"http://localhost:11434/v1","apiKey":"ollama-local-1234","model":"qwen2.5:7b"}""",
            "https://api.deepseek.com/v1/chat/completions",
            "deepseek-chat",
        )
        assertTrue(outcome.ok, outcome.message)
        assertEquals("qwen2.5:7b", runtime.llmConfig()?.model)
        assertEquals("http://localhost:11434/v1/chat/completions", runtime.llmConfig()?.endpoint)
    }
}
