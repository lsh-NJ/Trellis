package dev.trellis.app.desktop

import dev.trellis.ai.HttpLlmClient
import dev.trellis.ai.LlmConfigLoader
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.Contribution
import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.SlotAction
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.ui.currentDataContext
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 一次性联调：用户问「模型能自己补宿主能力吗？也不能做一个插件就求一次加能力吧？」
 *
 * 这里用一个**全新需求**（既不是读书、也不是任务）验证：只靠宿主已经有的通用动作，
 * 模型能不能自己拼出一个能用的插件，而不用等宿主为它加一行能力。
 *
 * 需求：「灵感便签」——输入一句话存下来；一个按钮把存下的句子写进知识库同名笔记
 * （已存在就往后追加）并跳过去打开。这正好落在通用动作 `writeKnowledgeNote`
 * （append 语义）+ `openKnowledgeNote` / `openPage` 的组合范围内。
 *
 * 验收看装出来的东西：
 *   ① 插件目录真的出现；
 *   ② 卡片上出现点名 `writeKnowledgeNote` 的按钮，且参数里带着笔记标题与已存的灵感；
 *   ③ 真点它：笔记落盘、内容含刚存的句子；再点一次是追加不是新建；
 *   ④ 卡片上还能一键打开这篇笔记。
 *
 * 只有 `TRELLIS_LIVE=1` 时才真的跑（要网络 + key，慢）。
 */
class HostActionsAgentLiveTest {

    private val request = "我要一个「灵感便签」插件：卡片上一个输入框，写一句话回车就存下来；" +
        "卡片上再放一个按钮，点它把存下的这些话写进知识库一篇叫「灵感便签」的笔记" +
        "（已经存在就往后追加，别覆盖），然后跳过去打开它。"

