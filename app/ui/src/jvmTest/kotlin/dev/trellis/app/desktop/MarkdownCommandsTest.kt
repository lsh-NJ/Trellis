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
        for (binding in defaultMarkdownBindings) {
            assertNotNull(markdownCommand(binding.commandId), "快捷键 " + binding.label + " 指向了不存在的命令")
        }
    }

    @Test
    fun everyCommandHasADefaultBinding() {
        // 评审意见三：菜单里 17 个命令都要有快捷键，漏一个就是「按了没反应」。
        val bound = defaultMarkdownBindings.map { it.commandId }.toSet()
        for (command in markdownCommands) {
            assertTrue(command.id in bound, "命令「" + command.label + "」没有默认快捷键")
        }
    }

    @Test
    fun noTwoBindingsShareTheSameKey() {
        val seen = mutableSetOf<Triple<Any, Boolean, Boolean>>()
        for (binding in defaultMarkdownBindings) {
            assertTrue(
                seen.add(Triple(binding.key, binding.shift, binding.alt)),
                "键位重复：" + binding.label,
            )
        }
    }

    @Test
    fun theShortcutSheetListsEveryBinding() {
        val hints = markdownShortcutHints()
        val listed = hints.map { it.keys }.toSet()
        for (binding in defaultMarkdownBindings) {
            val keys = platformShortcutLabel(binding.label)
            assertTrue(listed.contains(keys), "清单里少了 " + keys)
        }
    }

    @Test
    fun theShortcutSheetFollowsCustomizedBindings() {
        // 设置页把粗体改成 Ctrl/Cmd+Shift+B 之后，菜单必须显示新键位，不能还挂着旧键位。
        val customized = effectiveMarkdownBindings(mapOf("bold" to "Ctrl/Cmd+Shift+B"))
        val hints = markdownShortcutHints(customized)
        val boldHint = hints.first { it.action == markdownCommand("bold")!!.label }
        assertEquals(platformShortcutLabel("Ctrl/Cmd+Shift+B"), boldHint.keys)
        assertTrue(hints.none { it.keys == platformShortcutLabel("Ctrl/Cmd+B") })
    }

    @Test
    fun platformShortcutLabelsFollowTheHostConventions() {
        assertEquals("Ctrl+B", platformShortcutLabel("Ctrl/Cmd+B", mac = false))
        assertEquals("Ctrl+Shift+X", platformShortcutLabel("Ctrl/Cmd+Shift+X", mac = false))
        assertEquals("⌘B", platformShortcutLabel("Ctrl/Cmd+B", mac = true))
        assertEquals("⇧⌘X", platformShortcutLabel("Ctrl/Cmd+Shift+X", mac = true))
    }

    @Test
    fun shortcutHintsReadActionFirstThenKeys() {
        // 菜单的每一行都是「动作 → 按键」：动作不能为空，按键不能为空。
        val hints = markdownShortcutHints()
        for (hint in hints) {
            assertTrue(hint.action.isNotBlank(), "菜单行缺动作：" + hint.keys)
            assertTrue(hint.keys.isNotBlank(), "菜单行缺按键：" + hint.action)
        }
        assertEquals(1, hints.count { it.action == "保存" })
        assertEquals(1, hints.count { it.action == "Tab / Shift+Tab 缩进" })
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
