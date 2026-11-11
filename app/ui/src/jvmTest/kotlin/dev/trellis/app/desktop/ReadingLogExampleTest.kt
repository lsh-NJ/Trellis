package dev.trellis.app.desktop

import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.DynamicSlot
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.slot.UiTap
import dev.trellis.ui.currentDataContext
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 示例插件「读书记录 v3」端到端验收。
 *
 * 用户 m01139/m01142 的三点：删掉多余说明行、加书/记页这类输入都改成浮窗、卡片只留数据与动作。
 * 所以这里断言的是一条真实链路，而不是「插件能显示文字」：
 *   1. 卡片结构：只有标题 + 每本一行（书名/进度/trailing），没有说明行、没有 stepper；
 *   2. 底部两个 Input 默认 popup=true（卡片上只画触发器），on 分别是 addBook / setPage；
 *   3. 点整行是 UiTap.Host 调 openKnowledgeNote，标题是「读书笔记：《书名》」；
 *   4. 真的执行一次：笔记落盘、跳转目标、当前页签都对，第二次不重复建；
 *   5. 浮窗提交（DynamicSlot.perform）真改 store：setPage 写 p:/t: 并受总页数封顶，addBook 加书/改总页数。
 *
 * 数据用 v1 的 store 键预写（books / p: / t:，没有 tot:），等于顺带证明老记录不用迁移。
 */
class ReadingLogExampleTest {

    private val tempDirs = mutableListOf<File>()

    private val book = "假如给我三天光明"

    private fun tempHome(): File =
        File.createTempFile("trellis-reading-", "").let {
            it.delete()
            it.mkdirs()
            tempDirs += it
            it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** 从仓库里找 examples/plugins/ai.reading-log，不依赖测试从哪个目录启动。 */
    private fun exampleDir(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "examples/plugins/ai.reading-log") }
            .firstOrNull { it.isDirectory }
            ?: error("找不到 examples/plugins/ai.reading-log（测试启动目录：" + File("").absolutePath + "）")

    private fun installExample(home: File) {
        val src = exampleDir()
        val dst = File(home, "plugins/ai.reading-log").apply { mkdirs() }
        for (name in listOf("manifest.json", "main.js")) {
            File(src, name).copyTo(File(dst, name), overwrite = true)
        }
    }

    /** v1 就在用的 store 键：books 用 "||" 分隔、p:书名 当前页、t:书名 时间戳；没有 tot:。 */
    private fun seedExistingData(home: File) {
        File(home, "store.json").writeText(
            """
            {
              "plugin/ai.reading-log": {
                "books": "$book",
                "p:$book": "3",
                "t:$book": "1794280111695"
              }
            }
            """.trimIndent(),
        )
    }

