package dev.trellis.app.desktop

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/** 概念详情：定义不在这里（[ConceptMetaStore]），这里只有引用与共现的相关概念。 */
internal data class ConceptDetail(
    val summary: ConceptSummary,
    val mentions: List<ConceptMention>,
    val related: List<ConceptSummary>,
)

/** 搜索命中：概念和笔记分两组，界面上也分两组显示。 */
internal data class SearchHits(
    val concepts: List<ConceptSummary>,
    val notes: List<NoteSummary>,
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

private val knownFrontMatterKeys = setOf("title", "created", "updated", "concepts")
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

/** 概念的去重/比较键：大小写不敏感。 */
internal fun conceptKey(name: String): String = normalizeConceptName(name).lowercase()

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

    return KnowledgeNote(
        id = id,
        title = title,
        body = body,
        createdAt = created,
        updatedAt = updated,
        concepts = concepts,
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

/** 槽位冒号前的标签长度上限。 */
private const val MAX_AI_SLOT_LABEL_CHARS: Int = 20

/** 正文里还没被 AI 补过的槽位。 */
internal data class AiFillSlot(val label: String)

/** 补全结果：新正文 + 这次真的补上的标签（顺序与正文一致）。 */
internal data class AiFillResult(val body: String, val applied: List<String>)

/**
 * 槽位语法：整行是 `标签：。。。`（可带 `- ` 列表前缀，允许中文/英文冒号）。
 *
 * `。。。` 必须在行尾——正文中间三个句号只是普通标点，不触发补全；
 * 代码块和 `:::ai` 块里的行也不算（见 [forEachPlainBodyLine]）。
 */
private val aiSlotPattern: Regex =
    Regex("^\\s*(?:[-*+]\\s+)?([^：:\\n]{1,24})[：:]\\s*" + Regex.escape(AI_SLOT_MARKER) + "\\s*$")

/** 一行是不是槽位；是就返回规范化后的标签。 */
internal fun aiFillSlotLabel(line: String): String? {
    val match = aiSlotPattern.matchEntire(line.trimEnd()) ?: return null
    val label = normalizeConceptName(match.groupValues[1])
    return label.takeIf { it.isNotEmpty() && it.length <= MAX_AI_SLOT_LABEL_CHARS }
}

/** 找出这篇笔记里所有待补全的槽位（去重、最多 [MAX_AI_FILL_SLOTS] 个，保持正文顺序）。 */
internal fun findAiFillSlots(body: String): List<AiFillSlot> {
    val slots = LinkedHashMap<String, AiFillSlot>()
    forEachPlainBodyLine(body) { _, line ->
        if (slots.size >= MAX_AI_FILL_SLOTS) return@forEachPlainBodyLine
        val label = aiFillSlotLabel(line) ?: return@forEachPlainBodyLine
        slots.putIfAbsent(conceptKey(label), AiFillSlot(label))
    }
    return slots.values.toList().take(MAX_AI_FILL_SLOTS)
}

/**
 * 把模型补出来的内容填回正文。
 *
 * 只替换行尾那一个 `。。。`，其它字符一字不动；填进去的内容包成 `{{…}}`，
 * 预览时渲染成绿色大引号。已经补过的行没有 `。。。` 了，所以再跑一次不会变。
 *
 * @param fills 标签 → 补全内容；标签比较大小写不敏感。
 */
internal fun applyAiFills(body: String, fills: Map<String, String>): AiFillResult {
    if (fills.isEmpty()) return AiFillResult(body, emptyList())
    val wanted = fills.entries
        .mapNotNull { (label, text) ->
            val key = conceptKey(label)
            val value = normalizeAiFillText(text)
            if (key.isEmpty() || value.isEmpty()) null else key to value
        }
        .toMap()
    if (wanted.isEmpty()) return AiFillResult(body, emptyList())

    val lines = body.replace("\r\n", "\n").split('\n').toMutableList()
    val applied = mutableListOf<String>()
    forEachPlainBodyLine(body) { index, line ->
        val label = aiFillSlotLabel(line) ?: return@forEachPlainBodyLine
        val text = wanted[conceptKey(label)] ?: return@forEachPlainBodyLine
        lines[index] = line.trimEnd()
            .replaceFirst(AI_SLOT_MARKER, AI_INLINE_OPEN + text + AI_INLINE_CLOSE)
        applied += label
    }
    if (applied.isEmpty()) return AiFillResult(body, emptyList())
    return AiFillResult(lines.joinToString("\n"), applied)
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

/** 把模型输出整理成能塞进 `{{…}}` 的一行字：不加换行、不套娃、不超长。 */
internal fun normalizeAiFillText(raw: String): String =
    raw.replace("\r\n", " ")
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
