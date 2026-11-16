package dev.trellis.app.desktop

import androidx.compose.ui.input.key.Key

/**
 * 可被用户覆盖的 Markdown 快捷键注册表。
 *
 * 为什么要有这一层：改造前快捷键是写死在 `MarkdownCommands.kt` 里的 14 条常量，
 * 但快捷键恰恰是最该让用户改的东西——`Cmd+B` 可能和浏览器、输入法冲突，用户想换成别的组合。
 * 这里把「默认键位」和「用户覆盖」拆成两件事：默认表始终完整可用，覆盖只影响一次解析结果；
 * 非法输入一律忽略而不是抛异常（键位偏好坏了不能让编辑器打不开）。
 *
 * label 用平台无关写法（`Ctrl/Cmd+B`）：Windows/Linux 渲染成 `Ctrl+B`、macOS 渲染成 `⌘B`，
 * 显示前统一过 [platformShortcutLabel]。修饰键顺序固定 `Ctrl/Cmd` → `Shift` → `Alt`，
 * 好处是同一个物理按键在任何平台捕获出来都是同一个字符串，用户把配置从一个平台带到
 * 另一个平台时不会「认不出来」。
 */

/**
 * 一条设备无关的快捷键：命令 + 物理按键 + 是否按 Shift/Alt + 规范化 label。
 *
 * [label] 之所以存一份而不是每次从 [key] 现算，是因为它会被写进用户配置：
 * 「存进去的字符串」必须稳定，读回时只要过一次 [normalizeMarkdownShortcut] 就能用，
 * 不需要依赖键名映射表在版本间保持不变。
 *
 * 没有 ctrlCmd 字段：这个注册表里的键位默认都带平台命令键（Ctrl/Cmd），
 * [alt] 只是额外的修饰——17 条默认键位和「加一个 Alt 变体」就够用了。
 * 若以后要支持纯 Alt 键位，再加字段比现在猜一个语义更安全。
 */
internal data class MarkdownBinding(
    val commandId: String,
    val key: Key,
    val shift: Boolean,
    val alt: Boolean = false,
    val label: String,
)

/**
 * 17 条默认键位，顺序跟 [markdownCommands] 一致（列表顺序只影响展示，不影响查找）。
 *
 * 键位选择沿用改造前那 14 条常量的语义（用户手上的肌肉记忆比
 * 「字母联想」重要），再给当时没有快捷键的 task/codeblock/image 补三条：
 * 任务清单 `Shift+K`、代码块 `Shift+E`、图片 `Shift+I`。
 * 新增的三条都挑了未占用的组合，所以整张表 (key, shift, alt) 仍然唯一。
 */
internal val defaultMarkdownBindings: List<MarkdownBinding> = listOf(
    MarkdownBinding("h1", Key.One, false, label = "Ctrl/Cmd+1"),
    MarkdownBinding("h2", Key.Two, false, label = "Ctrl/Cmd+2"),
    MarkdownBinding("h3", Key.Three, false, label = "Ctrl/Cmd+3"),
    MarkdownBinding("paragraph", Key.Zero, false, label = "Ctrl/Cmd+0"),
    MarkdownBinding("bullet", Key.L, true, label = "Ctrl/Cmd+Shift+L"),
    MarkdownBinding("numbered", Key.O, true, label = "Ctrl/Cmd+Shift+O"),
    MarkdownBinding("task", Key.K, true, label = "Ctrl/Cmd+Shift+K"),
    MarkdownBinding("quote", Key.Q, true, label = "Ctrl/Cmd+Shift+Q"),
    MarkdownBinding("rule", Key.R, true, label = "Ctrl/Cmd+Shift+R"),
    MarkdownBinding("codeblock", Key.E, true, label = "Ctrl/Cmd+Shift+E"),
    MarkdownBinding("bold", Key.B, false, label = "Ctrl/Cmd+B"),
    MarkdownBinding("italic", Key.I, false, label = "Ctrl/Cmd+I"),
    MarkdownBinding("strike", Key.X, true, label = "Ctrl/Cmd+Shift+X"),
    MarkdownBinding("code", Key.C, true, label = "Ctrl/Cmd+Shift+C"),
    MarkdownBinding("link", Key.K, false, label = "Ctrl/Cmd+K"),
    MarkdownBinding("table", Key.T, true, label = "Ctrl/Cmd+Shift+T"),
    MarkdownBinding("image", Key.I, true, label = "Ctrl/Cmd+Shift+I"),
)

/**
 * 按一次按键查命令。
 *
 * [alt] 是独立参数而不是并进 label：`Ctrl/Cmd+Shift+K` 和 `Ctrl/Cmd+Shift+Alt+K`
 * 是两条不同的键位，编辑器分发时要能精确区分；默认 false 覆盖绝大多数调用。
 * 查找用 [bindings] 而不是固化默认表，这样换了用户覆盖后的清单也能直接分发。
 */
