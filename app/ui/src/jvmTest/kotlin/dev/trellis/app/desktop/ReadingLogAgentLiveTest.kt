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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 一次性联调：把用户那句原话丢给真模型，验收「现在的插件制作流程」是否真的变好。
 *
 * 复现的是用户投诉的原始场景（2026-11-10）：
 * 「我需要一个读书记录插件，我可以写入我要读的书，然后记录上次读到了哪里（页数就可以），
 *   还能一键创建并跳转对应的读书笔记（如果存在就跳转，不存在就创建）」
 * 旧版装出来的插件只有书名/页码，第 3 条完全没做，模型还写进 limitation 宣布「装好了」。
 *
 * 2026-11-10 下午用户又提了流程要求，这里一并验收：
 *   ① 先出设计、用 ask_choices 等用户确认，之后才实装（第一轮不该直接写文件）；
 *   ② 插件详情要有说明：manifest 的 description / usage 不能空；
 *   ③ 新插件默认放首页最下方：slot.home.section 的 order ≥ 500；
 *   ④ 装完的回复要给「怎么用」和「设计」，不能只回一句「装好了」。
 *
 * 功能验收不看模型嘴上说什么，看它**装出来的东西**：
 *   - home/plugins 下真的出现插件目录；
 *   - 卡片渲染出来，带书名/页码类的进度控件，以及一个点名宿主动作
 *     `openKnowledgeNote` 的入口（按钮或列表行都算，用户要求「点书名就能开笔记」）；
 *   - 真点这个入口：笔记文件落盘、跳转目标指向它；再点一次不重复建。
 *
 * 不是回归测试（要网络、要 key、慢），只有 `TRELLIS_LIVE=1` 时才真的跑。
 */
class ReadingLogAgentLiveTest {

    private val request = "我需要一个读书记录插件，我可以写入我要读的书，然后记录上次读到了哪里" +
        "（页数就可以），还能一键创建并跳转对应的读书笔记（如果存在就跳转，不存在就创建）"

    private data class NoteEntry(val label: String, val title: String, val tap: UiTap.Host)

    private data class ManifestMeta(val description: String, val usage: String, val limitation: String)

