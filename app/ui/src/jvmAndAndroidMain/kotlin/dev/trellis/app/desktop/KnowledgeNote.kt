package dev.trellis.app.desktop

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 一篇知识库笔记。文件是真相，索引只是投影（见 `docs/knowledge-base-draft.md` §3）。
 *
 * 文件格式：`<home>/knowledge/notes/<id>.md` = 可选 frontmatter + Markdown 正文。
 * 带 frontmatter 的文件仍然是普通 Markdown，Obsidian / Vim 都能直接打开；
 * 导入的笔记没有 frontmatter 也能读（标题取第一个 `#` 标题、时间取文件修改时间）。
 *
 * frontmatter 认识 title / created / updated / concepts 四个字段，其余字段原样保留，
 * 所以以后加 `source:` 之类的新字段时，旧版本读写一轮不会把数据抹掉。
 */
internal data class KnowledgeNote(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * 本笔记涉及的概念名（frontmatter 的 `concepts:`）。
     *
     * 它是「点亮」的唯一真相：概念至少被一篇笔记的这里提到，才算亮着；
     * 概念索引（[KnowledgeStore.conceptIndex]）删掉也能从这里重建，不用重新调模型。
     */
    val concepts: List<String> = emptyList(),
    /**
     * 被删除（标记被拿掉）的概念，frontmatter 的 `removed-concepts:`。
     *
     * 它只是「墓碑」：右栏默认收起，点开能看到、能恢复；不参与点亮与索引。
     * 重新在正文里 `[[标注]]` 同一个概念时，会自动从这里移除（见 [synchronizeConceptMembership]）。
     */
    val removedConcepts: List<String> = emptyList(),
    val extras: Map<String, String> = emptyMap(),
)

/** 列表用的轻量信息：不把正文全带进列表渲染。 */
internal data class NoteSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val chars: Int,
)

/** 概念索引的一行：点亮 = 至少一篇笔记的 frontmatter 里有它。 */
internal data class ConceptSummary(
    val name: String,
    val noteCount: Int,
    /** 正文里出现的次数（只在 frontmatter 标注的按 1 次算）。 */
    val mentionCount: Int,
    val updatedAt: Long,
)

/**
 * 一条引用：概念在某篇笔记正文里的命中位置。
 *
 * [start]/[end] 是正文里的字符偏移，打开笔记时用它选中命中处；
 * 正文没出现（只在 frontmatter 标注过）时是 -1，只展示笔记标题。
 */
internal data class ConceptMention(
    val noteId: String,
    val noteTitle: String,
    val noteUpdatedAt: Long,
    val snippet: String,
    val start: Int,
    val end: Int,
)

/**
 * 概念详情：定义不在这里（[ConceptMetaStore]），这里只有引用与共现的相关概念。
 *
 * [mentions] 是正文里用 `[[…]]` 标注过这个概念（或 frontmatter 里登记过）的笔记；
 * [others] 是只在正文里普通提到、没有标注的笔记——分开列，避免「提到」被当成「归属」。
 */
internal data class ConceptDetail(
    val summary: ConceptSummary,
    val mentions: List<ConceptMention>,
    val related: List<ConceptSummary>,
    val others: List<ConceptMention> = emptyList(),
)

/**
 * 搜索命中的一篇笔记：[start]/[end] 是正文里第一次出现关键词的范围（标题命中、正文没出现时是 -1），
 * 点搜索结果时编辑器用它选中并滚动到命中处；[snippet] 是命中那一行的上下文，给结果行做副标题。
 */
internal data class NoteSearchHit(
    val summary: NoteSummary,
    val start: Int = -1,
    val end: Int = -1,
    val snippet: String? = null,
)

/**
 * 别的页请求「打开某篇笔记并选中一段」（计划页「AI 自动完成」点证据用）。
 * [start]/[end] 传 -1 表示只打开笔记、不选中。
 */
internal data class KnowledgeRevealTarget(
    val noteId: String,
    val start: Int = -1,
    val end: Int = -1,
)

/** 搜索命中：概念和笔记分两组，界面上也分两组显示。 */
internal data class SearchHits(
    val concepts: List<ConceptSummary>,
    val notes: List<NoteSearchHit>,
)

internal val noteFileExtension: String = ".md"

/** 笔记 id 就是文件名；限制字符集，避免路径穿越。 */
internal val noteIdPattern: Regex = Regex("^[A-Za-z0-9_-]{1,80}$")

/**
 * 去掉标题里的行内 Markdown 标记（`**加粗**`、`` `代码` ``、`[文字](url)` 等）。
 *
 * 标题是纯文本：笔记列表、目录、搜索、删除确认里都直接显示它。文件里可以写
 * `# **RAG** 检索增强`，显示给人看的时候不该带着一串星号。只做「看起来一样」的
 * 轻量清洗，不碰代码块/表格这类块级语法。
 */
private val inlineMarkdownCleanup: List<Pair<Regex, (MatchResult) -> String>> = listOf(
    Regex("!\\[([^\\]]*)]\\([^)]*\\)") to { m: MatchResult -> m.groupValues[1] },
    Regex("\\[([^\\]]*)]\\([^)]*\\)") to { m: MatchResult -> m.groupValues[1] },
    Regex("`+([^`]*)`+") to { m: MatchResult -> m.groupValues[1] },
    Regex("\\*\\*\\*(.+?)\\*\\*\\*") to { m: MatchResult -> m.groupValues[1] },
    Regex("\\*\\*(.+?)\\*\\*") to { m: MatchResult -> m.groupValues[1] },
    Regex("__(.+?)__") to { m: MatchResult -> m.groupValues[1] },
    Regex("~~(.+?)~~") to { m: MatchResult -> m.groupValues[1] },
    Regex("\\*(?!\\s)(.+?)(?<!\\s)\\*") to { m: MatchResult -> m.groupValues[1] },
    Regex("_(?!\\s)(.+?)(?<!\\s)_") to { m: MatchResult -> m.groupValues[1] },
)

