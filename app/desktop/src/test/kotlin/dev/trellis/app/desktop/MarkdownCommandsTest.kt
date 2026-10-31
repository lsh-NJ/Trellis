package dev.trellis.app.desktop

import androidx.compose.ui.text.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 斜杠菜单的触发/过滤/插入，以及快捷键清单的一致性。
 *
 * 重点钉两类事故：`10/3`、`docs/a.md` 这种正常内容不能弹出菜单；
 * 说明书上的键位必须真的对应一条命令（漏一个 id 就会出现「按了没反应」）。
 */
class MarkdownCommandsTest {

    @Test
    fun slashAtLineStartOrAfterSpaceOpensTheMenu() {
        assertEquals(0, slashTriggerStart("/", 1))
        assertEquals(3, slashTriggerStart("你好 /", 4))
        assertEquals(2, slashTriggerStart("a\n/", 3))
    }

    @Test
    fun slashInsideWordsAndPathsDoesNotOpen() {
        assertNull(slashTriggerStart("10/3", 3))
        assertNull(slashTriggerStart("docs/a.md", 5))
        assertNull(slashTriggerStart("a/b", 2))
    }

    @Test
    fun slashInsideACodeFenceDoesNotOpen() {
        assertNull(slashTriggerStart("```\n/", 5))
        assertEquals(8, slashTriggerStart("```\n```\n/", 9))
    }

    @Test
    fun queryStaysAliveUntilCursorLeavesTheToken() {
        assertEquals("bt", slashQueryAt("/bt", 0, 3))
        assertEquals("", slashQueryAt("/", 0, 1))
        // 打了空格 = 这段结束了，菜单该关。
        assertNull(slashQueryAt("/bt ", 0, 4))
        // 光标跑到 `/` 前面。
        assertNull(slashQueryAt("/bt", 0, 0))
    }

    @Test
    fun filteringMatchesLabelsIdsAndAliases() {
        assertEquals("bold", filterMarkdownCommands("粗体").first().id)
        assertEquals("bold", filterMarkdownCommands("bold").first().id)
        // 拼音首字母：ct = 粗体（别名 "cuti" 的包含命中），必须排在边缘命中前面。
        assertEquals("bold", filterMarkdownCommands("ct").first().id)
        assertEquals("h2", filterMarkdownCommands("h2").single().id)
        assertTrue(filterMarkdownCommands("").size == markdownCommands.size)
        assertTrue(filterMarkdownCommands("不存在的命令").isEmpty())
    }

    @Test
    fun aShortPrefixPutsTheMostObviousCommandFirst() {
        // 敲 `b` 第一项必须是粗体，不能是「代码块」——菜单默认选中第一项。
        assertEquals("bold", filterMarkdownCommands("b").first().id)
        assertEquals("code", filterMarkdownCommands("daima").first().id)
        assertTrue(filterMarkdownCommands("biaoti").map { it.id }.containsAll(listOf("h1", "h2", "h3")))
    }
    @Test
    fun applyingACommandRemovesTheSlashTokenFirst() {
        // `/h2` 换成二级标题：占位文本不该残留 `/h2`。
        val heading = applySlashCommand("/h2", 0, 3, markdownCommand("h2")!!)
        assertEquals("## ", heading.text)
        assertEquals(3, heading.selection.min)

        val bullet = applySlashCommand("hello\n/lb", 6, 9, markdownCommand("bullet")!!)
        assertEquals("hello\n- ", bullet.text)
    }

    @Test
    fun applyingAnInlineCommandWorksOnTheClearedLine() {
        val bold = applySlashCommand("/b", 0, 2, markdownCommand("bold")!!)
        assertEquals("**粗体**", bold.text)
        assertEquals(2, bold.selection.min)
        assertEquals(4, bold.selection.max)
    }

    @Test
    fun applyingWithAClosedMenuOnlyUsesTheCursor() {
        val result = applySlashCommand("abc", 0, 3, markdownCommand("bold")!!)
        assertEquals("abc**粗体**", result.text)
    }

    @Test
    fun everyBindingPointsAtARealCommand() {
        for (binding in markdownKeyBindings) {
            assertNotNull(markdownCommand(binding.commandId), "快捷键 " + binding.label + " 指向了不存在的命令")
        }
    }

    @Test
    fun noTwoBindingsShareTheSameKey() {
        val seen = mutableSetOf<Pair<Any, Boolean>>()
        for (binding in markdownKeyBindings) {
            assertTrue(
                seen.add(binding.key to binding.shift),
                "键位重复：" + binding.label,
            )
        }
    }

    @Test
    fun theShortcutSheetListsEveryBinding() {
        val listed = markdownShortcutHints.map { it.keys }.toSet()
        for (binding in markdownKeyBindings) {
            assertTrue(listed.contains(binding.label), "清单里少了 " + binding.label)
        }
    }

    @Test
    fun everyCommandIsReachableFromTheEmptyMenu() {
        assertEquals(markdownCommands.size, filterMarkdownCommands("").size)
        assertTrue(markdownCommands.all { it.label.isNotBlank() && it.hint.isNotBlank() })
    }

    @Test
    fun ruleCommandInsertsADividerOnItsOwnLine() {
        val result = markdownCommand("rule")!!.apply("上一段", TextRange(3))
        assertEquals("上一段\n---", result.text)
    }
}