internal fun markdownBindingFor(
    key: Key,
    shift: Boolean,
    alt: Boolean = false,
    bindings: List<MarkdownBinding>,
): MarkdownBinding? = bindings.firstOrNull { it.key == key && it.shift == shift && it.alt == alt }

/**
 * 把默认表 + 用户覆盖合成为「当前生效键位」。
 *
 * 规则（每一条都是为了不让坏配置把编辑器的键位搞乱）：
 * - 只认 [markdownCommands] 里存在的 id，别的 id 忽略——命令被删了，旧配置不该报错；
 * - 空串/全空白 = 解绑（这条命令没有快捷键），不是「改坏」；
 * - label 必须能过 [normalizeMarkdownShortcut]，否则保留默认；
 * - 与其它命令当前的 (key, shift, alt) 撞车时忽略这条覆盖（保留默认），保证结果唯一——
 *   两个命令共用一个按键时，按下去到底执行哪个是没有答案的，不如拒绝。
 *
 * 结果是按 [defaultMarkdownBindings] 顺序排列的列表，解绑的命令直接不出现。
 */
internal fun effectiveMarkdownBindings(overrides: Map<String, String>): List<MarkdownBinding> {
    val known = overrides.filterKeys { markdownCommand(it) != null }
    fun rawOf(commandId: String): String? = known[commandId]?.trim()

    // 「被用户接管」的命令 = 有合法覆盖或明确解绑。它们的默认键位要让出来，其余命令的默认键位先占位，
    // 这样「把 A 让给 B」和「改回默认」都不会因为处理顺序不同算出两套结果。
    val managed = defaultMarkdownBindings
        .filter { row ->
            val raw = rawOf(row.commandId) ?: return@filter false
            raw.isEmpty() || parseMarkdownShortcut(raw) != null
        }
        .map { it.commandId }
        .toSet()

    val resolved = LinkedHashMap<String, MarkdownBinding>()
    val used = mutableSetOf<Triple<Key, Boolean, Boolean>>()

    // 第一轮：没被接管的命令保持默认键位，并占住这些键。
    for (row in defaultMarkdownBindings) {
        if (row.commandId in managed) continue
        if (!used.add(Triple(row.key, row.shift, row.alt))) continue
        resolved[row.commandId] = row
    }

    // 第二轮：用户改过的键位。按默认表顺序处理（与 Map 迭代顺序无关）。与已占键位撞车就忽略，
    // 留给第三轮回退默认；写入侧（AppRuntime.commitMarkdownKeys）会拦住这种会失效的配置。
    for (row in defaultMarkdownBindings) {
        if (row.commandId !in managed) continue
        val raw = rawOf(row.commandId) ?: continue
        if (raw.isEmpty()) continue // 明确解绑
        val parsed = parseMarkdownShortcut(raw) ?: continue
        val triple = Triple(parsed.key, parsed.shift, parsed.alt)
        if (!used.add(triple)) continue
        resolved[row.commandId] = MarkdownBinding(
            commandId = row.commandId,
            key = parsed.key,
            shift = parsed.shift,
            alt = parsed.alt,
            label = parsed.label,
        )
    }

    // 第三轮：覆盖非法/撞车的命令回退默认（默认键位还空着时）；解绑的命令不再出现。
    for (row in defaultMarkdownBindings) {
        if (row.commandId in resolved) continue
        val raw = rawOf(row.commandId)
        if (raw != null && raw.isEmpty()) continue
        if (!used.add(Triple(row.key, row.shift, row.alt))) continue
        resolved[row.commandId] = row
    }

    return defaultMarkdownBindings.mapNotNull { resolved[it.commandId] }
}

/**
 * 页面/窗口级保留键位：这些键不属于编辑器命令，绑上去也永远不会生效——更糟的是
 * `Ctrl/Cmd+Q` 会直接退应用。列表与 `KnowledgeScreen` 的页面快捷键、`Main.kt` 的窗口快捷键保持一致。
 */
internal val markdownReservedShortcuts: Set<String> = setOf(
    "Ctrl/Cmd+S", // 保存
    "Ctrl/Cmd+N", // 新建笔记
    "Ctrl/Cmd+/", // 打开/关闭页头菜单
    "Ctrl/Cmd+Q", // 窗口级：退出
)

