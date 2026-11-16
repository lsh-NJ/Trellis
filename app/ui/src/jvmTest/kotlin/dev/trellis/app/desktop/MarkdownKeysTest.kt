package dev.trellis.app.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 快捷键注册表：17 条默认键位、用户覆盖合成、label 规范化、按键捕获、菜单清单。
 *
 * 这里重点钉两类事故：
 * 1. 默认表漏一条命令 / 两条命令抢同一个键位——用户按了没反应，或者按出别的命令；
 * 2. 用户覆盖（坏 label、与别的命令撞车、空串解绑）把整张表搞乱，而不是只影响那一条。
 */
class MarkdownKeysTest {

    private val expectedDefaultLabels = mapOf(
        "h1" to "Ctrl/Cmd+1",
        "h2" to "Ctrl/Cmd+2",
        "h3" to "Ctrl/Cmd+3",
        "paragraph" to "Ctrl/Cmd+0",
        "bullet" to "Ctrl/Cmd+Shift+L",
        "numbered" to "Ctrl/Cmd+Shift+O",
        "task" to "Ctrl/Cmd+Shift+K",
        "quote" to "Ctrl/Cmd+Shift+Q",
        "rule" to "Ctrl/Cmd+Shift+R",
        "codeblock" to "Ctrl/Cmd+Shift+E",
        "bold" to "Ctrl/Cmd+B",
        "italic" to "Ctrl/Cmd+I",
        "strike" to "Ctrl/Cmd+Shift+X",
        "code" to "Ctrl/Cmd+Shift+C",
        "link" to "Ctrl/Cmd+K",
        "table" to "Ctrl/Cmd+Shift+T",
        "image" to "Ctrl/Cmd+Shift+I",
    )

