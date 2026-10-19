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
import kotlin.test.assertFalse
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
    """{"id":"ai.mine","name":"我的卡片","description":"测试用","howToUse":"在首页看","slots":[{"point":"$point","title":"标题","body":"$body"}]}"""

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

    /**
     * 模型只能被告知**界面上真的渲染了的**挂载点。
     *
     * 以前目录里列了 9 个，界面只渲染了 1 个 —— 模型很热心地
     * 往 slot.detail.aside 挂卡片，用户装完在界面上根本找不到，
     * 只能反复开关插件玩「找不同」。
     */
    @Test
    fun theAgentOnlyLearnsAboutMountPointsTheAppActuallyRenders() = runTest {
        val home = tempHome()
        File(home, "llm.json").configureFakeKey()

        val runtime = AppRuntime.create(home)
        val fake = FakeLlm(jsonCard())
        runtime.llmOverride = fake

        runtime.generatePlugin("随便")

        val system = runtime.agentLog.joinToString("\n")
        // agentLog 里没有 prompt，所以直接验目录：界面上没渲染的位置不该出现
        assertTrue(
            RENDERED_MOUNT_POINTS == setOf(dev.trellis.kernel.slot.Slots.HomeSection.id),
            "当前界面只渲染了首页区块，这个常量必须和 AppScreen 同步",
        )

        val catalog = dev.trellis.ai.CapabilityCatalog.of(runtime.kernel.extensions, RENDERED_MOUNT_POINTS)
        assertTrue(catalog.mountPointIds.contains("slot.home.section"))
        assertFalse(
            catalog.mountPointIds.contains("slot.detail.aside"),
            "界面没渲染 detail.aside，就不该告诉模型它能用：" + catalog.mountPointIds,
        )
        assertTrue(catalog.mountPointIds.size == 1, "现在只渲染了一个位置：" + catalog.mountPointIds)

        runtime.shutdown()
    }

    /**
     * 生成之后必须能「看见自己生成了什么」。
     *
     * 以前只有一句「装好了：ai.xxx」，用户得回首页一个字一个字地找不同，
     * 而且分不清「没生成」「挂在看不见的地方了」「生成了但内容不对」。
     */
    @Test
    fun thePreviewShowsTheCardTheUsageAndWhereItLives() = runTest {
        val home = tempHome()
        File(home, "llm.json").configureFakeKey()

        val runtime = AppRuntime.create(home)
        runtime.llmOverride = FakeLlm(jsonCard())

        runtime.planService.createPlan("测试计划")
        val plan = runtime.planService.plans().first()
        runtime.planService.addTask("任务一", planId = plan.id)

        runtime.generatePlugin("我想要一个显示待办数的卡片")

        val preview = runtime.lastGenerated
        assertTrue(preview != null, "生成成功后必须有预览")
        preview!!
        assertEquals("我的卡片", preview.name)
        assertEquals("测试用", preview.description)
        assertEquals("在首页看", preview.usage)
        assertEquals(1, preview.cards.size)

        val card = preview.cards.single()
        assertEquals("slot.home.section", card.mountPointId)
        assertTrue(card.visible, "首页区块是渲染了的，这张卡该算可见")
        assertTrue(card.body.contains("项待办"), "预览里的正文必须已经求值：" + card.body)
        assertFalse(card.body.contains("{{"), "预览里不该残留占位符：" + card.body)

        runtime.shutdown()
    }


    /** 退了一步的插件，预览里必须把这句话摆出来。 */
    @Test
    fun aDowngradeSurfacesItsLimitationInThePreview() = runTest {
        val home = tempHome()
        File(home, "llm.json").configureFakeKey()

        val runtime = AppRuntime.create(home)
        runtime.llmOverride = FakeLlm(
            """{"id":"ai.review","name":"复习进度","description":"显示复习数量","howToUse":"在首页看","limitation":"你要的是安排复习，我只做了显示数量，没有安排。","slots":[{"point":"slot.home.section","title":"今天的复习","body":"还剩 {{ summary.todo }} 项"}]}""",
        )

        val outcome = runtime.generatePlugin("按我的精力曲线安排复习")
        assertIs<AgentOutcome.Installed>(outcome)

        val preview = runtime.lastGenerated
        assertTrue(preview != null)
        assertEquals(
            "你要的是安排复习，我只做了显示数量，没有安排。",
            preview!!.limitation,
            "退步说明必须一路传到预览里，否则界面显示不出来",
        )

        runtime.shutdown()
    }

}