/**
 * 把用户输入规整成规范 label；不合法返回 null，不抛异常。
 *
 * 接受的东西比规范 label 宽：修饰键/按键顺序随便、大小写随便、token 之间带空格、
 * 也接受 macOS 显示写法（`⇧⌘X`）和 Compose 属性名（`Slash`）——用户很可能是
 * 从界面上抄下来的，抄下来用不了就等于没有这个功能。
 *
 * 但「必须有 Ctrl/Cmd 或 Alt」这条不让步：只有 Shift 的组合不是命令键位，
 * 和 [shortcutLabelForKey] 的判定保持一致，这样「捕获 → 存 → 再捕获」能闭环。
 */
internal fun normalizeMarkdownShortcut(label: String): String? = parseMarkdownShortcut(label)?.label

/**
 * 捕获按键时用：把一次按键变成规范 label；不能当快捷键的键返回 null。
 *
 * 白名单只放字母、数字和 Compose `Key` 里确实存在的少量符号（见
 * [markdownShortcutKeyTokens]）。F1、方向键、小键盘这些没有出现在名单里的键一律
 * 返回 null——与其「猜一个用户不认识的写法」，不如明确不支持。
 * 没有 Ctrl/Cmd 也没有 Alt 时同样返回 null：Shift 单独不构成命令键位。
 */
internal fun shortcutLabelForKey(
    key: Key,
    ctrlCmd: Boolean,
    shift: Boolean,
    alt: Boolean,
): String? {
    val keyToken = keyTokenByKey[key] ?: return null
    if (!ctrlCmd && !alt) return null
    return buildShortcutLabel(keyToken, ctrlCmd, shift, alt)
}

/**
 * 「菜单」里的快捷键清单：只列出 [markdownCommands] 里当前有键位的命令。
 *
 * 顺序跟 [markdownCommands] 走而不是跟 [bindings] 走：菜单要跟斜杠命令菜单同序，
 * 用户在两处之间来回看时才不会「怎么少了一项」。keys 过 [platformShortcutLabel]，
 * macOS 显示 `⌘`/`⇧`/`⌥`，其它平台显示 `Ctrl`/`Shift`/`Alt`。
 */
internal fun markdownShortcutHintLines(bindings: List<MarkdownBinding>): List<ShortcutHint> {
    val byCommand = bindings.associateBy { it.commandId }
    return markdownCommands.mapNotNull { command ->
        val binding = byCommand[command.id] ?: return@mapNotNull null
        ShortcutHint(action = command.label, keys = platformShortcutLabel(binding.label))
    }
}

/**
 * 允许出现在快捷键里的键 → 规范写法。
 *
 * 白名单只收「有意支持」的键：字母、数字，加上 Compose `Key.Companion` 里确实存在的
 * 符号（Slash、Minus、Equals、左右方括号、Backslash、Semicolon、Apostrophe、Comma、
 * Period、Grave、At、Pound、Multiply）——都对照过属性清单，不是猜的。
 *
 * `Key.Plus` 故意不收：`+` 同时是 label 的分隔符，`Ctrl/Cmd++` 这种写法无法无歧义
 * 地拆开；给加号换一套命名写法（`Ctrl/Cmd+Plus`）又破坏了「符号用单字符」的一致格式，
 * 收益远小于成本。
 */
private val markdownShortcutKeyTokens: List<Pair<Key, String>> = listOf(
    Key.A to "A", Key.B to "B", Key.C to "C", Key.D to "D",
    Key.E to "E", Key.F to "F", Key.G to "G", Key.H to "H",
    Key.I to "I", Key.J to "J", Key.K to "K", Key.L to "L",
    Key.M to "M", Key.N to "N", Key.O to "O", Key.P to "P",
    Key.Q to "Q", Key.R to "R", Key.S to "S", Key.T to "T",
    Key.U to "U", Key.V to "V", Key.W to "W", Key.X to "X",
    Key.Y to "Y", Key.Z to "Z",
    Key.Zero to "0", Key.One to "1", Key.Two to "2", Key.Three to "3",
    Key.Four to "4", Key.Five to "5", Key.Six to "6", Key.Seven to "7",
    Key.Eight to "8", Key.Nine to "9",
    Key.Slash to "/", Key.Minus to "-", Key.Equals to "=",
    Key.LeftBracket to "[", Key.RightBracket to "]",
    Key.Backslash to "\\", Key.Semicolon to ";", Key.Apostrophe to "'",
    Key.Comma to ",", Key.Period to ".", Key.Grave to "`",
    Key.At to "@", Key.Pound to "#", Key.Multiply to "*",
)

private val keyTokenByKey: Map<Key, String> = markdownShortcutKeyTokens.toMap()

private val keyByToken: Map<String, Key> =
    markdownShortcutKeyTokens.associate { (key, token) -> token to key }

/**
 * 命名别名：用户/配置里可能写 Compose 的属性名（`Ctrl/Cmd+Slash`），
 * 规整时换成单字符写法（`Ctrl/Cmd+/`），保证规范 label 只有一种形态。
 */
