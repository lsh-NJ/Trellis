package dev.trellis.app.desktop

import dev.trellis.ai.CapabilityCatalog
import dev.trellis.kernel.approval.DefaultApprovalGate
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 钉住「模型能用的清单」本身。
 *
 * 提示词写得再好，清单里没有的东西它也做不出来。这一轮 AI 能力退化的第三层病因
 * 就是清单太窄：9 个挂载点只渲染 4 个、7 个数据字段首页已经写死了 7 个、动作只有 3 个。
 *
 * 这个测试从**真实的运行时内核**取清单（不是测试夹具），所以界面不渲染、内核不注册，
 * 它立刻就会红。改能力面时改这里，等于承认「模型能做的事变了」。
 */
class CapabilitySurfaceTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempHome(): File =
        File.createTempFile("trellis-surface-", "").let {
            it.delete(); it.mkdirs(); tempDirs += it; it
        }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun theSurfaceHandedToTheModelKeepsUpWithTheKernel() = runTest {
        val home = tempHome()
        File(home, "llm.json").writeText(
            """{"endpoint":"http://localhost/invalid","apiKey":"sk-test","model":"fake"}""",
        )
        val runtime = AppRuntime.create(home)
        (runtime.kernel.approvals as? DefaultApprovalGate)?.setManualHandler { true }

        val catalog = CapabilityCatalog.of(runtime.kernel.extensions, RENDERED_MOUNT_POINTS)

        // 挂载点：界面渲染几个，模型就看得见几个。详情页两个、任务行尾一个还没有容器，所以是 7 不是 10。
        assertEquals(RENDERED_MOUNT_POINTS.size, catalog.mountPoints.size)
        assertEquals(7, catalog.mountPoints.size, "渲染点改了就要同步这里：" + catalog.mountPoints.map { it.id })

        // 动作：三个新的编辑动作必须真的注册进内核
        val actions = catalog.actions.map { it.id }
        assertTrue("renameTask" in actions, "少了 renameTask：" + actions)
        assertTrue("rescheduleTask" in actions, "少了 rescheduleTask：" + actions)
        assertTrue("deleteTask" in actions, "少了 deleteTask：" + actions)
        assertEquals(6, actions.size, "动作数变了：" + actions)

        // 数据字段：首页显示不出来的三个才算新能力
        val fields = catalog.dataFields.map { it.path }
        assertTrue("summary.streakDays" in fields, "少了 streakDays：" + fields)
        assertTrue("summary.completionRate" in fields, "少了 completionRate：" + fields)
        assertTrue("summary.oldestOpenTask" in fields, "少了 oldestOpenTask：" + fields)
        assertEquals(10, fields.size, "字段数变了：" + fields)

        // 事件载荷：规则要能引用 event.spentMinutes
        val completed = catalog.events.firstOrNull { it.id == "task.completed" }
        assertNotNull(completed, "task.completed 必须登记：" + catalog.events.map { it.id })
        assertTrue(
            completed.payload.any { it.name == "spentMinutes" },
            "task.completed 的载荷里应当有 spentMinutes：" + completed.payload,
        )
    }
}
