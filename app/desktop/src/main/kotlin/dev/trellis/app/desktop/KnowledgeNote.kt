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

/** 标题为空时列表/编辑器上显示的占位名。 */
internal fun displayTitle(title: String): String = title.trim().ifBlank { "无标题笔记" }

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