private val namedShortcutKeyTokens: Map<String, String> = mapOf(
    "slash" to "/",
    "minus" to "-",
    "hyphen" to "-",
    "equals" to "=",
    "equal" to "=",
    "leftbracket" to "[",
    "bracketleft" to "[",
    "rightbracket" to "]",
    "bracketright" to "]",
    "backslash" to "\\",
    "semicolon" to ";",
    "apostrophe" to "'",
    "quote" to "'",
    "comma" to ",",
    "period" to ".",
    "dot" to ".",
    "grave" to "`",
    "backtick" to "`",
    "backquote" to "`",
    "at" to "@",
    "pound" to "#",
    "hash" to "#",
    "numbersign" to "#",
    "multiply" to "*",
    "asterisk" to "*",
    "star" to "*",
)

private enum class ShortcutModifier { CtrlCmd, Shift, Alt }

/** 解析出来的键位：key/shift/alt 给分发用，label 给配置与展示用。 */
private class ParsedMarkdownShortcut(
    val key: Key,
    val shift: Boolean,
    val alt: Boolean,
    val label: String,
)

/** 只调一次 [MarkdownBinding] 的 label 拼装，保证「捕获」和「解析」用同一套格式。 */
private fun buildShortcutLabel(
    keyToken: String,
    ctrlCmd: Boolean,
    shift: Boolean,
    alt: Boolean,
): String = buildList {
    if (ctrlCmd) add("Ctrl/Cmd")
    if (shift) add("Shift")
    if (alt) add("Alt")
    add(keyToken)
}.joinToString("+")

private fun modifierOf(token: String): ShortcutModifier? = when (token.lowercase()) {
    "ctrl/cmd", "cmd/ctrl", "ctrl", "control", "cmd", "command", "mod", "⌘" ->
        ShortcutModifier.CtrlCmd

    "shift", "⇧" -> ShortcutModifier.Shift
    "alt", "option", "opt", "⌥" -> ShortcutModifier.Alt
    else -> null
}

/** 单个 token → 规范键名；白名单外返回 null。 */
private fun canonicalKeyToken(raw: String): String? {
    val token = raw.filterNot { it.isWhitespace() }
    if (token.isEmpty()) return null
    if (token.length == 1) {
        val ch = token[0]
        return when {
            ch in 'a'..'z' -> ch.uppercaseChar().toString()
            ch in 'A'..'Z' -> ch.toString()
            ch in '0'..'9' -> ch.toString()
            // 符号只认白名单里有的，避免把任意一个字符都当成快捷键。
            keyByToken.containsKey(ch.toString()) -> ch.toString()
            else -> null
        }
    }
    return namedShortcutKeyTokens[token.lowercase()]
}

/**
 * 解析平台无关 label。
 *
 * 先展开 macOS 符号再按 `+` 拆：`⇧⌘X` 没有 `+`，直接拆会当成一个未知键；
 * 展开成 `Shift+Ctrl/Cmd+X` 后，后面「顺序无关」的逻辑就能统一处理。
 * 空的 token（`Ctrl/Cmd++B`、结尾的 `+`）直接判非法，比悄悄容错更能早点暴露配置写错。
 */
private fun parseMarkdownShortcut(rawLabel: String): ParsedMarkdownShortcut? {
    val trimmed = rawLabel.trim()
    if (trimmed.isEmpty()) return null
    val expanded = trimmed
        .replace("⇧", "Shift+")
        .replace("⌥", "Alt+")
        .replace("⌘", "Ctrl/Cmd+")
    val tokens = expanded.split('+').map { it.trim() }
    if (tokens.size < 2) return null
    if (tokens.any { it.isEmpty() }) return null

    var ctrlCmd = false
    var shift = false
    var alt = false
    var key: Key? = null
    var keyToken: String? = null
    for (token in tokens) {
        when (modifierOf(token.filterNot { it.isWhitespace() })) {
            ShortcutModifier.CtrlCmd -> ctrlCmd = true
            ShortcutModifier.Shift -> shift = true
            ShortcutModifier.Alt -> alt = true
            null -> {
                // 修饰键之外的 token 只能有一个：`Ctrl/Cmd+K+J` 不是一条组合键。
                if (key != null) return null
                val canonical = canonicalKeyToken(token) ?: return null
                keyToken = canonical
                key = keyByToken[canonical] ?: return null
            }
        }
    }
    val resolvedKey = key ?: return null
    val resolvedToken = keyToken ?: return null
    if (!ctrlCmd && !alt) return null
    return ParsedMarkdownShortcut(
        key = resolvedKey,
        shift = shift,
        alt = alt,
        label = buildShortcutLabel(resolvedToken, ctrlCmd, shift, alt),
    )
}