    @Test
    fun liveAgentAsksForDesignFirstThenBuildsAWorkingReadingLog() = runBlocking {
        if (System.getenv("TRELLIS_LIVE") != "1") return@runBlocking

        val home = Files.createTempDirectory("trellis-live-reading").toFile().apply { deleteOnExit() }
        val source = File(System.getProperty("user.home"), ".trellis/llm.json")
        require(source.exists()) { "没有找到 ~/.trellis/llm.json" }
        source.copyTo(File(home, "llm.json"), overwrite = true)

        val runtime = AppRuntime.create(home)
        val config = LlmConfigLoader.load(home)
        requireNotNull(config) { "llm.json 没被读到" }
        runtime.llmOverride = HttpLlmClient(config)

        try {
            // 第一轮：按新流程，模型应该先给设计并用 ask_choices 等确认，而不是直接写文件。
            runtime.sendChat(request)
            val designAsk = runtime.chatAsk
            val designAnswer = lastAssistant(runtime)
            var plugins = installedPluginIds(home)
            var confirmed = false

            // 替用户确认设计（这就是真实用户会点的那一下）。
            if (designAsk != null) {
                val yes = designAsk.options.firstOrNull { option ->
                    val label = option.label
                    label.contains("按") || label.contains("可以") || label.contains("好") ||
                        label.contains("做") || label.contains("装")
                } ?: designAsk.options.firstOrNull()
                runtime.sendChat(yes?.label ?: "按这个设计做，直接装好。")
                confirmed = true
                plugins = installedPluginIds(home)
            }

            // 确认后模型可能还要再问一轮（比如确认细节），最多再推两次。
            var rounds = 0
            while (plugins.isEmpty() && runtime.chatAsk != null && rounds < 2) {
                runtime.sendChat("就按你说的设计装好，别再问了，做不到的如实说。")
                plugins = installedPluginIds(home)
                rounds++
            }

            val lines = runtime.chat.toList()
            val steps = lines.filter { it.role == ChatRole.Step }.map { it.text }
            val answer = lastAssistant(runtime)

            // 看模型装出来的东西，不看它嘴上怎么报。
            val card = findReadingCard(runtime, plugins)
            val slot = card?.value as? DynamicSlot
            var nodes = slot?.let { collect(it.build(currentDataContext())) }.orEmpty()
            var noteEntry = findOpenNote(nodes)

            // 空 store 渲染时当然没有书。先通过卡片自己的「加书」输入框加一本，
            // 再重新渲染 —— 这一步同时验证了「写入要读的书」这条需求。
            var seededBook: String? = null
            if (slot != null && noteEntry == null) {
                val addInput = nodes.filterIsInstance<UiNode.Input>()
                    .firstOrNull { it.hint.contains("书") }
                val handler = (addInput?.onSubmit as? UiTap.Plugin)?.handler
                if (handler != null) {
                    seededBook = "假如给我三天光明"
                    runCatching { slot.perform(handler, mapOf("value" to seededBook)) }
                    nodes = collect(slot.build(currentDataContext()))
                    noteEntry = findOpenNote(nodes)
                }
            }

            // 进度控件：旧版是输入框写页码，新版可能是 −/+ 步进器，两者都算满足。
            val hasProgressControl = nodes.any {
                (it is UiNode.Input && (it.hint.contains("页") || it.hint.contains("读"))) ||
                    (it is UiNode.Stepper && (it.unit.contains("页") || it.label.contains("页")))
            }

            var clickOutcome: String? = null
            var noteFiles: List<File> = emptyList()
            var revealOk = false
            var noDuplicate = false
            if (card != null && slot != null && noteEntry != null) {
                val first = runtime.performSlotAction(card.owner, SlotAction(noteEntry.label, noteEntry.tap.call))
                clickOutcome = first.message
                revealOk = first.ok && runtime.pendingKnowledgeReveal != null &&
                    runtime.currentTab == TAB_KNOWLEDGE
                noteFiles = notes(home)
                val countAfterFirst = noteFiles.size

                runtime.pendingKnowledgeReveal = null
                runtime.performSlotAction(card.owner, SlotAction(noteEntry.label, noteEntry.tap.call))
                noDuplicate = notes(home).size == countAfterFirst
            }

            // 插件详情与摆放：manifest 说明不能空，首页卡片 order 要落到最下方。
            val installedManifest = plugins.firstNotNullOfOrNull { id ->
                readManifestMeta(File(home, "plugins/$id/manifest.json"))
            }
            val cardOrder = card?.order

            val report = buildString {
                appendLine("== request ==")
                appendLine(request)
                appendLine("== designAsk (第一轮有没有先问设计) ==")
                appendLine(
                    if (designAsk == null) "(没提问，直接动手了)"
                    else designAsk.options.joinToString("、") { it.label } + " | question=" + designAsk.question,
                )
                appendLine("== designAnswer ==")
                appendLine(designAnswer)
                appendLine("== confirmed ==")
                appendLine(confirmed.toString() + " / extraRounds=" + rounds)
                appendLine("== installed plugins ==")
                appendLine(plugins.joinToString("、").ifBlank { "(没有装任何插件)" })
                appendLine("== manifest 说明 ==")
                appendLine(
                    installedManifest?.let {
                        "description=" + it.description.ifBlank { "(空)" } +
                            "\nusage=" + it.usage.ifBlank { "(空)" } +
                            "\nlimitation=" + it.limitation.ifBlank { "(空)" }
                    } ?: "(没读到 manifest)",
                )
                appendLine("== card order ==")
                appendLine(cardOrder?.toString() ?: "(没有卡片)")
                appendLine("== steps ==")
                steps.forEach { appendLine(it) }
                appendLine("== answer ==")
                appendLine(answer)
                appendLine("== choices ==")
                appendLine(runtime.chatAsk?.options?.map { it.label } ?: "(没有待答提问)")
                appendLine("== card ==")
                appendLine(
                    if (card == null) {
                        "(没有找到能渲染的读书记录卡片；已装插件：" + plugins.joinToString("、") + ")"
                    } else {
                        "owner=${card.owner.value} id=${card.id} order=${card.order}"
                    },
                )
                appendLine("== clickable entries ==")
                appendLine(
                    nodes.filter { it is UiNode.Button || it is UiNode.ListRow }.joinToString("、") { node ->
                        when (node) {
                            is UiNode.Button -> node.label + " -> " + describeTap(node.onTap)
                            is UiNode.ListRow -> node.title + " -> " + describeTap(node.onTap)
                            else -> ""
                        }
                    }.ifBlank { "(没有按钮/可点行)" },
                )
                appendLine("== progress control ==")
                appendLine(hasProgressControl.toString())
                appendLine("== seeded book ==")
                appendLine(seededBook ?: "(卡片自己就有书，没加)")
                appendLine("== click ==")
                appendLine(clickOutcome ?: "(没点到)")
                appendLine("notes=" + noteFiles.map { it.name } + " revealOk=" + revealOk + " noDuplicate=" + noDuplicate)
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
            File("build/live-reading-log.txt").apply { parentFile.mkdirs() }.writeText(report)

            // 流程要求 ①：先设计、等确认。
            assertTrue(
                designAsk != null,
                "第一轮应当先给设计并用 ask_choices 等用户确认，而不是直接装：\n$report",
            )
            assertTrue(plugins.isNotEmpty(), "确认后应当真的装出一个插件：\n$report")
            // 流程要求 ②：插件详情要有说明。
            assertNotNull(installedManifest, "应当能读到插件 manifest：\n$report")
            assertTrue(
                installedManifest!!.description.isNotBlank() && installedManifest.usage.isNotBlank(),
                "manifest 的 description / usage 必须写（插件详情页会展示）：\n$report",
            )
            // 流程要求 ③：新插件默认放首页最下方。
            assertNotNull(card, "插件应当贡献一张能在挂载点渲染的卡片：\n$report")
            assertTrue((cardOrder ?: 0) >= 500, "新插件首页卡片 order 应 ≥500（放最下方）：\n$report")
            // 流程要求 ④：装完回报用法与设计。
            assertTrue(
                answer.contains("怎么用") || answer.contains("用法"),
                "装完的回复应当讲「怎么用」：\n$report",
            )
            val flattened = slot?.build(currentDataContext())?.let { flatten(it) }.orEmpty()
            assertTrue(flattened.contains("书"), "卡片上要能写书名：\n$report")
            assertTrue(hasProgressControl, "卡片上要有记录读到第几页的控件：\n$report")
            assertNotNull(
                noteEntry,
                "应当有「打开笔记」入口并点名宿主动作 openKnowledgeNote —— " +
                    "这是用户第 3 条要求，旧版就是在这一步静默降级：\n$report",
            )
            assertTrue(noteEntry!!.title.contains("书"), "打开笔记应当把书名带进标题：\n$report")
            assertTrue(revealOk, "点「打开笔记」应当新建/打开笔记并跳过去，实际：" + clickOutcome + "\n$report")
            assertTrue(
                noteFiles.any { it.readText(Charsets.UTF_8).contains(noteEntry.title.substringBefore("》")) } ||
                    noteFiles.any { it.readText(Charsets.UTF_8).contains("假如给我三天光明") },
                "应当真的落下一篇读书笔记：" + noteFiles.map { it.name } + "\n$report",
            )
            assertTrue(noDuplicate, "同名笔记已存在时应当打开而不是再建一篇：\n$report")
            assertTrue(steps.any { "deploy" in it }, "应当真的走过 deploy：\n$report")
        } finally {
            runtime.shutdown()
        }
    }

    private fun lastAssistant(runtime: AppRuntime): String =
        runtime.chat.toList().lastOrNull { it.role == ChatRole.Assistant }?.text.orEmpty()

    private fun readManifestMeta(file: File): ManifestMeta? = runCatching {
        val obj = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.content.orEmpty()
        ManifestMeta(str("description"), str("usage"), str("limitation"))
    }.getOrNull()

    private fun describeTap(tap: UiTap?): String = when (tap) {
        is UiTap.Host -> "host:" + tap.call.action + tap.call.args
        is UiTap.Plugin -> "plugin:" + tap.handler
        null -> "无可点动作"
    }

    /** 只看装出来的插件目录（回收站不算）。 */
    private fun installedPluginIds(home: File): List<String> =
        File(home, "plugins").listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name != ".trash" && File(it, "manifest.json").exists() }
            .map { it.name }
            .sorted()

