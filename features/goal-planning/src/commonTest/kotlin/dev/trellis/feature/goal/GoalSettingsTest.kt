package dev.trellis.feature.goal

import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.time.KernelClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 设置页里的「删除这份规划」。
 *
 * 用户实测反馈是「点了没用」。查下来不是点击没生效，而是这个按钮在**没有目标时也显示**：
 * 点第一下改文案，点第二下删除一个不存在的东西 —— 界面上什么都不会变。
 *
 * 所以这里钉三件事：空态不出现删除入口；确认态必须给出「确定 / 取消」两个明确出口；
 * 确定之后真的回到 EMPTY，并且入口消失。
 */
class GoalSettingsTest {

    private val today = "2026-10-31"

    private class MemoryKv : KvStore {
        private val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override suspend fun put(key: String, value: String) {
            map[key] = value
        }
        override suspend fun delete(key: String) {
            map.remove(key)
        }
        override suspend fun keys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }
        override suspend fun clear() = map.clear()
    }

    private fun fixedClock(nowMs: Long): KernelClock = object : KernelClock {
        override fun nowMs(): Long = nowMs
        override fun monotonicMs(): Long = nowMs
    }

    private fun service(): GoalService = GoalService(
        kit = GoalKit(MemoryKv()),
        clock = fixedClock(0L),
        dailyMinutes = { 60 },
        today = { today },
        effort = { null },
    )

    private suspend fun active(service: GoalService) {
        var mirror = GoalEngine.start(service.mirror(), "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        RequiredFields.of(GoalKind.ACADEMIC).forEachIndexed { index, key ->
            mirror = GoalEngine.answer(mirror, key, "第 " + index + " 项", FieldState.CONFIRMED, index.toLong())
        }
        service.update { GoalEngine.confirm(GoalEngine.toDraft(mirror, today, 60)) }
    }

    private fun texts(node: UiNode): List<String> = when (node) {
        is UiNode.Text -> listOf(node.text)
        is UiNode.Column -> node.children.flatMap { texts(it) }
        is UiNode.Row -> node.children.flatMap { texts(it) }
        is UiNode.Section -> node.children.flatMap { texts(it) }
        is UiNode.Grid -> node.children.flatMap { texts(it) }
        is UiNode.Notice -> listOf(node.text)
        else -> emptyList()
    }

    private fun buttons(node: UiNode): List<String> = when (node) {
        is UiNode.Button -> listOf(node.label)
        is UiNode.Column -> node.children.flatMap { buttons(it) }
        is UiNode.Row -> node.children.flatMap { buttons(it) }
        is UiNode.Section -> node.children.flatMap { buttons(it) }
        is UiNode.Grid -> node.children.flatMap { buttons(it) }
        else -> emptyList()
    }

    @Test
    fun anEmptyGoalShowsNoDeleteButtonAtAll() = runTest {
        val slot = GoalSettingsSlot(service(), { 60 })
        val labels = buttons(slot.build(DataContext.ofNow(0L)))
        assertFalse(
            labels.any { it.contains("删除") },
            "没有目标时不该出现删除入口 —— 点了什么也不会变，用户只会认为按钮坏了：" + labels,
        )
        assertTrue(texts(slot.build(DataContext.ofNow(0L))).any { it.contains("没有可删的东西") })
    }

    @Test
    fun deletingTakesTwoExplicitStepsAndActuallyClearsTheGoal() = runTest {
        val service = service()
        active(service)
        val slot = GoalSettingsSlot(service, { 60 })

        // ① 有目标时才给入口。
        val initial = slot.build(DataContext.ofNow(0L))
        assertTrue("删除这份规划" in buttons(initial), "有目标就该能删：" + buttons(initial))

        // ② 第一次点击只亮出确认，不删。
        val armed = slot.perform("reset", emptyMap())
        assertTrue(armed.ok, armed.message)
        assertEquals(GoalPhase.ACTIVE, service.mirror().phase, "第一步不许动数据")
        val confirming = slot.build(DataContext.ofNow(0L))
        assertTrue("确定删除" in buttons(confirming), "要给出明确的确定：" + buttons(confirming))
        assertTrue("取消" in buttons(confirming), "也要给取消，不能只能往前走：" + buttons(confirming))
        assertTrue(
            texts(confirming).any { it.contains("插件本身保留") },
            "要说清删什么、留什么：" + texts(confirming),
        )

        // ③ 取消：什么都不变。
        assertTrue(slot.perform("reset-cancel", emptyMap()).ok)
        assertEquals(GoalPhase.ACTIVE, service.mirror().phase, "取消后目标还在")
        assertTrue("删除这份规划" in buttons(slot.build(DataContext.ofNow(0L))), "取消后回到初始态")

        // ④ 重新确认并删除：真的回到 EMPTY，入口消失。
        slot.perform("reset", emptyMap())
        val done = slot.perform("reset-confirm", emptyMap())
        assertTrue(done.ok, done.message)
        assertEquals(GoalPhase.EMPTY, service.mirror().phase, "删除之后必须回到「还没有目标」")
        val after = slot.build(DataContext.ofNow(0L))
        assertFalse(buttons(after).any { it.contains("删除") }, "删完就不该再有删除入口：" + buttons(after))
        assertTrue(texts(after).any { it.contains("还没有目标") }, "要说清现在没有目标：" + texts(after))
    }

    @Test
    fun confirmingWithoutArmingDeletesNothing() = runTest {
        val service = service()
        active(service)
        val slot = GoalSettingsSlot(service, { 60 })

        // 直接调 reset-confirm（比如界面状态被重置过）：不能因此把目标删掉。
        // 删除要用户看见确认文案后才可能发生，这是一条安全边界。
        val result = slot.perform("reset-confirm", emptyMap())
        assertEquals(GoalPhase.ACTIVE, service.mirror().phase, "没走过确认就不该删")
        assertFalse(result.ok)
    }
}