internal fun stripInlineMarkdown(text: String): String {
    var result = text
    for ((pattern, replace) in inlineMarkdownCleanup) {
        result = pattern.replace(result, replace)
    }
    return result.replace(Regex("\\s+"), " ").trim()
}

/** 标题为空时列表/编辑器上显示的占位名。 */
internal fun displayTitle(title: String): String = stripInlineMarkdown(title).ifBlank { "无标题笔记" }

/**
 * 搜索结果里给命中行当副标题的片段：去掉行首的块级标记（`###`、`>`、`-`、`1.`）、
 * 行内标记（加粗/代码/链接）和概念标注括号，只留人读得懂的文本。
 */
internal fun plainSnippet(text: String): String {
    val noBlock = text.replace(Regex("^\\s*(#{1,6}|>|[-*+]|\\d+[.)])\\s+"), "")
    return stripInlineMarkdown(stripAllConceptAnnotations(noBlock))
}

private val knownFrontMatterKeys = setOf("title", "created", "updated", "concepts", "removed-concepts")
private val isoFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
private val headingPattern = Regex("^#\\s+(.+)$")

/** 概念名上限：太长的大概率是句子而不是概念（UI 上 chips 也放不下）。 */
internal const val MAX_CONCEPT_NAME_LENGTH: Int = 40

/**
 * 规范化概念名：去首尾空白、把连续空白压成一个空格。
 *
 * 只做「不影响语义」的整理，不转换大小写、不删标点——概念是用户的词，
 * 显示时按用户写的来，比较时才用 [conceptKey]。
 */
internal fun normalizeConceptName(raw: String): String =
    raw.replace(Regex("\\s+"), " ").trim()
        .replace(",", " ").replace("，", " ").trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_CONCEPT_NAME_LENGTH)
        .trim()

/**
 * 正文里要高亮的名字：只算「本文概念」。
 *
 * 只看这篇笔记 frontmatter 里标注过（`concepts:`）的概念，再加上概念库里登记的别名；
 * 概念库里的其它概念不在本文染色——否则任何笔记里只要撞上概念库的词就一片绿。
 * 名字过一遍准入 + 身份键去重（旧数据里的 M / ef / 空白变体在这里被挡住）。
 */
internal fun highlightNamesForNote(
    concepts: List<String>,
    identities: List<ConceptIdentity>,
): List<String> {
    if (concepts.isEmpty()) return emptyList()
    val names = ArrayList<String>()
    val seen = HashSet<String>()
    for (raw in concepts) {
        val name = normalizeConceptName(raw)
        if (!isAcceptableConceptName(name)) continue
        val key = conceptIdentityKey(name)
        if (seen.add(key)) names += name
        val identity = identities.firstOrNull { conceptIdentityKey(it.name) == key } ?: continue
        for (alias in identity.aliases) {
            if (isAcceptableConceptName(alias) && seen.add(conceptIdentityKey(alias))) names += alias
        }
    }
    return names
}

/** 概念的去重/比较键：大小写不敏感。 */
internal fun conceptKey(name: String): String = normalizeConceptName(name).lowercase()

/**
 * 概念身份键：判断「是不是同一个概念」时用它。
 *
 * 比 [conceptKey] 多忽略空白——「Matryoshka 表示学习」和「Matryoshka表示学习」是同一个
 * 概念，不能因为中间有没有空格被拆成两条（AI 两次抽取很容易抽出这种变体）。
 */
internal fun conceptIdentityKey(name: String): String =
    conceptKey(name).filterNot { it.isWhitespace() }

/**
 * 概念准入：太短、纯符号、单字母缩写这类名字一旦进概念库，正文里会「绿色满天飞」
 * （M / ef / en / [] 这种）。登记、别名、高亮、抽取解析都走这里，宁缺毋滥：
 *  - 至少 2 个字符；
 *  - 至少含一个字母或汉字（纯标点、纯数字不算）；
 *  - 纯 ASCII 的名字至少 3 个字符（否则两字母缩写太容易误命中）。
 */
internal fun isAcceptableConceptName(raw: String): Boolean {
    val name = normalizeConceptName(raw)
    if (name.length < 2) return false
    if (name.none { it.isLetter() }) return false
    val asciiOnly = name.all { it.code < 0x80 }
    return !(asciiOnly && name.length < 3)
}

/** 拆 frontmatter 里一行概念列表：中英文逗号/分号都可，去空、按 [conceptKey] 去重。 */
internal fun splitConceptList(raw: String): List<String> =
    raw.split(',', '，', ';', '；')
        .map(::normalizeConceptName)
        .filter { it.isNotBlank() }
        .distinctBy(::conceptKey)

/** 写回一行概念列表；顺序保留，字段差异要稳定。 */
internal fun joinConceptList(names: List<String>): String =
    names.map(::normalizeConceptName).filter { it.isNotBlank() }.distinctBy(::conceptKey).joinToString(", ")

private fun formatTimestamp(ms: Long, zone: ZoneId): String =
    isoFormatter.format(Instant.ofEpochMilli(ms).atZone(zone))

