package dev.trellis.app.desktop

import dev.trellis.ai.AuthoredScript
import dev.trellis.ai.PromptBuilder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 提示词里那个脚本例子必须真的能跑。
 *
 * 一个跑不起来的例子比没有例子更坏：模型会照着它写，然后每一份草稿都坏在同一条上，
 * 而它自己看不出错在哪。这个测试是把例子钉在沙箱上。
 */
class PromptExampleTest {
    @Test
    fun theScriptExampleInThePromptActuallyRuns() = runTest {
        val json = Json { ignoreUnknownKeys = true }
        val script = json.decodeFromString<AuthoredScript>(PromptBuilder.EXAMPLE_SCRIPT)
        val complaints = script.validate()
        assertTrue(complaints.isEmpty(), "例子自己就没过自查：" + complaints)
        val issues = ScriptSelfTest().test(mapOf("main.js" to script.script))
        assertTrue(issues.isEmpty(), "例子跑不起来：" + issues.map { it.code + " " + it.message })
    }
}
