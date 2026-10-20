package dev.trellis.app.desktop

import dev.trellis.kernel.slot.Slots
import dev.trellis.kernel.slot.TemplateSlot
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 卡片上的按钮，端到端。
 *
 * 这是「轨道 A 从只能看变成能按」的唯一证明：
 * 两个 JSON 文件 → 卡片上一个按钮 → 按钮真的改了数据。
 *
 * 第二条测试守的是**更要紧**的那件事：按钮不能成为绕过授权的一扇后门。
 * 插件没声明 write_data，按下去就必须被拒，数据一个字节都不能动。
 */
class SlotActionFlowTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File = File.createTempFile("trellis-btn-", "").let { file ->
        file.delete()
        file.mkdirs()
        tempDirs += file
        file
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** 一份纯 JSON 的插件，卡片下面带一个按钮。 */
    private fun File.installButtonPlugin(declareWrite: Boolean = true) {
        val dir = File(this, "plugins/ai.btn").apply { mkdirs() }
        val manifest = if (declareWrite) {
            """{"id":"ai.btn","version":"1.0.0","name":"按钮","capabilities":["write_data"]}"""
        } else {
            """{"id":"ai.btn","version":"1.0.0","name":"按钮"}"""
        }
        File(dir, "manifest.json").writeText(manifest)
        File(dir, "main.dsl.json").writeText(
            """{"slots":[{"point":"slot.home.section","id":"s1","title":"番茄","body":"按一下加一条",""" +
                """"actions":[{"label":"加一个番茄钟","action":"addTask","args":{"title":"番茄钟 25 分钟"}}]}]}""",
        )
    }

    private suspend fun AppRuntime.button(): TemplateSlot {
        val slot = assertIs<TemplateSlot>(
            kernel.extensions.all(Slots.HomeSection).single { it.id == "s1" },
        )
        assertEquals(1, slot.actions.size, "卡片上应当正好一个按钮")
        return slot
    }

    @Test
    fun aCardButtonReallyCreatesATask() = runTest {
        val home = tempHome()
        home.installButtonPlugin()
        val runtime = AppRuntime.create(home)
        try {
            runtime.planService.createPlan("测试计划")
            runtime.refresh()
            val before = runtime.tasks.size

            val slot = runtime.button()
            val outcome = runtime.performSlotAction(slot.owner, slot.actions[0])

            assertTrue(outcome.ok, "按钮应当执行成功，实际：" + outcome.message)
            runtime.refresh()
            assertEquals(before + 1, runtime.tasks.size, "待办里应当多出一条")
            assertTrue(
                runtime.tasks.any { it.title == "番茄钟 25 分钟" },
                "多出来的那条应当叫「番茄钟 25 分钟」，实际：" + runtime.tasks.map { it.title },
            )
        } finally {
            runtime.shutdown()
        }
    }

    @Test
    fun aButtonFromAPluginWithoutWriteDataIsDenied() = runTest {
        val home = tempHome()
        home.installButtonPlugin(declareWrite = false)
        val runtime = AppRuntime.create(home)
        try {
            runtime.planService.createPlan("测试计划")
            runtime.refresh()
            val before = runtime.tasks.size

            val slot = runtime.button()
            val outcome = runtime.performSlotAction(slot.owner, slot.actions[0])

            assertFalse(outcome.ok, "没声明 write_data 的插件不该能新建任务：" + outcome.message)
            runtime.refresh()
            assertEquals(before, runtime.tasks.size, "被拒之后数据一个字节都不该动")
        } finally {
            runtime.shutdown()
        }
    }
}