    @Test
    fun liveAgentComposesGenericHostActionsWithoutNewHostCode() = runBlocking {
        if (System.getenv("TRELLIS_LIVE") != "1") return@runBlocking

        val home = Files.createTempDirectory("trellis-live-host-actions").toFile().apply { deleteOnExit() }
        val source = File(System.getProperty("user.home"), ".trellis/llm.json")
        require(source.exists()) { "没有找到 ~/.trellis/llm.json" }
        source.copyTo(File(home, "llm.json"), overwrite = true)

        val runtime = AppRuntime.create(home)
        val config = LlmConfigLoader.load(home)
        requireNotNull(config) { "llm.json 没被读到" }
        runtime.llmOverride = HttpLlmClient(config)

        try {
            runtime.sendChat(request)
            var plugins = installedPluginIds(home)
            if (plugins.isEmpty() && runtime.chatAsk != null) {
                runtime.sendChat("就按你说的做，直接装好；宿主已经有「写知识库笔记」「打开笔记」这类通用动作，不用再找人加能力。")
                plugins = installedPluginIds(home)
            }

            val lines = runtime.chat.toList()
            val steps = lines.filter { it.role == ChatRole.Step }.map { it.text }
            val answer = lines.lastOrNull { it.role == ChatRole.Assistant }?.text.orEmpty()

            val card = findNoteCard(runtime, plugins)
            val slot = card?.value as? DynamicSlot
            var nodes = slot?.let { collect(it.build(currentDataContext())) }.orEmpty()

            // 需求 1：输入一句话存下来（脚本自己的 handler）。
            val inspiration = "灵感：今天的插件应该自己会找路"
            var seeded: String? = null
            if (slot != null) {
                val input = nodes.filterIsInstance<UiNode.Input>()
                    .firstOrNull { (it.onSubmit as? UiTap.Plugin) != null }
                val handler = (input?.onSubmit as? UiTap.Plugin)?.handler
                if (handler != null) {
                    seeded = inspiration
                    runCatching { slot.perform(handler, mapOf("value" to inspiration)) }
                    nodes = collect(slot.build(currentDataContext()))
                }
            }

            // 需求 2：按钮点名通用动作 writeKnowledgeNote，且参数里带着笔记标题 + 刚存的灵感。
            val writeButton = nodes.filterIsInstance<UiNode.Button>().firstOrNull { button ->
                val tap = button.onTap
                tap is UiTap.Host && tap.call.action == "writeKnowledgeNote"
            }
            val writeArgs = (writeButton?.onTap as? UiTap.Host)?.call?.args.orEmpty()
            var writeOutcome: String? = null
            var firstNotes: List<File> = emptyList()
            var appended = false
            if (writeButton != null && slot != null && card != null) {
                val call = (writeButton.onTap as UiTap.Host).call
                val first = runtime.performSlotAction(card.owner, SlotAction(writeButton.label, call))
                writeOutcome = first.message
                firstNotes = notes(home)
                // 再点一次：应当追加到同一篇，而不是再建一篇。
                runtime.performSlotAction(card.owner, SlotAction(writeButton.label, call))
                val after = notes(home)
                appended = after.size == firstNotes.size
            }

            // 需求 3：一键打开这篇笔记（writeKnowledgeNote 不负责跳转，应另有 openPage/openKnowledgeNote）。
            val openButton = nodes.filterIsInstance<UiNode.Button>().firstOrNull { button ->
                val tap = button.onTap
                tap is UiTap.Host && (tap.call.action == "openKnowledgeNote" || tap.call.action == "openPage")
            }
            var revealOk = false
            var openOutcome: String? = null
            if (openButton != null && card != null) {
                val call = (openButton.onTap as UiTap.Host).call
                val open = runtime.performSlotAction(card.owner, SlotAction(openButton.label, call))
                openOutcome = open.message
                revealOk = open.ok && (runtime.pendingKnowledgeReveal != null || runtime.currentTab == TAB_KNOWLEDGE)
            }

            val report = buildString {
                appendLine("== request ==")
                appendLine(request)
                appendLine("== installed plugins ==")
                appendLine(plugins.joinToString("、").ifBlank { "(没有装任何插件)" })
                appendLine("== steps ==")
                steps.forEach { appendLine(it) }
                appendLine("== answer ==")
                appendLine(answer)
                appendLine("== choices ==")
                appendLine(runtime.chatAsk?.options?.map { it.label } ?: "(没有提问)")
                appendLine("== card ==")
                appendLine(if (card == null) "(没有找到能渲染的便签卡片)" else "owner=${card.owner.value} id=${card.id}")
                appendLine("== host buttons ==")
                appendLine(
                    nodes.filterIsInstance<UiNode.Button>().joinToString("、") { button ->
                        button.label + " -> " + when (val tap = button.onTap) {
                            is UiTap.Host -> "host:" + tap.call.action + tap.call.args
                            is UiTap.Plugin -> "plugin:" + tap.handler
                        }
                    }.ifBlank { "(没有按钮)" },
                )
                appendLine("== seeded inspiration ==")
                appendLine(seeded ?: "(卡片没有输入框可存)")
                appendLine("== write args ==")
                appendLine(writeArgs.toString())
                appendLine("== write ==")
                appendLine(writeOutcome ?: "(没有 writeKnowledgeNote 按钮)")
                appendLine("notes=" + firstNotes.map { it.name } + " appended=" + appended)
                appendLine("== open ==")
                appendLine(openOutcome ?: "(没有打开笔记的按钮) revealOk=" + revealOk)
                appendLine("== notes content ==")
                notes(home).forEach { appendLine("-- " + it.name + " --"); appendLine(it.readText(Charsets.UTF_8)) }
                for (id in plugins) {
                    val dir = File(home, "plugins/$id")
                    val main = listOf("main.js", "main.dsl.json")
                        .map { File(dir, it) }
                        .firstOrNull { it.exists() }
                    if (main != null) {
                        appendLine("== source: $id/${main.name} ==")
                        appendLine(main.readText(Charsets.UTF_8))
                    }
                }
            }
            File("build/live-host-actions.txt").apply { parentFile.mkdirs() }.writeText(report)

            assertTrue(plugins.isNotEmpty(), "应当真的装出一个插件：\n$report")
            assertNotNull(card, "插件应当贡献一张能渲染的便签卡片：\n$report")
            assertNotNull(
                writeButton,
                "应当靠通用动作 writeKnowledgeNote 把内容写进笔记（不该要求宿主再加新能力）：\n$report",
            )
            assertTrue(
                writeArgs.values.any { it.contains("灵感便签") },
                "writeKnowledgeNote 的参数应当带上笔记标题：$writeArgs\n$report",
            )
            assertTrue(
                writeArgs.values.any { it.contains("灵感") && it != writeArgs["title"] },
                "按钮参数里应当带上已经存下的灵感正文：$writeArgs\n$report",
            )
            assertTrue(appended, "再点一次应当追加到同一篇笔记，而不是新建：\n$report")
            assertTrue(
                notes(home).any { it.readText(Charsets.UTF_8).contains("灵感") },
                "应当真的落下一篇含灵感的笔记：" + notes(home).map { it.name } + "\n$report",
            )
            assertNotNull(openButton, "还应当有一个按钮一键打开这篇笔记：\n$report")
            assertTrue(revealOk, "点开笔记应当跳进知识库并定位，实际：$openOutcome\n$report")
            assertTrue(
                steps.any { "deploy" in it },
                "应当真的走过 deploy：\n$report",
            )
        } finally {
            runtime.shutdown()
        }
    }