private fun parseTimestamp(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

private fun oneLine(value: String): String = value.replace('\n', ' ').replace('\r', ' ').trim()

/**
 * 解析一篇笔记。
 *
 * 容错优先：frontmatter 没闭合、字段写坏、整份就是普通 Markdown，都当正文处理；
 * 绝不因为一个文件坏了把列表/页面带崩。
 */
internal fun parseNoteMarkdown(id: String, raw: String, zone: ZoneId, fallbackMs: Long): KnowledgeNote {
    val text = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val lines = text.split('\n')
    val fields = LinkedHashMap<String, String>()
    var bodyStart = 0

    if (lines.isNotEmpty() && lines.first().trim() == "---") {
        val close = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
        if (close != null) {
            for (index in 1 until close) {
                val line = lines[index]
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().lowercase()
                if (key.isEmpty()) continue
                fields[key] = line.substring(colon + 1).trim()
            }
            bodyStart = close + 1
        }
    }

    // frontmatter 之后惯常有一个空行；它属于格式，不属于正文。
    var body = lines.drop(bodyStart).joinToString("\n")
    if (bodyStart > 0 && body.startsWith("\n")) body = body.removePrefix("\n")

    val title = fields["title"]?.takeIf { it.isNotBlank() }
        ?: body.lineSequence().map { it.trim() }.firstOrNull { headingPattern.matches(it) }
            ?.let { headingPattern.matchEntire(it)?.groupValues?.get(1)?.trim() }
            ?.let(::stripInlineMarkdown)
        ?: ""
    val created = parseTimestamp(fields["created"]) ?: fallbackMs
    val updated = parseTimestamp(fields["updated"]) ?: created
    val extras = fields.filterKeys { it !in knownFrontMatterKeys }
    val concepts = splitConceptList(fields["concepts"].orEmpty())
    val removedConcepts = splitConceptList(fields["removed-concepts"].orEmpty())

    return KnowledgeNote(
        id = id,
        title = title,
        body = body,
        createdAt = created,
        updatedAt = updated,
        concepts = concepts,
        removedConcepts = removedConcepts,
        extras = extras,
    )
}

// ---- AI 补全槽位与 AI 内容标记（约定见 `docs/knowledge-base-draft.md` §11） ----

/** 行尾三个全角句号 = 让 AI 在这里补一句话；用户自己写的，明文可见。 */
internal const val AI_SLOT_MARKER: String = "。。。"

/** 行内 AI 内容标记：预览时渲染成绿色大引号，编辑态看到的是这两个符号。 */
internal const val AI_INLINE_OPEN: String = "{{"
internal const val AI_INLINE_CLOSE: String = "}}"

/** 块级 AI 内容标记：`:::ai` 到 `:::` 之间是 AI 写的多行内容。 */
internal const val AI_BLOCK_START: String = ":::ai"
internal const val AI_BLOCK_END: String = ":::"

/** 一次保存最多补几个槽位：防止用户写一整屏 `。。。` 让模型自由发挥。 */
internal const val MAX_AI_FILL_SLOTS: Int = 3

/** 一个槽位补出来的内容上限：一句话解释，不是小作文。 */
internal const val MAX_AI_FILL_CHARS: Int = 80

/** 槽位标签的展示上限：标签只是给模型看的提示，超长就截断，绝不因此丢掉槽位。 */
internal const val AI_SLOT_LABEL_DISPLAY_MAX: Int = 40

/** `。。。` 槽位的稳定标识：同一行号永远同一个 id，模型回传后按它对齐。 */
internal fun aiSlotId(lineIndex: Int): String = "slot@$lineIndex"

/** 正文里还没被 AI 补过的槽位；[lineText] 是那一行的原文（给提示词看，可能已截断）。 */
internal data class AiFillSlot(
    val label: String,
    val lineIndex: Int,
    val lineText: String = "",
) {
    /** 稳定标识：同一行号 → 同一个 id，模型回传时按它对齐。 */
    val id: String get() = aiSlotId(lineIndex)
}

/** 补全结果：新正文 + 这次真的补上的槽位（标签，空标签用「第 N 行」）。 */
internal data class AiFillResult(val body: String, val applied: List<String>)

/** 标签前面的列表前缀：`- ` / `* ` / `+ ` / `1. ` / `1) `，可有可无。 */
private val aiSlotLabelPrefixPattern = Regex("^\\s*(?:[-*+]\\s+|\\d+[.)]\\s+)?")

/** 标签里的 `[[概念]]` / `{{AI 内容}}` 只保留里面的字。 */
private val aiSlotLabelAnnotationPattern = Regex("\\[\\[([^\\]]*)\\]\\]|\\{\\{([^}]*)\\}\\}")

/**
 * 槽位语法：行尾是 `。。。`，前面写什么都行。
 *
 * 三个句号必须在行尾——正文中间出现只是普通标点，不触发补全；
 * 代码块和 `:::ai` 块里的行也不算（见 [forEachPlainBodyLine]）。
 * 标签不是必需的：整行只有 `。。。` 也是合法槽位（空标签）。
 */
internal fun aiFillSlotLabel(line: String): String? {
    val trimmed = line.trimEnd()
    if (!trimmed.endsWith(AI_SLOT_MARKER)) return null
    return aiSlotLabelFrom(trimmed.dropLast(AI_SLOT_MARKER.length))
}

/**
 * 从 `。。。` 前面那段文字里提取标签。
 *
 * 去列表前缀、去 `[[…]]`/`{{…}}` 包装、去 Markdown 强调符、去结尾冒号、折叠空白；
 * 空标签返回空串（不是 null）——空串 = 「这一行没有标签」，仍然是槽位。
 */
private fun aiSlotLabelFrom(raw: String): String {
    var text = aiSlotLabelPrefixPattern.replace(raw, "")
    text = aiSlotLabelAnnotationPattern.replace(text) { match ->
        match.groupValues[1].ifEmpty { match.groupValues[2] }
    }
    text = text.replace('*', ' ').replace('_', ' ').replace('`', ' ')
    text = text.trim().trimEnd('：', ':')
    text = text.replace(Regex("\\s+"), " ").trim()
    return text.take(AI_SLOT_LABEL_DISPLAY_MAX)
}

/**
 * 找出这篇笔记里所有待补全的槽位（最多 [MAX_AI_FILL_SLOTS] 个，保持正文顺序）。
 *
 * 不同行即使标签相同也各算各的：id 由行号决定，回填不会认错行。
 * [lineOffset] 给长笔记分段用：把某一段的局部行号换算成整篇行号，id 才能落回原文。
 */
internal fun findAiFillSlots(body: String, lineOffset: Int = 0): List<AiFillSlot> {
    val slots = ArrayList<AiFillSlot>()
    forEachPlainBodyLine(body) { index, line ->
        if (slots.size >= MAX_AI_FILL_SLOTS) return@forEachPlainBodyLine
        val label = aiFillSlotLabel(line) ?: return@forEachPlainBodyLine
        slots += AiFillSlot(label, index + lineOffset, line)
    }
    return slots
}

/** 取整篇正文里指定行上的槽位；那行不是槽位（或在代码块里）就返回 null。 */
internal fun findAiFillSlotAt(body: String, lineIndex: Int): AiFillSlot? {
    if (lineIndex < 0) return null
    var found: AiFillSlot? = null
    forEachPlainBodyLine(body) { index, line ->
        if (index != lineIndex) return@forEachPlainBodyLine
        val label = aiFillSlotLabel(line) ?: return@forEachPlainBodyLine
        found = AiFillSlot(label, index, line)
    }
    return found
}

/**
 * 单槽补全的上下文：该行所在段落 + 前后各 [radius] 段（段落按空行切）。
 *
 * 段落只认空行分界：列表、引用连着写算同一段，和用户「一段话」的直觉一致。
 */
internal fun aiFillContextAround(body: String, lineIndex: Int, radius: Int = 2): String {
    val lines = body.replace("\r\n", "\n").split('\n')
    if (lines.isEmpty()) return ""
    val target = lineIndex.coerceIn(0, lines.lastIndex)
    var start = target
    while (start > 0 && lines[start - 1].isNotBlank()) start--
    var end = target
    while (end < lines.lastIndex && lines[end + 1].isNotBlank()) end++
    repeat(radius.coerceAtLeast(0)) {
        var previous = start - 1
        while (previous >= 0 && lines[previous].isBlank()) previous--
        if (previous < 0) return@repeat
        while (previous > 0 && lines[previous - 1].isNotBlank()) previous--
        start = previous
    }
    repeat(radius.coerceAtLeast(0)) {
        var next = end + 1
        while (next <= lines.lastIndex && lines[next].isBlank()) next++
        if (next > lines.lastIndex) return@repeat
        while (next < lines.lastIndex && lines[next + 1].isNotBlank()) next++
        end = next
    }
    return lines.subList(start, end + 1).joinToString("\n").trim()
}

/** 该行之前最近的直系标题链（从一级到最近一级），给单槽补全当上下文。 */
internal fun headingChainBefore(body: String, lineIndex: Int): List<String> {
    val chain = ArrayList<Pair<Int, String>>()
    // parseOutline 只返回树根，必须按文档顺序把整棵树拍平再按行号过滤。
    fun visit(items: List<OutlineItem>) {
        for (item in items) {
            if (item.lineIndex >= lineIndex) continue
            while (chain.isNotEmpty() && chain.last().first >= item.level) chain.removeAt(chain.lastIndex)
            chain += item.level to item.text
            visit(item.children)
        }
    }
    visit(parseOutline(body))
    return chain.map { it.second }
}

/**
 * 把模型补出来的内容填回正文。
 *
 * 只替换行尾那一个 `。。。`，其它字符一字不动；填进去的内容包成 `{{…}}`，
 * 预览时渲染成绿色大引号。已经补过的行没有 `。。。` 了，所以再跑一次不会变。
 *
 * @param fills 槽位 id（`slot@行号`）→ 补全内容；按 id 落行，不会认错行。
 */
internal fun applyAiFills(body: String, fills: Map<String, String>): AiFillResult {
    if (fills.isEmpty()) return AiFillResult(body, emptyList())
    val wanted = fills.entries
        .mapNotNull { (id, text) ->
            val key = id.trim()
            val value = normalizeAiFillText(text)
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        .toMap()
    if (wanted.isEmpty()) return AiFillResult(body, emptyList())

    val lines = body.replace("\r\n", "\n").split('\n').toMutableList()
    val applied = mutableListOf<String>()
    forEachPlainBodyLine(body) { index, line ->
        val label = aiFillSlotLabel(line) ?: return@forEachPlainBodyLine
        val text = wanted[aiSlotId(index)] ?: return@forEachPlainBodyLine
        // 只动行尾那个槽位标记：行里前面若也有「。。。」（普通标点）保持原样。
        lines[index] = line.removeSuffix(AI_SLOT_MARKER) + AI_INLINE_OPEN + text + AI_INLINE_CLOSE
        applied += label.ifEmpty { "第 ${index + 1} 行" }
    }
    if (applied.isEmpty()) return AiFillResult(body, emptyList())
    return AiFillResult(lines.joinToString("\n"), applied)
}

/** 概念标记 `[[…]]` 的两侧。和 `{{…}}`（AI 补全）一样是「语法层」标记，预览时只渲染内容。 */
internal const val CONCEPT_OPEN: String = "[["
internal const val CONCEPT_CLOSE: String = "]]"

/**
 * 把抽取出来的概念名在正文里第一次出现的地方包成 `[[概念]]`。
 *
 * 规则（和人工标记一致，也不破坏原文）：
 *  - 只标第一次出现：概念可能在正文里出现很多次，标满会花；
 *  - 只在「普通正文行」里找：代码块、`:::ai` 块、已有的 `{{…}}`/`[[…]]` 里不标；
 *  - 名字在正文里找不到就跳过（模型编出来的名字不落盘）；
 *  - 已经标过（`[[名字]]` 存在）的东西不再重复包。
 */
internal fun markConceptMentions(body: String, names: List<String>): String {
    if (body.isBlank() || names.isEmpty()) return body
    val targets = names.map(::normalizeConceptName).filter { it.isNotBlank() }.distinctBy(::conceptKey)
    if (targets.isEmpty()) return body
    var result = body.replace("\r\n", "\n")
    for (name in targets) {
        if (result.contains(CONCEPT_OPEN + name + CONCEPT_CLOSE)) continue
        val index = findPlainMention(result, name) ?: continue
        result = result.substring(0, index) + CONCEPT_OPEN + name + CONCEPT_CLOSE +
            result.substring(index + name.length)
    }
    return result
}

/** 在普通正文行里找 [name] 第一个不在标记内部的字符位置；找不到返回 null。 */
private fun findPlainMention(body: String, name: String): Int? {
    var offset = 0
    var inCode = false
    var inAiBlock = false
    for (line in body.split('\n')) {
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> inCode = !inCode
            inCode -> Unit
            trimmed.startsWith(":::ai") -> inAiBlock = true
            trimmed == ":::" -> inAiBlock = false
            inAiBlock -> Unit
            else -> {
                var from = 0
                while (true) {
                    val local = line.indexOf(name, from)
                    if (local < 0) break
                    val absolute = offset + local
                    if (!insidePair(body, absolute, name.length)) return absolute
                    from = local + 1
                }
            }
        }
        offset += line.length + 1
    }
    return null
}

/** [position] 处那段文字是否已经落在 `[[…]]` 或 `{{…}}` 内部。 */
private fun insidePair(body: String, position: Int, length: Int): Boolean {
    fun inside(open: String, close: String): Boolean {
        val openAt = body.lastIndexOf(open, position)
        if (openAt < 0) return false
        val closeBefore = body.lastIndexOf(close, position)
        if (closeBefore > openAt) return false
        val closeAfter = body.indexOf(close, position + length)
        return closeAfter >= 0
    }
    return inside(CONCEPT_OPEN, CONCEPT_CLOSE) || inside("{{", "}}")
}

/**
 * 「正文有没有真的变过」的比较基准，用来决定焦点离开后要不要再麻烦模型。
 *
 * 只比较人写的内容：`[[概念]]` 只留名字、`{{AI 补全}}` 整段去掉、`:::ai` 块整块去掉、
 * 行尾空白忽略。于是「AI 补全」「人删/加 AI 做的标记」都不算正文变了，
 * 不会反复解析；反过来，人只要真的改了正文，基准就对不上。
 */
internal fun bodyForAiComparison(body: String): String {
    val kept = StringBuilder()
    var inAiBlock = false
    for (line in body.replace("\r\n", "\n").split('\n')) {
        val trimmed = line.trim()
        if (inAiBlock) {
            if (trimmed == AI_BLOCK_END) inAiBlock = false
            continue
        }
        if (trimmed.startsWith(AI_BLOCK_START)) {
            inAiBlock = true
            continue
        }
        kept.append(line.trimEnd()).append('\n')
    }
    return kept.toString()
        .replace(Regex("\\{\\{[^\\n]*?\\}\\}"), "")
        .replace(Regex("\\[\\[([^\\[\\]\\n]*?)\\]\\]"), "$1")
        // 删掉 AI 块会留下空行：连续空行压成一个，避免「只删了 AI 内容」看起来像改过正文。
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

/**
 * 预览里点 AI 块的 ×：删掉这一行内容；块空了就连 `:::ai` / `:::` 一起删。
 * 只在 `:::ai` 块里找，避免误删正文里一模一样的普通行；找不到返回 null。
 */
internal fun removeAiBlockLine(body: String, line: String): String? {
    val lines = body.replace("\r\n", "\n").split('\n').toMutableList()
    val target = line.trim()
    var inCode = false
    var inBlock = false
    var targetIndex = -1
    for (index in lines.indices) {
        val trimmed = lines[index].trim()
        if (inCode) {
            if (trimmed.startsWith("```")) inCode = false
            continue
        }
        if (trimmed.startsWith("```")) {
            inCode = true
            continue
        }
        if (inBlock) {
            if (trimmed == AI_BLOCK_END) {
                inBlock = false
                continue
            }
            if (trimmed == target || trimListPrefix(trimmed) == target) {
                targetIndex = index
                break
            }
            continue
        }
        if (trimmed == AI_BLOCK_START) inBlock = true
    }
    if (targetIndex < 0) return null
    lines.removeAt(targetIndex)

    // 删完可能只剩一个空块：把 `:::ai` + `:::` 也去掉，不留下空壳。
    var index = 0
    while (index < lines.size) {
        if (lines[index].trim() == AI_BLOCK_START) {
            var next = index + 1
            while (next < lines.size && lines[next].isBlank()) next++
            if (next < lines.size && lines[next].trim() == AI_BLOCK_END) {
                lines.removeAt(next)
                lines.removeAt(index)
                continue
            }
        }
        index++
    }
    return lines.joinToString("\n")
}

/** `- ` / `* ` / `+ ` 开头的列表项去掉前缀：AI 块里两种写法都允许。 */
private fun trimListPrefix(line: String): String =
    line.removePrefix("- ").removePrefix("* ").removePrefix("+ ").trim()

/** 删掉正文里一段行内 AI 内容（`{{…}}` 连同里面的字）；找不到返回 null。 */
internal fun removeAiInline(body: String, content: String): String? {
    val target = AI_INLINE_OPEN + content + AI_INLINE_CLOSE
    val index = body.indexOf(target)
    if (index < 0) return null
    return body.removeRange(index, index + target.length)
}

/**
 * 模型偶尔会按 JSON mode 回 `{"content": "..."}`（接口被别处共用的老毛病）：
 * 能认出来就只取里面那段字符串，别把 JSON 塞进正文。
 */
private fun aiFillSentenceFrom(raw: String): String {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return raw
    val parsed = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject ?: return raw
    val first = parsed.values.firstOrNull { it is JsonPrimitive && it.isString } as? JsonPrimitive ?: return raw
    return first.content
}

/** 把模型输出整理成能塞进 `{{…}}` 的一行字：不加换行、不套娃、不超长。 */
internal fun normalizeAiFillText(raw: String): String =
    aiFillSentenceFrom(raw).replace("\r\n", " ")
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .removePrefix(AI_INLINE_OPEN)
        .removeSuffix(AI_INLINE_CLOSE)
        .replace(Regex("\\s{2,}"), " ")
        .take(MAX_AI_FILL_CHARS)
        .trim()

/**
 * 逐行扫正文里「普通」的行：跳过 fenced code 与 `:::ai` 块。
 *
 * [visit] 的参数是原始行号（用于改行）和去掉行尾空格后的行内容。
 */
private fun forEachPlainBodyLine(body: String, visit: (index: Int, line: String) -> Unit) {
    val lines = body.replace("\r\n", "\n").split('\n')
    var inCode = false
    var inBlock = false
    for (index in lines.indices) {
        val line = lines[index].trimEnd()
        val trimmed = line.trim()
        if (inCode) {
            if (trimmed.startsWith("```")) inCode = false
            continue
        }
        if (trimmed.startsWith("```")) {
            inCode = true
            continue
        }
        if (inBlock) {
            if (trimmed == AI_BLOCK_END) inBlock = false
            continue
        }
        if (trimmed == AI_BLOCK_START) {
            inBlock = true
            continue
        }
        visit(index, line)
    }
}

/** 序列化回文件；字段顺序固定，diff 才稳定。 */
internal fun formatNoteMarkdown(note: KnowledgeNote, zone: ZoneId): String = buildString {
    append("---\n")
    append("title: ").append(oneLine(note.title)).append('\n')
    append("created: ").append(formatTimestamp(note.createdAt, zone)).append('\n')
    append("updated: ").append(formatTimestamp(note.updatedAt, zone)).append('\n')
    val concepts = joinConceptList(note.concepts)
    if (concepts.isNotEmpty()) append("concepts: ").append(concepts).append('\n')
    val removed = joinConceptList(note.removedConcepts)
    if (removed.isNotEmpty()) append("removed-concepts: ").append(removed).append('\n')
    for ((key, value) in note.extras) {
        if (key.lowercase() in knownFrontMatterKeys) continue
        append(key).append(": ").append(oneLine(value)).append('\n')
    }
    append("---\n")
    if (note.body.isNotEmpty()) {
        append('\n')
        append(note.body)
    }
}

// ---- 导出：给别的工具 / 人看的干净 Markdown（约定见 `docs/knowledge-base-draft.md` §12） ----

private val exportHeadingPattern = Regex("^(#{1,6})\\s+(.+)$")

/**
 * 导出正文：frontmatter 不带出去，AI 标记还原成普通文字，标题作为一级标题补在最上面。
 *
 * 正文已经用同名标题开头（例如 `# **RAG** 检索增强`）时不再重复加标题；
 * 其它情况（标题只存在 frontmatter、正文从段落或二级标题开始）才补 `# 标题`。
 */
internal fun exportNoteMarkdown(note: KnowledgeNote): String {
    val cleanBody = stripAllConceptAnnotations(cleanAiMarkersForExport(note.body))
    val title = displayTitle(note.title)
    val firstHeading = cleanBody.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?.let { exportHeadingPattern.matchEntire(it) }
        ?.let { stripInlineMarkdown(it.groupValues[2]) }
    return buildString {
        if (firstHeading != title) {
            append("# ").append(title).append("\n\n")
        }
        append(cleanBody.trimEnd())
        append('\n')
    }
}

/**
 * 把编辑器的 AI 标记还原成普通 Markdown 文字。
 *
 * - `{{…}}` → 去掉花括号，只留里面的内容；
 * - `:::ai` / `:::` 围栏行删掉，块里的内容按原样保留；
 * - fenced code（```）里一字不动——用户贴的代码里出现 `{{` 不该被改。
 */
internal fun cleanAiMarkersForExport(body: String): String {
    val lines = body.replace("\r\n", "\n").split('\n')
    val output = ArrayList<String>(lines.size)
    var inCode = false
    var inAiBlock = false
    for (line in lines) {
        val trimmed = line.trim()
        if (inCode) {
            if (trimmed.startsWith("```")) inCode = false
            output += line
            continue
        }
        if (trimmed.startsWith("```")) {
            inCode = true
            output += line
            continue
        }
        if (inAiBlock) {
            if (trimmed == AI_BLOCK_END) {
                inAiBlock = false
                continue
            }
            output += line
            continue
        }
        if (trimmed == AI_BLOCK_START) {
            inAiBlock = true
            continue
        }
        output += line.replace(AI_INLINE_OPEN, "").replace(AI_INLINE_CLOSE, "")
    }
    return output.joinToString("\n")
}

// ---- 概念标注：`[[正文原文=>概念名]]`（约定见 docs/knowledge-base-draft.md §11） ----

/** `[[…]]` 里「原文 => 概念名」的分隔符；同时也接受 `→`（解析时统一）。 */
internal const val CONCEPT_SEPARATOR: String = "=>"

/** 概念库里一个概念的身份：规范名 + 别名。别名只为匹配，不会自动从正文学习。 */
internal data class ConceptIdentity(
    val name: String,
    val aliases: List<String> = emptyList(),
)

/**
 * 正文里一条 `[[…]]` 标注的原始解析结果；[start]/[end] 是正文绝对偏移（end 不含）。
 *
 * [display] 是显示/降级时要保留的原文（分隔符左边）；[target] 是分隔符右边的概念名，
 * 只有写了 `原文 => 概念名` 时才非空。
 */
internal data class ConceptAnnotation(
    val start: Int,
    val end: Int,
    val display: String,
    val target: String?,
)

private val conceptAnnotationPattern = Regex("\\[\\[([^\\[\\]\\n]*?)\\]\\]")
private val inlineAiSpanPattern = Regex("\\{\\{[^\\n]*?\\}\\}")
private val conceptSeparatorPattern = Regex("\\s*(?:=>|→)\\s*")

/**
 * 扫出正文里所有 `[[…]]` 标注。
 *
 * 和 AI 打标用同一套「哪里算普通正文」的规则：fenced code、`:::ai` 块、
 * `{{…}}`（AI 补全内容）里的 `[[…]]` 不算标注，原样当普通文字。
 * 表格行照常解析（分隔符用 `=>`，不会和表格里的 `|` 打架）。
 */
internal fun parseConceptAnnotations(body: String): List<ConceptAnnotation> {
    val text = body.replace("\r\n", "\n")
    if (!text.contains(CONCEPT_OPEN)) return emptyList()
    val result = ArrayList<ConceptAnnotation>()
    var lineStart = 0
    var inCode = false
    var inAiBlock = false
    for (line in text.split('\n')) {
        val trimmed = line.trimStart()
        val base = lineStart
        lineStart += line.length + 1
        when {
            trimmed.startsWith("```") -> {
                inCode = !inCode
                continue
            }
            inCode -> continue
            trimmed.startsWith(AI_BLOCK_START) -> {
                inAiBlock = true
                continue
            }
            trimmed == AI_BLOCK_END -> {
                inAiBlock = false
                continue
            }
            inAiBlock -> continue
        }
        if (!line.contains(CONCEPT_OPEN)) continue
        val aiSpans = inlineAiSpanPattern.findAll(line).map { it.range }.toList()
        for (match in conceptAnnotationPattern.findAll(line)) {
            if (aiSpans.any { match.range.first in it }) continue
            val parsed = parseConceptAnnotationContent(match.groupValues[1]) ?: continue
            result += ConceptAnnotation(
                start = base + match.range.first,
                end = base + match.range.last + 1,
                display = parsed.first,
                target = parsed.second,
            )
        }
    }
    return result
}

/** 把 `[[…]]` 里的内容拆成「原文 + 可选概念名」；空内容/空原文返回 null。 */
private fun parseConceptAnnotationContent(raw: String): Pair<String, String?>? {
    val pieces = raw.split(conceptSeparatorPattern, limit = 2)
    val display = pieces.firstOrNull()?.trim().orEmpty()
    if (display.isEmpty()) return null
    val target = pieces.getOrNull(1)?.let(::normalizeConceptName)?.takeIf { it.isNotEmpty() }
    return display to target
}

/** `[[…]]` 里要显示/保留的原文（分隔符左边）；没有分隔符时就是整段内容。 */
internal fun conceptAnnotationDisplay(content: String): String {
    val pieces = content.split(conceptSeparatorPattern, limit = 2)
    val display = pieces.firstOrNull()?.trim().orEmpty()
    return if (display.isEmpty()) content.trim() else display
}

/**
 * `[[…]]` 内容里从哪个下标开始是「只管解析、不该显示」的部分（`=> 概念名`）。
 *
 * 返回值是内容内部的偏移；没有分隔符返回 -1。编辑态靠它把 `=> 名字` 一起藏掉。
 */
internal fun conceptAnnotationDisplayLength(content: String): Int {
    val match = conceptSeparatorPattern.find(content) ?: return -1
    return content.substring(0, match.range.first).trimEnd().length
}

/**
 * 把一个名字解析成概念库里的规范名。
 *
 * 顺序：规范名逐字（大小写不敏感）→ 别名 → 都没有就当新概念（用规范化后的原文）。
 * 返回空串表示这个名字没法用。
 */
internal fun resolveConceptName(raw: String, identities: List<ConceptIdentity>): String {
    val normalized = normalizeConceptName(raw)
    if (normalized.isEmpty()) return ""
    val key = conceptIdentityKey(normalized)
    identities.firstOrNull { conceptIdentityKey(it.name) == key }?.let { return it.name }
    identities.firstOrNull { identity -> identity.aliases.any { conceptIdentityKey(it) == key } }?.let { return it.name }
    return normalized
}

/** 一条标注指向的规范概念名（有 `=>` 看右边，否则看原文）。 */
internal fun conceptAnnotationName(annotation: ConceptAnnotation, identities: List<ConceptIdentity>): String =
    resolveConceptName(annotation.target ?: annotation.display, identities)

/** 正文标注出来的概念名（按出现顺序去重）——「本文概念」的唯一来源。 */
internal fun annotatedConceptNames(body: String, identities: List<ConceptIdentity>): List<String> =
    parseConceptAnnotations(body)
        .map { conceptAnnotationName(it, identities) }
        .filter { it.isNotEmpty() }
        .distinctBy(::conceptIdentityKey)

/**
 * 按正文标注重算「本文概念」和墓碑。
 *
 * - `concepts:` = 正文里 `[[…]]` 解析出的规范名（顺序按第一次出现）；
 * - 之前登记过、这次没有标注的概念，挪进 `removed-concepts:`（墓碑，可恢复）；
 * - 重新标注的概念从墓碑里移除；
 * - 从没出现过 `[[…]]`、也没有墓碑的老笔记原样返回（不做迁移，避免一保存就把旧概念清空）。
 */
internal fun synchronizeConceptMembership(note: KnowledgeNote, identities: List<ConceptIdentity>): KnowledgeNote {
    val text = note.body.replace("\r\n", "\n")
    val annotations = parseConceptAnnotations(text)
    if (annotations.isEmpty() && note.removedConcepts.isEmpty() && !text.contains(CONCEPT_OPEN)) return note
    val derived = annotations
        .map { conceptAnnotationName(it, identities) }
        .filter { it.isNotEmpty() }
        .distinctBy(::conceptIdentityKey)
    val derivedKeys = derived.map(::conceptIdentityKey).toSet()
    val claimed = LinkedHashMap<String, String>()
    for (old in note.concepts + note.removedConcepts) {
        val key = conceptIdentityKey(old)
        if (key.isNotEmpty()) claimed.putIfAbsent(key, normalizeConceptName(old))
    }
    val removed = claimed.filterKeys { it !in derivedKeys }.values.toList()
    if (derived == note.concepts && removed == note.removedConcepts) return note
    return note.copy(concepts = derived, removedConcepts = removed)
}

/**
 * AI 整理：把每条候选概念按 mention 在正文里第一次出现的位置包成标注。
 *
 * - mention 与规范名相同 → `[[名字]]`；
 * - 不同 → `[[正文原词 => 规范名]]`（AI 想换一种说法时，正文原词一个字符都不动）；
 * - mention 在正文里找不到、或正文已经有这个概念的标注 → 跳过（宁可漏标，不要标错）。
 */
internal fun markConceptAnnotations(body: String, candidates: List<ConceptCandidate>): String {
    if (candidates.isEmpty()) return body
    var text = body
    for (candidate in candidates) {
        val name = normalizeConceptName(candidate.name)
        if (name.isEmpty()) continue
        if (hasAnnotationFor(text, name)) continue
        val mention = candidate.mention.trim().ifBlank { name }
        if (mention.isEmpty()) continue
        val index = findPlainMention(text, mention) ?: continue
        val marker = if (conceptIdentityKey(mention) == conceptIdentityKey(name)) {
            CONCEPT_OPEN + mention + CONCEPT_CLOSE
        } else {
            CONCEPT_OPEN + mention + " " + CONCEPT_SEPARATOR + " " + name + CONCEPT_CLOSE
        }
        text = text.substring(0, index) + marker + text.substring(index + mention.length)
    }
    return text
}

/**
 * 只留下「正文里真的落成标注」的候选：mention 找不到的被丢掉，不该在概念库里留下孤儿定义。
 */
internal fun retainedCandidates(
    body: String,
    candidates: List<ConceptCandidate>,
    identities: List<ConceptIdentity>,
): List<ConceptCandidate> {
    if (candidates.isEmpty()) return emptyList()
    val annotated = annotatedConceptNames(body, identities).map(::conceptIdentityKey).toSet()
    return candidates.filter { conceptIdentityKey(it.name) in annotated }
}

/** 正文里是不是已经有一条指向 [name] 的标注（规范名或别名，大小写不敏感）。 */
private fun hasAnnotationFor(body: String, name: String): Boolean {
    val key = conceptIdentityKey(name)
    if (key.isEmpty()) return false
    for (match in conceptAnnotationPattern.findAll(body)) {
        val content = match.groupValues.getOrNull(1) ?: continue
        val pieces = content.split(conceptSeparatorPattern, limit = 2)
        val display = pieces.firstOrNull()?.trim().orEmpty()
        val target = pieces.getOrNull(1)?.trim().orEmpty()
        if (conceptIdentityKey(display) == key || conceptIdentityKey(target) == key) return true
    }
    return false
}

/** 把所有标注降级成纯文本（导出用）：`[[原文=>概念]]` 只剩「原文」。 */
internal fun stripAllConceptAnnotations(body: String): String {
    val text = body.replace("\r\n", "\n")
    val annotations = parseConceptAnnotations(text)
    if (annotations.isEmpty()) return text
    val builder = StringBuilder(text.length)
    var cursor = 0
    for (annotation in annotations) {
        if (annotation.start < cursor) continue
        builder.append(text, cursor, annotation.start)
        builder.append(annotation.display)
        cursor = annotation.end
    }
    builder.append(text, cursor, text.length)
    return builder.toString()
}

/**
 * 删除某个概念的标注：把指向它的标记降级成纯文本（保留原文），其它标记不动。
 *
 * 右栏的 × 走这里——概念从正文里消失，下一次 [synchronizeConceptMembership] 会把它挪进墓碑。
 */
internal fun stripConceptAnnotations(body: String, name: String, identities: List<ConceptIdentity>): String {
    val key = conceptIdentityKey(name)
    if (key.isEmpty()) return body
    val text = body.replace("\r\n", "\n")
    val annotations = parseConceptAnnotations(text)
    if (annotations.isEmpty()) return text
    val builder = StringBuilder(text.length)
    var cursor = 0
    var changed = false
    for (annotation in annotations) {
        if (annotation.start < cursor) continue
        builder.append(text, cursor, annotation.start)
        if (conceptIdentityKey(conceptAnnotationName(annotation, identities)) == key) {
            builder.append(annotation.display)
            changed = true
        } else {
            builder.append(text, annotation.start, annotation.end)
        }
        cursor = annotation.end
    }
    builder.append(text, cursor, text.length)
    return if (changed) builder.toString() else text
}

/**
 * 人工「添加概念」：优先把正文里第一次普通出现包成标注，正文没有就追加到文末。
 *
 * 命中的是别名时写成 `[[别名=>规范名]]`（显示仍是原文，但归属明确）；
 * 命中的就是规范名时写成 `[[规范名]]`。
 */
internal fun addConceptAnnotation(body: String, name: String, identities: List<ConceptIdentity>): String {
    val text = body.replace("\r\n", "\n")
    val canonical = resolveConceptName(name, identities)
    if (canonical.isEmpty()) return text
    val key = conceptIdentityKey(canonical)
    if (parseConceptAnnotations(text).any { conceptIdentityKey(conceptAnnotationName(it, identities)) == key }) return text
    val aliases = identities.firstOrNull { conceptIdentityKey(it.name) == key }?.aliases.orEmpty()
    val variants = (listOf(canonical) + aliases).filter { it.isNotBlank() }.distinctBy(::conceptIdentityKey)
    for (variant in variants) {
        val index = findPlainMention(text, variant) ?: continue
        val original = text.substring(index, index + variant.length)
        val marker = if (conceptIdentityKey(variant) == key) {
            CONCEPT_OPEN + original + CONCEPT_CLOSE
        } else {
            CONCEPT_OPEN + original + " " + CONCEPT_SEPARATOR + " " + canonical + CONCEPT_CLOSE
        }
        return text.substring(0, index) + marker + text.substring(index + variant.length)
    }
    return if (text.isBlank()) {
        CONCEPT_OPEN + canonical + CONCEPT_CLOSE
    } else {
        text.trimEnd() + "\n\n" + CONCEPT_OPEN + canonical + CONCEPT_CLOSE + "\n"
    }
}
