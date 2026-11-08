package dev.trellis.app.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 过程区新交互的视觉验收。
 *
 * 三张图回答「改完长什么样」：
 *  1. 默认：每条过程只占一行（思考 / 判断 / 结果 / 工具名），整块限高内滚；
 *  2. 点开一条思考：正文排在同一块里，不套内层小滚动框；
 *  3. 点开一条工具：一句人话 + 参数 + 结果。
 *
 * 交互链路本身在 Compose 里不好断言，这里保证「画得出来、画出来不是空白」，
 * 再把 PNG 写进 `app/ui/build/` 给人看。
 */
class ChatProcessRenderTest {

    private fun write(name: String, bytes: ByteArray) {
        val out = File("build/$name")
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
        println("  截图已写出: " + out.absolutePath + "  (" + bytes.size + " 字节)")
    }

    /** 一段像真跑过的过程：判断 + 若干轮（工具 / 结果 / 思考），足够把窗口撑满。 */
    private fun demoProcessLines(): List<ChatLine> {
        val lines = mutableListOf<ChatLine>()
        var id = 1L
        fun step(text: String, tool: ChatToolTrace? = null) {
            lines += ChatLine(id, ChatRole.Step, text, tool = tool)
            id++
        }
        step("判断：它要动手，切到工具模式")
        step(
            "第 1 步：read_learning_overview",
            ChatToolTrace(
                tool = "read_learning_overview",
                description = "读学习总览",
                arguments = """{"planId": "pln_demo"}""",
                result = "计划 pln_demo：本周 3 个单元，已完成 1 个，复习 2 个。",
                isError = false,
                // 上一跳模型没吐 JSON，宿主纠正过它：这行只在这里（工具详情）露出来。
                notes = listOf("第 0 步输出格式不对（里面没有 JSON），已让它重说"),
            ),
        )
        step("　→ 计划 pln_demo：本周 3 个单元，已完成 1 个。")
        step("思考：本周还剩两个单元没排，先把话重复的差事挪到周五，再补周一的缺口。")
        repeat(5) { n ->
            step(
                "第 ${n + 2} 步：plan_week",
                ChatToolTrace(
                    tool = "plan_week",
                    description = "排这一周",
                    arguments = """{"planId": "pln_demo", "week": "2026-11-09", "replan": false}""",
                    result = "本周已排 4 条：周一 2、周三 1、周五 1。",
                    isError = false,
                ),
            )
            step("　→ 本周已排 4 条，还剩 1 条在待安排池。")
            step("思考：周三已经塞了三件，再往里加会把当天的预算吃满，得往周四挪。")
        }
        step(
            "第 8 步：sync_plan_week",
            ChatToolTrace(
                tool = "sync_plan_week",
                description = "把周文档同步成计划单元",
                arguments = """{"planId": "pln_demo", "week": "2026-11-09", "confirmed": true}""",
                result = "出错了：计划文档里 2026-11-09 这一周是空的。",
                isError = true,
            ),
        )
        return lines
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderRows(
        runtime: AppRuntime,
        steps: List<ChatLine>,
        openStepId: Long?,
        name: String,
    ) {
        val scene = ImageComposeScene(width = 760, height = 560, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(false),
                dark = false,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(false),
            ) {
                Column(Modifier.padding(16.dp)) {
                    ProcessRows(
                        steps = steps,
                        running = false,
                        openStepId = openStepId,
                        maxHeight = 300.dp,
                        onToggle = {},
                    )
                }
            }
        }
        val image = scene.render()
        scene.close()
        val bytes = image.encodeToData()?.bytes ?: error("PNG 编码失败")
        write(name, bytes)
        assertTrue(bytes.size > 5_000, name + " 太小了，八成是空白画面：" + bytes.size)
    }

    @Test
    fun rendersFoldedProcessRowsOneLineEachWithinTheWindow() {
        val home = Files.createTempDirectory("trellis-process-render").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        val steps = demoProcessLines()

        renderRows(runtime, steps, openStepId = null, name = "ui-shot-process-folded.png")

        runBlocking { runtime.shutdown() }
    }

    @Test
    fun rendersOneExpandedThinkingRowInsideTheWindow() {
        val home = Files.createTempDirectory("trellis-process-render").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        val steps = demoProcessLines()
        val thinkingId = steps.first { it.text.startsWith("思考：") }.id

        renderRows(runtime, steps, openStepId = thinkingId, name = "ui-shot-process-expanded-thinking.png")

        runBlocking { runtime.shutdown() }
    }

