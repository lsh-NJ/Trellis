package dev.trellis.app.desktop

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.domain.plan.Actor
import dev.trellis.domain.plan.CommandEnvelope
import dev.trellis.domain.plan.CommandResult
import dev.trellis.domain.plan.LearningPlanService
import dev.trellis.feature.learning.LearningPlanStore
import dev.trellis.kernel.action.ActionParam
import dev.trellis.kernel.action.KernelAction
import dev.trellis.kernel.api.ActionOutcome
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.extension.KernelPoints
import dev.trellis.kernel.rule.ActionCall
import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TemplateSlot
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 宿主动作 `core.host` 的运行时验收：切页 / 写笔记 / 发通知 / 开计时器。
 *
 * 这些动作存在的意义是「插件作者不用为每个需求求宿主加专用动作」，
 * 所以既测每个动作本身，也测「一张 DSL 卡片用两个通用动作串起一次操作」。
 */
class HostActionsTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun tempHome(): File =
        Files.createTempDirectory("trellis-host-actions").toFile().also { tempDirs += it }

    private fun env() = CommandEnvelope(
        commandId = "cmd_" + UUID.randomUUID().toString().replace("-", ""),
        actor = Actor.USER,
        planId = null,
        origin = "test.host.actions",
    )

    private fun <T> CommandResult<T>.unwrap(): T {
        if (!ok) error("命令失败：${error?.code} ${error?.message}")
        return value!!
    }

    private suspend fun AppRuntime.host(
        id: String,
        vararg args: Pair<String, String>,
    ): ActionOutcome = kernel.performAction(PluginId("core.host"), ActionCall(id, mapOf(*args)))

    /** 装一张 DSL 卡片，卡上两个按钮：先写笔记，再打开这篇笔记。 */
    private fun File.installReadingPlugin() {
        val dir = File(this, "plugins/ai.reading").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"id":"ai.reading","version":"1.0.0","name":"读书记录"}""",
        )
        File(dir, "main.dsl.json").writeText(
            """
            {"slots":[{"point":"slot.home.section","id":"reading","title":"读书记录","body":"一键记一笔",
              "actions":[
                {"label":"记一笔","action":"writeKnowledgeNote",
                 "args":{"title":"读书记录","body":"今天读了三体"}},
                {"label":"打开笔记","action":"openPage",
                 "args":{"page":"知识库","target":"读书记录"}}
              ]}]}
            """.trimIndent(),
        )
    }

    // ---- 一张卡片把两个通用动作串起来 ----

    @Test
    fun aCardCanWriteANoteAndThenOpenIt() = runTest {
        val home = tempHome()
        home.installReadingPlugin()
        val runtime = AppRuntime.create(home)
        try {
            val slot = assertIs<TemplateSlot>(
                runtime.kernel.extensions.all(Slots.HomeSection).single { it.id == "reading" },
            )
            assertEquals(2, slot.actions.size, "卡片上应当有两个通用动作按钮")

            val write = runtime.performSlotAction(slot.owner, slot.actions[0])
            assertTrue(write.ok, "写笔记按钮应当成功，实际：" + write.message)
            assertEquals("新建了笔记「读书记录」（" + runtime.knowledgeStore.list().single().id + "）", write.message)
            val note = runtime.knowledgeStore.list().single()
            assertEquals("今天读了三体", runtime.knowledgeStore.read(note.id)?.body)

            val open = runtime.performSlotAction(slot.owner, slot.actions[1])
            assertTrue(open.ok, "打开笔记按钮应当成功，实际：" + open.message)
            assertEquals(TAB_KNOWLEDGE, runtime.currentTab, "应当切到知识库页")
            assertEquals(note.id, runtime.pendingKnowledgeReveal?.noteId, "应当请求打开刚写的那篇")
        } finally {
            runtime.shutdown()
        }
    }

    // ---- writeKnowledgeNote：append / replace / createOnly ----

    @Test
    fun writeKnowledgeNoteHonoursModes() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            val created = runtime.host("writeKnowledgeNote", "title" to "三体", "body" to "第一部")
            assertTrue(created.ok, created.message)
            val noteId = runtime.knowledgeStore.list().single().id

            // 默认 append：新建时就是这一段的正文。
            assertEquals("第一部", runtime.knowledgeStore.read(noteId)?.body)

            // append：接在末尾，中间空一行。
            val appended = runtime.host("writeKnowledgeNote", "title" to "三体", "body" to "第二部")
            assertTrue(appended.ok, appended.message)
            assertEquals("第一部\n\n第二部", runtime.knowledgeStore.read(noteId)?.body)
            assertEquals(1, runtime.knowledgeStore.list().size, "同名笔记不该再建一篇")

            // replace：整篇换掉。
            val replaced = runtime.host(
                "writeKnowledgeNote",
                "title" to "三体",
                "body" to "整篇重写",
                "mode" to "replace",
            )
            assertTrue(replaced.ok, replaced.message)
            assertEquals("整篇重写", runtime.knowledgeStore.read(noteId)?.body)

            // createOnly：已存在就不动。
            val skipped = runtime.host(
                "writeKnowledgeNote",
                "title" to "三体",
                "body" to "不该出现",
                "mode" to "createOnly",
            )
            assertTrue(skipped.ok, skipped.message)
            assertTrue(skipped.message.contains("已存在"), skipped.message)
            assertEquals("整篇重写", runtime.knowledgeStore.read(noteId)?.body)

            // createOnly：不存在就建。
            val fresh = runtime.host(
                "writeKnowledgeNote",
                "title" to "球状闪电",
                "body" to "量子玫瑰",
                "mode" to "createOnly",
            )
            assertTrue(fresh.ok, fresh.message)
            assertEquals(2, runtime.knowledgeStore.list().size)

            // 认不出的 mode：说人话，不写坏东西。
            val badMode = runtime.host(
                "writeKnowledgeNote",
                "title" to "三体",
                "body" to "x",
                "mode" to "overwrite",
            )
            assertTrue(badMode.message.contains("不认识的写作方式"), badMode.message)
            assertEquals("整篇重写", runtime.knowledgeStore.read(noteId)?.body)
        } finally {
            runtime.shutdown()
        }
    }

    // ---- openPage：知识库 / 计划 / 活动 / 未知页 ----

    @Test
    fun openPageOpensTheRequestedTarget() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            // 知识库：先造一篇笔记，再按标题打开。
            runtime.host("writeKnowledgeNote", "title" to "三体", "body" to "黑暗森林")
            val noteId = runtime.knowledgeStore.list().single().id
            val openNote = runtime.host("openPage", "page" to "知识库", "target" to "三体")
            assertTrue(openNote.ok, openNote.message)
            assertEquals(TAB_KNOWLEDGE, runtime.currentTab)
            assertEquals(noteId, runtime.pendingKnowledgeReveal?.noteId)

            // 知识库：标题不存在 → 人话提示，不切走。
            val missingNote = runtime.host("openPage", "page" to "kb", "target" to "没有这篇")
            assertTrue(missingNote.message.contains("知识库里没有这篇笔记"), missingNote.message)

            // 计划：后端里有一份「读书计划」，用模糊名字打开。
            val store: LearningPlanService = LearningPlanStore.open(
                File(home, "plans/plan.db").toPath(),
                { runtime.clock.nowMs() },
                runtime.clock.zone,
            )
            val shell = store.createShell(env(), "读书计划", "每周读一章").unwrap()
            runtime.refreshPlan()
            val openPlan = runtime.host("openPage", "page" to "plan", "target" to "读书")
            assertTrue(openPlan.ok, openPlan.message)
            assertEquals(TAB_PLAN, runtime.currentTab)
            assertEquals(shell.planId, runtime.planFocusId)

            // 活动：目标带日期就落在那一天。
            val openDay = runtime.host("openPage", "page" to "活动", "target" to "2026-11-01")
            assertTrue(openDay.ok, openDay.message)
            assertEquals(TAB_ACTIVITY, runtime.currentTab)
            assertEquals(LocalDate.parse("2026-11-01"), runtime.pendingActivityDay)

            // 未知页：说人话。
            val unknown = runtime.host("openPage", "page" to "火星")
            assertTrue(unknown.message.contains("没有这一页"), unknown.message)
        } finally {
            runtime.shutdown()
        }
    }

    // ---- notify ----

    @Test
    fun notifyPassesTitleAndBody() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            var captured: Pair<String, String>? = null
            runtime.notifier = Notifier { title, body -> captured = title to body }

            val outcome = runtime.host("notify", "title" to "读书提醒", "body" to "该读书了")
            assertTrue(outcome.ok, outcome.message)
            assertEquals("读书提醒" to "该读书了", captured)
            assertEquals("已发通知（系统可能拦截）", outcome.message)
        } finally {
            runtime.shutdown()
        }
    }

    // ---- startFocusTimer ----

    @Test
    fun startFocusTimerStartsTheTimerWindow() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            val outcome = runtime.host(
                "startFocusTimer",
                "minutes" to "25",
                "label" to "读书",
            )
            assertTrue(outcome.ok, outcome.message)
            assertEquals("计时器已开始：25 分钟（读书）", outcome.message)
            assertTrue(runtime.timerVisible, "计时小窗应当打开")
            assertTrue(runtime.timerRunning, "应当已经开始计时")
            assertEquals(25, runtime.timerMinutes)
            assertEquals("读书", runtime.timerTaskTitle)
            assertNull(runtime.timerTaskId, "通用计时不属于任何待办任务")
            assertEquals(FocusMode.Countdown, runtime.timerMode)

            // label 省略 → 「专注」；分钟数由宿主钳到 5..180。
            val again = runtime.host("startFocusTimer", "minutes" to "999")
            assertTrue(again.ok, again.message)
            assertEquals("专注", runtime.timerTaskTitle)
            assertEquals(180, runtime.timerMinutes)
        } finally {
            runtime.shutdown()
        }
    }

    // ---- 动作面：注册齐全、免审批、参数表对 ----

    @Test
    fun hostActionsAreRegisteredAndApprovalFree() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            val actions = runtime.kernel.extensions.all(KernelPoints.Action).associateBy { it.id }
            fun action(id: String): KernelAction {
                val found = actions[id]
                assertTrue(found != null, "内核里应当注册了动作 " + id)
                return found!!
            }

            val openPage = action("openPage")
            assertEquals("打开某个页面", openPage.label)
            assertEquals(listOf("page", "target"), openPage.params.map(ActionParam::name))
            assertEquals(listOf(true, false), openPage.params.map(ActionParam::required))
            assertNull(openPage.capability)

            val write = action("writeKnowledgeNote")
            assertEquals("写一篇知识库笔记", write.label)
            assertEquals(listOf("title", "body", "mode"), write.params.map(ActionParam::name))
            assertNull(write.capability)

            val notify = action("notify")
            assertEquals(listOf("title", "body"), notify.params.map(ActionParam::name))
            assertNull(notify.capability)

            val timer = action("startFocusTimer")
            assertEquals(listOf("minutes", "label"), timer.params.map(ActionParam::name))
            assertNull(timer.capability)

            val catalog = CapabilityCatalog.of(runtime.kernel.extensions, RENDERED_MOUNT_POINTS)
            val catalogIds = catalog.actions.map { it.id }.toSet()
            listOf("openPage", "writeKnowledgeNote", "notify", "startFocusTimer").forEach { id ->
                assertTrue(id in catalogIds, "能力目录里应当有 " + id)
                assertNull(catalog.actions.single { it.id == id }.capability)
            }
        } finally {
            runtime.shutdown()
        }
    }
}