    /** 直接读插件命名空间，证明 handler 真的把数据写回了 store。 */
    private fun storeData(home: File): Map<String, String> {
        val root = Json.parseToJsonElement(File(home, "store.json").readText(Charsets.UTF_8)).jsonObject
        return root["plugin/ai.reading-log"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    private fun readingCard(runtime: AppRuntime): DynamicSlot {
        // 卡片注册在首页挂载点；同一个插件贡献的那张卡就是它。
        val contribution = runtime.kernel.extensions.contributionsAt(Slots.HomeSection)
            .single { it.id == "reading" }
        assertEquals(PluginId("ai.reading-log"), contribution.owner)
        assertEquals(900, contribution.order, "读书记录要排在首页已有卡片下方")
        val slot = runtime.contributionsOf(PluginId("ai.reading-log"))
            .mapNotNull { it.value as? DynamicSlot }
            .single()
        assertEquals("reading", slot.id)
        return slot
    }

    private suspend fun build(runtime: AppRuntime): UiNode = readingCard(runtime).build(currentDataContext())

    @Test
    fun theCardIsAListWithTwoPopupInputsAndNoExtraCopy() = runTest {
        val home = tempHome()
        installExample(home)
        seedExistingData(home)
        val runtime = AppRuntime.create(home)
        try {
            val node = build(runtime)
            val nodes = collect(node)
            val flattened = flatten(node)

            // 卡片上只剩标题：说明行已按用户要求删掉，空状态也不该出现。
            assertEquals(
                listOf("读书"),
                nodes.filterIsInstance<UiNode.Text>().map { it.text },
                "卡片上只该有标题这一行文字（说明行已删除）：" + flattened,
            )
            assertFalse(flattened.contains("点书名"), "不要多余的用法说明：" + flattened)
            assertFalse(flattened.contains("还没有在读的书"), "有书时不该出现空状态")

            // 书名 + 唯一一处进度 + trailing，全在同一个 ListRow 上。
            val row = nodes.filterIsInstance<UiNode.ListRow>().single { it.title == book }
            assertEquals("3 页", row.subtitle, "未设总页数时只显示当前页")
            assertEquals("打开笔记", row.trailing)
            assertEquals(1, flattened.split(book).size - 1, "书名只该出现一次：" + flattened)
            assertEquals(1, flattened.split("3 页").size - 1, "进度只该出现一次：" + flattened)

            // 点整行 = 宿主动作：打开或新建「读书笔记：《书名》」。
            val tap = assertIs<UiTap.Host>(row.onTap, "整行应当是宿主动作，不是脚本 handler")
            assertEquals("openKnowledgeNote", tap.call.action)
            assertEquals("读书笔记：《$book》", tap.call.args["title"])

            // 进度靠浮窗改：卡片上不再有 stepper。
            assertTrue(nodes.filterIsInstance<UiNode.Stepper>().isEmpty(), "v3 不再用 stepper 改页数")

            // 底部两个浮窗输入：默认 popup=true，卡片上只画触发器文字。
            val inputs = nodes.filterIsInstance<UiNode.Input>()
            assertEquals(listOf("加一本书", "记一页"), inputs.map { it.submitLabel })
            assertTrue(inputs.all { it.popup }, "两个输入都该是浮窗，卡片上只留触发器")
            assertTrue(inputs[0].hint.contains("书名"), "加书输入要说明写法：" + inputs[0].hint)
            assertTrue(inputs[1].hint.contains("记页数"), "记页输入要说明写法：" + inputs[1].hint)
            val addTap = assertIs<UiTap.Plugin>(inputs[0].onSubmit)
            assertEquals("addBook", addTap.handler)
            val setTap = assertIs<UiTap.Plugin>(inputs[1].onSubmit)
            assertEquals("setPage", setTap.handler)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun clickingTheRowCreatesThenReusesTheNote() = runTest {
        val home = tempHome()
        installExample(home)
        seedExistingData(home)
        val runtime = AppRuntime.create(home)
        try {
            val noteTitle = "读书笔记：《$book》"
            val outcome = runtime.kernel.performAction(
                PluginId("ai.reading-log"),
                ActionCall("openKnowledgeNote", mapOf("title" to noteTitle)),
            )
            assertTrue(outcome.ok, "打开笔记应当成功：" + outcome.message)

            val notesDir = File(home, "knowledge/notes")
            val files = assertNotNull(notesDir.listFiles { file -> file.name.endsWith(".md") })
            assertEquals(1, files.size, "第一次点应当新建一篇，实际：" + files.map { it.name })
            assertTrue(
                files.single().readText(Charsets.UTF_8).contains(noteTitle),
                "笔记标题应当是「读书笔记：《书名》」",
            )

            val noteId = files.single().nameWithoutExtension
            assertEquals(noteId, runtime.pendingKnowledgeReveal?.noteId, "应当跳到刚打开的这篇笔记")
            assertEquals(TAB_KNOWLEDGE, runtime.currentTab, "应当切到知识库页")

            // 再点一次：同名笔记已存在，只打开、不重复建。
            runtime.pendingKnowledgeReveal = null
            val again = runtime.kernel.performAction(
                PluginId("ai.reading-log"),
                ActionCall("openKnowledgeNote", mapOf("title" to noteTitle)),
            )
            assertTrue(again.ok, "第二次打开笔记也应当成功：" + again.message)
            assertEquals(1, notesDir.listFiles { file -> file.name.endsWith(".md") }!!.size, "不该重复建笔记")
            assertEquals(noteId, runtime.pendingKnowledgeReveal?.noteId, "第二次应当打开同一篇")
            assertEquals(TAB_KNOWLEDGE, runtime.currentTab)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun recordingAPageUpdatesStoreAndIsCappedByTotalPages() = runTest {
        val home = tempHome()
        installExample(home)
        seedExistingData(home)
        val runtime = AppRuntime.create(home)
        try {
            val slot = readingCard(runtime)

            // 浮窗「记一页」提交「书名 13」→ p:<书名> 3 → 13，t: 更新。
            val recorded = slot.perform("setPage", mapOf("value" to "$book 13"))
            assertTrue(recorded.ok, "记页数应当成功：" + recorded.message)
            assertTrue(recorded.message.contains("13"), "回执要说记到第几页：" + recorded.message)
            var store = storeData(home)
            assertEquals("13", store["p:$book"], "p:<书名> 应当写回 store")
            assertNotNull(store["t:$book"], "记页码时也要更新时间戳")
            assertEquals("13 页", listRow(runtime).subtitle)

            // 「记一页」里也可以带总分页「120/300」，顺手设总页数。
            val withTotal = slot.perform("setPage", mapOf("value" to "$book 120/300"))
            assertTrue(withTotal.ok, "带总页数应当成功：" + withTotal.message)
            assertEquals("120 ｜ 300 页", listRow(runtime).subtitle)
            assertEquals("300", storeData(home)["tot:$book"])

            // 超过总页数会被封顶，退不存在的书要给人话。
            val capped = slot.perform("setPage", mapOf("value" to "$book 1000"))
            assertTrue(capped.ok, "记页数应当成功：" + capped.message)
            assertEquals("300", storeData(home)["p:$book"], "页码不该超过总页数")
            assertEquals("300 ｜ 300 页", listRow(runtime).subtitle)

            val missing = slot.perform("setPage", mapOf("value" to "不存在的书 10"))
            assertFalse(missing.message.isBlank(), "找不到书要给一句人话提示")
            assertTrue(missing.message.contains("没有"), "提示要说清书不在列表里：" + missing.message)

            // 回退到 0 也允许。
            assertTrue(slot.perform("setPage", mapOf("value" to "$book 0")).ok)
            assertEquals("0", storeData(home)["p:$book"])
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun addingABookAcceptsTotalPagesAndDoesNotDuplicate() = runTest {
        val home = tempHome()
        installExample(home)
        seedExistingData(home)
        val runtime = AppRuntime.create(home)
        try {
            val slot = readingCard(runtime)

            // 「三体 120/300」= 加书 + 当前页 + 总页数。
            val added = slot.perform("addBook", mapOf("value" to "三体 120/300"))
            assertTrue(added.ok, "加书应当成功：" + added.message)
            val view = build(runtime)
            val flattened = flatten(view)
            assertTrue(flattened.contains("三体"), "新书要出现在列表里：" + flattened)
            assertTrue(flattened.contains("120 ｜ 300 页"), "新书要显示当前｜总：" + flattened)
            assertEquals(2, collect(view).filterIsInstance<UiNode.ListRow>().size, "每本书一行")

            // 同名再写一次（不带页数）不会重复加，也不会变更已有数据。
            val beforeDuplicate = storeData(home)
            slot.perform("addBook", mapOf("value" to "三体"))
            val afterDuplicate = storeData(home)
            assertEquals(listOf(book, "三体"), afterDuplicate["books"]!!.split("||"), "书名不该重复")
            assertEquals(beforeDuplicate["p:三体"], afterDuplicate["p:三体"])
            assertEquals("300", afterDuplicate["tot:三体"])

            // 空状态只在没书时出现——这里已有两本。
            assertFalse(flattened.contains("还没有在读的书"))
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun anEmptyLogShowsOnlyOneLine() = runTest {
        val home = tempHome()
        installExample(home)
        val runtime = AppRuntime.create(home)
        try {
            val node = build(runtime)
            val flattened = flatten(node)
            assertEquals(
                listOf("读书", "还没有在读的书。"),
                collect(node).filterIsInstance<UiNode.Text>().map { it.text },
                "空书架只有标题 + 一行提示，不加操作说明：" + flattened,
            )
            assertEquals(1, flattened.split("还没有在读的书").size - 1)
        } finally {
            runtime.shutdown()
        }
    }

    private suspend fun listRow(runtime: AppRuntime): UiNode.ListRow =
        collect(readingCard(runtime).build(currentDataContext()))
            .filterIsInstance<UiNode.ListRow>()
            .first()

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
        is UiNode.ListRow -> listOfNotNull(node.title, node.subtitle, node.trailing).joinToString(" ")
        is UiNode.Switch -> node.label
        is UiNode.Chip -> node.label
        is UiNode.HeatStrip -> node.values.joinToString(",")
        is UiNode.Spacer -> ""
        UiNode.Divider -> ""
    }
}
