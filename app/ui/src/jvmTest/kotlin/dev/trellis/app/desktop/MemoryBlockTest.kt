package dev.trellis.app.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 跨会话记忆块（`AppRuntime.memoryBlock` 的纯函数部分）的文案与截断规则。
 *
 * 用户要的是「目标这类东西跨会话进每次对话」，所以这里钉三件事：
 * 1. 没有记忆时返回空串（空块不占提示词前缀）；
 * 2. 顺序固定：目标 → 偏好 → 近期决定/事件；
 * 3. `maxChars` 是硬上限，预算紧时先保目标。
 */
class MemoryBlockTest {

    private fun tempHome(): File = Files.createTempDirectory("trellis-memory-block").toFile()

    @Test
    fun emptyMemoryRendersNothing() {
        assertEquals("", renderCrossSessionMemory(StewardStore(tempHome()), today = "2026-11-07"))
    }

    @Test
    fun ritualMonologueAloneDoesNotProduceABlock() {
        val store = StewardStore(tempHome())
        store.appendMemory(StewardMemoryEvent(1, "2026-11-06", StewardMemoryKind.Morning, "早安，今天有 3 个节点"))
        store.appendMemory(StewardMemoryEvent(2, "2026-11-06", StewardMemoryKind.Evening, "今天完成得不错"))

        // 晨/夜仪式是管家独白，不是跨会话记忆：没有目标/偏好/决定时就不该有块。
        assertEquals("", renderCrossSessionMemory(store, today = "2026-11-07"))
    }

    @Test
    fun goalsComeFirstThenPreferencesThenRecentDecisions() {
        val store = StewardStore(tempHome())
        store.appendMemory(StewardMemoryEvent(1, "2026-11-01", StewardMemoryKind.Goal, "想考软考中级"))
        store.appendMemory(StewardMemoryEvent(2, "2026-11-02", StewardMemoryKind.Preference, "晚上别排新任务"))
        store.appendMemory(StewardMemoryEvent(3, "2026-11-03", StewardMemoryKind.Adopted, "先复习第三章"))
        store.appendMemory(StewardMemoryEvent(4, "2026-11-04", StewardMemoryKind.Morning, "晨间独白不该进近期决定"))
        store.appendMemory(StewardMemoryEvent(5, "2026-09-01", StewardMemoryKind.Note, "太久以前的事件不该进近期决定"))

        val block = renderCrossSessionMemory(store, today = "2026-11-07")
        assertTrue(block.startsWith(CROSS_SESSION_MEMORY_OPEN), block)
        assertTrue(block.endsWith(CROSS_SESSION_MEMORY_CLOSE), block)
        assertTrue(block.contains("用户长期目标"), block)
        assertTrue(block.contains("用户长期偏好"), block)
        assertTrue(block.contains("最近的约定/决定"), block)

        val goalAt = block.indexOf("想考软考中级")
        val preferenceAt = block.indexOf("晚上别排新任务")
        val recentAt = block.indexOf("先复习第三章")
        assertTrue(goalAt >= 0 && goalAt < preferenceAt, "目标必须在偏好之前：$block")
        assertTrue(preferenceAt < recentAt, "偏好必须在近期决定之前：$block")
        assertFalse(block.contains("晨间独白"), "仪式独白不进记忆块")
        assertFalse(block.contains("太久以前"), "超出近期窗口的事件不进记忆块")
    }

    @Test
    fun eachSectionKeepsOnlyTheNewestThree() {
        val store = StewardStore(tempHome())
        (1..5).forEach { index ->
            store.appendMemory(
                StewardMemoryEvent(index.toLong(), "2026-11-0$index", StewardMemoryKind.Goal, "目标$index"),
            )
        }
        val block = renderCrossSessionMemory(store, today = "2026-11-07")
        // goals() 按记录时间降序取最多 3 条：最新的是目标5/4/3。
        assertTrue(block.contains("目标5") && block.contains("目标4") && block.contains("目标3"), block)
        assertFalse(block.contains("目标2"), block)
        assertFalse(block.contains("目标1"), block)
    }

    @Test
    fun maxCharsIsAHardCapAndGoalsWinTheBudget() {
        val store = StewardStore(tempHome())
        store.appendMemory(
            StewardMemoryEvent(1, "2026-11-01", StewardMemoryKind.Goal, "想考软考中级".repeat(40)),
        )
        store.appendMemory(
            StewardMemoryEvent(2, "2026-11-02", StewardMemoryKind.Preference, "晚上别排新任务".repeat(10)),
        )
        store.appendMemory(
            StewardMemoryEvent(3, "2026-11-03", StewardMemoryKind.Adopted, "先复习第三章".repeat(10)),
        )

        val block = renderCrossSessionMemory(store, today = "2026-11-07", maxChars = 160)
        assertTrue(block.length <= 160, "记忆块必须不超过 maxChars，实际 ${block.length}：$block")
        assertTrue(block.startsWith(CROSS_SESSION_MEMORY_OPEN))
        assertTrue(block.endsWith(CROSS_SESSION_MEMORY_CLOSE))
        assertTrue(block.contains("用户长期目标"), "预算紧时目标优先保留：$block")
    }

    @Test
    fun multilineMemoryIsFlattenedIntoSingleLine() {
        val store = StewardStore(tempHome())
        store.appendMemory(StewardMemoryEvent(1, "2026-11-01", StewardMemoryKind.Goal, "想考软考\n中级：2027 年 5 月"))

        val block = renderCrossSessionMemory(store, today = "2026-11-07")
        assertTrue(block.contains("想考软考 中级"), block)
        assertFalse(block.contains("想考软考\n中级"), "记忆正文里的换行必须压平，否则会破坏块结构")
    }
}
