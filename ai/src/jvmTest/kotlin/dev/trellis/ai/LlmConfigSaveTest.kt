package dev.trellis.ai

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「导入」最终要落到磁盘上，所以这里钉住三件事：
 * 写出来的文件能被读回来、**不带 BOM**、没配 key 时读出来是 null 而不是空配置。
 */
class LlmConfigSaveTest {

    private fun home(): java.io.File =
        Files.createTempDirectory("trellis-llm").toFile().apply { deleteOnExit() }

    @Test
    fun savedConfigReadsBackThroughTheFileOnlyPath() {
        val home = home()
        val config = LlmConfig(
            endpoint = "https://api.deepseek.com/v1/chat/completions",
            apiKey = "sk-test-1234567890",
            model = "deepseek-chat",
        )
        val file = LlmConfigLoader.save(home, config)

        assertTrue(file.isFile, "llm.json 应该被创建")
        assertEquals(config, LlmConfigLoader.fromFile(home))
        assertEquals("llm.json", LlmConfigLoader.source(home))
    }

    @Test
    fun theFileIsWrittenWithoutABom() {
        val home = home()
        val file = LlmConfigLoader.save(home, LlmConfig("https://api.deepseek.com/v1/chat/completions", "sk-abcdef123456", "deepseek-chat"))
        val bytes = file.readBytes()
        // UTF-8 BOM 的 EF BB BF 会让 JSON 解析器认不出第一块，整份配置静默失效。
        assertFalse(
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte(),
            "llm.json 不能带 BOM",
        )
        assertTrue(String(bytes, Charsets.UTF_8).trimStart().startsWith("{"), "应该是一个 JSON 对象")
    }

    @Test
    fun anEmptyHomeReadsAsNotConfigured() {
        val home = home()
        assertNull(LlmConfigLoader.fromFile(home))
        assertEquals("未配置", LlmConfigLoader.source(home))
        assertNull(LlmConfigLoader.load(home))
    }

    @Test
    fun aHandWrittenFileWithBomStillLoads() {
        // 用户拿记事本改过的文件会带 BOM：读的时候必须剥掉，不能因此变成「没配」。
        val home = home()
        val file = java.io.File(home, "llm.json")
        file.writeText("\uFEFF" + """{"endpoint":"https://api.deepseek.com/v1/chat/completions","apiKey":"sk-bom-1234567890","model":"deepseek-chat"}""")
        val config = LlmConfigLoader.fromFile(home)
        assertNotNull(config)
        assertEquals("sk-bom-1234567890", config.apiKey)
    }
}