    private fun installedPluginIds(home: File): List<String> =
        File(home, "plugins").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name != ".trash" && File(it, "manifest.json").exists() }
            .map { it.name }
            .sorted()

    /** 在已装插件里找「像便签/灵感」的那张动态卡。 */
    private suspend fun findNoteCard(runtime: AppRuntime, plugins: List<String>): Contribution? {
        val candidates = plugins.flatMap { runtime.contributionsOf(PluginId(it)) }
        return candidates.firstOrNull { contribution ->
            val slot = contribution.value as? DynamicSlot ?: return@firstOrNull false
            val text = runCatching { flatten(slot.build(currentDataContext())) }.getOrNull()
                ?: return@firstOrNull false
            Slots.all.any { point -> point.id == contribution.pointId } &&
                (text.contains("灵感") || text.contains("便签") || text.contains("笔记"))
        }
    }

    private fun notes(home: File): List<File> =
        File(home, "knowledge/notes").listFiles { file -> file.name.endsWith(".md") }.orEmpty().toList()

    private fun collect(node: UiNode): List<UiNode> = listOf(node) + when (node) {
        is UiNode.Column -> node.children.flatMap { collect(it) }
        is UiNode.Row -> node.children.flatMap { collect(it) }
        is UiNode.Section -> node.children.flatMap { collect(it) }
        is UiNode.Grid -> node.children.flatMap { collect(it) }
        is UiNode.Cell -> collect(node.child)
        else -> emptyList()
    }

    private fun flatten(node: UiNode): String = when (node) {
        is UiNode.Column -> node.children.joinToString("|") { flatten(it) }
        is UiNode.Row -> node.children.joinToString("|") { flatten(it) }
        is UiNode.Section -> (listOf(node.title) + node.children.map { flatten(it) }).joinToString("|")
        is UiNode.Grid -> node.children.joinToString("|") { flatten(it) }
        is UiNode.Cell -> flatten(node.child)
        is UiNode.Text -> node.text
        is UiNode.Stat -> node.label + " " + node.value
        is UiNode.Badge -> node.text
        is UiNode.Bars -> node.label
        is UiNode.Sparkline -> node.label
        is UiNode.Columns -> node.values.joinToString(",")
        is UiNode.Area -> node.values.joinToString(",")
        is UiNode.DotMatrix -> node.value
        is UiNode.Ring -> node.label
        is UiNode.Progress -> node.label
        is UiNode.Notice -> node.text
        is UiNode.Icon -> node.glyph
        is UiNode.Image -> node.alt.ifBlank { node.ref }
        is UiNode.Table -> (listOf(node.caption) + node.headers + node.rows.map { it.joinToString(" ") })
            .filter { it.isNotBlank() }.joinToString("|")
        is UiNode.Button -> node.label
        is UiNode.Input -> node.hint
        is UiNode.Stepper -> node.label + " " + node.value
        is UiNode.ListRow -> node.title + (node.trailing?.let { " " + it } ?: "")
        is UiNode.Switch -> node.label
        is UiNode.Chip -> node.label
        is UiNode.HeatStrip -> node.values.joinToString(",")
        is UiNode.Spacer -> ""
        UiNode.Divider -> ""
    }
}