    /** 找点名 openKnowledgeNote 的可点元素：按钮或列表行（「点书名开笔记」那种）。 */
    private fun findOpenNote(nodes: List<UiNode>): NoteEntry? {
        for (node in nodes) {
            val label: String
            val tap: UiTap?
            when (node) {
                is UiNode.Button -> {
                    label = node.label
                    tap = node.onTap
                }
                is UiNode.ListRow -> {
                    label = node.title
                    tap = node.onTap
                }
                else -> continue
            }
            val host = tap as? UiTap.Host ?: continue
            if (host.call.action != "openKnowledgeNote") continue
            val title = host.call.args["title"] ?: continue
            return NoteEntry(label, title, host)
        }
        return null
    }

    private suspend fun findReadingCard(runtime: AppRuntime, plugins: List<String>): Contribution? {
        val candidates = plugins.flatMap { runtime.contributionsOf(PluginId(it)) }
        return candidates.firstOrNull { contribution ->
            val slot = contribution.value as? DynamicSlot ?: return@firstOrNull false
            val text = runCatching { flatten(slot.build(currentDataContext())) }.getOrNull()
                ?: return@firstOrNull false
            Slots.all.any { point -> point.id == contribution.pointId } &&
                (text.contains("书") || text.contains("第几页") || text.contains("页"))
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
