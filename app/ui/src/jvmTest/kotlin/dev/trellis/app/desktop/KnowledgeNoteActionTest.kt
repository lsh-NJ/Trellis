package dev.trellis.app.desktop

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.kernel.core.PluginId
import dev.trellis.kernel.rule.ActionCall
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

/**
 * 「一键打开或新建知识库笔记」端到端。
 *
 * 守三件事：
 *  1. 声明式插件卡片上的按钮，真的能新建笔记、跳进知识库并定位到它（这是读书插件缺失的那一环）；
 *  2. 同名笔记不会越点越多，已存在的笔记也不会被后来的 body 覆盖；
 *  3. 动作在 `CapabilityCatalog` 里可见，AI 生成卡片时才知道有它可用。
 */
class KnowledgeNoteActionTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File = File.createTempFile("trellis-kb-action-", "").let { file ->
        file.delete()
        file.mkdirs()
        tempDirs += file
        file
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** 一个纯 JSON 的「读书记录」插件：卡片上有一个按钮，点名 openKnowledgeNote。 */
    private fun File.installReadingPlugin(title: String, body: String? = null) {
        val dir = File(this, "plugins/ai.reading").apply { mkdirs() }
        File(dir, "manifest.json").writeText(
            """{"id":"ai.reading","version":"1.0.0","name":"读书记录"}""",
        )
        val args = if (body == null) {
            """{"title":"$title"}"""
        } else {
            """{"title":"$title","body":"$body"}"""
        }
        File(dir, "main.dsl.json").writeText(
            """{"slots":[{"point":"slot.home.section","id":"reading","title":"读书记录",""" +
                """"body":"今天读到哪儿了","actions":[{"label":"写读书笔记","action":"openKnowledgeNote",""" +
                """"args":$args}]}]}""",
        )
    }

    private suspend fun AppRuntime.readingButton(): TemplateSlot {
        val slot = assertIs<TemplateSlot>(
            kernel.extensions.all(Slots.HomeSection).single { it.id == "reading" },
        )
        assertEquals(1, slot.actions.size, "卡片上应当正好一个按钮")
        return slot
    }

    private fun File.noteFileCount(): Int =
        File(this, "knowledge/notes").listFiles()?.count { it.isFile && it.extension == "md" } ?: 0

    @Test
    fun aCardButtonCreatesANoteAndJumpsToIt() = runTest {
        val home = tempHome()
        home.installReadingPlugin("假如给我三天光明", body = "海伦·凯勒自传")
        val runtime = AppRuntime.create(home)
        try {
            val slot = runtime.readingButton()
            val outcome = runtime.performSlotAction(slot.owner, slot.actions[0])

            assertTrue(outcome.ok, "按钮应当执行成功，实际：" + outcome.message)
            assertEquals("新建并打开了笔记「假如给我三天光明」", outcome.message)

            val summaries = runtime.knowledgeStore.list()
            assertEquals(1, summaries.size, "知识库里应当正好多出一篇笔记")
            val summary = summaries.single()
            assertEquals("假如给我三天光明", summary.title)

            assertEquals(
                summary.id,
                runtime.pendingKnowledgeReveal?.noteId,
                "应当请求知识库页打开这篇笔记",
            )
            assertEquals(TAB_KNOWLEDGE, runtime.currentTab, "应当切到知识库页")

            val note = runtime.knowledgeStore.read(summary.id)
            assertEquals("海伦·凯勒自传", note?.body, "新建时的 body 应当写进正文")
            assertEquals(1, home.noteFileCount(), "笔记文件应当真的落在 <home>/knowledge/notes")
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun triggeringTheSameTitleAgainOpensInsteadOfDuplicating() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            val first = runtime.kernel.performAction(
                PluginId("core.knowledge"),
                ActionCall("openKnowledgeNote", mapOf("title" to "三体", "body" to "第一版正文")),
            )
            assertTrue(first.ok, "首次调用应当成功，实际：" + first.message)
            assertEquals("新建并打开了笔记「三体」", first.message)
            val noteId = runtime.knowledgeStore.list().single().id
            assertEquals("第一版正文", runtime.knowledgeStore.read(noteId)?.body)

            val second = runtime.kernel.performAction(
                PluginId("core.knowledge"),
                ActionCall("openKnowledgeNote", mapOf("title" to " 三体 ", "body" to "第二版正文")),
            )
            assertTrue(second.ok, "再次调用应当成功，实际：" + second.message)
            assertEquals("已打开笔记「三体」", second.message)

            assertEquals(1, runtime.knowledgeStore.list().size, "同名笔记不该再建一篇")
            assertEquals(1, home.noteFileCount())
            val again = runtime.knowledgeStore.list().single()
            assertEquals(noteId, again.id, "打开的还是原来那篇")
            assertEquals("第一版正文", runtime.knowledgeStore.read(noteId)?.body, "已有笔记的正文不能被覆盖")
            assertEquals(noteId, runtime.pendingKnowledgeReveal?.noteId)
            assertEquals(TAB_KNOWLEDGE, runtime.currentTab)
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun theActionIsVisibleInTheCapabilityCatalog() = runTest {
        val home = tempHome()
        val runtime = AppRuntime.create(home)
        try {
            assertTrue(
                runtime.kernel.plugins().any { it.manifest.id == PluginId("core.knowledge") },
                "内置插件 core.knowledge 应当已加载",
            )

            val catalog = CapabilityCatalog.of(runtime.kernel.extensions)
            assertTrue(catalog.actionIds.contains("openKnowledgeNote"), "动作目录里应当有 openKnowledgeNote")

            val action = catalog.actions.single { it.id == "openKnowledgeNote" }
            assertEquals("打开或新建知识库笔记", action.label)
            assertNull(action.capability, "宿主安全动作不需要能力，避免每次点击都弹审批")

            val title = action.params.single { it.name == "title" }
            assertTrue(title.required, "title 是必填参数")
            val body = action.params.single { it.name == "body" }
            assertFalse(body.required, "body 是可选参数")
        } finally {
            runtime.shutdown()
        }
    }
}
