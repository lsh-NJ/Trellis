package dev.trellis.app.desktop

import dev.trellis.ai.HttpLlmClient
import dev.trellis.ai.LlmConfigLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 一次性联调：用真 key + 真网络，把「一句话 -> 查 B 站 -> 拉分集 -> 出清单」整条链路跑一遍。
 *
 * 不是回归测试（要网络、要 key、慢），所以只有 `TRELLIS_LIVE=1` 时才真的跑；
 * 平时它是一名空跑，不会拖慢 `gradlew test`。
 */
class BilibiliAgentLiveTest {

    @Test
    fun liveTurnSearchesBilibiliAndLaysOutAPlan() = runBlocking {
        if (System.getenv("TRELLIS_LIVE") != "1") return@runBlocking

        val home = Files.createTempDirectory("trellis-live").toFile().apply { deleteOnExit() }
        val source = File(System.getProperty("user.home"), ".trellis/llm.json")
        require(source.exists()) { "没有找到 ~/.trellis/llm.json" }
        source.copyTo(File(home, "llm.json"), overwrite = true)

        val runtime = AppRuntime.create(home)
        val config = LlmConfigLoader.load(home)
        requireNotNull(config) { "llm.json 没被读到" }
        runtime.llmOverride = HttpLlmClient(config)

        runtime.sendChat("我想学操作系统，帮我从 B 站找一套课，把分集排成学习计划")

        val lines = runtime.chat.toList()
        val steps = lines.filter { it.role == ChatRole.Step }.map { it.text }
        val plan = lines.lastOrNull { it.plan.isNotEmpty() }?.plan

        val report = buildString {
            appendLine("== intent/plan ==")
            appendLine("lines=${lines.size} steps=${steps.size} planSteps=${plan?.size}")
            plan?.forEachIndexed { i, s -> appendLine("${i + 1}. [${s.state}] ${s.text} ${s.impact}") }
            appendLine("== steps ==")
            steps.forEach { appendLine(it) }
            appendLine("== answer ==")
            appendLine(lines.lastOrNull { it.role == ChatRole.Assistant }?.text)
        }
        File("build/live-bilibili.txt").apply { parentFile.mkdirs() }.writeText(report)

        assertTrue(steps.any { "search_bilibili" in it }, "应当调用 search_bilibili：\n$report")
        assertTrue(steps.any { "list_bilibili_parts" in it }, "应当调用 list_bilibili_parts：\n$report")
        assertTrue((plan?.size ?: 0) >= 3, "应当把分集排成清单：\n$report")
        runtime.shutdown()
    }
    /**
     * 真实事故复现：「我要速通操作系统」这一句里没有「资源/学习计划」这类词，
     * 意图落到聊天分支；模型于是吐了它自己那套 DSML 标记语言 —— 以前宿主不认，
     * 气泡里是乱码、工具一个没跑，用户看到的是「查到了就没下文」。
     */
    @Test
    fun liveShortWishStillHandsOutToolsAndLeavesASuggestion() = runBlocking {
        if (System.getenv("TRELLIS_LIVE") != "1") return@runBlocking

        val home = Files.createTempDirectory("trellis-live-short").toFile().apply { deleteOnExit() }
        val source = File(System.getProperty("user.home"), ".trellis/llm.json")
        require(source.exists()) { "没有找到 ~/.trellis/llm.json" }
        source.copyTo(File(home, "llm.json"), overwrite = true)

        val runtime = AppRuntime.create(home)
        val config = LlmConfigLoader.load(home)
        requireNotNull(config) { "llm.json 没被读到" }
        runtime.llmOverride = HttpLlmClient(config)

        runtime.sendChat("我要速通操作系统")

        val lines = runtime.chat.toList()
        val steps = lines.filter { it.role == ChatRole.Step }.map { it.text }
        val answer = lines.lastOrNull { it.role == ChatRole.Assistant }?.text.orEmpty()
        val report = buildString {
            appendLine("== steps ==")
            steps.forEach { appendLine(it) }
            appendLine("== answer ==")
            appendLine(answer)
            appendLine("== choices ==")
            appendLine(runtime.chatChoices.map { it.label })
        }
        File("build/live-bilibili-short.txt").apply { parentFile.mkdirs() }.writeText(report)

        assertTrue(steps.any { "search_bilibili" in it }, "短句也该真的去查：\n$report")
        assertFalse(answer.contains("DSML", ignoreCase = true), "协议壳不该出现在答复里：\n$report")
        // 只查没排时，要么模型调了 ask_choices（选项非空），要么它在回答里把选项写清楚 ——
        // 运行时不再替模型猜下一步，所以这里不强制一定有选项条。
        val planned = steps.any { "set_plan" in it || "add_tasks" in it }
        if (!planned) {
            val offered = runtime.chatChoices.isNotEmpty() ||
                listOf("待办", "计划", "建议").any { answer.contains(it) }
            assertTrue(offered, "只查没排时应当问用户下一步：\n$report")
        }
        runtime.shutdown()
    }

}
