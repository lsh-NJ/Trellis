package dev.trellis.feature.goal

import dev.trellis.kernel.extension.DataContext
import dev.trellis.kernel.slot.UiNode
import dev.trellis.kernel.storage.KvStore
import dev.trellis.kernel.time.KernelClock
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 首页卡上那一行「计划 x 分钟 · 实际 y 分钟」。
 *
 * 为什么单独测卡片、不只测 contextBlock：两处措辞是**分别决定**的。
 * 上下文块写给模型看（要提醒它别拿这个数改排期），卡片写给用户看（要让他看出差多少）。
 * 只测其中一处，另一处的取舍就没有人守。
 *
 * 这一条也是 P0 里唯一没法用截图证明的部分：截图环境里没有真的活动库，
 * 那一行永远不会出现，只能在这里把服务摆好、把卡片 build 出来验。
 */
class GoalCardTextTest {

    private val today = "2026-10-29"

    /** 最小的 KvStore：卡片只要求「读得到刚写的」。 */
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

    /** 一份「已经确认、今天有安排」的状态：和用户点完「就这样开始」之后一模一样。 */
    private fun activeMirror(): GoalMirror {
        var mirror = GoalEngine.start(GoalMirror(), "我想考研", GoalKind.ACADEMIC, Clarity.VAGUE, today, 0L)
        RequiredFields.of(GoalKind.ACADEMIC).forEachIndexed { index, key ->
            mirror = GoalEngine.answer(mirror, key, "第 " + index + " 项", FieldState.CONFIRMED, index.toLong())
        }
        return GoalEngine.confirm(GoalEngine.toDraft(mirror, today, 90))
    }

    private suspend fun buildCard(effort: TodayEffort?): Pair<UiNode, DayPlan> {
        val service = GoalService(
            kit = GoalKit(MemoryKv()),
            clock = fixedClock(1_782_000_000_000L),
            dailyMinutes = { 90 },
            today = { today },
            effort = { effort },
        )
        service.update { activeMirror() }
        val plan = service.mirror().dayPlan(today)!!
        return GoalTodaySlot(service, { today }).build(DataContext.ofNow(1_782_000_000_000L)) to plan
    }

    /** 把节点树里所有文字摊平。断言只看文字：位置和配色是宿主的事。 */
    private fun texts(node: UiNode): List<String> = when (node) {
        is UiNode.Text -> listOf(node.text)
        is UiNode.Column -> node.children.flatMap { texts(it) }
        is UiNode.Row -> node.children.flatMap { texts(it) }
        is UiNode.Section -> node.children.flatMap { texts(it) }
        else -> emptyList()
    }

    @Test
    fun withoutActivityDataTheCardSaysNothingAboutActualTime() = runTest {
        val (card, _) = buildCard(effort = null)
        val lines = texts(card)
        assertFalse(lines.any { it.contains("实际") }, "没有活动数据就不该提「实际」：" + lines)
        assertTrue(lines.any { it.contains("我想考研") }, "目标本身还是要显示：" + lines)
    }

    @Test
    fun zeroMinutesIsTreatedAsNoDataInsteadOfSlacking() = runTest {
        // 0 分更常见的意思是「没同步」，不是「今天什么都没干」。显示出来会让人误会。
        val (card, _) = buildCard(TodayEffort(0, hasAfkData = true))
        assertFalse(texts(card).any { it.contains("实际") }, "0 分钟不显示这一行：" + texts(card))
    }

    @Test
    fun theCardComparesPlanWithActual() = runTest {
        val (card, plan) = buildCard(TodayEffort(62, hasAfkData = true))
        val line = texts(card).firstOrNull { it.startsWith("计划 ") }
        assertTrue(line != null, "有数据就该出现对照行：" + texts(card))
        assertTrue(line!!.contains("计划 " + plan.totalMinutes + " 分钟"), "要说清计划多少：" + line)
        assertTrue(line.contains("实际 62 分钟"), "要说清实际多少：" + line)
        assertTrue(line.contains("比计划少 " + (plan.totalMinutes - 62)), "差多少要直说，不要让人自己算：" + line)
    }

    @Test
    fun windowOnlyDataComesWithACaveat() = runTest {
        val (trusted, _) = buildCard(TodayEffort(62, hasAfkData = true))
        assertFalse(
            texts(trusted).any { it.contains("可能偏高") },
            "有 AFK 记录就别加注解，加多了用户会不信这个数：" + texts(trusted),
        )

        val (windowOnly, _) = buildCard(TodayEffort(62, hasAfkData = false))
        assertTrue(
            texts(windowOnly).any { it.contains("只统计了窗口") },
            "没有 AFK 记录时必须说明这个数偏高：" + texts(windowOnly),
        )
    }
}