    @Test
    fun rendersOneExpandedToolRowInsideTheWindow() {
        val home = Files.createTempDirectory("trellis-process-render").toFile().apply { deleteOnExit() }
        val runtime = runBlocking { AppRuntime.create(home) }
        val steps = demoProcessLines()
        val toolId = steps.first { it.tool != null }.id

        renderRows(runtime, steps, openStepId = toolId, name = "ui-shot-process-expanded-tool.png")

        runBlocking { runtime.shutdown() }
    }

    /**
     * 整页集成：把过程行塞进真实会话里，确认对话页仍然画得出来
     * （回合分组、限高窗口、结论 Markdown 三者在同一屏）。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersAChatRoundWithTheNewProcessArea() {
        val home = Files.createTempDirectory("trellis-process-chat").toFile().apply { deleteOnExit() }
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = "chat-process-demo",
            title = "过程区验收",
            createdAt = now,
            updatedAt = now,
            lines = listOf(ChatLine(1, ChatRole.User, "把本周的学习安排好，别堆在同一天。", atMs = now)) +
                demoProcessLines().map { it.copy(id = it.id + 1) } +
                listOf(ChatLine(900, ChatRole.Assistant, "本周排好了：周一 2 条、周三 1 条、周五 1 条，剩下的进待安排池。", atMs = now + 1000L)),
        )
        ChatStore(home).save(session)

        val runtime = runBlocking { AppRuntime.create(home) }
        runtime.openChat(session.id)
        runtime.currentTab = TAB_CHAT

        val scene = ImageComposeScene(width = 1180, height = 820, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(false),
                dark = false,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(false),
            ) {
                AppScreen(runtime)
            }
        }
        val image = scene.render()
        scene.close()
        val bytes = image.encodeToData()?.bytes ?: error("PNG 编码失败")
        write("ui-shot-chat-process.png", bytes)
        assertTrue(bytes.size > 5_000, "对话页截图太小了，八成是空白画面：" + bytes.size)

        runBlocking { runtime.shutdown() }
    }

    /**
     * 提问卡：`ask_choices` 的问题 + 选项排在结论之后，输入框上方再留一行「还有一问没答」。
     *
     * 这张图要能回答用户那句「我问的是什么」：题干在卡里，选项在题干下面，编号可指代。
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun rendersTheQuestionCardInsideTheReply() {
        val home = Files.createTempDirectory("trellis-chat-ask-shot").toFile().apply { deleteOnExit() }
        val now = System.currentTimeMillis()
        val session = ChatSession(
            id = "chat-ask-demo",
            title = "提问卡验收",
            createdAt = now,
            updatedAt = now,
            lines = listOf(
                ChatLine(1, ChatRole.User, "对话页的过程区还能再收一点吗？", atMs = now),
                ChatLine(
                    2,
                    ChatRole.Step,
                    "思考：过程区已经能整组收成一行了，再动就是选项位置的事。",
                    atMs = now + 500L,
                ),
                ChatLine(
                    3,
                    ChatRole.Assistant,
                    "可以。过程行先不动，我先问一句再改：你想先改哪一块？",
                    atMs = now + 1000L,
                ),
            ),
        )
        ChatStore(home).save(session)

        val runtime = runBlocking { AppRuntime.create(home) }
        runtime.openChat(session.id)
        runtime.currentTab = TAB_CHAT
        runtime.offerChoices("这次先改哪一块？", listOf("对话页的提问卡", "输入框上方的提醒条", "先都不改"))
        assertTrue(runtime.chatAsk != null, "提问没进状态，截图就看不到卡")

        val scene = ImageComposeScene(width = 1180, height = 820, density = Density(1f)) {
            AppTheme(
                prefs = runtime.uiPrefs,
                themeScheme = runtime.themeScheme(false),
                dark = false,
                chrome = runtime.chromeStyle(),
                extra = runtime.extraColors(false),
            ) {
                AppScreen(runtime)
            }
        }
        val image = scene.render()
        scene.close()
        val bytes = image.encodeToData()?.bytes ?: error("PNG 编码失败")
        write("ui-shot-chat-ask.png", bytes)
        assertTrue(bytes.size > 5_000, "提问卡截图太小了，八成是空白画面：" + bytes.size)

        runBlocking { runtime.shutdown() }
    }
}