    private val supportedLetterKeys = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J,
        Key.K, Key.L, Key.M, Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T,
        Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )

    private val supportedDigitKeys = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
        Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )

    private val supportedSymbolKeys = listOf(
        Key.Slash to "/",
        Key.Minus to "-",
        Key.Equals to "=",
        Key.LeftBracket to "[",
        Key.RightBracket to "]",
        Key.Backslash to "\\",
        Key.Semicolon to ";",
        Key.Apostrophe to "'",
        Key.Comma to ",",
        Key.Period to ".",
        Key.Grave to "`",
        Key.At to "@",
        Key.Pound to "#",
        Key.Multiply to "*",
    )

    private fun defaultLabel(commandId: String): String =
        defaultMarkdownBindings.first { it.commandId == commandId }.label

    @Test
    fun defaultsCoverAllCommandsWithTheDocumentedKeys() {
        // 先钉「一条命令一条默认键位」：新增命令忘了配快捷键会在这里失败。
        assertEquals(markdownCommands.size, defaultMarkdownBindings.size)
        assertEquals(
            markdownCommands.map { it.id }.toSet(),
            defaultMarkdownBindings.map { it.commandId }.toSet(),
        )
        assertEquals(expectedDefaultLabels, defaultMarkdownBindings.associate { it.commandId to it.label })
        for (binding in defaultMarkdownBindings) {
            assertNotNull(markdownCommand(binding.commandId), "绑定指向不存在的命令：" + binding.commandId)
        }
    }

    @Test
    fun defaultKeysAreUnique() {
        val seen = mutableSetOf<Triple<Key, Boolean, Boolean>>()
        for (binding in defaultMarkdownBindings) {
            assertTrue(
                seen.add(Triple(binding.key, binding.shift, binding.alt)),
                "键位重复：" + binding.label,
            )
        }
    }

    @Test
    fun defaultLabelsAreCanonicalForTheirKeys() {
        // label 和 key/shift/alt 是两份数据，漂移了就会出现「菜单写 Ctrl+B，实际按不出来」。
        for (binding in defaultMarkdownBindings) {
            assertEquals(
                binding.label,
                shortcutLabelForKey(binding.key, ctrlCmd = true, shift = binding.shift, alt = binding.alt),
                binding.commandId,
            )
            assertEquals(binding.label, normalizeMarkdownShortcut(binding.label), binding.commandId)
        }
    }

    @Test
    fun platformLabelsRenderTheDefaults() {
        assertEquals("Ctrl+B", platformShortcutLabel(defaultLabel("bold"), mac = false))
        assertEquals("⌘B", platformShortcutLabel(defaultLabel("bold"), mac = true))
        assertEquals("Ctrl+Shift+K", platformShortcutLabel(defaultLabel("task"), mac = false))
        assertEquals("⇧⌘K", platformShortcutLabel(defaultLabel("task"), mac = true))
    }

    @Test
    fun bindingLookupMatchesKeyAndModifiers() {
        assertEquals("bold", markdownBindingFor(Key.B, false, false, defaultMarkdownBindings)?.commandId)
        assertEquals("task", markdownBindingFor(Key.K, true, false, defaultMarkdownBindings)?.commandId)
        assertEquals("link", markdownBindingFor(Key.K, false, false, defaultMarkdownBindings)?.commandId)
        assertNull(markdownBindingFor(Key.K, true, true, defaultMarkdownBindings))
        assertNull(markdownBindingFor(Key.B, true, false, defaultMarkdownBindings))
        assertNull(markdownBindingFor(Key.F1, false, false, defaultMarkdownBindings))
    }

    @Test
    fun overrideReplacesOnlyThatCommand() {
        val effective = effectiveMarkdownBindings(mapOf("bold" to "Ctrl/Cmd+J"))
        val bold = effective.first { it.commandId == "bold" }
        assertEquals(Key.J, bold.key)
        assertTrue(!bold.shift && !bold.alt)
        assertEquals("Ctrl/Cmd+J", bold.label)
        assertEquals("Ctrl/Cmd+I", effective.first { it.commandId == "italic" }.label)
        assertEquals(defaultMarkdownBindings.size, effective.size)
        assertEquals("bold", markdownBindingFor(Key.J, false, false, effective)?.commandId)
        assertNull(markdownBindingFor(Key.B, false, false, effective))
        // 没配置 = 原样返回默认表。
        assertEquals(defaultMarkdownBindings, effectiveMarkdownBindings(emptyMap()))
    }

    @Test
    fun overrideIsNormalizedBeforeItIsStored() {
        val effective = effectiveMarkdownBindings(mapOf("bold" to "shift+ctrl/cmd+b"))
        val bold = effective.first { it.commandId == "bold" }
        assertEquals("Ctrl/Cmd+Shift+B", bold.label)
        assertEquals(Key.B, bold.key)
        assertTrue(bold.shift)
    }

    @Test
    fun blankOverrideUnbindsTheCommand() {
        val effective = effectiveMarkdownBindings(mapOf("bold" to ""))
        assertEquals(defaultMarkdownBindings.size - 1, effective.size)
        assertNull(effective.firstOrNull { it.commandId == "bold" })
        assertNull(markdownBindingFor(Key.B, false, false, effective))
        assertEquals(defaultMarkdownBindings.size - 1, markdownShortcutHintLines(effective).size)
    }

    @Test
    fun whitespaceOverrideAlsoUnbinds() {
        val effective = effectiveMarkdownBindings(mapOf("bold" to "   "))
        assertNull(effective.firstOrNull { it.commandId == "bold" })
    }

    @Test
    fun invalidLabelsAndUnknownIdsAreIgnored() {
        val effective = effectiveMarkdownBindings(
            mapOf(
                "bold" to "B", // 没有 Ctrl/Cmd 或 Alt
                "italic" to "Ctrl/Cmd+Nope", // 白名单外的键
                "link" to "Ctrl/Cmd+", // 只有修饰键
                "nope" to "Ctrl/Cmd+J", // 不存在的命令
            ),
        )
        assertEquals(defaultMarkdownBindings, effective)
    }

    @Test
    fun conflictingOverrideKeepsTheDefault() {
        val effective = effectiveMarkdownBindings(
            mapOf(
                "bold" to "Ctrl/Cmd+I", // 和 italic 的默认键位撞车
                "strike" to "Ctrl/Cmd+Shift+K", // 和 task 的默认键位撞车
            ),
        )
        assertEquals("Ctrl/Cmd+B", effective.first { it.commandId == "bold" }.label)
        assertEquals("Ctrl/Cmd+I", effective.first { it.commandId == "italic" }.label)
        assertEquals("Ctrl/Cmd+Shift+X", effective.first { it.commandId == "strike" }.label)
        assertEquals("Ctrl/Cmd+Shift+K", effective.first { it.commandId == "task" }.label)
    }

    @Test
    fun overrideCanTakeAKeyFreedByAnEarlierUnbind() {
        // 先解绑 italic，Ctrl/Cmd+I 就空出来了；按 map 顺序处理，bold 可以拿走它。
        val overrides = linkedMapOf("italic" to "", "bold" to "Ctrl/Cmd+I")
        val effective = effectiveMarkdownBindings(overrides)
        assertEquals(defaultMarkdownBindings.size - 1, effective.size)
        assertEquals("Ctrl/Cmd+I", effective.first { it.commandId == "bold" }.label)
        assertNull(effective.firstOrNull { it.commandId == "italic" })
    }

    @Test
    fun altOverrideIsADistinctKey() {
        val effective = effectiveMarkdownBindings(mapOf("bold" to "Alt+B"))
        val bold = effective.first { it.commandId == "bold" }
        assertTrue(bold.alt)
        assertEquals("Alt+B", bold.label)
        assertEquals("bold", markdownBindingFor(Key.B, shift = false, alt = true, bindings = effective)?.commandId)
        assertNull(markdownBindingFor(Key.B, shift = false, alt = false, bindings = effective))
    }

    @Test
    fun normalizePutsModifiersInTheFixedOrder() {
        assertEquals("Ctrl/Cmd+Shift+Alt+K", normalizeMarkdownShortcut("alt+shift+ctrl/cmd+k"))
        assertEquals("Ctrl/Cmd+Shift+K", normalizeMarkdownShortcut("shift+Ctrl/Cmd+K"))
        assertEquals("Ctrl/Cmd+B", normalizeMarkdownShortcut("  ctrl/cmd + b  "))
        assertEquals("Alt+1", normalizeMarkdownShortcut("alt+1"))
    }

    @Test
    fun normalizeAcceptsMacSymbolsAndNamedKeys() {
        // 用户很可能直接抄 macOS 菜单上的显示写法。
        assertEquals("Ctrl/Cmd+Shift+X", normalizeMarkdownShortcut("⇧⌘X"))
        assertEquals("Alt+K", normalizeMarkdownShortcut("⌥K"))
        assertEquals("Ctrl/Cmd+Alt+K", normalizeMarkdownShortcut("⌥⌘K"))
        // Compose 属性名也认，规整成单字符写法。
        assertEquals("Ctrl/Cmd+/", normalizeMarkdownShortcut("Ctrl/Cmd+Slash"))
        assertEquals("Ctrl/Cmd+Shift+-", normalizeMarkdownShortcut("ctrl/cmd+shift+minus"))
        assertEquals("Alt+=", normalizeMarkdownShortcut("option+equals"))
    }

    @Test
    fun normalizeRejectsInvalidInput() {
        assertNull(normalizeMarkdownShortcut(""))
        assertNull(normalizeMarkdownShortcut("   "))
        assertNull(normalizeMarkdownShortcut("K"), "没有修饰键")
        assertNull(normalizeMarkdownShortcut("Shift+K"), "只有 Shift，不算命令键位")
        assertNull(normalizeMarkdownShortcut("Ctrl/Cmd+"), "没有键")
        assertNull(normalizeMarkdownShortcut("Ctrl/Cmd+Shift"), "只有修饰键")
        assertNull(normalizeMarkdownShortcut("Ctrl/Cmd+K+J"), "两个键")
        assertNull(normalizeMarkdownShortcut("Ctrl/Cmd+Unknown"), "白名单外的键")
        assertNull(normalizeMarkdownShortcut("Ctrl/Cmd++K"), "空 token")
    }

    @Test
    fun shortcutLabelForKeyUsesTheWhitelist() {
        assertEquals("Ctrl/Cmd+Shift+K", shortcutLabelForKey(Key.K, ctrlCmd = true, shift = true, alt = false))
        assertEquals("Ctrl/Cmd+Alt+K", shortcutLabelForKey(Key.K, ctrlCmd = true, shift = false, alt = true))
        assertEquals("Alt+K", shortcutLabelForKey(Key.K, ctrlCmd = false, shift = false, alt = true))
        assertEquals("Ctrl/Cmd+3", shortcutLabelForKey(Key.Three, ctrlCmd = true, shift = false, alt = false))
    }

    @Test
    fun unsupportedKeysAndModifierCombinationsReturnNull() {
        assertNull(shortcutLabelForKey(Key.F1, ctrlCmd = true, shift = false, alt = false))
        assertNull(shortcutLabelForKey(Key.Enter, ctrlCmd = true, shift = false, alt = false))
        assertNull(shortcutLabelForKey(Key.DirectionUp, ctrlCmd = true, shift = false, alt = false))
        assertNull(shortcutLabelForKey(Key.NumPad1, ctrlCmd = true, shift = false, alt = false))
        // Shift 单独不能算「有修饰键」：normalize 也不接受 Shift+K 这种写法。
        assertNull(shortcutLabelForKey(Key.K, ctrlCmd = false, shift = true, alt = false))
        assertNull(shortcutLabelForKey(Key.K, ctrlCmd = false, shift = false, alt = false))
        // 加号是 label 的分隔符，故意不做键位。
        assertNull(shortcutLabelForKey(Key.Plus, ctrlCmd = true, shift = false, alt = false))
    }

    @Test
    fun capturedLettersAndDigitsRoundTripThroughNormalize() {
        supportedLetterKeys.forEachIndexed { index, key ->
            val expected = "Ctrl/Cmd+" + ('A' + index)
            assertEquals(expected, shortcutLabelForKey(key, true, false, false), "字母 " + ('A' + index))
            assertEquals(expected, normalizeMarkdownShortcut(expected.lowercase()), "小写输入 " + expected)
            val shifted = assertNotNull(shortcutLabelForKey(key, true, true, true))
            assertEquals(shifted, normalizeMarkdownShortcut(shifted), "Shift+Alt 往返 " + shifted)
        }
        supportedDigitKeys.forEachIndexed { index, key ->
            val expected = "Ctrl/Cmd+" + index
            assertEquals(expected, shortcutLabelForKey(key, true, false, false), "数字 " + index)
            assertEquals(expected, normalizeMarkdownShortcut(expected), "数字往返 " + expected)
        }
    }

    @Test
    fun capturedSymbolsRoundTripThroughNormalize() {
        for ((key, token) in supportedSymbolKeys) {
            val label = assertNotNull(shortcutLabelForKey(key, true, false, false), "符号键 " + token)
            assertEquals("Ctrl/Cmd+" + token, label)
            assertEquals(label, normalizeMarkdownShortcut(label), "符号往返 " + token)
            // 单字符输入（用户手打）也要能规整到同一个 label。
            assertEquals(label, normalizeMarkdownShortcut("ctrl/cmd+" + token), "单字符输入 " + token)
        }
    }

    @Test
    fun hintLinesFollowTheCommandTableOrder() {
        val hints = markdownShortcutHintLines(defaultMarkdownBindings)
        assertEquals(defaultMarkdownBindings.size, hints.size)
        val expected = markdownCommands.mapNotNull { command ->
            val binding = defaultMarkdownBindings.firstOrNull { it.commandId == command.id }
                ?: return@mapNotNull null
            ShortcutHint(action = command.label, keys = platformShortcutLabel(binding.label))
        }
        assertEquals(expected, hints)
        assertTrue(hints.all { it.owned })
    }

    @Test
    fun hintLinesReflectOverridesAndUnbinds() {
        val effective = effectiveMarkdownBindings(mapOf("bold" to "Ctrl/Cmd+J", "italic" to ""))
        val hints = markdownShortcutHintLines(effective)
        assertEquals(16, hints.size)
        assertEquals(platformShortcutLabel("Ctrl/Cmd+J"), hints.first { it.action == "粗体" }.keys)
        assertNull(hints.firstOrNull { it.action == "斜体" })
    }

    @Test
    fun hintLinesSkipUnknownCommandIds() {
        val hints = markdownShortcutHintLines(
            listOf(MarkdownBinding("nope", Key.J, false, label = "Ctrl/Cmd+J")),
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun resolutionDoesNotDependOnMapIterationOrder() {
        // 配置从 JSON 读回来时 Map 顺序不稳定：同一份覆盖必须算出同一套键位。
        val first = linkedMapOf("bold" to "Ctrl/Cmd+I", "italic" to "Ctrl/Cmd+B")
        val second = linkedMapOf("italic" to "Ctrl/Cmd+B", "bold" to "Ctrl/Cmd+I")
        assertEquals(
            effectiveMarkdownBindings(first).associate { it.commandId to it.label },
            effectiveMarkdownBindings(second).associate { it.commandId to it.label },
        )
    }

    @Test
    fun swappingTwoKeysKeepsBothOverridesEffective() {
        val bindings = effectiveMarkdownBindings(
            mapOf("bold" to "Ctrl/Cmd+I", "italic" to "Ctrl/Cmd+B"),
        ).associateBy { it.commandId }
        assertEquals("Ctrl/Cmd+I", bindings.getValue("bold").label)
        assertEquals("Ctrl/Cmd+B", bindings.getValue("italic").label)
    }
}
