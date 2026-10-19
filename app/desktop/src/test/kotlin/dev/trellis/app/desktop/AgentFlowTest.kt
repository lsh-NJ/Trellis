package dev.trellis.app.desktop

import dev.trellis.ai.AgentOutcome
import dev.trellis.ai.LlmClient
import dev.trellis.ai.LlmMessage
import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TemplateSlot
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** 按剧本回话的假模型。 */
private class FakeLlm(vararg private val replies: String) : LlmClient {
    var calls = 0
        private set

    override suspend fun complete(messages: List<LlmMessage>): String {
        val reply = replies[minOf(calls, replies.size - 1)]
        calls++
        return reply
    }
}

private fun jsonCard(point: String = "slot.home.section", body: String = "还有 {{ summary.todo }} 项待办"): String =
    """{"id":"ai.mine","name":"我的卡片","description":"测试用","slots":[{"point":"$point","title":"标题","body":"$body"}]}"""

class AgentFlowTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-agent-", "").let { file ->
            file.delete()
            file.mkdirs()
            tempDirs += file
            file
        }

    /** 写一份假 key，让 generatePlugin 认为「配置好了」。真正的模型被 llmOverride 换掉了。 */
    private fun File.configureFakeKey() {
        writeText("""{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""")
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun withoutAKeyItDoesNotEvenStart() = runTest {
        val runtime = AppRuntime.create(tempHome())
        assertNull(runtime.generatePlugin("随便"), "没配 key 就不该发请求")
        assertTrue(runtime.agentLog.isEmpty())
        runtime.shutdown()
    }

    /**
     * 整条链路的端到端验证：
     * 假模型 -> 本地自查 -> 真装进内核 -> 界面上真的多一张卡片 -> 而且数字是真的。
     */
    @Test
    fun aGeneratedPluginShowsUpOnTheHomeScreenWithRealData() = runTest {
        val home = tempHome()
        File(home, "llm.json").configureFakeKey()

        val runtime = AppRuntime.create(home)
        runtime.llmOverride = FakeLlm(jsonCard())

        // 先造一条真实数据，好验证卡片上的数字不是写死的
        runtime.planService.createPlan("测试计划")
        val plan = runtime.planService.plans().first()
        runtime.planService.addTask("任务一", planId = plan.id)

        val outcome = runtime.generatePlugin("我想要一个显示待办数的卡片")

        assertEquals(AgentOutcome.Installed("ai.mine", 1), outcome)
        assertTrue(
            runtime.pluginInfos.any { it.manifest.id.value == "ai.mine" },
            "内核里应当多了一个插件：" + runtime.pluginInfos.map { it.manifest.id.value },
        )

        val card = assertIs<TemplateSlot>(
            runtime.kernel.extensions.all(Slots.HomeSection).single { it.id == "s1" },
        )
        val rendered = runtime.kernel.extensions.renderTemplate(card.body, DataContext.ofNow(1_000L))
        assertTrue(rendered.contains("项待办"), "卡片正文应当是求值后的：" + rendered)
        assertTrue(!rendered.contains("{{"), "占位符应当已经被替换掉：" + rendered)

        runtime.shutdown()
    }

    /** 模型第一次把挂载点写错，把报错喂回去，第二次改对了。 */
    @Test
    fun aWrongMountPointIsCorrectedOnRetry() = runTest {
        val home = tempHome()
        File(home, "llm.json").configureFakeKey()

        val runtime = AppRuntime.create(home)
        runtime.llmOverride = FakeLlm(jsonCard(point = "slot.homeSection"), jsonCard())

        val outcome = runtime.generatePlugin("随便")

        assertEquals(AgentOutcome.Installed("ai.mine", 2), outcome)
        assertTrue(
            runtime.agentLog.any { it.contains("自查没过") },
            "流水账里应当能看见它被挡过一次：" + runtime.agentLog,
        )
        assertTrue(
            runtime.pluginInfos.any { it.manifest.id.value == "ai.mine" },
            "第二次应当装上了",
        )
        runtime.shutdown()
    }

    /** 三次都错 -> 放弃，而且不留半成品文件。 */
    @Test
    fun givingUpLeavesNothingBehind() = runTest {
        val home = tempHome()
        File(home, "llm.json").configureFakeKey()

        val runtime = AppRuntime.create(home)
        runtime.llmOverride = FakeLlm(jsonCard(point = "slot.nowhere"))

        val outcome = runtime.generatePlugin("随便")

        assertIs<AgentOutcome.GaveUp>(outcome)
        assertTrue(
            runtime.pluginInfos.none { it.manifest.id.value == "ai.mine" },
            "失败不该留下插件",
        )
        assertTrue(
            !File(runtime.pluginsDir, "ai.mine").exists(),
            "失败不该在磁盘上留目录",
        )
        runtime.shutdown()
    }
}
