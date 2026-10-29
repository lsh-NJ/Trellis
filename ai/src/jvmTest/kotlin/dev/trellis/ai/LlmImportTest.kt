package dev.trellis.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「一键导入」的解析规则。
 *
 * 这一层是纯函数，所以可以把「用户到底会粘进来什么」逐条钉住：
 * 裸 key、整段 JSON、服务商文档里那一行、以及各种写错的形态。
 */
class LlmImportTest {

    private val preset = LlmPreset("DeepSeek", "https://api.deepseek.com/v1/chat/completions", "deepseek-chat")

    @Test
    fun aBareKeyUsesTheSelectedPreset() {
        val result = LlmImport.parse("sk-1234567890abcdef", preset.endpoint, preset.model)
        assertTrue(result.ok, result.error ?: "")
        assertEquals("sk-1234567890abcdef", result.config?.apiKey)
        assertEquals(preset.endpoint, result.config?.endpoint)
        assertEquals("deepseek-chat", result.config?.model)
    }

    @Test
    fun aFullJsonOverridesThePreset() {
        val raw = """{"endpoint":"https://api.moonshot.cn/v1/chat/completions","apiKey":"sk-moon-123456","model":"moonshot-v1-8k"}"""
        val result = LlmImport.parse(raw, preset.endpoint, preset.model)
        assertEquals("sk-moon-123456", result.config?.apiKey)
        assertEquals("moonshot-v1-8k", result.config?.model)
        assertEquals("https://api.moonshot.cn/v1/chat/completions", result.config?.endpoint)
    }

    @Test
    fun serviceAliasesAreAccepted() {
        // 服务商控制台导出的字段名五花八门：baseUrl / key / token 都得认。
        val raw = """{"baseUrl":"https://api.openai.com/v1","key":"sk-alias-123456"}"""
        val result = LlmImport.parse(raw, preset.endpoint, preset.model)
        assertEquals("sk-alias-123456", result.config?.apiKey)
        // 只到 /v1 的地址要补成能直接 POST 的完整地址。
        assertEquals("https://api.openai.com/v1/chat/completions", result.config?.endpoint)
        assertEquals("deepseek-chat", result.config?.model)
    }

    @Test
    fun oneLineUrlKeyModelWorks() {
        val raw = "https://api.deepseek.com  sk-line-123456  deepseek-reasoner"
        val result = LlmImport.parse(raw, preset.endpoint, preset.model)
        assertEquals("sk-line-123456", result.config?.apiKey)
        assertEquals("deepseek-reasoner", result.config?.model)
        assertEquals("https://api.deepseek.com/v1/chat/completions", result.config?.endpoint)
    }

    @Test
    fun aBareHostGetsV1AndAFullPathIsLeftAlone() {
        assertEquals("https://api.deepseek.com/v1/chat/completions", LlmImport.normalizeEndpoint("https://api.deepseek.com"))
        // 末尾的斜杠会被去掉：发请求时多一个斜杠是 404，少一个不是。
        assertEquals("https://api.deepseek.com/v1/chat/completions", LlmImport.normalizeEndpoint("https://api.deepseek.com/v1/chat/completions/"))
        assertEquals("http://localhost:11434/v1/chat/completions", LlmImport.normalizeEndpoint("http://localhost:11434"))
        assertNull(LlmImport.normalizeEndpoint("api.deepseek.com"))
    }

    @Test
    fun aUrlWithoutAKeySaysWhatIsMissing() {
        val result = LlmImport.parse("https://api.deepseek.com", preset.endpoint, preset.model)
        assertNull(result.config)
        assertTrue(result.error.orEmpty().contains("key"), result.error)
    }

    @Test
    fun anEmptyPasteSaysWhatToDo() {
        val result = LlmImport.parse("   \n  ", preset.endpoint, preset.model)
        assertNull(result.config)
        assertTrue(result.error.orEmpty().contains("粘贴"), result.error)
    }

    @Test
    fun aTooShortKeyIsRejectedInsteadOfSaved() {
        val result = LlmImport.parse("sk-1", preset.endpoint, preset.model)
        assertNull(result.config)
        assertTrue(result.error.orEmpty().contains("太短"), result.error)
    }

    @Test
    fun aKeyWithWhitespaceIsRejected() {
        val result = LlmImport.parse("sk-1234 5678901", preset.endpoint, preset.model)
        assertNull(result.config)
        assertTrue(result.error.orEmpty().contains("空格"), result.error)
    }

    @Test
    fun aBrokenJsonIsRejectedWithAnActionableMessage() {
        val result = LlmImport.parse("""{"apiKey": "sk-1234567890",""", preset.endpoint, preset.model)
        assertNull(result.config)
        assertTrue(result.error.orEmpty().contains("JSON"), result.error)
    }
}
